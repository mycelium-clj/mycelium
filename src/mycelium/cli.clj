(ns mycelium.cli
  "Command-line interface exposing the manifest query layer to agents.
   Every command is a scoped read over an EDN manifest — the manifest is the
   program database, this is the agent's door into it.

   Exit codes: 0 success, 1 validation/read failure, 3 status not green,
   64 usage error, 66 manifest file missing."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.manifest :as manifest]
            [mycelium.orchestrate :as orch]
            [mycelium.patch :as patch]
            [sci.core :as sci]))

;; ===== bundled skills =====

(def ^:private skill-topics
  "Topic name → resource path. Files live in resources/mycelium/skills/."
  {"agent"    "mycelium/skills/agent.md"
   "manifest" "mycelium/skills/manifest.md"
   "cells"    "mycelium/skills/cells.md"
   "testing"  "mycelium/skills/testing.md"
   "patterns" "mycelium/skills/patterns.md"})

(defn- split-frontmatter
  "Returns [frontmatter-map body]. Frontmatter is the leading `---` block of
   `key: value` lines; absent → [{} s]."
  [s]
  (if (str/starts-with? s "---\n")
    (if-let [end (str/index-of s "\n---" 4)]
      (let [fm (into {}
                     (keep (fn [line]
                             (when-let [[_ k v] (re-matches #"([\w-]+):\s*(.*)" line)]
                               [k (str/trim v)])))
                     (str/split-lines (subs s 4 end)))]
        [fm (str/triml (subs s (+ end 4)))])
      [{} s])
    [{} s]))

(defn- extract-section
  "Returns the section with the given {#id} anchor: its heading line and body
   up to the next heading of the same or higher level."
  [content section-id]
  (let [lines   (str/split-lines content)
        anchor  (str "{#" section-id "}")
        level   (fn [l] (count (second (re-matches #"(#+) .*" l))))
        start   (some (fn [i] (when (str/includes? (nth lines i) anchor) i))
                      (range (count lines)))]
    (when-not start
      (throw (ex-info (str "Unknown section: " section-id ". Available: "
                           (str/join ", " (keep #(second (re-find #"\{#([^}]+)\}" %)) lines)))
                      {:section section-id})))
    (let [own  (level (nth lines start))
          body (reduce (fn [acc l]
                         (if (and (re-matches #"#+ .*" l) (<= (level l) own))
                           (reduced acc)
                           (conj acc l)))
                       []
                       (drop (inc start) lines))]
      (str/trimr (str/join "\n" (cons (nth lines start) body))))))

(defn- skill-source
  [topic]
  (if-let [path (get skill-topics topic)]
    (slurp (io/resource path))
    (throw (ex-info (str "Unknown skill topic: " topic
                         ". Available: " (str/join ", " (sort (keys skill-topics))))
                    {:exit 1}))))

(defn- skill-content
  "Loads a skill topic with frontmatter stripped."
  [topic]
  (second (split-frontmatter (skill-source topic))))

(defn- skill-listing
  "One line per topic: name, served size, description from frontmatter."
  []
  (str/join "\n"
            (for [t (sort (keys skill-topics))
                  :let [src (skill-source t)
                        [fm body] (split-frontmatter src)
                        kb  (/ (Math/round (/ (count body) 100.0)) 10.0)]]
              (format "  %-9s (~%s KB) %s" t kb (get fm "description" "")))))

;; ===== usage =====

(def usage
  "Usage: myc <command> <manifest-path> [args] [--json] [--require <ns>]

Commands:
  validate <path> [--lenient]  validate manifest structure, exit 0/1
                               (strict like load-manifest: every cell needs :on-error)
  hash <path>                  content hash of the file (sha-256, canonical EDN)
  status <path>                implementation status per cell; exit 3 unless all passing
  test <path> <cell> [--input <edn>]
                               run one cell in isolation, print result; exit 3 on failure
  brief <path> <cell>          self-contained implementation brief for one cell
  briefs <path>                briefs for every cell
  region <path> <name>         scoped brief for a :regions cluster
  refs <path> <cell>           every place a cell is referenced
  plan <path>                  execution plan (scaffold, parallel groups)
  paths <path>                 enumerate all start-to-terminal paths
  schema <path>                accumulated data keys at each cell
  dot <path>                   DOT graph for visualization
  patch <path> --op <name> [--<arg> <value> ...] [--expect-hash <h>] [--dry-run]
                               checked edit; `myc patch --op help` lists ops
  skills [get <topic> [--section <id>]]
                               bundled skill content (agent loop docs)

Flags:
  --json                       machine-readable output: {\"ok\": bool, \"exit\": n, ...}
  --require <ns>               load a namespace (may repeat) before querying, so
                               registered cell handlers are visible to status/test/schema")

;; ===== argv parsing =====

(def ^:private valued-flags
  "Flags that consume the next token. Anything else starting with -- is a
   boolean flag, except inside an --op group where --key value pairs are op args."
  #{"--require" "--section" "--expect-hash" "--input"})

(defn- parse-argv
  "Splits argv into {:pos [...] :flags #{...} :vals {flag [v ...]} :ops [...]}.
   Op groups: `--op NAME [--arg value ...]` up to the next --op or known flag."
  [args]
  (loop [args (seq args) acc {:pos [] :flags #{} :vals {} :ops []}]
    (if-not args
      acc
      (let [[a & more] args]
        (cond
          (= "--op" a)
          (let [op-name (first more)
                _ (when (or (nil? op-name) (str/starts-with? op-name "--"))
                    (throw (ex-info "--op requires an op name" {:exit 64})))
                [op-args remaining]
                (loop [xs (rest more) op {:op op-name}]
                  (let [[k v] xs]
                    (if (and k (str/starts-with? k "--") (not= "--op" k)
                             (not (valued-flags k)) (not= "--json" k)
                             (not= "--dry-run" k) (not= "--lenient" k))
                      (do (when (or (nil? v) (str/starts-with? v "--"))
                            (throw (ex-info (str k " requires a value") {:exit 64})))
                          (recur (drop 2 xs) (assoc op (keyword (subs k 2)) v)))
                      [op xs])))]
            (recur (seq remaining) (update acc :ops conj op-args)))

          (valued-flags a)
          (let [v (first more)]
            (when (or (nil? v) (str/starts-with? v "--"))
              (throw (ex-info (str a " requires a value") {:exit 64})))
            (recur (next more) (update-in acc [:vals a] (fnil conj []) v)))

          (str/starts-with? a "--")
          (recur more (update acc :flags conj a))

          :else
          (recur more (update acc :pos conj a)))))))

(defn- ->cell-kw
  "Cell name from a CLI token: `start`, `:start`, `auth/validate` all work."
  [s]
  (cond
    (keyword? s) s
    (nil? s)     nil
    :else        (keyword (if (str/starts-with? s ":") (subs s 1) s))))

;; ===== JSON =====

(defn- jsonable
  "Rewrites a value so data.json can emit it losslessly: keywords keep their
   namespace, sets become vectors, code forms and other opaque values become
   their printed form."
  [x]
  (let [kw->str (fn [k] (if (namespace k) (str (namespace k) "/" (name k)) (name k)))]
    (walk/prewalk
     (fn [v]
       (cond
         (map? v)     (into {} (map (fn [[k val]]
                                      [(cond (keyword? k) (kw->str k)
                                             (string? k)  k
                                             :else        (pr-str k))
                                       val]))
                            v)
         (keyword? v) (kw->str v)
         (set? v)     (vec v)
         (symbol? v)  (str v)
         (seq? v)     (pr-str v)
         (fn? v)      (pr-str v)
         (and (double? v) (or (Double/isNaN v) (Double/isInfinite v))) (str v)
         (or (nil? v) (string? v) (number? v) (boolean? v) (vector? v)) v
         :else        (pr-str v)))
     x)))

(defn- ->json [x] (json/write-str (jsonable x)))

;; ===== manifest loading =====

(defn- read-raw
  "Reads a manifest file as EDN without expanding or validating. Throws
   ex-info with :exit 66 (missing) or 1 (unparseable)."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "No such file: " path) {:exit 66})))
    (try
      (edn/read-string (slurp f))
      (catch Exception e
        (throw (ex-info (str "Read error: " (ex-message e)) {:exit 1 :cause e}))))))

(defn- read-manifest
  "Reads, expands fragments, and validates a manifest. Throws ex-info with
   :exit on failure. opts as for load-manifest (default lenient)."
  ([path] (read-manifest path {:strict? false}))
  ([path opts]
   (let [raw (read-raw path)]
     (try
       (manifest/expand-manifest raw path opts)
       (catch Exception e
         (throw (ex-info (str "Invalid manifest: " (ex-message e))
                         {:exit 1 :data (ex-data e) :cause e})))))))

(defn- requires->load!
  "Loads --require namespaces so registered handlers are queryable."
  [namespaces]
  (doseq [n namespaces]
    (try
      (require (symbol n))
      (catch Throwable t
        (throw (ex-info (str "Could not require " n ": " (ex-message t))
                        {:exit 1}))))))

;; ===== output formatting =====

(defn- format-path
  [steps]
  ;; steps chain: each step's target is the next step's cell, so only the
  ;; first step names its source cell: ":a --x-> :b --y-> :end"
  (str/join " "
            (map-indexed (fn [i {:keys [cell transition target]}]
                           (str (when (zero? i) (str cell " "))
                                (if (= :unconditional transition)
                                  "--> "
                                  (str "--" (name transition) "-> "))
                                target))
                         steps)))

(defn- format-status
  [{:keys [total implemented passing pending cells] :as _status} id]
  (str "Workflow: " id "\n"
       "Status: " passing "/" total " passing"
       " | " total " total | " implemented " implemented | " pending " pending\n"
       (str/join "\n" (map (fn [{:keys [name id status error errors]}]
                             (str (case status
                                    :passing "[PASS] "
                                    :failing "[FAIL] "
                                    :pending "[]     ")
                                  name " (" id ")"
                                  (when (= :failing status)
                                    (str " — " (or error
                                                   (some-> errors first :detail))))))
                           cells))))

(defn- format-refs
  [cell refs]
  (str cell " — " (count refs) " reference" (when (not= 1 (count refs)) "s")
       (when (seq refs) "\n")
       (str/join "\n" (map (fn [{:keys [role path]}]
                             (format "  %-20s %s" (name role) (str/join " " (map pr-str path))))
                           refs))))

(defn- format-test-result
  [cell-name cell-id input {:keys [pass? errors output duration-ms matched-dispatch]}]
  (str "Cell: " cell-name " (" cell-id ")\n"
       "Input: " (pr-str input) "\n"
       "Result: " (if pass? "PASS" "FAIL") "\n"
       "Matched dispatch: " (pr-str matched-dispatch) "\n"
       "Output: " (pr-str output) "\n"
       (when (seq errors)
         (str "Errors:\n"
              (str/join "\n" (map (fn [{:keys [phase detail]}]
                                    (str "  [" (name phase) "] " (if (string? detail) detail (pr-str detail))))
                                  errors))
              "\n"))
       "Duration: " (format "%.1f" (double (or duration-ms 0))) "ms"))

(defn- format-op-help
  []
  (str "Patch ops (myc patch <path> --op <name> [--<arg> <value> ...]):\n"
       (str/join "\n"
                 (for [[op-name {:keys [doc args]}] (sort (patch/ops))]
                   (str "  " op-name "\n    " doc "\n"
                        (str/join "\n" (for [arg args]
                                         (format "    --%-10s %s" (name (:name arg)) (:doc arg)))))))))

;; ===== commands =====

(defn- coerce-op
  "Turns a parsed `--op NAME --k v` group into the map patch/apply-op expects,
   using the op registry's arg types. Unknown ops pass through so patch
   reports them."
  [{:keys [op] :as group}]
  (if-let [spec (get (patch/ops) op)]
    (reduce (fn [m {arg-name :name type :type}]
              (let [v (get group arg-name)]
                (when (nil? v)
                  (throw (ex-info (str "--op " op " requires --" (name arg-name)
                                       " (" (str/join ", " (map #(str "--" (name (:name %))) (:args spec))) ")")
                                  {:exit 64})))
                (assoc m arg-name (case type :cell (->cell-kw v) v))))
            {:op op}
            (:args spec))
    group))

(defn- write-atomically!
  "Writes text to path via a sibling temp file + atomic move."
  [path text]
  (let [target (.toPath (.getAbsoluteFile (io/file path)))
        tmp    (java.nio.file.Files/createTempFile (.getParent target) ".myc-" ".tmp"
                                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile tmp) text)
      (java.nio.file.Files/move tmp target
                                (into-array java.nio.file.CopyOption
                                            [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                             java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (java.nio.file.Files/deleteIfExists tmp)))))

(defn- run-patch
  [path {:keys [flags vals ops]}]
  (cond
    (some #(= "help" (:op %)) ops)
    {:exit 0 :message (format-op-help) :data {:ops (patch/ops)}}

    (nil? path)
    {:exit 64 :message "Usage: myc patch <path> --op <name> [--<arg> <value> ...] [--expect-hash <h>] [--dry-run]"}

    (empty? ops)
    {:exit 64 :message "Usage: myc patch <path> --op <name> [--<arg> <value> ...] [--expect-hash <h>] [--dry-run]\nRun `myc patch --op help` to list ops."}

    :else
    (let [dry-run?    (contains? flags "--dry-run")
          expect-hash (first (get vals "--expect-hash"))
          ops         (mapv coerce-op ops)
          raw         (read-raw path)
          text        (slurp path)
          result      (try
                        (patch/apply-ops raw {:expect-hash  expect-hash
                                              :ops          ops
                                              :fragment-dir (manifest/manifest-dir path)})
                        (catch clojure.lang.ExceptionInfo e
                          (throw (ex-info (ex-message e) (assoc (ex-data e) :exit 1) e))))
          new-hash    (patch/manifest-hash result)
          data        {:path path :hash new-hash :written (not dry-run?)
                       :ops (map :op ops)}]
      (if dry-run?
        {:exit 0 :data data
         :message (str "dry run ok — would write " path " (new hash " new-hash ")")}
        (do (write-atomically! path (patch/render text raw result))
            {:exit 0 :data data
             :message (str "patch ok: wrote " path " (hash " new-hash ")")})))))

(defn- run-skills
  [{:keys [pos vals]}]
  (let [section (first (get vals "--section"))]
    (cond
      (empty? pos)
      {:exit 0
       :data {:topics (sort (keys skill-topics))}
       :message (str "Skill topics (myc skills get <topic> [--section <id>]):\n"
                     (skill-listing))}

      (= "get" (first pos))
      (let [topic (second pos)]
        (if (nil? topic)
          {:exit 64 :message "Usage: myc skills get <topic> [--section <id>]"}
          (let [content (skill-content topic)
                out     (if section (extract-section content section) content)]
            {:exit 0 :message out :data {:topic topic :section section :content out}})))

      :else {:exit 64 :message "Usage: myc skills [get <topic> [--section <id>]]"})))

(defn- compile-dispatches
  "Manifest dispatch predicates are EDN forms; evaluate them the way the
   runtime does (SCI) so `myc test` reports the matched label."
  [dispatches]
  (when (seq dispatches)
    (let [ctx (sci/init {})]
      (mapv (fn [[label pred]] [label (if (ifn? pred) pred (sci/eval-form ctx pred))])
            dispatches))))

(defn- run-test
  [m path {:keys [pos vals]}]
  (let [cell-name (->cell-kw (second pos))]
    (cond
      (nil? cell-name)
      {:exit 64 :message "Usage: myc test <path> <cell-name> [--input <edn>] [--require <ns>]"}

      (not (contains? (:cells m) cell-name))
      {:exit 1 :message (str "Cell " cell-name " not found in manifest " path
                             ". Cells: " (str/join ", " (map pr-str (keys (:cells m)))))}

      :else
      (let [cell-id (get-in m [:cells cell-name :id])
            cell    (cell/get-cell cell-id)]
        (if-not cell
          {:exit 1 :message (str "Cell " cell-name " (" cell-id ") has no registered handler"
                                 " — load its namespace with --require <ns>")}
          (let [input  (if-let [s (first (get vals "--input"))]
                         (edn/read-string s)
                         (dev/generate-test-input cell {}))
                result (dev/test-cell cell-id {:input      input
                                               :resources  {}
                                               :dispatches (compile-dispatches
                                                            (get-in m [:dispatches cell-name]))})]
            {:exit (if (:pass? result) 0 3)
             :data (assoc result :cell cell-name :id cell-id :input input)
             :message (format-test-result cell-name cell-id input result)}))))))

(defn- run-query
  "Read-only commands over a loaded manifest."
  [cmd m path {:keys [pos] :as _parsed}]
  (let [arg (->cell-kw (second pos))]
    (case cmd
      "validate" {:exit 0 :message (str "manifest ok: " (:id m)) :data {:id (:id m)}}
      "status"   (let [status (dev/workflow-status m)
                       exit   (if (and (pos? (:total status))
                                       (= (:total status) (:passing status)))
                                0 3)]
                   {:exit exit :data status :message (format-status status (:id m))})
      "brief"    (if (nil? arg)
                   {:exit 64 :message "Usage: myc brief <path> <cell-name>"}
                   (let [prompt (:prompt (manifest/cell-brief m arg))]
                     {:exit 0 :message prompt :data {:cell arg :brief prompt}}))
      "briefs"   (let [briefs (orch/cell-briefs m)]
                   {:exit 0 :data {:briefs briefs}
                    :message (str/join "\n\n---\n\n" (vals briefs))})
      "region"   (if (nil? arg)
                   {:exit 64 :message "Usage: myc region <path> <region-name>"}
                   (let [prompt (:prompt (orch/region-brief m arg))]
                     {:exit 0 :message prompt :data {:region arg :brief prompt}}))
      "refs"     (cond
                   (nil? arg)
                   {:exit 64 :message "Usage: myc refs <path> <cell-name>"}
                   (not (or (contains? (:cells m) arg) (contains? (:joins m) arg)
                            (contains? (into {} (keep (fn [[_ fm]] (when (:as fm) [(:as fm) 1]))) (:fragments m)) arg)
                            (#{:end :error :halt} arg)))
                   {:exit 1 :message (str "Cell " arg " not found in manifest " path)}
                   :else
                   (let [refs (patch/cell-refs m arg)]
                     {:exit 0 :data {:cell arg :refs refs} :message (format-refs arg refs)}))
      "plan"     (let [{:keys [scaffold parallel sequential] :as plan} (orch/plan m)]
                   {:exit 0 :data plan
                    :message (str "cells: " (str/join ", " scaffold) "\n"
                                  "parallel groups: " (count parallel)
                                  (when (= 1 (count parallel))
                                    " — all cells are independent (runtime data deps only)")
                                  "\n"
                                  "sequential: " (if (seq sequential) (str/join ", " sequential) "none")
                                  "\n")})
      "paths"    (let [paths (dev/enumerate-paths m)]
                   {:exit 0 :data {:paths paths :count (count paths)}
                    :message (str (str/join "\n" (map format-path paths))
                                  "\n" (count paths) " paths")})
      "schema"   (let [s (dev/infer-workflow-schema m)]
                   {:exit 0 :message (pr-str s) :data {:schema s}})
      "dot"      (let [d (dev/workflow->dot m)]
                   {:exit 0 :message d :data {:dot d}})
      (throw (ex-info (str "Unknown command: " cmd "\n\n" usage) {:exit 64})))))

(def ^:private raw-commands
  "Commands that read the file without validating it."
  #{"hash"})

(defn- dispatch
  [cmd {:keys [pos flags vals] :as parsed}]
  (let [path (first pos)]
    (cond
      (= cmd "help")   {:exit 0 :message usage}
      (= cmd "skills") (run-skills parsed)
      (= cmd "patch")  (do (requires->load! (get vals "--require"))
                           (run-patch path parsed))
      (nil? path)      (throw (ex-info (str "Usage: myc " cmd " <manifest-path> [...]\n\n" usage)
                                       {:exit 64}))
      (raw-commands cmd)
      (let [h (patch/manifest-hash (read-raw path))]
        {:exit 0 :message h :data {:hash h}})

      :else
      (do (requires->load! (get vals "--require"))
          (let [strict? (and (= cmd "validate") (not (contains? flags "--lenient")))
                m       (read-manifest path {:strict? strict?})]
            (if (= cmd "test")
              (run-test m path parsed)
              (run-query cmd m path parsed)))))))

(defn run
  "Runs the CLI against argv. Returns {:exit n :message s}; never calls
   System/exit — callers (-main, tests) decide what to do with the result.
   With --json, :message is a JSON object: {\"ok\" bool \"exit\" n ...} carrying
   the command's structured :data on success or \"error\" + \"data\" on failure."
  [argv]
  (let [[cmd & rest-args] argv
        json?  (some #{"--json"} rest-args)
        result (try
                 (if (nil? cmd)
                   {:exit 64 :message usage}
                   (dispatch cmd (parse-argv rest-args)))
                 (catch clojure.lang.ExceptionInfo e
                   {:exit (or (:exit (ex-data e)) 1)
                    :message (ex-message e)
                    :error? true
                    :error-data (dissoc (ex-data e) :exit :cause)})
                 (catch Exception e
                   {:exit 1 :message (str "Error: " (ex-message e)) :error? true}))
        {:keys [exit message data error? error-data]} result]
    (if json?
      {:exit exit
       :message (->json (merge {:ok (zero? exit) :exit exit}
                               (cond
                                 error? {:error message :data (or (:data error-data) error-data)}
                                 data   data
                                 :else  {:output message})))}
      {:exit exit :message message})))

(defn -main
  [& argv]
  (let [{:keys [exit message]} (run argv)]
    (if (zero? exit)
      (println message)
      (binding [*out* *err*]
        (println message)))
    (System/exit exit)))

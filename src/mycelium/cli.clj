(ns mycelium.cli
  "Command-line interface exposing the manifest query layer to agents.
   Every command is a scoped read over an EDN manifest — the manifest is the
   program database, this is the agent's door into it.

   Exit codes: 0 success, 1 validation/read failure, 3 status not green,
   64 usage error, 66 manifest file missing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [mycelium.dev :as dev]
            [mycelium.manifest :as manifest]
            [mycelium.orchestrate :as orch]
            [mycelium.patch :as patch]))

;; ===== bundled skills =====

(def ^:private skill-topics
  "Topic name → resource path. Files live in resources/mycelium/skills/."
  {"agent"    "mycelium/skills/agent.md"
   "manifest" "mycelium/skills/manifest.md"
   "cells"    "mycelium/skills/cells.md"
   "testing"  "mycelium/skills/testing.md"
   "patterns" "mycelium/skills/patterns.md"})

(defn- strip-frontmatter
  [s]
  (if (str/starts-with? s "---\n")
    (let [end (str/index-of s "\n---" 4)]
      (if end (subs s (+ end 4)) s))
    s))

(defn- extract-section
  "Returns the body of section with the given {#id} anchor, up to the next
   heading of the same or higher level."
  [content section-id]
  (let [lines  (str/split content #"\n")
        anchor (str "{#" section-id "}")
        start  (some (fn [i] (when (str/includes? (nth lines i "") anchor) i))
                     (range (count lines)))]
    (when-not start
      (throw (ex-info (str "Unknown section: " section-id)
                      {:section section-id
                       :available (keep #(second (re-find #"\{#([^}]+)\}" %)) lines)})))
    (let [level     (count (re-find #"^#+ " (nth lines start)))
          body      (drop start lines)
          take-until (fn [ls]
                       (reduce (fn [acc l]
                                 (if (and (re-matches #"#+ .*" l)
                                          (< level (count (re-find #"^#+ " l))))
                                   (reduced acc)
                                   (conj acc l)))
                               [] ls))]
      (str/triml (str/join "\n" (rest (take-until body)))))))

(defn- skill-content
  "Loads a skill topic; strips YAML frontmatter. Throws with :exit 1 and the
   topic list when unknown."
  [topic]
  (if-let [path (get skill-topics topic)]
    (-> (io/resource path) slurp strip-frontmatter)
    (throw (ex-info (str "Unknown skill topic: " topic
                         ". Available: " (str/join ", " (sort (keys skill-topics))))
                    {:exit 1}))))

(def usage
  "Usage: myc <command> <manifest-path> [args] [--json]

Commands:
  validate <path>              validate manifest structure, exit 0/1
  hash <path>                  deterministic content hash (sha-256, canonical EDN)
  status <path> [--json]       implementation status per cell; exit 3 unless all passing
  brief <path> <cell>          self-contained implementation brief for one cell
  briefs <path>                briefs for every cell
  region <path> <name>         scoped brief for a :regions cluster
  plan <path>                  execution plan (scaffold, parallel groups)
  paths <path>                 enumerate all start-to-terminal paths
  schema <path>                accumulated data keys at each cell
  dot <path>                   DOT graph for visualization
  skills get <topic>           print bundled skill content (agent loop docs)

  --require <ns>               load a namespace (may repeat) before querying, so
                               registered cell handlers are visible to status/schema")

(defn- json-encode [x]
  (cond
    (nil? x)       "null"
    (boolean? x)   (str x)
    (number? x)    (str x)
    (string? x)    (pr-str x)
    (keyword? x)   (pr-str (if (namespace x)
                             (str (namespace x) "/" (name x))
                             (name x)))
    (map? x)       (str "{"
                        (str/join ", " (map (fn [[k v]]
                                              (str (json-encode (if (keyword? k) (name k) k))
                                                   ": " (json-encode v)))
                                            x))
                        "}")
    (sequential? x) (str "[" (str/join ", " (map json-encode x)) "]")
    :else          (pr-str (str x))))

;; ===== canonical hashing =====

(defn- canonical-form
  "Recursively converts a manifest to a key-order-insensitive canonical form:
   maps sorted by key string, sequences kept in order."
  [x]
  (cond
    (map? x)       (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
                         (map (fn [[k v]] [(canonical-form k) (canonical-form v)]))
                         x)
    (sequential? x) (mapv canonical-form x)
    (set? x)        (into (sorted-set-by (fn [a b] (compare (pr-str a) (pr-str b))))
                          (map canonical-form x))
    :else x))

(defn manifest-hash
  "Deterministic sha-256 hex of a manifest, insensitive to key order and
   whitespace. Dispatch predicate forms hash as structure, not functions."
  [m]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical-form m)) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;; ===== manifest loading =====

(defn- read-manifest
  "Reads, expands fragments, and validates a manifest via load-manifest.
   Throws ex-info with :exit on failure."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "No such file: " path) {:exit 66})))
    (try
      (manifest/load-manifest path {:strict? false})
      (catch Exception e
        (throw (ex-info (str "Read error: " (ex-message e))
                        {:exit 1 :cause e}))))))

(defn- requires->load!
  "Loads --require namespaces so registered handlers are queryable."
  [namespaces]
  (doseq [n namespaces]
    (try
      (require (symbol n))
      (catch Throwable t
        (throw (ex-info (str "Could not require " n ": " (ex-message t))
                        {:exit 1}))))))

(defn- parse-requires
  "Pulls the value following each --require flag from raw args."
  [raw-args]
  (keep (fn [[a b]] (when (= "--require" a) b))
        (partition-all 2 1 raw-args)))

(defn- cli-patch-ops
  "Parses repeated `--op NAME [--from X] [--to Y]` groups from patch argv."
  [raw-args]
  (loop [args raw-args ops []]
    (cond
      (empty? args)          ops
      (not= "--op" (first args)) (recur (rest args) ops)
      :else                  (let [op-name (second args)
                                   after-op (drop 2 args)
                                   from (when (= "--from" (first after-op)) (second after-op))
                                   after-from (if from (drop 2 after-op) after-op)
                                   to (when (= "--to" (first after-from)) (second after-from))
                                   after-to (if to (drop 2 after-from) after-from)]
                              (recur after-to
                                     (conj ops (cond-> {:op op-name}
                                                 from (assoc :from (keyword from))
                                                 to (assoc :to (keyword to)))))))))

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

;; ===== command dispatch =====

(defn run
  "Runs the CLI against argv. Returns {:exit n :message s}; never calls
   System/exit — callers (-main, tests) decide what to do with the result."
  [argv]
  (try
    (let [[cmd & rest-args] argv]
      (if (nil? cmd)
        {:exit 64 :message usage}
        (let [flags    (set (filter #(str/starts-with? % "--") rest-args))
              pos      (vec (remove #(str/starts-with? % "--") rest-args))
              json?    (contains? flags "--json")
              requires (parse-requires rest-args)
              [path & command-args] pos]
          (cond
            (= cmd "help") {:exit 0 :message usage}
            (= cmd "skills")
            (let [args (vec (remove #(str/starts-with? % "--") rest-args))
                  section (second (drop-while #(not= "--section" %) rest-args))]
              (cond
                (= args [])
                {:exit 0
                 :message (str "Skill topics (myc skills get <topic> [--section <id>]):\n"
                               (str/join "\n" (for [t (sort (keys skill-topics))]
                                                 (str "  " t))))}

                (= (first args) "get")
                (let [topic (second args)]
                  (if (nil? topic)
                    {:exit 64 :message "Usage: myc skills get <topic> [--section <id>]"}
                    (try
                      (let [content (skill-content topic)]
                        {:exit 0
                         :message (if section
                                    (extract-section content section)
                                    content)})
                      (catch clojure.lang.ExceptionInfo e
                        {:exit 1 :message (ex-message e)}))))

                :else {:exit 64 :message "Usage: myc skills [get <topic> [--section <id>]]"}))

            (nil? path)
            (throw (ex-info (str "Usage: myc " cmd " <manifest-path> [...]") {:exit 64}))

            (= cmd "patch")
            (let [dry-run?    (contains? flags "--dry-run")
                  expect-hash (second (drop-while #(not= "--expect-hash" %) rest-args))
                  ops         (cli-patch-ops rest-args)]
              (if (empty? ops)
                {:exit 64 :message "Usage: myc patch <path> --op rename-cell --from <old> --to <new> [--expect-hash <h>] [--dry-run]"}
                (let [m (read-manifest path)]
                  (try
                    (let [result (patch/apply-ops m {:expect-hash expect-hash :ops ops})
                          new-hash (manifest-hash result)]
                      (if dry-run?
                        {:exit 0
                         :message (str "dry run ok — would write " path
                                       " (new hash " new-hash ")")}
                        (do (spit path (pr-str result))
                            {:exit 0
                             :message (str "patch ok: wrote " path
                                           " (hash " new-hash ")")})))
                    (catch clojure.lang.ExceptionInfo e
                      {:exit 1 :message (ex-message e)})))))

            :else
            (do (requires->load! requires)
                (let [m (read-manifest path)]
                  (case cmd
                    "validate" {:exit 0 :message (str "manifest ok: " (:id m))}
                    "hash"     {:exit 0 :message (manifest-hash m)}
                    "status"   (let [status (dev/workflow-status m)
                                     exit   (if (and (pos? (:total status))
                                                     (= (:total status) (:passing status)))
                                              0 3)]
                                 (if json?
                                   {:exit exit :message (json-encode status)}
                                   {:exit exit :message (format-status status (:id m))}))
                    "brief"    (let [cell (some-> command-args first keyword)]
                                 (if (nil? cell)
                                   {:exit 64 :message "Usage: myc brief <path> <cell-name>"}
                                   (try
                                     {:exit 0 :message (:prompt (manifest/cell-brief m cell))}
                                     (catch Exception e
                                       {:exit 1 :message (ex-message e)}))))
                    "briefs"   {:exit 0 :message (str/join "\n\n---\n\n"
                                                          (vals (orch/cell-briefs m)))}
                    "region"   (let [region (some-> command-args first keyword)]
                                 (if (nil? region)
                                   {:exit 64 :message "Usage: myc region <path> <region-name>"}
                                   (try
                                     {:exit 0 :message (:prompt (orch/region-brief m region))}
                                     (catch Exception e
                                       {:exit 1 :message (ex-message e)}))))
                    "plan"     (let [{:keys [scaffold parallel]} (orch/plan m)]
                                 {:exit 0
                                  :message (str "cells: " (str/join ", " scaffold) "\n"
                                                "parallel groups: " (count parallel) " — "
                                                "all cells are independent "
                                                "(runtime data deps only)\n"
                                                "sequential: none\n")})
                    "paths"    (let [paths (dev/enumerate-paths m)]
                                 {:exit 0
                                  :message (str (str/join "\n" (map format-path paths))
                                                "\n" (count paths) " paths")})
                    "schema"   {:exit 0 :message (pr-str (dev/infer-workflow-schema m))}
                    "dot"      {:exit 0 :message (dev/workflow->dot m)}
                    (throw (ex-info (str "Unknown command: " cmd "\n\n" usage)
                                    {:exit 64})))))))))
    (catch clojure.lang.ExceptionInfo e
      {:exit (or (:exit (ex-data e)) 1) :message (ex-message e)})
    (catch Exception e
      {:exit 1 :message (str "Error: " (ex-message e))})))

(defn -main
  [& argv]
  (let [{:keys [exit message]} (run argv)]
    (if (zero? exit)
      (println message)
      (binding [*out* *err*]
        (println message)))
    (System/exit exit)))

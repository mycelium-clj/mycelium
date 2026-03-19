(ns mycelium.dev
  "Development utilities: cell testing, scaffolding, visualization."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.provider :as mp]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(defn test-cell
  "Runs a single cell in isolation with full schema validation.
   Options:
     :input               - input data map
     :resources           - resources map (default {})
     :dispatches          - [[label pred-fn] ...] for dispatch-aware output validation
     :expected-dispatch   - if set, verifies the matched dispatch label
   Returns:
     {:pass? bool :errors [...] :output {...} :duration-ms n :matched-dispatch kw-or-nil}"
  [cell-id {:keys [input resources dispatches expected-dispatch] :or {resources {}}}]
  (let [cell       (cell/get-cell! cell-id)
        start-time (System/nanoTime)
        ;; Validate input
        input-err  (schema/validate-input cell input)]
    (if input-err
      {:pass?       false
       :errors      [{:phase :input :detail input-err}]
       :output      nil
       :duration-ms 0}
      (try
        (let [output      ((:handler cell) resources input)
              duration-ms (/ (- (System/nanoTime) start-time) 1e6)
              ;; Determine matched dispatch label first
              matched     (when dispatches
                            (some (fn [[label pred]]
                                    (when (pred output) label))
                                  dispatches))
              output-err  (schema/validate-output cell output matched)
              dispatch-err (when (and expected-dispatch
                                      (not= expected-dispatch matched))
                             {:phase :dispatch
                              :detail (str "Expected dispatch " expected-dispatch
                                           " but got " matched)})
              all-errors  (cond-> []
                            output-err   (conj {:phase :output :detail output-err})
                            dispatch-err (conj dispatch-err))]
          (if (seq all-errors)
            {:pass?            false
             :errors           all-errors
             :output           output
             :duration-ms      duration-ms
             :matched-dispatch matched}
            {:pass?            true
             :errors           []
             :output           output
             :duration-ms      duration-ms
             :matched-dispatch matched}))
        (catch Exception e
          {:pass?       false
           :errors      [{:phase :handler :detail (ex-message e)}]
           :output      nil
           :duration-ms (/ (- (System/nanoTime) start-time) 1e6)})))))

(defn test-transitions
  "Tests a cell across multiple dispatch paths.
   cases: {dispatch-label {:input ... :resources ... :dispatches ...}}
   When a case provides :dispatches, :expected-dispatch is set to the label.
   When no :dispatches, the cell is tested for output only (no dispatch check).
   Returns: {dispatch-label test-result}"
  [cell-id cases]
  (into {}
        (map (fn [[label opts]]
               [label (test-cell cell-id
                        (if (:dispatches opts)
                          (assoc opts :expected-dispatch label)
                          opts))]))
        cases))

(defn enumerate-paths
  "Enumerates all paths from :start to terminal states in a manifest.
   Join nodes are treated as single steps (their internal members are not expanded).
   Returns seq of paths, each a vector of {:cell :transition :target} (with optional :join? true)."
  [{:keys [cells edges joins]}]
  (let [terminal?  #{:end :error :halt}
        join-names (set (keys (or joins {})))]
    (loop [queue [[:start [] #{}]]
           paths []]
      (if (empty? queue)
        paths
        (let [[cell-name path-so-far visited] (first queue)
              rest-queue (rest queue)]
          (cond
            (terminal? cell-name)
            (recur rest-queue (conj paths path-so-far))

            (contains? visited cell-name)
            (recur rest-queue paths)

            :else
            (let [edge-def    (get edges cell-name)
                  new-visited (conj visited cell-name)
                  is-join?    (contains? join-names cell-name)]
              (if (keyword? edge-def)
                ;; Unconditional — use :unconditional as transition label
                (let [step (cond-> {:cell cell-name :transition :unconditional :target edge-def}
                             is-join? (assoc :join? true))]
                  (recur (conj (vec rest-queue)
                               [edge-def (conj path-so-far step) new-visited])
                         paths))
                ;; Map edges
                (let [next-items (mapv (fn [[transition target]]
                                         (let [step (cond-> {:cell cell-name :transition transition :target target}
                                                      is-join? (assoc :join? true))]
                                           [target (conj path-so-far step) new-visited]))
                                       edge-def)]
                  (recur (into (vec rest-queue) next-items)
                         paths))))))))))

(defn- dot-id
  "Converts a keyword name to a valid DOT identifier by quoting it."
  [kw]
  (str "\"" (name kw) "\""))

(defn workflow->dot
  "Generates a DOT graph string from a manifest for visualization.
   Join nodes are rendered as subgraph clusters containing their member cells."
  [{:keys [cells edges joins]}]
  (let [sb (StringBuilder.)
        joins-map   (or joins {})
        join-members (set (mapcat :cells (vals joins-map)))]
    (.append sb "digraph {\n")
    (.append sb "  rankdir=LR;\n")
    (.append sb "  node [shape=box];\n")
    ;; Add regular cell nodes (excluding join members)
    (doseq [[cell-name _cell-def] cells]
      (when-not (contains? join-members cell-name)
        (.append sb (str "  " (dot-id cell-name) ";\n"))))
    ;; Add join subgraph clusters
    (doseq [[join-name join-def] joins-map]
      (.append sb (str "  subgraph cluster_" (name join-name) " {\n"))
      (.append sb (str "    label=\"" (name join-name) " (join)\";\n"))
      (.append sb "    style=dashed;\n")
      (.append sb "    color=blue;\n")
      ;; The join node itself
      (.append sb (str "    " (dot-id join-name) " [shape=diamond];\n"))
      ;; Member cells inside the cluster
      (doseq [member (:cells join-def)]
        (.append sb (str "    " (dot-id member) " [shape=box style=filled fillcolor=lightyellow];\n"))
        (.append sb (str "    " (dot-id join-name) " -> " (dot-id member) " [style=dashed];\n")))
      (.append sb "  }\n"))
    ;; Terminal nodes
    (.append sb "  \"end\" [shape=doublecircle];\n")
    (.append sb "  \"error\" [shape=doubleoctagon color=red];\n")
    (.append sb "  \"halt\" [shape=octagon color=orange];\n")
    ;; Add edges
    (doseq [[from edge-def] edges]
      (if (keyword? edge-def)
        (.append sb (str "  " (dot-id from) " -> " (dot-id edge-def) ";\n"))
        (doseq [[label target] edge-def]
          (.append sb (str "  " (dot-id from) " -> " (dot-id target)
                       " [label=\"" (name label) "\"];\n")))))
    (.append sb "}\n")
    (str sb)))

(defn- generate-test-input
  "Generates test input data from a cell's input schema using Malli generators."
  [cell]
  (try
    (mg/generate (m/schema (get-in cell [:schema :input])) {:size 3 :seed 42})
    (catch Exception _
      {})))

(defn workflow-status
  "Reports implementation status across all cells in a manifest.
   Returns a map with :total, :implemented, :passing, :failing, :pending, :cells."
  [{:keys [cells]}]
  (let [cell-statuses
        (mapv (fn [[cell-name cell-def]]
                (let [cell-id (:id cell-def)
                      cell    (cell/get-cell cell-id)]
                  (if-not cell
                    {:id cell-id :name cell-name :status :pending}
                    (try
                      (let [result (test-cell cell-id {:input     (generate-test-input cell)
                                                       :resources {}})]
                        {:id     cell-id
                         :name   cell-name
                         :status (if (:pass? result) :passing :failing)
                         :errors (:errors result)})
                      (catch Exception e
                        {:id     cell-id
                         :name   cell-name
                         :status :failing
                         :error  (ex-message e)})))))
              cells)
        counts (frequencies (map :status cell-statuses))]
    {:total       (count cell-statuses)
     :implemented (- (count cell-statuses) (get counts :pending 0))
     :passing     (get counts :passing 0)
     :failing     (get counts :failing 0)
     :pending     (get counts :pending 0)
     :cells       cell-statuses}))

(defn analyze-workflow
  "Runs Maestro's static analysis on a workflow definition.
   Returns {:reachable #{...} :unreachable #{...} :no-path-to-end #{...} :cycles [...]}
   with state IDs reverse-resolved to cell name keywords for readability.
   Join-aware: join names appear as regular states, join members are excluded."
  [{:keys [cells edges dispatches joins] :as workflow-def}]
  (let [joins-map (or joins {})
        join-members (set (mapcat :cells (vals joins-map)))
        ;; Build state->names for reverse resolution
        state->names (merge
                      (into {}
                            (map (fn [[cell-name _]]
                                   [(wf/resolve-state-id cell-name) cell-name]))
                            cells)
                      (into {}
                            (map (fn [[join-name _]]
                                   [(wf/resolve-state-id join-name) join-name]))
                            joins-map))
        resolve-name (fn [sid] (get state->names sid sid))
        ;; Build the FSM spec (same as compile-workflow, minus interceptors)
        effective-dispatches (reduce (fn [acc [cell-name edge-def]]
                                      (if (and (map? edge-def) (not (get acc cell-name)))
                                        (let [cell-id (get cells cell-name)
                                              c (when cell-id (cell/get-cell cell-id))]
                                          (if-let [defaults (:default-dispatches c)]
                                            (assoc acc cell-name defaults)
                                            acc))
                                        acc))
                                    (or dispatches {})
                                    edges)
        ;; Add join default dispatches (filtered to edge keys)
        join-default-dispatches [[:failure (fn [d] (some? (:mycelium/join-error d)))]
                                 [:done    (fn [d] (not (:mycelium/join-error d)))]]
        effective-dispatches (reduce (fn [acc [join-name _]]
                                      (let [edge-def (get edges join-name)]
                                        (if (and (map? edge-def) (not (get acc join-name)))
                                          (let [edge-keys (set (keys edge-def))
                                                filtered (filterv (fn [[label _]]
                                                                    (contains? edge-keys label))
                                                                  join-default-dispatches)]
                                            (if (seq filtered)
                                              (assoc acc join-name filtered)
                                              acc))
                                          acc)))
                                    effective-dispatches
                                    joins-map)
        ;; Build FSM states for non-join-member cells
        fsm-cell-states (into {}
                              (keep (fn [[cell-name cell-id]]
                                      (when-not (contains? join-members cell-name)
                                        (let [c        (cell/get-cell! cell-id)
                                              state-id (wf/resolve-state-id cell-name)
                                              edge-def (get edges cell-name)
                                              dispatch-vec (get effective-dispatches cell-name)]
                                          [state-id
                                           (merge
                                            {:handler    (:handler c)
                                             :dispatches (wf/compile-edges edge-def dispatch-vec)}
                                            (when (:async? c) {:async? true}))]))))
                              cells)
        ;; Build FSM states for joins (synthetic handler)
        fsm-join-states (into {}
                              (map (fn [[join-name _join-def]]
                                     (let [state-id     (wf/resolve-state-id join-name)
                                           edge-def     (get edges join-name)
                                           dispatch-vec (get effective-dispatches join-name)]
                                       [state-id
                                        {:handler    (fn [_ data] data) ;; dummy for analysis
                                         :dispatches (wf/compile-edges edge-def dispatch-vec)}])))
                              joins-map)
        fsm-states (merge fsm-cell-states fsm-join-states)
        spec     {:fsm fsm-states}
        analysis (fsm/analyze spec)]
    {:reachable      (set (map resolve-name (:reachable analysis)))
     :unreachable    (set (map resolve-name (:unreachable analysis)))
     :no-path-to-end (set (map resolve-name (:no-path-to-end analysis)))
     :cycles         (mapv (fn [cycle] (mapv resolve-name cycle)) (:cycles analysis))}))

(defn- get-map-keys
  "Extracts top-level key names from a Malli schema. Returns #{} if not a map schema.
   Handles both [:map [:k v] ...] and lite syntax {:k v}."
  [schema]
  (cond
    (and (vector? schema) (= :map (first schema)))
    (set (keep (fn [entry]
                 (when (vector? entry)
                   (first entry)))
               (rest schema)))

    (map? schema)
    (set (keys schema))

    :else #{}))

(defn- cell-output-keys
  "Returns the union of all output keys for a cell across all transitions."
  [cell-id]
  (let [cell   (cell/get-cell cell-id)
        output (when cell (get-in cell [:schema :output]))]
    (cond
      (nil? output)    #{}
      (vector? output) (get-map-keys output)
      (map? output)    (reduce (fn [acc [_ s]] (set/union acc (get-map-keys s))) #{} output)
      :else            #{})))

(defn- cell-input-keys
  "Returns the set of input keys for a cell."
  [cell-id]
  (let [cell (cell/get-cell cell-id)]
    (when cell
      (get-map-keys (get-in cell [:schema :input])))))

(defn- join-output-keys
  "Returns the union of output keys from all member cells of a join."
  [join-def cells-map]
  (reduce (fn [acc member]
            (let [cell-id (get cells-map member)]
              (set/union acc (cell-output-keys cell-id))))
          #{}
          (:cells join-def)))

(defn infer-workflow-schema
  "Walks a workflow definition and returns a map showing the accumulated schema
   at each cell. For each cell, reports:
     :available-before — keys available when the cell starts
     :adds            — keys this cell adds (output keys)
     :available-after  — keys available after the cell completes

   Handles branching by taking the union of available keys from all incoming paths.
   Join nodes: union of all member output keys."
  [{:keys [cells edges joins] :as _workflow-def}]
  (let [joins-map (or joins {})
        terminal? #{:end :error :halt}
        ;; Accumulate per-cell info via BFS
        result (atom {})
        ;; Track best available-before per cell (union across all incoming edges)
        available-before (atom {})
        ;; Start cell's input keys are the initial set
        start-cell-id (get cells :start)
        start-input (or (cell-input-keys start-cell-id) #{})
        queue (atom [[:start start-input]])]
    (swap! available-before assoc :start start-input)
    (loop []
      (when (seq @queue)
        (let [[cell-name avail] (first @queue)]
          (swap! queue #(vec (rest %)))
          (when-not (terminal? cell-name)
            ;; Merge incoming available keys
            (let [prev-avail (get @available-before cell-name #{})
                  merged-avail (set/union prev-avail avail)]
              (swap! available-before assoc cell-name merged-avail)
              ;; Compute output keys
              (let [adds (if-let [join-def (get joins-map cell-name)]
                           (join-output-keys join-def cells)
                           (let [cell-id (get cells cell-name)]
                             (cell-output-keys cell-id)))
                    after (set/union merged-avail adds)]
                ;; Record if first visit or if available-before expanded
                (let [prev-record (get @result cell-name)]
                  (when (or (nil? prev-record)
                            (not= merged-avail (:available-before prev-record)))
                    (swap! result assoc cell-name
                           {:available-before merged-avail
                            :adds            adds
                            :available-after  after})
                    ;; Traverse edges
                    (let [edge-def (get edges cell-name)]
                      (if (keyword? edge-def)
                        (swap! queue conj [edge-def after])
                        (when (map? edge-def)
                          (doseq [[_ target] edge-def]
                            (swap! queue conj [target after])))))))))))
        (recur)))
    @result))

;; ===== Stub generation =====

(defn- resolve-cell-info
  "Resolves cell ID and schema from a workflow cell entry.
   Handles both bare keyword (:app/id) and map ({:id :app/id :schema ...}) forms.
   Falls back to registry lookup when schema not inline."
  [cell-entry]
  (let [cell-id (if (map? cell-entry) (:id cell-entry) cell-entry)
        ;; Try inline schema first (manifest-style), then registry
        inline-schema (when (map? cell-entry) (:schema cell-entry))
        reg-cell (cell/get-cell cell-id)
        schema (or inline-schema (:schema reg-cell))
        doc (or (when (map? cell-entry) (:doc cell-entry)) (:doc reg-cell))
        requires (or (when (map? cell-entry) (:requires cell-entry)) (:requires reg-cell))]
    {:cell-id  cell-id
     :schema   schema
     :doc      doc
     :requires requires}))

(defn- format-schema-value
  "Formats a schema value as a readable string."
  [schema-val]
  (if (map? schema-val)
    ;; Per-transition output schema
    (str "{" (str/join "\n                "
                       (map (fn [[k v]] (str k " " (pr-str v)))
                            (sort-by first schema-val))) "}")
    (pr-str schema-val)))

(defn generate-stubs
  "Generates defcell stub code from a workflow definition.
   For each cell, produces a defcell form with schema and a TODO handler.
   Cells already registered get their schema from the registry.
   Manifest-style cell entries (maps with :id and :schema) use inline schemas.

   Returns a string of Clojure code.

   Usage:
     (println (dev/generate-stubs workflow-def))
     (spit \"stubs.clj\" (dev/generate-stubs workflow-def))"
  [{:keys [cells]}]
  (str/join "\n\n"
            (map (fn [[cell-name cell-entry]]
                   (let [{:keys [cell-id schema doc requires]} (resolve-cell-info cell-entry)
                         has-schema? (some? schema)
                         input-schema (:input schema)
                         output-schema (:output schema)
                         doc-str (or doc (str "TODO: document " cell-id))
                         opts-parts (cond-> [(str " :doc    " (pr-str doc-str))]
                                      input-schema
                                      (conj (str " :input  " (pr-str input-schema)))
                                      output-schema
                                      (conj (str " :output " (format-schema-value output-schema)))
                                      requires
                                      (conj (str " :requires " (pr-str (vec requires)))))
                         opts-str (str "\n  {" (str/join "\n   " opts-parts) "}")
                         handler-str (if requires
                                       (str "(fn [{:keys ["
                                            (str/join " " (map name requires))
                                            "]} data]\n    ;; TODO: implement " cell-name
                                            "\n    data)")
                                       (str "(fn [_resources data]\n    ;; TODO: implement " cell-name
                                            "\n    data)"))]
                     (str "(cell/defcell " cell-id
                          opts-str
                          "\n  " handler-str ")")))
                 (sort-by first cells))))

(defn- format-trace-entry
  "Formats a single trace entry as a human-readable string."
  [idx entry]
  (let [cell      (:cell entry)
        cell-id   (:cell-id entry)
        transition (:transition entry)
        dur       (:duration-ms entry)
        error     (:error entry)
        halted    (:halted entry)
        timed-out (:timeout? entry)]
    (str (inc idx) ". " cell " (" cell-id ")"
         (when transition (str " -> " transition))
         (when dur (str " [" (format "%.2f" (double dur)) "ms]"))
         (when halted " [HALTED]")
         (when timed-out " [TIMEOUT]")
         (when error
           (str "\n   ERROR: " (:errors error))))))

(defn format-trace
  "Formats a :mycelium/trace vector into a human-readable string.
   Each step shows: cell name, cell-id, transition, duration, and errors."
  [trace]
  (if (seq trace)
    (str/join "\n" (map-indexed format-trace-entry trace))
    "(empty trace)"))

(defn trace-logger
  "Returns an :on-trace callback that prints each trace entry to stdout.
   Use with :on-trace opt in run-workflow or pre-compile.
   Example: (myc/run-workflow wf res data {:on-trace (dev/trace-logger)})"
  []
  (let [counter (atom -1)]
    (fn [entry]
      (println (format-trace-entry (swap! counter inc) entry)))))

;; ===== Schema inference from test runs =====

(defn- strip-mycelium-keys
  "Removes :mycelium/* keys from a data map for schema inference."
  [data]
  (into {} (remove (fn [[k _]] (and (keyword? k) (= "mycelium" (namespace k))))) data))

(defn infer-schemas
  "Infers input/output schemas for each cell by running a workflow with test inputs.
   Runs the workflow in :validate :off mode, captures cell inputs and outputs
   via :on-trace, then uses malli.provider/provide to generate schemas.

   Returns a map of {cell-name {:input schema :output schema}}.

   Arguments:
     workflow-def — workflow definition map
     resources    — resources map
     test-inputs  — vector of input data maps to run"
  [workflow-def resources test-inputs]
  (let [cell-data (atom {})] ;; {cell-name {:inputs [...] :outputs [...]}}
    ;; Run workflow for each test input sequentially
    (doseq [input test-inputs]
      (let [;; Per-run capture of cell input data (keyed by state-id)
            input-capture (atom {})
            pre-hook (fn [fsm-state _resources]
                       (let [state-id (:current-state-id fsm-state)]
                         (when-not (contains? #{::fsm/end ::fsm/error ::fsm/halt} state-id)
                           (swap! input-capture assoc state-id
                                  (strip-mycelium-keys (:data fsm-state))))
                         fsm-state))
            on-trace (fn [{:keys [cell] :as _entry}]
                       (let [input-data  (get @input-capture
                                             (wf/resolve-state-id cell))
                             output-data (strip-mycelium-keys (:data _entry))]
                         (swap! cell-data update cell
                                (fn [m]
                                  (-> (or m {:inputs [] :outputs []})
                                      (update :inputs conj (or input-data output-data))
                                      (update :outputs conj output-data))))))
            compiled (wf/compile-workflow workflow-def
                                         {:validate :off
                                          :pre pre-hook
                                          :on-trace on-trace})]
        (try
          @(fsm/run-async compiled resources {:data input})
          (catch Exception _))))
    ;; Infer schemas from collected data
    (into {}
          (map (fn [[cell-name {:keys [inputs outputs]}]]
                 [cell-name {:input  (mp/provide inputs)
                             :output (mp/provide outputs)}]))
          @cell-data)))

(defn apply-inferred-schemas!
  "Applies inferred schemas to cells in the registry.
   `inferred` is the output of `infer-schemas`.
   `workflow-def` is the workflow definition (needed to map cell-names to cell-ids)."
  [inferred workflow-def]
  (let [cells (:cells workflow-def)]
    (doseq [[cell-name schema-map] inferred]
      (let [cell-ref (get cells cell-name)
            cell-id  (if (map? cell-ref) (:id cell-ref) cell-ref)]
        (when cell-id
          (cell/set-cell-schema! cell-id schema-map))))))

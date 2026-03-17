(ns mycelium.validation
  "Shared validation functions for workflow and manifest validation.
   Provides schema well-formedness checks, edge target validation,
   BFS reachability, and dispatch coverage verification."
  (:require [clojure.set :as set]
            [malli.core :as m]))

;; ===== Schema well-formedness =====

(defn validate-malli-schema!
  "Validates that a Malli schema definition is well-formed.
   Returns nil on success, throws on invalid schema.
   `label` is included in the error message for diagnostics."
  [schema label]
  (try
    (m/schema schema)
    nil
    (catch Exception e
      (throw (ex-info (str "Invalid Malli schema for " label ": " (ex-message e))
                      {:label label :schema schema}
                      e)))))

(defn validate-output-schema!
  "Validates an output schema which may be a single schema (vector/keyword)
   or a per-transition map. If a map, validates each value as a Malli schema."
  [output-schema label]
  (cond
    (vector? output-schema)
    (validate-malli-schema! output-schema label)

    (map? output-schema)
    (doseq [[k v] output-schema]
      (validate-malli-schema! v (str label " transition " k)))

    :else
    (validate-malli-schema! output-schema label)))

;; ===== Cell definition validation =====

(defn validate-cell-def!
  "Validates a single cell definition (manifest or fragment).
   Checks :id, :doc, and :schema presence, validates Malli schemas.
   Expects schemas to be pre-normalized (lite syntax already converted).
   Skips schema validation for :schema :inherit (resolved separately).
   `context` is a string prefix for error messages (e.g. \"Cell\" or \"Fragment cell\")."
  [cell-name cell-def context]
  (when-not (:id cell-def)
    (throw (ex-info (str context " " cell-name " missing :id") {:cell-name cell-name})))
  (when-not (and (string? (:doc cell-def)) (seq (:doc cell-def)))
    (throw (ex-info (str context " " cell-name " missing :doc — provide a non-empty string "
                         "describing the cell's purpose and semantics")
                    {:cell-name cell-name})))
  (when-not (:schema cell-def)
    (throw (ex-info (str context " " cell-name " missing :schema") {:cell-name cell-name})))
  (when-not (= :inherit (:schema cell-def))
    (let [input  (get-in cell-def [:schema :input])
          output (get-in cell-def [:schema :output])]
      (when-not input
        (throw (ex-info (str context " " cell-name " missing :schema :input") {:cell-name cell-name})))
      (when-not output
        (throw (ex-info (str context " " cell-name " missing :schema :output") {:cell-name cell-name})))
      (validate-malli-schema! input (str cell-name " :input"))
      (validate-output-schema! output (str cell-name " :output")))))

;; ===== Edge target validation =====

(defn validate-edge-targets!
  "Checks all edge targets reference valid cell names or terminal states (:end/:error/:halt).
   `edges-map` is {cell-name -> edge-def}, `cell-names` is a set of valid cell names."
  [edges-map cell-names]
  (let [valid-names (into #{:end :error :halt} cell-names)]
    (doseq [[from edge-def] edges-map
            target (if (keyword? edge-def) [edge-def] (vals edge-def))]
      (when-not (contains? valid-names target)
        (throw (ex-info (str "Invalid edge target " target " from " from
                             ". Valid targets: " valid-names)
                        {:from from :target target :valid valid-names}))))))

;; ===== Reachability =====

(defn validate-reachability!
  "BFS from :start, checks all cells in `cell-names` are reachable via `edges-map`."
  [edges-map cell-names]
  (let [adjacency (into {}
                        (map (fn [[from edge-def]]
                               [from (if (keyword? edge-def)
                                       #{edge-def}
                                       (set (vals edge-def)))]))
                        edges-map)
        reachable (loop [queue   (conj clojure.lang.PersistentQueue/EMPTY :start)
                         visited #{}]
                    (if (empty? queue)
                      visited
                      (let [node  (peek queue)
                            queue (pop queue)]
                        (if (visited node)
                          (recur queue visited)
                          (recur (into queue (get adjacency node #{}))
                                 (conj visited node))))))
        unreachable (set/difference (set cell-names) reachable)]
    (when (seq unreachable)
      (throw (ex-info (str "Unreachable cells: " unreachable)
                      {:unreachable unreachable})))))

;; ===== Dispatch coverage =====

(defn validate-dispatch-coverage!
  "For each cell with map edges, checks dispatch labels match edge keys exactly.
   Dispatches are vectors of [label pred] pairs; labels must match edge keys.
   Cells with unconditional edges (keyword) need no dispatch entry."
  [edges-map dispatches-map]
  (doseq [[cell-name edge-def] edges-map]
    (when (map? edge-def)
      (let [edge-keys     (set (keys edge-def))
            dispatch-vec  (get dispatches-map cell-name)
            dispatch-keys (when dispatch-vec (set (map first dispatch-vec)))]
        (when-not dispatch-vec
          (throw (ex-info (str "Cell " cell-name " has map edges but no dispatch defined")
                          {:cell-name cell-name :edge-keys edge-keys})))
        (let [missing (set/difference edge-keys dispatch-keys)]
          (when (seq missing)
            (throw (ex-info (str "Cell " cell-name " has edge(s) " missing
                                 " with no dispatch predicates")
                            {:cell-name cell-name :missing missing
                             :edge-keys edge-keys :dispatch-keys dispatch-keys}))))
        (let [extra (set/difference dispatch-keys edge-keys)]
          (when (seq extra)
            (throw (ex-info (str "Cell " cell-name " has dispatch(es) " extra
                                 " that don't match any edge")
                            {:cell-name cell-name :extra extra
                             :edge-keys edge-keys :dispatch-keys dispatch-keys}))))))))

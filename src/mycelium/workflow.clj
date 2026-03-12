(ns mycelium.workflow
  "Workflow DSL compiler: transforms workflow definitions into Maestro FSM specs."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.resilience :as resilience]
            [mycelium.schema :as schema]
            [mycelium.validation :as v]
            [maestro.core :as fsm]))

;; ===== Cell reference normalization =====

(defn- normalize-cell-ref
  "Normalizes a cell reference to {:id cell-id, :params params-map-or-nil}.
   Accepts either a keyword (bare cell-id) or a map with :id and optional :params."
  [cell-name cell-ref]
  (cond
    (keyword? cell-ref)
    {:id cell-ref :params nil}

    (and (map? cell-ref) (:id cell-ref))
    {:id (:id cell-ref) :params (:params cell-ref)}

    (map? cell-ref)
    (throw (ex-info (str "Cell " cell-name " map ref missing :id")
                    {:cell-name cell-name :cell-ref cell-ref}))

    :else
    (throw (ex-info (str "Cell " cell-name " has invalid ref type: " (type cell-ref))
                    {:cell-name cell-name :cell-ref cell-ref}))))

(defn- cells->ids
  "Extracts a {cell-name → cell-id} map from cells, normalizing map refs."
  [cells]
  (into {} (map (fn [[cell-name cell-ref]]
                  [cell-name (:id (normalize-cell-ref cell-name cell-ref))]))
        cells))

(defn- cells->params
  "Extracts a {cell-name → params} map from cells, only for cells with params."
  [cells]
  (into {} (keep (fn [[cell-name cell-ref]]
                   (let [{:keys [params]} (normalize-cell-ref cell-name cell-ref)]
                     (when params
                       [cell-name params]))))
        cells))

;; ===== State ID resolution =====

(def ^:private special-states
  {:start ::fsm/start
   :end   ::fsm/end
   :error ::fsm/error
   :halt  ::fsm/halt})

(defn resolve-state-id
  "Maps special keywords (:start, :end, :error, :halt) to Maestro reserved states.
   Other keywords are namespaced to :mycelium.workflow/name."
  [kw]
  (or (get special-states kw)
      (keyword "mycelium.workflow" (name kw))))

;; ===== Edge compilation =====

(defn compile-edges
  "Compiles edge definitions into Maestro dispatch pairs.
   If edges is a keyword → unconditional dispatch to that target.
   If edges is a map → uses dispatch-vec (vector of [label pred] pairs) to
   produce ordered [[target pred] ...] matching Maestro's format.
   :default dispatches are always placed last to ensure catch-all semantics."
  [edges dispatch-vec]
  (if (keyword? edges)
    [[(resolve-state-id edges) (constantly true)]]
    (let [;; Partition into non-default and default, then concat so :default is last
          {defaults true others false} (group-by #(= :default (first %)) dispatch-vec)
          ordered (concat others defaults)]
      (mapv (fn [[label pred]]
              (let [target (get edges label)]
                (when-not target
                  (throw (ex-info (str "No edge target for dispatch label " label)
                                  {:label label})))
                [(resolve-state-id target) pred]))
            ordered))))

;; ===== Schema key utilities =====

(defn- get-map-keys
  "Extracts top-level keys from a Malli :map schema. Returns nil if not a :map schema.
   Skips Malli property maps (e.g. {:closed true} in [:map {:closed true} [:x :int]])."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (set (keep (fn [entry]
                 (when (vector? entry)
                   (first entry)))
               (rest schema)))))

(defn- get-output-keys-for-transition
  "Gets output keys for a specific transition of a cell.
   For vector output schema, returns all keys for any transition.
   For map output schema, returns keys for that specific transition."
  [cell-id transition]
  (let [cell   (cell/get-cell! cell-id)
        output (get-in cell [:schema :output])]
    (cond
      (nil? output)    nil
      (vector? output) (get-map-keys output)
      (map? output)    (when-let [schema (get output transition)]
                         (get-map-keys schema)))))

(defn- get-all-output-keys
  "Gets the union of all output keys across all transitions.
   Used for unconditional edges where all transitions route to the same target."
  [cell-id]
  (let [cell   (cell/get-cell! cell-id)
        output (get-in cell [:schema :output])]
    (cond
      (nil? output)    nil
      (vector? output) (get-map-keys output)
      (map? output)    (reduce (fn [acc [_ schema]]
                                 (into acc (get-map-keys schema)))
                               #{}
                               output))))

(defn- get-join-member-output-keys
  "Gets the union of all output keys from a single join member cell."
  [cell-id]
  (let [cell   (cell/get-cell! cell-id)
        output (get-in cell [:schema :output])]
    (cond
      (nil? output)    #{}
      (vector? output) (or (get-map-keys output) #{})
      (map? output)    (reduce (fn [acc [_ schema]]
                                 (into acc (or (get-map-keys schema) #{})))
                               #{}
                               output))))

;; ===== Join validation =====

(defn- validate-join-defs!
  "Validates join definitions against the workflow."
  [joins cells edges]
  (let [cell-names (set (keys cells))
        join-names (set (keys joins))]
    ;; Join names must not collide with cell names
    (let [collisions (set/intersection cell-names join-names)]
      (when (seq collisions)
        (throw (ex-info (str "Join name collision with cell names: " collisions)
                        {:collisions collisions}))))
    ;; Check no cell appears in multiple joins
    (let [seen (atom {})]
      (doseq [[join-name join-def] joins
              member (:cells join-def)]
        (if-let [other-join (get @seen member)]
          (throw (ex-info (str "Cell " member " is member of multiple joins: "
                               other-join " and " join-name)
                          {:cell member :joins [other-join join-name]}))
          (swap! seen assoc member join-name))))
    (doseq [[join-name join-def] joins]
      (let [member-cells (:cells join-def)
            strategy     (:strategy join-def :parallel)]
        ;; Empty :cells vector rejected
        (when (empty? member-cells)
          (throw (ex-info (str "Join " join-name " has empty :cells vector")
                          {:join-name join-name})))
        ;; :strategy must be :parallel or :sequential
        (when-not (contains? #{:parallel :sequential} strategy)
          (throw (ex-info (str "Join " join-name " has invalid strategy: " strategy
                               ". Must be :parallel or :sequential")
                          {:join-name join-name :strategy strategy})))
        ;; Member cells must exist in :cells map
        (doseq [member member-cells]
          (when-not (contains? cell-names member)
            (throw (ex-info (str "Join " join-name " references " member
                                 " which is not found in :cells map")
                            {:join-name join-name :member member}))))
        ;; Member cells must NOT have entries in :edges
        (doseq [member member-cells]
          (when (contains? edges member)
            (throw (ex-info (str "Join member " member " in join " join-name
                                 " must not have its own entry in :edges")
                            {:join-name join-name :member member}))))
        ;; :on-failure must reference valid cell/terminal or not be present
        (when-let [on-failure (:on-failure join-def)]
          (when-not (or (contains? cell-names on-failure)
                        (contains? #{:end :error :halt} on-failure)
                        (contains? join-names on-failure))
            (throw (ex-info (str "Join " join-name " :on-failure references "
                                 on-failure " which is not found in :cells or terminal states")
                            {:join-name join-name :on-failure on-failure}))))))))

(defn- validate-join-output-conflicts!
  "For each join, checks that member cells don't produce overlapping output keys
   unless a :merge-fn is provided."
  [joins cells]
  (doseq [[join-name join-def] joins]
    (when-not (:merge-fn join-def)
      (let [member-cells (:cells join-def)
            cell-key-pairs (mapv (fn [member]
                                   (let [cell-id (get cells member)]
                                     [member (get-join-member-output-keys cell-id)]))
                                 member-cells)]
        ;; Check for overlapping keys between any pair
        (doseq [i (range (count cell-key-pairs))
                j (range (inc i) (count cell-key-pairs))]
          (let [[name-a keys-a] (nth cell-key-pairs i)
                [name-b keys-b] (nth cell-key-pairs j)
                conflicts (set/intersection keys-a keys-b)]
            (when (seq conflicts)
              (throw (ex-info (str "Join " join-name " has output key conflict between "
                                   name-a " and " name-b ": " conflicts
                                   ". Provide :merge-fn to resolve.")
                              {:join-name join-name
                               :cell-a    name-a
                               :cell-b    name-b
                               :conflicts conflicts})))))))))

;; ===== Join handler construction =====

(def ^:private default-async-timeout-ms
  "Default timeout in ms for async cell invocations within joins."
  30000)

(defn- invoke-cell-handler
  "Invokes a cell handler, handling both sync and async cells.
   Returns the result data map (blocking for async cells).
   `timeout-ms` overrides the default async timeout."
  ([cell resources data]
   (invoke-cell-handler cell resources data default-async-timeout-ms))
  ([cell resources data timeout-ms]
   (if (:async? cell)
     (let [p (promise)]
       ((:handler cell) resources data
        (fn [result] (deliver p {:ok result}))
        (fn [error]  (deliver p {:error error})))
       (let [v (deref p timeout-ms {:error (ex-info "Async cell timed out" {:cell-id (:id cell)})})]
         (if (:error v)
           (throw (if (instance? Throwable (:error v))
                    (:error v)
                    (ex-info (str (:error v)) {:cell-id (:id cell)})))
           (:ok v))))
     ((:handler cell) resources data))))

(defn- build-join-handler
  "Builds a synthetic handler function for a join node.
   The handler runs member cells (parallel or sequential) with snapshot semantics,
   merges results, and collects errors."
  [_join-name join-def cells-map]
  (let [member-names (:cells join-def)
        strategy     (:strategy join-def :parallel)
        merge-fn     (:merge-fn join-def)
        timeout-ms   (:timeout-ms join-def default-async-timeout-ms)
        member-cells (mapv (fn [m] {:name m :cell (cell/get-cell! (get cells-map m))})
                           member-names)]
    (fn [resources data]
      (let [snapshot data ;; each branch gets the same snapshot
            run-member (fn [{:keys [name cell]}]
                         (let [start-ns (System/nanoTime)]
                           (try
                             (let [result (invoke-cell-handler cell resources snapshot timeout-ms)
                                   dur-ms (/ (- (System/nanoTime) start-ns) 1e6)]
                               {:name     name
                                :cell-id  (:id cell)
                                :result   result
                                :duration-ms dur-ms
                                :status   :ok})
                             (catch Throwable e
                               (let [dur-ms (/ (- (System/nanoTime) start-ns) 1e6)]
                                 {:name     name
                                  :cell-id  (:id cell)
                                  :error    (or (ex-message e) (.toString e))
                                  :duration-ms dur-ms
                                  :status   :error})))))
            outcomes (if (= strategy :parallel)
                       (let [futures (mapv #(future (run-member %)) member-cells)]
                         (mapv deref futures))
                       ;; Sequential
                       (mapv run-member member-cells))
            errors  (filterv #(= :error (:status %)) outcomes)
            successes (filterv #(= :ok (:status %)) outcomes)
            ;; Build join trace entries
            join-traces (mapv (fn [o]
                                (cond-> {:cell     (:name o)
                                         :cell-id  (:cell-id o)
                                         :duration-ms (:duration-ms o)
                                         :status   (:status o)}
                                  (:error o) (assoc :error (:error o))))
                              outcomes)
            merged-data (if (seq errors)
                          (assoc data :mycelium/join-error
                                 (mapv (fn [e] {:cell (:name e) :error (:error e)}) errors))
                          (if merge-fn
                            (merge-fn data (mapv :result successes))
                            (apply merge data (map :result successes))))]
        (assoc merged-data :mycelium/join-traces join-traces)))))

(defn- get-map-entries
  "Extracts top-level entries from a Malli :map schema as a map of {key -> type-schema}.
   Returns {} if not a :map schema."
  [schema]
  (if (and (vector? schema) (= :map (first schema)))
    (into {}
          (keep (fn [entry]
                  (when (and (vector? entry) (>= (count entry) 2))
                    [(first entry) (second entry)])))
          (rest schema))
    {}))

(defn- build-join-input-schema
  "Builds a synthesized input schema for a join node.
   Union of all member cells' input keys with their actual types preserved.
   When the same key appears in multiple members with different types,
   the first type encountered wins (they must be compatible for the
   workflow to function correctly)."
  [join-def cells-map]
  (let [member-names (:cells join-def)
        all-entries (reduce (fn [acc member]
                              (let [cell (cell/get-cell! (get cells-map member))
                                    schema (get-in cell [:schema :input])
                                    entries (get-map-entries schema)]
                                ;; merge — first type wins for duplicate keys
                                (merge entries acc)))
                            {}
                            member-names)]
    (if (empty? all-entries)
      [:map]
      (into [:map] (mapv (fn [[k t]] [k t]) all-entries)))))

(defn- build-join-output-keys
  "Gets the union of all output keys from all join member cells."
  [join-def cells-map]
  (reduce (fn [acc member]
            (let [cell-id (get cells-map member)]
              (into acc (get-join-member-output-keys cell-id))))
          #{}
          (:cells join-def)))

;; ===== Pipeline expansion =====

(defn- expand-pipeline
  "Expands a :pipeline vector into :edges and :dispatches for programmatic workflows.
   Pipeline is mutually exclusive with :edges, :dispatches, and :joins."
  [{:keys [pipeline cells edges dispatches joins] :as workflow}]
  (if-not pipeline
    workflow
    (do
      (when edges
        (throw (ex-info ":pipeline is mutually exclusive with :edges" {})))
      (when dispatches
        (throw (ex-info ":pipeline is mutually exclusive with :dispatches" {})))
      (when joins
        (throw (ex-info ":pipeline is mutually exclusive with :joins" {})))
      (when (empty? pipeline)
        (throw (ex-info ":pipeline must have at least 1 element" {})))
      (let [cell-names (set (keys cells))]
        (doseq [name pipeline]
          (when-not (contains? cell-names name)
            (throw (ex-info (str "Pipeline references " name " which is not in :cells")
                            {:pipeline-ref name :valid-cells cell-names})))))
      (let [pairs (partition 2 1 pipeline)
            expanded-edges (into {} (concat
                                      (map (fn [[from to]] [from to]) pairs)
                                      [[(last pipeline) :end]]))]
        (-> workflow
            (dissoc :pipeline)
            (assoc :edges expanded-edges :dispatches {}))))))

;; ===== Validation =====

(defn- validate-cells-exist!
  "Checks all cells referenced in :cells exist in the registry.
   Accepts both bare keyword and map cell refs."
  [cells]
  (doseq [[cell-name cell-ref] cells]
    (let [{:keys [id]} (normalize-cell-ref cell-name cell-ref)]
      (cell/get-cell! id))))

(defn- validate-default-edges!
  "Validates :default edge usage. Throws if :default is the only edge for a cell."
  [edges-map]
  (doseq [[cell-name edge-def] edges-map]
    (when (and (map? edge-def)
               (contains? edge-def :default)
               (= 1 (count edge-def)))
      (throw (ex-info (str "Cell " cell-name " has :default as its only edge. "
                           "Use an unconditional edge (keyword) instead.")
                      {:cell-name cell-name})))))

(defn- inject-default-dispatches
  "For cells with a :default edge and no explicit :default dispatch predicate,
   auto-appends [:default (constantly true)] as the last dispatch predicate."
  [dispatches-map edges-map]
  (reduce (fn [acc [cell-name edge-def]]
            (if (and (map? edge-def)
                     (contains? edge-def :default))
              (let [dispatch-vec (get acc cell-name [])
                    has-default? (some #(= :default (first %)) dispatch-vec)]
                (if has-default?
                  acc
                  (assoc acc cell-name
                         (conj (vec dispatch-vec)
                               [:default (constantly true)]))))
              acc))
          (or dispatches-map {})
          edges-map))

(defn- merge-default-dispatches
  "Merges default dispatches from cell specs into the workflow dispatches.
   For each cell with map edges and no explicit dispatch, checks the cell spec
   for :default-dispatches and uses that as fallback."
  [dispatches-map edges-map cells-map]
  (reduce (fn [acc [cell-name edge-def]]
            (if (and (map? edge-def) (not (get acc cell-name)))
              (let [cell-id (get cells-map cell-name)
                    cell (when cell-id (cell/get-cell cell-id))]
                (if-let [defaults (:default-dispatches cell)]
                  (assoc acc cell-name defaults)
                  acc))
              acc))
          (or dispatches-map {})
          edges-map))

(defn- get-transform-output-keys
  "Extracts output keys from a transform spec's :schema :output, if present."
  [transform-spec]
  (when-let [schema (get-in transform-spec [:schema :output])]
    (get-map-keys schema)))

(defn- get-transform-input-keys
  "Extracts input keys from a transform spec's :schema :input, if present."
  [transform-spec]
  (when-let [schema (get-in transform-spec [:schema :input])]
    (get-map-keys schema)))

(defn- validate-schema-chain!
  "Walks all paths from :start, accumulating output keys.
   For each cell, checks its input keys are available from upstream outputs or workflow input.
   For per-transition output schemas, only passes the keys from the matching transition's schema
   along each edge.
   Join nodes: validates each member's inputs, then adds the union of all member outputs.
   When transforms are present, uses transform schemas instead of cell schemas for chain checks."
  ([edges-map cells-map joins-map]
   (validate-schema-chain! edges-map cells-map joins-map nil))
  ([edges-map cells-map joins-map transforms]
   (let [get-input-keys  (fn [cell-id]
                           (let [cell   (cell/get-cell! cell-id)
                                 schema (get-in cell [:schema :input])]
                             (get-map-keys schema)))
         errors (atom [])
         visit  (fn visit [cell-name available-keys visited]
                  (when-not (or (contains? visited cell-name)
                                (contains? #{:end :error :halt} cell-name))
                    (if-let [join-def (get joins-map cell-name)]
                      ;; This is a join node — validate member inputs and accumulate union of outputs
                      (let [member-names (:cells join-def)]
                        ;; Validate each member's input keys against available
                        (doseq [member member-names]
                          (let [cell-id    (get cells-map member)
                                input-keys (get-input-keys cell-id)
                                missing    (when input-keys
                                             (set/difference input-keys available-keys))]
                            (when (seq missing)
                              (swap! errors conj
                                     {:cell-name     member
                                      :cell-id       cell-id
                                      :missing-keys  missing
                                      :available-keys available-keys}))))
                        ;; Accumulate union of all member output keys
                        (let [out-keys (build-join-output-keys join-def cells-map)
                              new-keys (into available-keys out-keys)
                              edge-def (get edges-map cell-name)]
                          (if (keyword? edge-def)
                            (visit edge-def new-keys (conj visited cell-name))
                            (doseq [[_transition target] edge-def]
                              (visit target new-keys (conj visited cell-name))))))
                      ;; Regular cell
                      (let [cell-id     (get cells-map cell-name)
                            ;; If cell has an input transform, check transform's input keys instead
                            xf-def      (get transforms cell-name)
                            input-xf    (when xf-def
                                          (or (:input xf-def)
                                              ;; For branching, :input is at top level
                                              nil))
                            effective-input-keys (if input-xf
                                                   (get-transform-input-keys input-xf)
                                                   (get-input-keys cell-id))
                            missing     (when effective-input-keys
                                          (set/difference effective-input-keys available-keys))]
                        (when (seq missing)
                          (swap! errors conj
                                 {:cell-name     cell-name
                                  :cell-id       cell-id
                                  :missing-keys  missing
                                  :available-keys available-keys}))
                        ;; Traverse edges, using per-transition output keys
                        (let [edge-def (get edges-map cell-name)]
                          (if (keyword? edge-def)
                            ;; Unconditional edge — use union of all output keys
                            (let [out-keys (get-all-output-keys cell-id)
                                  ;; Add output transform keys if present
                                  xf-out-keys (when-let [xf (:output xf-def)]
                                                (get-transform-output-keys xf))
                                  new-keys (into available-keys
                                                 (concat (or out-keys #{})
                                                         (or xf-out-keys #{})))]
                              (visit edge-def new-keys (conj visited cell-name)))
                            ;; Map edges — per-transition output keys
                            (doseq [[transition target] edge-def]
                              (let [out-keys (get-output-keys-for-transition cell-id transition)
                                    ;; Add per-edge output transform keys if present
                                    xf-out-keys (when-let [edge-xf (get xf-def transition)]
                                                  (get-transform-output-keys (:output edge-xf)))
                                    new-keys (into available-keys
                                                   (concat (or out-keys #{})
                                                           (or xf-out-keys #{})))]
                                (visit target new-keys (conj visited cell-name))))))))))]
     ;; Start with :start cell
     (let [start-cell-id    (get cells-map :start)
           start-input-keys (when start-cell-id (get-input-keys start-cell-id))
           initial-keys     (or start-input-keys #{})
           edge-def         (get edges-map :start)
           xf-def           (get transforms :start)]
       (if (keyword? edge-def)
         (let [out-keys (get-all-output-keys start-cell-id)
               xf-out-keys (when-let [xf (:output xf-def)]
                             (get-transform-output-keys xf))
               new-keys (into initial-keys
                              (concat (or out-keys #{})
                                      (or xf-out-keys #{})))]
           (visit edge-def new-keys #{:start}))
         (doseq [[transition target] edge-def]
           (let [out-keys (get-output-keys-for-transition start-cell-id transition)
                 xf-out-keys (when-let [edge-xf (get xf-def transition)]
                               (get-transform-output-keys (:output edge-xf)))
                 new-keys (into initial-keys
                                (concat (or out-keys #{})
                                        (or xf-out-keys #{})))]
             (visit target new-keys #{:start})))))
     (when (seq @errors)
       (let [msg (str "Schema chain error: "
                      (str/join
                       "; "
                       (map (fn [{:keys [cell-name cell-id missing-keys available-keys]}]
                              (str cell-id " at " cell-name
                                   " requires keys " missing-keys
                                   " but only " available-keys " available"))
                            @errors)))]
         (throw (ex-info msg {:errors @errors})))))))

(def ^:private join-default-dispatches
  "Default dispatch predicates for join nodes.
   :failure if :mycelium/join-error is present, :done otherwise."
  [[:failure (fn [d] (some? (:mycelium/join-error d)))]
   [:done    (fn [d] (not (:mycelium/join-error d)))]])

(defn- compute-join-dispatches
  "Computes effective dispatches for join nodes, filtering join-default-dispatches
   to only include labels that match existing edge keys. Also appends a :default
   catch-all predicate if the join's edge map contains a :default target.
   Returns a dispatches map keyed by join name."
  [joins-map edges]
  (reduce (fn [acc [join-name _]]
            (let [edge-def (get edges join-name)]
              (if (and (map? edge-def)
                       (not (get acc join-name)))
                (let [edge-keys (set (keys edge-def))
                      filtered  (filterv (fn [[label _]]
                                           (contains? edge-keys label))
                                         join-default-dispatches)
                      ;; Append :default catch-all if edge map has :default target
                      with-default (if (contains? edge-def :default)
                                     (conj (vec filtered) [:default (constantly true)])
                                     filtered)]
                  (if (seq with-default)
                    (assoc acc join-name with-default)
                    acc))
                acc)))
          {}
          joins-map))

;; ===== Graph-level timeout wrapping =====

(defn- validate-timeouts!
  "Validates :timeouts map entries. Each key must be a cell in :cells,
   value must be a positive integer, and the cell must have a :timeout edge."
  [timeouts cells edges]
  (let [cell-names (set (keys cells))]
    (doseq [[cell-name timeout-ms] timeouts]
      (when-not (contains? cell-names cell-name)
        (throw (ex-info (str "timeout references unknown cell " cell-name
                             " — timeout cell must be in :cells")
                        {:cell-name cell-name})))
      (when-not (and (integer? timeout-ms) (pos? timeout-ms))
        (throw (ex-info (str "Timeout for " cell-name " must be a positive integer, got: " timeout-ms)
                        {:cell-name cell-name :timeout timeout-ms})))
      (let [edge-def (get edges cell-name)]
        (when-not (and (map? edge-def) (contains? edge-def :timeout))
          (throw (ex-info (str "Cell " cell-name " has a timeout but no :timeout edge target")
                          {:cell-name cell-name})))))))

(defn- inject-timeout-dispatches
  "For cells with a :timeout edge and a timeout value, auto-injects a
   [:timeout (fn [d] (:mycelium/timeout d))] dispatch predicate.
   Inserted before any :default predicate so timeout takes priority."
  [dispatches-map timeouts edges]
  (if (empty? timeouts)
    dispatches-map
    (reduce (fn [acc [cell-name _timeout-ms]]
              (let [edge-def (get edges cell-name)]
                (if (and (map? edge-def) (contains? edge-def :timeout))
                  (let [dispatch-vec (get acc cell-name [])
                        has-timeout? (some #(= :timeout (first %)) dispatch-vec)]
                    (if has-timeout?
                      acc
                      (let [timeout-pred [:timeout (fn [d] (some? (:mycelium/timeout d)))]
                            ;; Insert first (before user predicates) but before :default
                            {defaults true others false} (group-by #(= :default (first %)) dispatch-vec)]
                        (assoc acc cell-name (vec (concat [timeout-pred] others defaults))))))
                  acc)))
            (or dispatches-map {})
            timeouts)))

(defn- wrap-handler-with-timeout
  "Wraps a cell handler to enforce a graph-level timeout.
   On timeout, returns the original input data with :mycelium/timeout true.
   Works for both sync and async handlers."
  [handler timeout-ms async?]
  (if async?
    ;; Async: deliver to promise, deref with timeout
    (fn [resources data]
      (let [p (promise)]
        (handler resources data
                 (fn [result] (deliver p {:ok result}))
                 (fn [error] (deliver p {:error error})))
        (let [v (deref p timeout-ms ::timed-out)]
          (if (= v ::timed-out)
            (assoc data :mycelium/timeout true)
            (if (:error v)
              (throw (if (instance? Throwable (:error v))
                       (:error v)
                       (ex-info (str (:error v)) {})))
              (:ok v))))))
    ;; Sync: run in future, deref with timeout
    (fn [resources data]
      (let [f (future (handler resources data))
            v (deref f timeout-ms ::timed-out)]
        (if (= v ::timed-out)
          (assoc data :mycelium/timeout true)
          v)))))

(defn- apply-graph-timeouts
  "Wraps handlers for cells that have graph-level timeouts.
   state->cell: {resolved-state-id -> cell-map}
   timeouts: {cell-name -> ms}"
  [state->cell timeouts]
  (if (empty? timeouts)
    state->cell
    (reduce (fn [acc [cell-name timeout-ms]]
              (let [state-id (resolve-state-id cell-name)]
                (if-let [cell (get acc state-id)]
                  (assoc acc state-id
                         (-> cell
                             (update :handler wrap-handler-with-timeout timeout-ms (:async? cell))
                             ;; Timeout wrapper blocks, so the cell becomes sync
                             (dissoc :async?)))
                  acc)))
            state->cell
            timeouts)))

;; ===== Error group expansion =====

(defn- validate-error-groups!
  "Validates :error-groups. Each group's cells must exist in :cells,
   :on-error must reference a cell in :cells, and no cell may appear
   in multiple groups."
  [error-groups cells]
  (let [cell-names (set (keys cells))
        seen (atom {})]
    (doseq [[group-name {:keys [cells on-error]}] error-groups]
      (when-not (contains? cell-names on-error)
        (throw (ex-info (str "error group " group-name " :on-error references nonexistent cell " on-error)
                        {:group group-name :on-error on-error})))
      (doseq [cell-name cells]
        (when-not (contains? cell-names cell-name)
          (throw (ex-info (str "error group " group-name " references nonexistent cell " cell-name)
                          {:group group-name :cell cell-name})))
        (when-let [other-group (get @seen cell-name)]
          (throw (ex-info (str "Cell " cell-name " appears in multiple error groups: "
                               other-group " and " group-name)
                          {:cell cell-name :groups [other-group group-name]})))
        (swap! seen assoc cell-name group-name)))))

(defn- expand-error-group-edges
  "Expands edges for cells in error groups. For cells with unconditional edges,
   converts to map edge adding :on-error target. For cells with map edges,
   adds :on-error entry if not already present."
  [edges error-groups]
  (let [cell->handler (into {}
                        (mapcat (fn [[_ {:keys [cells on-error]}]]
                                  (map (fn [c] [c on-error]) cells)))
                        error-groups)]
    (reduce (fn [acc [cell-name handler-name]]
              (let [edge-def (get acc cell-name)]
                (cond
                  ;; No edge — shouldn't happen (validation catches), skip
                  (nil? edge-def) acc
                  ;; Unconditional edge → convert to map with :on-error
                  (keyword? edge-def)
                  (assoc acc cell-name {:done edge-def :on-error handler-name})
                  ;; Map edge — add :on-error if not present
                  (and (map? edge-def) (not (contains? edge-def :on-error)))
                  (assoc acc cell-name (assoc edge-def :on-error handler-name))
                  ;; Already has :on-error — leave as-is
                  :else acc)))
            edges
            cell->handler)))

(defn- inject-error-group-dispatches
  "For cells in error groups, auto-injects an :on-error dispatch predicate
   that checks for :mycelium/error. Also injects a :done predicate for cells
   whose edges were expanded from unconditional to map."
  [dispatches-map error-groups edges]
  (let [cell->handler (into {}
                        (mapcat (fn [[_ {:keys [cells on-error]}]]
                                  (map (fn [c] [c on-error]) cells)))
                        error-groups)]
    (reduce (fn [acc [cell-name _]]
              (let [dispatch-vec (get acc cell-name [])
                    has-on-error? (some #(= :on-error (first %)) dispatch-vec)
                    error-pred [:on-error (fn [d] (some? (:mycelium/error d)))]]
                (if has-on-error?
                  acc
                  (let [;; If cell had unconditional edge (now expanded to {:done X :on-error Y}),
                        ;; add [:done (constantly true)] if not present
                        edge-def (get edges cell-name)
                        has-done? (some #(= :done (first %)) dispatch-vec)
                        needs-done? (and (map? edge-def) (contains? edge-def :done) (not has-done?))
                        with-done (if needs-done?
                                    (conj (vec dispatch-vec) [:done (constantly true)])
                                    dispatch-vec)
                        ;; Insert :on-error first so it takes priority
                        {defaults true others false} (group-by #(= :default (first %)) with-done)]
                    (assoc acc cell-name (vec (concat [error-pred] others defaults)))))))
            (or dispatches-map {})
            cell->handler)))

(defn- wrap-handler-with-error-catch
  "Wraps a cell handler with try/catch. On error, returns data with
   :mycelium/error {:cell cell-name, :message msg}.
   Handles both sync (2-arity) and async (4-arity) handlers."
  [handler cell-name async?]
  (let [make-error (fn [data e]
                     (assoc data :mycelium/error
                            {:cell    cell-name
                             :message (or (ex-message e) (.toString e))}))]
    (if async?
      (fn [resources data callback error-callback]
        (try
          (handler resources data
                   callback
                   (fn [e] (callback (make-error data e))))
          (catch Throwable e
            (callback (make-error data e)))))
      (fn [resources data]
        (try
          (handler resources data)
          (catch Throwable e
            (make-error data e)))))))

(defn- apply-error-group-wrapping
  "Wraps handlers of cells in error groups with try/catch error catching."
  [state->cell error-groups]
  (if (empty? error-groups)
    state->cell
    (let [grouped-cells (set (mapcat :cells (vals error-groups)))]
      (reduce (fn [acc cell-name]
                (let [state-id (resolve-state-id cell-name)]
                  (if-let [cell (get acc state-id)]
                    (assoc acc state-id
                           (update cell :handler wrap-handler-with-error-catch cell-name (:async? cell)))
                    acc)))
              state->cell
              grouped-cells))))

;; ===== Constraint validation =====

(defn- enumerate-workflow-paths
  "Enumerates all paths from :start to terminal states.
   Returns seq of paths, each a vector of cell-name keywords.
   Each path also carries :terminal — the terminal state (:end, :error, :halt)."
  [edges]
  (loop [queue [[:start [] #{}]]
         paths []]
    (if (empty? queue)
      paths
      (let [[cell-name path-so-far visited] (first queue)
            rest-queue (rest queue)]
        (cond
          (contains? #{:end :error :halt} cell-name)
          (recur rest-queue (conj paths {:cells path-so-far :terminal cell-name}))

          (contains? visited cell-name)
          (recur rest-queue paths)

          :else
          (let [edge-def    (get edges cell-name)
                new-visited (conj visited cell-name)
                new-path    (conj path-so-far cell-name)]
            (if (keyword? edge-def)
              (recur (conj (vec rest-queue) [edge-def new-path new-visited])
                     paths)
              (let [next-items (mapv (fn [[_ target]]
                                       [target new-path new-visited])
                                     edge-def)]
                (recur (into (vec rest-queue) next-items)
                       paths)))))))))

(defn- check-constraint
  "Checks a single constraint against all paths. Returns nil if satisfied,
   or an error string describing the violation."
  [constraint paths]
  (let [type (:type constraint)]
    (case type
      :must-follow
      (let [{:keys [if then]} constraint]
        (some (fn [{:keys [cells]}]
                (let [cell-set (set cells)]
                  (when (contains? cell-set if)
                    (let [if-idx (.indexOf (vec cells) if)
                          after  (set (drop (inc if-idx) cells))]
                      (when-not (contains? after then)
                        (str "Constraint :must-follow violated: " if " appears on path "
                             cells " but " then " does not follow it"))))))
              paths))

      :must-precede
      (let [{:keys [cell before]} constraint]
        (some (fn [{:keys [cells]}]
                (let [cell-set (set cells)]
                  (when (contains? cell-set before)
                    (let [before-idx (.indexOf (vec cells) before)
                          preceding  (set (take before-idx cells))]
                      (when-not (contains? preceding cell)
                        (str "Constraint :must-precede violated: " cell " must appear before "
                             before " on path " cells " but does not"))))))
              paths))

      :never-together
      (let [constraint-cells (set (:cells constraint))]
        (some (fn [{:keys [cells]}]
                (let [cell-set (set cells)
                      overlap  (set/intersection cell-set constraint-cells)]
                  (when (= (count overlap) (count constraint-cells))
                    (str "Constraint :never-together violated: "
                         (seq constraint-cells) " all appear on path " cells))))
              paths))

      :always-reachable
      (let [target-cell (:cell constraint)
            end-paths   (filter #(= :end (:terminal %)) paths)]
        (when (seq end-paths)
          (some (fn [{:keys [cells]}]
                  (when-not (contains? (set cells) target-cell)
                    (str "Constraint :always-reachable violated: " target-cell
                         " does not appear on path " cells " (which reaches :end)")))
                end-paths)))

      ;; Unknown type
      (throw (ex-info (str "Unknown constraint type: " type)
                      {:constraint constraint})))))

(defn- validate-constraints!
  "Validates all constraints against enumerated workflow paths.
   Throws on the first violation found."
  [constraints edges]
  (when (seq constraints)
    (let [paths (enumerate-workflow-paths edges)]
      (doseq [constraint constraints]
        (when-let [error (check-constraint constraint paths)]
          (throw (ex-info error {:constraint constraint})))))))

(declare validate-transforms!)

(defn validate-workflow
  "Runs all validations on a workflow definition.
   Merges :default-dispatches from cell specs as fallback for cells without explicit dispatches."
  [{:keys [cells edges dispatches joins input-schema pipeline] :as raw-workflow}]
  (let [{:keys [cells edges dispatches joins input-schema]} (expand-pipeline raw-workflow)]
    (validate-cells-exist! cells)
    ;; Validate and expand error groups (must happen before edge/dispatch validation)
    (let [error-groups (or (:error-groups raw-workflow) {})
          _            (when (seq error-groups)
                         (validate-error-groups! error-groups cells))
          edges        (if (seq error-groups)
                         (expand-error-group-edges edges error-groups)
                         edges)
          dispatches   (if (seq error-groups)
                         (inject-error-group-dispatches dispatches error-groups edges)
                         dispatches)]
      ;; Validate :default edge usage (must not be sole edge)
      (validate-default-edges! edges)
      ;; Normalize to {name → cell-id} for all downstream validation
      (let [cell-ids (cells->ids cells)]
        ;; Validate :input-schema well-formedness if present
        (when input-schema
          (v/validate-malli-schema! input-schema "input-schema"))
        ;; Validate :resilience policies if present
        (when-let [resilience (:resilience raw-workflow)]
          (resilience/validate-resilience! resilience cell-ids))
        ;; Validate join definitions
        (let [joins-map (or joins {})]
          (when (seq joins-map)
            (validate-join-defs! joins-map cell-ids edges)
            (validate-join-output-conflicts! joins-map cell-ids))
          ;; For edge-target and reachability validation, include join names as valid targets
          (let [cell-names     (set (keys cell-ids))
                join-names     (set (keys joins-map))
                join-members   (set (mapcat :cells (vals joins-map)))
                edge-cell-names (set/difference cell-names join-members)
                valid-names    (set/union edge-cell-names join-names)]
            (v/validate-edge-targets! edges valid-names)
            (v/validate-reachability! edges valid-names))
          ;; Merge default dispatches — include join default dispatches (filtered to match edge keys)
          ;; Also auto-inject :default catch-all predicates
          ;; Join dispatches take priority (merged last) since joins compute their own dispatches
          (let [timeouts-map        (or (:timeouts raw-workflow) {})
                with-timeouts      (inject-timeout-dispatches dispatches timeouts-map edges)
                with-defaults      (inject-default-dispatches with-timeouts edges)
                join-dispatches    (compute-join-dispatches joins-map edges)
                effective-dispatches (merge (merge-default-dispatches with-defaults edges cell-ids)
                                            join-dispatches)]
            (v/validate-dispatch-coverage! edges effective-dispatches))
          ;; Validate transforms before schema chain (transforms affect key availability)
          (when-let [transforms (:transforms raw-workflow)]
            (validate-transforms! transforms cells edges))
          (validate-schema-chain! edges cell-ids joins-map (:transforms raw-workflow))
          ;; Validate graph-level timeouts
          (when-let [timeouts (:timeouts raw-workflow)]
            (validate-timeouts! timeouts cells edges))
          ;; Validate constraints (after all structural validations pass)
          (when-let [constraints (:constraints raw-workflow)]
            (validate-constraints! constraints edges)))))))

;; ===== Edge transform validation & compilation =====

(defn- validate-transform-spec!
  "Validates a single transform spec {:fn f, :schema {:input [...] :output [...]}}."
  [cell-name label spec]
  (when-not (and (map? spec) (ifn? (:fn spec)))
    (throw (ex-info (str "Transform " label " on cell " cell-name " must be a function (:fn key required)")
                    {:cell-name cell-name :label label :spec spec})))
  (when-let [schema (:schema spec)]
    (when (:input schema)
      (v/validate-malli-schema! (:input schema) (str "Transform " label " on " cell-name " :input")))
    (when (:output schema)
      (v/validate-malli-schema! (:output schema) (str "Transform " label " on " cell-name " :output")))))

(defn- validate-transforms!
  "Validates the :transforms map at compile time.
   Each key must be a valid cell name, edge labels must match the cell's edges,
   and transform specs must have :fn (function) and optional :schema."
  [transforms cells edges]
  (let [cell-names (set (keys cells))]
    (doseq [[cell-name transform-def] transforms]
      (when-not (contains? cell-names cell-name)
        (throw (ex-info (str "Transform references nonexistent cell " cell-name)
                        {:cell-name cell-name :valid-cells cell-names})))
      (let [edge-def (get edges cell-name)]
        (if (map? edge-def)
          ;; Branching cell: keys are edge labels or :input
          (doseq [[k v] transform-def]
            (cond
              (= k :input)
              (validate-transform-spec! cell-name :input v)

              (contains? edge-def k)
              (do
                (when-not (map? v)
                  (throw (ex-info (str "Transform edge " k " on cell " cell-name
                                       " must be a map with :input/:output keys")
                                  {:cell-name cell-name :label k :value v})))
                (when (:input v) (validate-transform-spec! cell-name (str k " :input") (:input v)))
                (when (:output v) (validate-transform-spec! cell-name (str k " :output") (:output v))))

              :else
              (throw (ex-info (str "Transform on cell " cell-name " has invalid edge label " k
                                   ". Valid edges: " (keys edge-def))
                              {:cell-name cell-name :label k :valid-edges (keys edge-def)}))))
          ;; Unconditional cell: keys are :input/:output
          (let [invalid-keys (disj (set (keys transform-def)) :input :output)]
            (when (seq invalid-keys)
              (throw (ex-info (str "Transform on cell " cell-name " has invalid keys " invalid-keys
                                   ". Unconditional cells only support :input and :output")
                              {:cell-name cell-name :invalid-keys invalid-keys})))
            (when (:input transform-def)
              (validate-transform-spec! cell-name :input (:input transform-def)))
            (when (:output transform-def)
              (validate-transform-spec! cell-name :output (:output transform-def)))))))))

(defn- build-transform-maps
  "Builds input and output transform lookup maps from the :transforms workflow key.
   Returns {:input {state-id -> fn}, :output {state-id -> fn-or-{transition -> fn}}}."
  [transforms edges]
  (reduce
    (fn [acc [cell-name transform-def]]
      (let [state-id (resolve-state-id cell-name)
            edge-def (get edges cell-name)]
        (if (map? edge-def)
          ;; Branching cell: extract top-level :input and per-edge :output
          (let [input-fn (some-> (:input transform-def) :fn)
                output-map (into {}
                                 (keep (fn [[k v]]
                                         (when (and (not= k :input) (map? v) (:output v))
                                           [k (get-in v [:output :fn])])))
                                 transform-def)]
            (cond-> acc
              input-fn       (assoc-in [:input state-id] input-fn)
              (seq output-map) (assoc-in [:output state-id] output-map)))
          ;; Unconditional cell
          (cond-> acc
            (:input transform-def)  (assoc-in [:input state-id] (get-in transform-def [:input :fn]))
            (:output transform-def) (assoc-in [:output state-id] (get-in transform-def [:output :fn]))))))
    {:input {} :output {}}
    transforms))

;; ===== Workflow-level interceptor matching =====

(defn- glob-match?
  "Simple glob matching: supports * as a wildcard for a single path segment.
   E.g., \"ui/*\" matches \"ui/render-dashboard\" but not \"ui/sub/thing\"."
  [pattern s]
  (let [regex-str (-> pattern
                      (clojure.string/replace "." "\\.")
                      (clojure.string/replace "*" "[^/]*"))
        regex (re-pattern (str "^" regex-str "$"))]
    (some? (re-matches regex s))))

(defn- match-scope
  "Checks if a cell matches an interceptor scope.
   scope can be:
     :all — matches everything
     {:id-match \"ui/*\"} — cell :id matches glob
     {:cells [:x :y]} — cell name is in the list"
  [scope cell-name cell-id]
  (cond
    (= scope :all)
    true

    (and (map? scope) (:id-match scope))
    (glob-match? (:id-match scope) (str (namespace cell-id) "/" (name cell-id)))

    (and (map? scope) (:cells scope))
    (contains? (set (:cells scope)) cell-name)

    :else false))

(def ^:private preserved-mycelium-keys
  "Mycelium keys that survive key propagation (stripped from input, re-attached after merge)."
  #{:mycelium/warnings})

(defn- strip-mycelium-keys
  "Removes :mycelium/* keys from a data map for key propagation.
   Preserves keys listed in `preserved-mycelium-keys`."
  [data]
  (into {} (remove (fn [[k _]] (and (keyword? k)
                                     (= "mycelium" (namespace k))
                                     (not (contains? preserved-mycelium-keys k))))) data))

(defn- wrap-handler-with-propagation
  "Wraps a cell handler so that input keys automatically propagate to output.
   Output = (merge (strip-mycelium-keys input) (handler resources input)).
   Handler output takes precedence over input keys.
   Handles both sync (2-arity) and async (4-arity) handlers."
  [handler]
  (fn
    ([resources data]
     (let [result (handler resources data)]
       (merge (strip-mycelium-keys data) result)))
    ([resources data callback error-callback]
     (handler resources data
              (fn [result]
                (callback (merge (strip-mycelium-keys data) result)))
              error-callback))))

(defn- wrap-handler-with-params
  "Wraps a cell handler to inject :mycelium/params into data before invocation.
   Handles both sync (2-arity) and async (4-arity) handlers."
  [handler params]
  (fn
    ([resources data]
     (handler resources (assoc data :mycelium/params params)))
    ([resources data callback error-callback]
     (handler resources (assoc data :mycelium/params params) callback error-callback))))

(defn- apply-interceptor-fns [data fns]
  (reduce #(%2 %1) data fns))

(defn- wrap-handler-with-interceptors
  "Wraps a cell handler with matching workflow-level interceptors.
   Handles both sync (2-arity) and async (4-arity) handlers.
   Interceptors apply in declaration order: first pre runs first, first post runs first."
  [handler matching-interceptors]
  (if (empty? matching-interceptors)
    handler
    (let [pres  (seq (keep :pre matching-interceptors))
          posts (seq (keep :post matching-interceptors))]
      (fn
        ;; Sync: (fn [resources data] -> data)
        ([resources data]
         (let [data'   (if pres (apply-interceptor-fns data pres) data)
               result  (handler resources data')
               result' (if posts (apply-interceptor-fns result posts) result)]
           result'))
        ;; Async: (fn [resources data callback error-callback])
        ([resources data callback error-callback]
         (let [data' (if pres (apply-interceptor-fns data pres) data)]
           (handler resources data'
                    (fn [result]
                      (callback (if posts (apply-interceptor-fns result posts) result)))
                    error-callback)))))))

(defn- apply-workflow-interceptors
  "For each cell, finds matching interceptors and wraps the handler.
   cells-map: {cell-name -> cell-id}
   interceptors: [{:id :scope :pre :post}]
   Returns updated state->cell map with wrapped handlers."
  [state->cell cells-map interceptors]
  (if (empty? interceptors)
    state->cell
    (reduce (fn [acc [state-id cell]]
              (let [cell-name (some (fn [[cname _]]
                                     (when (= (resolve-state-id cname) state-id)
                                       cname))
                                   cells-map)
                    cell-id   (:id cell)
                    matching  (filterv #(match-scope (:scope %) cell-name cell-id) interceptors)]
                (if (seq matching)
                  (assoc acc state-id
                         (update cell :handler wrap-handler-with-interceptors matching))
                  acc)))
            state->cell
            state->cell)))

;; ===== Compilation =====

(defn- build-edge-targets
  "Builds a reverse map from edge definitions for the post-interceptor.
   Returns {resolved-state-id → {resolved-target-state-id → transition-label}}.
   Used to infer which transition was taken from the dispatch target."
  [edges]
  (into {}
    (keep (fn [[cell-name edge-def]]
            (when (map? edge-def)
              [(resolve-state-id cell-name)
               (into {}
                 (map (fn [[label target]]
                        [(resolve-state-id target) label]))
                 edge-def)])))
    edges))

(defn compile-workflow
  "Compiles a workflow definition into a Maestro FSM.
   Returns the compiled (ready-to-run) FSM."
  ([workflow] (compile-workflow workflow {}))
  ([raw-workflow opts]
   (let [{:keys [cells edges dispatches joins] :as workflow} (expand-pipeline raw-workflow)]
     ;; Validate
     (validate-workflow workflow)
     (let [;; Expand error groups (edges + dispatches) before other processing
           error-groups (or (:error-groups workflow) {})
           edges        (if (seq error-groups)
                          (expand-error-group-edges edges error-groups)
                          edges)
           dispatches   (if (seq error-groups)
                          (inject-error-group-dispatches dispatches error-groups edges)
                          dispatches)
           ;; Normalize cells to {name → cell-id} for compilation
           cell-ids (cells->ids cells)
           cell-params (cells->params cells)
           joins-map (or joins {})
         ;; Determine which cells are consumed by joins
         join-members (set (mapcat :cells (vals joins-map)))
         ;; Merge timeout, default, and join dispatches
         timeouts-map       (or (:timeouts workflow) {})
         with-timeouts      (inject-timeout-dispatches dispatches timeouts-map edges)
         with-defaults      (inject-default-dispatches with-timeouts edges)
         join-dispatches    (compute-join-dispatches joins-map edges)
         effective-dispatches (merge (merge-default-dispatches with-defaults edges cell-ids)
                                     join-dispatches)
         ;; Build state->cell map for non-join-member cells
         state->cell (into {}
                           (keep (fn [[cell-name cell-id]]
                                   (when-not (contains? join-members cell-name)
                                     [(resolve-state-id cell-name)
                                      (cell/get-cell! cell-id)])))
                           cell-ids)
         ;; Build synthetic join cell specs for state->cell
         join-cell-specs (into {}
                               (map (fn [[join-name join-def]]
                                      (let [handler   (build-join-handler join-name join-def cell-ids)
                                            in-schema (build-join-input-schema join-def cell-ids)]
                                        [(resolve-state-id join-name)
                                         {:id       (keyword "mycelium.join" (name join-name))
                                          :handler  handler
                                          :schema   {:input  in-schema
                                                     :output :map}
                                          :default-dispatches join-default-dispatches}])))
                               joins-map)
         state->cell (merge state->cell join-cell-specs)
         ;; Pre-compile all Malli schemas so interceptors use compiled objects
         state->cell (schema/pre-compile-schemas state->cell)
         ;; Apply params wrapping for parameterized cells
         state->cell (reduce (fn [acc [cell-name params]]
                               (let [state-id (resolve-state-id cell-name)]
                                 (if-let [cell (get acc state-id)]
                                   (assoc acc state-id
                                          (update cell :handler wrap-handler-with-params params))
                                   acc)))
                             state->cell
                             cell-params)
         ;; Apply resilience policies
         resilience-map (:resilience workflow)
         state->cell (if (seq resilience-map)
                       (reduce (fn [acc [cell-name policies]]
                                 (let [state-id (resolve-state-id cell-name)]
                                   (if-let [cell (get acc state-id)]
                                     (assoc acc state-id
                                            (update cell :handler
                                                    resilience/wrap-handler cell-name policies
                                                    {:async? (:async? cell)}))
                                     acc)))
                               state->cell
                               resilience-map)
                       state->cell)
         ;; Apply graph-level timeouts (wraps handlers with timeout logic)
         state->cell (apply-graph-timeouts state->cell timeouts-map)
         ;; Apply error group wrapping (try/catch → :mycelium/error)
         state->cell (apply-error-group-wrapping state->cell error-groups)
         ;; Apply workflow-level interceptors (wraps cell handlers)
         wf-interceptors (:interceptors workflow)
         state->cell (apply-workflow-interceptors state->cell cell-ids wf-interceptors)
         ;; Apply key propagation wrapping (merge input → output)
         ;; Enabled by default — aligns runtime with schema chain validator's
         ;; key accumulation assumption. Disable with :propagate-keys? false.
         state->cell (if (not (false? (:propagate-keys? opts)))
                       (into {} (map (fn [[state-id cell]]
                                       [state-id (update cell :handler wrap-handler-with-propagation)]))
                             state->cell)
                       state->cell)
         ;; Build state->edge-targets for post-interceptor transition lookup
         state->edge-targets (build-edge-targets edges)
         ;; Build state->names for human-readable trace entries
         state->names (merge
                       (into {}
                             (map (fn [[cell-name _]]
                                    [(resolve-state-id cell-name) cell-name]))
                             cell-ids)
                       (into {}
                             (map (fn [[join-name _]]
                                    [(resolve-state-id join-name) join-name]))
                             joins-map))
         ;; Build Maestro FSM states — non-join-member cells only
         fsm-cell-states (into {}
                               (keep (fn [[cell-name cell-id]]
                                       (when-not (contains? join-members cell-name)
                                         (let [state-id  (resolve-state-id cell-name)
                                               cell      (get state->cell state-id)
                                               edge-def  (get edges cell-name)
                                               dispatch-vec (get effective-dispatches cell-name)]
                                           [state-id
                                            (merge
                                             {:handler    (:handler cell)
                                              :dispatches (compile-edges edge-def dispatch-vec)}
                                             (when (:async? cell)
                                               {:async? true}))]))))
                               cell-ids)
         ;; Build FSM states for joins (use interceptor-wrapped handlers from state->cell)
         fsm-join-states (into {}
                               (map (fn [[join-name _join-def]]
                                      (let [state-id     (resolve-state-id join-name)
                                            join-cell    (get state->cell state-id)
                                            edge-def     (get edges join-name)
                                            dispatch-vec (get effective-dispatches join-name)]
                                        [state-id
                                         {:handler    (:handler join-cell)
                                          :dispatches (compile-edges edge-def dispatch-vec)}])))
                               joins-map)
         fsm-states (merge fsm-cell-states fsm-join-states)
         ;; Build transform maps from :transforms
         transform-maps (when-let [transforms (:transforms workflow)]
                          (build-transform-maps transforms edges))
         ;; Build interceptors — compose custom pre/post with schema interceptors
         schema-opts (-> (select-keys opts [:coerce? :on-trace :validate])
                        (assoc :state->names state->names)
                        (cond->
                          transform-maps (assoc :input-transforms  (:input transform-maps)
                                                :output-transforms (:output transform-maps))))
         schema-pre  (schema/make-pre-interceptor state->cell schema-opts)
         schema-post (schema/make-post-interceptor state->cell state->edge-targets state->names schema-opts)
         pre  (if-let [custom-pre (:pre opts)]
                (fn [fsm-state resources]
                  (-> (schema-pre fsm-state resources)
                      (custom-pre resources)))
                schema-pre)
         post (if-let [custom-post (:post opts)]
                (fn [fsm-state resources]
                  (-> (schema-post fsm-state resources)
                      (custom-post resources)))
                schema-post)
         ;; Merge custom on-end / on-error handlers
         spec {:fsm  (merge fsm-states
                            (when-let [on-error (:on-error opts)]
                              {::fsm/error {:handler on-error}})
                            (when-let [on-end (:on-end opts)]
                              {::fsm/end {:handler on-end}}))
               :opts {:pre  pre
                      :post post}}
         ;; Static analysis: catch structural issues Maestro can detect
         analysis (fsm/analyze spec)]
     (when (seq (:no-path-to-end analysis))
       (let [names (mapv #(get state->names % %) (:no-path-to-end analysis))]
         (throw (ex-info (str "States with no path to end: " names)
                         {:no-path-to-end names :analysis analysis}))))
     (fsm/compile spec)))))

(ns mycelium.manifest
  "Manifest loading, validation, cell-brief generation, and workflow construction."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [mycelium.cell :as cell]
            [mycelium.fragment :as fragment]
            [mycelium.validation :as v]))

;; ===== Cell definition validation =====

(defn- validate-cell-def!
  "Validates a single cell definition within a manifest.
   Skips schema validation for :schema :inherit (resolved separately)."
  [cell-name cell-def]
  (when-not (:id cell-def)
    (throw (ex-info (str "Cell " cell-name " missing :id") {:cell-name cell-name})))
  (when-not (:schema cell-def)
    (throw (ex-info (str "Cell " cell-name " missing :schema") {:cell-name cell-name})))
  (when-not (= :inherit (:schema cell-def))
    (when-not (get-in cell-def [:schema :input])
      (throw (ex-info (str "Cell " cell-name " missing :schema :input") {:cell-name cell-name})))
    (when-not (get-in cell-def [:schema :output])
      (throw (ex-info (str "Cell " cell-name " missing :schema :output") {:cell-name cell-name})))
    (v/validate-malli-schema! (get-in cell-def [:schema :input]) (str cell-name " :input"))
    (v/validate-output-schema! (get-in cell-def [:schema :output]) (str cell-name " :output"))))

(defn- resolve-inherit-schemas
  "Resolves :schema :inherit in cell definitions by looking up schemas from cell registry."
  [cells]
  (into {}
    (map (fn [[cell-name cell-def]]
           (if (= :inherit (:schema cell-def))
             (let [cell-id (:id cell-def)
                   cell (cell/get-cell cell-id)]
               (when-not cell
                 (throw (ex-info (str "Cell " cell-name " has :schema :inherit but "
                                      cell-id " is not registered")
                                 {:cell-name cell-name :cell-id cell-id})))
               (when-not (:schema cell)
                 (throw (ex-info (str "Cell " cell-name " has :schema :inherit but "
                                      cell-id " has no schema in registry")
                                 {:cell-name cell-name :cell-id cell-id})))
               [cell-name (assoc cell-def :schema (:schema cell))])
             [cell-name cell-def])))
    cells))

;; ===== Pipeline expansion =====

(defn- expand-pipeline
  "Expands a :pipeline vector into :edges and :dispatches.
   Pipeline is mutually exclusive with :edges, :dispatches, :fragments, and :joins."
  [{:keys [pipeline cells edges dispatches fragments joins] :as manifest}]
  (if-not pipeline
    manifest
    (do
      (when edges
        (throw (ex-info ":pipeline is mutually exclusive with :edges"
                        {:id (:id manifest)})))
      (when dispatches
        (throw (ex-info ":pipeline is mutually exclusive with :dispatches"
                        {:id (:id manifest)})))
      (when fragments
        (throw (ex-info ":pipeline is mutually exclusive with :fragments"
                        {:id (:id manifest)})))
      (when joins
        (throw (ex-info ":pipeline is mutually exclusive with :joins"
                        {:id (:id manifest)})))
      (when (empty? pipeline)
        (throw (ex-info ":pipeline must have at least 1 element"
                        {:id (:id manifest)})))
      (let [cell-names (set (keys cells))]
        (doseq [cell-name pipeline]
          (when-not (contains? cell-names cell-name)
            (throw (ex-info (str "Pipeline references " cell-name " which is not in :cells")
                            {:id (:id manifest) :pipeline-ref cell-name
                             :valid-cells cell-names})))))
      (let [pairs (partition 2 1 pipeline)
            edges (into {} (concat
                            (map (fn [[from to]] [from to]) pairs)
                            [[(last pipeline) :end]]))]
        (-> manifest
            (dissoc :pipeline)
            (assoc :edges edges
                   :dispatches {}))))))

;; ===== Region validation =====

(defn- validate-regions!
  "Validates :regions definitions. Each region's cells must exist in :cells,
   and no cell may appear in multiple regions."
  [regions cells]
  (let [cell-names (set (keys cells))
        seen (atom {})]
    (doseq [[region-name region-cells] regions]
      (doseq [cell-name region-cells]
        (when-not (contains? cell-names cell-name)
          (throw (ex-info (str "region " region-name " references nonexistent cell " cell-name)
                          {:region region-name :cell cell-name})))
        (when-let [other-region (get @seen cell-name)]
          (throw (ex-info (str "Cell " cell-name " appears in multiple regions: "
                               other-region " and " region-name)
                          {:cell cell-name :regions [other-region region-name]})))
        (swap! seen assoc cell-name region-name)))))

;; ===== Manifest validation =====

(defn- validate-on-error!
  "Validates :on-error declarations on cells.
   In strict mode, missing :on-error throws. In non-strict mode, it's ignored.
   If :on-error is present and non-nil, the target must be a valid cell name."
  [cells strict?]
  (let [cell-names (set (keys cells))]
    (doseq [[cell-name cell-def] cells]
      (if (contains? cell-def :on-error)
        ;; :on-error is present — if non-nil, validate the target
        (when-let [target (:on-error cell-def)]
          (when-not (contains? cell-names target)
            (throw (ex-info (str "Cell " cell-name " :on-error references " target
                                 " which is not found in :cells")
                            {:cell-name cell-name :on-error target
                             :valid-cells cell-names}))))
        ;; :on-error is missing
        (when strict?
          (throw (ex-info (str "Cell " cell-name " missing :on-error declaration. "
                               "Add :on-error with a target cell keyword or nil.")
                          {:cell-name cell-name})))))))

(defn validate-manifest
  "Validates a manifest structure. Returns the manifest if valid, throws otherwise.
   Optional opts map:
     :strict? — if true (default), requires :on-error on every cell.
                Set to false to allow missing :on-error (legacy mode)."
  ([manifest] (validate-manifest manifest {:strict? true}))
  ([manifest opts]
   (let [{:keys [id cells edges dispatches joins] :as manifest} (expand-pipeline manifest)]
     (when-not id
       (throw (ex-info "Manifest missing :id" {:manifest manifest})))
     (when-not cells
       (throw (ex-info "Manifest missing :cells" {:id id})))
     (when-not edges
       (throw (ex-info "Manifest missing :edges" {:id id})))
     ;; Resolve :schema :inherit before validation
     (let [cells    (resolve-inherit-schemas cells)
           manifest (assoc manifest :cells cells)]
       ;; Validate each cell definition
       (doseq [[cell-name cell-def] cells]
         (validate-cell-def! cell-name cell-def))
       ;; Validate :on-error declarations
       (validate-on-error! cells (:strict? opts))
       ;; Determine join members — cells consumed by joins don't need edges
       (let [joins-map    (or joins {})
             join-members (set (mapcat :cells (vals joins-map)))
             cell-names   (set (keys cells))
             join-names   (set (keys joins-map))
             ;; Valid edge targets include non-member cells + join names
             edge-cell-names (set/difference cell-names join-members)
             valid-names     (set/union edge-cell-names join-names)]
         (v/validate-edge-targets! edges valid-names)
         ;; Only require edge entries for non-join-member cells
         (doseq [[cell-name _] cells]
           (when-not (or (get edges cell-name)
                         (contains? join-members cell-name))
             (throw (ex-info (str "Cell " cell-name " has no edges defined")
                             {:cell-name cell-name}))))
         ;; Inject join default dispatches for join nodes with map edges
         (let [join-default-dispatches [[:failure (fn [d] (some? (:mycelium/join-error d)))]
                                        [:done    (fn [d] (not (:mycelium/join-error d)))]]
               join-dispatches (reduce (fn [acc [join-name _]]
                                         (let [edge-def (get edges join-name)]
                                           (if (and (map? edge-def)
                                                    (not (get (or dispatches {}) join-name)))
                                             (let [edge-keys (set (keys edge-def))
                                                   filtered  (filterv (fn [[label _]]
                                                                        (contains? edge-keys label))
                                                                      join-default-dispatches)]
                                               (if (seq filtered)
                                                 (assoc acc join-name filtered)
                                                 acc))
                                             acc)))
                                       {}
                                       joins-map)
               effective-dispatches (merge join-dispatches (or dispatches {}))]
           (v/validate-dispatch-coverage! edges effective-dispatches))
         (v/validate-reachability! edges valid-names))
       ;; Validate :input-schema if present
       (when-let [input-schema (:input-schema manifest)]
         (v/validate-malli-schema! input-schema "input-schema"))
       ;; Validate :regions if present
       (when-let [regions (:regions manifest)]
         (validate-regions! regions cells))
       manifest))))

;; ===== Fragment expansion =====

(def expand-fragments
  "Expands all :fragments in a manifest, merging fragment cells/edges/dispatches into the host.
   See mycelium.fragment/expand-all-fragments."
  fragment/expand-all-fragments)

;; ===== Manifest loading =====

(defn load-manifest
  "Loads and validates a manifest from an EDN file path.
   If the manifest contains :fragments, expands them before validation."
  [path]
  (let [content (slurp path)
        manifest (edn/read-string content)
        expanded (if (:fragments manifest)
                   (expand-fragments manifest)
                   manifest)]
    (validate-manifest expanded)))

;; ===== Cell brief generation =====

(defn- generate-example
  "Generates example data from a Malli schema."
  [schema]
  (try
    (mg/generate (m/schema schema) {:size 3 :seed 42})
    (catch Exception _
      {:example "could not generate"})))

(defn- format-output-schema-section
  "Formats output schema section for the cell-brief prompt.
   Handles both single-schema (vector) and per-edge (map) formats."
  [output-schema edge-labels]
  (if (map? output-schema)
    (str "Output schemas (per edge):\n"
         (str/join "\n"
                   (map (fn [label]
                          (str "  " (pr-str label) ": " (pr-str (get output-schema label))))
                        (sort edge-labels))))
    (str "Output schema:\n  " (pr-str output-schema))))

(defn- generate-output-examples
  "Generates output examples. For per-edge schema maps, generates one per edge."
  [output-schema edge-labels]
  (if (map? output-schema)
    (into {}
          (map (fn [label]
                 [label (generate-example (get output-schema label))]))
          (sort edge-labels))
    (generate-example output-schema)))

(defn cell-brief
  "Extracts a self-contained brief for a single cell from a manifest.
   Returns a map with :id, :doc, :schema, :requires, :examples, :prompt."
  [manifest cell-name]
  (let [cell-def    (get-in manifest [:cells cell-name])
        _           (when-not cell-def
                      (throw (ex-info (str "Cell " cell-name " not found in manifest")
                                      {:cell-name cell-name :manifest-id (:id manifest)})))
        {:keys [id doc schema requires]} cell-def
        edge-def    (get-in manifest [:edges cell-name])
        edge-labels (when (map? edge-def) (set (keys edge-def)))
        dispatches  (get-in manifest [:dispatches cell-name])
        input-ex    (generate-example (:input schema))
        output-ex   (generate-output-examples (:output schema) edge-labels)
        output-section (format-output-schema-section (:output schema) edge-labels)
        example-section (if (map? (:output schema))
                          (str/join "\n\n"
                                    (map (fn [[label ex]]
                                           (str "Example output (" (pr-str label) "):\n  " (pr-str ex)))
                                         (sort-by first output-ex)))
                          (str "Example output:\n  " (pr-str output-ex)))
        dispatch-section (when dispatches
                           (str "Dispatch predicates (checked in order, first match wins):\n"
                                (str/join "\n"
                                          (map (fn [[label _]]
                                                 (str "  " (pr-str label) " — predicate evaluates your output"))
                                               dispatches))
                                "\n"))
        prompt      (str "## Cell: " id "\n"
                         (when doc (str "\n## Purpose\n" doc "\n"))
                         "\n## Contract\n\n"
                         "Input schema:\n  " (pr-str (:input schema)) "\n\n"
                         output-section "\n\n"
                         "Required resources: " (if (seq requires)
                                                  (str/join ", " (map pr-str requires))
                                                  "none") "\n\n"
                         (when dispatch-section
                           (str dispatch-section "\n"))
                         "\n## Example Data\n\n"
                         "Example input:\n  " (pr-str input-ex) "\n\n"
                         example-section "\n"
                         "\n## Rules\n"
                         "- Handler signature: (fn [resources data] -> data)\n"
                         "- Return enriched data — dispatch predicates in the workflow determine routing\n"
                         "- MUST NOT require or call any other cell's namespace\n"
                         "- Output data MUST pass the output schema validation\n")]
    {:id          id
     :doc         doc
     :schema      schema
     :requires    (or requires [])
     :examples    {:input input-ex :output output-ex}
     :prompt      prompt}))

;; ===== Manifest → Workflow =====

(defn manifest->workflow
  "Converts a manifest into a workflow definition map.
   For each cell in the manifest:
   - If not already registered, registers a stub handler with the full manifest spec.
   - If already registered, applies manifest metadata via `set-cell-meta!`.
   The manifest is the single source of truth for schemas and requires.
   Returns {:cells ... :edges ... :dispatches ...} suitable for `workflow/compile-workflow`."
  [{:keys [cells edges dispatches] :as manifest}]
  (let [cells    (resolve-inherit-schemas cells)
        cell-refs (into {}
                        (map (fn [[cell-name cell-def]]
                               (let [{:keys [id schema requires params]} cell-def]
                                 (if (cell/get-cell id)
                                   (cell/set-cell-meta! id {:schema   schema
                                                            :requires (or requires [])})
                                   (let [stub {:id      id
                                               :handler (fn [_ data] data)
                                               :schema  schema
                                               :requires (or requires [])
                                               :doc     (:doc cell-def)}]
                                     (.addMethod cell/cell-spec id (constantly stub))
                                     stub))
                                 ;; Emit map form when params present, bare keyword otherwise
                                 [cell-name (if params
                                              {:id id :params params}
                                              id)])))
                        cells)]
    (cond-> {:cells cell-refs
             :edges edges}
      dispatches (assoc :dispatches dispatches)
      (:joins manifest) (assoc :joins (:joins manifest))
      (:input-schema manifest) (assoc :input-schema (:input-schema manifest))
      (:interceptors manifest) (assoc :interceptors (:interceptors manifest))
      (:resilience manifest) (assoc :resilience (:resilience manifest)))))

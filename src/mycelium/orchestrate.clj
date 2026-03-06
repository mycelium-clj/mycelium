(ns mycelium.orchestrate
  "Agent orchestration: brief generation, reassignment, planning, progress."
  (:require [clojure.string :as str]
            [mycelium.manifest :as manifest]
            [mycelium.dev :as dev]))

(defn cell-briefs
  "Returns a map of cell-name → brief for every cell in the manifest."
  [manifest-data]
  (into {}
        (map (fn [[cell-name _]]
               [cell-name (manifest/cell-brief manifest-data cell-name)]))
        (:cells manifest-data)))

(defn reassignment-brief
  "Generates a targeted brief that includes failure context.
   `error-context` should be {:error \"...\", :input {...}, :output {...}}"
  [manifest-data cell-name {:keys [error input output]}]
  (let [base-brief (manifest/cell-brief manifest-data cell-name)
        error-section (str "\n\n## Previous Implementation Failed\n\n"
                          "Error: " error "\n\n"
                          "Given input: " (pr-str input) "\n\n"
                          "Your handler returned: " (pr-str output) "\n\n"
                          "Fix the handler to satisfy the output schema.\n")]
    (assoc base-brief
           :prompt (str (:prompt base-brief) error-section))))

(defn region-brief
  "Generates a scoped brief for a named region in a manifest.
   Returns {:cells [...], :internal-edges {...}, :entry-points [...],
            :exit-points [...], :prompt \"...\"}."
  [manifest-data region-name]
  (let [regions (:regions manifest-data)
        region-cells (get regions region-name)]
    (when-not region-cells
      (throw (ex-info (str "Unknown region " region-name " — not found in :regions")
                      {:region region-name :available (keys regions)})))
    (let [region-set   (set region-cells)
          all-edges    (:edges manifest-data)
          all-cells    (:cells manifest-data)
          ;; Cell info
          cells-info   (mapv (fn [cell-name]
                               (let [cell-def (get all-cells cell-name)]
                                 {:name   cell-name
                                  :id     (:id cell-def)
                                  :doc    (:doc cell-def)
                                  :schema (:schema cell-def)}))
                             region-cells)
          ;; Internal edges: edges where both source and all targets are within the region
          internal-edges (into {}
                           (keep (fn [cell-name]
                                   (let [edge-def (get all-edges cell-name)]
                                     (when edge-def
                                       (if (keyword? edge-def)
                                         (when (contains? region-set edge-def)
                                           [cell-name edge-def])
                                         (let [internal (into {}
                                                         (filter (fn [[_ target]]
                                                                   (contains? region-set target)))
                                                         edge-def)]
                                           (when (seq internal)
                                             [cell-name internal])))))))
                           region-cells)
          ;; Entry points: region cells that have incoming edges from outside the region
          ;; :start is always an entry point if it's in the region (implicit workflow entry)
          external-targets (set
                             (concat
                               [:start] ;; implicit entry from Maestro
                               (mapcat (fn [[cell-name edge-def]]
                                         (when-not (contains? region-set cell-name)
                                           (if (keyword? edge-def)
                                             [edge-def]
                                             (vals edge-def))))
                                       all-edges)))
          entry-points (filterv #(contains? external-targets %) region-cells)
          ;; Exit points: region cells with edges going outside the region
          exit-points (into []
                        (keep (fn [cell-name]
                                (let [edge-def (get all-edges cell-name)]
                                  (when edge-def
                                    (if (keyword? edge-def)
                                      (when-not (contains? region-set edge-def)
                                        {:cell cell-name
                                         :transitions {:unconditional edge-def}})
                                      (let [external (into {}
                                                       (remove (fn [[_ target]]
                                                                 (contains? region-set target)))
                                                       edge-def)]
                                        (when (seq external)
                                          {:cell cell-name
                                           :transitions external})))))))
                        region-cells)
          ;; Generate prompt
          prompt (str "## Region: " (name region-name) "\n\n"
                      "### Cells\n\n"
                      (str/join "\n"
                                (map (fn [{:keys [name id doc schema]}]
                                       (str "- **" name "** (" id ")"
                                            (when doc (str " — " doc))
                                            "\n  Input: " (pr-str (:input schema))
                                            "\n  Output: " (pr-str (:output schema))))
                                     cells-info))
                      "\n\n### Internal Edges\n\n"
                      (if (seq internal-edges)
                        (str/join "\n"
                                  (map (fn [[from targets]]
                                         (str "- " from " → " (pr-str targets)))
                                       internal-edges))
                        "(none)")
                      "\n\n### Entry Points\n\n"
                      (str/join ", " (map str entry-points))
                      "\n\n### Exit Points\n\n"
                      (if (seq exit-points)
                        (str/join "\n"
                                  (map (fn [{:keys [cell transitions]}]
                                         (str "- " cell " → " (pr-str transitions)))
                                       exit-points))
                        "(none)")
                      "\n")]
      {:cells          cells-info
       :internal-edges internal-edges
       :entry-points   entry-points
       :exit-points    exit-points
       :prompt         prompt})))

(defn plan
  "Generates an execution plan from a manifest.
   Identifies which cells can be built in parallel."
  [{:keys [cells]}]
  (let [cell-names (vec (keys cells))
        ;; In the common case, all cells are independent (each can be developed in isolation)
        ;; They only have runtime data dependencies, not development dependencies
        parallel-groups [cell-names]]
    {:scaffold   cell-names
     :parallel   parallel-groups
     :sequential []}))

(defn progress
  "Generates a human-readable progress report string."
  [{:keys [id] :as manifest-data}]
  (let [status (dev/workflow-status manifest-data)
        {:keys [total implemented passing failing pending cells]} status]
    (str "Workflow: " id "\n"
         "Status: " passing "/" total " cells passing"
         " | " total " total | " implemented " implemented | " pending " pending\n\n"
         (str/join "\n"
                   (map (fn [{:keys [id name status error errors]}]
                          (let [tag (case status
                                     :passing "[PASS]"
                                     :failing "[FAIL]"
                                     :pending "[    ]")]
                            (str tag " " name " (" id ")"
                                 (when (= status :failing)
                                   (str " — " (or error
                                                  (some-> errors first :detail :errors str)))))))
                        cells))
         "\n")))

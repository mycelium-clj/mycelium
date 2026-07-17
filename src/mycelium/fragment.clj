(ns mycelium.fragment
  "Workflow fragment loading, validation, and expansion.
   Fragments are reusable sub-workflows that can be embedded into host manifests."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [mycelium.validation :as v]))

(defn validate-fragment
  "Validates a fragment definition. Returns the fragment if valid, throws otherwise.
   Checks: :entry, :exits, :cells, internal edge consistency.
   opts:
     :malli/registry — local Malli registry used to validate cell schemas."
  ([fragment]
   (validate-fragment fragment {}))
  ([{:keys [id entry exits cells edges dispatches] :as fragment} opts]
  (when-not entry
    (throw (ex-info (str "Fragment " (or id "unknown") " missing :entry")
                    {:fragment-id id})))
  (when-not exits
    (throw (ex-info (str "Fragment " (or id "unknown") " missing :exits")
                    {:fragment-id id})))
  (when (empty? exits)
    (throw (ex-info (str "Fragment " (or id "unknown") " has empty :exits")
                    {:fragment-id id})))
  ;; Entry must be a cell name
  (when-not (contains? cells entry)
    (throw (ex-info (str "Fragment " (or id "unknown") " :entry " entry
                         " is not found in :cells")
                    {:fragment-id id :entry entry})))
  ;; Validate each cell definition
  (doseq [[cell-name cell-def] cells]
    (v/validate-cell-def! cell-name cell-def "Fragment cell" opts))
  ;; Validate :on-error targets and edge targets
  (let [cell-names    (set (keys cells))
        exit-targets  (set (map #(keyword "_exit" (name %)) exits))
        valid-targets (set/union cell-names exit-targets)]
    ;; Validate :on-error — must be nil, a cell name, or a valid :_exit/* reference
    (doseq [[cell-name cell-def] cells]
      (when-let [on-err (:on-error cell-def)]
        (when-not (contains? valid-targets on-err)
          (throw (ex-info (str "Fragment " (or id "unknown") " cell " cell-name
                               " :on-error references " on-err
                               " which is not a valid cell or exit")
                          {:fragment-id id :cell-name cell-name
                           :on-error on-err :valid-targets valid-targets})))))
    (doseq [[from edge-def] edges]
      (let [targets (if (keyword? edge-def) [edge-def] (vals edge-def))]
        (doseq [target targets]
          (when-not (contains? valid-targets target)
            (if (and (keyword? target)
                     (= "_exit" (namespace target)))
              (throw (ex-info (str "Fragment " (or id "unknown")
                                   " references exit " (name target)
                                   " which is not declared in :exits")
                              {:fragment-id id :exit-ref target :declared-exits exits}))
              (throw (ex-info (str "Fragment " (or id "unknown")
                                   " invalid edge target " target " from " from)
                              {:fragment-id id :from from :target target}))))))))
  fragment))

;; ===== Fragment expansion =====

(defn expand-fragment
  "Expands a single fragment into cells, edges, and dispatches.
   host-mapping: {:as :start, :exits {:success :render-dashboard, :failure :render-error}}
   host-cells: existing host cell names (set or map) to check for collisions.
   Returns {:cells {...} :edges {...} :dispatches {...}}
   opts:
     :malli/registry — local Malli registry used to validate cell schemas."
  ([fragment host-mapping host-cells]
   (expand-fragment fragment host-mapping host-cells {}))
  ([fragment host-mapping host-cells opts]
  (validate-fragment fragment opts)
  (let [{:keys [entry exits cells edges dispatches]} fragment
        {:keys [as exits]} host-mapping
        ;; Validate all fragment exits are wired
        _ (doseq [exit (:exits fragment)]
            (when-not (get exits exit)
              (throw (ex-info (str "Fragment exit " exit " is not wired in host mapping")
                              {:fragment-id (:id fragment) :exit exit
                               :wired-exits (keys exits)}))))
        ;; Build rename map: entry cell → :as name
        rename-map (if (and as (not= as entry))
                     {entry as}
                     {})
        rename (fn [cell-name] (get rename-map cell-name cell-name))
        ;; Check for collisions with host cells
        host-cell-names (if (map? host-cells) (set (keys host-cells)) (set host-cells))
        fragment-cell-names (set (map rename (keys cells)))
        collisions (set/intersection fragment-cell-names host-cell-names)
        _ (when (seq collisions)
            (throw (ex-info (str "Fragment cell name collision with host: " collisions)
                            {:fragment-id (:id fragment) :collisions collisions})))
        ;; Exit keyword mapping
        exit-mapping exits
        ;; Rename cells and resolve :on-error :_exit/* references
        expanded-cells (into {}
                             (map (fn [[cell-name cell-def]]
                                    (let [resolved-def
                                          (if-let [on-err (:on-error cell-def)]
                                            (if (and (keyword? on-err)
                                                     (= "_exit" (namespace on-err)))
                                              (assoc cell-def :on-error
                                                     (get exit-mapping (keyword (name on-err))))
                                              (assoc cell-def :on-error (rename on-err)))
                                            cell-def)]
                                      [(rename cell-name) resolved-def])))
                             cells)
        ;; Rename and replace edges
        expanded-edges (into {}
                             (map (fn [[from edge-def]]
                                    (let [renamed-from (rename from)
                                          ;; Replace internal cell refs
                                          replaced (if (keyword? edge-def)
                                                     (if (= "_exit" (namespace edge-def))
                                                       (get exit-mapping (keyword (name edge-def)))
                                                       (rename edge-def))
                                                     (into {}
                                                           (map (fn [[label target]]
                                                                  (if (and (keyword? target)
                                                                           (= "_exit" (namespace target)))
                                                                    [label (get exit-mapping (keyword (name target)))]
                                                                    [label (rename target)])))
                                                           edge-def))]
                                      [renamed-from replaced])))
                             edges)
        ;; Rename dispatches
        expanded-dispatches (into {}
                                  (map (fn [[cell-name dispatch-vec]]
                                         [(rename cell-name) dispatch-vec]))
                                  (or dispatches {}))]
    {:cells      expanded-cells
     :edges      expanded-edges
     :dispatches expanded-dispatches})))

(defn load-fragment
  "Loads a fragment from a resource path. Returns the parsed EDN."
  [path]
  (if-let [resource (io/resource path)]
    (edn/read-string (slurp resource))
    (throw (ex-info (str "Fragment resource not found: " path) {:path path}))))

(defn- resolve-fragment
  "Resolves fragment data from a mapping. Supports :fragment (inline) or :ref (file path)."
  [frag-mapping]
  (or (:fragment frag-mapping)
      (when-let [ref (:ref frag-mapping)]
        (load-fragment ref))
      (throw (ex-info "Fragment mapping must have :fragment or :ref"
                      {:mapping frag-mapping}))))

(defn expand-all-fragments
  "Expands all :fragments in a manifest, merging them into the manifest's cells/edges/dispatches.
   Each fragment mapping may specify :fragment (inline data) or :ref (resource path).
   Returns the manifest with fragments expanded and :fragments key removed.
   opts:
     :malli/registry — local Malli registry used to validate cell schemas."
  ([manifest]
   (expand-all-fragments manifest {}))
  ([{:keys [fragments cells edges dispatches] :as manifest} opts]
  (if (empty? fragments)
    manifest
    (let [result (reduce (fn [acc [_frag-name frag-mapping]]
                           (let [fragment (resolve-fragment frag-mapping)
                                 {:keys [as exits]} frag-mapping
                                 expansion (expand-fragment fragment
                                                           {:as as :exits exits}
                                                           (:cells acc)
                                                           opts)]
                             (-> acc
                                 (update :cells merge (:cells expansion))
                                 (update :edges merge (:edges expansion))
                                 (update :dispatches merge (:dispatches expansion)))))
                         (-> manifest
                             (dissoc :fragments))
                         (sort-by first fragments))]
     result))))

(ns mycelium.compose
  "Hierarchical composition: wrapping workflows as cells for nesting."
  (:require [malli.core :as m]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]
            [promesa.core :as p]))

(defn- get-map-entries
  "Extracts top-level entries from a resolved Malli :map schema.
   Returns full entries with properties preserved, or nil if not a map schema."
  [schema]
  (when schema
    (let [schema (m/deref-all schema)]
      (when (= :map (m/type schema))
        (mapv (fn [[k properties child]]
                (if properties
                  [k properties child]
                  [k child]))
              (m/children schema))))))

(defn- output-entries-for-edge
  "Extracts map entries from a cell's output schema for edges routing to :end."
  [output edge-def]
  (cond
    ;; Unconditional edge to :end
    (= :end edge-def)
    (if (schema/per-transition? output)
      (mapcat (fn [[_ s]] (or (get-map-entries s) []))
              (schema/transitions-map output))
      (get-map-entries output))

    ;; Map edges — collect entries from transitions routing to :end
    (map? edge-def)
    (let [end-transitions (keep (fn [[transition target]]
                                  (when (= :end target) transition))
                                edge-def)]
      (when (seq end-transitions)
        (if (schema/per-transition? output)
          (let [transitions (schema/transitions-map output)]
            (mapcat (fn [t]
                      (or (some-> (get transitions t) get-map-entries) []))
                    end-transitions))
          (get-map-entries output))))))

(defn- collect-end-reaching-output-entries
  "Walks workflow edges to find cells routing to :end and collects their resolved output entries.
   Returns the union of full entries with properties preserved."
  [edges cells opts]
  (->> edges
       (mapcat (fn [[cell-name edge-def]]
                 (let [cell-ref (get cells cell-name)
                       cell-id  (if (map? cell-ref) (:id cell-ref) cell-ref)]
                   (when-let [cell (some-> (cell/get-cell cell-id)
                                           (schema/compile-cell-schemas opts))]
                     (some-> (get-in cell [:schema :output])
                             (output-entries-for-edge edge-def))))))
       (reduce (fn [entries entry]
                 (let [k (first entry)]
                   (if (contains? entries k)
                     entries
                     (assoc entries k entry))))
               {})
       vals))

(defn- infer-workflow-output-schema
  "Infers a per-transition output schema for a composed cell, wrapped
   in the explicit `[:per-transition {...}]` form.

   :success path gets the union of output keys from cells routing to :end.
   :failure path gets [:map [:mycelium/error :any]].

   When the caller passes a proper [:map ...] vector, that becomes the
   :success schema. When the caller's schema is the bare :map keyword,
   we infer :success from the child workflow's end-reaching cells, and
   fall back to bare :map when there's nothing to infer."
  [workflow schema opts]
  (let [output (:output schema)]
    (cond
      ;; Caller already passed an explicit per-transition wrapper — use it as-is.
      (schema/per-transition? output)
      output

      ;; Caller provided a real schema (Malli form or lite map) —
      ;; use it as :success, add :failure
      (and (not (keyword? output)) (or (vector? output) (map? output)))
      [:per-transition {:success output
                        :failure [:map [:mycelium/error :any]]}]

      :else
      (let [entries (collect-end-reaching-output-entries
                     (:edges workflow) (:cells workflow) opts)]
        (if (seq entries)
          [:per-transition {:success (into [:map] entries)
                            :failure [:map [:mycelium/error :any]]}]
          ;; Fall back to bare :map
          :map)))))

(def workflow-cell-dispatches
  "Default dispatch predicates for composed workflow cells.
   Ordered vector — :success checked first, :failure as fallback."
  [[:success (fn [data] (nil? (:mycelium/error data)))]
   [:failure (fn [data] (some? (:mycelium/error data)))]])

(defn workflow->cell
  "Wraps a workflow definition as a cell spec.
   The resulting cell runs the child workflow to completion and returns
   data with :mycelium/error on failure for dispatch routing.

   `cell-id`  - the ID for the resulting cell
   `workflow`  - workflow definition map {:cells ... :edges ... :dispatches ...}
   `schema`    - {:input [...] :output [...]} for the cell.
                 Lite map schemas compile like any other form.
   opts:
     :malli/registry — local Malli registry captured during compilation."
  ([cell-id workflow schema-map]
   (workflow->cell cell-id workflow schema-map {}))
  ([cell-id workflow schema-map opts]
   (let [compiled (wf/compile-workflow
                   workflow
                   (merge opts
                          {:on-error
                           (fn [_ fsm-state]
                             (-> (:data fsm-state)
                                 (assoc :mycelium/error
                                        (or (:error fsm-state)
                                            (get-in fsm-state
                                                    [:data :mycelium/schema-error])))
                                 (assoc :mycelium/child-trace
                                        (:trace fsm-state))))
                           :on-end
                           (fn [_ fsm-state]
                             (-> (:data fsm-state)
                                 (assoc :mycelium/child-trace
                                        (:trace fsm-state))))}))
         handler (fn [resources data]
                   (try
                     (let [result (fsm/run compiled resources {:data data})]
                       (if (p/promise? result) @result result))
                     (catch Exception e
                       (assoc data :mycelium/error (ex-message e)))))]
     {:id                 cell-id
      :handler            handler
      :schema             {:input (:input schema-map)
                           :output (infer-workflow-output-schema
                                    workflow schema-map opts)}
      :default-dispatches workflow-cell-dispatches})))

(defn register-workflow-cell!
  "Creates a workflow-as-cell and registers it in the cell registry.

   `cell-id`  - the ID for the resulting cell
   `workflow`  - workflow definition map {:cells ... :edges ... :dispatches ...}
   `schema`    - {:input [...] :output [...]} for the cell
   opts:
     :malli/registry — local Malli registry captured during compilation."
  ([cell-id workflow schema-map]
   (register-workflow-cell! cell-id workflow schema-map {}))
  ([cell-id workflow schema-map opts]
   (let [spec (workflow->cell cell-id workflow schema-map opts)]
     (.addMethod cell/cell-spec cell-id (constantly spec))
     spec)))

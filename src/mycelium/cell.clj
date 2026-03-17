(ns mycelium.cell
  "Cell registry for Mycelium. Cells are registered via `defmethod cell-spec`."
  (:require [mycelium.schema :as schema]
            [mycelium.validation :as v]))

(defmulti cell-spec
  "Multimethod-backed cell registry. Dispatches on cell-id keyword,
   returns the full cell spec map or nil for unknown cells."
  identity)

(defmethod cell-spec :default [_] nil)

(defonce ^:private cell-overrides (atom {}))

(defn clear-registry!
  "Removes all cells from the registry."
  []
  (doseq [k (keys (dissoc (methods cell-spec) :default))]
    (remove-method cell-spec k))
  (reset! cell-overrides {}))


(defn get-cell
  "Returns the cell spec for the given id, or nil if not found."
  [id]
  (let [spec (cell-spec id)]
    (when spec
      (if-let [overrides (get @cell-overrides id)]
        (merge spec overrides)
        spec))))

(defn get-cell!
  "Returns the cell spec for the given id, or throws if not found."
  [id]
  (or (get-cell id)
      (throw (ex-info (str "Cell " id " not found in registry")
                      {:id id}))))

(defn- output-dispatched?
  "Heuristic for defcell/set-cell-schema! (no edge context available):
   a map output schema is per-transition if all values are vectors (Malli schema
   forms like [:map ...], [:or ...], etc.). If any value is a keyword or map,
   it's treated as lite syntax. For manifests, edge context is used instead.
   Trade-off: per-transition maps with bare keyword schemas (e.g. {:success :any})
   would be misclassified as lite — use vector form [:any] in that rare case."
  [output]
  (and (map? output) (seq output) (every? vector? (vals output))))

(defn set-cell-schema!
  "Sets or overwrites the schema for an already-registered cell.
   Normalizes lite syntax, validates Malli, then updates.
   Throws if the cell is not found or the schema is invalid."
  [cell-id schema]
  (when-not (cell-spec cell-id)
    (throw (ex-info (str "Cell " cell-id " not found in registry")
                    {:id cell-id})))
  (let [dispatched? (output-dispatched? (:output schema))
        schema (schema/normalize-cell-schema schema dispatched?)]
    (when (:input schema)
      (v/validate-malli-schema! (:input schema) (str cell-id " :input")))
    (when (:output schema)
      (v/validate-output-schema! (:output schema) (str cell-id " :output")))
    (swap! cell-overrides update cell-id merge {:schema schema})
    schema))

(defn set-cell-meta!
  "Sets metadata overrides (schema, requires) on a registered cell.
   The manifest calls this to inject metadata into cells that were registered
   without schemas/requires. Expects schemas to be pre-normalized.
   Validates schema is well-formed. Throws if the cell is not found."
  [cell-id {:keys [schema requires] :as meta-map}]
  (when-not (cell-spec cell-id)
    (throw (ex-info (str "Cell " cell-id " not found in registry")
                    {:id cell-id})))
  (when schema
    (when (:input schema)
      (v/validate-malli-schema! (:input schema) (str cell-id " :input")))
    (when (:output schema)
      (v/validate-output-schema! (:output schema) (str cell-id " :output"))))
  (let [overrides (cond-> {}
                    schema   (assoc :schema schema)
                    requires (assoc :requires requires))]
    (swap! cell-overrides update cell-id merge overrides))
  meta-map)

(defn list-cells
  "Returns a seq of all registered cell IDs."
  []
  (keys (dissoc (methods cell-spec) :default)))

(defn defcell
  "Registers a cell with less boilerplate than the raw defmethod.
   Eliminates ID duplication — the cell-id is specified once.

   Arity:
     (defcell :ns/id opts handler-fn)

   opts is a map that MUST contain :doc (a non-empty string describing the cell's
   purpose and semantics for LLM consumption). It may also contain :input, :output
   (schema), :requires, and :async?.
   The :input/:output keys become the cell's :schema. Extra keys (:doc, :requires,
   :async?) are lifted to the top-level spec.

   Examples:
     ;; Minimal — no schema
     (defcell :order/compute-tax
       {:doc \"Computes sales tax on the order subtotal\"}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))

     ;; With schema
     (defcell :order/compute-tax
       {:doc    \"Computes sales tax on the order subtotal\"
        :input  [:map [:subtotal :double]]
        :output [:map [:tax :double]]}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))

     ;; With schema + opts
     (defcell :order/compute-tax
       {:doc      \"Computes sales tax using regional tax rate tables\"
        :input    [:map [:subtotal :double]]
        :output   [:map [:tax :double]]
        :requires [:tax-rates]}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))"
  [cell-id opts handler-fn]
  (when-not (and (map? opts) (string? (:doc opts)) (seq (:doc opts)))
    (throw (ex-info (str "defcell " cell-id ": opts map with non-empty :doc string is required")
                    {:id cell-id})))
  (let [schema-keys #{:input :output}
        opt-keys    #{:doc :requires :async?}
        raw-schema  (let [s (select-keys opts schema-keys)]
                      (when (seq s) s))
        dispatched? (and raw-schema (output-dispatched? (:output raw-schema)))
        schema      (when raw-schema
                      (schema/normalize-cell-schema raw-schema dispatched?))
        extra       (select-keys opts opt-keys)
        spec        (cond-> {:id cell-id :handler handler-fn :doc (:doc extra)}
                      schema (assoc :schema schema)
                      (:requires extra) (assoc :requires (:requires extra))
                      (:async? extra) (assoc :async? (:async? extra)))]
    (.addMethod cell-spec cell-id (constantly spec))
    spec))

(ns mycelium.schema
  "Schema validation interceptors for Mycelium.
   Provides pre/post interceptors that enforce Malli schemas on cell input/output,
   and async callback wrappers for async cells."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [maestro.core :as fsm]))

(def ^:private terminal-states
  #{::fsm/end ::fsm/error ::fsm/halt})

(defn- strip-mycelium-keys
  "Removes :mycelium/* keys from a data map to reduce error payload noise."
  [data]
  (into {} (remove (fn [[k _]] (and (keyword? k)
                                     (= "mycelium" (namespace k)))))
        data))

(defn- build-failed-keys
  "Builds a map of {key {:value v, :type type-name, :message msg}} from
   Malli's humanized errors and the original data."
  [humanized data]
  (when (map? humanized)
    (into {}
          (map (fn [[k msgs]]
                 (let [v (get data k)]
                   [k {:value   v
                       :type    (when (some? v) (.getName (class v)))
                       :message (first msgs)}])))
          humanized)))

(defn- build-error-map
  "Builds a schema error map with enriched diagnostics.
   Includes a human-readable :message with cell-id, phase, and failing key names."
  [cell-id phase explanation data]
  (let [humanized   (me/humanize explanation)
        failed-keys (when (map? humanized) (build-failed-keys humanized data))
        key-names   (when failed-keys (keys failed-keys))
        message     (str "Schema " (name phase) " validation failed at " cell-id
                         (when (seq key-names)
                           (str " — failing keys: " (pr-str key-names))))]
    (cond-> {:cell-id     cell-id
             :phase       phase
             :message     message
             :errors      humanized
             :data        (strip-mycelium-keys data)}
      failed-keys (assoc :failed-keys failed-keys))))

(defn validate-input
  "Validates data against the cell's input schema.
   Returns nil if valid (or if no input schema is defined), or an error map if invalid.
   Uses open-map semantics (extra keys pass through)."
  [cell data]
  (when-let [schema (get-in cell [:schema :input])]
    (when-let [explanation (m/explain schema data)]
      (build-error-map (:id cell) :input explanation data))))

(defn output-schema-for-transition
  "Returns the output schema for a specific transition of a cell.
   If output is nil → nil. If vector → same schema for all transitions.
   If map → schema for that transition key (or nil if not found)."
  [cell transition]
  (let [output (get-in cell [:schema :output])]
    (cond
      (nil? output)    nil
      (vector? output) output
      (map? output)    (get output transition)
      :else            output)))

(defn validate-output
  "Validates data against the cell's output schema.
   When transition is provided and output is a per-transition map, validates
   against that specific transition's schema.
   When transition is nil and output is a map, validates against all schemas
   (passes if any match).
   Returns nil if valid, or an error map if invalid."
  ([cell data]
   (validate-output cell data nil))
  ([cell data transition]
   (let [output (get-in cell [:schema :output])]
     (cond
       ;; No output schema — always passes
       (nil? output) nil

       ;; Per-transition output schema (map)
       (map? output)
       (if transition
         ;; Validate against the specific transition's schema
         (when-let [schema (get output transition)]
           (when-let [explanation (m/explain schema data)]
             (build-error-map (:id cell) :output explanation data)))
         ;; No transition known — validate against all schemas, pass if any matches
         (let [schemas (vals output)]
           (when (every? (fn [schema]
                           (some? (m/explain schema data)))
                         schemas)
             (let [msg (str "Data does not match any output schema for " (:id cell))]
               {:cell-id (:id cell)
                :phase   :output
                :message msg
                :errors  msg
                :data    (strip-mycelium-keys data)}))))

       ;; Single schema (vector or keyword)
       :else
       (when-let [explanation (m/explain output data)]
         (build-error-map (:id cell) :output explanation data))))))

;; ===== Schema coercion =====

(def number-coercion-transformer
  "Malli's json-transformer handles numeric coercion safely:
   double→int only when the value has no fractional part (949.0 → 949,
   but 949.5 is left unconverted so validation catches it).
   int→double always converts."
  mt/json-transformer)

(defn coerce-input
  "Coerces data to match the cell's input schema using number coercion.
   Returns {:data coerced-data} on success, {:error error-map} on failure.
   If no input schema exists, returns {:data data} unchanged."
  [cell data]
  (let [schema (get-in cell [:schema :input])]
    (if-not schema
      {:data data}
      (let [coerced (m/decode schema data number-coercion-transformer)]
        (if-let [explanation (m/explain schema coerced)]
          {:error (build-error-map (:id cell) :input explanation data)}
          {:data coerced})))))

(defn coerce-output
  "Coerces data to match the cell's output schema using number coercion.
   Returns {:data coerced-data} on success, {:error error-map} on failure.
   Handles single schemas and per-transition schema maps."
  ([cell data]
   (coerce-output cell data nil))
  ([cell data transition]
   (let [output (get-in cell [:schema :output])]
     (cond
       (nil? output)
       {:data data}

       (map? output)
       (if transition
         (if-let [schema (get output transition)]
           (let [coerced (m/decode schema data number-coercion-transformer)]
             (if-let [explanation (m/explain schema coerced)]
               {:error (build-error-map (:id cell) :output explanation data)}
               {:data coerced}))
           {:data data})
         ;; No transition — try each schema, use first that matches after coercion
         (let [results (for [[_label schema] output]
                         (let [coerced (m/decode schema data number-coercion-transformer)]
                           (when-not (m/explain schema coerced)
                             coerced)))]
           (if-let [coerced (first (filter some? results))]
             {:data coerced}
             (let [msg (str "Data does not match any output schema for " (:id cell))]
               {:error {:cell-id (:id cell)
                        :phase   :output
                        :message msg
                        :errors  msg
                        :data    (strip-mycelium-keys data)}}))))

       :else
       (let [coerced (m/decode output data number-coercion-transformer)]
         (if-let [explanation (m/explain output coerced)]
           {:error (build-error-map (:id cell) :output explanation data)}
           {:data coerced}))))))

(defn- compile-schema-value
  "Compiles a single schema value to a Malli schema object if it's a raw form.
   Returns the value unchanged if it's already compiled or nil."
  [schema-val]
  (cond
    (nil? schema-val) nil
    (m/schema? schema-val) schema-val
    :else (m/schema schema-val)))

(defn pre-compile-schemas
  "Pre-compiles all Malli schemas in a state->cell map.
   Converts raw schema forms (vectors, keywords) into compiled Malli schema objects
   so that validate-input/validate-output don't re-parse them on every call.
   Returns an updated state->cell map with compiled schemas."
  [state->cell]
  (into {}
        (map (fn [[state-id cell]]
               (let [input  (get-in cell [:schema :input])
                     output (get-in cell [:schema :output])
                     compiled-input  (compile-schema-value input)
                     compiled-output (cond
                                      (nil? output)    nil
                                      (map? output)    (into {} (map (fn [[k v]]
                                                                       [k (compile-schema-value v)])
                                                                     output))
                                      :else            (compile-schema-value output))
                     updated-cell (cond-> cell
                                    compiled-input  (assoc-in [:schema :input] compiled-input)
                                    (some? compiled-output) (assoc-in [:schema :output] compiled-output))]
                 [state-id updated-cell])))
        state->cell))

(defn- extract-cell-path
  "Extracts a vector of cell names from the :mycelium/trace in data."
  [data]
  (mapv :cell (:mycelium/trace data)))

(defn- attach-cell-path
  "Attaches :cell-path to an error map based on the current trace."
  [error data]
  (let [path (extract-cell-path data)]
    (cond-> error
      (seq path) (assoc :cell-path path))))

(defn- attach-cell-name
  "Attaches :cell-name to an error map from state->names lookup."
  [error state-id state->names]
  (if-let [cell-name (get state->names state-id)]
    (assoc error :cell-name cell-name)
    error))

(defn make-pre-interceptor
  "Creates a Maestro pre-interceptor that validates input schemas.
   `state->cell` is a map of state-id → cell-spec.
   `opts` — optional map:
     `:coerce?`      — when true, coerces data before validation.
     `:state->names` — map of state-id → cell-name keyword for error messages.
   Skips terminal states."
  ([state->cell] (make-pre-interceptor state->cell {}))
  ([state->cell opts]
   (let [coerce?      (:coerce? opts)
         state->names (:state->names opts)]
     (fn [fsm-state _resources]
       (let [state-id (:current-state-id fsm-state)]
         (if (contains? terminal-states state-id)
           fsm-state
           (if-let [cell (get state->cell state-id)]
             (if coerce?
               (let [result (coerce-input cell (:data fsm-state))]
                 (if (:error result)
                   (-> fsm-state
                       (assoc :current-state-id ::fsm/error)
                       (assoc-in [:data :mycelium/schema-error]
                                 (-> (:error result)
                                     (attach-cell-path (:data fsm-state))
                                     (attach-cell-name state-id state->names))))
                   (assoc fsm-state :data (:data result))))
               (if-let [error (validate-input cell (:data fsm-state))]
                 (-> fsm-state
                     (assoc :current-state-id ::fsm/error)
                     (assoc-in [:data :mycelium/schema-error]
                               (-> error
                                   (attach-cell-path (:data fsm-state))
                                   (attach-cell-name state-id state->names))))
                 fsm-state))
             fsm-state)))))))

(defn make-post-interceptor
  "Creates a Maestro post-interceptor that validates output schemas and appends
   trace entries for diagnostics.
   Uses :last-state-id to look up the cell that just ran.
   `state->edge-targets` maps state-id → {target-state-id → transition-label},
   used to infer which transition was taken from :current-state-id (set by Maestro
   after dispatch evaluation).
   `state->names` maps resolved-state-id → cell-name keyword for human-readable traces.
   `opts` — optional map:
     `:coerce?`  — when true, coerces output data before validation.
     `:on-trace` — callback `(fn [trace-entry])` called after each cell completes.
   Skips terminal states."
  ([state->cell state->edge-targets state->names]
   (make-post-interceptor state->cell state->edge-targets state->names {}))
  ([state->cell state->edge-targets state->names opts]
   (let [coerce?  (:coerce? opts)
         on-trace (:on-trace opts)]
     (fn [fsm-state _resources]
       (let [state-id (:last-state-id fsm-state)]
         (if (or (nil? state-id)
                 (contains? terminal-states state-id))
           fsm-state
           (if-let [cell (get state->cell state-id)]
             (let [transition (when state->edge-targets
                                (get-in state->edge-targets
                                        [state-id (:current-state-id fsm-state)]))
                   data       (:data fsm-state)
                   skip-validation? (or (:mycelium/resilience-error data)
                                        (:mycelium/timeout data)
                                        (:mycelium/error data))
                   ;; When coercing, get both error and coerced data
                   coerce-result (when (and coerce? (not skip-validation?))
                                   (coerce-output cell data transition))
                   ;; When not coercing, just validate
                   error (cond
                           skip-validation?               nil
                           coerce?                        (:error coerce-result)
                           :else                          (validate-output cell data transition))
                   ;; Use coerced data when available
                   data (if (and coerce? (not skip-validation?) (not error))
                          (:data coerce-result)
                          data)
                   ;; Extract duration-ms from the latest Maestro trace segment
                   duration-ms (some-> (:trace fsm-state) last :duration-ms)
                   ;; Extract join sub-traces if present
                   join-traces (:mycelium/join-traces data)
                   halted?     (some? (:mycelium/halt data))
                   timed-out?  (some? (:mycelium/timeout data))
                   trace-entry (cond-> {:cell       (get state->names state-id)
                                        :cell-id    (:id cell)
                                        :transition transition
                                        :data       (dissoc data :mycelium/trace :mycelium/join-traces)}
                                 duration-ms   (assoc :duration-ms duration-ms)
                                 join-traces   (assoc :join-traces join-traces)
                                 halted?       (assoc :halted true)
                                 timed-out?    (assoc :timeout? true)
                                 error         (assoc :error error))]
               (when on-trace (on-trace trace-entry))
               (cond
                 error
                 (-> fsm-state
                     (update-in [:data :mycelium/trace] (fnil conj []) trace-entry)
                     (update :data dissoc :mycelium/join-traces :mycelium/params)
                     (assoc :current-state-id ::fsm/error)
                     (assoc-in [:data :mycelium/schema-error]
                               (-> error
                                   (attach-cell-path (:data fsm-state))
                                   (attach-cell-name state-id state->names))))

                 halted?
                 (-> fsm-state
                     (assoc :data data)
                     (update-in [:data :mycelium/trace] (fnil conj []) trace-entry)
                     (update :data dissoc :mycelium/join-traces)
                     (assoc-in [:data :mycelium/resume] (:current-state-id fsm-state))
                     (assoc :current-state-id ::fsm/halt))

                 :else
                 (-> fsm-state
                     (assoc :data data)
                     (update-in [:data :mycelium/trace] (fnil conj []) trace-entry)
                     (update :data dissoc :mycelium/join-traces :mycelium/params :mycelium/timeout))))
             fsm-state)))))))

(defn wrap-async-callback
  "Wraps an async cell's callback to validate output before forwarding.
   If validation fails, calls error-callback with an ex-info.
   For per-transition output schemas, falls back to 'any schema matches' validation
   since the transition target is not known until dispatch."
  [cell original-callback error-callback]
  (fn [data]
    (if-let [error (validate-output cell data)]
      (error-callback (ex-info (str "Output validation failed for " (:id cell))
                               error))
      (original-callback data))))

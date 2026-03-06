(ns mycelium.schema
  "Schema validation interceptors for Mycelium.
   Provides pre/post interceptors that enforce Malli schemas on cell input/output,
   and async callback wrappers for async cells."
  (:require [malli.core :as m]
            [malli.error :as me]
            [maestro.core :as fsm]))

(def ^:private terminal-states
  #{::fsm/end ::fsm/error ::fsm/halt})

(defn validate-input
  "Validates data against the cell's input schema.
   Returns nil if valid (or if no input schema is defined), or an error map if invalid.
   Uses open-map semantics (extra keys pass through)."
  [cell data]
  (when-let [schema (get-in cell [:schema :input])]
    (when-let [explanation (m/explain schema data)]
      {:cell-id (:id cell)
       :phase   :input
       :errors  (me/humanize explanation)
       :data    data})))

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
             {:cell-id (:id cell)
              :phase   :output
              :errors  (me/humanize explanation)
              :data    data}))
         ;; No transition known — validate against all schemas, pass if any matches
         (let [schemas (vals output)]
           (when (every? (fn [schema]
                           (some? (m/explain schema data)))
                         schemas)
             {:cell-id (:id cell)
              :phase   :output
              :errors  (str "Data does not match any output schema for " (:id cell))
              :data    data})))

       ;; Single schema (vector or keyword)
       :else
       (when-let [explanation (m/explain output data)]
         {:cell-id (:id cell)
          :phase   :output
          :errors  (me/humanize explanation)
          :data    data})))))

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

(defn make-pre-interceptor
  "Creates a Maestro pre-interceptor that validates input schemas.
   `state->cell` is a map of state-id → cell-spec.
   Skips terminal states."
  [state->cell]
  (fn [fsm-state _resources]
    (let [state-id (:current-state-id fsm-state)]
      (if (contains? terminal-states state-id)
        fsm-state
        (if-let [cell (get state->cell state-id)]
          (if-let [error (validate-input cell (:data fsm-state))]
            (-> fsm-state
                (assoc :current-state-id ::fsm/error)
                (assoc-in [:data :mycelium/schema-error] error))
            fsm-state)
          fsm-state)))))

(defn make-post-interceptor
  "Creates a Maestro post-interceptor that validates output schemas and appends
   trace entries for diagnostics.
   Uses :last-state-id to look up the cell that just ran.
   `state->edge-targets` maps state-id → {target-state-id → transition-label},
   used to infer which transition was taken from :current-state-id (set by Maestro
   after dispatch evaluation).
   `state->names` maps resolved-state-id → cell-name keyword for human-readable traces.
   Skips terminal states."
  [state->cell state->edge-targets state->names]
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
                ;; Skip output validation when resilience error or timeout is present
                ;; (the handler was short-circuited)
                error      (when-not (or (:mycelium/resilience-error data)
                                         (:mycelium/timeout data))
                             (validate-output cell data transition))
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
            (cond
              error
              (-> fsm-state
                  (update-in [:data :mycelium/trace] (fnil conj []) trace-entry)
                  (update :data dissoc :mycelium/join-traces :mycelium/params)
                  (assoc :current-state-id ::fsm/error)
                  (assoc-in [:data :mycelium/schema-error] error))

              halted?
              (-> fsm-state
                  (update-in [:data :mycelium/trace] (fnil conj []) trace-entry)
                  (update :data dissoc :mycelium/join-traces)
                  ;; Save resume point (where dispatches resolved to)
                  (assoc-in [:data :mycelium/resume] (:current-state-id fsm-state))
                  ;; Redirect to halt
                  (assoc :current-state-id ::fsm/halt))

              :else
              (-> fsm-state
                  (update-in [:data :mycelium/trace] (fnil conj []) trace-entry)
                  (update :data dissoc :mycelium/join-traces :mycelium/params :mycelium/timeout))))
          fsm-state)))))

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

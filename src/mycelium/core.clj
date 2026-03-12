(ns mycelium.core
  "Public API for the Mycelium framework.
   Re-exports key functions from internal namespaces."
  (:require [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.workflow :as workflow]
            [mycelium.compose :as compose]
            [mycelium.manifest :as manifest]
            [mycelium.middleware :as mw]
            [mycelium.system :as sys]
            [malli.core :as m]
            [maestro.core :as fsm]
            [promesa.core :as p]))

;; --- Cell registry ---

(def cell-spec
  "Multimethod for cell registration. Use (defmethod myc/cell-spec :id [_] {...}).
   See mycelium.cell/cell-spec."
  cell/cell-spec)

(def defcell
  "Registers a cell with less boilerplate. Eliminates ID duplication.
   See mycelium.cell/defcell."
  cell/defcell)

;; --- Workflow compilation ---

(def compile-workflow
  "Compiles a workflow definition into a Maestro FSM.
   See mycelium.workflow/compile-workflow."
  workflow/compile-workflow)

;; --- Composition ---

(def workflow->cell
  "Wraps a workflow as a cell for hierarchical composition.
   See mycelium.compose/workflow->cell."
  compose/workflow->cell)

;; --- Manifest ---

(def load-manifest
  "Loads and validates a manifest from an EDN file.
   See mycelium.manifest/load-manifest."
  manifest/load-manifest)

(def cell-brief
  "Generates a self-contained brief for a cell from a manifest.
   See mycelium.manifest/cell-brief."
  manifest/cell-brief)

;; --- Pre-compilation ---

(defn pre-compile
  "Pre-compiles a workflow definition for repeated execution.
   Returns a compiled workflow map that can be passed to `run-compiled`.
   Performs all validation and compilation at call time so that
   `run-compiled` does zero compilation work per request.

   opts — optional map passed to compile-workflow:
     :pre  — additional pre-interceptor (fn [fsm-state resources] -> fsm-state)
     :post — additional post-interceptor (fn [fsm-state resources] -> fsm-state)
     :on-error — custom error handler
     :on-end   — custom end handler
     :coerce?  — auto-coerce numeric types (int↔double)
     :propagate-keys? — auto-merge input keys into handler output (default true)
     :on-trace — callback (fn [trace-entry]) called after each cell completes"
  ([workflow-def] (pre-compile workflow-def {}))
  ([workflow-def opts]
   (let [compiled-fsm (compile-workflow workflow-def opts)
         input-schema-raw (:input-schema workflow-def)
         input-schema-compiled (when input-schema-raw
                                 (m/schema input-schema-raw))]
     {:compiled-fsm         compiled-fsm
      :input-schema-raw     input-schema-raw
      :input-schema-compiled input-schema-compiled})))

(defn- check-input-schema
  "Validates initial-data against a pre-compiled input schema.
   Returns nil if valid or no schema, error map on failure."
  [{:keys [input-schema-raw input-schema-compiled]} initial-data]
  (when input-schema-compiled
    (when-let [explanation (m/explain input-schema-compiled initial-data)]
      {:schema input-schema-raw
       :errors (:errors explanation)
       :data   initial-data})))

;; --- Execution ---

(defn- deref-if-promise
  "Derefs a Promesa promise to a plain value; returns non-promises as-is."
  [result]
  (if (p/promise? result) @result result))

(defn- extract-result
  "Extracts the data map from an FSM run result.
   Normal end: result is already the data map (from default-on-end).
   Halt: result is the FSM state map (minus :fsm) — extract :data."
  [result]
  (if (and (map? result) (:data result) (:current-state-id result) (not (:fsm result)))
    (:data result)
    result))

(defn run-compiled
  "Runs a pre-compiled workflow. Zero compilation overhead per call.
   Use `pre-compile` to create the compiled workflow at startup.
   If the workflow halts, returns data with :mycelium/halt and :mycelium/resume keys."
  ([compiled-workflow resources initial-data]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     {:mycelium/input-error input-error}
     (extract-result
      (deref-if-promise
       (fsm/run (:compiled-fsm compiled-workflow) resources {:data initial-data})))))
  ([compiled-workflow resources initial-data opts]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     {:mycelium/input-error input-error}
     (extract-result
      (deref-if-promise
       (fsm/run (:compiled-fsm compiled-workflow) resources {:data initial-data}))))))

(defn run-compiled-async
  "Like run-compiled but returns a future."
  ([compiled-workflow resources initial-data]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     (future {:mycelium/input-error input-error})
     (fsm/run-async (:compiled-fsm compiled-workflow) resources {:data initial-data})))
  ([compiled-workflow resources initial-data opts]
   (if-let [input-error (check-input-schema compiled-workflow initial-data)]
     (future {:mycelium/input-error input-error})
     (fsm/run-async (:compiled-fsm compiled-workflow) resources {:data initial-data}))))

(defn resume-compiled
  "Resumes a halted workflow from where it left off.
   `compiled-workflow` — pre-compiled workflow (from `pre-compile`).
   `resources` — resources map.
   `halted-result` — the data map returned from a halted run
     (must contain :mycelium/resume).
   `merge-data` — optional map to merge into the data before resuming
     (e.g., human-provided input)."
  ([compiled-workflow resources halted-result]
   (resume-compiled compiled-workflow resources halted-result nil))
  ([compiled-workflow resources halted-result merge-data]
   (let [resume-state (:mycelium/resume halted-result)]
     (when-not resume-state
       (throw (ex-info "Cannot resume: result is not a halted workflow (missing :mycelium/resume)"
                       {:result-keys (keys halted-result)})))
     (let [data (cond-> (dissoc halted-result :mycelium/halt :mycelium/resume)
                  merge-data (merge merge-data))]
       (extract-result
        (deref-if-promise
         (fsm/run (:compiled-fsm compiled-workflow)
                  resources
                  {:current-state-id resume-state
                   :data data})))))))

(defn run-workflow
  "Convenience function: compiles and runs a workflow in one step.
   For repeated execution of the same workflow, use `pre-compile` + `run-compiled` instead.
   opts — optional map passed to compile-workflow:
     :pre  — additional pre-interceptor (fn [fsm-state resources] -> fsm-state)
     :post — additional post-interceptor (fn [fsm-state resources] -> fsm-state)
     :on-error — custom error handler
     :on-end   — custom end handler
     :coerce?  — auto-coerce numeric types (int↔double)
     :propagate-keys? — auto-merge input keys into handler output (default true)
     :on-trace — callback (fn [trace-entry]) called after each cell completes"
  ([workflow-def]
   (run-workflow workflow-def {} {} {}))
  ([workflow-def resources]
   (run-workflow workflow-def resources {} {}))
  ([workflow-def resources initial-data]
   (run-workflow workflow-def resources initial-data {}))
  ([workflow-def resources initial-data opts]
   (run-compiled (pre-compile workflow-def opts) resources initial-data)))

(defn run-workflow-async
  "Like run-workflow but returns a future. Deref to get the final data map.
   For repeated execution, use `pre-compile` + `run-compiled-async` instead."
  ([workflow-def]
   (run-workflow-async workflow-def {} {} {}))
  ([workflow-def resources]
   (run-workflow-async workflow-def resources {} {}))
  ([workflow-def resources initial-data]
   (run-workflow-async workflow-def resources initial-data {}))
  ([workflow-def resources initial-data opts]
   (run-compiled-async (pre-compile workflow-def opts) resources initial-data)))

;; --- Middleware ---

(def workflow-handler
  "Creates a Ring handler from a pre-compiled workflow.
   See mycelium.middleware/workflow-handler."
  mw/workflow-handler)

(def html-response
  "Standard HTML response from workflow result.
   See mycelium.middleware/html-response."
  mw/html-response)

;; --- System compilation ---

(def compile-system
  "Compiles a system from a route→manifest map for bird's-eye view.
   See mycelium.system/compile-system."
  sys/compile-system)

;; --- Dev tools ---

(def test-transitions
  "Tests a cell across multiple transitions.
   See mycelium.dev/test-transitions."
  dev/test-transitions)

(def enumerate-paths
  "Enumerates all paths from :start to terminal states.
   See mycelium.dev/enumerate-paths."
  dev/enumerate-paths)

(def analyze-workflow
  "Runs Maestro's static analysis on a workflow definition.
   See mycelium.dev/analyze-workflow."
  dev/analyze-workflow)

(def infer-workflow-schema
  "Walks a workflow and reports accumulated schema keys at each cell.
   See mycelium.dev/infer-workflow-schema."
  dev/infer-workflow-schema)

(def generate-stubs
  "Generates defcell stub code from a workflow definition.
   See mycelium.dev/generate-stubs."
  dev/generate-stubs)

;; --- Error inspection ---

(defn workflow-error
  "Extracts a unified error map from a workflow result.
   Returns nil if no error is present, or a map with:
     :error-type — keyword identifying the error category
     :message    — human-readable error description
     :details    — original error data for programmatic access
   Plus error-specific keys like :cell-id, :cell, :cell-path, :failed-keys."
  [result]
  (cond
    ;; Workflow-level input schema validation
    (:mycelium/input-error result)
    (let [err (:mycelium/input-error result)]
      {:error-type :input
       :message    (str "Workflow input validation failed: " (:errors err))
       :details    err})

    ;; Schema validation error (input or output)
    (:mycelium/schema-error result)
    (let [err       (:mycelium/schema-error result)
          cell-name (:cell-name err)
          cell-id   (:cell-id err)
          fk        (:failed-keys err)
          key-names (when fk (keys fk))
          key-diff  (:key-diff err)
          cell-label (if cell-name
                       (str cell-name " (" cell-id ")")
                       (str cell-id))]
      (cond-> {:error-type  (keyword "schema" (name (:phase err)))
               :cell-id     cell-id
               :cell-path   (:cell-path err)
               :failed-keys fk
               :message     (:message err)
               :details     err}
        cell-name (assoc :cell-name cell-name)
        key-diff  (assoc :key-diff key-diff)))

    ;; Handler exception (error groups)
    (:mycelium/error result)
    (let [err (:mycelium/error result)]
      {:error-type :handler
       :cell       (:cell err)
       :message    (or (:message err) (str "Handler error at " (:cell err)))
       :details    err})

    ;; Resilience error (timeout, circuit breaker, etc.)
    (:mycelium/resilience-error result)
    (let [err (:mycelium/resilience-error result)]
      {:error-type (keyword "resilience" (name (:type err)))
       :cell       (:cell err)
       :message    (or (:message err)
                       (str "Resilience " (name (:type err)) " at " (:cell err)))
       :details    err})

    ;; Join error
    (:mycelium/join-error result)
    (let [errs (:mycelium/join-error result)]
      {:error-type :join
       :message    (str "Join failed: " (count errs) " member(s) errored")
       :details    errs})

    ;; Graph-level timeout (bare flag)
    (:mycelium/timeout result)
    {:error-type :timeout
     :message    "Cell timed out"
     :details    {:timeout true}}))

(defn error?
  "Returns true if the workflow result contains any error."
  [result]
  (some? (workflow-error result)))

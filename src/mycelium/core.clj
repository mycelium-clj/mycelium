(ns mycelium.core
  "Public API for the Mycelium framework.
   Re-exports key functions from internal namespaces."
  (:require [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.schema :as schema]
            [mycelium.workflow :as workflow]
            [mycelium.compose :as compose]
            [mycelium.manifest :as manifest]
            [mycelium.middleware :as mw]
            [mycelium.queue :as queue]
            [mycelium.system :as sys]
            [clojure.set :as set]
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
     :on-trace — callback (fn [trace-entry]) called after each cell completes
     :malli/registry — local Malli registry captured during compilation"
  ([workflow-def] (pre-compile workflow-def {}))
  ([workflow-def opts]
   (let [compiled-fsm (compile-workflow workflow-def opts)
         input-schema-raw (:input-schema workflow-def)
         input-schema-compiled (schema/compile-schema input-schema-raw opts)]
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
     :on-trace — callback (fn [trace-entry]) called after each cell completes
     :malli/registry — local Malli registry captured during compilation"
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
   For repeated execution, use `pre-compile` + `run-compiled-async` instead.
   opts are passed to `run-workflow`."
  ([workflow-def]
   (run-workflow-async workflow-def {} {} {}))
  ([workflow-def resources]
   (run-workflow-async workflow-def resources {} {}))
  ([workflow-def resources initial-data]
   (run-workflow-async workflow-def resources initial-data {}))
  ([workflow-def resources initial-data opts]
   (run-compiled-async (pre-compile workflow-def opts) resources initial-data)))

;; --- Direct cell invocation ---

(defn invoke-cell
  "Invoke a single registered cell directly, outside of a workflow.

  Useful for read-shaped routes that orchestrate one or two cells, for
  REPL exploration, and for tests that want to exercise a cell in
  isolation without standing up a workflow.

  Validates by default:
    1. The cell exists in the registry.
    2. Every key in the cell's :requires vector is present in resources.
    3. data conforms to the cell's :input schema (if declared).
    4. The handler's return value conforms to the cell's :output schema
       (if declared). Per-transition output schemas are matched against
       the returned :mycelium/transition or against any branch when
       transition is unspecified.

  Returns the handler's result on success.
  Throws ex-info on validation failure with a `:type` key naming the
  failure mode:

    :mycelium.invoke-cell/cell-not-found
    :mycelium.invoke-cell/missing-resources
    :mycelium.invoke-cell/input-error
    :mycelium.invoke-cell/output-error

  opts:
    :validate — :strict (default) runs all checks above.
                :off skips :requires + schema checks; only the
                cell-not-found check still fires (since the lookup is
                needed to find the handler).
    :malli/registry — local Malli registry used to compile cell schemas.

  Naming follows the rest of the Mycelium API (pre-compile, dev/test-cell)
  which uses plain `:validate` with keyword values rather than `:validate?`."
  ([cell-id resources data]
   (invoke-cell cell-id resources data {}))
  ([cell-id resources data {:keys [validate]
                            :or {validate :strict}
                            :as opts}]
   (let [cell (or (cell/get-cell cell-id)
                  (throw (ex-info (str "Cell not found in registry: " cell-id)
                                  {:type     :mycelium.invoke-cell/cell-not-found
                                   :cell-id  cell-id})))
         cell (if (= :strict validate)
                (schema/compile-cell-schemas cell opts)
                cell)]
     (when (= :strict validate)
       ;; :requires check
       (when-let [reqs (seq (:requires cell))]
         (let [provided (set (keys (or resources {})))
               missing  (set/difference (set reqs) provided)]
           (when (seq missing)
             (throw (ex-info (str "Cell " cell-id
                                  " requires resources " (vec reqs)
                                  " but missing: " (vec missing)
                                  " (got: " (vec provided) ")")
                             {:type     :mycelium.invoke-cell/missing-resources
                              :cell-id  cell-id
                              :requires (vec reqs)
                              :missing  (vec missing)
                              :provided (vec provided)})))))
       ;; :input check
       (when-let [input-error (schema/validate-input cell data)]
         (throw (ex-info (str "Cell " cell-id " input failed schema validation")
                         (assoc input-error
                                :type :mycelium.invoke-cell/input-error)))))
     (let [result ((:handler cell) resources data)]
       (when (= :strict validate)
         (when-let [output-error (schema/validate-output
                                   cell result (:mycelium/transition result))]
           (throw (ex-info (str "Cell " cell-id " output failed schema validation")
                           (assoc output-error
                                  :type :mycelium.invoke-cell/output-error)))))
       result))))

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

(def infer-schemas
  "Infers input/output schemas by running workflow with test inputs.
   See mycelium.dev/infer-schemas."
  dev/infer-schemas)

(def apply-inferred-schemas!
  "Applies inferred schemas to cells in the registry.
   See mycelium.dev/apply-inferred-schemas!."
  dev/apply-inferred-schemas!)

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

;; --- Queue API ---

(defn enqueue-workflow
  "Enqueues a workflow for asynchronous execution.

  `queue` — a WorkQueue implementation (e.g., from `mycelium.queue/memory-queue`).
  `workflow-name` — keyword identifying the workflow (used by worker to look up compiled spec).
  `compiled-workflow` — pre-compiled workflow (from `pre-compile`). The input schema
    is validated at enqueue time; invalid data throws immediately.
  `initial-data` — the initial data map for the workflow.
  `opts` — optional map:
    :run-at       — epoch-ms when the task becomes available (default: now)
    :max-attempts — max retries before dead-letter (default: 1)

  Returns a UUID task-id. Use `start-worker` to process enqueued tasks.

  For halt/resume workflows, session-id = (str task-id) — deterministic
  correlation between the enqueued task and the persisted session."
  ([queue workflow-name compiled-workflow initial-data]
   (enqueue-workflow queue workflow-name compiled-workflow initial-data {}))
  ([queue workflow-name compiled-workflow initial-data opts]
   (when-let [err (check-input-schema compiled-workflow initial-data)]
     (throw (ex-info (str "Enqueue input validation failed: " (:errors err))
                     {:type :mycelium.enqueue/input-error
                      :workflow-name workflow-name
                      :details err})))
   (queue/enqueue! queue workflow-name
     {:initial-data initial-data}
     opts)))

(defn start-worker
  "Starts a worker loop that claims tasks from the queue and executes them.

  `queue` — a WorkQueue implementation.
  `workflows` — map of {workflow-name => compiled-workflow}.
  `resources` — resources map passed to workflow handlers.

  Returns a future that runs the worker loop. The loop continues until
  the future is cancelled. Each task is claimed, executed, and completed
  or failed based on the result.

  Resume: when task data contains :mycelium/session-id, the worker uses
  :resume-load, :resume-save, and :resume-delete callbacks to manage
  halted workflow state. See `mycelium.store/start-worker-with-store`
  for a convenience wrapper that supplies these from a WorkflowStore.

  Halt handling: when a workflow halts (returns :mycelium/resume):
  - With :on-halt: checks claimed? first to guard against stale leases,
    then invokes (on-halt result) and passes the return value to complete!.
  - Without :on-halt: dead-letters the task with a descriptive error.

  Options:
    :worker-id      — string identifying this worker (default: auto-generated)
    :poll-ms        — ms to sleep when queue is empty (default: 1000)
    :heartbeat-ms   — ms between heartbeat! calls during task execution
                      (default: no heartbeat). Set to ~claim-timeout-ms/3
                      for long-running workflows.
    :on-halt        — (fn [result] -> result) called when a workflow halts
                      (returns :mycelium/resume). The returned value is passed
                      to complete!. Use this to persist halted state.
    :resume-load    — (fn [session-id] -> halted-data-or-nil). Required for resume.
    :resume-save    — (fn [session-id data] -> session-id). Required for resume.
    :resume-delete  — (fn [session-id] -> nil). Required for resume."
  ([queue workflows resources]
   (start-worker queue workflows resources {}))
  ([queue workflows resources {:keys [worker-id poll-ms heartbeat-ms on-halt
                                      resume-load resume-save resume-delete]
                               :or {poll-ms 1000}}]
   (let [worker-id (or worker-id (str (java.util.UUID/randomUUID)))
         heartbeat-thread (volatile! nil)]
     (future
       (loop []
         (if-let [task (queue/claim! queue worker-id)]
           (let [wf-name (:workflow-name task)
                 compiled (get workflows wf-name)
                 task-id (:task-id task)]
               (if-not compiled
                 (do
                   (queue/fail! queue task-id worker-id
                     (ex-info (str "Unknown workflow: " wf-name) {}))
                   (recur))
                 (do
                   (let [cancel-heartbeat (fn []
                                            (when-let [t @heartbeat-thread]
                                              (future-cancel t)
                                              (vreset! heartbeat-thread nil)))
                         _ (when heartbeat-ms
                             (cancel-heartbeat)
                             (vreset! heartbeat-thread
                               (future
                                 (try
                                   (while true
                                     (Thread/sleep heartbeat-ms)
                                     (queue/heartbeat! queue task-id worker-id))
                                   (catch Exception _)))))
                         initial-data (:initial-data (:data task))]
                     (try
                       (if-let [session-id (:mycelium/session-id initial-data)]
                         ;; --- Resume path ---
                         (if-not (and resume-load resume-save resume-delete)
                           (queue/fail! queue task-id worker-id
                             (ex-info "Resume task requires :resume-load/:resume-save/:resume-delete callbacks" {}))
                           (if-let [halted (resume-load session-id)]
                             (let [merge-data (:mycelium/merge-data initial-data)
                                   result (resume-compiled compiled resources halted merge-data)]
                               (cond
                                 (error? result)
                                 (queue/fail! queue task-id worker-id
                                   (ex-info (str "Resume error: " (:message (workflow-error result)))
                                            {:result result}))

                                 (:mycelium/resume result)
                                 (do
                                   (resume-save session-id result)
                                   (queue/complete! queue task-id worker-id
                                     (-> result
                                         (dissoc :mycelium/resume)
                                         (assoc :mycelium/session-id session-id))))

                                 :else
                                 (do
                                   (resume-delete session-id)
                                   (queue/complete! queue task-id worker-id result))))
                             (queue/fail! queue task-id worker-id
                               (ex-info (str "Resume session not found: " session-id) {}))))

                         ;; --- Normal execution path ---
                         (let [result (run-compiled compiled resources initial-data)]
                           (cond
                             (error? result)
                             (queue/fail! queue task-id worker-id
                               (ex-info (str "Workflow error: " (:message (workflow-error result)))
                                        {:result result}))

                             (:mycelium/resume result)
                             (if on-halt
                               (if (queue/claimed? queue task-id worker-id)
                                 (let [result' (assoc result :mycelium/session-id (str task-id))]
                                   (queue/complete! queue task-id worker-id (on-halt result')))
                                 (queue/fail! queue task-id worker-id
                                   (ex-info "Halted but lease expired before on-halt — may be re-claimed" {})))
                               (queue/fail! queue task-id worker-id
                                 (ex-info "Workflow halted but no :on-halt handler configured" {})))

                             :else
                             (queue/complete! queue task-id worker-id result))))
                    (catch Exception e
                      (queue/fail! queue task-id worker-id e))
                    (finally
                      (cancel-heartbeat))))
                   (recur))))
           ;; No task available — sleep and poll again
           (do
             (Thread/sleep poll-ms)
             (recur))))))))

(ns mycelium.store
  "Persistence protocol and helpers for halted workflow state.
   Allows workflows to halt, persist their state, and resume later
   (potentially in a different process or after a restart)."
  (:require [mycelium.core :as myc]
            [mycelium.queue :as queue]))

(defprotocol WorkflowStore
  (save-workflow! [store session-id halted-data]
    "Persist halted workflow state. Returns session-id.")
  (load-workflow [store session-id]
    "Load halted workflow state. Returns data map or nil if not found.")
  (delete-workflow! [store session-id]
    "Remove persisted state after completion or cancellation.")
  (list-workflows [store]
    "List all persisted session IDs."))

(defn memory-store
  "Creates an in-memory workflow store backed by an atom.
   Suitable for development and testing."
  []
  (let [state (atom {})]
    (reify WorkflowStore
      (save-workflow! [_ session-id data]
        (swap! state assoc session-id data)
        session-id)
      (load-workflow [_ session-id]
        (get @state session-id))
      (delete-workflow! [_ session-id]
        (swap! state dissoc session-id)
        nil)
      (list-workflows [_]
        (keys @state)))))

(defn new-session-id
  "Generates a unique session ID string.
   Prefer (str task-id) for queue-based workflows — it gives
   deterministic correlation between the enqueued task and the
   persisted session."
  []
  (str (java.util.UUID/randomUUID)))

(defn run-with-store
  "Runs a pre-compiled workflow. If it halts, persists state to store and returns
   {:mycelium/session-id id, :mycelium/halt halt-context, ...visible-data}.
   If it completes normally, returns the result unchanged (nothing persisted).
   `session-id` can be provided in opts as :session-id, otherwise auto-generated."
  ([compiled-workflow resources initial-data store]
   (run-with-store compiled-workflow resources initial-data store {}))
  ([compiled-workflow resources initial-data store opts]
   (let [result (myc/run-compiled compiled-workflow resources initial-data)]
     (if (:mycelium/resume result)
       ;; Halted — persist and return session reference
       (let [session-id (or (:session-id opts) (new-session-id))]
         (save-workflow! store session-id result)
         (-> result
             (dissoc :mycelium/resume)
             (assoc :mycelium/session-id session-id)))
       ;; Completed normally
       result))))

(defn resume-with-store
  "Loads halted state from store by session-id, resumes the workflow inline.
   On completion, deletes persisted state and returns result.
   On re-halt, updates store and returns session reference.
   Optional merge-data is merged into the data before resuming.

   For queue-backed async resume, use `enqueue-resume` instead."
  ([compiled-workflow resources session-id store]
   (resume-with-store compiled-workflow resources session-id store nil))
  ([compiled-workflow resources session-id store merge-data]
   (let [halted-data (load-workflow store session-id)]
     (when-not halted-data
       (throw (ex-info (str "Workflow session not found: " session-id)
                       {:session-id session-id})))
     (let [result (myc/resume-compiled compiled-workflow resources halted-data merge-data)]
       (if (:mycelium/resume result)
         ;; Re-halted — update store with new state
         (do
           (save-workflow! store session-id result)
           (-> result
               (dissoc :mycelium/resume)
               (assoc :mycelium/session-id session-id)))
         ;; Completed — clean up store
         (do
           (delete-workflow! store session-id)
           result))))))

(defn enqueue-resume
  "Enqueues a resume task for a halted workflow. The worker picks it up,
   loads the halted state from the store, and resumes execution with all
   queue guarantees (lease, retry, heartbeat, dead-letter).

   `queue` — a WorkQueue implementation.
   `workflow-name` — keyword identifying the workflow (used by worker to look up compiled spec).
   `compiled-workflow` — pre-compiled workflow (from `mycelium.core/pre-compile`).
   `resources` — resources map passed to workflow handlers.
   `session-id` — the session ID from a previous halt.
   `store` — a WorkflowStore instance. The worker must also have this store
     (via `start-worker-with-store`'s :store option).
   `merge-data` — optional map to merge into the data before resuming
     (e.g., human-provided input).

   Returns a UUID task-id for the resume task. The persisted state keeps
   its original `session-id` (the one passed in), not (str task-id) — the
   resume task reuses the existing session rather than creating a new one.
   The result of the resumed workflow is not returned from this function;
   use `load-workflow` on completion or inspect the worker's output.

   Note: the resume task carries a control map (:mycelium/session-id,
   :mycelium/merge-data) rather than workflow input, so it deliberately
   bypasses `enqueue-workflow`'s start-schema validation — resume re-enters
   the FSM mid-flight on already-validated data."
  ([queue workflow-name compiled-workflow resources session-id store]
   (enqueue-resume queue workflow-name compiled-workflow resources session-id store nil))
  ([queue workflow-name _compiled-workflow _resources session-id store merge-data]
   (when-not (load-workflow store session-id)
     (throw (ex-info (str "Workflow session not found: " session-id)
                     {:session-id session-id})))
   (queue/enqueue! queue workflow-name
     {:initial-data {:mycelium/session-id session-id
                     :mycelium/merge-data merge-data}})))

(defn start-worker-with-store
  "Like `mycelium.core/start-worker`, but with store-backed halt/resume.

   When a workflow halts, the worker persists state to the store using
   session-id = (str task-id) — deterministic, so the enqueuer can
   discover the session from the returned task-id.

   When a resume task is claimed (task data contains :mycelium/session-id),
   the worker loads halted state from the store, calls resume-compiled,
   and cleans up the store on completion. Re-halts update the existing
   session.

   `queue` — a WorkQueue implementation.
   `workflows` — map of {workflow-name => compiled-workflow}.
   `resources` — resources map passed to workflow handlers.
   `store` — a WorkflowStore instance.
   `opts` — passed through to `start-worker` (:worker-id, :poll-ms, :heartbeat-ms)."
  [queue workflows resources store & {:as opts}]
  (myc/start-worker queue workflows resources
    (assoc opts
      :resume-load   (fn [sid] (load-workflow store sid))
      :resume-save   (fn [sid data] (save-workflow! store sid data))
      :resume-delete (fn [sid] (delete-workflow! store sid))
      :on-halt
      (fn [result]
        (let [session-id (:mycelium/session-id result)]
          (save-workflow! store session-id result)
          (-> result
              (dissoc :mycelium/resume)
              (assoc :mycelium/session-id session-id)))))))

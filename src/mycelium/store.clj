(ns mycelium.store
  "Persistence protocol and helpers for halted workflow state.
   Allows workflows to halt, persist their state, and resume later
   (potentially in a different process or after a restart)."
  (:require [mycelium.core :as myc]))

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
  "Generates a unique session ID string."
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
  "Loads halted state from store by session-id, resumes the workflow.
   On completion, deletes persisted state and returns result.
   On re-halt, updates store and returns session reference.
   Optional merge-data is merged into the data before resuming."
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

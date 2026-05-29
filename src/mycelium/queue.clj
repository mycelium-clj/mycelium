(ns mycelium.queue
  "Durable queue abstraction for asynchronous workflow execution.

  Provides a WorkQueue protocol that can be backed by in-memory storage
  (default, zero-config) or durable storage like Postgres (via Absurd or
  custom adapters).

  Default: `memory-queue` — in-memory, no durability, same process.
  For production durability, pass a custom implementation when creating
  workflows (e.g., an Absurd-backed adapter)."
  (:import [java.util.concurrent PriorityBlockingQueue]
           [java.util.concurrent.locks ReentrantLock]))

;; ===== Protocol =====

(defprotocol WorkQueue
  (enqueue!
    [queue workflow-name data]
    [queue workflow-name data opts]
    "Enqueue a workflow run for asynchronous execution.

    `workflow-name` — keyword identifying the workflow definition.
    `data` — the initial data map for the workflow.
    `opts` — optional map:
      :run-at         — epoch-ms when the task becomes available (default: now)
      :max-attempts   — max retries before dead-letter (default: 1)

    Returns a UUID task-id.")

  (claim!
    [queue worker-id]
    "Atomically claim the next available task.

    Returns a task map with :task-id, :workflow-name, :data, :attempt, :worker-id,
    or nil if no tasks are available. Claim includes a lease — if the worker
    doesn't call heartbeat! or complete!/fail! within the lease timeout, the
    task becomes available for other workers.")

  (complete!
    [queue task-id worker-id result]
    "Mark a claimed task as completed successfully.
    Only succeeds if the worker-id matches the current claim holder
    and the lease has not expired. Otherwise it's a no-op.")

  (fail!
    [queue task-id worker-id error]
    "Mark a claimed task as failed.
    Only succeeds if the worker-id matches the current claim holder
    and the lease has not expired. Otherwise it's a no-op.
    If attempts < max-attempts, the task is re-queued for retry
    with exponential backoff (1s, 2s, 4s, ... capped at 60s).
    If attempts exhausted, the task is dead-lettered (removed from queue).
    `error` should be a Throwable or ex-info.")

  (heartbeat!
    [queue task-id worker-id]
    "Refresh the claim lease for a running task, preventing expiry.
    Only succeeds if the worker-id matches the current claim holder
    and the lease has not expired. Otherwise it's a no-op.")

  (claimed?
    [queue task-id worker-id]
    "Returns true if the task is currently claimed by worker-id
    and the lease has not expired. Used by workers to guard side
    effects (e.g., store writes) before completing/failing a task.")

  (queue-depth
    [queue]
    "Returns the number of pending tasks (including claimed but not yet
    completed/failed). Useful for monitoring.")

  (dead-lettered
    [queue]
    "Returns a vector of dead-lettered task entries.
    Each entry is a map with :task-id, :workflow-name, :data, :error, :failed-at.
    Entries are retained for inspection (callers decide when to purge)."))

;; ===== Helpers =====

(defn- now-ms []
  (System/currentTimeMillis))

(defn- new-uuid []
  (java.util.UUID/randomUUID))

(defn- backoff-ms
  "Exponential backoff for retries: 1s, 2s, 4s, 8s, ... capped at 60s."
  [attempt]
  (min (* 1000 (long (Math/pow 2 (dec attempt)))) 60000))

(defn memory-queue
  "Creates an in-memory work queue backed by a priority queue.

  Tasks are ordered by :run-at (earliest first), then by insertion order.
  No durability — all state is lost on process restart.

  Suitable for development, testing, and single-process deployments.
  For production durability, provide a custom WorkQueue implementation.

  Implementation notes:
  - claim! runs an O(n) scan over all tasks to drain expired claims.
    Acceptable for modest queue sizes; a durable adapter backed by
    indexed Postgres rows would handle this more efficiently.
  - Long-running workflows MUST set :heartbeat-ms on the worker and
    :max-attempts > 1 to avoid premature dead-lettering (default
    claim-timeout-ms is 5 min, default max-attempts is 1).

  Options:
    :claim-timeout-ms — lease timeout in ms (default: 300000 = 5 min)
    :max-attempts     — default max retries (default: 1 = no retry)
    :max-dead-letters — max dead-letter entries retained (default: 10000)"
  ([]
   (memory-queue {}))
   ([{:keys [claim-timeout-ms max-attempts max-dead-letters]
       :or {claim-timeout-ms 300000
            max-attempts 1
            max-dead-letters 10000}}]
    (let [seq-counter (atom 0)
          tasks (atom {})
          dead-letters (atom [])
          dead-letter! (fn [entry]
                         (swap! dead-letters
                           (fn [dls]
                             (let [v (conj dls entry)]
                               (if (> (count v) max-dead-letters)
                                 (subvec v (- (count v) max-dead-letters))
                                 v)))))
          lock (ReentrantLock.)
          pq (PriorityBlockingQueue. 64
               (reify java.util.Comparator
                 (compare [_ a b]
                   (let [c (compare (:run-at a) (:run-at b))]
                     (if (zero? c)
                       (compare (:seq a) (:seq b))
                       c)))))]
     (reify
       WorkQueue

       (enqueue! [this workflow-name data]
         (enqueue! this workflow-name data nil))

       (enqueue! [_ workflow-name data opts]
         (let [task-id (new-uuid)
               run-at  (or (:run-at opts) (now-ms))
               max-att (or (:max-attempts opts) max-attempts)
               task    {:task-id        task-id
                        :workflow-name  workflow-name
                        :data           data
                        :run-at         run-at
                        :max-attempts   max-att
                        :attempt        0
                        :seq            (swap! seq-counter inc)
                        :state          :pending
                        :worker-id      nil
                        :claimed-at     nil
                        :claim-expires-at nil}]
           (.lock lock)
           (try
             (swap! tasks assoc task-id task)
             (.put pq task)
             (finally
               (.unlock lock)))
           task-id))

       (claim! [_ worker-id]
         (.lock lock)
         (try
             ;; Drain expired claims back to pending, dead-letter if attempts exhausted
             (doseq [[tid task] @tasks]
               (when (and (= :claimed (:state task))
                          (<= (:claim-expires-at task) (now-ms)))
                 (let [next-attempt (inc (:attempt task))]
                   (if (< next-attempt (:max-attempts task))
                     ;; Re-queue for retry
                     (let [updated (-> task
                                       (assoc :state :pending
                                              :worker-id nil
                                              :claimed-at nil
                                              :claim-expires-at nil
                                              :attempt next-attempt))]
                       (swap! tasks assoc tid updated)
                       (.put pq updated))
                     ;; Attempts exhausted — dead letter
                     (do
                       (dead-letter! {:task-id       (:task-id task)
                                       :workflow-name (:workflow-name task)
                                       :data          (:data task)
                                       :error         (ex-info "Claim lease expired, max attempts exhausted" {})
                                       :failed-at     (now-ms)})
                       (swap! tasks dissoc tid))))))
           ;; Poll for eligible tasks, skipping stale entries
           (loop []
             (if-let [task (.poll pq)]
               (if (and (= :pending (:state task))
                        (<= (:run-at task) (now-ms)))
                 ;; Eligible — claim it
                 (let [now    (now-ms)
                       claimed (-> task
                                   (assoc :state :claimed
                                          :worker-id worker-id
                                          :claimed-at now
                                          :claim-expires-at (+ now claim-timeout-ms)))]
                   (swap! tasks assoc (:task-id task) claimed)
                   {:task-id       (:task-id claimed)
                    :workflow-name (:workflow-name claimed)
                    :data          (:data claimed)
                    :attempt       (:attempt claimed)
                    :worker-id     worker-id})
                 ;; Stale entry (completed/dead) or not yet eligible
                 (if (contains? @tasks (:task-id task))
                   ;; Still tracked but not eligible (e.g. future run-at) — put back, stop
                   (do (.put pq task) nil)
                   ;; Stale — skip and continue looping
                   (recur)))
               ;; Queue empty
               nil))
           (finally
             (.unlock lock))))

       (complete! [_ task-id worker-id _result]
         (.lock lock)
         (try
           (when-let [task (get @tasks task-id)]
             (when (and (= :claimed (:state task))
                        (= worker-id (:worker-id task))
                        (< (now-ms) (:claim-expires-at task)))
               (swap! tasks dissoc task-id)))
           (finally
             (.unlock lock))))

       (fail! [_ task-id worker-id error]
         (.lock lock)
         (try
           (when-let [task (get @tasks task-id)]
             (when (and (= :claimed (:state task))
                        (= worker-id (:worker-id task))
                        (< (now-ms) (:claim-expires-at task)))
               (let [attempts (inc (:attempt task))]
                 (if (< attempts (:max-attempts task))
                   (let [retried (-> task
                                     (assoc :state :pending
                                            :attempt attempts
                                            :worker-id nil
                                            :claimed-at nil
                                            :claim-expires-at nil
                                            :run-at (+ (now-ms) (backoff-ms attempts))))]
                     (swap! tasks assoc task-id retried)
                     (.put pq retried))
                   (do
                     (dead-letter! {:task-id       (:task-id task)
                                    :workflow-name (:workflow-name task)
                                    :data          (:data task)
                                    :error         error
                                    :failed-at     (now-ms)})
                     (swap! tasks dissoc task-id))))))
           (finally
             (.unlock lock))))

       (heartbeat! [_ task-id worker-id]
         (.lock lock)
         (try
           (when-let [task (get @tasks task-id)]
             (when (and (= :claimed (:state task))
                        (= worker-id (:worker-id task))
                        (< (now-ms) (:claim-expires-at task)))
               (swap! tasks assoc task-id
                      (assoc task :claim-expires-at (+ (now-ms) claim-timeout-ms)))))
           (finally
             (.unlock lock))))

       (claimed? [_ task-id worker-id]
         (when-let [task (get @tasks task-id)]
           (and (= :claimed (:state task))
                (= worker-id (:worker-id task))
                (< (now-ms) (:claim-expires-at task)))))

       (queue-depth [_]
         (count @tasks))

       (dead-lettered [_]
         (vec @dead-letters))))))

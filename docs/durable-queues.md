# Durable Queues

Mycelium includes a queue abstraction for asynchronous workflow execution with
lease-based claims, retries, heartbeats, and dead-letter visibility.
The default implementation is in-memory (zero-config), and a Postgres-backed
adapter is available via [mycelium-absurd](https://github.com/mycelium-clj/mycelium-absurd).

## Concepts

### Task lifecycle

```
enqueue → claim → execute → complete
                  ↘ fail → retry (with backoff) → claim → ...
                        ↘ exhausted → dead-letter
```

- **Enqueue**: submit a workflow for async execution. Returns a task-id.
  Optionally schedule for a future time with `:run-at`.
- **Claim**: a worker atomically picks up the next available task.
  Only one worker can claim a task at a time (lease-based).
- **Execute**: the worker runs the workflow via `run-compiled`.
- **Complete**: on success, the task is removed from the queue.
- **Fail**: on error, the task is re-queued with exponential backoff
  (1s, 2s, 4s, ... capped at 60s) if attempts remain. When max attempts
  are exhausted, the task is dead-lettered.
- **Heartbeat**: long-running tasks can extend their lease to prevent
  being reclaimed by another worker.

### In-memory (default)

```clojure
(require '[mycelium.core :as myc]
         '[mycelium.queue :as q])

;; Create an in-memory queue
(def queue (q/memory-queue))

;; Compile your workflow
(def compiled (myc/pre-compile workflow-def))

;; Enqueue a task
(def task-id (myc/enqueue-workflow queue :my-wf compiled {:input "data"}))

;; Start a worker
(def worker (myc/start-worker queue {:my-wf compiled} resources
              {:poll-ms 500}))

;; Graceful shutdown
(future-cancel worker)
```

In-memory queues are suitable for development, testing, and single-process
deployments. All state is lost on process restart.

### Postgres-backed (mycelium-absurd)

[mycelium-absurd](https://github.com/mycelium-clj/mycelium-absurd) implements
the `WorkQueue` protocol using [Absurd](https://github.com/earendil-works/absurd)'s
Postgres stored procedures. Tasks survive restarts, and workers can be
distributed across machines.

```clojure
(require '[mycelium-absurd.core :refer [absurd-queue]]
         '[mycelium.core :as myc])

;; Create a Postgres-backed queue (absurd.sql must be applied first)
(def queue (absurd-queue hikari-datasource "my-workflows"))

;; Use identically to the in-memory queue
(def task-id (myc/enqueue-workflow queue :my-wf compiled {:input "data"}))
(def worker (myc/start-worker queue {:my-wf compiled} resources {:poll-ms 500}))
```

### Halt/resume with stores

Workflows can halt (e.g., for human approval) and resume later. When using
`start-worker-with-store`, halted state is persisted automatically:

```clojure
(require '[mycelium.store :as store])

;; Memory store (development)
(def store (store/memory-store))

;; Start worker with halt/resume support
(def worker
  (store/start-worker-with-store queue workflows resources store
    {:heartbeat-ms 60000}))

;; When a workflow halts, the worker persists state and completes the task.
;; The session-id = (str task-id), so the enqueuer already has it.

;; To resume:
(store/resume-with-store compiled resources session-id store)
```

For Postgres-backed stores, implement the `WorkflowStore` protocol with your
database of choice. The [mycelium-absurd](https://github.com/mycelium-clj/mycelium-absurd)
library includes Absurd-backed queue support; combine it with a database-backed
store for fully durable halt/resume.

## Configuration

### Queue options

```clojure
;; In-memory queue with custom settings
(q/memory-queue
  {:claim-timeout-ms   300000   ;; 5 min lease (default)
   :max-attempts       3        ;; 3 retries before dead-letter (default: 1)
   :max-dead-letters   10000})  ;; cap on retained dead-letter entries (default: 10000)
```

### Worker options

```clojure
(myc/start-worker queue workflows resources
  {:worker-id     "my-worker"   ;; auto-generated if omitted
   :poll-ms       1000          ;; sleep when queue empty (default: 1000)
   :heartbeat-ms  60000})       ;; heartbeat interval for long-running tasks
```

## Protocol

The `WorkQueue` protocol (defined in `mycelium.queue`) has 8 operations:

| Operation | Purpose |
|-----------|---------|
| `enqueue!` | Submit a workflow run (supports `:run-at`, `:max-attempts`) |
| `claim!` | Atomically claim next available task with lease |
| `complete!` | Mark task completed (fenced by worker-id + lease) |
| `fail!` | Mark task failed, re-queue with backoff if attempts remain |
| `heartbeat!` | Extend claim lease (fenced by worker-id + lease) |
| `claimed?` | Check if worker still holds a valid claim |
| `queue-depth` | Count pending + claimed tasks (for monitoring) |
| `dead-lettered` | Inspect tasks that exhausted all retries |

Custom implementations must implement all 8 methods. See the
[memory-queue](https://github.com/mycelium-clj/mycelium/blob/main/src/mycelium/queue.clj)
source for a reference implementation.

## Long-running workflows

The default claim lease is 5 minutes. Workflows that may run longer must:

1. Set `:heartbeat-ms` on the worker (e.g., to 60000 for 1-minute heartbeats)
2. Set `:max-attempts` > 1 to survive occasional timeouts
3. Ensure cell handlers are idempotent — expired leases can cause
   concurrent re-execution even if the original run completed

## Dead-letter inspection

```clojure
;; View tasks that exhausted all retries
(q/dead-lettered queue)
;; => [{:task-id #uuid "..."
;;      :workflow-name :my-wf
;;      :data {:input "data"}
;;      :error #error{...}
;;      :failed-at 1699123456789}
;;     ...]
```

## Custom adapters

Implement `mycelium.queue/WorkQueue` for any backend (Redis, SQS, Kafka, etc.):

```clojure
(require '[mycelium.queue :as mq])

(defn my-redis-queue [conn]
  (reify mq/WorkQueue
    (enqueue! [_ workflow-name data] ...)
    (enqueue! [_ workflow-name data opts] ...)
    (claim! [_ worker-id] ...)
    (complete! [_ task-id worker-id result] ...)
    (fail! [_ task-id worker-id error] ...)
    (heartbeat! [_ task-id worker-id] ...)
    (claimed? [_ task-id worker-id] ...)
    (queue-depth [_] ...)
    (dead-lettered [_] ...)))
```

### Relationship to WorkflowStore

`WorkQueue` and `WorkflowStore` are separate protocols:

- **WorkQueue**: handles task lifecycle (enqueue, claim, complete/fail, heartbeat).
  This is where durability lives — Postgres, Redis, SQS.
- **WorkflowStore**: handles persisting halted workflow state for human-in-the-loop
  workflows. Can be backed by the same database or a different one.
- **start-worker-with-store**: wires them together so halts automatically persist
  state and resume tasks are re-enqueued.

Both are protocol-based and swappable independently.

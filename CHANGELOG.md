# Changelog

## 2026-03-07

### Unified Error Inspection

`workflow-error` and `error?` functions for consistent error handling. Instead of checking 6 different `:mycelium/*` keys with different shapes, use one function:

```clojure
(let [result (myc/run-workflow wf resources data opts)]
  (if (myc/error? result)
    (let [{:keys [error-type cell-id message details]} (myc/workflow-error result)]
      (log/error error-type cell-id message))
    (handle-success result)))
```

Error types: `:schema/input`, `:schema/output`, `:handler`, `:resilience/timeout`, `:resilience/circuit-open`, `:resilience/bulkhead-full`, `:resilience/rate-limited`, `:join`, `:timeout`, `:input`. All return a consistent map with `:error-type`, `:message`, and `:details`.

### Live Execution Tracing

`:on-trace` callback for real-time observation of workflow execution. Called after each cell completes with the trace entry (cell name, cell-id, transition, duration, data snapshot, errors). No more `println` in handlers.

```clojure
;; Custom callback
(myc/run-workflow wf resources data
  {:on-trace (fn [entry] (log/info (:cell entry) (:duration-ms entry)))})

;; Built-in logger for REPL debugging
(myc/run-workflow wf resources data {:on-trace (dev/trace-logger)})
```

Also adds `dev/format-trace` to pretty-print a `:mycelium/trace` vector after the fact.

### Auto Key Propagation

Automatic key propagation, enabled by default. Each cell's output is automatically merged with its input — cells only need to return new or changed keys. Eliminates the boilerplate of explicitly passing through all upstream keys in every cell's output and output schema. Disable with `:propagate-keys? false` if needed.

```clojure
;; Before: handler must include ALL keys downstream cells need
(defmethod cell/cell-spec :process/compute-tax [_]
  {:handler (fn [_ data]
              (assoc data :tax (* (:subtotal data) 0.1)))
   :schema {:output [:map [:subtotal :double] [:items :any] [:tax :double]]}})

;; After: handler returns only new/changed keys
(defmethod cell/cell-spec :process/compute-tax [_]
  {:handler (fn [_ data] {:tax (* (:subtotal data) 0.1)})
   :schema {:output [:map [:tax :double]]}})

;; Key propagation is on by default — no opt-in needed
(myc/run-workflow workflow-def resources data)
```

Handler output takes precedence over input keys (explicit override). Internal `:mycelium/*` keys are excluded from propagation. Works with both sync and async cells, and combines with `:coerce? true`. Disable with `{:propagate-keys? false}`.

### Schema Coercion

Automatic numeric type coercion via `:coerce? true` in compilation options. Eliminates `int` vs `double` mismatches — when a cell produces `949.0` (double) but the next cell's schema expects `:int`, coercion converts it automatically. Handles both `double→int` and `int→double`. Non-numeric values still fail validation normally.

```clojure
(myc/run-workflow workflow-def resources data {:coerce? true})

;; Or with pre-compile:
(def compiled (myc/pre-compile workflow-def {:coerce? true}))
```

Fractional values like `949.5` are never silently truncated — only whole-valued doubles (`949.0`) are coerced. Uses Malli's built-in `json-transformer` under the hood. New public functions in `mycelium.schema`: `coerce-input`, `coerce-output`.

### Improved Schema Error Messages

Schema validation errors now include enriched diagnostics:

- **`:failed-keys`** — per-key map showing the actual `:value`, its Java `:type`, and the error `:message`
- **`:cell-path`** — vector of cell names that executed before the failure
- **`:data`** — stripped of `:mycelium/*` internal keys to reduce noise

```clojure
;; Before:
{:cell-id :step-b, :phase :input,
 :errors {:count ["should be an integer"]},
 :data {:count 42.5, :mycelium/trace [...200 lines...]}}

;; After:
{:cell-id :step-b, :phase :input,
 :errors {:count ["should be an integer"]},
 :data {:count 42.5},
 :failed-keys {:count {:value 42.5, :type "java.lang.Double",
                        :message "should be an integer"}},
 :cell-path [:start]}
```

## 2026-03-06

### WorkflowStore Persistence Protocol

Formal persistence layer for halt/resume. `WorkflowStore` protocol with `save-workflow!`, `load-workflow`, `delete-workflow!`, `list-workflows`. Includes an in-memory implementation for dev/testing. Store-aware helpers (`run-with-store`, `resume-with-store`) auto-persist on halt, auto-delete on completion, and reuse session IDs across re-halts.

```clojure
(def s (store/memory-store))
(def halted (store/run-with-store compiled resources data s))
(store/resume-with-store compiled resources (:mycelium/session-id halted) s {:approved true})
```

## 2026-03-05

### Error Groups

Shared error handling across sets of cells. Instead of wiring `:on-error` on every cell, declare a group with a single error handler. The framework injects try/catch at compile time, captures error details in `:mycelium/error`, and routes to the handler. Works with both sync and async cells.

```clojure
{:error-groups {:pipeline {:cells [:fetch :transform]
                            :on-error :err}}}
```

### Region Briefs

Named cell groupings for LLM orchestration. `region-brief` generates a scoped summary of a subgraph: member cells with schemas, internal edges, entry points, and exit points. Purely informational — no runtime behavior change.

```clojure
{:regions {:auth [:parse-request :validate-session]}}

(orch/region-brief manifest :auth)
```

### Graph-Level Timeouts

Declarative timeout routing on edges. Specify a timeout in milliseconds and a `:timeout` edge target — the framework races the cell against the clock and routes to the fallback on expiry. Handlers stay pure with no timeout logic. Distinct from resilience4j time limiters, which produce errors rather than route.

```clojure
{:timeouts {:fetch 5000}
 :edges    {:fetch {:done :process, :timeout :fallback}}}
```

### Compile-Time Path Constraints

Declare structural invariants that are validated against all enumerated workflow paths at compile time. Catches misrouted workflows before they run.

- `:must-follow` — if cell A runs, cell B must appear later on the same path
- `:must-precede` — cell A must appear before cell B on every path containing B
- `:never-together` — cells never appear on the same path
- `:always-reachable` — cell must be reachable from every non-terminal cell

```clojure
{:constraints [{:type :must-follow :if :flag-missing :then :apply-tags}
               {:type :never-together :cells [:manual-review :auto-approve]}]}
```

### Default Transitions

`:default` edge label acts as a catch-all fallback. Auto-generates `(constantly true)` as the last dispatch predicate — a safety net when no other predicate matches. Particularly useful for agent-generated routing logic.

```clojure
{:edges {:start {:success :process, :default :error-handler}}}
```

### Halt/Resume (Human-in-the-Loop)

Workflows can halt mid-execution at designated points and resume later with new input. Workflow state is serializable for persistence across sessions, enabling human review steps, approval gates, and multi-session workflows.

### Parameterized Cells and Resilience Policies

Cells accept runtime parameters, and handlers can be wrapped with resilience4j policies (retry with backoff, circuit breaker, bulkhead, time limiter) declared in the workflow manifest. Policies are applied at compile time — cell handlers stay focused on domain logic.

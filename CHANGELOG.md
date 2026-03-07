# Changelog

## 2026-03-07

### Schema Coercion

Automatic numeric type coercion via `:coerce? true` in compilation options. Eliminates `int` vs `double` mismatches — when a cell produces `949.0` (double) but the next cell's schema expects `:int`, coercion converts it automatically. Handles both `double→int` and `int→double`. Non-numeric values still fail validation normally.

```clojure
(myc/run-workflow workflow-def resources data {:coerce? true})

;; Or with pre-compile:
(def compiled (myc/pre-compile workflow-def {:coerce? true}))
```

New public functions in `mycelium.schema`: `coerce-input`, `coerce-output`, `number-coercion-transformer`.

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

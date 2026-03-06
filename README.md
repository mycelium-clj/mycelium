# Mycelium

[![Tests](https://github.com/yogthos/mycelium/actions/workflows/test.yml/badge.svg)](https://github.com/yogthos/mycelium/actions/workflows/test.yml)

Schema-enforced, composable workflow components for Clojure. Built on [Maestro](https://github.com/yogthos/maestro).

Mycelium structures applications as directed graphs of pure data transformations. Each node (cell) has explicit input/output schemas. Cells are developed and tested in complete isolation, then composed into workflows that are validated at compile time. Routing between cells is determined by dispatch predicates defined at the workflow level — handlers compute data, the graph decides where it goes.

## Why

LLM coding agents fail at large codebases for the same reason humans do: unbounded context. Mycelium solves this by constraining each component to a fixed scope with an explicit contract. An agent implementing a cell never needs to see the rest of the application — just its schema and test harness.

See [Managing Complexity with Mycelium](https://yogthos.net/posts/2026-02-25-ai-at-scale.html) for a longer rationale.

## Quick Start

```clojure
;; deps.edn
{:deps {io.github.yogthos/mycelium {:git/url "https://github.com/yogthos/mycelium"
                                    :git/sha "..."}}}
```

### Define Cells

A cell is a function with schema contracts, registered via `defmethod`:

```clojure
(require '[mycelium.cell :as cell])

(defmethod cell/cell-spec :math/double [_]
  {:id          :math/double
   :handler     (fn [_resources data]
                  (assoc data :result (* 2 (:x data))))
   :schema      {:input  [:map [:x :int]]
                 :output [:map [:result :int]]}})

(defmethod cell/cell-spec :math/add-ten [_]
  {:id          :math/add-ten
   :handler     (fn [_resources data]
                  (assoc data :result (+ 10 (:result data))))
   :schema      {:input  [:map [:result :int]]
                 :output [:map [:result :int]]}})
```

Each cell is a miniature program akin to a microservice. The purpose of a cell is to encapsulate the implementation details for a particular step in the application workflow. For example, a cell handling authentication might check the database for the user account, compare it with the provided credentials, and then return an updated state with additional keys indicating the result of the operation.

Cells must:
- Return the data map (application state) with any computed values added
- Produce output satisfying their `:output` schema on every path
- Only use resources passed via the first argument

### Compose into Workflows

```clojure
(require '[mycelium.core :as myc])

(let [result (myc/run-workflow
               {:cells {:start  :math/double
                        :add    :math/add-ten}
                :edges {:start {:done :add}
                        :add   {:done :end}}
                :dispatches {:start [[:done (constantly true)]]
                             :add   [[:done (constantly true)]]}}
               {}          ;; resources
               {:x 5})]    ;; initial data
  (:result result))
;; => 20
```

### Pipeline Shorthand

For linear (unbranched) workflows, use `:pipeline` instead of wiring `:edges` and `:dispatches` by hand:

```clojure
(myc/run-workflow
  {:cells    {:start :math/double
              :add   :math/add-ten}
   :pipeline [:start :add]}
  {} {:x 5})
;; Equivalent to {:edges {:start :add, :add :end}, :dispatches {}}
```

`:pipeline` expands each pair into an unconditional edge and appends `:end` after the last cell. It is mutually exclusive with `:edges`, `:dispatches`, and `:joins`. Works in both programmatic workflow definitions and manifests.

### Branching

Edges map transition labels to targets. Dispatch predicates examine the data to determine which edge to take:

```clojure
(defmethod cell/cell-spec :check/threshold [_]
  {:id          :check/threshold
   :handler     (fn [_ data]
                  (assoc data :above-threshold (> (:value data) 10)))
   :schema      {:input  [:map [:value :int]]
                 :output [:map]}})

(myc/run-workflow
  {:cells {:start :check/threshold
           :big   :process/big-values
           :small :process/small-values}
   :edges {:start {:high :big, :low :small}
           :big   {:done :end}
           :small {:done :end}}
   :dispatches {:start [[:high (fn [data] (:above-threshold data))]
                        [:low  (fn [data] (not (:above-threshold data)))]]
                :big   [[:done (constantly true)]]
                :small [[:done (constantly true)]]}}
  {} {:value 42})
```

Handlers compute data; dispatch predicates decide the route. This keeps business logic decoupled from graph navigation.

### Default Transitions

Use `:default` as an edge label for a catch-all fallback. If no other dispatch predicate matches, the `:default` edge is taken automatically — no dispatch predicate needed:

```clojure
(myc/run-workflow
  {:cells {:start :check/threshold
           :big   :process/big-values
           :err   :process/error-handler}
   :edges {:start {:high :big, :default :err}
           :big   {:done :end}
           :err   {:done :end}}
   :dispatches {:start [[:high (fn [data] (> (:value data) 10))]]
                :big   [[:done (constantly true)]]
                :err   [[:done (constantly true)]]}}
  {} {:value 3})
;; value <= 10, :high predicate fails → :default catches → routes to :err
```

This is especially useful as a safety net for agent-generated routing logic. `:default` must not be the only edge — if all paths lead to the same target, use an unconditional keyword edge instead.

### Graph-Level Timeouts

Move timeout logic from handlers to the workflow definition. When a cell exceeds its timeout, the framework injects `:mycelium/timeout true` into data and routes to the `:timeout` edge target. Handlers stay pure — no timeout logic needed:

```clojure
(myc/run-workflow
  {:cells {:start    :api/fetch-data
           :render   :ui/render-result
           :fallback :ui/show-error}
   :edges {:start    {:done :render, :timeout :fallback}
           :render   :end
           :fallback :end}
   :dispatches {:start [[:done (fn [d] (not (:mycelium/timeout d)))]]}
   :timeouts {:start 5000}}  ;; 5 seconds
  {} {:url "https://api.example.com/data"})
```

The `:timeout` dispatch is auto-injected and evaluated first (before user predicates and `:default`). This is distinct from resilience `:timeout` policies — graph timeouts **route** to an alternative cell, resilience timeouts produce `:mycelium/resilience-error`.

### Per-Transition Output Schemas

Cells with multiple outgoing edges can declare different output schemas for each transition:

```clojure
(defmethod cell/cell-spec :user/fetch [_]
  {:id          :user/fetch
   :handler     (fn [{:keys [db]} data]
                  (if-let [profile (get-user db (:user-id data))]
                    (assoc data :profile profile)
                    (assoc data :error-message "Not found")))
   :schema      {:input  [:map [:user-id :string]]
                 :output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
                          :not-found [:map [:error-message :string]]}}
   :requires    [:db]})
```

In the workflow, per-transition schemas are validated based on which dispatch matched:

```clojure
;; Single schema (all transitions must satisfy it)
:output [:map [:profile map?]]

;; Per-transition schemas (each transition has its own contract)
:output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
         :not-found [:map [:error-message :string]]}
```

The schema chain validator tracks which keys are available on each path independently, so a downstream cell on the `:found` path can require `:profile` without the `:not-found` path needing to produce it.

### Resources

External dependencies are injected, never acquired by cells:

```clojure
(defmethod cell/cell-spec :user/fetch [_]
  {:id          :user/fetch
   :handler     (fn [{:keys [db]} data]
                  (if-let [profile (get-user db (:user-id data))]
                    (assoc data :profile profile)
                    (assoc data :error-message "Not found")))
   :schema      {:input  [:map [:user-id :string]]
                 :output {:found     [:map [:profile [:map [:name :string] [:email :string]]]]
                          :not-found [:map [:error-message :string]]}}
   :requires    [:db]})

;; Resources are passed at run time
(myc/run-workflow workflow {:db my-db-conn} {:user-id "alice"})
```

### Async Cells

```clojure
(defmethod cell/cell-spec :api/fetch-data [_]
  {:id          :api/fetch-data
   :handler     (fn [_resources data callback error-callback]
                  (future
                    (try
                      (let [resp (http/get (:url data))]
                        (callback (assoc data :response resp)))
                      (catch Exception e
                        (error-callback e)))))
   :schema      {:input  [:map [:url :string]]
                 :output [:map [:response map?]]}
   :async?      true})
```

### Parameterized Cells

The same cell handler can be reused with different configuration by passing a map with `:id` and `:params` instead of a bare keyword:

```clojure
(defmethod cell/cell-spec :math/multiply [_]
  {:id      :math/multiply
   :handler (fn [_ data]
              (let [factor (get-in data [:mycelium/params :factor])]
                (assoc data :result (* factor (:x data)))))
   :schema  {:input  [:map [:x :int]]
             :output [:map [:result :int]]}})

(myc/run-workflow
  {:cells {:start  {:id :math/multiply :params {:factor 3}}
           :double {:id :math/multiply :params {:factor 2}}}
   :pipeline [:start :double]}
  {} {:x 5})
;; => {:x 5, :result 30}  — first ×3 = 15, then ×2 = 30
```

Params are injected as `:mycelium/params` in the data map before the handler runs, and automatically cleaned up after each step. Bare keywords continue to work as before.

## Accumulating Data Model

Cells communicate through an accumulating data map. Every cell receives the full map of all keys produced by every prior cell in the path — not just its immediate predecessor. Cells `assoc` their outputs into this map, and the enriched map flows forward.

This means a cell can depend on data produced several steps earlier without any special wiring:

```
start → validate-session → fetch-profile → fetch-orders → render-summary → end
         produces :user-id   produces :profile  produces :orders   needs :profile AND :orders
```

- **Non-adjacent dependency**: `:fetch-orders` needs `:user-id` from `:validate-session` (two steps back). It works because `:user-id` persists through `:fetch-profile`.
- **Multi-source inputs**: `:render-summary` needs both `:profile` (from `:fetch-profile`) and `:orders` (from `:fetch-orders`). Both are present in the accumulated map.
- **Schema chain validation**: `compile-workflow` walks each path from `:start`, accumulating declared output keys at every edge. By the time it reaches `:render-summary`, the available key set includes outputs from all prior cells — so both `:profile` and `:orders` are in scope.

This model handles linear and branching (tree) topologies naturally. For parallel fan-out/fan-in (diamond shapes where cells execute concurrently and merge results), use join nodes — described next.

## Join Nodes (Fork-Join Parallel Execution)

When multiple independent cells can run concurrently, declare a **join node** that groups them together:

```clojure
{:cells {:start          :auth/validate-session
         :fetch-profile  :user/fetch-profile     ;; join member
         :fetch-orders   :user/fetch-orders      ;; join member
         :render-summary :ui/render-summary
         :render-error   :ui/render-error}

 :joins {:fetch-data {:cells    [:fetch-profile :fetch-orders]
                      :strategy :parallel}}       ;; or :sequential

 :edges {:start          {:authorized :fetch-data, :unauthorized :render-error}
         :fetch-data     {:done :render-summary, :failure :render-error}
         :render-summary {:done :end}
         :render-error   {:done :end}}

 :dispatches {:start [[:authorized   (fn [d] (:session-valid d))]
                       [:unauthorized (fn [d] (not (:session-valid d)))]]
              :render-summary [[:done (constantly true)]]
              :render-error   [[:done (constantly true)]]}}
```

Key concepts:

- **Join members** (`:fetch-profile`, `:fetch-orders`) exist in `:cells` but have **no entries in `:edges`** — they are consumed by the join
- The **join name** (`:fetch-data`) appears in `:edges` like a regular cell
- Each member receives the **same input snapshot** — parallel and sequential produce identical results
- The join provides **default dispatches** (`:done` / `:failure`) based on whether any branch threw an exception

### Snapshot Semantics

Each branch in a join receives the same data snapshot from upstream. Branches cannot see each other's outputs. After all branches complete, their results are merged into the data map.

### Output Key Conflict Detection

At compile time, Mycelium collects output schema keys from all join members. If any key appears in multiple cells:

- **No `:merge-fn`** → compile-time error listing the conflicting keys and which cells produce them
- **`:merge-fn` provided** → allowed, the user handles conflict resolution

```clojure
;; Both cells produce :items — requires :merge-fn
:joins {:gather {:cells    [:source-a :source-b]
                 :merge-fn (fn [data results]
                             (assoc data :items
                                    (vec (mapcat :items results))))}}
```

The `:merge-fn` receives the original upstream data and a vector of result maps from each branch.

### Error Handling

All branches run to completion (no early cancellation). Errors are collected in `:mycelium/join-error` on the data map. The join's default dispatches route to `:failure` when errors are present:

```clojure
;; Join edges should include a :failure route
:edges {:fetch-data {:done :render-summary, :failure :render-error}}
```

### Async Cells in Joins

Async cells (`:async? true`) work within joins. The join handler wraps them with promise-based invocation, so both sync and async cells execute uniformly.

### Schema Chain Integration

The schema chain validator handles joins by:
1. Validating each member cell's input keys are available from upstream
2. Accumulating the **union** of all member cells' output keys as available downstream

This means a cell after the join can require keys from any member — the validator knows the join produces the union of all member outputs.

### Join Trace

Each join produces a single trace entry with a `:join-traces` vector containing per-member timing and status:

```clojure
{:cell :fetch-data
 :cell-id :mycelium.join/fetch-data
 :transition :done
 :join-traces [{:cell :fetch-profile, :cell-id :user/fetch-profile,
                :duration-ms 12.3, :status :ok}
               {:cell :fetch-orders, :cell-id :user/fetch-orders,
                :duration-ms 8.7, :status :ok}]}
```

### Join Options

| Option | Default | Description |
|--------|---------|-------------|
| `:cells` | (required) | Vector of cell names to run in the join |
| `:strategy` | `:parallel` | `:parallel` or `:sequential` |
| `:merge-fn` | `nil` | `(fn [data results])` — custom result merging |
| `:on-failure` | `nil` | Cell name to route to on failure (informational) |
| `:timeout-ms` | `30000` | Timeout in ms for async cell invocations within the join |

## Resilience Policies

Cells can be wrapped with [resilience4j](https://github.com/resilience4j/resilience4j) policies — timeouts, retries, circuit breakers, bulkheads, and rate limiters — via the `:resilience` key in a workflow definition:

```clojure
(myc/run-workflow
  {:cells {:start :api/fetch-data
           :fallback :ui/show-error}
   :edges {:start    {:done :end, :failed :fallback}
           :fallback :end}
   :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                         [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}
   :resilience {:start {:timeout        {:timeout-ms 5000}
                         :retry          {:max-attempts 3 :wait-ms 200}
                         :circuit-breaker {:failure-rate 50
                                           :minimum-calls 10
                                           :sliding-window-size 100
                                           :wait-in-open-ms 60000}
                         :bulkhead       {:max-concurrent 25 :max-wait-ms 0}
                         :rate-limiter   {:limit-for-period 50
                                          :limit-refresh-period-ms 500
                                          :timeout-ms 5000}
                         :async-timeout-ms 30000}}}
  resources initial-data)
```

When a resilience policy triggers (timeout, circuit open, bulkhead full, rate limited, retries exhausted), the handler returns data with `:mycelium/resilience-error` instead of throwing. Use dispatch predicates to route to fallback cells.

### Resilience Error Map

```clojure
{:type    :timeout       ;; :timeout, :circuit-open, :bulkhead-full, :rate-limited, :unknown
 :cell    :start         ;; workflow cell name
 :message "..."}         ;; human-readable description
```

### Stateful Policies

Circuit breakers and rate limiters track state across calls. Use `pre-compile` + `run-compiled` so the same instance is reused:

```clojure
(def compiled (myc/pre-compile workflow-def))
(myc/run-compiled compiled resources data)  ;; CB state persists
```

### Policy Reference

| Policy | Key | Config | Default |
|--------|-----|--------|---------|
| Timeout | `:timeout` | `:timeout-ms` | (required) |
| Retry | `:retry` | `:max-attempts`, `:wait-ms` | 3, 500 |
| Circuit Breaker | `:circuit-breaker` | `:failure-rate`, `:minimum-calls`, `:sliding-window-size`, `:wait-in-open-ms` | 50, 10, 100, 60000 |
| Bulkhead | `:bulkhead` | `:max-concurrent`, `:max-wait-ms` | 25, 0 |
| Rate Limiter | `:rate-limiter` | `:limit-for-period`, `:limit-refresh-period-ms`, `:timeout-ms` | 50, 500, 5000 |
| Async Timeout | `:async-timeout-ms` | (integer, ms) | 30000 |

`:async-timeout-ms` controls how long the resilience wrapper waits for an async cell's promise to resolve. It is independent of the resilience4j `:timeout` policy.

## Halt & Resume (Human-in-the-Loop)

A cell can pause the entire workflow by returning `:mycelium/halt` in its data. The workflow halts after that cell's handler runs, preserving all accumulated data and trace. A human (or external process) can then inspect the halted state, optionally inject new data, and resume execution from where it stopped.

```clojure
;; Cell signals halt
(defmethod cell/cell-spec :review/check [_]
  {:id      :review/check
   :handler (fn [_ data]
              (assoc data :mycelium/halt {:reason :needs-approval
                                          :item   (:item-id data)}))
   :schema  {:input [:map [:item-id :string]] :output [:map]}})

;; Run workflow — halts at :review/check
(let [compiled (myc/pre-compile workflow-def)
      halted   (myc/run-compiled compiled resources {:item-id "X"})
      ;; halted contains :mycelium/halt context and :mycelium/resume token
      ;; Human reviews, then resume with additional data:
      result   (myc/resume-compiled compiled resources halted {:approved true})]
  (:approved result)) ;; => true
```

Key behaviors:

- `:mycelium/halt` can be `true` or a context map (e.g. `{:reason :needs-approval}`)
- All accumulated data from before the halt is preserved after resume
- `:mycelium/trace` is continuous across halt/resume boundaries
- A workflow can halt and resume multiple times
- If the halting cell dispatches to a branch, resume continues on the correct branch
- Calling `resume-compiled` on a non-halted result throws an exception

## Constraints

Declare compile-time path invariants that are checked against all enumerated workflow paths:

```clojure
{:cells {:start :check/flag, :fix :repair/apply-tags, :done :ui/render}
 :pipeline [:start :fix :done]
 :constraints [{:type :must-follow, :if :start, :then :fix}]}
```

| Type | Meaning |
|------|---------|
| `:must-follow` | If `:if` cell appears on a path, `:then` cell must appear later |
| `:must-precede` | `:cell` must appear before `:before` on every path containing `:before` |
| `:never-together` | Listed `:cells` must never all appear on the same path |
| `:always-reachable` | `:cell` must appear on every path that reaches `:end` |

`:always-reachable` only checks paths to `:end` — paths terminating at `:error` or `:halt` are ignored. It passes vacuously if no paths reach `:end`. Constraints reference workflow cell names (including join names), not join member cells. Violations throw at compile time with the specific path that violates the constraint.

## Compile-Time Validation

`compile-workflow` validates before any code runs:

- **Cell existence** — all referenced cells must be registered
- **Edge targets** — all targets must point to valid cells, join names, or `:end`/`:error`/`:halt`
- **Reachability** — every cell and join must be reachable from `:start`
- **Dispatch coverage** — every edge label must have a corresponding dispatch predicate, and vice versa
- **Schema chain** — each cell's input keys must be available from upstream outputs (join-aware: validates member inputs and accumulates union of member outputs)
- **Constraints** — path invariants checked against all enumerated paths
- **Graph timeouts** — timeout cells exist, values are positive integers, cells have `:timeout` edge
- **Resilience validation** — policy keys are valid, referenced cells exist, timeout-ms is positive
- **Join validation** — member cells exist, no name collisions with cells, members have no edges, output keys are disjoint (or `:merge-fn` provided), strategy is valid, no cell appears in multiple joins
- **Region validation** (manifest) — region cells exist, no cell in multiple regions

```
Schema chain error: :user/fetch-profile at :fetch-profile requires keys #{:user-id}
but only #{:http-request} available
```

## Runtime Schema Enforcement

Pre and post interceptors validate every transition automatically:

- **Pre**: validates input data against the cell's `:input` schema before the handler runs
- **Post**: validates output data against `:output` schema, using the dispatch target to select per-transition schemas

Schema violations redirect to the error state with detailed diagnostics attached at `:mycelium/schema-error`.

## Workflow Trace

Every workflow run produces a `:mycelium/trace` vector in the result data — a step-by-step record of which cells ran, what transition was taken, and what the data looked like after each step.

```clojure
(let [result (myc/run-workflow
               {:cells {:start :math/double, :add :math/add-ten}
                :edges {:start {:done :add}, :add {:done :end}}
                :dispatches {:start [[:done (constantly true)]]
                             :add   [[:done (constantly true)]]}}
               {} {:x 5})]
  (:mycelium/trace result))
;; => [{:cell :start, :cell-id :math/double, :transition :done,
;;      :data {:x 5, :result 10}}
;;     {:cell :add, :cell-id :math/add-ten, :transition :done,
;;      :data {:x 5, :result 20}}]
```

Each trace entry contains:

| Key | Description |
|-----|-------------|
| `:cell` | Workflow cell name (e.g. `:start`, `:validate`) |
| `:cell-id` | Cell registry ID (e.g. `:auth/validate`) |
| `:transition` | Dispatch label taken (`nil` for unconditional edges) |
| `:data` | Data snapshot after the handler ran |
| `:error` | Schema error details (only present on validation failure) |
| `:join-traces` | Per-member timing/status (only present for join nodes) |

Data snapshots exclude `:mycelium/trace` itself to avoid recursive nesting.

### Asserting on Traces in Tests

```clojure
(let [result (fsm/run compiled {} {:data {:count 0}})
      trace  (:mycelium/trace result)]
  ;; Verify execution order
  (is (= [:start :validate :finish] (mapv :cell trace)))
  ;; Verify transitions taken
  (is (= [:ok :authorized :done] (mapv :transition trace)))
  ;; Verify data at each step
  (is (= 42 (get-in (last trace) [:data :result]))))
```

### Error Traces

When a schema violation occurs, the trace entry for the failing step includes an `:error` key with the validation details:

```clojure
(let [error-data (atom nil)
      compiled   (wf/compile-workflow workflow
                   {:on-error (fn [_ fsm-state]
                                (reset! error-data (:data fsm-state))
                                (:data fsm-state))})]
  (fsm/run compiled {} {:data {:x 1}})
  (let [trace (:mycelium/trace @error-data)
        fail  (last trace)]
    (is (some? (:error fail)))
    (is (= :step2 (:cell fail)))))
```

### Composed Workflow Traces

Child workflow traces flow through automatically — the parent result's `:mycelium/trace` contains the child's step-by-step entries followed by the parent's own entry for the composed cell.

## Hierarchical Composition

Workflows can be nested as cells in parent workflows:

```clojure
(require '[mycelium.compose :as compose])

;; Wrap a child workflow as a cell
(compose/register-workflow-cell!
  :auth/flow
  {:cells {:start :auth/parse, :validate :auth/check}
   :edges {:start {:ok :validate, :fail :error}
           :validate {:ok :end, :fail :error}}
   :dispatches {:start    [[:ok   (fn [data] (:user-id data))]
                            [:fail (fn [data] (:error data))]]
                :validate [[:ok   (fn [data] (:session-valid data))]
                           [:fail (fn [data] (not (:session-valid data)))]]}}
  {:input  [:map [:http-request map?]]
   :output [:map [:user-id :string]]})

;; Use it in a parent workflow — composed cells provide :default-dispatches
(myc/run-workflow
  {:cells {:start :auth/flow
           :main  :app/dashboard}
   :edges {:start {:success :main, :failure :error}
           :main  {:done :end}}
   :dispatches {:main [[:done (constantly true)]]}}
  resources initial-data)
```

Child workflows produce `:success` or `:failure` based on whether `:mycelium/error` is present in the data. Composed cells carry `:default-dispatches` that `compile-workflow` uses as fallback when no explicit dispatches are provided for that position. Child execution traces are preserved at `:mycelium/child-trace`.

### Output Schema Inference

`workflow->cell` automatically infers the composed cell's output schema by walking the child workflow's edges to find cells that route to `:end` and collecting their declared output keys. This means the parent workflow's schema chain validator can see what a composed cell produces — no manual `set-cell-schema!` override needed.

```clojure
;; The child workflow's last cell declares [:map [:credit-score :int] [:risk-level :keyword]]
;; workflow->cell infers this and produces a per-transition output schema:
;;   {:success [:map [:credit-score :int] [:risk-level :keyword]]
;;    :failure [:map [:mycelium/error :any]]}
```

If you pass a concrete `[:map ...]` vector as `:output` in the schema argument, it takes precedence over inference.

## Manifest System

Define workflows as pure data in `.edn` files:

```clojure
;; workflows/user-onboarding.edn
{:id :user-onboarding
 :cells {:start {:id :auth/parse-request
                 :doc "Extract credentials from HTTP request"
                 :schema {:input  [:map [:http-request map?]]
                          :output [:map [:user-id :string] [:auth-token :string]]}}
         :validate {:id :auth/validate-session
                    :doc "Check credentials"
                    :schema {:input  [:map [:user-id :string] [:auth-token :string]]
                             :output [:map [:session-valid :boolean]]}
                    :requires [:db]}}
 :edges {:start    {:success :validate, :failure :error}
         :validate {:authorized :end, :unauthorized :error}}
 :dispatches {:start    [[:success (fn [data] (:user-id data))]
                         [:failure (fn [data] (not (:user-id data)))]]
              :validate [[:authorized   (fn [data] (:session-valid data))]
                         [:unauthorized (fn [data] (not (:session-valid data)))]]}}
```

Manifests can also include `:joins` — they are passed through to the workflow definition unchanged:

```clojure
{:id :order-summary
 :cells {:start         {:id :auth/extract-session ...}
         :fetch-profile {:id :user/fetch-profile ...}
         :fetch-orders  {:id :user/fetch-orders ...}
         :render-summary {:id :ui/render-summary ...}}
 :joins {:fetch-data {:cells    [:fetch-profile :fetch-orders]
                      :strategy :parallel}}
 :edges {:start      {:success :fetch-data}
         :fetch-data {:done :render-summary, :failure :error}
         ...}}
```

Dispatch predicates in EDN are `(fn ...)` forms compiled by Maestro's built-in SCI evaluator.

Load and validate:

```clojure
(require '[mycelium.manifest :as manifest])

(def m (manifest/load-manifest "workflows/user-onboarding.edn"))
```

Generate a cell brief for an agent:

```clojure
(manifest/cell-brief m :start)
;; => {:id :auth/parse-request
;;     :prompt "## Cell: :auth/parse-request\n..."
;;     :examples {:input {...} :output {...}}
;;     ...}
```

Convert to a compilable workflow (registers stub handlers for unimplemented cells):

```clojure
(def workflow-def (manifest/manifest->workflow m))
```

## Dev Tooling

### Test a Cell in Isolation

```clojure
(require '[mycelium.dev :as dev])

(dev/test-cell :auth/parse-request
  {:input     {:http-request {:headers {} :body {"username" "alice"}}}
   :resources {}})
;; => {:pass? true, :output {...}, :errors [], :duration-ms 0.42}

;; With dispatch predicates to verify which edge matches
(dev/test-cell :auth/parse-request
  {:input      {:http-request {:headers {} :body {"username" "alice"}}}
   :dispatches [[:success (fn [d] (:user-id d))]
                [:failure (fn [d] (not (:user-id d)))]]
   :expected-dispatch :success})
;; => {:pass? true, :matched-dispatch :success, ...}
```

### Test Multiple Transitions

```clojure
(dev/test-transitions :user/fetch-profile
  {:found     {:input {:user-id "alice" :session-valid true}
               :resources {:db my-db}
               :dispatches [[:found     (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error-type d))]]}
   :not-found {:input {:user-id "nobody" :session-valid true}
               :resources {:db my-db}
               :dispatches [[:found     (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error-type d))]]}})
;; => {:found     {:pass? true, :matched-dispatch :found, :output {...}}
;;     :not-found {:pass? true, :matched-dispatch :not-found, :output {...}}}
```

When a case omits `:dispatches`, `test-transitions` skips the dispatch check and tests output only. This is useful for cells without dispatch predicates:

```clojure
(dev/test-transitions :loan/classify-risk
  {:low    {:input {:credit-score 750}}
   :medium {:input {:credit-score 650}}
   :high   {:input {:credit-score 500}}})
;; => {:low {:pass? true, :output {:risk-level :low, ...}}, ...}
```

### Infer Workflow Schema

See the accumulated data shape at each point in a workflow:

```clojure
(dev/infer-workflow-schema workflow-def)
;; => {:start  {:available-before #{:x}
;;              :adds #{:a-done}
;;              :available-after #{:x :a-done}}
;;     :step-b {:available-before #{:x :a-done}
;;              :adds #{:b-done}
;;              :available-after #{:x :a-done :b-done}}}
```

Also available as `myc/infer-workflow-schema`. Handles branching (union of all incoming paths), joins (union of member outputs), and per-transition output schemas.

### Enumerate Workflow Paths

```clojure
(dev/enumerate-paths manifest)
;; => [[{:cell :start, :transition :success, :target :validate}
;;      {:cell :validate, :transition :authorized, :target :end}]
;;     [{:cell :start, :transition :failure, :target :error}]
;;     ...]
```

### Visualize a Workflow

```clojure
(dev/workflow->dot manifest)
;; => "digraph { ... }" — pipe to `dot -Tpng` to render
```

### Check Implementation Status

```clojure
(dev/workflow-status manifest)
;; => {:total 4, :passing 2, :failing 1, :pending 1, :cells [...]}
```

## Agent Orchestration

```clojure
(require '[mycelium.orchestrate :as orch])

;; Generate briefs for all cells
(orch/cell-briefs manifest)

;; Generate a targeted brief after failure
(orch/reassignment-brief manifest :validate
  {:error "Output missing key :session-valid"
   :input {:user-id "alice" :auth-token "tok_abc"}
   :output {:session-valid nil}})

;; Execution plan
(orch/plan manifest)
;; => {:scaffold [:start :validate ...], :parallel [[...]], :sequential []}

;; Progress report
(println (orch/progress manifest))

;; Region brief — scoped context for a subgraph cluster
(orch/region-brief manifest :auth)
;; => {:cells [{:name :start, :id :auth/parse, :schema {...}}, ...]
;;     :internal-edges {:start {:ok :validate-session}}
;;     :entry-points [:start]
;;     :exit-points [{:cell :validate-session, :transitions {:authorized :fetch-profile}}]
;;     :prompt "## Region: auth\n..."}
```

### Regions

Group cells into named regions in the manifest for LLM context scoping. `region-brief` generates a focused brief showing cells, schemas, internal edges, and entry/exit points — useful when an LLM only needs context for a subgraph:

```clojure
{:cells {:start :auth/parse, :validate :auth/validate, :fetch :user/fetch}
 :regions {:auth [:start :validate]}}
```

Region cells must exist in `:cells` and no cell may appear in multiple regions. Regions have no runtime effect.

## Architecture

```
mycelium/
├── src/mycelium/
│   ├── cell.clj          ;; Cell registry (multimethod-based)
│   ├── schema.clj        ;; Malli pre/post interceptors
│   ├── workflow.clj      ;; DSL → Maestro compiler
│   ├── resilience.clj    ;; resilience4j wrapper (timeout, retry, CB, bulkhead, rate limiter)
│   ├── compose.clj       ;; Hierarchical workflow nesting
│   ├── manifest.clj      ;; EDN manifest loading, cell briefs
│   ├── middleware.clj     ;; Ring middleware for workflow execution
│   ├── dev.clj           ;; Testing harness, visualization
│   ├── orchestrate.clj   ;; Agent orchestration helpers
│   └── core.clj          ;; Public API
└── test/mycelium/        ;; tests and assertions
```

## Acknowledgements

* [Statecharts: a visual formalism for complex systems](https://www.sciencedirect.com/science/article/pii/0167642387900359)

## License

Copyright (c) Dmitri Sotnikov. All rights reserved.

# Mycelium — LLM Quick-Start Guide

> Load this file into context when working with Mycelium projects.

## What It Is

Mycelium is a Clojure workflow framework built on [Maestro](https://github.com/yogthos/maestro). Applications are directed graphs of **cells** (pure data transformations) with Malli schema contracts on every input/output boundary. Cells are developed and tested in isolation, then composed into workflows validated at compile time.

**Key namespace:** `mycelium.core` (aliased as `myc`) — re-exports all public API functions.

## Core Concepts

### Cell

A function with schema contracts, registered via multimethod:

```clojure
(ns my.cells
  (:require [mycelium.cell :as cell]))

(defmethod cell/cell-spec :my/double [_]
  {:id      :my/double
   :handler (fn [resources data]
              (assoc data :result (* 2 (:x data))))
   :schema  {:input  [:map [:x :int]]
             :output [:map [:result :int]]}})
```

**Rules:**
- Handler signature: `(fn [resources data] -> data)` — always returns enriched data map
- `:input` / `:output` are [Malli](https://github.com/metosin/malli) schemas
- Cells never import or call other cells — they only see `resources` and `data`
- Resources (DB, HTTP clients, atoms) are injected, never acquired
- `:requires [:db :cache]` documents resource dependencies (informational)

### Workflow

A directed graph connecting cells:

```clojure
{:cells      {:start  :my/validate
              :step-b :my/process
              :step-c :my/render}
 :edges      {:start  {:valid :step-b, :invalid :end}
              :step-b :step-c
              :step-c :end}
 :dispatches {:start [[:valid   (fn [d] (= :ok (:status d)))]
                      [:invalid (fn [d] (not= :ok (:status d)))]]}}
```

**Edges** define topology. **Dispatches** are ordered `[label pred]` vectors — first matching predicate wins. Unconditional edges (keyword target) need no dispatch entry. Empty `:dispatches {}` is valid when all edges are unconditional.

### Pipeline Shorthand

For linear (unbranched) workflows, use `:pipeline` instead of `:edges` + `:dispatches`:

```clojure
{:cells    {:start :my/validate, :process :my/process, :render :my/render}
 :pipeline [:start :process :render]}
;; Expands to: {:edges {:start :process, :process :render, :render :end}, :dispatches {}}
```

Mutually exclusive with `:edges`, `:dispatches`, and `:joins`.

### Data Model

Cells communicate through an **accumulating data map**. Every cell receives all keys produced by every prior cell in the path. Cells `assoc` new keys; the enriched map flows forward.

```
start (has :x) → validate (adds :status) → process (sees :x AND :status, adds :result) → end
```

### Per-Transition Output Schemas

Cells with multiple outgoing edges can declare different output schemas per transition:

```clojure
:schema {:input  [:map [:id :string]]
         :output {:found     [:map [:profile [:map [:name :string]]]]
                  :not-found [:map [:error-message :string]]}}
```

The schema chain validator tracks keys per path independently.

## Running Workflows

```clojure
(require '[mycelium.core :as myc])

;; One-shot (compiles + runs)
(myc/run-workflow workflow-def resources initial-data)

;; Pre-compiled (zero overhead per call — use for production)
(def compiled (myc/pre-compile workflow-def))
(myc/run-compiled compiled resources initial-data)

;; Async variants
(myc/run-workflow-async workflow-def resources initial-data)
(myc/run-compiled-async compiled resources initial-data)
```

## Composition — Workflows as Cells

Nest a workflow inside a parent by wrapping it as a cell:

```clojure
(require '[mycelium.compose :as compose])

(compose/register-workflow-cell!
  :my/sub-workflow
  {:cells {:start :my/a, :step2 :my/b}
   :pipeline [:start :step2]}
  {:input  [:map [:x :int]]
   :output [:map [:result :int]]})  ;; or :map to infer

;; Use in parent:
{:cells {:start :my/sub-workflow, :next :my/downstream}
 :edges {:start {:success :next, :failure :error}
         :next  :end}}
;; No explicit dispatches needed — composed cells provide :default-dispatches
```

**Output inference:** `workflow->cell` automatically infers the composed cell's output schema by walking the child workflow's end-reaching cells. If you pass a concrete `[:map ...]` vector as `:output`, it takes precedence. Pass `:map` to use inference.

**Default dispatches:** `:success` (no `:mycelium/error`) and `:failure` (`:mycelium/error` present) are provided automatically.

## Join Nodes (Fork-Join)

Run cells in parallel (or sequential), merge results:

```clojure
{:cells {:start :my/auth, :a :my/fetch-profile, :b :my/fetch-orders, :render :my/page}
 :joins {:fetch-data {:cells [:a :b], :strategy :parallel}}
 :edges {:start {:ok :fetch-data, :fail :error}
         :fetch-data {:done :render, :failure :error}
         :render :end}
 :dispatches {:start [[:ok (fn [d] (:authed d))] [:fail (fn [d] (not (:authed d)))]]}}
```

- Join members exist in `:cells` but have **no entries in `:edges`**
- Each member gets a **snapshot** of the input data (branches don't see each other)
- Default dispatches: `:done` / `:failure` (provided automatically)
- Without `:merge-fn`, results are merged via `(apply merge data results)` — output keys must be disjoint
- With `:merge-fn`: `(fn [data results-vec] -> merged-data)`

## Parameterized Cells

Reuse the same handler with different configuration:

```clojure
{:cells {:triple {:id :math/multiply :params {:factor 3}}
         :double {:id :math/multiply :params {:factor 2}}}
 :pipeline [:triple :double]}
```

Params are injected as `:mycelium/params` in data before the handler runs. Access via `(get-in data [:mycelium/params :factor])`. Cleaned up automatically after each step. Bare keywords still work as before.

## Resilience Policies

Wrap cells with resilience4j policies via `:resilience`:

```clojure
{:cells {:start :api/call, :fallback :ui/error}
 :edges {:start {:done :end, :failed :fallback}, :fallback :end}
 :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                       [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}
 :resilience {:start {:timeout {:timeout-ms 5000}
                       :retry   {:max-attempts 3 :wait-ms 200}}}}
```

Available policies: `:timeout`, `:retry`, `:circuit-breaker`, `:bulkhead`, `:rate-limiter`. When triggered, data gets `:mycelium/resilience-error` (map with `:type`, `:cell`, `:message`). Use dispatch predicates to route to fallback cells.

Stateful policies (circuit breaker, rate limiter) require `pre-compile` + `run-compiled`. `:async-timeout-ms` (default 30s) controls promise wait time for async cells.

## Manifests (EDN)

Define workflows as data in `.edn` files:

```clojure
{:id :user-onboarding
 :cells {:start   {:id :auth/parse, :schema {:input [...] :output [...]}, :doc "..."}
         :validate {:id :auth/check, :schema :inherit}}  ;; :inherit pulls from registry
 :edges {:start {:ok :validate}, :validate :end}
 :dispatches {:start [[:ok (fn [d] (:user-id d))]]}}
```

```clojure
(def m (myc/load-manifest "workflows/user-onboarding.edn"))
(myc/cell-brief m :start)  ;; Self-contained prompt for an LLM to implement the cell
```

## Dev & Testing

```clojure
(require '[mycelium.dev :as dev])

;; Test a cell in isolation
(dev/test-cell :my/cell-id
  {:input {:x 5} :resources {} :dispatches [[:ok pred] [:fail pred]]})
;; => {:pass? true, :output {...}, :matched-dispatch :ok, :duration-ms 0.4}

;; Test multiple transitions at once
(dev/test-transitions :my/cell-id
  {:ok   {:input {:x 5}  :dispatches [[:ok pred] [:fail pred]]}
   :fail {:input {:x -1} :dispatches [[:ok pred] [:fail pred]]}})
;; => {:ok {:pass? true, ...}, :fail {:pass? true, ...}}

;; Cells without dispatch predicates — output-only check
(dev/test-transitions :my/classify
  {:low  {:input {:score 750}}
   :high {:input {:score 400}}})

;; See accumulated schema at each workflow step
(myc/infer-workflow-schema workflow-def)
;; => {:start  {:available-before #{:x}, :adds #{:status}, :available-after #{:x :status}}
;;     :step-b {:available-before #{:x :status}, :adds #{:result}, ...}}

;; Enumerate all paths through a workflow
(dev/enumerate-paths manifest)

;; Static analysis (reachability, cycles, dead ends)
(dev/analyze-workflow workflow-def)

;; DOT graph for visualization
(dev/workflow->dot manifest)  ;; pipe to `dot -Tpng`

;; Implementation status
(dev/workflow-status manifest)
;; => {:total 4, :passing 2, :failing 1, :pending 1, :cells [...]}
```

## Ring Middleware

```clojure
(def compiled (myc/pre-compile workflow-def))

;; Create a handler
(myc/workflow-handler compiled
  {:resources {:db db-conn}
   :input-fn  (fn [req] {:http-request req})
   :output-fn myc/html-response})
```

## Compile-Time Validations

`compile-workflow` checks before any code runs:

| Check | What it catches |
|-------|-----------------|
| Cell existence | All `:cells` values are registered |
| Edge targets | Targets point to valid cells, joins, or `:end`/`:error`/`:halt` |
| Reachability | Every cell reachable from `:start` |
| Dispatch coverage | Every edge label has a predicate (and vice versa) |
| Schema chain | Each cell's input keys available from upstream outputs |
| Join validation | Members exist, no name collisions, disjoint outputs |
| Resilience | Policy keys valid, referenced cells exist, timeout-ms positive |
| No-path-to-end | No reachable state stuck without path to `:end` |

## File Layout

```
src/mycelium/
  core.clj         Public API (re-exports)
  cell.clj         Cell registry (multimethod)
  schema.clj       Malli pre/post interceptors
  workflow.clj      DSL → Maestro compiler
  resilience.clj   resilience4j wrapper (timeout, retry, CB, bulkhead, rate limiter)
  compose.clj      Hierarchical workflow nesting
  manifest.clj     EDN manifest loading, cell briefs
  middleware.clj   Ring middleware
  dev.clj          Testing, visualization, schema inference
  orchestrate.clj  Agent orchestration helpers
```

## Common Patterns

### Implementing a Cell from a Brief

When you receive a cell brief (from `cell-brief`), implement it as:

```clojure
(defmethod cell/cell-spec :namespace/cell-name [_]
  {:id      :namespace/cell-name
   :handler (fn [resources data]
              ;; Your logic here — assoc results into data
              (assoc data :output-key computed-value))
   :schema  {:input  [:map [:required-key :type] ...]
             :output [:map [:output-key :type] ...]}})
```

### Testing Workflow

1. Implement cells with `defmethod cell/cell-spec`
2. Test each cell in isolation: `(dev/test-cell :id {:input {...}})`
3. Test dispatch paths: `(dev/test-transitions :id {:label {:input {...} :dispatches [...]}})`
4. Compile the workflow: `(myc/compile-workflow workflow-def)` — catches schema chain errors
5. Run end-to-end: `(myc/run-workflow workflow-def resources data)`
6. Assert on `:mycelium/trace` in the result for execution order

### Error Handling

- Schema violations redirect to `::fsm/error` with `:mycelium/schema-error` in data
- Custom error handling: pass `:on-error (fn [resources fsm-state] -> data)` to `compile-workflow`
- Composed cells set `:mycelium/error` on failure for parent dispatch routing
- Join errors collected in `:mycelium/join-error`
- Resilience errors in `:mycelium/resilience-error` — route to fallback cells via dispatch predicates

### Gotchas

- `::fsm/start` is NOT terminal — it runs a handler. Only `::end`, `::error`, `::halt` are terminal.
- Handlers receive `(resources, data)` — not the full FSM state.
- Dispatch predicates receive `data` (not fsm-state) — first truthy match wins.
- Ring's `wrap-params` produces STRING-keyed query params — cells must check both `(get qp :key)` and `(get qp "key")`.
- `:schema :inherit` in manifests resolves schema from cell registry — the cell must already be registered.
- Composed cell output schemas are inferred automatically. Only use `set-cell-schema!` if you need to override the inference.

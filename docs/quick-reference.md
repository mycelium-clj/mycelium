# Mycelium Quick Reference

## Core API

```clojure
(require '[mycelium.core :as myc])

;; Pre-compile once at startup (recommended for production)
(def compiled (myc/pre-compile workflow-def opts))

;; Run a pre-compiled workflow (zero compilation overhead)
(myc/run-compiled compiled resources initial-data)
(myc/run-compiled-async compiled resources initial-data)  ;; returns a future

;; Convenience: compile + run in one step (re-compiles every call)
(myc/run-workflow workflow-def resources initial-data)
(myc/run-workflow workflow-def resources initial-data opts)

;; Async convenience (returns a future)
(myc/run-workflow-async workflow-def resources initial-data)

;; Resume a halted workflow (human-in-the-loop)
(myc/resume-compiled compiled resources halted-result)
(myc/resume-compiled compiled resources halted-result {:human-input "value"})

;; Compile a system (all workflows)
(myc/compile-system {"/route" manifest, ...})
```

### Compilation Options

`pre-compile` and `run-workflow` accept an opts map:

```clojure
{:pre      (fn [fsm-state resources] fsm-state)  ;; pre-interceptor for every state
 :post     (fn [fsm-state resources] fsm-state)  ;; post-interceptor for every state
 :on-error (fn [resources fsm-state] data)        ;; runs when FSM enters error state
 :on-end   (fn [resources fsm-state] data)}       ;; runs when FSM enters end state
```

## Accumulating Data Model

Cells communicate through an accumulating data map. Every cell receives the full map of all keys produced by every prior cell in the path. Cells `assoc` their outputs and the enriched map flows forward.

```
start → validate → fetch-profile → fetch-orders → render
         adds :user-id  adds :profile   adds :orders   needs :profile AND :orders
```

A cell can depend on data produced several steps earlier without special wiring — keys persist through intermediate cells. The schema chain validator walks each path and confirms required keys are available from upstream outputs.

## Cell Registration

```clojure
(require '[mycelium.cell :as cell])

(defmethod cell/cell-spec :namespace/cell-id [_]
  {:id       :namespace/cell-id
   :handler  (fn [resources data] (assoc data :result "value"))
   :schema   {:input  [:map [:key :type]]
              :output [:map [:result :string]]}
   :requires [:db]         ;; optional — resource dependencies
   :async?   true          ;; optional — async handler signature
   :doc      "..."})       ;; optional

;; Async handler signature (4-arity with callbacks):
;; (fn [resources data callback error-callback] ...)
```

### Cell Registry Helpers

```clojure
(cell/list-cells)                     ;; => (:ns/a :ns/b ...) — all registered IDs
(cell/get-cell :ns/id)                ;; => spec map or nil
(cell/get-cell! :ns/id)               ;; => spec map or throws
(cell/set-cell-schema! :ns/id schema) ;; overwrite schema on registered cell
(cell/clear-registry!)                ;; remove all cells (testing only)
```

## Workflow Definition

```clojure
{:cells       {:start :cell/id, :step2 :cell/id2}    ;; or {:id :cell/id :params {...}}
 :edges       {:start {:label :step2}, :step2 {:done :end}}
 :dispatches  {:start [[:label (fn [data] (:key data))]]
               :step2 [[:done (constantly true)]]}
 :joins       {:join-name {:cells [:a :b] :strategy :parallel}}  ;; optional
 :input-schema [:map [:key :type]]                                ;; optional
 :interceptors [{:id :x :scope :all :pre (fn [d] d)}]            ;; optional
 :resilience  {:start {:timeout {:timeout-ms 5000}}}              ;; optional
}
```

### Pipeline Shorthand

```clojure
;; Instead of :edges + :dispatches for linear flows:
{:pipeline [:start :process :render]
 :cells    {:start :cell/a, :process :cell/b, :render :cell/c}}
;; Expands to :edges {:start :process, :process :render, :render :end}
;; Mutually exclusive with :edges, :dispatches, :fragments, :joins
```

### Branching

Edges map transition labels to targets. Dispatch predicates examine data to pick the edge:

```clojure
{:cells {:start :check/threshold
         :big   :process/big-values
         :small :process/small-values}
 :edges {:start {:high :big, :low :small}
         :big   {:done :end}
         :small {:done :end}}
 :dispatches {:start [[:high (fn [data] (> (:value data) 10))]
                      [:low  (fn [data] (<= (:value data) 10))]]
              :big   [[:done (constantly true)]]
              :small [[:done (constantly true)]]}}
```

Handlers compute data; dispatch predicates decide the route.

### Default Transitions

Use `:default` as an edge label for a catch-all fallback when no other dispatch predicate matches:

```clojure
{:cells {:start :check/validate, :ok :process/run, :err :process/error}
 :edges {:start {:success :ok, :default :err}
         :ok :end, :err :end}
 :dispatches {:start [[:success (fn [d] (:valid d))]]}}
;; :default auto-generates (constantly true) as the last predicate
;; No need to add [:default ...] to :dispatches
```

- `:default` must not be the only edge (use an unconditional keyword edge instead)
- You can provide an explicit `:default` predicate in `:dispatches` to override the auto-generated one
- `:default` is always evaluated last, even if listed first in `:dispatches`
- Works with join nodes — add `:default` alongside `:done`/`:failure` edges
- Trace entries record `:default` as the transition label

### Graph-Level Timeouts

Move timeout logic from handlers to the workflow definition. When a cell exceeds its timeout, the framework injects `:mycelium/timeout true` into data and routes to the `:timeout` edge target:

```clojure
{:cells {:fetch-tags :mp3/parse-id3
         :render     :ui/render-tags
         :fallback   :ui/show-error}
 :edges {:fetch-tags {:done :render, :timeout :fallback}
         :render     :end
         :fallback   :end}
 :dispatches {:fetch-tags [[:done (fn [d] (not (:mycelium/timeout d)))]]}
 :timeouts {:fetch-tags 5000}}  ;; ms
```

- Timeout values must be positive integers (milliseconds)
- Cells with timeouts must have a `:timeout` edge target
- A `:timeout` dispatch predicate is auto-injected and evaluated **first** (before user predicates)
- Works with both sync and async cells (async cells become blocking with timeout)
- Output schema validation is skipped for timed-out cells (handler didn't produce normal output)
- Trace entries include `:timeout? true` when a cell times out
- Distinct from resilience `:timeout` policies — graph timeouts **route**, resilience timeouts **error**

## Join Nodes (Fork-Join)

When multiple independent cells can run concurrently, declare a join node:

```clojure
{:cells {:start          :auth/validate-session
         :fetch-profile  :user/fetch-profile     ;; join member
         :fetch-orders   :user/fetch-orders      ;; join member
         :render-summary :ui/render-summary
         :render-error   :ui/render-error}

 :joins {:fetch-data {:cells    [:fetch-profile :fetch-orders]
                      :strategy :parallel}}

 :edges {:start          {:authorized :fetch-data, :unauthorized :render-error}
         :fetch-data     {:done :render-summary, :failure :render-error}
         :render-summary {:done :end}
         :render-error   {:done :end}}

 :dispatches {:start [[:authorized   (fn [d] (:session-valid d))]
                       [:unauthorized (fn [d] (not (:session-valid d)))]]
              :render-summary [[:done (constantly true)]]
              :render-error   [[:done (constantly true)]]}}
```

### Key Concepts

- **Join members** (`:fetch-profile`, `:fetch-orders`) exist in `:cells` but have **no entries in `:edges`** — the join consumes them
- The **join name** (`:fetch-data`) appears in `:edges` like a regular cell
- Each member receives the **same input snapshot** — branches cannot see each other's outputs
- After all branches complete, results are merged into the data map
- Default dispatches `:done` / `:failure` are provided based on whether any branch threw an exception

### Join Options

| Option | Default | Description |
|--------|---------|-------------|
| `:cells` | (required) | Vector of cell names to run |
| `:strategy` | `:parallel` | `:parallel` or `:sequential` |
| `:merge-fn` | `nil` | `(fn [data results-vec])` — custom merge when output keys overlap |
| `:timeout-ms` | `30000` | Timeout for async cells within the join |

### Output Key Conflicts

At compile time, output keys from all join members are checked for overlap:

- **No `:merge-fn`** — compile-time error listing conflicting keys
- **`:merge-fn` provided** — user handles conflict resolution

```clojure
;; Both cells produce :items — requires :merge-fn
:joins {:gather {:cells    [:source-a :source-b]
                 :merge-fn (fn [data results]
                             (assoc data :items
                                    (vec (mapcat :items results))))}}
```

### Join Error Handling

All branches run to completion (no early cancellation). Errors are collected in `:mycelium/join-error`. The join's default dispatches route to `:failure` when errors are present:

```clojure
:edges {:fetch-data {:done :render-summary, :failure :render-error}}
```

### Join Trace

Each join produces a trace entry with per-member timing:

```clojure
{:cell :fetch-data
 :cell-id :mycelium.join/fetch-data
 :transition :done
 :join-traces [{:cell :fetch-profile, :cell-id :user/fetch-profile,
                :duration-ms 12.3, :status :ok}
               {:cell :fetch-orders, :cell-id :user/fetch-orders,
                :duration-ms 8.7, :status :ok}]}
```

## Interceptors

Workflow-level interceptors wrap cell handlers at compile time:

```clojure
:interceptors [{:id    :log-timing
                :scope :all                              ;; every cell
                :pre   (fn [data] (assoc data ::t0 (System/nanoTime)))
                :post  (fn [data] (dissoc data ::t0))}

               {:id    :ui-only
                :scope {:id-match "ui/*"}                ;; glob on cell :id
                :pre   (fn [data] data)}

               {:id    :targeted
                :scope {:cells [:render :fetch]}         ;; explicit cell names
                :post  (fn [data] data)}]
```

Scope forms:

| Scope | Matches |
|-------|---------|
| `:all` | Every cell |
| `{:id-match "ui/*"}` | Cell registry `:id` matching glob (e.g. `:ui/render-dashboard`) |
| `{:cells [:x :y]}` | Specific workflow cell names |

Interceptor `:pre`/`:post` receive and return the `data` map (not fsm-state).

## Parameterized Cells

Reuse the same handler with different config by passing a map instead of a bare keyword:

```clojure
{:cells {:triple {:id :math/multiply :params {:factor 3}}
         :double {:id :math/multiply :params {:factor 2}}}
 :pipeline [:triple :double]}
```

Params are injected as `:mycelium/params` in the data map and cleaned up after each step. Access via `(get-in data [:mycelium/params :factor])`.

## Resilience Policies

Wrap cells with [resilience4j](https://github.com/resilience4j/resilience4j) policies via `:resilience`:

```clojure
{:cells {:start :api/call, :fallback :ui/error}
 :edges {:start {:done :end, :failed :fallback}, :fallback :end}
 :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                       [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}
 :resilience {:start {:timeout        {:timeout-ms 5000}
                       :retry          {:max-attempts 3 :wait-ms 200}
                       :circuit-breaker {:failure-rate 50 :minimum-calls 10
                                         :sliding-window-size 100 :wait-in-open-ms 60000}
                       :bulkhead       {:max-concurrent 25 :max-wait-ms 0}
                       :rate-limiter   {:limit-for-period 50
                                        :limit-refresh-period-ms 500 :timeout-ms 5000}
                       :async-timeout-ms 30000}}}
```

When triggered, handler returns data with `:mycelium/resilience-error` (map with `:type`, `:cell`, `:message`). Error types: `:timeout`, `:circuit-open`, `:bulkhead-full`, `:rate-limited`, `:unknown`.

Stateful policies (circuit breaker, rate limiter) require `pre-compile` + `run-compiled` to share state across calls.

`:async-timeout-ms` controls how long the resilience wrapper waits for an async handler's promise (default 30s). Independent of the resilience4j `:timeout` policy.

## Manifest Loading

```clojure
(require '[mycelium.manifest :as manifest])

(def m (manifest/load-manifest "path/to/file.edn"))
(def wf (manifest/manifest->workflow m))
(manifest/cell-brief m :cell-name)  ;; LLM-friendly prompt
```

### Manifest Cell Fields

```clojure
{:id       :namespace/name    ;; cell registry ID
 :schema   {:input  [...]     ;; Malli schema
            :output [...]}    ;; single or per-transition
 :doc      "..."              ;; optional description
 :requires [:db]              ;; optional resource dependencies
 :on-error :cell-name}        ;; or nil — required in strict mode (default)
```

Use `:schema :inherit` to resolve schema from the cell registry (avoids duplicating schemas in manifest):

```clojure
{:id :user/fetch-profile
 :schema :inherit             ;; pulls :input/:output from cell/cell-spec
 :on-error :error-handler}
```

### Manifest Validation

```clojure
(manifest/validate-manifest manifest)                ;; strict mode (default)
(manifest/validate-manifest manifest {:strict? false}) ;; skip :on-error requirement
```

## Fragment API

```clojure
(require '[mycelium.fragment :as fragment])

(fragment/load-fragment "fragments/auth.edn")              ;; load from classpath
(fragment/validate-fragment fragment-data)                  ;; validate structure
(fragment/expand-fragment frag mapping host-cells)          ;; expand one
(fragment/expand-all-fragments manifest)                    ;; expand all
```

Manifest fragment references support classpath refs or inline data:

```clojure
:fragments
  {:auth {:ref "fragments/auth.edn"             ;; loaded from classpath
          :as :start
          :exits {:success :dashboard, :failure :login-error}}
   :log  {:fragment {...inline fragment data...} ;; inline definition
          :as :start
          :exits {:success :next-step}}}
```

## Subworkflows (Nested Composition)

Wrap a workflow as a single opaque cell. See [subworkflows.md](subworkflows.md).

```clojure
(require '[mycelium.compose :as compose])

;; Register a workflow as a reusable cell
(compose/register-workflow-cell!
  :payment/flow
  {:cells {...} :edges {...} :dispatches {...}}
  {:input [:map ...] :output [:map ...]})

;; Or create spec without registering
(compose/workflow->cell :payment/flow workflow-def schema)
```

Default dispatches `:success` / `:failure` are provided automatically based on `:mycelium/error`.

Output schema is inferred automatically by walking child workflow edges to `:end` and collecting output keys. Pass an explicit `:output` schema to override.

## Output Schema Formats

```clojure
;; Single (all transitions)
:output [:map [:result :int]]

;; Per-transition
:output {:success [:map [:data :string]]
         :failure [:map [:error :string]]}
```

Per-transition schemas are validated based on which dispatch matched. The schema chain validator tracks available keys independently per path.

## Constraints

Declare compile-time path invariants that are checked against all enumerated paths:

```clojure
{:constraints [{:type :must-follow,      :if :flag-missing, :then :apply-tags}
               {:type :must-precede,     :cell :validate,   :before :process}
               {:type :never-together,   :cells [:manual-review :auto-approve]}
               {:type :always-reachable, :cell :audit-log}]}
```

| Type | Meaning |
|------|---------|
| `:must-follow` | If `:if` cell appears on a path, `:then` cell must appear later on that path |
| `:must-precede` | `:cell` must appear before `:before` on every path containing `:before` |
| `:never-together` | All listed `:cells` must never appear on the same path |
| `:always-reachable` | `:cell` must appear on every path that reaches `:end` (ignores `:error`/`:halt` paths) |

- Constraints reference **workflow cell names** (including join names), not join member cells or cell registry IDs
- `:always-reachable` passes vacuously if no paths reach `:end` (all paths go to `:error`/`:halt`)
- Violations throw at compile time with the specific path that violates the constraint

## Compile-Time Validation

`compile-workflow` validates before any code runs:

- **Cell existence** — all referenced cells must be registered
- **Edge targets** — must point to valid cells, join names, or `:end`/`:error`/`:halt`
- **Reachability** — every cell and join must be reachable from `:start`
- **Dispatch coverage** — every edge label must have a dispatch predicate, and vice versa
- **Schema chain** — each cell's input keys must be available from upstream outputs (join-aware)
- **Constraints** — path invariants checked against all enumerated paths (see Constraints)
- **Graph timeouts** — timeout cells exist, values are positive integers, cells have `:timeout` edge
- **Resilience validation** — policy keys valid, referenced cells exist, timeout-ms positive
- **Join validation** — member cells exist, no name collisions, members have no edges, output keys disjoint (or `:merge-fn` provided)

## Edge Targets

| Target | Meaning |
|--------|---------|
| `:cell-name` | Next cell in workflow |
| `:join-name` | Enter a join node |
| `:end` | Workflow completes successfully |
| `:error` | Workflow terminates with error |
| `:halt` | Workflow halts |
| `:_exit/name` | Fragment exit reference (resolved during expansion) |

## Ring Middleware

```clojure
(require '[mycelium.middleware :as mw])

;; Create a Ring handler from a pre-compiled workflow
(mw/workflow-handler compiled {:resources {:db db}})

;; With custom input/output transforms
(mw/workflow-handler compiled
  {:resources {:db db}
   :input-fn  (fn [req] {:http-request req})   ;; default
   :output-fn mw/html-response})               ;; default

;; :resources can be a function for per-request construction
(mw/workflow-handler compiled
  {:resources (fn [req] {:db db :request req})})

;; Standard HTML response helper
(mw/html-response result)  ;; => {:status 200 :body (:html result) ...}
```

## Dev Tools

```clojure
(require '[mycelium.dev :as dev])

;; Test a cell in isolation
(dev/test-cell :cell/id {:input {:key "val"} :resources {:db db}})
;; => {:pass? true, :output {...}, :errors [], :duration-ms 0.42}

;; Test with dispatch verification
(dev/test-cell :cell/id
  {:input      {:key "val"}
   :dispatches [[:success (fn [d] (:result d))]
                [:failure (fn [d] (:error d))]]
   :expected-dispatch :success})
;; => {:pass? true, :matched-dispatch :success, :output {...}, ...}

;; Test multiple transitions
(dev/test-transitions :cell/id
  {:found     {:input {:id "alice"} :resources {:db db}
               :dispatches [[:found (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error d))]]}
   :not-found {:input {:id "nobody"} :resources {:db db}
               :dispatches [[:found (fn [d] (:profile d))]
                            [:not-found (fn [d] (:error d))]]}})

;; Enumerate all paths from :start to terminal states
(dev/enumerate-paths workflow-def)

;; Generate DOT graph for visualization
(dev/workflow->dot workflow-def)

;; Check cell implementation status
(dev/workflow-status manifest)
;; => {:total 4, :passing 2, :failing 1, :pending 1, :cells [...]}

;; Static analysis — reachability, unreachable states, cycles
(dev/analyze-workflow workflow-def)
;; => {:reachable #{:start :step2} :unreachable #{} :no-path-to-end #{} :cycles []}

;; Infer accumulated schema at each cell
(dev/infer-workflow-schema workflow-def)
;; => {:start  {:available-before #{:x}, :adds #{:result}, :available-after #{:x :result}}
;;     :step2  {:available-before #{:x :result}, :adds #{:total}, ...}}
```

`analyze-workflow` and `infer-workflow-schema` are also available as `myc/analyze-workflow` and `myc/infer-workflow-schema`.

## Agent Orchestration

```clojure
(require '[mycelium.orchestrate :as orch])

;; Generate briefs for all cells (for parallel agent assignment)
(orch/cell-briefs manifest)
;; => {:start {:id :auth/parse, :prompt "## Cell: ...", ...}, ...}

;; Generate a targeted brief after a cell implementation fails
(orch/reassignment-brief manifest :validate
  {:error "Output missing key :session-valid"
   :input {:user-id "alice" :auth-token "tok_abc"}
   :output {:session-valid nil}})

;; Build plan — which cells can be implemented in parallel
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

Group cells into named regions in the manifest for LLM context scoping:

```clojure
{:cells {:start :auth/parse, :validate :auth/validate, :fetch :user/fetch, :render :ui/render}
 :regions {:auth       [:start :validate]
           :data-fetch [:fetch]}}
```

- Region cells must exist in `:cells`
- No cell may appear in multiple regions
- `region-brief` returns cell schemas, internal edges, entry/exit points, and a prompt
- Exit point `:transitions` is always a map — unconditional edges use `{:unconditional :target}`
- Regions are purely informational — no runtime behavior change

## Workflow Trace

Every run produces `:mycelium/trace` — a vector of step-by-step execution records:

```clojure
;; Each trace entry:
{:cell        :fetch-user       ;; workflow cell name
 :cell-id     :user/fetch       ;; registry cell ID
 :transition  :success          ;; dispatch label taken (nil for unconditional)
 :data        {...}             ;; data snapshot after handler ran
 :duration-ms 12.4              ;; execution time
 :error       {...}             ;; schema error details (only on validation failure)
 :join-traces [{...}]}          ;; per-member timing (only for join nodes)
```

### Asserting on Traces

```clojure
(let [result (myc/run-workflow wf {} {:x 5})
      trace  (:mycelium/trace result)]
  (is (= [:start :add] (mapv :cell trace)))
  (is (= [:done :done] (mapv :transition trace)))
  (is (= 20 (get-in (last trace) [:data :result]))))
```

## System Queries

```clojure
(require '[mycelium.system :as sys])

(sys/cell-usage system :cell/id)        ;; => ["/route1" "/route2"]
(sys/route-cells system "/route")       ;; => #{:cell/a :cell/b}
(sys/route-resources system "/route")   ;; => #{:db}
(sys/schema-conflicts system)           ;; => [{:cell-id ... :routes ...}]
(sys/system->dot system)                ;; => DOT graph string
```

## Halt & Resume (Human-in-the-Loop)

A cell can pause the workflow by returning `:mycelium/halt` in its data. The workflow halts after that cell, preserving all accumulated data and trace. A human (or external process) can later resume the workflow from where it stopped.

### Halting

```clojure
;; Cell signals halt by assoc'ing :mycelium/halt into data
(defmethod cell/cell-spec :review/check [_]
  {:id      :review/check
   :handler (fn [_ data]
              (assoc data :mycelium/halt {:reason :needs-approval
                                          :item   (:item-id data)}))
   :schema  {:input [:map [:item-id :string]] :output [:map]}})
```

`:mycelium/halt` can be `true` or a map with context for the human reviewer.

### Resuming

```clojure
(let [compiled (myc/pre-compile workflow-def)
      halted   (myc/run-compiled compiled resources {:item-id "X"})
      ;; halted contains :mycelium/halt and :mycelium/resume
      ;; Inspect halted, get human input, then resume:
      result   (myc/resume-compiled compiled resources halted {:approved true})]
  ;; result has data from before + after halt, :mycelium/halt cleared
  (:approved result)) ;; => true
```

`resume-compiled` takes an optional merge-data map (4th arg) that is merged into the data before resuming — useful for injecting human-provided input.

### Key Behaviors

- **Data accumulates** — all keys from before the halt are available after resume
- **Trace is continuous** — `:mycelium/trace` spans the full execution (before + after halt)
- **Multiple halts** — a workflow can halt and resume multiple times
- **Branching** — if a halting cell dispatches to a branch, resume continues on the correct branch
- **Halt trace entry** — the trace entry for the halting cell has `:halted true`
- **Resume validation** — calling `resume-compiled` on a non-halted result throws an exception

## Workflow Result Keys

| Key | Description |
|-----|-------------|
| `:mycelium/trace` | Vector of execution trace entries (see Workflow Trace) |
| `:mycelium/input-error` | Input schema validation failure (workflow didn't run) |
| `:mycelium/schema-error` | Runtime schema violation details |
| `:mycelium/join-error` | Join node error details |
| `:mycelium/timeout` | `true` when a cell exceeded its graph-level timeout |
| `:mycelium/resilience-error` | Resilience policy trigger details (`:type`, `:cell`, `:message`) |
| `:mycelium/child-trace` | Nested workflow trace (composed cells) |
| `:mycelium/halt` | Halt context (true or map) — present when workflow is halted |
| `:mycelium/resume` | Resume state token — present when workflow is halted |

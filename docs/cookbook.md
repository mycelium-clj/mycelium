# Mycelium Cookbook

Problem-oriented patterns for common workflow tasks. Each recipe shows the problem, the idiomatic solution, and the key details.

---

## Build a Linear Pipeline

**Problem:** You have a sequence of steps that run one after another with no branching.

```clojure
;; Use :pipeline shorthand — no :edges or :dispatches needed
(def workflow
  {:cells    {:start   :app/validate
              :process :app/transform
              :render  :app/format}
   :pipeline [:start :process :render]})

(def compiled (myc/pre-compile workflow))
(myc/run-compiled compiled {} {:input "data"})
```

`:pipeline` expands to `:edges {:start :process, :process :render, :render :end}` with no dispatch predicates. Mutually exclusive with `:edges`, `:dispatches`, `:fragments`, `:joins`.

---

## Add Branching Logic

**Problem:** A step needs to route to different cells based on its output.

```clojure
(def workflow
  {:cells {:start :order/check-fraud
           :ok    :order/process
           :flag  :order/manual-review}

   :edges {:start {:clean :ok, :suspicious :flag}
           :ok    :end
           :flag  :end}

   :dispatches {:start [[:clean      (fn [d] (= :ok (:fraud-status d)))]
                        [:suspicious (fn [d] (= :flagged (:fraud-status d)))]]}})
```

The cell handler sets a key (`:fraud-status`). Dispatch predicates examine that key and pick the edge. Handlers compute data; dispatches decide the route.

For per-transition output schemas, use a map instead of a single schema:

```clojure
:schema {:input  [:map [:total :double]]
         :output {:clean      [:map [:fraud-status [:= :ok]]]
                  :suspicious [:map [:fraud-status [:= :flagged]]]}}
```

---

## Add a Catch-All Fallback

**Problem:** You want a safety net when no dispatch predicate matches.

```clojure
{:edges {:start {:success :process, :default :error-handler}
         :process :end, :error-handler :end}
 :dispatches {:start [[:success (fn [d] (:valid d))]]}}
;; :default auto-generates (constantly true) as the last predicate
;; No need to add [:default ...] to :dispatches
```

`:default` is always evaluated last, even if listed first. It must not be the only edge (use an unconditional edge instead).

---

## Run Steps in Parallel (Fork-Join)

**Problem:** Multiple independent operations can run concurrently.

```clojure
(def workflow
  {:cells {:start    :order/validate
           :tax      :order/calc-tax       ;; join member
           :shipping :order/calc-shipping   ;; join member
           :total    :order/compute-total
           :err      :order/show-error}

   :joins {:fees {:cells [:tax :shipping] :strategy :parallel}}

   :edges {:start {:ok :fees, :invalid :err}
           :fees  {:done :total, :failure :err}
           :total :end, :err :end}

   :dispatches {:start [[:ok      (fn [d] (:valid d))]
                        [:invalid (fn [d] (not (:valid d)))]]}})
```

Join members exist in `:cells` but have **no entries in `:edges`**. Each member gets the same input snapshot. Output keys must not overlap (or provide `:merge-fn`). Results are merged into data, and `:done`/`:failure` dispatches are auto-generated.

---

## Handle Errors Across Multiple Cells

**Problem:** Several cells can fail the same way and should share one error handler.

```clojure
(def workflow
  {:cells {:start     :data/fetch
           :transform :data/transform
           :render    :data/render
           :err       :data/handle-error}

   :edges {:start     :transform
           :transform :render
           :render    :end
           :err       :end}

   :error-groups {:pipeline {:cells [:start :transform :render]
                              :on-error :err}}})
```

At compile time, the framework expands unconditional edges to include `:on-error` targets and injects dispatch predicates. If any grouped cell throws, `:mycelium/error` is set on data with `{:cell :cell-name, :message "..."}` and the error handler runs.

The error handler cell receives the full data map including `:mycelium/error`:

```clojure
(defmethod cell/cell-spec :data/handle-error [_]
  {:id      :data/handle-error
   :handler (fn [_ data]
              (let [{:keys [cell message]} (:mycelium/error data)]
                {:error-page (str "Failed at " cell ": " message)}))
   :schema  {:input [:map] :output [:map [:error-page :string]]}})
```

---

## Add a Human Approval Gate (Halt/Resume)

**Problem:** A workflow must pause for human review before continuing.

```clojure
;; 1. Cell signals halt
(defmethod cell/cell-spec :review/request [_]
  {:id      :review/request
   :handler (fn [_ data]
              {:mycelium/halt {:reason :needs-approval
                               :item   (:item-id data)}})
   :schema  {:input [:map [:item-id :string]] :output [:map]}})

;; 2. Run workflow — it halts
(def compiled (myc/pre-compile workflow))
(def halted (myc/run-compiled compiled resources {:item-id "X"}))
;; halted contains :mycelium/halt and :mycelium/resume

;; 3. Resume with human input
(def result (myc/resume-compiled compiled resources halted
              {:approved true, :reviewer "alice"}))
;; :approved and :reviewer are now in the data map
```

`:mycelium/halt` can be `true` or a map with context. Data accumulates across halt/resume. Trace is continuous. A workflow can halt and resume multiple times.

### Persisting Halted State Across Sessions

```clojure
(require '[mycelium.store :as store])

(def s (store/memory-store))

;; Run — auto-persists on halt
(def halted (store/run-with-store compiled resources data s))

;; Resume by session ID (e.g., from a database later)
(store/resume-with-store compiled resources
  (:mycelium/session-id halted) s {:approved true})
```

Implement `store/WorkflowStore` protocol for production persistence (DB, Redis, etc.).

---

## Wrap a Workflow as a Reusable Cell

**Problem:** A sub-process should be embedded as a single step in a parent workflow.

```clojure
(require '[mycelium.compose :as compose])

;; Define the sub-workflow
(def credit-check-wf
  {:cells {:start  :credit/lookup
           :score  :credit/calculate
           :classify :credit/classify-risk}
   :edges {:start :score, :score :classify, :classify :end}
   :dispatches {}})

;; Register it as a cell
(compose/register-workflow-cell!
  :credit/assessment
  credit-check-wf
  {:input  [:map [:applicant-name :string]]
   :output [:map [:credit-score :int] [:risk-level [:enum :low :medium :high]]]})

;; Use it like any other cell
(def parent-wf
  {:cells {:start   :app/validate
           :credit  :credit/assessment   ;; <-- composed workflow
           :decide  :app/eligibility}
   :edges {:start :credit
           :credit {:success :decide, :failure :end}
           :decide :end}
   :dispatches {:credit [[:success (fn [d] (nil? (:mycelium/error d)))]
                         [:failure (fn [d] (some? (:mycelium/error d)))]]}})
```

`:success`/`:failure` dispatches are auto-generated based on `:mycelium/error`. Child trace is available in `:mycelium/child-trace`.

---

## Protect Against External Service Failures

**Problem:** An API call might be slow, fail repeatedly, or overload a downstream service.

```clojure
(def workflow
  {:cells {:start    :api/call-external
           :ok       :app/process-result
           :fallback :app/use-cached}

   :edges {:start {:done :ok, :failed :fallback}
           :ok :end, :fallback :end}

   :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                        [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}

   :resilience {:start {:timeout        {:timeout-ms 5000}
                        :retry          {:max-attempts 3 :wait-ms 200}
                        :circuit-breaker {:failure-rate 50
                                          :minimum-calls 10
                                          :sliding-window-size 100
                                          :wait-in-open-ms 60000}}}})
```

When a resilience policy triggers, `:mycelium/resilience-error` is set with `{:type :timeout|:circuit-open|:bulkhead-full|:rate-limited, :cell ..., :message ...}`.

Stateful policies (circuit breaker, rate limiter) require `pre-compile` + `run-compiled` to share state across calls.

---

## Add a Timeout to a Cell

**Problem:** A cell should fall back to an alternative if it takes too long, without touching handler code.

```clojure
(def workflow
  {:cells {:fetch    :data/fetch-remote
           :process  :data/process
           :fallback :data/use-default}

   :edges {:fetch {:done :process, :timeout :fallback}
           :process :end, :fallback :end}

   :dispatches {:fetch [[:done (fn [d] (not (:mycelium/timeout d)))]]}

   :timeouts {:fetch 5000}})  ;; milliseconds
```

Graph-level timeouts **route** to a fallback cell. Resilience timeouts **error**. The `:timeout` dispatch predicate is auto-injected and evaluated first. Output schema validation is skipped for timed-out cells.

---

## Inspect Errors Uniformly

**Problem:** Mycelium has 6 different error keys. You want one check.

```clojure
(let [result (myc/run-workflow wf resources data)]
  (if (myc/error? result)
    (let [{:keys [error-type cell-id cell message details]} (myc/workflow-error result)]
      (case error-type
        :schema/input   (log/warn "Bad input at" cell-id)
        :schema/output  (log/warn "Bad output at" cell-id)
        :handler        (log/error "Handler failed at" cell)
        :join           (log/error "Join failed:" message)
        :timeout        (log/warn "Cell timed out")
        ;; :resilience/timeout, :resilience/circuit-open,
        ;; :resilience/bulkhead-full, :resilience/rate-limited
        (log/error "Resilience error:" error-type cell)))
    (handle-success result)))
```

`workflow-error` returns nil on success, or a map with `:error-type`, `:message`, `:details` (plus `:cell-id`, `:cell`, `:cell-path`, `:failed-keys` where applicable).

---

## Write a Cell

**Problem:** You need to implement a cell with explicit input/output contracts.

```clojure
(require '[mycelium.cell :as cell])

(defmethod cell/cell-spec :order/compute-tax [_]
  {:id      :order/compute-tax
   :handler (fn [resources data]
              ;; With key propagation (default): return only new keys
              {:tax (* (:subtotal data) (:tax-rate data))})
   :schema  {:input  [:map [:subtotal :double] [:tax-rate :double]]
             :output [:map [:tax :double]]}})
```

Key propagation is on by default — handler output is merged with input, so you only return new or changed keys. To return all keys explicitly, disable with `{:propagate-keys? false}`.

### Async Cell

```clojure
(defmethod cell/cell-spec :api/fetch [_]
  {:id      :api/fetch
   :async?  true
   :handler (fn [resources data callback error-callback]
              (http/get (:url data)
                {:on-success (fn [resp] (callback {:response-body (:body resp)}))
                 :on-error   (fn [err] (error-callback err))}))
   :schema  {:input  [:map [:url :string]]
             :output [:map [:response-body :string]]}})
```

### Parameterized Cell

```clojure
;; Register once, use with different params
(defmethod cell/cell-spec :math/multiply [_]
  {:id      :math/multiply
   :handler (fn [_ data]
              (let [factor (get-in data [:mycelium/params :factor])]
                {:result (* (:value data) factor)}))
   :schema  {:input [:map [:value number?]] :output [:map [:result number?]]}})

;; Use in workflow
{:cells {:triple {:id :math/multiply :params {:factor 3}}
         :double {:id :math/multiply :params {:factor 2}}}
 :pipeline [:triple :double]}
```

---

## Test a Cell in Isolation

**Problem:** Verify a cell's behavior without running a full workflow.

```clojure
(require '[mycelium.dev :as dev])

;; Basic test
(dev/test-cell :order/compute-tax
  {:input {:subtotal 100.0 :tax-rate 0.1}})
;; => {:pass? true, :output {:tax 10.0, ...}, :errors [], :duration-ms 0.42}

;; Test with dispatch verification
(dev/test-cell :order/check-fraud
  {:input      {:total 6000}
   :dispatches [[:clean (fn [d] (= :ok (:fraud-status d)))]
                [:suspicious (fn [d] (= :flagged (:fraud-status d)))]]
   :expected-dispatch :suspicious})
;; => {:pass? true, :matched-dispatch :suspicious, ...}

;; Test multiple transitions at once
(dev/test-transitions :order/check-fraud
  {:clean {:input {:total 100}
           :dispatches [[:clean (fn [d] (= :ok (:fraud-status d)))]
                        [:suspicious (fn [d] (= :flagged (:fraud-status d)))]]}
   :suspicious {:input {:total 6000}
                :dispatches [[:clean (fn [d] (= :ok (:fraud-status d)))]
                             [:suspicious (fn [d] (= :flagged (:fraud-status d)))]]}})
```

---

## Debug Workflow Execution

**Problem:** You need to see what happened during a workflow run.

### Live Tracing (REPL)

```clojure
;; Built-in trace logger — prints each cell as it completes
(myc/run-workflow wf resources data {:on-trace (dev/trace-logger)})
;; Prints:
;; 1. :validate (:app/validate) -> :done [0.42ms]
;; 2. :process (:app/transform) -> :done [1.23ms]

;; Custom callback
(myc/run-workflow wf resources data
  {:on-trace (fn [entry]
               (log/info (:cell entry) (:duration-ms entry)))})
```

### Post-Run Trace Inspection

```clojure
(let [result (myc/run-workflow wf resources data)
      trace  (:mycelium/trace result)]
  ;; Pretty-print the full trace
  (println (dev/format-trace trace))

  ;; Assert on execution path
  (is (= [:validate :process :render] (mapv :cell trace)))

  ;; Check specific cell output
  (is (= 42 (get-in (last trace) [:data :result]))))
```

Each trace entry: `{:cell :name, :cell-id :ns/id, :transition :label, :duration-ms N, :data {...}, :error {...}}`.

---

## Handle Type Mismatches Between Cells

**Problem:** One cell produces a `double` but the next expects an `int`.

```clojure
;; Enable coercion at compile time
(def compiled (myc/pre-compile workflow {:coerce? true}))
(myc/run-compiled compiled resources data)

;; Or per-run
(myc/run-workflow workflow resources data {:coerce? true})
```

Only whole-valued doubles (`949.0`) are coerced to int. Fractional values (`949.5`) still fail validation. Uses Malli's `json-transformer`.

---

## Serve a Workflow as a Ring Handler

**Problem:** Expose a workflow as an HTTP endpoint.

```clojure
(require '[mycelium.middleware :as mw])

(def compiled (myc/pre-compile workflow))

;; Basic handler — request goes in, HTML comes out
(def handler (mw/workflow-handler compiled {:resources {:db db}}))

;; Custom input/output transforms
(def handler
  (mw/workflow-handler compiled
    {:resources {:db db}
     :input-fn  (fn [req] {:user-id (get-in req [:params :id])})
     :output-fn (fn [result] {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body (json/encode result)})}))

;; Per-request resources
(def handler
  (mw/workflow-handler compiled
    {:resources (fn [req] {:db db :current-user (:identity req)})}))
```

---

## Validate Workflow Structure at Compile Time

**Problem:** Ensure structural invariants hold across all possible execution paths.

```clojure
(def workflow
  {:cells {:start   :app/validate
           :process :app/process
           :audit   :app/audit-log
           :review  :app/manual-review
           :auto    :app/auto-approve}

   :edges {:start {:ok :process, :invalid :end}
           :process {:review :review, :auto :auto}
           :review :audit, :auto :audit, :audit :end}

   :dispatches {:start   [[:ok (fn [d] (:valid d))]
                          [:invalid (fn [d] (not (:valid d)))]]
                :process [[:review (fn [d] (:needs-review d))]
                          [:auto (fn [d] (not (:needs-review d)))]]}

   :constraints [{:type :must-follow :if :process :then :audit}
                 {:type :never-together :cells [:review :auto]}
                 {:type :always-reachable :cell :audit}]})
```

Violations throw at compile time with the specific path that breaks the constraint.

| Type | Meaning |
|------|---------|
| `:must-follow` | If `:if` cell runs, `:then` must appear later on same path |
| `:must-precede` | `:cell` must appear before `:before` |
| `:never-together` | Listed cells never on same path |
| `:always-reachable` | Cell on every path reaching `:end` |

---

## Share Workflow Logic with Fragments

**Problem:** Multiple workflows share the same sub-graph (e.g., authentication).

```clojure
;; fragments/auth.edn
{:cells {:parse    {:id :auth/parse-request :schema :inherit}
         :validate {:id :auth/validate-session :schema :inherit}}
 :edges {:parse {:ok :validate}
         :validate {:valid   :_exit/success
                    :invalid :_exit/failure}}
 :dispatches {:parse    [[:ok (fn [d] (:parsed d))]]
              :validate [[:valid (fn [d] (:session-valid d))]
                         [:invalid (fn [d] (not (:session-valid d)))]]}}

;; Host workflow references the fragment
{:cells {:dashboard :app/render-dashboard
         :login     :app/login-page}
 :fragments {:auth {:ref "fragments/auth.edn"
                    :as :start
                    :exits {:success :dashboard
                            :failure :login}}}
 :edges {:dashboard :end, :login :end}}
```

`:_exit/name` references are resolved during fragment expansion. Fragment cells are merged into the host workflow.

---

## Analyze a Workflow Before Running It

**Problem:** Check for unreachable cells, missing paths, or cycles.

```clojure
;; Static analysis
(dev/analyze-workflow workflow-def)
;; => {:reachable #{:start :step2 ...}
;;     :unreachable #{}
;;     :no-path-to-end #{}
;;     :cycles []}

;; See accumulated schema at each cell
(dev/infer-workflow-schema workflow-def)
;; => {:start  {:available-before #{:x}, :adds #{:result}, :available-after #{:x :result}}
;;     :step2  {:available-before #{:x :result}, :adds #{:total}, ...}}

;; Enumerate all possible paths
(dev/enumerate-paths workflow-def)
;; => [[:start :process :end] [:start :error-handler :end] ...]

;; Generate DOT graph for visualization
(dev/workflow->dot workflow-def)
```

---

## Pre-Compile for Production

**Problem:** Avoid recompiling the workflow on every request.

```clojure
;; At startup — compile once
(def compiled (myc/pre-compile workflow-def {:coerce? true}))

;; Per request — zero compilation overhead
(defn handle-request [req]
  (let [resources {:db (get-db)}
        data      (extract-input req)]
    (myc/run-compiled compiled resources data)))
```

`pre-compile` does all validation, schema chain checking, and FSM compilation at call time. `run-compiled` only executes.

---

## Use Interceptors for Cross-Cutting Concerns

**Problem:** Add logging, timing, or transformation that applies to multiple cells without modifying handlers.

```clojure
{:interceptors
 [{:id    :request-timing
   :scope :all
   :pre   (fn [data] (assoc data ::t0 (System/nanoTime)))
   :post  (fn [data]
            (let [elapsed (/ (- (System/nanoTime) (::t0 data)) 1e6)]
              (println "Cell took" elapsed "ms")
              (dissoc data ::t0)))}

  {:id    :ui-defaults
   :scope {:id-match "ui/*"}   ;; glob on cell :id
   :pre   (fn [data] (assoc data :theme "dark"))}

  {:id    :audit
   :scope {:cells [:payment :refund]}   ;; specific cell names
   :post  (fn [data]
            (update data :audit-log conj {:action (:last-action data)
                                           :time (System/currentTimeMillis)}))}]}
```

Interceptor `:pre`/`:post` receive and return the data map. Scope forms: `:all`, `{:id-match "pattern/*"}`, `{:cells [:x :y]}`.

---

## Generate LLM Briefs for Agent Orchestration

**Problem:** Give AI agents focused context to implement individual cells.

```clojure
(require '[mycelium.orchestrate :as orch])

;; Brief for a single cell
(myc/cell-brief manifest :validate)
;; Returns a self-contained prompt with schema, edges, and context

;; Briefs for all cells (parallel agent assignment)
(orch/cell-briefs manifest)

;; After a cell implementation fails, generate a targeted reassignment brief
(orch/reassignment-brief manifest :validate
  {:error "Output missing key :session-valid"
   :input {:user-id "alice"}
   :output {:session-valid nil}})

;; Scoped context for a region of the graph
(orch/region-brief manifest :auth)

;; Implementation plan — what can run in parallel
(orch/plan manifest)
```

---

## Common Patterns

### Errors Are Data, Not Exceptions

Expected failure conditions should be keys on the data map, routed via dispatch predicates:

```clojure
;; Cell sets a status key
(fn [resources data]
  (let [result (charge-card resources (:card data) (:total data))]
    {:payment-status (if (:success result) :approved :declined)}))

;; Workflow routes on it
:dispatches {:payment [[:approved (fn [d] (= :approved (:payment-status d)))]
                       [:declined (fn [d] (= :declined (:payment-status d)))]]}
```

Reserve exceptions for truly unexpected failures (IO errors, connection drops). Use error groups for those.

### Manifest-First Design

Write the manifest (cells, edges, schemas) before implementing handlers. The manifest is the architecture — it compiles and validates before any cell code runs. Each cell can then be implemented independently using only its schema as context.

### Cell Output With Key Propagation

With key propagation (default), cells return only new or changed keys:

```clojure
;; Handler returns only what it adds
(fn [_ data] {:tax (* (:subtotal data) 0.1)})
;; Framework merges: (merge input-keys handler-output)
;; Handler output takes precedence on conflicts
```

Disable with `{:propagate-keys? false}` if you need explicit control over which keys flow forward.

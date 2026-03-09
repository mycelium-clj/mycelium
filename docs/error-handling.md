# Error Handling

Mycelium treats errors as data flowing through the workflow graph, not as exceptions that unwind the stack. This guide covers the error taxonomy, how to handle errors at different levels, and how to build diagnostics and observability into your workflows.

## Error Taxonomy

Mycelium has six error categories, each stored as a namespaced key on the data map:

| Key | When Set | Structure |
|-----|----------|-----------|
| `:mycelium/input-error` | Initial data fails `:input-schema` validation | `{:errors ..., :data ...}` |
| `:mycelium/schema-error` | Cell input/output fails Malli schema | `{:cell-id, :cell-name, :phase, :message, :failed-keys, :cell-path, :errors, :data}` |
| `:mycelium/error` | Cell handler throws (caught by error group) | `{:cell :cell-name, :message "..."}` |
| `:mycelium/resilience-error` | Resilience policy triggers | `{:type :timeout\|:circuit-open\|:bulkhead-full\|:rate-limited, :cell, :message}` |
| `:mycelium/join-error` | One or more join members fail | Seq of `{:cell-id, :cell, :message, ...}` |
| `:mycelium/timeout` | Cell exceeds graph-level timeout | `true` |

These are mutually exclusive — a workflow result contains at most one error key.

## Unified Error Inspection

Rather than checking each key individually, use the `workflow-error` and `error?` functions:

```clojure
(require '[mycelium.core :as myc])

(let [result (myc/run-workflow wf resources data)]
  (if (myc/error? result)
    (let [{:keys [error-type message cell-id cell-name cell-path
                  failed-keys details]} (myc/workflow-error result)]
      (log/error message)
      ;; error-type is one of:
      ;;   :input, :schema/input, :schema/output, :handler,
      ;;   :resilience/timeout, :resilience/circuit-open,
      ;;   :resilience/bulkhead-full, :resilience/rate-limited,
      ;;   :join, :timeout
      (case error-type
        :schema/input  (handle-bad-input cell-name failed-keys)
        :schema/output (handle-bad-output cell-name failed-keys)
        :handler       (handle-cell-failure cell-name)
        :join          (handle-join-failure details)
        (handle-generic-error error-type message)))
    (handle-success result)))
```

`workflow-error` returns nil on success, or a map with:
- `:error-type` — keyword identifying the category
- `:message` — human-readable description including the cell name and failing keys
- `:details` — the original error data for programmatic access
- `:cell-id` — cell spec ID (e.g., `:app/validate`)
- `:cell-name` — workflow cell name (e.g., `:validate`)
- `:cell-path` — vector of cells that ran before the failure
- `:failed-keys` — per-key diagnostics (schema errors only)

## Schema Validation Errors

Schema errors are the most detailed. When a cell's input or output fails Malli validation, the error includes:

```clojure
;; Schema error structure (from :mycelium/schema-error)
{:cell-id     :app/process-payment
 :cell-name   :payment
 :phase       :input           ;; or :output
 :message     "Schema input validation failed at payment (:app/process-payment) — failing keys: (:amount :currency)"
 :failed-keys {:amount   {:value "not-a-number"
                           :type  "java.lang.String"
                           :message "should be a double"}
                :currency {:value nil
                           :type  nil
                           :message "missing required key"}}
 :cell-path   [:validate :lookup]   ;; cells that ran before this one
 :errors      {:amount ["should be a double"]
               :currency ["missing required key"]}
 :data        {:user-id "alice"}}   ;; clean data (no :mycelium/* keys)
```

### Diagnosing Schema Failures

The `:failed-keys` map gives you per-key diagnostics — the actual value, its Java type, and what Malli expected:

```clojure
(when-let [{:keys [message failed-keys cell-path]} (myc/workflow-error result)]
  (println message)
  ;; "Schema input validation failed at payment (:app/process-payment) — failing keys: (:amount :currency)"

  (doseq [[k {:keys [value type message]}] failed-keys]
    (println "  " k "=" (pr-str value) "(" type ")" "-" message))
  ;; :amount = "not-a-number" (java.lang.String) - should be a double
  ;; :currency = nil (nil) - missing required key

  (println "Execution path:" cell-path))
  ;; Execution path: [:validate :lookup]
```

### Input Validation

Validate initial workflow data before any cells run:

```clojure
{:cells {:start :app/process}
 :edges {:start :end}
 :input-schema [:map
                [:user-id :string]
                [:amount [:and :double [:> 0]]]]}

;; On invalid input:
(let [result (myc/run-workflow wf {} {:user-id 123})]
  (:mycelium/input-error result))
;; => {:errors {:user-id ["should be a string"], :amount ["missing required key"]}
;;     :data {:user-id 123}}
```

No cells execute when input validation fails.

## Error Groups

Error groups assign a shared error handler to multiple cells. When any grouped cell's handler throws an exception, the exception is caught, `:mycelium/error` is set on data, and execution routes to the error handler.

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

   :error-groups {:pipeline {:cells    [:start :transform :render]
                              :on-error :err}}})
```

At compile time, the framework:
1. Expands unconditional edges to include `:on-error` targets
2. Injects `:on-error` dispatch predicates (checked first)
3. Wraps grouped cell handlers with try/catch

The error handler receives the full data map with `:mycelium/error`:

```clojure
(defmethod cell/cell-spec :data/handle-error [_]
  {:id      :data/handle-error
   :handler (fn [_ data]
              (let [{:keys [cell message]} (:mycelium/error data)]
                {:error-page (str "Failed at " (name cell) ": " message)}))
   :schema  {:input [:map] :output [:map [:error-page :string]]}})
```

### Error Recovery

An error handler can clear the error and attempt recovery:

```clojure
(defmethod cell/cell-spec :order/retry-with-backup [_]
  {:id      :order/retry-with-backup
   :handler (fn [{:keys [backup-gateway]} data]
              (let [{:keys [cell message]} (:mycelium/error data)]
                (log/warn "Primary failed at" cell ":" message)
                (-> (dissoc data :mycelium/error)
                    (assoc :result (call-backup backup-gateway data)))))
   :schema  {:input [:map] :output [:map [:result :string]]}})
```

If the recovery cell itself throws and is in an error group, the error routes to its group's handler.

## Graph-Level Timeouts

Graph-level timeouts route to a fallback cell instead of raising an error:

```clojure
{:cells {:fetch    :data/fetch-remote
         :process  :data/process
         :fallback :data/use-default}

 :edges {:fetch   {:done :process, :timeout :fallback}
         :process :end
         :fallback :end}

 :dispatches {:fetch [[:done (fn [d] (not (:mycelium/timeout d)))]]}

 :timeouts {:fetch 5000}}  ;; milliseconds
```

The `:timeout` dispatch is auto-injected and evaluated first. When triggered, `:mycelium/timeout` is set to `true` on the data map and output schema validation is skipped.

### Cascading Timeouts

Chain timeouts for progressively slower fallbacks:

```clojure
{:edges {:fast   {:done :process, :timeout :slow}
         :slow   {:done :process, :timeout :cached}
         :process :end
         :cached  :end}

 :timeouts {:fast 1000, :slow 10000}}
```

### Timeouts vs Resilience Timeouts

| Feature | Graph-level `:timeouts` | Resilience `:timeout` |
|---------|------------------------|-----------------------|
| Error key | `:mycelium/timeout` | `:mycelium/resilience-error` |
| Routing | Routes via dispatch | Sets error on data |
| Requires | `:timeout` edge | Dispatch on `:mycelium/resilience-error` |
| Use case | Fallback to alternative cell | Protection with error signal |

## Resilience Policies

Wrap cells with circuit breakers, retries, timeouts, bulkheads, and rate limiters:

```clojure
{:cells {:start    :api/call-external
         :ok       :app/process-result
         :fallback :app/use-cached}

 :edges {:start {:done :ok, :failed :fallback}
         :ok :end, :fallback :end}

 :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                      [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}

 :resilience {:start {:timeout         {:timeout-ms 5000}
                      :retry           {:max-attempts 3 :wait-ms 200}
                      :circuit-breaker {:failure-rate 50
                                        :minimum-calls 10
                                        :sliding-window-size 100
                                        :wait-in-open-ms 60000}
                      :bulkhead        {:max-concurrent 25 :max-wait-ms 0}
                      :rate-limiter    {:limit-for-period 50
                                        :limit-refresh-period-ms 500
                                        :timeout-ms 5000}}}}
```

When a policy triggers, the handler doesn't throw — instead `:mycelium/resilience-error` is set:

```clojure
{:type    :circuit-open  ;; or :timeout, :bulkhead-full, :rate-limited
 :cell    :start
 :message "CircuitBreaker 'start-cb' is OPEN..."}
```

Resilience instances are stateful (circuit breaker state, rate limiter counters). Use `pre-compile` + `run-compiled` to share state across invocations:

```clojure
(def compiled (myc/pre-compile workflow))
;; Reuse `compiled` — circuit breaker state persists
(myc/run-compiled compiled resources data-1)
(myc/run-compiled compiled resources data-2)
```

## Join Errors

When a fork-join member throws, `:mycelium/join-error` contains details of which members failed:

```clojure
{:joins {:fees {:cells [:tax :shipping :discount]
                :strategy :parallel}}

 :edges {:start :fees
         :fees  {:done :total, :failure :partial}
         :total :end, :partial :end}}
```

Successfully completed members' outputs are merged into data. The `:failure` edge is auto-dispatched. The recovery cell can inspect partial results:

```clojure
(fn [_ data]
  (let [errors (:mycelium/join-error data)]
    ;; errors is a seq of {:cell-id :order/calc-shipping, :cell :shipping, ...}
    ;; Successful members' outputs are already in data
    {:note (str (count errors) " service(s) failed")
     :total (or (:tax data) 0)}))
```

## Execution Tracing

Every workflow result includes `:mycelium/trace` — a vector of entries showing what ran:

```clojure
{:cell       :validate        ;; workflow cell name
 :cell-id    :app/validate    ;; cell spec ID
 :transition :done            ;; dispatch transition taken
 :duration-ms 1.23            ;; execution time
 :data       {:x 42}          ;; data snapshot (no :mycelium/* keys)
 :error      nil}             ;; schema error if validation failed
```

### Live Tracing

Use `:on-trace` for real-time monitoring:

```clojure
(require '[mycelium.dev :as dev])

;; Built-in logger — prints each cell as it completes
(myc/run-workflow wf resources data {:on-trace (dev/trace-logger)})
;; 1. :validate (:app/validate) -> :done [0.42ms]
;; 2. :process (:app/transform) -> :done [1.23ms]
```

### Custom Trace Callbacks

Build structured logging, metrics, or alerting on top of `:on-trace`:

```clojure
;; Structured logging
(defn structured-logger []
  (fn [{:keys [cell cell-id transition duration-ms error]}]
    (log/info {:event     :cell-completed
               :cell      cell
               :cell-id   cell-id
               :transition transition
               :duration-ms duration-ms
               :error?    (some? error)})))

(myc/run-workflow wf resources data {:on-trace (structured-logger)})
```

```clojure
;; Metrics collection
(defn metrics-collector [registry]
  (fn [{:keys [cell duration-ms error]}]
    (metrics/record-timer registry (str "cell." (name cell) ".duration") duration-ms)
    (when error
      (metrics/increment registry (str "cell." (name cell) ".errors")))))
```

```clojure
;; Slow cell alerting
(defn slow-cell-alerter [threshold-ms]
  (fn [{:keys [cell duration-ms]}]
    (when (and duration-ms (> duration-ms threshold-ms))
      (log/warn "Slow cell" cell "took" duration-ms "ms"))))
```

### Post-Run Trace Analysis

```clojure
(let [result (myc/run-workflow wf resources data)
      trace  (:mycelium/trace result)]
  ;; Pretty-print
  (println (dev/format-trace trace))

  ;; Assert execution path
  (is (= [:validate :process :render] (mapv :cell trace)))

  ;; Find the slowest cell
  (let [slowest (apply max-key :duration-ms trace)]
    (println "Slowest:" (:cell slowest) (:duration-ms slowest) "ms"))

  ;; Check for errors in trace
  (let [errored (filter :error trace)]
    (doseq [e errored]
      (println "Error at" (:cell e) "-" (get-in e [:error :message])))))
```

## Error Handling Patterns

### Errors Are Data, Not Exceptions

Expected failures should be data keys routed via dispatch — not thrown exceptions:

```clojure
;; GOOD: cell sets a status key, dispatch routes on it
(fn [resources data]
  (let [result (charge-card resources (:card data) (:total data))]
    {:payment-status (if (:success result) :approved :declined)}))

:dispatches {:payment [[:approved (fn [d] (= :approved (:payment-status d)))]
                       [:declined (fn [d] (= :declined (:payment-status d)))]]}
```

Reserve error groups for truly unexpected failures (IO errors, null pointers, connection drops).

### Errors as Routing Decisions

Use per-transition output schemas to validate both success and failure paths:

```clojure
:schema {:input  [:map [:card :string] [:total :double]]
         :output {:approved [:map [:payment-status [:= :approved]]
                              [:transaction-id :string]]
                  :declined [:map [:payment-status [:= :declined]]
                              [:decline-reason :string]]}}
```

Each branch gets its own schema validation — a declined payment must include `:decline-reason`.

### Layered Error Strategy

Combine multiple error mechanisms for defense in depth:

```clojure
{:cells {:start     :api/call
         :process   :app/process
         :fallback  :app/use-cached
         :err       :app/handle-error}

 ;; Layer 1: Graph-level timeout — route to fallback
 :timeouts {:start 5000}

 ;; Layer 2: Resilience — retry transient failures, circuit-break persistent ones
 :resilience {:start {:retry          {:max-attempts 3 :wait-ms 200}
                      :circuit-breaker {:failure-rate 50
                                        :minimum-calls 10}}}

 ;; Layer 3: Error group — catch anything that gets through
 :error-groups {:all {:cells [:start :process] :on-error :err}}

 :edges {:start    {:done :process, :timeout :fallback}
         :process  :end
         :fallback :end
         :err      :end}

 :dispatches {:start [[:done (fn [d] (not (:mycelium/timeout d)))]]}}
```

Evaluation order: timeout check → resilience wrapper → error group catch.

### Request Context Propagation

Pass correlation IDs or request context through the workflow using data keys:

```clojure
;; Set context at workflow entry
(myc/run-compiled compiled resources
  {:request-id (str (random-uuid))
   :user-id    (:id current-user)
   :order-data order})

;; Every cell receives :request-id and :user-id via key propagation
;; Include in error handling:
(defmethod cell/cell-spec :app/handle-error [_]
  {:id      :app/handle-error
   :handler (fn [_ data]
              (log/error {:request-id (:request-id data)
                          :user-id    (:user-id data)
                          :error      (:mycelium/error data)})
              {:error-page "Something went wrong"})
   :schema  {:input [:map] :output [:map [:error-page :string]]}})
```

### Structured Error Responses for APIs

Build HTTP error responses from workflow errors:

```clojure
(defn workflow-handler [compiled resources]
  (fn [req]
    (let [result (myc/run-compiled compiled resources (extract-input req))]
      (if-let [{:keys [error-type message cell-name failed-keys]}
               (myc/workflow-error result)]
        (case error-type
          :input
          {:status 400
           :body   {:error "Invalid input" :details message}}

          (:schema/input :schema/output)
          {:status 500
           :body   {:error "Internal validation error"
                    :cell  cell-name}}

          (:resilience/timeout :resilience/circuit-open)
          {:status 503
           :body   {:error "Service temporarily unavailable"
                    :retry-after 30}}

          :timeout
          {:status 504
           :body   {:error "Request timed out"}}

          ;; Default
          {:status 500
           :body   {:error "Internal error"}})
        {:status 200
         :body   (select-keys result [:order-id :status])}))))
```

## Static Analysis

Catch structural problems before any code runs:

```clojure
(require '[mycelium.dev :as dev])

(dev/analyze-workflow workflow-def)
;; => {:reachable      #{:start :process :render}
;;     :unreachable    #{}
;;     :no-path-to-end #{}
;;     :cycles         []}

;; Schema chain — see what keys are available at each cell
(dev/infer-workflow-schema workflow-def)
;; => {:start   {:available-before #{:x}, :adds #{:result}, :available-after #{:x :result}}
;;     :process {:available-before #{:x :result}, :adds #{:total}, ...}}

;; All possible paths through the graph
(dev/enumerate-paths workflow-def)
;; => [[:start :process :end] [:start :error-handler :end]]
```

### Compile-Time Constraints

Enforce structural invariants across all execution paths:

```clojure
{:constraints [{:type :must-follow      :if :process   :then :audit}
               {:type :never-together   :cells [:review :auto]}
               {:type :always-reachable :cell :audit}]}
```

Violations throw at compile time with the specific path that breaks the constraint.

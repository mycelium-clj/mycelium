(ns mycelium.resilience
  "Resilience policies for Mycelium cells.
   Wraps cell handlers with resilience4j circuit breakers, retries,
   timeouts, bulkheads, and rate limiters."
  (:require [clojure.set :as set])
  (:import [io.github.resilience4j.circuitbreaker CircuitBreaker CircuitBreakerConfig]
           [io.github.resilience4j.retry Retry RetryConfig]
           [io.github.resilience4j.timelimiter TimeLimiter TimeLimiterConfig]
           [io.github.resilience4j.bulkhead Bulkhead BulkheadConfig]
           [io.github.resilience4j.ratelimiter RateLimiter RateLimiterConfig]
           [io.github.resilience4j.circuitbreaker CallNotPermittedException]
           [io.github.resilience4j.bulkhead BulkheadFullException]
           [io.github.resilience4j.ratelimiter RequestNotPermitted]
           [java.time Duration]
           [java.util.function Supplier]
           [java.util.concurrent TimeoutException CompletableFuture]))

;; ===== Config builders =====

(defn- build-timeout
  "Builds a TimeLimiter from config map.
   Config keys: :timeout-ms (required)."
  [cell-name {:keys [timeout-ms]}]
  (let [config (-> (TimeLimiterConfig/custom)
                   (.timeoutDuration (Duration/ofMillis timeout-ms))
                   (.cancelRunningFuture true)
                   (.build))]
    (TimeLimiter/of (str (name cell-name) "-timeout") config)))

(defn- build-retry
  "Builds a Retry from config map.
   Config keys: :max-attempts (default 3), :wait-ms (default 500)."
  [cell-name {:keys [max-attempts wait-ms]}]
  (let [config (-> (RetryConfig/custom)
                   (.maxAttempts (or max-attempts 3))
                   (.waitDuration (Duration/ofMillis (or wait-ms 500)))
                   (.build))]
    (Retry/of (str (name cell-name) "-retry") config)))

(defn- build-circuit-breaker
  "Builds a CircuitBreaker from config map.
   Config keys: :failure-rate (default 50), :wait-in-open-ms (default 60000),
   :sliding-window-size (default 100), :minimum-calls (default 10)."
  [cell-name {:keys [failure-rate wait-in-open-ms sliding-window-size minimum-calls]}]
  (let [config (-> (CircuitBreakerConfig/custom)
                   (.failureRateThreshold (float (or failure-rate 50)))
                   (.waitDurationInOpenState (Duration/ofMillis (or wait-in-open-ms 60000)))
                   (.slidingWindowSize (or sliding-window-size 100))
                   (.minimumNumberOfCalls (or minimum-calls 10))
                   (.build))]
    (CircuitBreaker/of (str (name cell-name) "-cb") config)))

(defn- build-bulkhead
  "Builds a Bulkhead from config map.
   Config keys: :max-concurrent (default 25), :max-wait-ms (default 0)."
  [cell-name {:keys [max-concurrent max-wait-ms]}]
  (let [config (-> (BulkheadConfig/custom)
                   (.maxConcurrentCalls (or max-concurrent 25))
                   (.maxWaitDuration (Duration/ofMillis (or max-wait-ms 0)))
                   (.build))]
    (Bulkhead/of (str (name cell-name) "-bh") config)))

(defn- build-rate-limiter
  "Builds a RateLimiter from config map.
   Config keys: :limit-for-period (default 50), :limit-refresh-period-ms (default 500),
   :timeout-ms (default 5000)."
  [cell-name {:keys [limit-for-period limit-refresh-period-ms timeout-ms]}]
  (let [config (-> (RateLimiterConfig/custom)
                   (.limitForPeriod (or limit-for-period 50))
                   (.limitRefreshPeriod (Duration/ofMillis (or limit-refresh-period-ms 500)))
                   (.timeoutDuration (Duration/ofMillis (or timeout-ms 5000)))
                   (.build))]
    (RateLimiter/of (str (name cell-name) "-rl") config)))

;; ===== Error classification =====

(defn- classify-error
  "Classifies a resilience4j exception into a :mycelium/resilience-error map."
  [cell-name ^Throwable e]
  (let [cause (if (instance? java.util.concurrent.ExecutionException e)
                (.getCause e)
                e)]
    (cond
      (instance? TimeoutException cause)
      {:type :timeout :cell cell-name :message (.getMessage cause)}

      (instance? CallNotPermittedException cause)
      {:type :circuit-open :cell cell-name :message (.getMessage cause)}

      (instance? BulkheadFullException cause)
      {:type :bulkhead-full :cell cell-name :message (.getMessage cause)}

      (instance? RequestNotPermitted cause)
      {:type :rate-limited :cell cell-name :message (.getMessage cause)}

      :else
      {:type :unknown :cell cell-name :message (.getMessage cause)
       :exception-type (str (type cause))})))

;; ===== Handler wrapping =====

(defn- wrap-with-decorators
  "Wraps a Supplier with non-timeout resilience decorators using static decorator methods.
   Each decorator is a pure function: (instance, Supplier) → Supplier.
   Applied innermost-first: circuit-breaker → bulkhead → rate-limiter → retry (outermost)."
  ^Supplier [^Supplier supplier {:keys [circuit-breaker retry bulkhead rate-limiter]}]
  (let [s (if circuit-breaker (CircuitBreaker/decorateSupplier circuit-breaker supplier) supplier)
        s (if bulkhead        (Bulkhead/decorateSupplier bulkhead s) s)
        s (if rate-limiter    (RateLimiter/decorateSupplier rate-limiter s) s)
        s (if retry           (Retry/decorateSupplier retry s) s)]
    s))

(def ^:private default-async-timeout-ms
  "Default timeout in ms for blocking on async handler promises."
  30000)

(defn- invoke-handler-sync
  "Invokes a handler synchronously. For async (4-arity) handlers, blocks on a promise.
   For sync (2-arity) handlers, calls directly.
   `async-timeout-ms` controls how long to wait for the async promise (default 30s)."
  [handler async? resources data async-timeout-ms]
  (if async?
    (let [p (promise)]
      (handler resources data
              (fn [result] (deliver p {:ok result}))
              (fn [error]  (deliver p {:error error})))
      (let [v (deref p (or async-timeout-ms default-async-timeout-ms)
                      {:error (ex-info "Async cell timed out in resilience wrapper" {})})]
        (if (:error v)
          (throw (if (instance? Throwable (:error v))
                   (:error v)
                   (ex-info (str (:error v)) {})))
          (:ok v))))
    (handler resources data)))

(defn wrap-handler
  "Wraps a cell handler with resilience policies.
   `cell-name` — the workflow cell name (for error reporting).
   `policies` — map of policy configs, e.g. {:timeout {:timeout-ms 5000}, :retry {:max-attempts 3}}.
   `opts` — optional map with :async? flag for async handlers.
   Returns a wrapped handler that catches resilience failures
   and returns data with :mycelium/resilience-error.
   Supports both sync (2-arity) and async (4-arity) handlers."
  ([handler cell-name policies]
   (wrap-handler handler cell-name policies {}))
  ([handler cell-name policies opts]
  (let [timeout-cfg     (:timeout policies)
        retry-cfg       (:retry policies)
        cb-cfg          (:circuit-breaker policies)
        bulkhead-cfg    (:bulkhead policies)
        rate-limiter-cfg (:rate-limiter policies)
        ;; Build resilience4j instances (stateful — built once at compile time)
        tl    (when timeout-cfg (build-timeout cell-name timeout-cfg))
        r     (when retry-cfg (build-retry cell-name retry-cfg))
        cb    (when cb-cfg (build-circuit-breaker cell-name cb-cfg))
        bh    (when bulkhead-cfg (build-bulkhead cell-name bulkhead-cfg))
        rl    (when rate-limiter-cfg (build-rate-limiter cell-name rate-limiter-cfg))
        has-decorators? (or r cb bh rl)
        async? (:async? opts)
        async-timeout-ms (:async-timeout-ms policies)
        invoke (fn [resources data]
                 (try
                   (if tl
                     ;; Timeout requires CompletableFuture path
                     (if has-decorators?
                       (let [supplier (reify Supplier
                                        (get [_] (invoke-handler-sync handler async? resources data async-timeout-ms)))
                             decorated (wrap-with-decorators supplier
                                         {:circuit-breaker cb :retry r
                                          :bulkhead bh :rate-limiter rl})
                             future-supplier (reify Supplier
                                               (get [_]
                                                 (CompletableFuture/supplyAsync decorated)))]
                         (.executeFutureSupplier tl future-supplier))
                       ;; Timeout only
                       (let [future-supplier (reify Supplier
                                               (get [_]
                                                 (CompletableFuture/supplyAsync
                                                   (reify Supplier
                                                     (get [_] (invoke-handler-sync handler async? resources data async-timeout-ms))))))]
                         (.executeFutureSupplier tl future-supplier)))
                     ;; No timeout — use synchronous decorator path
                     (if has-decorators?
                       (let [supplier (reify Supplier
                                        (get [_] (invoke-handler-sync handler async? resources data async-timeout-ms)))
                             decorated (wrap-with-decorators supplier
                                         {:circuit-breaker cb :retry r
                                          :bulkhead bh :rate-limiter rl})]
                         (.get decorated))
                       ;; No policies at all — shouldn't happen, but return normally
                       (invoke-handler-sync handler async? resources data async-timeout-ms)))
                   (catch Exception e
                     (assoc data :mycelium/resilience-error
                            (classify-error cell-name e)))))]
    (fn
      ([resources data]
       (invoke resources data))
      ([resources data callback _error-callback]
       (future (callback (invoke resources data)))
       nil)))))

;; ===== Validation =====

(def ^:private valid-policy-keys
  #{:timeout :retry :circuit-breaker :bulkhead :rate-limiter :async-timeout-ms})

(defn validate-resilience!
  "Validates the :resilience map in a workflow definition.
   Each key must be a cell name in the cells map, each value a map of policy configs."
  [resilience-map cells]
  (let [cell-names (set (keys cells))]
    (doseq [[cell-name policies] resilience-map]
      (when-not (contains? cell-names cell-name)
        (throw (ex-info (str "Resilience policy references cell " cell-name
                             " which is not in :cells")
                        {:cell-name cell-name :valid-cells cell-names})))
      (when-not (map? policies)
        (throw (ex-info (str "Resilience policies for " cell-name " must be a map")
                        {:cell-name cell-name :policies policies})))
      (let [unknown (set/difference (set (keys policies)) valid-policy-keys)]
        (when (seq unknown)
          (throw (ex-info (str "Unknown resilience policy keys for " cell-name ": " unknown)
                          {:cell-name cell-name :unknown-keys unknown
                           :valid-keys valid-policy-keys}))))
      (when-let [timeout (:timeout policies)]
        (when-not (and (:timeout-ms timeout) (pos? (:timeout-ms timeout)))
          (throw (ex-info (str "Resilience :timeout for " cell-name
                               " requires positive :timeout-ms")
                          {:cell-name cell-name :timeout timeout}))))
      (when-let [async-timeout (:async-timeout-ms policies)]
        (when-not (and (integer? async-timeout) (pos? async-timeout))
          (throw (ex-info (str "Resilience :async-timeout-ms for " cell-name
                               " must be a positive integer")
                          {:cell-name cell-name :async-timeout-ms async-timeout})))))))

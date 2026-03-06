(ns mycelium.resilience-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Round 1: Timeout =====

(deftest timeout-triggers-on-slow-cell-test
  (testing "Slow handler exceeding timeout produces :mycelium/resilience-error"
    (defmethod cell/cell-spec :res/slow [_]
      {:id      :res/slow
       :handler (fn [_ data]
                  (Thread/sleep 200)
                  (assoc data :result "done"))
       :schema  {:input [:map [:x :int]]
                 :output {:done    [:map [:result :string]]
                          :timeout [:map [:mycelium/resilience-error :map]]}}})
    (defmethod cell/cell-spec :res/handle-timeout [_]
      {:id      :res/handle-timeout
       :handler (fn [_ data] (assoc data :handled true))
       :schema  {:input [:map] :output [:map [:handled :boolean]]}})

    (let [result (myc/run-workflow
                  {:cells {:start   :res/slow
                           :timeout :res/handle-timeout}
                   :edges {:start   {:done :end, :timeout :timeout}
                           :timeout :end}
                   :dispatches {:start [[:timeout (fn [d] (some? (:mycelium/resilience-error d)))]
                                        [:done    (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                   :resilience {:start {:timeout {:timeout-ms 50}}}}
                  {}
                  {:x 1})]
      (is (some? (:mycelium/resilience-error result)))
      (is (= :timeout (get-in result [:mycelium/resilience-error :type]))))))

(deftest timeout-does-not-trigger-on-fast-cell-test
  (testing "Fast handler within timeout completes normally"
    (defmethod cell/cell-spec :res/fast [_]
      {:id      :res/fast
       :handler (fn [_ data] (assoc data :result "fast"))
       :schema  {:input  [:map [:x :int]]
                 :output {:done    [:map [:result :string]]
                          :timeout [:map [:mycelium/resilience-error :map]]}}})

    (let [result (myc/run-workflow
                  {:cells {:start :res/fast}
                   :edges {:start {:done :end, :timeout :error}}
                   :dispatches {:start [[:timeout (fn [d] (some? (:mycelium/resilience-error d)))]
                                        [:done    (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                   :resilience {:start {:timeout {:timeout-ms 5000}}}}
                  {}
                  {:x 1})]
      (is (= "fast" (:result result)))
      (is (nil? (:mycelium/resilience-error result))))))

(deftest timeout-preserves-upstream-data-test
  (testing "On timeout, upstream data keys are preserved"
    (defmethod cell/cell-spec :res/slow-preserve [_]
      {:id      :res/slow-preserve
       :handler (fn [_ data]
                  (Thread/sleep 200)
                  (assoc data :result "done"))
       :schema  {:input  [:map [:x :int]]
                 :output {:done    [:map [:result :string]]
                          :timeout [:map [:mycelium/resilience-error :map]]}}})
    (defmethod cell/cell-spec :res/handle-timeout2 [_]
      {:id      :res/handle-timeout2
       :handler (fn [_ data] (assoc data :handled true))
       :schema  {:input [:map] :output [:map [:handled :boolean]]}})

    (let [result (myc/run-workflow
                  {:cells {:start   :res/slow-preserve
                           :timeout :res/handle-timeout2}
                   :edges {:start   {:done :end, :timeout :timeout}
                           :timeout :end}
                   :dispatches {:start [[:timeout (fn [d] (some? (:mycelium/resilience-error d)))]
                                        [:done    (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                   :resilience {:start {:timeout {:timeout-ms 50}}}}
                  {}
                  {:x 42})]
      (is (= 42 (:x result))))))

;; ===== Round 2: Retry =====

(deftest retry-recovers-from-transient-failure-test
  (testing "Retry recovers after transient failures"
    (let [call-count (atom 0)]
      (defmethod cell/cell-spec :res/flaky [_]
        {:id      :res/flaky
         :handler (fn [_ data]
                    (swap! call-count inc)
                    (if (< @call-count 3)
                      (throw (ex-info "transient failure" {}))
                      (assoc data :result "recovered")))
         :schema  {:input [:map [:x :int]]
                   :output [:map [:result :string]]}})

      (let [result (myc/run-workflow
                    {:cells {:start :res/flaky}
                     :edges {:start :end}
                     :resilience {:start {:retry {:max-attempts 5 :wait-ms 10}}}}
                    {}
                    {:x 1})]
        (is (= "recovered" (:result result)))
        (is (= 3 @call-count))))))

(deftest retry-exhausted-test
  (testing "When retries exhausted, resilience error is produced"
    (defmethod cell/cell-spec :res/always-fail [_]
      {:id      :res/always-fail
       :handler (fn [_ _] (throw (ex-info "permanent failure" {})))
       :schema  {:input [:map [:x :int]]
                 :output [:map]}})
    (defmethod cell/cell-spec :res/handle-fail [_]
      {:id      :res/handle-fail
       :handler (fn [_ data] (assoc data :handled true))
       :schema  {:input [:map] :output [:map [:handled :boolean]]}})

    (let [result (myc/run-workflow
                  {:cells {:start   :res/always-fail
                           :fallback :res/handle-fail}
                   :edges {:start   {:done :end, :failed :fallback}
                           :fallback :end}
                   :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                                        [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                   :resilience {:start {:retry {:max-attempts 2 :wait-ms 10}}}}
                  {}
                  {:x 1})]
      (is (true? (:handled result)))
      (is (some? (:mycelium/resilience-error result))))))

;; ===== Round 3: Circuit breaker =====

(deftest circuit-breaker-opens-after-failures-test
  (testing "Circuit breaker opens after threshold failures"
    (let [call-count (atom 0)]
      (defmethod cell/cell-spec :res/cb-fail [_]
        {:id      :res/cb-fail
         :handler (fn [_ data]
                    (swap! call-count inc)
                    (throw (ex-info "fail" {})))
         :schema  {:input [:map [:x :int]]
                   :output [:map]}})
      (defmethod cell/cell-spec :res/cb-fallback [_]
        {:id      :res/cb-fallback
         :handler (fn [_ data] (assoc data :fallback true))
         :schema  {:input [:map] :output [:map [:fallback :boolean]]}})

      ;; Pre-compile once so the CB instance is shared across runs
      (let [compiled (myc/pre-compile
                      {:cells {:start    :res/cb-fail
                               :fallback :res/cb-fallback}
                       :edges {:start   {:done :end, :failed :fallback}
                               :fallback :end}
                       :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                                            [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                       :resilience {:start {:circuit-breaker {:failure-rate 50
                                                             :minimum-calls 5
                                                             :sliding-window-size 10
                                                             :wait-in-open-ms 60000}}}})]
        ;; First 5+ runs: handler throws, cb records failures
        (dotimes [_ 6]
          (myc/run-compiled compiled {} {:x 1}))
        (let [calls-before @call-count]
          ;; Now circuit should be OPEN — handler should NOT be called
          (let [result (myc/run-compiled compiled {} {:x 1})]
            (is (some? (:mycelium/resilience-error result)))
            (is (= :circuit-open (get-in result [:mycelium/resilience-error :type])))
            ;; Handler was not invoked — call count unchanged
            (is (= calls-before @call-count))))))))

;; ===== Round 4: Bulkhead =====

(deftest bulkhead-limits-concurrency-test
  (testing "Bulkhead rejects calls exceeding max concurrency"
    (defmethod cell/cell-spec :res/bh-slow [_]
      {:id      :res/bh-slow
       :handler (fn [_ data]
                  (Thread/sleep 200)
                  (assoc data :result "ok"))
       :schema  {:input [:map [:x :int]]
                 :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :res/bh-fallback [_]
      {:id      :res/bh-fallback
       :handler (fn [_ data] (assoc data :rejected true))
       :schema  {:input [:map] :output [:map [:rejected :boolean]]}})

    (let [compiled (myc/pre-compile
                    {:cells {:start    :res/bh-slow
                             :fallback :res/bh-fallback}
                     :edges {:start    {:done :end, :rejected :fallback}
                             :fallback :end}
                     :dispatches {:start [[:rejected (fn [d] (some? (:mycelium/resilience-error d)))]
                                          [:done     (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                     :resilience {:start {:bulkhead {:max-concurrent 1 :max-wait-ms 0}}}})
          ;; Launch 3 concurrent calls — only 1 should proceed
          results (let [futures (doall (repeatedly 3 #(future (myc/run-compiled compiled {} {:x 1}))))]
                    (mapv deref futures))
          rejected (filter #(some? (:mycelium/resilience-error %)) results)
          succeeded (filter #(= "ok" (:result %)) results)]
      (is (>= (count rejected) 1) "At least one call should be rejected")
      (is (>= (count succeeded) 1) "At least one call should succeed"))))

;; ===== Round 5: Composition & interaction =====

(deftest composed-timeout-and-retry-test
  (testing "Retry recovers within timeout window"
    (let [call-count (atom 0)]
      (defmethod cell/cell-spec :res/retry-within-timeout [_]
        {:id      :res/retry-within-timeout
         :handler (fn [_ data]
                    (swap! call-count inc)
                    (if (< @call-count 3)
                      (throw (ex-info "transient" {}))
                      (assoc data :result "ok")))
         :schema  {:input [:map [:x :int]]
                   :output [:map [:result :string]]}})

      (let [result (myc/run-workflow
                    {:cells {:start :res/retry-within-timeout}
                     :edges {:start :end}
                     :resilience {:start {:timeout {:timeout-ms 5000}
                                          :retry   {:max-attempts 5 :wait-ms 10}}}}
                    {}
                    {:x 1})]
        (is (= "ok" (:result result)))
        (is (= 3 @call-count))))))

(deftest parameterized-cell-with-resilience-test
  (testing "Parameterized cells work with resilience policies"
    (let [call-count (atom 0)]
      (defmethod cell/cell-spec :res/param-retry [_]
        {:id      :res/param-retry
         :handler (fn [_ data]
                    (swap! call-count inc)
                    (let [mult (get-in data [:mycelium/params :multiplier])]
                      (if (< @call-count 2)
                        (throw (ex-info "transient" {}))
                        (assoc data :result (* (:x data) mult)))))
         :schema  {:input [:map [:x :int]]
                   :output [:map [:result :int]]}})

      (let [result (myc/run-workflow
                    {:cells {:start {:id :res/param-retry
                                     :params {:multiplier 7}}}
                     :edges {:start :end}
                     :resilience {:start {:retry {:max-attempts 3 :wait-ms 10}}}}
                    {}
                    {:x 6})]
        (is (= 42 (:result result)))))))

(deftest resilience-trace-records-error-test
  (testing "Trace entry records resilience timeout"
    (defmethod cell/cell-spec :res/trace-slow [_]
      {:id      :res/trace-slow
       :handler (fn [_ data]
                  (Thread/sleep 200)
                  (assoc data :result "done"))
       :schema  {:input [:map [:x :int]]
                 :output [:map]}})
    (defmethod cell/cell-spec :res/trace-fallback [_]
      {:id      :res/trace-fallback
       :handler (fn [_ data] (assoc data :handled true))
       :schema  {:input [:map] :output [:map [:handled :boolean]]}})

    (let [result (myc/run-workflow
                  {:cells {:start    :res/trace-slow
                           :fallback :res/trace-fallback}
                   :edges {:start    {:done :end, :timeout :fallback}
                           :fallback :end}
                   :dispatches {:start [[:timeout (fn [d] (some? (:mycelium/resilience-error d)))]
                                        [:done    (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                   :resilience {:start {:timeout {:timeout-ms 50}}}}
                  {}
                  {:x 1})
          trace (:mycelium/trace result)
          start-entry (first trace)]
      (is (= :timeout (:transition start-entry))))))

;; ===== Round 6: Async cells =====

(deftest async-cell-with-retry-test
  (testing "Async cell handler works with retry resilience policy"
    (let [call-count (atom 0)]
      (defmethod cell/cell-spec :res/async-flaky [_]
        {:id      :res/async-flaky
         :async?  true
         :handler (fn [_ data callback error-callback]
                    (swap! call-count inc)
                    (if (< @call-count 3)
                      (error-callback (ex-info "transient async failure" {}))
                      (callback (assoc data :result "async-recovered"))))
         :schema  {:input [:map [:x :int]]
                   :output [:map [:result :string]]}})

      (let [result (myc/run-workflow
                    {:cells {:start :res/async-flaky}
                     :edges {:start :end}
                     :resilience {:start {:retry {:max-attempts 5 :wait-ms 10}}}}
                    {}
                    {:x 1})]
        (is (= "async-recovered" (:result result)))
        (is (= 3 @call-count))))))

(deftest async-cell-with-timeout-test
  (testing "Async cell that takes too long gets timed out"
    (defmethod cell/cell-spec :res/async-slow [_]
      {:id      :res/async-slow
       :async?  true
       :handler (fn [_ data callback _error-callback]
                  (future
                    (Thread/sleep 500)
                    (callback (assoc data :result "done"))))
       :schema  {:input [:map [:x :int]]
                 :output {:done    [:map [:result :string]]
                          :timeout [:map [:mycelium/resilience-error :map]]}}})
    (defmethod cell/cell-spec :res/async-timeout-handler [_]
      {:id      :res/async-timeout-handler
       :handler (fn [_ data] (assoc data :handled true))
       :schema  {:input [:map] :output [:map [:handled :boolean]]}})

    (let [result (myc/run-workflow
                  {:cells {:start   :res/async-slow
                           :timeout :res/async-timeout-handler}
                   :edges {:start   {:done :end, :timeout :timeout}
                           :timeout :end}
                   :dispatches {:start [[:timeout (fn [d] (some? (:mycelium/resilience-error d)))]
                                        [:done    (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                   :resilience {:start {:timeout {:timeout-ms 50}}}}
                  {}
                  {:x 1})]
      (is (some? (:mycelium/resilience-error result)))
      (is (= :timeout (get-in result [:mycelium/resilience-error :type]))))))

(deftest async-timeout-ms-configurable-test
  (testing "Custom :async-timeout-ms controls how long resilience waits for async handler"
    (defmethod cell/cell-spec :res/async-hang [_]
      {:id      :res/async-hang
       :async?  true
       :handler (fn [_ data callback _error-callback]
                  ;; Never calls back within 200ms
                  (future
                    (Thread/sleep 500)
                    (callback (assoc data :result "late"))))
       :schema  {:input [:map [:x :int]]
                 :output {:done [:map [:result :string]]
                          :failed [:map [:mycelium/resilience-error :map]]}}})
    (defmethod cell/cell-spec :res/async-hang-fallback [_]
      {:id      :res/async-hang-fallback
       :handler (fn [_ data] (assoc data :handled true))
       :schema  {:input [:map] :output [:map [:handled :boolean]]}})

    (let [result (myc/run-workflow
                  {:cells {:start    :res/async-hang
                           :fallback :res/async-hang-fallback}
                   :edges {:start    {:done :end, :failed :fallback}
                           :fallback :end}
                   :dispatches {:start [[:failed (fn [d] (some? (:mycelium/resilience-error d)))]
                                        [:done   (fn [d] (nil? (:mycelium/resilience-error d)))]]}
                   ;; No resilience4j timeout — only retry. The async-timeout-ms
                   ;; controls the inner promise deref timeout.
                   :resilience {:start {:async-timeout-ms 100
                                        :retry {:max-attempts 1 :wait-ms 10}}}}
                  {}
                  {:x 1})]
      (is (some? (:mycelium/resilience-error result)))
      (is (true? (:handled result))))))

;; ===== Validation =====

(deftest resilience-invalid-cell-throws-test
  (testing "Resilience policy referencing non-existent cell throws"
    (defmethod cell/cell-spec :res/valid [_]
      {:id :res/valid :handler (fn [_ d] d) :schema {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"not in :cells"
          (wf/compile-workflow
           {:cells {:start :res/valid}
            :edges {:start :end}
            :resilience {:nonexistent {:timeout {:timeout-ms 100}}}})))))

(deftest resilience-unknown-policy-key-throws-test
  (testing "Unknown resilience policy key throws with helpful message"
    (defmethod cell/cell-spec :res/valid3 [_]
      {:id :res/valid3 :handler (fn [_ d] d) :schema {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"Unknown resilience policy keys"
          (wf/compile-workflow
           {:cells {:start :res/valid3}
            :edges {:start :end}
            :resilience {:start {:bogus {:foo 1}}}})))))

(deftest resilience-invalid-timeout-ms-throws-test
  (testing "Negative timeout-ms throws"
    (defmethod cell/cell-spec :res/valid2 [_]
      {:id :res/valid2 :handler (fn [_ d] d) :schema {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"timeout-ms"
          (wf/compile-workflow
           {:cells {:start :res/valid2}
            :edges {:start :end}
            :resilience {:start {:timeout {:timeout-ms -1}}}})))))

(ns mycelium.timeout-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(defn- make-cell
  ([id] (make-cell id (fn [_ data] (assoc data id true))))
  ([id handler]
   (defmethod cell/cell-spec id [_]
     {:id id
      :handler handler
      :schema {:input [:map] :output [:map]}})))

(defn- timed-out?
  "Checks if any trace entry has :timeout? true."
  [result]
  (some :timeout? (:mycelium/trace result)))

;; ===== Round 1: Basic timeout routing =====

(deftest basic-timeout-routing-test
  (testing "Cell that exceeds timeout routes to :timeout edge target"
    (make-cell :c/slow (fn [_ data]
                         (Thread/sleep 200)
                         (assoc data :result "done")))
    (make-cell :c/fallback)
    (make-cell :c/unreachable)

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow
                            :ok    :c/unreachable
                            :fallback :c/fallback}
                    :edges {:start {:done :ok, :timeout :fallback}
                            :ok :end
                            :fallback :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (timed-out? result)
          "Timeout recorded in trace")
      (is (true? (:c/fallback result))
          "Fallback cell ran")
      (is (nil? (:c/unreachable result))
          "Normal path cell did not run"))))

;; ===== Round 2: No timeout — handler completes in time =====

(deftest no-timeout-when-fast-test
  (testing "Cell that completes before timeout routes normally"
    (make-cell :c/fast (fn [_ data]
                         (assoc data :result "fast")))
    (make-cell :c/next)

    (let [result (myc/run-workflow
                   {:cells {:start :c/fast, :next :c/next}
                    :edges {:start {:done :next, :timeout :end}
                            :next :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 5000}}
                   {} {})]
      (is (= "fast" (:result result))
          "Handler result is present")
      (is (not (timed-out? result))
          "No timeout in trace"))))

;; ===== Round 3: Validation — timeout value must be positive integer =====

(deftest timeout-validation-positive-integer-test
  (testing "Timeout value must be a positive integer"
    (make-cell :c/val3)

    (is (thrown-with-msg? Exception #"positive integer"
          (myc/run-workflow
            {:cells {:start :c/val3}
             :edges {:start {:done :end, :timeout :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :timeouts {:start -100}}
            {} {})))

    (is (thrown-with-msg? Exception #"positive integer"
          (myc/run-workflow
            {:cells {:start :c/val3}
             :edges {:start {:done :end, :timeout :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :timeouts {:start 0}}
            {} {})))

    (is (thrown-with-msg? Exception #"positive integer"
          (myc/run-workflow
            {:cells {:start :c/val3}
             :edges {:start {:done :end, :timeout :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :timeouts {:start "fast"}}
            {} {})))))

;; ===== Round 4: Validation — timeout cell must have :timeout edge =====

(deftest timeout-requires-timeout-edge-test
  (testing "Cell with timeout must have a :timeout edge target"
    (make-cell :c/val4)

    (is (thrown-with-msg? Exception #"timeout.*edge"
          (myc/run-workflow
            {:cells {:start :c/val4}
             :edges {:start :end}
             :timeouts {:start 1000}}
            {} {})))))

;; ===== Round 5: Validation — timeout cell must exist =====

(deftest timeout-cell-must-exist-test
  (testing "Timeout references a cell that exists in :cells"
    (make-cell :c/val5)

    (is (thrown-with-msg? Exception #"timeout.*:nonexistent"
          (myc/run-workflow
            {:cells {:start :c/val5}
             :edges {:start :end}
             :timeouts {:nonexistent 1000}}
            {} {})))))

;; ===== Round 6: Timeout with async cell =====

(deftest timeout-with-async-cell-test
  (testing "Async cell that times out routes to :timeout edge"
    (defmethod cell/cell-spec :c/async-slow [_]
      {:id :c/async-slow
       :handler (fn [_resources data callback _error-cb]
                  (future
                    (Thread/sleep 200)
                    (callback (assoc data :async-result "done"))))
       :async? true
       :schema {:input [:map] :output [:map]}})
    (make-cell :c/async-fallback)

    (let [result (myc/run-workflow
                   {:cells {:start :c/async-slow, :fallback :c/async-fallback}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (timed-out? result))
      (is (true? (:c/async-fallback result))))))

;; ===== Round 7: Timeout dispatch auto-injection =====

(deftest timeout-dispatch-auto-injected-test
  (testing ":timeout dispatch is auto-injected — user doesn't need to write it"
    (make-cell :c/slow7 (fn [_ data]
                          (Thread/sleep 200)
                          (assoc data :result "done")))
    (make-cell :c/fallback7)

    ;; No :timeout in dispatches — should be auto-injected
    (let [result (myc/run-workflow
                   {:cells {:start :c/slow7, :fallback :c/fallback7}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (fn [d] (not (:mycelium/timeout d)))]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (timed-out? result)))))

;; ===== Round 8: Timeout with :default edge =====

(deftest timeout-with-default-edge-test
  (testing "Timeout works alongside :default edge"
    (make-cell :c/slow8 (fn [_ data]
                          (Thread/sleep 200)
                          (assoc data :result "done")))
    (make-cell :c/fallback8)
    (make-cell :c/default8)

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow8, :fallback :c/fallback8, :default-cell :c/default8}
                    :edges {:start {:done :end, :timeout :fallback, :default :default-cell}
                            :fallback :end
                            :default-cell :end}
                    :dispatches {:start [[:done (fn [d] (and (not (:mycelium/timeout d))
                                                             (:result d)))]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (timed-out? result))
      (is (true? (:c/fallback8 result))
          ":timeout routes before :default"))))

;; ===== Round 9: Trace records timeout =====

(deftest timeout-trace-test
  (testing "Trace entry records timeout event"
    (make-cell :c/slow9 (fn [_ data]
                          (Thread/sleep 200)
                          (assoc data :result "done")))
    (make-cell :c/fallback9)

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow9, :fallback :c/fallback9}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :timeouts {:start 50}}
                   {} {})
          trace (:mycelium/trace result)
          start-entry (first (filter #(= :start (:cell %)) trace))]
      (is (some? start-entry) "Start cell appears in trace")
      (is (true? (:timeout? start-entry)) "Trace entry has :timeout? flag"))))

;; ===== Round 10: Multiple cells with timeouts =====

(deftest multiple-timeouts-test
  (testing "Multiple cells can have independent timeouts"
    (make-cell :c/fast10 (fn [_ data] (assoc data :first "ok")))
    (make-cell :c/slow10 (fn [_ data]
                           (Thread/sleep 200)
                           (assoc data :second "done")))
    (make-cell :c/fallback10)

    (let [result (myc/run-workflow
                   {:cells {:start :c/fast10
                            :next  :c/slow10
                            :fallback :c/fallback10}
                    :edges {:start {:done :next, :timeout :end}
                            :next {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (fn [d] (not (:mycelium/timeout d)))]]
                                 :next  [[:done (fn [d] (not (:mycelium/timeout d)))]]}
                    :timeouts {:start 5000, :next 50}}
                   {} {})]
      (is (= "ok" (:first result)) "First cell completes normally")
      (is (timed-out? result) "Second cell times out (recorded in trace)")
      (is (true? (:c/fallback10 result)) "Routes to fallback"))))

;; ===== Round 11: Timeout skips output schema validation =====

(deftest timeout-skips-output-schema-test
  (testing "Timed-out cell does not trigger output schema validation error"
    (defmethod cell/cell-spec :c/strict-output [_]
      {:id :c/strict-output
       :handler (fn [_ data]
                  (Thread/sleep 200)
                  (assoc data :required-key "value"))
       :schema {:input [:map]
                :output [:map [:required-key :string]]}})
    (make-cell :c/fallback11)

    (let [result (myc/run-workflow
                   {:cells {:start :c/strict-output, :fallback :c/fallback11}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback :end}
                    :dispatches {:start [[:done (fn [d] (not (:mycelium/timeout d)))]]}
                    :timeouts {:start 50}}
                   {} {})]
      (is (timed-out? result)
          "Timeout fires")
      (is (nil? (:mycelium/schema-error result))
          "No schema error despite missing :required-key"))))

;; ===== Round 12: Stale :mycelium/timeout flag is cleaned up =====

(deftest timeout-flag-cleared-for-downstream-test
  (testing ":mycelium/timeout is cleared after routing so downstream cells don't see it"
    (make-cell :c/slow12 (fn [_ data]
                           (Thread/sleep 200)
                           (assoc data :slow-result "done")))
    ;; Fallback also has a timeout — should NOT fire from stale flag
    (make-cell :c/fallback12 (fn [_ data]
                               (assoc data :fallback-result "recovered")))

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow12
                            :fallback :c/fallback12
                            :oops :c/fallback12}
                    :edges {:start {:done :end, :timeout :fallback}
                            :fallback {:done :end, :timeout :oops}
                            :oops :end}
                    :dispatches {:start    [[:done (fn [d] (not (:mycelium/timeout d)))]]
                                 :fallback [[:done (fn [d] (not (:mycelium/timeout d)))]]}
                    :timeouts {:start 50, :fallback 5000}}
                   {} {})]
      (is (= "recovered" (:fallback-result result))
          "Fallback cell completes normally")
      (is (nil? (:mycelium/timeout result))
          "Timeout flag is cleaned up after routing"))))

;; ===== Round 13: Handler exception propagates through timeout wrapper =====

(deftest timeout-handler-exception-propagates-test
  (testing "If handler throws within timeout window, exception propagates normally"
    (make-cell :c/explode (fn [_ _data]
                            (throw (ex-info "boom" {:reason :test}))))

    ;; Maestro catches the exception and routes to ::error, wrapping as "execution error".
    ;; The original "boom" is nested as the cause.
    (is (thrown? Exception
          (myc/run-workflow
            {:cells {:start :c/explode}
             :edges {:start {:done :end, :timeout :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :timeouts {:start 5000}}
            {} {})))))

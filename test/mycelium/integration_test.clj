(ns mycelium.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]
            [promesa.core :as p]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. Linear workflow runs end-to-end =====

(deftest linear-workflow-end-to-end-test
  (testing "Linear workflow runs end-to-end, all data accumulated"
    (defmethod cell/cell-spec :int/step-1 [_]
      {:id          :int/step-1
       :handler     (fn [_ data]
                      (assoc data :a (inc (:x data))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :int/step-2 [_]
      {:id          :int/step-2
       :handler     (fn [_ data]
                      (assoc data :b (* 2 (:a data))))
       :schema      {:input [:map [:a :int]]
                     :output [:map [:b :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/step-1
                             :step2 :int/step-2}
                     :edges {:start {:next :step2}
                             :step2 {:done :end}}
                     :dispatches {:start [[:next (constantly true)]]
                                  :step2 [[:done (constantly true)]]}})
          result   (fsm/run compiled {} {:data {:x 10}})]
      (is (= 11 (:a result)))
      (is (= 22 (:b result))))))

;; ===== 2. Branching workflow takes correct path =====

(deftest branching-workflow-test
  (testing "Branching workflow takes correct path based on dispatch predicates"
    (defmethod cell/cell-spec :int/router [_]
      {:id          :int/router
       :handler     (fn [_ data] data)
       :schema      {:input [:map [:value :int]]
                     :output [:map]}})

    (defmethod cell/cell-spec :int/high-path [_]
      {:id          :int/high-path
       :handler     (fn [_ data]
                      (assoc data :result "high"))
       :schema      {:input [:map [:value :int]]
                     :output [:map [:result :string]]}})

    (defmethod cell/cell-spec :int/low-path [_]
      {:id          :int/low-path
       :handler     (fn [_ data]
                      (assoc data :result "low"))
       :schema      {:input [:map [:value :int]]
                     :output [:map [:result :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start    :int/router
                             :high     :int/high-path
                             :low      :int/low-path}
                     :edges {:start {:high :high, :low :low}
                             :high  {:done :end}
                             :low   {:done :end}}
                     :dispatches {:start [[:high (fn [d] (> (:value d) 5))]
                                          [:low  (fn [d] (<= (:value d) 5))]]
                                  :high [[:done (constantly true)]]
                                  :low  [[:done (constantly true)]]}})]
      (is (= "high" (:result (fsm/run compiled {} {:data {:value 10}}))))
      (is (= "low"  (:result (fsm/run compiled {} {:data {:value 2}})))))))

;; ===== 3. Invalid input triggers error state =====

(deftest invalid-input-triggers-error-test
  (testing "Invalid input triggers error state (pre interceptor catches)"
    (defmethod cell/cell-spec :int/strict-cell [_]
      {:id          :int/strict-cell
       :handler     (fn [_ data]
                      (assoc data :greeting (str "Hello " (:name data))))
       :schema      {:input [:map [:name :string]]
                     :output [:map [:greeting :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/strict-cell}
                     :edges {:start {:ok :end}}
                     :dispatches {:start [[:ok (constantly true)]]}})]
      (is (thrown? Exception
            (fsm/run compiled {} {:data {:name 42}}))))))

;; ===== 4. Invalid output triggers error state =====

(deftest invalid-output-triggers-error-test
  (testing "Invalid output triggers error state (post interceptor catches)"
    (defmethod cell/cell-spec :int/bad-output-cell [_]
      {:id          :int/bad-output-cell
       :handler     (fn [_ data]
                      ;; Returns :y as int instead of string - schema violation
                      (assoc data :y 42))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/bad-output-cell}
                     :edges {:start {:ok :end}}
                     :dispatches {:start [[:ok (constantly true)]]}})]
      (is (thrown? Exception
            (fsm/run compiled {} {:data {:x 1}}))))))

;; ===== 5. Error state receives schema error details =====

(deftest error-state-receives-schema-error-test
  (testing "Error state receives :mycelium/schema-error details"
    (defmethod cell/cell-spec :int/will-fail [_]
      {:id          :int/will-fail
       :handler     (fn [_ data]
                      (assoc data :y 42))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}})

    (let [error-data (atom nil)
          compiled   (wf/compile-workflow
                      {:cells {:start :int/will-fail}
                       :edges {:start {:ok :end}}
                       :dispatches {:start [[:ok (constantly true)]]}}
                      {:on-error (fn [_ fsm-state]
                                   (reset! error-data (:data fsm-state))
                                   (:data fsm-state))})]
      (fsm/run compiled {} {:data {:x 1}})
      (is (some? (:mycelium/schema-error @error-data)))
      (is (= :int/will-fail (get-in @error-data [:mycelium/schema-error :cell-id]))))))

;; ===== 6. Resources pass through to cell handlers =====

(deftest resources-pass-through-test
  (testing "Resources pass through to cell handlers"
    (defmethod cell/cell-spec :int/uses-resource [_]
      {:id          :int/uses-resource
       :handler     (fn [{:keys [config]} data]
                      (assoc data :config-val (:api-key config)))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:config-val :string]]}
       :requires    [:config]})

    (let [compiled  (wf/compile-workflow
                     {:cells {:start :int/uses-resource}
                      :edges {:start {:ok :end}}
                      :dispatches {:start [[:ok (constantly true)]]}})
          resources {:config {:api-key "secret-123"}}
          result    (fsm/run compiled resources {:data {:x 1}})]
      (is (= "secret-123" (:config-val result))))))

;; ===== 7. Looping workflow with counter =====

(deftest looping-workflow-test
  (testing "Looping workflow (cycle) works with counter via dispatch predicates"
    (defmethod cell/cell-spec :int/incrementer [_]
      {:id          :int/incrementer
       :handler     (fn [_ data]
                      (assoc data :count (inc (:count data))))
       :schema      {:input [:map [:count :int]]
                     :output [:map [:count :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/incrementer}
                     :edges {:start {:again :start, :done :end}}
                     :dispatches {:start [[:again (fn [d] (< (:count d) 5))]
                                          [:done  (fn [d] (>= (:count d) 5))]]}})
          result   (fsm/run compiled {} {:data {:count 0}})]
      (is (= 5 (:count result))))))

;; ===== 8. Async cell runs correctly =====

(deftest async-cell-in-workflow-test
  (testing "Async cell runs correctly in workflow"
    (defmethod cell/cell-spec :int/async-cell [_]
      {:id          :int/async-cell
       :handler     (fn [_ data callback _error-callback]
                      (future
                        (Thread/sleep 10)
                        (callback (assoc data :y (* (:x data) 3)))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}
       :async?      true})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/async-cell}
                     :edges {:start {:ok :end}}
                     :dispatches {:start [[:ok (constantly true)]]}})
          result   (fsm/run compiled {} {:data {:x 7}})
          result   (if (p/promise? result) @result result)]
      (is (= 21 (:y result))))))

;; ===== 9. Linear workflow trace contains entries in order =====

(deftest linear-workflow-trace-test
  (testing "Linear workflow result contains :mycelium/trace with entries in order"
    (defmethod cell/cell-spec :int/trace-a [_]
      {:id      :int/trace-a
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :int/trace-b [_]
      {:id      :int/trace-b
       :handler (fn [_ data] (assoc data :b 2))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/trace-a
                             :step2 :int/trace-b}
                     :edges {:start {:next :step2}
                             :step2 {:done :end}}
                     :dispatches {:start [[:next (constantly true)]]
                                  :step2 [[:done (constantly true)]]}})
          result   (fsm/run compiled {} {:data {:x 10}})
          trace    (:mycelium/trace result)]
      (is (= 2 (count trace)))
      (is (= :start (:cell (first trace))))
      (is (= :int/trace-a (:cell-id (first trace))))
      (is (= :next (:transition (first trace))))
      (is (= :step2 (:cell (second trace))))
      (is (= :int/trace-b (:cell-id (second trace))))
      (is (= :done (:transition (second trace)))))))

;; ===== 10. Branching workflow trace shows correct path =====

(deftest branching-workflow-trace-test
  (testing "Branching workflow trace shows only the path taken"
    (defmethod cell/cell-spec :int/trace-router [_]
      {:id      :int/trace-router
       :handler (fn [_ data] data)
       :schema  {:input [:map [:value :int]] :output [:map]}})

    (defmethod cell/cell-spec :int/trace-high [_]
      {:id      :int/trace-high
       :handler (fn [_ data] (assoc data :path "high"))
       :schema  {:input [:map [:value :int]] :output [:map [:path :string]]}})

    (defmethod cell/cell-spec :int/trace-low [_]
      {:id      :int/trace-low
       :handler (fn [_ data] (assoc data :path "low"))
       :schema  {:input [:map [:value :int]] :output [:map [:path :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/trace-router
                             :high  :int/trace-high
                             :low   :int/trace-low}
                     :edges {:start {:high :high, :low :low}
                             :high  {:done :end}
                             :low   {:done :end}}
                     :dispatches {:start [[:high (fn [d] (> (:value d) 5))]
                                          [:low  (fn [d] (<= (:value d) 5))]]
                                  :high [[:done (constantly true)]]
                                  :low  [[:done (constantly true)]]}})
          result-high (fsm/run compiled {} {:data {:value 10}})
          result-low  (fsm/run compiled {} {:data {:value 2}})]
      ;; High path: start → high
      (is (= [:start :high] (mapv :cell (:mycelium/trace result-high))))
      (is (= :high (:transition (first (:mycelium/trace result-high)))))
      ;; Low path: start → low
      (is (= [:start :low] (mapv :cell (:mycelium/trace result-low))))
      (is (= :low (:transition (first (:mycelium/trace result-low))))))))

;; ===== 11. Looping workflow trace shows each iteration =====

(deftest looping-workflow-trace-test
  (testing "Looping workflow trace shows each iteration"
    (defmethod cell/cell-spec :int/trace-inc [_]
      {:id      :int/trace-inc
       :handler (fn [_ data] (assoc data :count (inc (:count data))))
       :schema  {:input [:map [:count :int]] :output [:map [:count :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/trace-inc}
                     :edges {:start {:again :start, :done :end}}
                     :dispatches {:start [[:again (fn [d] (< (:count d) 3))]
                                          [:done  (fn [d] (>= (:count d) 3))]]}})
          result   (fsm/run compiled {} {:data {:count 0}})
          trace    (:mycelium/trace result)]
      (is (= 3 (count trace)))
      (is (every? #(= :start (:cell %)) trace))
      (is (= [:again :again :done] (mapv :transition trace)))
      ;; Data snapshots show progression
      (is (= [1 2 3] (mapv #(get-in % [:data :count]) trace))))))

;; ===== 12. Error case trace shows failing step =====

(deftest error-trace-test
  (testing "Error case trace shows the failing step with :error details"
    (defmethod cell/cell-spec :int/trace-ok [_]
      {:id      :int/trace-ok
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :int/trace-bad [_]
      {:id      :int/trace-bad
       :handler (fn [_ data]
                  ;; Returns wrong type — schema violation
                  (assoc data :b "not-an-int"))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})

    (let [error-data (atom nil)
          compiled   (wf/compile-workflow
                      {:cells {:start :int/trace-ok
                               :step2 :int/trace-bad}
                       :edges {:start {:next :step2}
                               :step2 {:done :end}}
                       :dispatches {:start [[:next (constantly true)]]
                                    :step2 [[:done (constantly true)]]}}
                      {:on-error (fn [_ fsm-state]
                                   (reset! error-data (:data fsm-state))
                                   (:data fsm-state))})]
      (fsm/run compiled {} {:data {:x 1}})
      (let [trace (:mycelium/trace @error-data)]
        ;; Both steps appear in trace
        (is (= 2 (count trace)))
        (is (= :start (:cell (first trace))))
        (is (nil? (:error (first trace))))
        (is (= :step2 (:cell (second trace))))
        (is (some? (:error (second trace))))))))

;; ===== 13. Trace entries include :duration-ms from Maestro =====

(deftest trace-duration-ms-test
  (testing "Trace entries include :duration-ms from Maestro's timing"
    (defmethod cell/cell-spec :int/timed-a [_]
      {:id      :int/timed-a
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :int/timed-b [_]
      {:id      :int/timed-b
       :handler (fn [_ data]
                  (Thread/sleep 5)
                  (assoc data :b 2))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/timed-a
                             :step2 :int/timed-b}
                     :edges {:start {:next :step2}
                             :step2 {:done :end}}
                     :dispatches {:start [[:next (constantly true)]]
                                  :step2 [[:done (constantly true)]]}})
          result   (fsm/run compiled {} {:data {:x 10}})
          trace    (:mycelium/trace result)]
      (is (= 2 (count trace)))
      ;; Both trace entries should have :duration-ms
      (doseq [entry trace]
        (is (number? (:duration-ms entry))
            (str "Trace entry for " (:cell entry) " should have :duration-ms")))
      ;; The sleeping cell should have measurable duration
      (is (>= (:duration-ms (second trace)) 1.0)))))

;; ===== 14. Error trace entries include :duration-ms =====

(deftest trace-duration-ms-on-error-test
  (testing "Trace entries include :duration-ms even when output validation fails"
    (defmethod cell/cell-spec :int/timed-ok [_]
      {:id      :int/timed-ok
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :int/timed-bad [_]
      {:id      :int/timed-bad
       :handler (fn [_ data]
                  (Thread/sleep 5)
                  (assoc data :b "not-an-int"))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})

    (let [error-data (atom nil)
          compiled   (wf/compile-workflow
                      {:cells {:start :int/timed-ok
                               :step2 :int/timed-bad}
                       :edges {:start {:next :step2}
                               :step2 {:done :end}}
                       :dispatches {:start [[:next (constantly true)]]
                                    :step2 [[:done (constantly true)]]}}
                      {:on-error (fn [_ fsm-state]
                                   (reset! error-data (:data fsm-state))
                                   (:data fsm-state))})]
      (fsm/run compiled {} {:data {:x 1}})
      (let [trace (:mycelium/trace @error-data)]
        (is (= 2 (count trace)))
        ;; Both entries should have duration-ms
        (doseq [entry trace]
          (is (number? (:duration-ms entry))
              (str "Trace entry for " (:cell entry) " should have :duration-ms")))
        ;; The error entry should also have :error AND :duration-ms
        (is (some? (:error (second trace))))
        (is (number? (:duration-ms (second trace))))))))

;; ===== 15. Looping workflow trace entries each have :duration-ms =====

(deftest trace-duration-ms-looping-test
  (testing "Each iteration of a looping workflow has its own :duration-ms"
    (defmethod cell/cell-spec :int/timed-loop [_]
      {:id      :int/timed-loop
       :handler (fn [_ data] (assoc data :count (inc (:count data))))
       :schema  {:input [:map [:count :int]] :output [:map [:count :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/timed-loop}
                     :edges {:start {:again :start, :done :end}}
                     :dispatches {:start [[:again (fn [d] (< (:count d) 3))]
                                          [:done  (fn [d] (>= (:count d) 3))]]}})
          result   (fsm/run compiled {} {:data {:count 0}})
          trace    (:mycelium/trace result)]
      (is (= 3 (count trace)))
      ;; Each iteration should have its own :duration-ms
      (doseq [entry trace]
        (is (number? (:duration-ms entry))
            (str "Loop iteration should have :duration-ms"))))))

;; ===== 16. Branching workflow trace has :duration-ms on taken path =====

(deftest trace-duration-ms-branching-test
  (testing "Branching workflow trace entries on the taken path have :duration-ms"
    (defmethod cell/cell-spec :int/timed-router [_]
      {:id      :int/timed-router
       :handler (fn [_ data] data)
       :schema  {:input [:map [:value :int]] :output [:map]}})

    (defmethod cell/cell-spec :int/timed-path [_]
      {:id      :int/timed-path
       :handler (fn [_ data] (assoc data :result "done"))
       :schema  {:input [:map [:value :int]] :output [:map [:result :string]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start  :int/timed-router
                             :path-a :int/timed-path
                             :path-b :int/timed-path}
                     :edges {:start  {:a :path-a, :b :path-b}
                             :path-a {:done :end}
                             :path-b {:done :end}}
                     :dispatches {:start  [[:a (fn [d] (> (:value d) 5))]
                                           [:b (fn [d] (<= (:value d) 5))]]
                                  :path-a [[:done (constantly true)]]
                                  :path-b [[:done (constantly true)]]}})]
      ;; Take path A
      (let [result (fsm/run compiled {} {:data {:value 10}})
            trace  (:mycelium/trace result)]
        (is (= 2 (count trace)))
        (is (= [:start :path-a] (mapv :cell trace)))
        (doseq [entry trace]
          (is (number? (:duration-ms entry)))))
      ;; Take path B
      (let [result (fsm/run compiled {} {:data {:value 2}})
            trace  (:mycelium/trace result)]
        (is (= 2 (count trace)))
        (is (= [:start :path-b] (mapv :cell trace)))
        (doseq [entry trace]
          (is (number? (:duration-ms entry))))))))

;; ===== 17. Trace data snapshots don't contain :mycelium/trace =====

(deftest trace-no-nesting-test
  (testing "Trace :data snapshots don't contain :mycelium/trace (no recursive nesting)"
    (defmethod cell/cell-spec :int/nest-a [_]
      {:id      :int/nest-a
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :int/nest-b [_]
      {:id      :int/nest-b
       :handler (fn [_ data] (assoc data :b 2))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})

    (let [compiled (wf/compile-workflow
                    {:cells {:start :int/nest-a
                             :step2 :int/nest-b}
                     :edges {:start {:next :step2}
                             :step2 {:done :end}}
                     :dispatches {:start [[:next (constantly true)]]
                                  :step2 [[:done (constantly true)]]}})
          result   (fsm/run compiled {} {:data {:x 10}})
          trace    (:mycelium/trace result)]
      (doseq [entry trace]
        (is (not (contains? (:data entry) :mycelium/trace))
            (str "Trace entry for " (:cell entry) " should not contain :mycelium/trace in :data"))))))

;; ===== Parameterized cells =====

(deftest parameterized-cell-receives-params-test
  (testing "Handler receives :mycelium/params from workflow params"
    (defmethod cell/cell-spec :int/param-multiply [_]
      {:id      :int/param-multiply
       :handler (fn [_ data]
                  (let [mult (get-in data [:mycelium/params :multiplier])]
                    (assoc data :result (* (:x data) mult))))
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:result :int]]}})

    (let [result (myc/run-workflow
                  {:cells {:start {:id :int/param-multiply
                                   :params {:multiplier 3}}}
                   :edges {:start {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]}}
                  {}
                  {:x 10})]
      (is (= 30 (:result result))))))

(deftest parameterized-cell-same-handler-different-params-test
  (testing "Same cell-id used twice with different params"
    (defmethod cell/cell-spec :int/param-prefix [_]
      {:id      :int/param-prefix
       :handler (fn [_ data]
                  (let [prefix (get-in data [:mycelium/params :prefix])
                        k      (get-in data [:mycelium/params :output-key])]
                    (assoc data k (str prefix "-" (:value data)))))
       :schema  {:input  [:map [:value :string]]
                 :output [:map]}})

    (let [result (myc/run-workflow
                  {:cells {:start  {:id :int/param-prefix
                                    :params {:prefix "hello" :output-key :greeting}}
                           :step-b {:id :int/param-prefix
                                    :params {:prefix "goodbye" :output-key :farewell}}}
                   :pipeline [:start :step-b]}
                  {}
                  {:value "world"})]
      (is (= "hello-world" (:greeting result)))
      (is (= "goodbye-world" (:farewell result))))))

(deftest parameterized-cell-params-not-in-output-test
  (testing ":mycelium/params is cleaned up from final result"
    (defmethod cell/cell-spec :int/param-simple [_]
      {:id      :int/param-simple
       :handler (fn [_ data] (assoc data :done true))
       :schema  {:input [:map] :output [:map [:done :boolean]]}})

    (let [result (myc/run-workflow
                  {:cells {:start {:id :int/param-simple
                                   :params {:mode "fast"}}}
                   :edges {:start :end}}
                  {}
                  {})]
      (is (true? (:done result)))
      (is (not (contains? result :mycelium/params))))))

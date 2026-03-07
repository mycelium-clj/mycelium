(ns mycelium.error-taxonomy-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== workflow-error extracts unified error =====

(deftest workflow-error-nil-on-success-test
  (testing "workflow-error returns nil for successful workflows"
    (defmethod cell/cell-spec :test/ok [_]
      {:id :test/ok
       :handler (fn [_ data] {:result 42})
       :schema {:input [:map] :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells {:start :test/ok}
                    :edges {:start :end}}
                   {} {})]
      (is (nil? (myc/workflow-error result))))))

(deftest workflow-error-predicate-test
  (testing "error? returns true/false based on workflow-error"
    (defmethod cell/cell-spec :test/ok [_]
      {:id :test/ok
       :handler (fn [_ data] {:result 42})
       :schema {:input [:map] :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells {:start :test/ok}
                    :edges {:start :end}}
                   {} {})]
      (is (false? (myc/error? result))))))

;; ===== Schema errors =====

(deftest workflow-error-schema-output-test
  (testing "workflow-error normalizes output schema errors"
    (defmethod cell/cell-spec :test/bad [_]
      {:id :test/bad
       :handler (fn [_ data] {:count "not-int"})
       :schema {:input [:map] :output [:map [:count :int]]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells {:start :test/bad}
                    :edges {:start :end}}
                   {} {} {:on-error on-error})
          err (myc/workflow-error result)]
      (is (some? err))
      (is (= :schema/output (:error-type err)))
      (is (= :test/bad (:cell-id err)))
      (is (string? (:message err)))
      (is (map? (:details err))))))

(deftest workflow-error-schema-input-test
  (testing "workflow-error normalizes input schema errors"
    (defmethod cell/cell-spec :test/strict [_]
      {:id :test/strict
       :handler (fn [_ data] data)
       :schema {:input [:map [:x :int]] :output [:map]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells {:start :test/strict}
                    :edges {:start :end}}
                   {} {:x "bad"} {:on-error on-error})
          err (myc/workflow-error result)]
      (is (some? err))
      (is (= :schema/input (:error-type err)))
      (is (= :test/strict (:cell-id err))))))

;; ===== Workflow input-schema errors =====

(deftest workflow-error-input-schema-test
  (testing "workflow-error normalizes workflow-level input-schema errors"
    (defmethod cell/cell-spec :test/step [_]
      {:id :test/step
       :handler (fn [_ data] data)
       :schema {:input [:map [:x :int]] :output [:map]}})
    (let [result (myc/run-workflow
                   {:cells {:start :test/step}
                    :edges {:start :end}
                    :input-schema [:map [:x :int]]}
                   {} {:x "not-int"})
          err (myc/workflow-error result)]
      (is (some? err))
      (is (= :input (:error-type err)))
      (is (string? (:message err)))
      (is (map? (:details err))))))

;; ===== Error group errors =====

(deftest workflow-error-handler-exception-test
  (testing "workflow-error normalizes handler exceptions from error groups"
    (defmethod cell/cell-spec :test/throws [_]
      {:id :test/throws
       :handler (fn [_ data] (throw (ex-info "boom" {:reason :test})))
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :test/err-handler [_]
      {:id :test/err-handler
       :handler (fn [_ data] data)
       :schema {:input [:map] :output [:map]}})
    (let [result (myc/run-workflow
                   {:cells {:start :test/throws
                            :err   :test/err-handler}
                    :edges {:start {:done :end :on-error :err}
                            :err   :end}
                    :dispatches {:start [[:done (constantly true)]]}
                    :error-groups {:pipeline {:cells [:start]
                                              :on-error :err}}}
                   {} {})
          err (myc/workflow-error result)]
      (is (some? err))
      (is (= :handler (:error-type err)))
      (is (= :start (:cell err)))
      (is (string? (:message err))))))

;; ===== Unified error has consistent shape =====

(deftest workflow-error-consistent-shape-test
  (testing "All error types have :error-type :message :details"
    (defmethod cell/cell-spec :test/bad [_]
      {:id :test/bad
       :handler (fn [_ data] {:count "not-int"})
       :schema {:input [:map] :output [:map [:count :int]]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells {:start :test/bad}
                    :edges {:start :end}}
                   {} {} {:on-error on-error})
          err (myc/workflow-error result)]
      ;; All errors have these three keys
      (is (keyword? (:error-type err)))
      (is (string? (:message err)))
      (is (some? (:details err))))))

;; ===== error? predicate =====

(deftest error-predicate-on-error-test
  (testing "error? returns true for error results"
    (defmethod cell/cell-spec :test/bad [_]
      {:id :test/bad
       :handler (fn [_ data] {:count "not-int"})
       :schema {:input [:map] :output [:map [:count :int]]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells {:start :test/bad}
                    :edges {:start :end}}
                   {} {} {:on-error on-error})]
      (is (true? (myc/error? result))))))

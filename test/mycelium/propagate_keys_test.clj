(ns mycelium.propagate-keys-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Default behavior (enabled) =====

(deftest propagate-keys-merges-input-into-output-test
  (testing "Input keys automatically appear in output (default behavior)"
    (defmethod cell/cell-spec :test/add-b [_]
      {:id      :test/add-b
       ;; Handler returns ONLY :b — :a propagates automatically
       :handler (fn [_ data] {:b (inc (:a data))})
       :schema  {:input  [:map [:a :int]]
                 :output [:map [:b :int]]}})
    (defmethod cell/cell-spec :test/use-both [_]
      {:id      :test/use-both
       :handler (fn [_ data] {:result (+ (:a data) (:b data))})
       :schema  {:input  [:map [:a :int] [:b :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/add-b
                                 :use   :test/use-both}
                    :edges      {:start {:done :use}
                                 :use   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :use   [[:done (constantly true)]]}}
                   {} {:a 10})]
      (is (= 21 (:result result))))))

(deftest propagate-keys-handler-output-takes-precedence-test
  (testing "Handler output keys override input keys (handler wins)"
    (defmethod cell/cell-spec :test/override [_]
      {:id      :test/override
       :handler (fn [_ data] {:x (inc (:x data)) :y 99})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:x :int] [:y :int]]}})
    (defmethod cell/cell-spec :test/read-xy [_]
      {:id      :test/read-xy
       :handler (fn [_ data] {:result (+ (:x data) (:y data))})
       :schema  {:input  [:map [:x :int] [:y :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/override
                                 :read  :test/read-xy}
                    :edges      {:start {:done :read}
                                 :read  {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :read  [[:done (constantly true)]]}}
                   {} {:x 10})]
      ;; handler output :x=11 should override input :x=10
      (is (= 110 (:result result))))))

(deftest propagate-keys-enabled-by-default-test
  (testing "Key propagation is on by default — no opt-in needed"
    (defmethod cell/cell-spec :test/add-b [_]
      {:id      :test/add-b
       :handler (fn [_ data] {:b (inc (:a data))})
       :schema  {:input  [:map [:a :int]]
                 :output [:map [:b :int]]}})
    (defmethod cell/cell-spec :test/use-both [_]
      {:id      :test/use-both
       :handler (fn [_ data] {:result (+ (:a data) (:b data))})
       :schema  {:input  [:map [:a :int] [:b :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/add-b
                                 :use   :test/use-both}
                    :edges      {:start {:done :use}
                                 :use   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :use   [[:done (constantly true)]]}}
                   {} {:a 10})]
      ;; :a propagates even without explicit :propagate-keys? true
      (is (= 21 (:result result))))))

;; ===== Opt-out =====

(deftest propagate-keys-can-be-disabled-test
  (testing "With :propagate-keys? false, keys are not propagated"
    (defmethod cell/cell-spec :test/add-b [_]
      {:id      :test/add-b
       :handler (fn [_ data] {:b (inc (:a data))})
       :schema  {:input  [:map [:a :int]]
                 :output [:map [:b :int]]}})
    (defmethod cell/cell-spec :test/use-both [_]
      {:id      :test/use-both
       :handler (fn [_ data] {:result (+ (or (:a data) 0) (:b data))})
       :schema  {:input  [:map [:a :int] [:b :int]]
                 :output [:map [:result :int]]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells      {:start :test/add-b
                                 :use   :test/use-both}
                    :edges      {:start {:done :use}
                                 :use   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :use   [[:done (constantly true)]]}}
                   {} {:a 10} {:propagate-keys? false :on-error on-error})]
      ;; :a is lost — input validation on :use fails
      (is (some? (:mycelium/schema-error result)))
      (is (= :input (:phase (:mycelium/schema-error result)))))))

;; ===== Three-cell chain =====

(deftest propagate-keys-through-chain-test
  (testing "Keys propagate through a multi-cell chain"
    (defmethod cell/cell-spec :test/step-a [_]
      {:id      :test/step-a
       :handler (fn [_ data] {:a-out 1})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:a-out :int]]}})
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] {:b-out 2})
       :schema  {:input  [:map [:a-out :int]]
                 :output [:map [:b-out :int]]}})
    (defmethod cell/cell-spec :test/step-c [_]
      {:id      :test/step-c
       :handler (fn [_ data]
                  {:result (+ (:x data) (:a-out data) (:b-out data))})
       :schema  {:input  [:map [:x :int] [:a-out :int] [:b-out :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/step-a
                                 :b     :test/step-b
                                 :c     :test/step-c}
                    :edges      {:start {:done :b}
                                 :b     {:done :c}
                                 :c     {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :b     [[:done (constantly true)]]
                                 :c     [[:done (constantly true)]]}}
                   {} {:x 100})]
      ;; :x propagates through all cells, :a-out through step-b and step-c
      (is (= 103 (:result result))))))

;; ===== Pre-compile =====

(deftest propagate-keys-with-pre-compile-test
  (testing "Key propagation works with pre-compile (default on)"
    (defmethod cell/cell-spec :test/add-b [_]
      {:id      :test/add-b
       :handler (fn [_ data] {:b (inc (:a data))})
       :schema  {:input  [:map [:a :int]]
                 :output [:map [:b :int]]}})
    (defmethod cell/cell-spec :test/use-both [_]
      {:id      :test/use-both
       :handler (fn [_ data] {:result (+ (:a data) (:b data))})
       :schema  {:input  [:map [:a :int] [:b :int]]
                 :output [:map [:result :int]]}})
    (let [compiled (myc/pre-compile
                     {:cells      {:start :test/add-b
                                   :use   :test/use-both}
                      :edges      {:start {:done :use}
                                   :use   {:done :end}}
                      :dispatches {:start [[:done (constantly true)]]
                                   :use   [[:done (constantly true)]]}})
          result (myc/run-compiled compiled {} {:a 10})]
      (is (= 21 (:result result))))))

;; ===== Schema validation still works =====

(deftest propagate-keys-schema-validation-still-enforced-test
  (testing "Output schema validation still catches bad handler output"
    (defmethod cell/cell-spec :test/bad-output [_]
      {:id      :test/bad-output
       :handler (fn [_ data] {:count "not-an-int"})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:count :int]]}})
    (defmethod cell/cell-spec :test/next [_]
      {:id      :test/next
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:count :int]]
                 :output [:map]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells      {:start :test/bad-output
                                 :next  :test/next}
                    :edges      {:start {:done :next}
                                 :next  {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :next  [[:done (constantly true)]]}}
                   {} {:x 42} {:on-error on-error})]
      (is (some? (:mycelium/schema-error result))))))

;; ===== Coercion + propagation combined =====

(deftest propagate-keys-with-coercion-test
  (testing "Key propagation and coercion work together"
    (defmethod cell/cell-spec :test/produce-double [_]
      {:id      :test/produce-double
       :handler (fn [_ data] {:count 10.0})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:count :double]]}})
    (defmethod cell/cell-spec :test/consume-int [_]
      {:id      :test/consume-int
       :handler (fn [_ data] {:result (+ (:x data) (:count data))})
       :schema  {:input  [:map [:x :int] [:count :int]]
                 :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/produce-double
                                 :use   :test/consume-int}
                    :edges      {:start {:done :use}
                                 :use   {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]
                                 :use   [[:done (constantly true)]]}}
                   {} {:x 5} {:coerce? true})]
      (is (= 15 (:result result))))))

;; ===== Propagated keys visible in final result =====

(deftest propagated-keys-in-final-result-test
  (testing "Propagated keys appear in the workflow result"
    (defmethod cell/cell-spec :test/add-tag [_]
      {:id      :test/add-tag
       :handler (fn [_ data] {:tag "processed"})
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:tag :string]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :test/add-tag}
                    :edges      {:start :end}}
                   {} {:x 42})]
      ;; Both :x (propagated) and :tag (handler output) should be present
      (is (= 42 (:x result)))
      (is (= "processed" (:tag result))))))

(ns mycelium.coercion-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [mycelium.core :as myc]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== coerce-input tests =====

(deftest coerce-input-double-to-int-test
  (testing "coerce-input coerces double to int when schema expects :int"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:x :int]]}})
    (let [cell   (cell/get-cell! :test/int-cell)
          result (schema/coerce-input cell {:x 42.0})]
      (is (nil? (:error result)))
      (is (= 42 (get-in result [:data :x])))
      (is (int? (get-in result [:data :x]))))))

(deftest coerce-input-int-to-double-test
  (testing "coerce-input coerces int to double when schema expects :double"
    (defmethod cell/cell-spec :test/double-cell [_]
      {:id      :test/double-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :double]]
                 :output [:map [:x :double]]}})
    (let [cell   (cell/get-cell! :test/double-cell)
          result (schema/coerce-input cell {:x 42})]
      (is (nil? (:error result)))
      (is (= 42.0 (get-in result [:data :x])))
      (is (float? (get-in result [:data :x]))))))

(deftest coerce-input-no-schema-passthrough-test
  (testing "coerce-input returns data unchanged when no input schema"
    (defmethod cell/cell-spec :test/no-schema [_]
      {:id      :test/no-schema
       :handler (fn [_ data] data)})
    (let [cell   (cell/get-cell! :test/no-schema)
          result (schema/coerce-input cell {:x 42})]
      (is (nil? (:error result)))
      (is (= {:x 42} (:data result))))))

(deftest coerce-input-fails-non-coercible-test
  (testing "coerce-input returns error when data can't be coerced"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:x :int]]}})
    (let [cell   (cell/get-cell! :test/int-cell)
          result (schema/coerce-input cell {:x "not-a-number"})]
      (is (some? (:error result))))))

(deftest coerce-input-preserves-extra-keys-test
  (testing "coerce-input preserves keys not in the schema"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:x :int]]}})
    (let [cell   (cell/get-cell! :test/int-cell)
          result (schema/coerce-input cell {:x 42.0 :extra "kept"})]
      (is (nil? (:error result)))
      (is (= "kept" (get-in result [:data :extra]))))))

;; ===== coerce-output tests =====

(deftest coerce-output-double-to-int-test
  (testing "coerce-output coerces double to int when schema expects :int"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:y :int]]}})
    (let [cell   (cell/get-cell! :test/int-cell)
          result (schema/coerce-output cell {:y 949.0})]
      (is (nil? (:error result)))
      (is (= 949 (get-in result [:data :y])))
      (is (int? (get-in result [:data :y]))))))

(deftest coerce-output-per-transition-test
  (testing "coerce-output works with per-transition schemas"
    (defmethod cell/cell-spec :test/pt-coerce [_]
      {:id      :test/pt-coerce
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output {:success [:map [:y :int]]
                          :failure [:map [:error-message :string]]}}})
    (let [cell   (cell/get-cell! :test/pt-coerce)
          result (schema/coerce-output cell {:y 42.0} :success)]
      (is (nil? (:error result)))
      (is (= 42 (get-in result [:data :y])))
      (is (int? (get-in result [:data :y]))))))

(deftest coerce-output-per-transition-no-label-test
  (testing "coerce-output with per-transition schema and no label tries all schemas"
    (defmethod cell/cell-spec :test/pt-coerce [_]
      {:id      :test/pt-coerce
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output {:success [:map [:y :int]]
                          :failure [:map [:error-message :string]]}}})
    (let [cell   (cell/get-cell! :test/pt-coerce)
          result (schema/coerce-output cell {:y 42.0})]
      (is (nil? (:error result)))
      (is (= 42 (get-in result [:data :y]))))))

(deftest coerce-output-fails-non-coercible-test
  (testing "coerce-output returns error when data can't be coerced"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:y :int]]}})
    (let [cell   (cell/get-cell! :test/int-cell)
          result (schema/coerce-output cell {:y "not-a-number"})]
      (is (some? (:error result))))))

;; ===== Pre-interceptor with coercion =====

(deftest pre-interceptor-coerces-data-test
  (testing "Pre-interceptor with coercion coerces data and passes it downstream"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:x :int]]}})
    (let [state->cell {:test/int-cell (cell/get-cell! :test/int-cell)}
          pre         (schema/make-pre-interceptor state->cell {:coerce? true})
          fsm-state   {:current-state-id :test/int-cell
                       :data             {:x 42.0}
                       :trace            []}
          result      (pre fsm-state {})]
      ;; Should NOT redirect to error
      (is (= :test/int-cell (:current-state-id result)))
      ;; Data should be coerced
      (is (= 42 (get-in result [:data :x])))
      (is (int? (get-in result [:data :x]))))))

(deftest pre-interceptor-coercion-rejects-invalid-test
  (testing "Pre-interceptor with coercion still rejects non-coercible data"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:x :int]]}})
    (let [state->cell {:test/int-cell (cell/get-cell! :test/int-cell)}
          pre         (schema/make-pre-interceptor state->cell {:coerce? true})
          fsm-state   {:current-state-id :test/int-cell
                       :data             {:x "bad"}
                       :trace            []}
          result      (pre fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (is (some? (get-in result [:data :mycelium/schema-error]))))))

;; ===== Post-interceptor with coercion =====

(deftest post-interceptor-coerces-output-test
  (testing "Post-interceptor with coercion coerces output data"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:y :int]]}})
    (let [state->cell {:test/int-cell (cell/get-cell! :test/int-cell)}
          post        (schema/make-post-interceptor state->cell nil nil {:coerce? true})
          fsm-state   {:last-state-id    :test/int-cell
                       :current-state-id :some/next
                       :data             {:y 949.0}
                       :trace            []}
          result      (post fsm-state {})]
      ;; Should NOT redirect to error
      (is (= :some/next (:current-state-id result)))
      ;; Data should be coerced in trace and in data
      (is (int? (get-in result [:data :y])))
      (is (= 949 (get-in result [:data :y]))))))

(deftest post-interceptor-coercion-rejects-invalid-test
  (testing "Post-interceptor with coercion still rejects non-coercible data"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:y :int]]}})
    (let [state->cell {:test/int-cell (cell/get-cell! :test/int-cell)}
          post        (schema/make-post-interceptor state->cell nil nil {:coerce? true})
          fsm-state   {:last-state-id    :test/int-cell
                       :current-state-id :some/next
                       :data             {:y "bad"}
                       :trace            []}
          result      (post fsm-state {})]
      (is (= ::fsm/error (:current-state-id result))))))

;; ===== Coercion works with pre-compiled schemas =====

(deftest coerce-with-precompiled-schemas-test
  (testing "Coercion works with pre-compiled Malli schema objects"
    (defmethod cell/cell-spec :test/int-cell [_]
      {:id      :test/int-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:y :int]]}})
    (let [state->cell {:test/int-cell (cell/get-cell! :test/int-cell)}
          compiled    (schema/pre-compile-schemas state->cell)
          cell        (get compiled :test/int-cell)]
      ;; coerce-input with compiled schema
      (let [result (schema/coerce-input cell {:x 42.0})]
        (is (nil? (:error result)))
        (is (= 42 (get-in result [:data :x]))))
      ;; coerce-output with compiled schema
      (let [result (schema/coerce-output cell {:y 949.0})]
        (is (nil? (:error result)))
        (is (= 949 (get-in result [:data :y])))))))

;; ===== End-to-end: compile-workflow with :coerce? =====

(deftest workflow-coercion-end-to-end-test
  (testing "Workflow compiled with :coerce? true handles numeric type mismatches"
    (defmethod cell/cell-spec :test/producer [_]
      {:id      :test/producer
       :handler (fn [_ data]
                  ;; Returns a double where downstream expects int
                  (assoc data :amount 949.0))
       :schema  {:input  [:map [:order-id :string]]
                 :output [:map [:order-id :string] [:amount :double]]}})
    (defmethod cell/cell-spec :test/consumer [_]
      {:id      :test/consumer
       :handler (fn [_ data]
                  ;; Expects :amount as int
                  (assoc data :result (str "processed-" (:amount data))))
       :schema  {:input  [:map [:amount :int]]
                 :output [:map [:amount :int] [:result :string]]}})
    (let [workflow {:cells      {:start    :test/producer
                                 :consumer :test/consumer}
                    :edges      {:start    {:done :consumer}
                                 :consumer {:done :end}}
                    :dispatches {:start    [[:done (constantly true)]]
                                 :consumer [[:done (constantly true)]]}}
          result (myc/run-workflow workflow {} {:order-id "ORD-1"} {:coerce? true})]
      ;; Without coercion this would fail because :amount is 949.0 (double) but consumer expects :int
      (is (not (contains? result :mycelium/schema-error)))
      (is (= "processed-949" (:result result))))))

(deftest workflow-without-coercion-fails-on-mismatch-test
  (testing "Workflow without :coerce? fails on numeric type mismatch (baseline)"
    (defmethod cell/cell-spec :test/producer [_]
      {:id      :test/producer
       :handler (fn [_ data]
                  (assoc data :amount 949.0))
       :schema  {:input  [:map [:order-id :string]]
                 :output [:map [:order-id :string] [:amount :double]]}})
    (defmethod cell/cell-spec :test/consumer [_]
      {:id      :test/consumer
       :handler (fn [_ data]
                  (assoc data :result (str "processed-" (:amount data))))
       :schema  {:input  [:map [:amount :int]]
                 :output [:map [:amount :int] [:result :string]]}})
    (let [workflow {:cells      {:start    :test/producer
                                 :consumer :test/consumer}
                    :edges      {:start    {:done :consumer}
                                 :consumer {:done :end}}
                    :dispatches {:start    [[:done (constantly true)]]
                                 :consumer [[:done (constantly true)]]}}
          on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow workflow {} {:order-id "ORD-1"} {:on-error on-error})]
      ;; Without coercion, schema validation should fail
      (is (some? (:mycelium/schema-error result))))))

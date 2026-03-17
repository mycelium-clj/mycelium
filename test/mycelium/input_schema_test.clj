(ns mycelium.input-schema-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as mycelium]
            [mycelium.manifest :as manifest]
            [mycelium.workflow :as workflow]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Helper cell ---

(defn- register-adder! []
  (defmethod cell/cell-spec :input-schema/adder [_]
    {:id      :input-schema/adder
     :handler (fn [_ data] (assoc data :result (+ (:x data) 100)))
     :schema  {:input [:map [:x :int]] :output [:map [:result :int]]}}))

;; ===== 1. Workflow with :input-schema rejects missing keys =====

(deftest input-schema-rejects-missing-keys-test
  (testing "run-workflow with :input-schema rejects data missing required keys"
    (register-adder!)
    (let [result (mycelium/run-workflow
                  {:cells {:start :input-schema/adder}
                   :edges {:start {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]}
                   :input-schema [:map [:x :int] [:extra :string]]}
                  {}
                  {:x 42})] ;; missing :extra
      (is (map? (:mycelium/input-error result)))
      (is (= [:map [:x :int] [:extra :string]]
             (get-in result [:mycelium/input-error :schema])))
      (is (some? (get-in result [:mycelium/input-error :errors])))
      (is (= {:x 42} (get-in result [:mycelium/input-error :data]))))))

;; ===== 2. Workflow with :input-schema accepts valid data =====

(deftest input-schema-accepts-valid-data-test
  (testing "run-workflow with :input-schema accepts valid data and runs normally"
    (register-adder!)
    (let [result (mycelium/run-workflow
                  {:cells {:start :input-schema/adder}
                   :edges {:start {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]}
                   :input-schema [:map [:x :int]]}
                  {}
                  {:x 42})]
      (is (nil? (:mycelium/input-error result)))
      (is (= 142 (:result result))))))

;; ===== 3. Workflow without :input-schema runs normally (backwards compat) =====

(deftest no-input-schema-backwards-compatible-test
  (testing "Workflow without :input-schema runs normally"
    (register-adder!)
    (let [result (mycelium/run-workflow
                  {:cells {:start :input-schema/adder}
                   :edges {:start {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]}}
                  {}
                  {:x 42})]
      (is (nil? (:mycelium/input-error result)))
      (is (= 142 (:result result))))))

;; ===== 4. Invalid :input-schema (bad Malli) → compile-time error =====

(deftest invalid-input-schema-compile-error-test
  (testing "Invalid :input-schema (bad Malli) causes compile-time error"
    (register-adder!)
    (is (thrown-with-msg? Exception #"[Ii]nvalid.*input-schema"
          (mycelium/run-workflow
           {:cells {:start :input-schema/adder}
            :edges {:start {:done :end}}
            :dispatches {:start [[:done (constantly true)]]}
            :input-schema [:not-a-real-type]}
           {}
           {:x 42})))))

;; ===== 5. Error map contains :schema, :errors, :data =====

(deftest input-error-map-structure-test
  (testing "Error map contains :schema, :errors, and :data"
    (register-adder!)
    (let [result (mycelium/run-workflow
                  {:cells {:start :input-schema/adder}
                   :edges {:start {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]}
                   :input-schema [:map [:x :int] [:y :string]]}
                  {}
                  {:x 42})]
      (is (contains? (:mycelium/input-error result) :schema))
      (is (contains? (:mycelium/input-error result) :errors))
      (is (contains? (:mycelium/input-error result) :data)))))

;; ===== 6. run-workflow-async also validates input schema =====

(deftest input-schema-async-validates-test
  (testing "run-workflow-async validates input schema"
    (register-adder!)
    (let [fut (mycelium/run-workflow-async
               {:cells {:start :input-schema/adder}
                :edges {:start {:done :end}}
                :dispatches {:start [[:done (constantly true)]]}
                :input-schema [:map [:x :int] [:required-key :string]]}
               {}
               {:x 42})]
      (is (future? fut))
      (let [result (deref fut 5000 :timeout)]
        (is (not= :timeout result))
        (is (map? (:mycelium/input-error result)))))))

;; ===== 7. Manifest with :input-schema passes through manifest->workflow =====

(deftest manifest-input-schema-passthrough-test
  (testing "Manifest :input-schema passes through manifest->workflow"
    (let [m {:id :test/input-schema-wf
             :cells {:start {:id :input-schema/m-cell
                              :doc "Manifest input schema test cell"
                              :schema {:input [:map [:x :int]]
                                       :output [:map [:y :int]]}
                              :on-error nil}}
             :edges {:start {:done :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :input-schema [:map [:x :int]]}
          wf-def (manifest/manifest->workflow m)]
      (is (= [:map [:x :int]] (:input-schema wf-def))))))

;; ===== 8. :input-schema validated during manifest validation =====

(deftest manifest-validates-input-schema-test
  (testing "Manifest with invalid :input-schema fails validation"
    (is (thrown-with-msg? Exception #"[Ii]nvalid.*input-schema"
          (manifest/validate-manifest
           {:id :test/bad-input-schema
            :cells {:start {:id :test/cell
                             :doc "Cell for input schema validation test"
                             :schema {:input [:map] :output [:map]}
                             :on-error nil}}
            :edges {:start :end}
            :input-schema [:not-a-real-type]})))))

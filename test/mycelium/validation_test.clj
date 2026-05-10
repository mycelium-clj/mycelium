(ns mycelium.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [mycelium.validation :as v]))

;; ===== Schema validation =====

(deftest validate-malli-schema-valid-test
  (testing "Valid Malli schemas pass without throwing"
    (is (nil? (v/validate-malli-schema! [:map [:x :int]] "test")))
    (is (nil? (v/validate-malli-schema! [:map {:closed true} [:x :int]] "test")))
    (is (nil? (v/validate-malli-schema! :string "test")))
    (is (nil? (v/validate-malli-schema! :int "test")))))

(deftest validate-malli-schema-invalid-test
  (testing "Invalid Malli schema throws with label in message"
    (is (thrown-with-msg? Exception #"test-label"
          (v/validate-malli-schema! [:not-a-real-type] "test-label")))))

(deftest validate-output-schema-vector-test
  (testing "Vector output schema validates as single Malli schema"
    (is (nil? (v/validate-output-schema! [:map [:y :int]] "test")))
    (is (thrown? Exception
          (v/validate-output-schema! [:not-real] "test")))))

(deftest validate-output-schema-per-transition-test
  (testing "Per-transition output schema validates each transition's schema"
    (is (nil? (v/validate-output-schema!
               [:per-transition {:found     [:map [:profile :map]]
                                 :not-found [:map [:error :string]]}]
               "test")))
    (is (thrown-with-msg? Exception #"transition :bad"
          (v/validate-output-schema!
           [:per-transition {:good [:map [:x :int]]
                             :bad  [:not-real]}]
           "test")))))

(deftest validate-output-schema-keyword-test
  (testing "Keyword output schema (e.g. :map) validates as Malli schema"
    (is (nil? (v/validate-output-schema! :map "test")))))

;; ===== Edge target validation =====

(deftest validate-edge-targets-valid-test
  (testing "Valid edge targets pass"
    (is (nil? (v/validate-edge-targets!
               {:start {:success :step-b, :failure :error}
                :step-b :end}
               #{:start :step-b})))))

(deftest validate-edge-targets-invalid-test
  (testing "Invalid edge target throws"
    (is (thrown-with-msg? Exception #"[Ii]nvalid edge target"
          (v/validate-edge-targets!
           {:start {:success :nonexistent, :failure :error}}
           #{:start})))))

(deftest validate-edge-targets-terminal-states-test
  (testing "Terminal states :end/:error/:halt are always valid targets"
    (is (nil? (v/validate-edge-targets!
               {:start {:a :end, :b :error, :c :halt}}
               #{:start})))))

;; ===== Reachability =====

(deftest validate-reachability-all-reachable-test
  (testing "All cells reachable from :start passes"
    (is (nil? (v/validate-reachability!
               {:start {:a :step-b, :b :step-c}
                :step-b :end
                :step-c :end}
               #{:start :step-b :step-c})))))

(deftest validate-reachability-unreachable-test
  (testing "Unreachable cell throws"
    (is (thrown-with-msg? Exception #"[Uu]nreachable"
          (v/validate-reachability!
           {:start :end}
           #{:start :orphan})))))

(deftest validate-reachability-deep-chain-test
  (testing "Deep chain reachability works"
    (is (nil? (v/validate-reachability!
               {:start :a, :a :b, :b :c, :c :end}
               #{:start :a :b :c})))))

;; ===== Dispatch coverage =====

(deftest validate-dispatch-coverage-valid-test
  (testing "Dispatch labels exactly match edge keys"
    (is (nil? (v/validate-dispatch-coverage!
               {:start {:success :step-b, :failure :error}}
               {:start [[:success (fn [d] (:ok d))]
                        [:failure (fn [d] (not (:ok d)))]]})))))

(deftest validate-dispatch-coverage-missing-test
  (testing "Missing dispatch label throws"
    (is (thrown-with-msg? Exception #"[Dd]ispatch"
          (v/validate-dispatch-coverage!
           {:start {:success :step-b, :failure :error}}
           {:start [[:success (fn [d] (:ok d))]]})))))

(deftest validate-dispatch-coverage-extra-test
  (testing "Extra dispatch label throws"
    (is (thrown-with-msg? Exception #"[Dd]ispatch"
          (v/validate-dispatch-coverage!
           {:start {:success :end}}
           {:start [[:success (fn [d] (:ok d))]
                    [:extra (fn [_] true)]]})))))

(deftest validate-dispatch-coverage-no-dispatch-for-map-edges-test
  (testing "Map edges with no dispatch entry throws"
    (is (thrown-with-msg? Exception #"[Dd]ispatch"
          (v/validate-dispatch-coverage!
           {:start {:success :end, :failure :error}}
           {})))))

(deftest validate-dispatch-coverage-keyword-edges-skip-test
  (testing "Unconditional (keyword) edges don't need dispatch entries"
    (is (nil? (v/validate-dispatch-coverage!
               {:start :end}
               {})))))

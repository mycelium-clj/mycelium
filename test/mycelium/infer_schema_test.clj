(ns mycelium.infer-schema-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. infer-schemas with single test input =====

(deftest infer-schemas-single-input-test
  (testing "Infers input/output schemas for each cell from a single test run"
    (cell/defcell :test/double
      (fn [_ data] {:result (* 2 (:x data))}))
    (cell/defcell :test/format
      (fn [_ data] {:message (str "Result: " (:result data))}))
    (let [wf-def  {:cells {:start :test/double
                           :fmt   :test/format}
                   :edges {:start :fmt
                           :fmt   :end}}
          inferred (dev/infer-schemas wf-def {} [{:x 5}])]
      ;; Should have schemas for both cells
      (is (contains? inferred :start))
      (is (contains? inferred :fmt))
      ;; Output of :start should include :result
      (let [start-output (get-in inferred [:start :output])]
        (is (some? start-output))
        (is (m/validate start-output {:result 10 :x 5}))))))

;; ===== 2. Multiple test inputs — schema covers variants =====

(deftest infer-schemas-multiple-inputs-test
  (testing "Multiple inputs produce schema covering all variants"
    (cell/defcell :test/classify
      (fn [_ data]
        (cond-> {:category (if (pos? (:x data)) :positive :negative)}
          (:label data) (assoc :label (:label data)))))
    (let [wf-def  {:cells {:start :test/classify}
                   :edges {:start :end}}
          inferred (dev/infer-schemas wf-def {}
                     [{:x 5}
                      {:x -3 :label "test"}
                      {:x 0}])]
      ;; Schema should mark :label as optional (not present in all inputs)
      (let [output-schema (get-in inferred [:start :output])]
        (is (some? output-schema))
        ;; Should validate all test outputs
        (is (m/validate output-schema {:category :positive :x 5}))
        (is (m/validate output-schema {:category :negative :x -3 :label "test"}))))))

;; ===== 3. Inferred schemas validate original test data =====

(deftest inferred-schemas-validate-test
  (testing "Inferred schemas validate against the original test data"
    (cell/defcell :test/enrich
      (fn [_ data] {:enriched true :original (:name data)}))
    (let [wf-def  {:cells {:start :test/enrich}
                   :edges {:start :end}}
          inputs  [{:name "Alice"} {:name "Bob"}]
          inferred (dev/infer-schemas wf-def {} inputs)
          output-schema (get-in inferred [:start :output])]
      ;; Run each input and verify output matches inferred schema
      (doseq [input inputs]
        (let [result (myc/run-workflow wf-def {} input)]
          (is (m/validate output-schema result)))))))

;; ===== 4. apply-inferred-schemas! updates cell registry =====

(deftest apply-inferred-schemas-test
  (testing "apply-inferred-schemas! updates cell schemas in registry"
    (cell/defcell :test/compute
      (fn [_ data] {:result (* 3 (:x data))}))
    (let [wf-def  {:cells {:start :test/compute}
                   :edges {:start :end}}
          inferred (dev/infer-schemas wf-def {} [{:x 1} {:x 2}])]
      (is (nil? (:schema (cell/get-cell :test/compute))))
      (dev/apply-inferred-schemas! inferred wf-def)
      (let [spec (cell/get-cell :test/compute)]
        (is (some? (get-in spec [:schema :input])))
        (is (some? (get-in spec [:schema :output])))))))

;; ===== 5. Round-trip: infer → apply → strict run passes =====

(deftest infer-apply-strict-roundtrip-test
  (testing "Infer → apply → strict run passes for matching data"
    (cell/defcell :test/roundtrip
      (fn [_ data] {:doubled (* 2 (:n data))}))
    (let [wf-def   {:cells {:start :test/roundtrip}
                    :edges {:start :end}}
          inferred (dev/infer-schemas wf-def {} [{:n 1} {:n 2} {:n 3}])]
      (dev/apply-inferred-schemas! inferred wf-def)
      ;; Run with strict validation — should pass
      (let [result (myc/run-workflow wf-def {} {:n 10})]
        (is (nil? (myc/workflow-error result)))
        (is (= 20 (:doubled result)))))))

;; ===== 6. Multi-cell workflow inference =====

(deftest infer-schemas-multi-cell-test
  (testing "Infers schemas across a multi-cell workflow"
    (cell/defcell :test/step-a
      (fn [_ data] {:a-out (inc (:x data))}))
    (cell/defcell :test/step-b
      (fn [_ data] {:b-out (* 2 (:a-out data))}))
    (cell/defcell :test/step-c
      (fn [_ data] {:final (str "done:" (:b-out data))}))
    (let [wf-def  {:cells {:start :test/step-a
                           :b     :test/step-b
                           :c     :test/step-c}
                   :edges {:start :b
                           :b     :c
                           :c     :end}}
          inferred (dev/infer-schemas wf-def {} [{:x 1} {:x 2}])]
      (is (= #{:start :b :c} (set (keys inferred))))
      ;; Each cell should have input and output
      (doseq [cell-name [:start :b :c]]
        (is (some? (get-in inferred [cell-name :input])) (str cell-name " missing input"))
        (is (some? (get-in inferred [cell-name :output])) (str cell-name " missing output"))))))

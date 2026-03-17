(ns mycelium.validate-warn-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(def ^:private on-error (fn [_resources fsm-state] (:data fsm-state)))

;; ===== 1. :validate :strict — current behavior unchanged =====

(deftest validate-strict-test
  (testing ":validate :strict halts on schema mismatch (default behavior)"
    (cell/defcell :test/bad-output
      {:doc    "Returns y from x but intentionally wrong"
       :input  {:x :int}
       :output {:y :int}}
      ;; Returns wrong key
      (fn [_ data] {:z 42}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/bad-output}
                    :edges {:start :end}}
                   {} {:x 1} {:on-error on-error :validate :strict})]
      (is (some? (myc/workflow-error result)))
      (is (= :schema/output (:error-type (myc/workflow-error result)))))))

;; ===== 2. :validate :warn — schema mismatch doesn't halt =====

(deftest validate-warn-continues-test
  (testing ":validate :warn continues execution on schema mismatch"
    (cell/defcell :test/warn-output
      {:doc    "Doubles x into y"
       :input  {:x :int}
       :output {:y :int}}
      ;; Returns wrong key name
      (fn [_ data] {:z (* 2 (:x data))}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/warn-output}
                    :edges {:start :end}}
                   {} {:x 5} {:validate :warn})]
      ;; Should complete without error
      (is (nil? (myc/workflow-error result)))
      ;; Result should contain the handler output
      (is (= 10 (:z result)))
      ;; Warnings should be collected
      (is (seq (:mycelium/warnings result))))))

;; ===== 3. :validate :warn — warnings accumulate across cells =====

(deftest validate-warn-accumulates-test
  (testing "Warnings accumulate across multiple cells"
    (cell/defcell :test/step-a
      {:doc    "Produces a-result from x"
       :input  {:x :int}
       :output {:a-result :int}}
      ;; Wrong key
      (fn [_ data] {:a-wrong 1}))
    (cell/defcell :test/step-b
      {:doc    "Produces b-result from x"
       :input  {:x :int}
       :output {:b-result :int}}
      ;; Also wrong key
      (fn [_ data] {:b-wrong 2}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/step-a
                            :step-b :test/step-b}
                    :edges {:start :step-b
                            :step-b :end}}
                   {} {:x 1} {:validate :warn})]
      (is (nil? (myc/workflow-error result)))
      ;; Should have warnings from both cells
      (is (>= (count (:mycelium/warnings result)) 2)))))

;; ===== 4. Warning contains diagnostic info =====

(deftest validate-warn-diagnostic-info-test
  (testing "Warning contains cell-id, phase, and key-diff info"
    (cell/defcell :test/diag
      {:doc    "Doubles x into result"
       :input  {:x :int}
       :output {:result :int}}
      (fn [_ data] {:rezult (* 2 (:x data))}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/diag}
                    :edges {:start :end}}
                   {} {:x 5} {:validate :warn})
          warning (first (:mycelium/warnings result))]
      (is (some? warning))
      (is (= :test/diag (:cell-id warning)))
      (is (= :output (:phase warning)))
      (is (some? (:message warning)))
      (is (some? (:key-diff warning))))))

;; ===== 5. :validate :off — no schema validation at all =====

(deftest validate-off-test
  (testing ":validate :off skips all schema validation"
    (cell/defcell :test/off
      {:doc    "Produces y from x"
       :input  {:x :int}
       :output {:y :int}}
      ;; Completely wrong types and keys
      (fn [_ data] {:z "not-an-int"}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/off}
                    :edges {:start :end}}
                   {} {:x 1} {:validate :off})]
      (is (nil? (myc/workflow-error result)))
      (is (= "not-an-int" (:z result)))
      ;; No warnings either
      (is (nil? (:mycelium/warnings result))))))

;; ===== 6. run-compiled accepts :validate option =====

(deftest run-compiled-validate-option-test
  (testing "run-compiled passes :validate to compilation"
    (cell/defcell :test/compiled-warn
      {:doc    "Produces y from x"
       :input  {:x :int}
       :output {:y :int}}
      (fn [_ data] {:z 42}))
    (let [compiled (myc/pre-compile
                     {:cells {:start :test/compiled-warn}
                      :edges {:start :end}}
                     {:validate :warn})
          result   (myc/run-compiled compiled {} {:x 1})]
      (is (nil? (myc/workflow-error result)))
      (is (seq (:mycelium/warnings result))))))

;; ===== 7. Input validation respects :validate mode =====

(deftest validate-warn-input-test
  (testing ":validate :warn collects input schema warnings too"
    (cell/defcell :test/input-warn
      {:doc    "Produces y from integer x"
       :input  {:x :int}
       :output {:y :int}}
      (fn [_ data] {:y 42}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/input-warn}
                    :edges {:start :end}}
                   {} {:x "not-an-int"} {:validate :warn})]
      ;; Should complete (handler still works with coercion-less wrong type)
      ;; At minimum, should not halt on schema error
      (is (nil? (:mycelium/schema-error result)))
      (is (seq (:mycelium/warnings result))))))

;; ===== 8. Default validate behavior is :strict =====

(deftest validate-default-strict-test
  (testing "Default behavior (no :validate opt) is strict"
    (cell/defcell :test/default-strict
      {:doc    "Produces y from x"
       :input  {:x :int}
       :output {:y :int}}
      (fn [_ data] {:z 42}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/default-strict}
                    :edges {:start :end}}
                   {} {:x 1} {:on-error on-error})]
      (is (some? (myc/workflow-error result))))))

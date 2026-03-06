(ns mycelium.constraints-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(defn- make-cell [id]
  (defmethod cell/cell-spec id [_]
    {:id id
     :handler (fn [_ data] (assoc data id true))
     :schema {:input [:map] :output [:map]}}))

;; ===== Round 1: :must-follow — satisfied =====

(deftest must-follow-satisfied-test
  (testing ":must-follow passes when :then cell appears after :if cell"
    (make-cell :c/flag)
    (make-cell :c/fix)
    (make-cell :c/done)

    (let [result (myc/run-workflow
                   {:cells {:start :c/flag, :fix :c/fix, :done :c/done}
                    :pipeline [:start :fix :done]
                    :constraints [{:type :must-follow, :if :start, :then :fix}]}
                   {} {})]
      (is (some? result) "Workflow compiles and runs"))))

;; ===== Round 2: :must-follow — violated =====

(deftest must-follow-violated-test
  (testing ":must-follow throws when :then cell does not follow :if cell"
    (make-cell :c/flag2)
    (make-cell :c/skip)

    (is (thrown-with-msg? Exception #"must-follow.*:start.*:skip"
          (myc/run-workflow
            {:cells {:start :c/flag2, :other :c/skip}
             :edges {:start {:a :other, :b :end}
                     :other :end}
             :dispatches {:start [[:a (fn [_] true)]
                                   [:b (fn [_] false)]]}
             :constraints [{:type :must-follow, :if :start, :then :skip}]}
            {} {})))))

;; ===== Round 3: :must-follow — cell absent (vacuous truth) =====

(deftest must-follow-cell-absent-test
  (testing ":must-follow is satisfied vacuously when :if cell is not on a path"
    (make-cell :c/a3)
    (make-cell :c/b3)
    (make-cell :c/c3)

    (let [result (myc/run-workflow
                   {:cells {:start :c/a3, :next :c/b3}
                    :pipeline [:start :next]
                    ;; :nonexistent is not on any path, so constraint is vacuously true
                    :constraints [{:type :must-follow, :if :nonexistent, :then :start}]}
                   {} {})]
      (is (some? result)))))

;; ===== Round 4: :must-precede — satisfied =====

(deftest must-precede-satisfied-test
  (testing ":must-precede passes when :cell appears before :before on all paths"
    (make-cell :c/validate)
    (make-cell :c/process)

    (let [result (myc/run-workflow
                   {:cells {:start :c/validate, :proc :c/process}
                    :pipeline [:start :proc]
                    :constraints [{:type :must-precede, :cell :start, :before :proc}]}
                   {} {})]
      (is (some? result)))))

;; ===== Round 5: :must-precede — violated =====

(deftest must-precede-violated-test
  (testing ":must-precede throws when :cell does not appear before :before"
    (make-cell :c/validate5)
    (make-cell :c/process5)
    (make-cell :c/other5)

    ;; :proc appears on a path without :validate before it
    (is (thrown-with-msg? Exception #"must-precede.*:validate.*:proc"
          (myc/run-workflow
            {:cells {:start :c/other5, :proc :c/process5, :validate :c/validate5}
             :edges {:start {:a :proc, :b :validate}
                     :proc :end
                     :validate {:done :proc}
                     }
             :dispatches {:start    [[:a (fn [_] true)] [:b (fn [_] false)]]
                          :validate [[:done (constantly true)]]}
             :constraints [{:type :must-precede, :cell :validate, :before :proc}]}
            {} {})))))

;; ===== Round 6: :never-together — satisfied =====

(deftest never-together-satisfied-test
  (testing ":never-together passes when cells are on different branches"
    (make-cell :c/manual)
    (make-cell :c/auto)
    (make-cell :c/start6)

    (let [result (myc/run-workflow
                   {:cells {:start :c/start6, :manual :c/manual, :auto :c/auto}
                    :edges {:start {:a :manual, :b :auto}
                            :manual :end, :auto :end}
                    :dispatches {:start [[:a (fn [_] true)] [:b (fn [_] false)]]}
                    :constraints [{:type :never-together, :cells [:manual :auto]}]}
                   {} {})]
      (is (some? result)))))

;; ===== Round 7: :never-together — violated =====

(deftest never-together-violated-test
  (testing ":never-together throws when both cells appear on the same path"
    (make-cell :c/manual7)
    (make-cell :c/auto7)

    (is (thrown-with-msg? Exception #"never-together"
          (myc/run-workflow
            {:cells {:start :c/manual7, :auto :c/auto7}
             :pipeline [:start :auto]
             :constraints [{:type :never-together, :cells [:start :auto]}]}
            {} {})))))

;; ===== Round 8: :always-reachable — satisfied =====

(deftest always-reachable-satisfied-test
  (testing ":always-reachable passes when cell appears on every path to :end"
    (make-cell :c/audit)
    (make-cell :c/work)

    (let [result (myc/run-workflow
                   {:cells {:start :c/work, :audit :c/audit}
                    :pipeline [:start :audit]
                    :constraints [{:type :always-reachable, :cell :audit}]}
                   {} {})]
      (is (some? result)))))

;; ===== Round 9: :always-reachable — violated =====

(deftest always-reachable-violated-test
  (testing ":always-reachable throws when a path to :end skips the cell"
    (make-cell :c/audit9)
    (make-cell :c/work9)
    (make-cell :c/skip9)

    (is (thrown-with-msg? Exception #"always-reachable.*:audit"
          (myc/run-workflow
            {:cells {:start :c/work9, :audit :c/audit9, :fast :c/skip9}
             :edges {:start {:normal :audit, :fast :fast}
                     :audit :end
                     :fast  :end}
             :dispatches {:start [[:normal (fn [_] true)] [:fast (fn [_] false)]]}
             :constraints [{:type :always-reachable, :cell :audit}]}
            {} {})))))

;; ===== Round 10: :always-reachable — ignores error/halt paths =====

(deftest always-reachable-ignores-error-halt-paths-test
  (testing ":always-reachable only checks paths to :end, not :error or :halt"
    (make-cell :c/audit10)
    (make-cell :c/work10)

    ;; Two paths: start → audit → end (has :audit), start → halt (no :audit)
    ;; :always-reachable should pass because the halt path is ignored
    (let [result (myc/run-workflow
                   {:cells {:start :c/work10, :audit :c/audit10}
                    :edges {:start {:ok :audit, :fail :halt}
                            :audit :end}
                    :dispatches {:start [[:ok (fn [_] true)] [:fail (fn [_] false)]]}
                    :constraints [{:type :always-reachable, :cell :audit}]}
                   {} {})]
      (is (some? result)))))

;; ===== Round 11: Multiple constraints =====

(deftest multiple-constraints-test
  (testing "Multiple constraints are all checked"
    (make-cell :c/a11)
    (make-cell :c/b11)

    ;; Two paths: start→b→end and start→end
    ;; First constraint passes (:b and :start on same path but that's fine for never-together with non-existent combo)
    ;; Second constraint fails: start→end has no :b after :start
    (is (thrown-with-msg? Exception #"must-follow"
          (myc/run-workflow
            {:cells {:start :c/a11, :b :c/b11}
             :edges {:start {:x :b, :y :end}
                     :b :end}
             :dispatches {:start [[:x (fn [_] true)] [:y (fn [_] false)]]}
             :constraints [{:type :never-together, :cells [:b :nonexistent]}  ;; passes (nonexistent not on any path)
                           {:type :must-follow, :if :start, :then :b}]}  ;; fails (start→end has no :b)
            {} {})))))

;; ===== Round 12: Invalid constraint type =====

(deftest invalid-constraint-type-throws-test
  (testing "Unknown constraint :type throws"
    (make-cell :c/x12)

    (is (thrown-with-msg? Exception #"[Uu]nknown constraint type"
          (myc/run-workflow
            {:cells {:start :c/x12}
             :edges {:start :end}
             :constraints [{:type :bogus}]}
            {} {})))))

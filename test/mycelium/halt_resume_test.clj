(ns mycelium.halt-resume-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Round 1: Basic halt =====

(deftest cell-can-halt-workflow-test
  (testing "Cell returning :mycelium/halt causes workflow to halt"
    (defmethod cell/cell-spec :halt/step1 [_]
      {:id      :halt/step1
       :handler (fn [_ data]
                  (assoc data :step1-done true :mycelium/halt true))
       :schema  {:input [:map [:x :int]] :output [:map [:step1-done :boolean]]}})
    (defmethod cell/cell-spec :halt/step2 [_]
      {:id      :halt/step2
       :handler (fn [_ data]
                  (assoc data :step2-done true))
       :schema  {:input [:map [:step1-done :boolean]] :output [:map [:step2-done :boolean]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :halt/step1, :next :halt/step2}
                    :edges {:start :next, :next :end}}
                   {} {:x 1})]
      (is (some? (:mycelium/halt result)) "Result should indicate halt")
      (is (some? (:mycelium/resume result)) "Result should contain resume token")
      (is (true? (:step1-done result)) "Halting cell's output should be present")
      (is (nil? (:step2-done result)) "Next cell should NOT have run"))))

;; ===== Round 2: Resume completes workflow =====

(deftest resume-continues-from-halt-point-test
  (testing "Resuming a halted workflow continues from the next cell"
    (defmethod cell/cell-spec :halt/r-step1 [_]
      {:id      :halt/r-step1
       :handler (fn [_ data]
                  (assoc data :step1-done true :mycelium/halt true))
       :schema  {:input [:map [:x :int]] :output [:map [:step1-done :boolean]]}})
    (defmethod cell/cell-spec :halt/r-step2 [_]
      {:id      :halt/r-step2
       :handler (fn [_ data]
                  (assoc data :step2-done true))
       :schema  {:input [:map [:step1-done :boolean]] :output [:map [:step2-done :boolean]]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :halt/r-step1, :next :halt/r-step2}
                      :edges {:start :next, :next :end}})
          halted (myc/run-compiled compiled {} {:x 1})
          result (myc/resume-compiled compiled {} halted)]
      (is (true? (:step1-done result)) "Data from before halt preserved")
      (is (true? (:step2-done result)) "Resumed cell ran")
      (is (nil? (:mycelium/halt result)) "Halt flag cleared after resume")
      (is (nil? (:mycelium/resume result)) "Resume token cleared after resume"))))

;; ===== Round 3: Data accumulation across halt/resume =====

(deftest data-accumulates-across-halt-resume-test
  (testing "All accumulated data from before halt is available after resume"
    (defmethod cell/cell-spec :halt/acc-a [_]
      {:id      :halt/acc-a
       :handler (fn [_ data] (assoc data :a-result 10))
       :schema  {:input [:map [:x :int]] :output [:map [:a-result :int]]}})
    (defmethod cell/cell-spec :halt/acc-b [_]
      {:id      :halt/acc-b
       :handler (fn [_ data]
                  (assoc data :b-result (* 2 (:a-result data)) :mycelium/halt true))
       :schema  {:input [:map [:a-result :int]] :output [:map [:b-result :int]]}})
    (defmethod cell/cell-spec :halt/acc-c [_]
      {:id      :halt/acc-c
       :handler (fn [_ data]
                  (assoc data :c-result (+ (:a-result data) (:b-result data))))
       :schema  {:input [:map [:a-result :int] [:b-result :int]] :output [:map [:c-result :int]]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :halt/acc-a, :mid :halt/acc-b, :fin :halt/acc-c}
                      :pipeline [:start :mid :fin]})
          halted (myc/run-compiled compiled {} {:x 1})
          result (myc/resume-compiled compiled {} halted)]
      (is (= 10 (:a-result result)))
      (is (= 20 (:b-result result)))
      (is (= 30 (:c-result result))))))

;; ===== Round 4: Resume with merge data =====

(deftest resume-with-merge-data-test
  (testing "Additional data can be merged on resume (e.g., human input)"
    (defmethod cell/cell-spec :halt/ask [_]
      {:id      :halt/ask
       :handler (fn [_ data]
                  (assoc data :question "Approve?" :mycelium/halt {:reason :need-approval}))
       :schema  {:input [:map [:x :int]] :output [:map [:question :string]]}})
    (defmethod cell/cell-spec :halt/use-answer [_]
      {:id      :halt/use-answer
       :handler (fn [_ data]
                  (assoc data :result (if (:approved data) "approved" "rejected")))
       :schema  {:input [:map] :output [:map [:result :string]]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :halt/ask, :decide :halt/use-answer}
                      :edges {:start :decide, :decide :end}})
          halted (myc/run-compiled compiled {} {:x 1})
          result (myc/resume-compiled compiled {} halted {:approved true})]
      (is (= "approved" (:result result))))))

;; ===== Round 5: Trace continuity =====

(deftest trace-continuous-across-halt-resume-test
  (testing "Trace entries from before and after halt form a continuous sequence"
    (defmethod cell/cell-spec :halt/t-a [_]
      {:id      :halt/t-a
       :handler (fn [_ data] (assoc data :a true :mycelium/halt true))
       :schema  {:input [:map] :output [:map [:a :boolean]]}})
    (defmethod cell/cell-spec :halt/t-b [_]
      {:id      :halt/t-b
       :handler (fn [_ data] (assoc data :b true))
       :schema  {:input [:map [:a :boolean]] :output [:map [:b :boolean]]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :halt/t-a, :next :halt/t-b}
                      :edges {:start :next, :next :end}})
          halted (myc/run-compiled compiled {} {})
          _ (is (= 1 (count (:mycelium/trace halted))) "Halted trace has 1 entry")
          _ (is (= :start (:cell (first (:mycelium/trace halted)))))
          result (myc/resume-compiled compiled {} halted)]
      (is (= 2 (count (:mycelium/trace result))) "Resumed trace has 2 entries")
      (is (= [:start :next] (mapv :cell (:mycelium/trace result)))))))

;; ===== Round 6: Multiple halt/resume cycles =====

(deftest multiple-halt-resume-cycles-test
  (testing "Workflow can halt and resume multiple times"
    (defmethod cell/cell-spec :halt/m-a [_]
      {:id      :halt/m-a
       :handler (fn [_ data] (assoc data :a true :mycelium/halt true))
       :schema  {:input [:map] :output [:map [:a :boolean]]}})
    (defmethod cell/cell-spec :halt/m-b [_]
      {:id      :halt/m-b
       :handler (fn [_ data] (assoc data :b true :mycelium/halt true))
       :schema  {:input [:map [:a :boolean]] :output [:map [:b :boolean]]}})
    (defmethod cell/cell-spec :halt/m-c [_]
      {:id      :halt/m-c
       :handler (fn [_ data] (assoc data :c true))
       :schema  {:input [:map [:a :boolean] [:b :boolean]] :output [:map [:c :boolean]]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :halt/m-a, :mid :halt/m-b, :fin :halt/m-c}
                      :pipeline [:start :mid :fin]})
          halted1 (myc/run-compiled compiled {} {})
          _ (is (true? (:a halted1)))
          _ (is (nil? (:b halted1)))
          halted2 (myc/resume-compiled compiled {} halted1)
          _ (is (true? (:b halted2)))
          _ (is (nil? (:c halted2)))
          _ (is (some? (:mycelium/halt halted2)) "Second halt triggered")
          result (myc/resume-compiled compiled {} halted2)]
      (is (true? (:a result)))
      (is (true? (:b result)))
      (is (true? (:c result)))
      (is (nil? (:mycelium/halt result)))
      (is (= 3 (count (:mycelium/trace result)))))))

;; ===== Round 7: Halt with custom context =====

(deftest halt-with-context-map-test
  (testing ":mycelium/halt can be a map with context for the human"
    (defmethod cell/cell-spec :halt/ctx [_]
      {:id      :halt/ctx
       :handler (fn [_ data]
                  (assoc data :mycelium/halt {:reason  :manual-review
                                              :message "Please review item"
                                              :item-id (:id data)}))
       :schema  {:input [:map [:id :string]] :output [:map]}})

    (let [result (myc/run-workflow
                   {:cells {:start :halt/ctx}
                    :edges {:start :end}}
                   {} {:id "item-42"})]
      (is (= {:reason :manual-review
              :message "Please review item"
              :item-id "item-42"}
             (:mycelium/halt result))))))

;; ===== Round 8: Resume non-halted result throws =====

(deftest resume-non-halted-throws-test
  (testing "Attempting to resume a completed (non-halted) result throws"
    (defmethod cell/cell-spec :halt/normal [_]
      {:id      :halt/normal
       :handler (fn [_ data] (assoc data :done true))
       :schema  {:input [:map] :output [:map [:done :boolean]]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :halt/normal}
                      :edges {:start :end}})
          result (myc/run-compiled compiled {} {})]
      (is (thrown-with-msg? Exception #"not a halted"
            (myc/resume-compiled compiled {} result))))))

;; ===== Round 9: Halt with branching =====

(deftest halt-with-branching-resumes-correct-branch-test
  (testing "When halt occurs after dispatch, resume goes to the correct branch"
    (defmethod cell/cell-spec :halt/branch [_]
      {:id      :halt/branch
       :handler (fn [_ data]
                  (assoc data :path (if (> (:x data) 10) :big :small)
                              :mycelium/halt true))
       :schema  {:input  [:map [:x :int]]
                 :output [:per-transition {:big   [:map [:path [:= :big]]]
                          :small [:map [:path [:= :small]]]}]}})
    (defmethod cell/cell-spec :halt/big [_]
      {:id      :halt/big
       :handler (fn [_ data] (assoc data :result "big"))
       :schema  {:input [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :halt/small [_]
      {:id      :halt/small
       :handler (fn [_ data] (assoc data :result "small"))
       :schema  {:input [:map] :output [:map [:result :string]]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :halt/branch, :big :halt/big, :small :halt/small}
                      :edges {:start {:big :big, :small :small}
                              :big :end, :small :end}
                      :dispatches {:start [[:big   (fn [d] (= :big (:path d)))]
                                           [:small (fn [d] (= :small (:path d)))]]}})
          ;; x=42 → dispatches to :big, then halts
          halted (myc/run-compiled compiled {} {:x 42})
          result (myc/resume-compiled compiled {} halted)]
      (is (= :big (:path result)))
      (is (= "big" (:result result))))))

;; ===== Round 10: Halt trace entry records halt =====

(deftest halt-trace-entry-has-halted-flag-test
  (testing "Trace entry for the halting cell includes :halted flag"
    (defmethod cell/cell-spec :halt/traced [_]
      {:id      :halt/traced
       :handler (fn [_ data] (assoc data :done true :mycelium/halt true))
       :schema  {:input [:map] :output [:map [:done :boolean]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :halt/traced}
                    :edges {:start :end}}
                   {} {})]
      (is (= 1 (count (:mycelium/trace result))))
      (is (true? (:halted (first (:mycelium/trace result))))))))

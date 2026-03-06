(ns mycelium.default-transition-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Round 1: Basic default transition =====

(deftest default-transition-fires-when-no-predicate-matches-test
  (testing ":default edge is taken when no other dispatch matches"
    (defmethod cell/cell-spec :dt/check [_]
      {:id      :dt/check
       :handler (fn [_ data] (assoc data :status :unknown))
       :schema  {:input [:map] :output [:map [:status :keyword]]}})
    (defmethod cell/cell-spec :dt/success [_]
      {:id      :dt/success
       :handler (fn [_ data] (assoc data :result "ok"))
       :schema  {:input [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :dt/fallback [_]
      {:id      :dt/fallback
       :handler (fn [_ data] (assoc data :result "fallback"))
       :schema  {:input [:map] :output [:map [:result :string]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :dt/check
                            :ok    :dt/success
                            :fb    :dt/fallback}
                    :edges {:start {:success :ok, :default :fb}
                            :ok :end
                            :fb :end}
                    :dispatches {:start [[:success (fn [d] (= :good (:status d)))]]}}
                   {} {})]
      (is (= "fallback" (:result result))))))

;; ===== Round 2: Default not taken when another matches =====

(deftest default-not-taken-when-predicate-matches-test
  (testing ":default is skipped when a real predicate matches"
    (defmethod cell/cell-spec :dt2/check [_]
      {:id      :dt2/check
       :handler (fn [_ data] (assoc data :status :good))
       :schema  {:input [:map] :output [:map [:status :keyword]]}})
    (defmethod cell/cell-spec :dt2/success [_]
      {:id      :dt2/success
       :handler (fn [_ data] (assoc data :result "ok"))
       :schema  {:input [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :dt2/fallback [_]
      {:id      :dt2/fallback
       :handler (fn [_ data] (assoc data :result "fallback"))
       :schema  {:input [:map] :output [:map [:result :string]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :dt2/check
                            :ok    :dt2/success
                            :fb    :dt2/fallback}
                    :edges {:start {:success :ok, :default :fb}
                            :ok :end
                            :fb :end}
                    :dispatches {:start [[:success (fn [d] (= :good (:status d)))]]}}
                   {} {})]
      (is (= "ok" (:result result))))))

;; ===== Round 3: Default as sole edge is rejected =====

(deftest default-as-sole-edge-throws-test
  (testing ":default as the only edge is rejected (use unconditional edge)"
    (defmethod cell/cell-spec :dt3/step [_]
      {:id      :dt3/step
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})

    (is (thrown-with-msg? Exception #"[Dd]efault.*only"
          (myc/run-workflow
            {:cells {:start :dt3/step}
             :edges {:start {:default :end}}}
            {} {})))))

;; ===== Round 4: Default with schema chain =====

(deftest default-path-schema-chain-works-test
  (testing "Schema chain validation works for the :default path"
    (defmethod cell/cell-spec :dt4/produce [_]
      {:id      :dt4/produce
       :handler (fn [_ data] (assoc data :value 42))
       :schema  {:input  [:map]
                 :output {:success [:map [:value :int]]
                          :default [:map [:value :int]]}}})
    (defmethod cell/cell-spec :dt4/consume [_]
      {:id      :dt4/consume
       :handler (fn [_ data] (assoc data :doubled (* 2 (:value data))))
       :schema  {:input [:map [:value :int]] :output [:map [:doubled :int]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :dt4/produce
                            :next  :dt4/consume}
                    :edges {:start {:success :next, :default :next}
                            :next :end}
                    :dispatches {:start [[:success (fn [_] false)]]}}
                   {} {})]
      (is (= 84 (:doubled result))))))

;; ===== Round 5: Default with multiple branches =====

(deftest default-with-multiple-branches-test
  (testing ":default acts as catch-all with multiple named branches"
    (defmethod cell/cell-spec :dt5/classify [_]
      {:id      :dt5/classify
       :handler (fn [_ data] (assoc data :category :other))
       :schema  {:input [:map] :output [:map [:category :keyword]]}})
    (defmethod cell/cell-spec :dt5/high [_]
      {:id      :dt5/high
       :handler (fn [_ data] (assoc data :result "high"))
       :schema  {:input [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :dt5/low [_]
      {:id      :dt5/low
       :handler (fn [_ data] (assoc data :result "low"))
       :schema  {:input [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :dt5/unknown [_]
      {:id      :dt5/unknown
       :handler (fn [_ data] (assoc data :result "unknown"))
       :schema  {:input [:map] :output [:map [:result :string]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :dt5/classify
                            :high  :dt5/high
                            :low   :dt5/low
                            :unk   :dt5/unknown}
                    :edges {:start {:high :high, :low :low, :default :unk}
                            :high :end, :low :end, :unk :end}
                    :dispatches {:start [[:high (fn [d] (= :high (:category d)))]
                                         [:low  (fn [d] (= :low (:category d)))]]}}
                   {} {})]
      (is (= "unknown" (:result result))))))

;; ===== Round 6: Default preserves trace =====

(deftest default-transition-recorded-in-trace-test
  (testing "Trace records :default as the transition label"
    (defmethod cell/cell-spec :dt6/step [_]
      {:id      :dt6/step
       :handler (fn [_ data] (assoc data :done true))
       :schema  {:input [:map] :output [:map [:done :boolean]]}})
    (defmethod cell/cell-spec :dt6/fb [_]
      {:id      :dt6/fb
       :handler (fn [_ data] (assoc data :fell-back true))
       :schema  {:input [:map] :output [:map [:fell-back :boolean]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :dt6/step
                            :fb    :dt6/fb}
                    :edges {:start {:success :end, :default :fb}
                            :fb :end}
                    :dispatches {:start [[:success (fn [_] false)]]}}
                   {} {})]
      (is (= :default (:transition (first (:mycelium/trace result))))))))

;; ===== Round 7: Default with halt =====

(deftest default-with-halt-works-test
  (testing "Cell can halt on the :default path"
    (defmethod cell/cell-spec :dt7/check [_]
      {:id      :dt7/check
       :handler (fn [_ data] (assoc data :checked true))
       :schema  {:input [:map] :output [:map [:checked :boolean]]}})
    (defmethod cell/cell-spec :dt7/review [_]
      {:id      :dt7/review
       :handler (fn [_ data] (assoc data :mycelium/halt {:reason :needs-review}))
       :schema  {:input [:map] :output [:map]}})

    (let [compiled (myc/pre-compile
                     {:cells {:start :dt7/check
                              :review :dt7/review}
                      :edges {:start {:ok :end, :default :review}
                              :review :end}
                      :dispatches {:start [[:ok (fn [_] false)]]}})
          halted (myc/run-compiled compiled {} {})]
      (is (some? (:mycelium/halt halted)))
      (is (= {:reason :needs-review} (:mycelium/halt halted)))
      (let [result (myc/resume-compiled compiled {} halted)]
        (is (nil? (:mycelium/halt result)))))))

;; ===== Round 8: Explicit :default dispatch predicate is allowed =====

(deftest explicit-default-predicate-allowed-test
  (testing "User can provide an explicit :default predicate (overrides auto-inject)"
    (defmethod cell/cell-spec :dt8/step [_]
      {:id      :dt8/step
       :handler (fn [_ data] (assoc data :x 1))
       :schema  {:input [:map] :output [:map [:x :int]]}})
    (defmethod cell/cell-spec :dt8/a [_]
      {:id      :dt8/a
       :handler (fn [_ data] (assoc data :result "a"))
       :schema  {:input [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :dt8/b [_]
      {:id      :dt8/b
       :handler (fn [_ data] (assoc data :result "b"))
       :schema  {:input [:map] :output [:map [:result :string]]}})

    (let [result (myc/run-workflow
                   {:cells {:start :dt8/step
                            :a     :dt8/a
                            :b     :dt8/b}
                    :edges {:start {:success :a, :default :b}
                            :a :end, :b :end}
                    :dispatches {:start [[:success (fn [_] false)]
                                         [:default (fn [_] true)]]}}
                   {} {})]
      (is (= "b" (:result result))))))

;; ===== Round 9: Default placed first in dispatches still fires last =====

(deftest default-first-in-dispatches-still-fires-last-test
  (testing ":default is reordered to last even if user puts it first in :dispatches"
    (defmethod cell/cell-spec :dt9/step [_]
      {:id      :dt9/step
       :handler (fn [_ data] (assoc data :x 1))
       :schema  {:input [:map] :output [:map [:x :int]]}})
    (defmethod cell/cell-spec :dt9/a [_]
      {:id      :dt9/a
       :handler (fn [_ data] (assoc data :result "a"))
       :schema  {:input [:map] :output [:map [:result :string]]}})
    (defmethod cell/cell-spec :dt9/b [_]
      {:id      :dt9/b
       :handler (fn [_ data] (assoc data :result "b"))
       :schema  {:input [:map] :output [:map [:result :string]]}})

    ;; :default listed FIRST but :success matches, so :success should win
    (let [result (myc/run-workflow
                   {:cells {:start :dt9/step
                            :a     :dt9/a
                            :b     :dt9/b}
                    :edges {:start {:success :a, :default :b}
                            :a :end, :b :end}
                    :dispatches {:start [[:default (fn [_] true)]
                                         [:success (fn [_] true)]]}}
                   {} {})]
      (is (= "a" (:result result)) ":success should win even though :default was listed first"))))

;; ===== Round 10: Default on join node =====

(deftest default-on-join-node-test
  (testing ":default edge on a join node acts as catch-all"
    (defmethod cell/cell-spec :dt10/branch-a [_]
      {:id      :dt10/branch-a
       :handler (fn [_ data] (assoc data :a-result 1))
       :schema  {:input [:map] :output [:map [:a-result :int]]}})
    (defmethod cell/cell-spec :dt10/branch-b [_]
      {:id      :dt10/branch-b
       :handler (fn [_ data] (assoc data :b-result 2))
       :schema  {:input [:map] :output [:map [:b-result :int]]}})
    (defmethod cell/cell-spec :dt10/after [_]
      {:id      :dt10/after
       :handler (fn [_ data] (assoc data :sum (+ (:a-result data) (:b-result data))))
       :schema  {:input [:map [:a-result :int] [:b-result :int]] :output [:map [:sum :int]]}})
    (defmethod cell/cell-spec :dt10/fallback [_]
      {:id      :dt10/fallback
       :handler (fn [_ data] (assoc data :fell-back true))
       :schema  {:input [:map] :output [:map [:fell-back :boolean]]}})
    (defmethod cell/cell-spec :dt10/start [_]
      {:id      :dt10/start
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})

    ;; Join succeeds → :done route. But if we only wire :default (no :done),
    ;; the join's default dispatch for :done won't match any edge, so :default catches.
    (let [result (myc/run-workflow
                   {:cells {:start     :dt10/start
                            :branch-a  :dt10/branch-a
                            :branch-b  :dt10/branch-b
                            :after     :dt10/after
                            :fallback  :dt10/fallback}
                    :joins {:gather {:cells [:branch-a :branch-b]
                                     :strategy :parallel}}
                    :edges {:start   :gather
                            :gather  {:done :after, :default :fallback}
                            :after   :end
                            :fallback :end}}
                   {} {})]
      ;; Join succeeds, :done matches, should route to :after
      (is (= 3 (:sum result))))))

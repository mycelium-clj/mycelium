(ns mycelium.dev-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. test-cell passes with valid handler =====

(deftest test-cell-passes-valid-test
  (testing "test-cell passes with valid handler → {:pass? true}"
    (defmethod cell/cell-spec :dev/good-cell [_]
      {:id          :dev/good-cell
       :handler     (fn [_ data]
                      (assoc data :y (* 2 (:x data))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}})

    (let [result (dev/test-cell :dev/good-cell
                                {:input {:x 5}
                                 :resources {}})]
      (is (true? (:pass? result)))
      (is (= 10 (get-in result [:output :y])))
      (is (number? (:duration-ms result))))))

;; ===== 2. test-cell reports failure with bad output =====

(deftest test-cell-reports-failure-test
  (testing "test-cell reports failure with bad output → {:pass? false :errors [...]}"
    (defmethod cell/cell-spec :dev/bad-cell [_]
      {:id          :dev/bad-cell
       :handler     (fn [_ data]
                      ;; Returns :y as int instead of string
                      (assoc data :y 42))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :string]]}})

    (let [result (dev/test-cell :dev/bad-cell
                                {:input {:x 5}
                                 :resources {}})]
      (is (false? (:pass? result)))
      (is (seq (:errors result))))))

;; ===== 3. workflow->dot produces valid DOT graph string =====

(deftest workflow-to-dot-test
  (testing "workflow->dot produces valid DOT graph string"
    (let [manifest {:id :test/wf
                    :cells {:start   {:id :test/a}
                            :process {:id :test/b}}
                    :edges {:start   {:success :process, :failure :error}
                            :process {:done :end}}}
          dot (dev/workflow->dot manifest)]
      (is (string? dot))
      (is (re-find #"digraph" dot))
      (is (re-find #"\"start\"" dot))
      (is (re-find #"\"process\"" dot))
      (is (re-find #"success" dot))
      (is (re-find #"\"halt\"" dot)))))

(deftest workflow-to-dot-handles-hyphens-test
  (testing "workflow->dot quotes identifiers with hyphens"
    (let [manifest {:cells {:start {:id :test/a}
                            :fetch-profile {:id :test/b}}
                    :edges {:start {:ok :fetch-profile}
                            :fetch-profile {:done :end}}}
          dot (dev/workflow->dot manifest)]
      (is (re-find #"\"fetch-profile\"" dot)))))

;; ===== test-cell with :dispatches option =====

(deftest test-cell-with-dispatches-test
  (testing "test-cell with :dispatches evaluates predicates and reports matched edge"
    (defmethod cell/cell-spec :dev/dispatch-cell [_]
      {:id          :dev/dispatch-cell
       :handler     (fn [_ data]
                      (if (= (:id data) "alice")
                        (assoc data :profile {:name "Alice"})
                        (assoc data :error-message "Not found")))
       :schema      {:input [:map [:id :string]]
                     :output [:per-transition {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}]}})
    (let [dispatches [[:found     (fn [d] (:profile d))]
                      [:not-found (fn [d] (:error-message d))]]
          result (dev/test-cell :dev/dispatch-cell
                                {:input {:id "alice"}
                                 :dispatches dispatches})]
      (is (true? (:pass? result)))
      (is (= :found (:matched-dispatch result))))))

(deftest test-cell-with-dispatches-failure-test
  (testing "test-cell with :dispatches reports :not-found dispatch for not-found data"
    (defmethod cell/cell-spec :dev/dispatch-cell2 [_]
      {:id          :dev/dispatch-cell2
       :handler     (fn [_ data]
                      (assoc data :error-message "Not found"))
       :schema      {:input [:map [:id :string]]
                     :output [:per-transition {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}]}})
    (let [dispatches [[:found     (fn [d] (:profile d))]
                      [:not-found (fn [d] (:error-message d))]]
          result (dev/test-cell :dev/dispatch-cell2
                                {:input {:id "nobody"}
                                 :dispatches dispatches})]
      (is (true? (:pass? result)))
      (is (= :not-found (:matched-dispatch result))))))

(deftest test-cell-with-expected-dispatch-test
  (testing "test-cell with :expected-dispatch checks dispatch matches"
    (defmethod cell/cell-spec :dev/exp-dispatch [_]
      {:id          :dev/exp-dispatch
       :handler     (fn [_ data]
                      (assoc data :profile {:name "Alice"}))
       :schema      {:input [:map [:id :string]]
                     :output [:per-transition {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}]}})
    (let [dispatches [[:found     (fn [d] (:profile d))]
                      [:not-found (fn [d] (:error-message d))]]]
      ;; Correct expectation
      (let [result (dev/test-cell :dev/exp-dispatch
                                  {:input {:id "alice"}
                                   :dispatches dispatches
                                   :expected-dispatch :found})]
        (is (true? (:pass? result))))
      ;; Wrong expectation
      (let [result (dev/test-cell :dev/exp-dispatch
                                  {:input {:id "alice"}
                                   :dispatches dispatches
                                   :expected-dispatch :not-found})]
        (is (false? (:pass? result)))
        (is (some #(= :dispatch (:phase %)) (:errors result)))))))

(deftest test-cell-per-transition-schema-test
  (testing "test-cell validates against per-transition schema via dispatches"
    (defmethod cell/cell-spec :dev/pt-cell [_]
      {:id          :dev/pt-cell
       :handler     (fn [_ data]
                      (assoc data :profile {:name "Alice"}))
       :schema      {:input [:map [:id :string]]
                     :output [:per-transition {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}]}})
    (let [dispatches [[:found     (fn [d] (:profile d))]
                      [:not-found (fn [d] (:error-message d))]]
          result (dev/test-cell :dev/pt-cell
                                {:input {:id "alice"}
                                 :dispatches dispatches})]
      (is (true? (:pass? result))))))

;; ===== test-transitions with dispatches =====

(deftest test-transitions-with-dispatches-test
  (testing "test-transitions tests multiple dispatch paths"
    (defmethod cell/cell-spec :dev/multi-cell [_]
      {:id          :dev/multi-cell
       :handler     (fn [_ data]
                      (if (= (:id data) "alice")
                        (assoc data :profile {:name "Alice"})
                        (assoc data :error-message "Not found")))
       :schema      {:input [:map [:id :string]]
                     :output [:per-transition {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}]}})
    (let [dispatches [[:found     (fn [d] (:profile d))]
                      [:not-found (fn [d] (:error-message d))]]
          results (dev/test-transitions :dev/multi-cell
                    {:found     {:input {:id "alice"}
                                 :dispatches dispatches}
                     :not-found {:input {:id "nobody"}
                                 :dispatches dispatches}})]
      (is (true? (get-in results [:found :pass?])))
      (is (true? (get-in results [:not-found :pass?])))
      (is (= :found (get-in results [:found :matched-dispatch])))
      (is (= :not-found (get-in results [:not-found :matched-dispatch]))))))

;; ===== test-transitions without dispatches =====

(deftest test-transitions-no-dispatches-test
  (testing "test-transitions works for cells with no dispatch predicates (output-only check)"
    (defmethod cell/cell-spec :dev/no-dispatch [_]
      {:id      :dev/no-dispatch
       :handler (fn [_ data]
                  (assoc data :risk-level
                         (cond (>= (:score data) 700) :low
                               (>= (:score data) 600) :medium
                               :else                   :high)))
       :schema  {:input [:map [:score :int]]
                 :output [:map [:risk-level [:enum :low :medium :high]]]}})
    (let [results (dev/test-transitions :dev/no-dispatch
                    {:low    {:input {:score 750}}
                     :medium {:input {:score 650}}
                     :high   {:input {:score 400}}})]
      (is (true? (get-in results [:low :pass?])))
      (is (true? (get-in results [:medium :pass?])))
      (is (true? (get-in results [:high :pass?])))
      (is (= :low (get-in results [:low :output :risk-level])))
      (is (= :medium (get-in results [:medium :output :risk-level])))
      (is (= :high (get-in results [:high :output :risk-level]))))))

;; ===== enumerate-paths =====

(deftest enumerate-paths-test
  (testing "enumerate-paths lists all paths through a workflow"
    (let [manifest {:cells {:start  {:id :test/a}
                            :step-b {:id :test/b}
                            :step-c {:id :test/c}}
                    :edges {:start  {:ok :step-b, :fail :step-c}
                            :step-b {:done :end}
                            :step-c {:done :end}}}
          paths (dev/enumerate-paths manifest)]
      ;; Should have 2 paths: start→step-b→end and start→step-c→end
      (is (= 2 (count paths)))
      ;; Each path should be a vector of steps
      (is (every? vector? paths))
      ;; Check first step in each path is start
      (is (every? #(= :start (:cell (first %))) paths)))))

(deftest enumerate-paths-cycle-terminates-test
  (testing "enumerate-paths terminates on cyclic workflows (loop edges)"
    (let [manifest {:cells {:start {:id :test/a}}
                    :edges {:start {:again :start, :done :end}}}
          paths (dev/enumerate-paths manifest)]
      ;; Should produce exactly 1 path: start→end via :done
      ;; The :again→:start cycle is detected and pruned
      (is (= 1 (count paths)))
      (is (= :done (:transition (first (first paths))))))))

;; ===== analyze-workflow =====

(deftest analyze-workflow-healthy-test
  (testing "analyze-workflow reports healthy workflow with no issues"
    (defmethod cell/cell-spec :dev/analyze-a [_]
      {:id      :dev/analyze-a
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map] :output [:map [:a :int]]}})
    (defmethod cell/cell-spec :dev/analyze-b [_]
      {:id      :dev/analyze-b
       :handler (fn [_ data] (assoc data :b 2))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})
    (let [analysis (dev/analyze-workflow
                    {:cells {:start :dev/analyze-a
                             :step2 :dev/analyze-b}
                     :edges {:start {:next :step2}
                             :step2 {:done :end}}
                     :dispatches {:start [[:next (constantly true)]]
                                  :step2 [[:done (constantly true)]]}})]
      (is (contains? (:reachable analysis) :start))
      (is (contains? (:reachable analysis) :step2))
      (is (empty? (:unreachable analysis)))
      (is (empty? (:no-path-to-end analysis)))
      (is (empty? (:cycles analysis))))))

(deftest analyze-workflow-detects-cycles-test
  (testing "analyze-workflow detects cycles in the workflow graph"
    (defmethod cell/cell-spec :dev/cycle-cell [_]
      {:id      :dev/cycle-cell
       :handler (fn [_ data] (update data :count (fnil inc 0)))
       :schema  {:input [:map] :output [:map [:count :int]]}})
    (let [analysis (dev/analyze-workflow
                    {:cells {:start :dev/cycle-cell}
                     :edges {:start {:again :start, :done :end}}
                     :dispatches {:start [[:again (fn [d] (< (:count d 0) 3))]
                                          [:done  (fn [d] (>= (:count d 0) 3))]]}})]
      ;; The self-loop :start → :start is a cycle
      (is (seq (:cycles analysis))))))

(deftest analyze-workflow-multi-node-cycle-test
  (testing "analyze-workflow detects multi-node cycles (A→B→A)"
    (defmethod cell/cell-spec :dev/cycle-a [_]
      {:id      :dev/cycle-a
       :handler (fn [_ data] (update data :n (fnil inc 0)))
       :schema  {:input [:map] :output [:map [:n :int]]}})
    (defmethod cell/cell-spec :dev/cycle-b [_]
      {:id      :dev/cycle-b
       :handler (fn [_ data] (update data :n inc))
       :schema  {:input [:map [:n :int]] :output [:map [:n :int]]}})
    (let [analysis (dev/analyze-workflow
                    {:cells {:start  :dev/cycle-a
                             :step-b :dev/cycle-b}
                     :edges {:start  {:next :step-b}
                             :step-b {:loop :start, :done :end}}
                     :dispatches {:start  [[:next (constantly true)]]
                                  :step-b [[:loop (fn [d] (< (:n d) 4))]
                                            [:done (fn [d] (>= (:n d) 4))]]}})]
      ;; Multi-node cycle: start → step-b → start
      (is (seq (:cycles analysis)))
      ;; Cycle entries should use cell names, not resolved state IDs
      (let [cycle-names (set (flatten (:cycles analysis)))]
        (is (contains? cycle-names :start))
        (is (contains? cycle-names :step-b))))))

(deftest analyze-workflow-no-path-to-end-test
  (testing "analyze-workflow reports states with no path to end"
    (defmethod cell/cell-spec :dev/npe-router [_]
      {:id      :dev/npe-router
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :dev/npe-dead [_]
      {:id      :dev/npe-dead
       :handler (fn [_ data] data)
       :schema  {:input [:map] :output [:map]}})
    (let [analysis (dev/analyze-workflow
                    {:cells {:start   :dev/npe-router
                             :dead-end :dev/npe-dead}
                     :edges {:start    {:ok :dead-end, :skip :end}
                             :dead-end :halt}
                     :dispatches {:start [[:ok (fn [_] true)]
                                           [:skip (fn [_] false)]]}})]
      (is (contains? (:no-path-to-end analysis) :dead-end))
      ;; :start does have a path to end via :skip
      (is (not (contains? (:no-path-to-end analysis) :start))))))

;; ===== infer-workflow-schema =====

(deftest infer-workflow-schema-linear-test
  (testing "infer-workflow-schema shows key accumulation through a linear workflow"
    (defmethod cell/cell-spec :dev/schema-a [_]
      {:id      :dev/schema-a
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})
    (defmethod cell/cell-spec :dev/schema-b [_]
      {:id      :dev/schema-b
       :handler (fn [_ data] (assoc data :b 2))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})

    (let [result (dev/infer-workflow-schema
                  {:cells {:start :dev/schema-a
                           :step2 :dev/schema-b}
                   :edges {:start :step2
                           :step2 :end}
                   :dispatches {}})]
      ;; :start should have :x from input
      (is (contains? (:available-before (get result :start)) :x))
      ;; :start adds :a
      (is (contains? (:adds (get result :start)) :a))
      ;; :step2 should have :x and :a available
      (is (contains? (:available-before (get result :step2)) :x))
      (is (contains? (:available-before (get result :step2)) :a))
      ;; :step2 adds :b
      (is (contains? (:adds (get result :step2)) :b))
      ;; :step2 available-after includes all three
      (is (= #{:x :a :b} (:available-after (get result :step2)))))))

(deftest infer-workflow-schema-branching-test
  (testing "infer-workflow-schema handles branching correctly"
    (defmethod cell/cell-spec :dev/schema-router [_]
      {:id      :dev/schema-router
       :handler (fn [_ data] data)
       :schema  {:input [:map [:x :int]]
                 :output [:per-transition {:ok [:map [:a :int]] :fail [:map [:err :string]]}]}})
    (defmethod cell/cell-spec :dev/schema-leaf [_]
      {:id      :dev/schema-leaf
       :handler (fn [_ data] (assoc data :done true))
       :schema  {:input [:map [:a :int]] :output [:map [:done :boolean]]}})

    (let [result (dev/infer-workflow-schema
                  {:cells {:start :dev/schema-router
                           :leaf  :dev/schema-leaf}
                   :edges {:start {:ok :leaf, :fail :end}
                           :leaf  :end}
                   :dispatches {:start [[:ok (fn [d] (:a d))]
                                        [:fail (fn [d] (:err d))]]}})]
      (is (some? (get result :start)))
      (is (some? (get result :leaf))))))

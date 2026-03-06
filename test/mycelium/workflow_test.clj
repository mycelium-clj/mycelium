(ns mycelium.workflow-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Helpers ---
;; Handlers no longer set :mycelium/transition — they just compute data.
;; Dispatch predicates in the workflow definition determine routing.
(defn- register-cells! []
  (defmethod cell/cell-spec :test/cell-a [_]
    {:id :test/cell-a
     :handler (fn [_ data] (assoc data :a-done true))
     :schema {:input [:map [:x :int]]
              :output [:map [:a-done :boolean]]}})
  (defmethod cell/cell-spec :test/cell-b [_]
    {:id :test/cell-b
     :handler (fn [_ data] (assoc data :b-done true))
     :schema {:input [:map [:a-done :boolean]]
              :output [:map [:b-done :boolean]]}})
  (defmethod cell/cell-spec :test/cell-c [_]
    {:id :test/cell-c
     :handler (fn [_ data] (assoc data :c-done true))
     :schema {:input [:map [:b-done :boolean]]
              :output [:map [:c-done :boolean]]}})
  (defmethod cell/cell-spec :test/cell-d [_]
    {:id :test/cell-d
     :handler (fn [_ data] (assoc data :d-done true))
     :schema {:input [:map [:a-done :boolean]]
              :output [:map [:d-done :boolean]]}}))

;; ===== resolve-state-id tests =====

(deftest resolve-state-id-test
  (testing "resolve-state-id maps special keywords to Maestro reserved states"
    (is (= ::fsm/start (wf/resolve-state-id :start)))
    (is (= ::fsm/end (wf/resolve-state-id :end)))
    (is (= ::fsm/error (wf/resolve-state-id :error)))
    (is (= ::fsm/halt (wf/resolve-state-id :halt))))
  (testing "resolve-state-id namespaces plain keywords"
    (let [resolved (wf/resolve-state-id :validate)]
      (is (qualified-keyword? resolved))
      (is (= "mycelium.workflow" (namespace resolved))))))

;; ===== compile-edges tests =====

(deftest compile-edges-with-dispatch-map-test
  (testing "compile-edges with dispatch map → predicates route based on data, not :mycelium/transition"
    (let [edges {:success :validate, :failure :error}
          dispatch-map [[:success (fn [data] (:a-done data))]
                        [:failure (fn [data] (not (:a-done data)))]]
          dispatches (wf/compile-edges edges dispatch-map)]
      (is (= 2 (count dispatches)))
      ;; Each dispatch is [target-id pred-fn]
      (let [[target pred] (first dispatches)]
        (is (qualified-keyword? target))
        ;; Predicate checks data, not :mycelium/transition
        (is (true? (pred {:a-done true})))
        (is (not (pred {:a-done false})))))))

(deftest compile-edges-keyword-test
  (testing "compile-edges with keyword → unconditional dispatch (no dispatch map needed)"
    (let [dispatches (wf/compile-edges :end nil)]
      (is (= 1 (count dispatches)))
      (let [[target pred] (first dispatches)]
        (is (= ::fsm/end target))
        (is (true? (pred {})))
        (is (true? (pred {:anything "works"})))))))

(deftest compile-edges-missing-dispatch-throws-test
  (testing "compile-edges throws when dispatch map missing a label"
    (is (thrown-with-msg? Exception #"No edge target for dispatch label"
          (wf/compile-edges {:success :end, :failure :error}
                            [[:success (fn [data] (:ok data))]
                             [:oops (fn [_] true)]])))))

;; ===== Linear workflow compilation =====

(deftest linear-workflow-compiles-test
  (testing "Linear workflow (A→B→C→end) compiles with :dispatches"
    (register-cells!)
    (let [workflow {:cells {:start  :test/cell-a
                           :step-b :test/cell-b
                           :step-c :test/cell-c}
                    :edges {:start  {:success :step-b, :failure :error}
                            :step-b {:success :step-c}
                            :step-c {:done :end}}
                    :dispatches {:start  [[:success (fn [data] (:a-done data))]
                                          [:failure (fn [data] (not (:a-done data)))]]
                                 :step-b [[:success (constantly true)]]
                                 :step-c [[:done (constantly true)]]}}
          compiled (wf/compile-workflow workflow)]
      (is (some? compiled))
      (is (map? compiled)))))

;; ===== Validation: missing cell =====

(deftest validate-catches-missing-cell-test
  (testing "Validate catches missing cell in registry"
    ;; Don't register any cells
    (is (thrown-with-msg? Exception #"not found"
          (wf/compile-workflow
           {:cells {:start :test/nonexistent}
            :edges {:start :end}})))))

;; ===== Validation: unreachable cell =====

(deftest validate-catches-unreachable-cell-test
  (testing "Validate catches unreachable cell"
    (register-cells!)
    (is (thrown-with-msg? Exception #"[Uu]nreachable"
          (wf/compile-workflow
           {:cells {:start    :test/cell-a
                    :step-b   :test/cell-b
                    :orphan   :test/cell-c}
            :edges {:start  {:success :step-b, :failure :error}
                    :step-b {:success :end}}
            :dispatches {:start  [[:success (fn [d] (:a-done d))]
                                  [:failure (fn [d] (not (:a-done d)))]]
                         :step-b [[:success (constantly true)]]}})))))

;; ===== Validation: missing dispatch for edge =====

(deftest validate-catches-missing-dispatch-test
  (testing "Validate catches missing dispatch for map edge"
    (register-cells!)
    ;; edges has :success and :failure but dispatches only has :success
    (is (thrown-with-msg? Exception #"[Dd]ispatch"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :edges {:start {:success :end, :failure :error}}
            :dispatches {:start [[:success (fn [d] (:a-done d))]]}})))))

(deftest validate-catches-extra-dispatch-test
  (testing "Validate catches extra dispatch key not in edges"
    (register-cells!)
    (is (thrown-with-msg? Exception #"[Dd]ispatch"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :edges {:start {:success :end}}
            :dispatches {:start [[:success (fn [d] (:a-done d))]
                                 [:extra   (fn [d] (:x d))]]}})))))

(deftest validate-no-dispatches-for-map-edges-test
  (testing "Validate catches map edges with no :dispatches key at all"
    (register-cells!)
    (is (thrown-with-msg? Exception #"[Dd]ispatch"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :edges {:start {:success :end, :failure :error}}})))))

;; ===== Schema chain validation =====

(deftest schema-chain-valid-test
  (testing "Schema chain validation passes valid chain"
    (register-cells!)
    (let [workflow {:cells {:start  :test/cell-a
                            :step-b :test/cell-b
                            :step-c :test/cell-c}
                    :edges {:start  {:success :step-b, :failure :error}
                            :step-b {:success :step-c}
                            :step-c {:done :end}}
                    :dispatches {:start  [[:success (fn [d] (:a-done d))]
                                          [:failure (fn [d] (not (:a-done d)))]]
                                 :step-b [[:success (constantly true)]]
                                 :step-c [[:done (constantly true)]]}}]
      ;; Should not throw
      (is (some? (wf/compile-workflow workflow))))))

(deftest schema-chain-catches-missing-key-test
  (testing "Schema chain validation catches missing upstream key with detailed error"
    (defmethod cell/cell-spec :test/needs-z [_]
      {:id :test/needs-z
       :handler (fn [_ data] (assoc data :w true))
       :schema {:input [:map [:z :string]]
                :output [:map [:w :boolean]]}})
    (defmethod cell/cell-spec :test/produces-a [_]
      {:id :test/produces-a
       :handler (fn [_ data] (assoc data :a-val 1))
       :schema {:input [:map [:x :int]]
                :output [:map [:a-val :int]]}})
    (is (thrown-with-msg? Exception #"[Ss]chema chain"
          (wf/compile-workflow
           {:cells {:start  :test/produces-a
                    :step-2 :test/needs-z}
            :edges {:start  {:next :step-2}
                    :step-2 {:ok :end}}
            :dispatches {:start  [[:next (constantly true)]]
                         :step-2 [[:ok (constantly true)]]}})))))

;; ===== Branching workflow =====

(deftest branching-workflow-compiles-test
  (testing "Branching workflow compiles correctly with dispatches"
    (register-cells!)
    (let [workflow {:cells {:start  :test/cell-a
                            :path-b :test/cell-b
                            :path-d :test/cell-d}
                    :edges {:start  {:success :path-b, :failure :path-d}
                            :path-b {:success :end}
                            :path-d {:done :end}}
                    :dispatches {:start  [[:success (fn [d] (:a-done d))]
                                          [:failure (fn [d] (not (:a-done d)))]]
                                 :path-b [[:success (constantly true)]]
                                 :path-d [[:done (constantly true)]]}}
          compiled (wf/compile-workflow workflow)]
      (is (some? compiled)))))

;; ===== Invalid edge target =====

(deftest invalid-edge-target-test
  (testing "Invalid edge target (referencing non-existent cell name) fails"
    (register-cells!)
    (is (thrown-with-msg? Exception #"[Ii]nvalid edge target"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :edges {:start {:success :nonexistent-cell, :failure :error}}
            :dispatches {:start [[:success (fn [d] (:a-done d))]
                                 [:failure (fn [d] (not (:a-done d)))]]}})))))

;; ===== Per-transition schema chain validation =====

(defn- register-per-transition-cells! []
  (defmethod cell/cell-spec :test/lookup [_]
    {:id :test/lookup
     :handler (fn [_ data]
                (if (= (:id data) "alice")
                  (assoc data :profile {:name "Alice"})
                  (assoc data :error-message "Not found")))
     :schema {:input [:map [:id :string]]
              :output {:found     [:map [:profile [:map [:name :string]]]]
                       :not-found [:map [:error-message :string]]}}})
  (defmethod cell/cell-spec :test/render-profile [_]
    {:id :test/render-profile
     :handler (fn [_ data] (assoc data :html "ok"))
     :schema {:input [:map [:profile [:map [:name :string]]]]
              :output [:map [:html :string]]}})
  (defmethod cell/cell-spec :test/render-error [_]
    {:id :test/render-error
     :handler (fn [_ data] (assoc data :html "error"))
     :schema {:input [:map [:error-message :string]]
              :output [:map [:html :string]]}}))

(deftest per-transition-schema-chain-valid-test
  (testing "Per-transition schemas — valid workflow compiles"
    (register-per-transition-cells!)
    (let [workflow {:cells {:start         :test/lookup
                            :render-profile :test/render-profile
                            :render-error   :test/render-error}
                    :edges {:start          {:found :render-profile
                                             :not-found :render-error}
                            :render-profile {:done :end}
                            :render-error   {:done :end}}
                    :dispatches {:start          [[:found     (fn [d] (:profile d))]
                                                  [:not-found (fn [d] (:error-message d))]]
                                 :render-profile [[:done (constantly true)]]
                                 :render-error   [[:done (constantly true)]]}}]
      (is (some? (wf/compile-workflow workflow))))))

(deftest per-transition-schema-chain-catches-missing-key-test
  (testing "Not-found path missing keys downstream cell needs → caught"
    (register-per-transition-cells!)
    ;; Wire :not-found to :render-profile (which needs :profile) — should fail
    (is (thrown-with-msg? Exception #"[Ss]chema chain"
          (wf/compile-workflow
           {:cells {:start         :test/lookup
                    :render-profile :test/render-profile}
            :edges {:start          {:found :render-profile
                                     :not-found :render-profile}
                    :render-profile {:done :end}}
            :dispatches {:start          [[:found     (fn [d] (:profile d))]
                                          [:not-found (fn [d] (:error-message d))]]
                         :render-profile [[:done (constantly true)]]}})))))

(deftest per-transition-schema-chain-two-paths-independent-test
  (testing "Two paths have different keys, each validates independently"
    (register-per-transition-cells!)
    (is (some? (wf/compile-workflow
                {:cells {:start         :test/lookup
                         :render-profile :test/render-profile
                         :render-error   :test/render-error}
                 :edges {:start          {:found :render-profile
                                          :not-found :render-error}
                         :render-profile {:done :end}
                         :render-error   {:done :end}}
                 :dispatches {:start          [[:found     (fn [d] (:profile d))]
                                               [:not-found (fn [d] (:error-message d))]]
                              :render-profile [[:done (constantly true)]]
                              :render-error   [[:done (constantly true)]]}})))))

(deftest single-vector-schema-chain-still-works-test
  (testing "Single vector output schema still works in chain validation"
    (register-cells!)
    (let [workflow {:cells {:start  :test/cell-a
                            :step-b :test/cell-b
                            :step-c :test/cell-c}
                    :edges {:start  {:success :step-b, :failure :error}
                            :step-b {:success :step-c}
                            :step-c {:done :end}}
                    :dispatches {:start  [[:success (fn [d] (:a-done d))]
                                          [:failure (fn [d] (not (:a-done d)))]]
                                 :step-b [[:success (constantly true)]]
                                 :step-c [[:done (constantly true)]]}}]
      (is (some? (wf/compile-workflow workflow))))))

;; ===== Map schema with Malli properties =====

(deftest schema-chain-with-map-properties-test
  (testing "Schema chain works when :map schema has Malli properties like {:closed true}"
    (defmethod cell/cell-spec :test/props-producer [_]
      {:id :test/props-producer
       :handler (fn [_ data] (assoc data :y 1))
       :schema {:input [:map [:x :int]]
                :output [:map [:y :int]]}})
    (defmethod cell/cell-spec :test/props-consumer [_]
      {:id :test/props-consumer
       :handler (fn [_ data] (assoc data :z 1))
       :schema {:input [:map {:closed true} [:y :int]]
                :output [:map [:z :int]]}})
    (is (some? (wf/compile-workflow
                {:cells {:start  :test/props-producer
                         :step-2 :test/props-consumer}
                 :edges {:start  {:done :step-2}
                         :step-2 {:done :end}}
                 :dispatches {:start  [[:done (constantly true)]]
                              :step-2 [[:done (constantly true)]]}})))))

;; ===== Three or more dispatch predicates — ordering matters =====

(deftest three-predicate-ordering-test
  (testing "With 3+ dispatches, first matching predicate wins (vector ordering)"
    (defmethod cell/cell-spec :test/classifier [_]
      {:id :test/classifier
       :handler (fn [_ data]
                  (assoc data :score (:raw-score data)))
       :schema {:input [:map [:raw-score :int]]
                :output [:map [:score :int]]}})
    (defmethod cell/cell-spec :test/high-handler [_]
      {:id :test/high-handler
       :handler (fn [_ data] (assoc data :tier "high"))
       :schema {:input [:map [:score :int]]
                :output [:map [:tier :string]]}})
    (defmethod cell/cell-spec :test/medium-handler [_]
      {:id :test/medium-handler
       :handler (fn [_ data] (assoc data :tier "medium"))
       :schema {:input [:map [:score :int]]
                :output [:map [:tier :string]]}})
    (defmethod cell/cell-spec :test/low-handler [_]
      {:id :test/low-handler
       :handler (fn [_ data] (assoc data :tier "low"))
       :schema {:input [:map [:score :int]]
                :output [:map [:tier :string]]}})
    ;; Workflow with 3 dispatch predicates — order determines priority
    ;; A score of 75 matches both :high (>= 70) and :medium (>= 40),
    ;; but :high comes first in the vector so it wins.
    (let [workflow {:cells {:start  :test/classifier
                            :high   :test/high-handler
                            :medium :test/medium-handler
                            :low    :test/low-handler}
                    :edges {:start  {:high :high, :medium :medium, :low :low}
                            :high   {:done :end}
                            :medium {:done :end}
                            :low    {:done :end}}
                    :dispatches {:start  [[:high   (fn [d] (>= (:score d) 70))]
                                          [:medium (fn [d] (>= (:score d) 40))]
                                          [:low    (constantly true)]]
                                 :high   [[:done (constantly true)]]
                                 :medium [[:done (constantly true)]]
                                 :low    [[:done (constantly true)]]}}
          compiled (wf/compile-workflow workflow)]
      ;; Score 75: matches :high (>= 70) and :medium (>= 40), :high wins
      (let [result (fsm/run compiled {} {:data {:raw-score 75}})]
        (is (= "high" (:tier result))))
      ;; Score 50: doesn't match :high, matches :medium (>= 40) and :low, :medium wins
      (let [result (fsm/run compiled {} {:data {:raw-score 50}})]
        (is (= "medium" (:tier result))))
      ;; Score 10: only matches :low (constantly true)
      (let [result (fsm/run compiled {} {:data {:raw-score 10}})]
        (is (= "low" (:tier result)))))))

;; ===== Unconditional edges don't need dispatches =====

(deftest unconditional-edge-no-dispatch-needed-test
  (testing "Unconditional edges (keyword target) need no dispatch entry"
    (defmethod cell/cell-spec :test/simple [_]
      {:id :test/simple
       :handler (fn [_ data] (assoc data :done true))
       :schema {:input [:map] :output [:map [:done :boolean]]}})
    (let [compiled (wf/compile-workflow
                    {:cells {:start :test/simple}
                     :edges {:start :end}})]
      (is (some? compiled)))))

;; ===== No-path-to-end validation via fsm/analyze =====

(deftest no-path-to-end-validation-test
  (testing "compile-workflow throws when a reachable state has no path to :end"
    (defmethod cell/cell-spec :test/dead-end [_]
      {:id :test/dead-end
       :handler (fn [_ data] data)
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :test/route-cell [_]
      {:id :test/route-cell
       :handler (fn [_ data] data)
       :schema {:input [:map] :output [:map]}})
    ;; start → dead-end → halt (no path to :end from dead-end)
    ;; start also has path to :end, but dead-end only goes to :halt
    (is (thrown-with-msg? Exception #"no path to end"
          (wf/compile-workflow
           {:cells {:start    :test/route-cell
                    :dead-end :test/dead-end}
            :edges {:start    {:ok :dead-end, :skip :end}
                    :dead-end :halt}
            :dispatches {:start [[:ok (fn [_] true)]
                                  [:skip (fn [_] false)]]}})))))

;; Regression guard: looping workflows with escape routes still compile

(deftest looping-workflow-still-compiles-test
  (testing "Looping workflow with path to :end compiles (analyze doesn't reject it)"
    (defmethod cell/cell-spec :test/loop-cell [_]
      {:id :test/loop-cell
       :handler (fn [_ data] (update data :count (fnil inc 0)))
       :schema {:input [:map] :output [:map [:count :int]]}})
    ;; Self-loop with escape to :end — must still compile
    (is (some? (wf/compile-workflow
                {:cells {:start :test/loop-cell}
                 :edges {:start {:again :start, :done :end}}
                 :dispatches {:start [[:again (fn [d] (< (:count d 0) 3))]
                                      [:done  (fn [d] (>= (:count d 0) 3))]]}})))))

(deftest multi-node-loop-still-compiles-test
  (testing "Multi-node loop (A→B→A) with escape compiles"
    (defmethod cell/cell-spec :test/loop-a [_]
      {:id :test/loop-a
       :handler (fn [_ data] (update data :n (fnil inc 0)))
       :schema {:input [:map] :output [:map [:n :int]]}})
    (defmethod cell/cell-spec :test/loop-b [_]
      {:id :test/loop-b
       :handler (fn [_ data] (update data :n inc))
       :schema {:input [:map [:n :int]] :output [:map [:n :int]]}})
    (is (some? (wf/compile-workflow
                {:cells {:start :test/loop-a
                         :step-b :test/loop-b}
                 :edges {:start  {:next :step-b}
                         :step-b {:loop :start, :done :end}}
                 :dispatches {:start  [[:next (constantly true)]]
                              :step-b [[:loop (fn [d] (< (:n d) 4))]
                                        [:done (fn [d] (>= (:n d) 4))]]}})))))

(deftest no-path-to-end-error-only-route-test
  (testing "Cell routing only to :error is flagged as no-path-to-end"
    (defmethod cell/cell-spec :test/error-only [_]
      {:id :test/error-only
       :handler (fn [_ data] data)
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :test/pre-error [_]
      {:id :test/pre-error
       :handler (fn [_ data] data)
       :schema {:input [:map] :output [:map]}})
    (is (thrown-with-msg? Exception #"no path to end"
          (wf/compile-workflow
           {:cells {:start     :test/pre-error
                    :error-only :test/error-only}
            :edges {:start      {:next :error-only, :skip :end}
                    :error-only :error}
            :dispatches {:start [[:next (fn [_] true)]
                                  [:skip (fn [_] false)]]}})))))

;; ===== Pipeline shorthand in programmatic workflows =====

(deftest pipeline-compiles-and-runs-test
  (testing "Pipeline shorthand compiles and runs correctly"
    (register-cells!)
    (let [compiled (wf/compile-workflow
                    {:cells {:start  :test/cell-a
                             :step-b :test/cell-b
                             :step-c :test/cell-c}
                     :pipeline [:start :step-b :step-c]})
          result (fsm/run compiled {} {:data {:x 1}})]
      (is (true? (:a-done result)))
      (is (true? (:b-done result)))
      (is (true? (:c-done result))))))

(deftest pipeline-plus-edges-throws-test
  (testing "Pipeline + edges throws"
    (register-cells!)
    (is (thrown-with-msg? Exception #"mutually exclusive"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :pipeline [:start]
            :edges {:start :end}})))))

(deftest pipeline-plus-dispatches-throws-test
  (testing "Pipeline + dispatches throws"
    (register-cells!)
    (is (thrown-with-msg? Exception #"mutually exclusive"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :pipeline [:start]
            :dispatches {:start [[:done (constantly true)]]}})))))

(deftest pipeline-empty-throws-test
  (testing "Empty pipeline throws"
    (is (thrown-with-msg? Exception #"at least 1"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :pipeline []})))))

(deftest pipeline-invalid-cell-throws-test
  (testing "Pipeline referencing non-existent cell throws"
    (register-cells!)
    (is (thrown-with-msg? Exception #"not in :cells"
          (wf/compile-workflow
           {:cells {:start :test/cell-a}
            :pipeline [:start :nonexistent]})))))

;; ===== Parameterized cells =====

(deftest parameterized-cell-compiles-test
  (testing "Workflow with parameterized cell (map form) compiles"
    (register-cells!)
    (let [compiled (wf/compile-workflow
                    {:cells {:start {:id :test/cell-a :params {:mode "fast"}}}
                     :edges {:start :end}})]
      (is (some? compiled)))))

(deftest parameterized-cell-backward-compat-test
  (testing "Bare keyword cell refs still compile (regression guard)"
    (register-cells!)
    (let [compiled (wf/compile-workflow
                    {:cells {:start :test/cell-a}
                     :edges {:start :end}})]
      (is (some? compiled)))))

(deftest parameterized-cell-missing-id-throws-test
  (testing "Map cell ref without :id throws"
    (register-cells!)
    (is (thrown-with-msg? Exception #":id"
          (wf/compile-workflow
           {:cells {:start {:params {:x 1}}}
            :edges {:start :end}})))))

(deftest parameterized-cell-schema-chain-test
  (testing "Parameterized cells participate in schema chain validation"
    (register-cells!)
    (let [compiled (wf/compile-workflow
                    {:cells {:start  {:id :test/cell-a :params {:mode "fast"}}
                             :step-b :test/cell-b}
                     :edges {:start  {:success :step-b}
                             :step-b {:done :end}}
                     :dispatches {:start  [[:success (constantly true)]]
                                  :step-b [[:done (constantly true)]]}})]
      (is (some? compiled)))))


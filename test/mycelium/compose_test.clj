(ns mycelium.compose-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.workflow :as wf]
            [mycelium.compose :as compose]
            [mycelium.core :as myc]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. workflow->cell produces valid cell spec =====

(deftest workflow-to-cell-produces-valid-spec-test
  (testing "workflow->cell produces valid cell spec with :default-dispatches"
    (defmethod cell/cell-spec :comp/step1 [_]
      {:id          :comp/step1
       :handler     (fn [_ data]
                      (assoc data :y (* 2 (:x data))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}})

    (let [child-workflow {:cells {:start :comp/step1}
                          :edges {:start {:done :end}}
                          :dispatches {:start [[:done (constantly true)]]}}
          cell-spec (compose/workflow->cell :comp/child child-workflow
                                           {:input [:map [:x :int]]
                                            :output [:map [:y :int]]})]
      (is (= :comp/child (:id cell-spec)))
      (is (fn? (:handler cell-spec)))
      (is (some? (:default-dispatches cell-spec)))
      (is (some? (:schema cell-spec))))))

;; ===== 2. Child workflow runs inside parent =====

(deftest child-workflow-runs-inside-parent-test
  (testing "Child workflow runs inside parent, data flows through"
    (defmethod cell/cell-spec :comp/doubler [_]
      {:id          :comp/doubler
       :handler     (fn [_ data]
                      (assoc data :doubled (* 2 (:x data))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:doubled :int]]}})

    (let [child-workflow {:cells {:start :comp/doubler}
                          :edges {:start {:done :end}}
                          :dispatches {:start [[:done (constantly true)]]}}]
      ;; Register the child workflow as a cell
      (compose/register-workflow-cell! :comp/child-wf child-workflow
                                       {:input [:map [:x :int]]
                                        :output [:map [:doubled :int]]})

      ;; Parent cell that sets up data
      (defmethod cell/cell-spec :comp/setup [_]
        {:id          :comp/setup
         :handler     (fn [_ data]
                        (assoc data :x (:raw data)))
         :schema      {:input [:map [:raw :int]]
                       :output [:map [:x :int]]}})

      (let [parent (wf/compile-workflow
                    {:cells {:start :comp/setup
                             :child :comp/child-wf}
                     :edges {:start {:next :child}
                             :child {:success :end, :failure :error}}
                     :dispatches {:start [[:next (constantly true)]]
                                  :child [[:success (fn [d] (not (:mycelium/error d)))]
                                          [:failure (fn [d] (some? (:mycelium/error d)))]]}})
            result (fsm/run parent {} {:data {:raw 21}})]
        (is (= 42 (:doubled result)))))))

;; ===== 3. Child failure propagates as parent :failure =====

(deftest child-failure-propagates-test
  (testing "Child failure propagates — parent dispatch routes via :mycelium/error"
    (defmethod cell/cell-spec :comp/failing-cell [_]
      {:id          :comp/failing-cell
       :handler     (fn [_ _data]
                      (throw (Exception. "intentional failure")))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}})

    (let [child-workflow {:cells {:start :comp/failing-cell}
                          :edges {:start {:done :end}}
                          :dispatches {:start [[:done (constantly true)]]}}]
      (compose/register-workflow-cell! :comp/failing-wf child-workflow
                                       {:input [:map [:x :int]]
                                        :output [:map [:y :int]]})

      (defmethod cell/cell-spec :comp/error-handler [_]
        {:id          :comp/error-handler
         :handler     (fn [_ data]
                        (assoc data :error-handled true))
         :schema      {:input [:map]
                       :output [:map [:error-handled :boolean]]}})

      (let [parent (wf/compile-workflow
                    {:cells {:start  :comp/failing-wf
                             :handle :comp/error-handler}
                     :edges {:start  {:success :end, :failure :handle}
                             :handle {:done :end}}
                     :dispatches {:start  [[:success (fn [d] (not (:mycelium/error d)))]
                                           [:failure (fn [d] (some? (:mycelium/error d)))]]
                                  :handle [[:done (constantly true)]]}})
            result (fsm/run parent {} {:data {:x 1}})]
        (is (= true (:error-handled result)))))))

;; ===== 4. Three-level nesting =====

(deftest three-level-nesting-test
  (testing "Three-level nesting (grandchild→child→parent) works"
    (defmethod cell/cell-spec :comp/adder [_]
      {:id          :comp/adder
       :handler     (fn [_ data]
                      (assoc data :n (+ (:n data) 10)))
       :schema      {:input [:map [:n :int]]
                     :output [:map [:n :int]]}})

    ;; Grandchild workflow
    (let [grandchild-wf {:cells {:start :comp/adder}
                         :edges {:start {:done :end}}
                         :dispatches {:start [[:done (constantly true)]]}}]
      (compose/register-workflow-cell! :comp/grandchild grandchild-wf
                                       {:input [:map [:n :int]]
                                        :output [:map [:n :int]]})

      ;; Child workflow containing grandchild
      (let [child-wf {:cells {:start :comp/grandchild}
                      :edges {:start {:success :end, :failure :error}}
                      :dispatches {:start [[:success (fn [d] (not (:mycelium/error d)))]
                                           [:failure (fn [d] (some? (:mycelium/error d)))]]}}]
        (compose/register-workflow-cell! :comp/child-nested child-wf
                                         {:input [:map [:n :int]]
                                          :output [:map [:n :int]]})

        ;; Parent workflow containing child
        (let [parent (wf/compile-workflow
                      {:cells {:start :comp/child-nested}
                       :edges {:start {:success :end, :failure :error}}
                       :dispatches {:start [[:success (fn [d] (not (:mycelium/error d)))]
                                            [:failure (fn [d] (some? (:mycelium/error d)))]]}})
              result (fsm/run parent {} {:data {:n 0}})]
          (is (= 10 (:n result))))))))

;; ===== 5. Child trace preserved in output data =====

(deftest child-trace-preserved-test
  (testing "Child trace preserved in output data"
    (defmethod cell/cell-spec :comp/traced [_]
      {:id          :comp/traced
       :handler     (fn [_ data] data)
       :schema      {:input [:map [:x :int]]
                     :output [:map [:x :int]]}})

    (let [child-wf {:cells {:start :comp/traced}
                    :edges {:start {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]}}]
      (compose/register-workflow-cell! :comp/traced-wf child-wf
                                       {:input [:map [:x :int]]
                                        :output [:map [:x :int]]})

      (let [parent (wf/compile-workflow
                    {:cells {:start :comp/traced-wf}
                     :edges {:start {:success :end, :failure :error}}
                     :dispatches {:start [[:success (fn [d] (not (:mycelium/error d)))]
                                          [:failure (fn [d] (some? (:mycelium/error d)))]]}})
            result (fsm/run parent {} {:data {:x 1}})]
        (is (vector? (:mycelium/child-trace result)))))))

;; ===== 6. Resources pass through to child cells =====

(deftest resources-pass-to-child-test
  (testing "Resources pass through to child cells"
    (defmethod cell/cell-spec :comp/resource-user [_]
      {:id          :comp/resource-user
       :handler     (fn [{:keys [config]} data]
                      (assoc data :from-config (:value config)))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:from-config :string]]}
       :requires    [:config]})

    (let [child-wf {:cells {:start :comp/resource-user}
                    :edges {:start {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]}}]
      (compose/register-workflow-cell! :comp/resource-wf child-wf
                                       {:input [:map [:x :int]]
                                        :output [:map [:from-config :string]]})

      (let [parent    (wf/compile-workflow
                       {:cells {:start :comp/resource-wf}
                        :edges {:start {:success :end, :failure :error}}
                        :dispatches {:start [[:success (fn [d] (not (:mycelium/error d)))]
                                             [:failure (fn [d] (some? (:mycelium/error d)))]]}})
            resources {:config {:value "from-parent"}}
            result    (fsm/run parent resources {:data {:x 1}})]
        (is (= "from-parent" (:from-config result)))))))

;; ===== 7. Child workflow compiled once, not per-invocation =====

(deftest child-workflow-compiled-once-test
  (testing "workflow->cell compiles child workflow once at creation, not per handler call"
    (defmethod cell/cell-spec :comp/counter-cell [_]
      {:id          :comp/counter-cell
       :handler     (fn [_ data]
                      (assoc data :n (inc (:n data))))
       :schema      {:input [:map [:n :int]]
                     :output [:map [:n :int]]}})

    (let [compile-count (atom 0)
          child-wf      {:cells {:start :comp/counter-cell}
                         :edges {:start {:done :end}}
                         :dispatches {:start [[:done (constantly true)]]}}]
      (with-redefs [wf/compile-workflow (let [orig wf/compile-workflow]
                                          (fn [& args]
                                            (swap! compile-count inc)
                                            (apply orig args)))]
        (let [cell-spec (compose/workflow->cell :comp/once-test child-wf
                                                {:input [:map [:n :int]]
                                                 :output [:map [:n :int]]})
              handler   (:handler cell-spec)]
          ;; compile-workflow called once during workflow->cell
          (is (= 1 @compile-count))
          ;; Run handler multiple times — should NOT recompile
          (handler {} {:n 0})
          (handler {} {:n 10})
          (handler {} {:n 20})
          (is (= 1 @compile-count)))))))

;; ===== 8. default-dispatches are provided by workflow->cell =====

(deftest default-dispatches-test
  (testing "workflow->cell provides :default-dispatches for success/failure routing"
    (defmethod cell/cell-spec :comp/simple [_]
      {:id :comp/simple
       :handler (fn [_ data] (assoc data :done true))
       :schema {:input [:map] :output [:map [:done :boolean]]}})

    (let [child-wf {:cells {:start :comp/simple}
                    :edges {:start {:done :end}}
                    :dispatches {:start [[:done (constantly true)]]}}
          spec (compose/workflow->cell :comp/def-disp child-wf
                                      {:input [:map] :output [:map [:done :boolean]]})]
      (is (vector? (:default-dispatches spec)))
      (let [labels (set (map first (:default-dispatches spec)))]
        (is (contains? labels :success))
        (is (contains? labels :failure)))
      ;; :success dispatch should return truthy for data without :mycelium/error
      (let [success-pred (second (first (filter #(= :success (first %)) (:default-dispatches spec))))
            failure-pred (second (first (filter #(= :failure (first %)) (:default-dispatches spec))))]
        (is (success-pred {:done true}))
        ;; :failure dispatch should return truthy for data with :mycelium/error
        (is (failure-pred {:mycelium/error "boom"}))))))

;; ===== 9. Child workflow's :mycelium/trace present in parent result =====

(deftest child-mycelium-trace-in-parent-test
  (testing "Child workflow's :mycelium/trace contains step-by-step entries in parent result"
    (defmethod cell/cell-spec :comp/trace-step1 [_]
      {:id      :comp/trace-step1
       :handler (fn [_ data] (assoc data :a 1))
       :schema  {:input [:map [:x :int]] :output [:map [:a :int]]}})

    (defmethod cell/cell-spec :comp/trace-step2 [_]
      {:id      :comp/trace-step2
       :handler (fn [_ data] (assoc data :b 2))
       :schema  {:input [:map [:a :int]] :output [:map [:b :int]]}})

    (let [child-wf {:cells {:start :comp/trace-step1
                            :step2 :comp/trace-step2}
                    :edges {:start {:next :step2}
                            :step2 {:done :end}}
                    :dispatches {:start [[:next (constantly true)]]
                                 :step2 [[:done (constantly true)]]}}]
      (compose/register-workflow-cell! :comp/traced-multi child-wf
                                       {:input [:map [:x :int]]
                                        :output [:map [:b :int]]})

      (let [parent   (wf/compile-workflow
                      {:cells {:start :comp/traced-multi}
                       :edges {:start {:success :end, :failure :error}}
                       :dispatches {:start [[:success (fn [d] (not (:mycelium/error d)))]
                                            [:failure (fn [d] (some? (:mycelium/error d)))]]}})
            result   (fsm/run parent {} {:data {:x 1}})
            trace    (:mycelium/trace result)]
        ;; Child's :mycelium/trace flows through to parent result
        ;; 2 entries from child + 1 entry from parent's post-interceptor for the composed cell
        (is (vector? trace))
        (is (= 3 (count trace)))
        ;; First two are child step-by-step entries
        (is (= :start (:cell (first trace))))
        (is (= :comp/trace-step1 (:cell-id (first trace))))
        (is (= :step2 (:cell (second trace))))
        (is (= :comp/trace-step2 (:cell-id (second trace))))
        ;; Third is the parent's trace entry for the composed cell itself
        (is (= :start (:cell (nth trace 2))))
        (is (= :comp/traced-multi (:cell-id (nth trace 2))))))))

;; ===== 10. Inferred output schema from child workflow =====

(deftest inferred-output-schema-test
  (testing "workflow->cell infers output keys from child workflow's end-reaching cells"
    (defmethod cell/cell-spec :comp/produce-x [_]
      {:id      :comp/produce-x
       :handler (fn [_ data] (assoc data :x-val 42))
       :schema  {:input [:map [:a :int]]
                 :output [:map [:x-val :int]]}})

    (let [child-wf {:cells {:start :comp/produce-x}
                     :edges {:start :end}
                     :dispatches {}}
          spec (compose/workflow->cell :comp/inferred child-wf
                                       {:input [:map [:a :int]]
                                        :output :map})]
      ;; Output should be the explicit per-transition wrapper, not bare :map
      (let [output      (get-in spec [:schema :output])
            transitions (second output)]
        (is (= :per-transition (first output)))
        ;; :success should contain the inferred keys
        (let [success-schema (get transitions :success)]
          (is (vector? success-schema))
          (is (= :map (first success-schema)))
          ;; Should include :x-val
          (let [key-names (set (map first (filter vector? (rest success-schema))))]
            (is (contains? key-names :x-val))))
        ;; :failure should exist
        (is (some? (get transitions :failure)))))))

(deftest inferred-output-enables-chain-validation-test
  (testing "Parent workflow chain validation passes without set-cell-schema! workaround"
    (defmethod cell/cell-spec :comp/inner-step [_]
      {:id      :comp/inner-step
       :handler (fn [_ data]
                  (assoc data :credit-score 750 :risk-level :low))
       :schema  {:input [:map [:name :string]]
                 :output [:map [:credit-score :int] [:risk-level :keyword]]}})

    ;; Register child as composed cell — no set-cell-schema! needed
    (let [child-wf {:cells {:start :comp/inner-step}
                     :edges {:start :end}
                     :dispatches {}}]
      (compose/register-workflow-cell! :comp/auto-inferred child-wf
                                       {:input [:map [:name :string]]
                                        :output :map}))

    ;; Downstream cell needs keys produced by the composed cell
    (defmethod cell/cell-spec :comp/downstream [_]
      {:id      :comp/downstream
       :handler (fn [_ data] (assoc data :tier (if (>= (:credit-score data) 700) "high" "low")))
       :schema  {:input [:map [:credit-score :int]]
                 :output [:map [:tier :string]]}})

    ;; Parent workflow should compile without errors
    ;; The chain validator sees :credit-score from the inferred output of :comp/auto-inferred
    (let [compiled (wf/compile-workflow
                    {:cells {:start      :comp/auto-inferred
                             :downstream :comp/downstream}
                     :edges {:start      {:success :downstream, :failure :error}
                             :downstream :end}
                     :dispatches {:downstream []}})
          result (fsm/run compiled {} {:data {:name "Alice"}})]
      (is (= "high" (:tier result))))))

(deftest explicit-output-schema-takes-precedence-test
  (testing "When caller provides [:map ...] output, inference is skipped"
    (defmethod cell/cell-spec :comp/explicit-inner [_]
      {:id      :comp/explicit-inner
       :handler (fn [_ data] (assoc data :y 1))
       :schema  {:input [:map] :output [:map [:y :int]]}})

    (let [child-wf {:cells {:start :comp/explicit-inner}
                     :edges {:start :end}
                     :dispatches {}}
          spec (compose/workflow->cell :comp/explicit-out child-wf
                                       {:input [:map]
                                        :output [:map [:y :int]]})]
      ;; Output should use the caller's schema for :success
      (is (= [:map [:y :int]] (get-in (second (get-in spec [:schema :output])) [:success]))))))

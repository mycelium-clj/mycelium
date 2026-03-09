(ns mycelium.transform-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.manifest :as manifest]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Helper cells ---

(defn- register-cells! []
  ;; Cell that outputs :user-name but NOT :name
  (defmethod cell/cell-spec :xf/lookup [_]
    {:id      :xf/lookup
     :handler (fn [_ data] {:user-name (str "user-" (:id data))})
     :schema  {:input  [:map [:id :int]]
               :output [:map [:user-name :string]]}})
  ;; Cell that expects :name (not :user-name)
  (defmethod cell/cell-spec :xf/greet [_]
    {:id      :xf/greet
     :handler (fn [_ data] {:greeting (str "Hello, " (:name data))})
     :schema  {:input  [:map [:name :string]]
               :output [:map [:greeting :string]]}})
  ;; Cell with branching output
  (defmethod cell/cell-spec :xf/classify [_]
    {:id      :xf/classify
     :handler (fn [_ data]
                {:tier (if (> (:score data) 80) :premium :basic)
                 :raw-score (:score data)})
     :schema  {:input  [:map [:score :int]]
               :output {:premium [:map [:tier [:= :premium]] [:raw-score :int]]
                        :basic   [:map [:tier [:= :basic]] [:raw-score :int]]}}})
  ;; Cell that expects :level (not :tier)
  (defmethod cell/cell-spec :xf/premium-handler [_]
    {:id      :xf/premium-handler
     :handler (fn [_ data] {:result (str "Premium: " (:level data))})
     :schema  {:input  [:map [:level :keyword]]
               :output [:map [:result :string]]}})
  ;; Cell that expects :category (not :tier)
  (defmethod cell/cell-spec :xf/basic-handler [_]
    {:id      :xf/basic-handler
     :handler (fn [_ data] {:result (str "Basic: " (:category data))})
     :schema  {:input  [:map [:category :string]]
               :output [:map [:result :string]]}}))

;; ===== 1. Input transform reshapes data before schema validation =====

(deftest input-transform-reshapes-data-test
  (testing "Input transform renames key so cell's input schema passes"
    (register-cells!)
    (let [result (myc/run-workflow
                   {:cells      {:start :xf/lookup
                                 :greet :xf/greet}
                    :edges      {:start :greet
                                 :greet :end}
                    :transforms {:greet {:input {:fn     (fn [data]
                                                          (assoc data :name (:user-name data)))
                                                 :schema {:input  [:map [:user-name :string]]
                                                          :output [:map [:name :string]]}}}}}
                   {} {:id 42})]
      (is (nil? (myc/workflow-error result)))
      (is (= "Hello, user-42" (:greeting result))))))

;; ===== 2. Output transform reshapes data after handler =====

(deftest output-transform-reshapes-data-test
  (testing "Output transform renames key for the next cell"
    (register-cells!)
    (let [result (myc/run-workflow
                   {:cells      {:start :xf/lookup
                                 :greet :xf/greet}
                    :edges      {:start :greet
                                 :greet :end}
                    :transforms {:start {:output {:fn     (fn [data]
                                                           (assoc data :name (:user-name data)))
                                                  :schema {:input  [:map [:user-name :string]]
                                                           :output [:map [:name :string]]}}}}}
                   {} {:id 42})]
      (is (nil? (myc/workflow-error result)))
      (is (= "Hello, user-42" (:greeting result))))))

;; ===== 3. Edge-specific output transforms on branching cells =====

(deftest edge-specific-output-transforms-test
  (testing "Different output transforms per edge on branching cell"
    (register-cells!)
    (let [wf {:cells      {:start   :xf/classify
                           :premium :xf/premium-handler
                           :basic   :xf/basic-handler}
              :edges      {:start   {:premium :premium, :basic :basic}
                           :premium :end
                           :basic   :end}
              :dispatches {:start [[:premium (fn [d] (= :premium (:tier d)))]
                                   [:basic   (fn [d] (= :basic (:tier d)))]]}
              :transforms {:start {:premium {:output {:fn     (fn [data]
                                                                (assoc data :level (:tier data)))
                                                      :schema {:input  [:map [:tier :keyword]]
                                                               :output [:map [:level :keyword]]}}}
                                   :basic   {:output {:fn     (fn [data]
                                                                (assoc data :category (name (:tier data))))
                                                      :schema {:input  [:map [:tier :keyword]]
                                                               :output [:map [:category :string]]}}}}}}
          ;; Premium path
          result-p (myc/run-workflow wf {} {:score 90})
          ;; Basic path
          result-b (myc/run-workflow wf {} {:score 50})]
      (is (nil? (myc/workflow-error result-p)))
      (is (= "Premium: :premium" (:result result-p)))
      (is (nil? (myc/workflow-error result-b)))
      (is (= "Basic: basic" (:result result-b))))))

;; ===== 4. Input transform on branching cell (top-level :input) =====

(deftest input-transform-on-branching-cell-test
  (testing "Input transform works alongside edge-specific output transforms"
    (register-cells!)
    (defmethod cell/cell-spec :xf/source [_]
      {:id      :xf/source
       :handler (fn [_ data] {:raw-value (:x data)})
       :schema  {:input [:map [:x :int]] :output [:map [:raw-value :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start    :xf/source
                                 :classify :xf/classify
                                 :premium  :xf/premium-handler
                                 :basic    :xf/basic-handler}
                    :edges      {:start    :classify
                                 :classify {:premium :premium, :basic :basic}
                                 :premium  :end
                                 :basic    :end}
                    :dispatches {:classify [[:premium (fn [d] (= :premium (:tier d)))]
                                           [:basic   (fn [d] (= :basic (:tier d)))]]}
                    :transforms {:classify {:input   {:fn     (fn [data]
                                                               (assoc data :score (:raw-value data)))
                                                      :schema {:input  [:map [:raw-value :int]]
                                                               :output [:map [:score :int]]}}
                                            :premium {:output {:fn     (fn [data]
                                                                         (assoc data :level (:tier data)))
                                                               :schema {:input  [:map [:tier :keyword]]
                                                                        :output [:map [:level :keyword]]}}}
                                            :basic   {:output {:fn     (fn [data]
                                                                         (assoc data :category (name (:tier data))))
                                                               :schema {:input  [:map [:tier :keyword]]
                                                                        :output [:map [:category :string]]}}}}}}
                   {} {:x 90})]
      (is (nil? (myc/workflow-error result)))
      (is (= "Premium: :premium" (:result result))))))

;; ===== 5. Transforms compose with key propagation =====

(deftest transforms-compose-with-key-propagation-test
  (testing "Transforms see fully propagated data (input keys merged)"
    (register-cells!)
    (let [seen-keys (atom nil)
          result (myc/run-workflow
                   {:cells      {:start :xf/lookup
                                 :greet :xf/greet}
                    :edges      {:start :greet
                                 :greet :end}
                    :transforms {:start {:output {:fn     (fn [data]
                                                           (reset! seen-keys (set (keys data)))
                                                           (assoc data :name (:user-name data)))
                                                  :schema {:input  [:map [:user-name :string]]
                                                           :output [:map [:name :string]]}}}}}
                   {} {:id 42})]
      (is (nil? (myc/workflow-error result)))
      ;; Output transform should see propagated keys (:id from input + :user-name from handler)
      (is (contains? @seen-keys :id))
      (is (contains? @seen-keys :user-name)))))

;; ===== 6. Without :transforms, workflow runs unchanged =====

(deftest no-transforms-backwards-compatible-test
  (testing "Workflow without :transforms runs normally"
    (register-cells!)
    (defmethod cell/cell-spec :xf/simple-a [_]
      {:id      :xf/simple-a
       :handler (fn [_ data] {:y (* (:x data) 2)})
       :schema  {:input [:map [:x :int]] :output [:map [:y :int]]}})
    (defmethod cell/cell-spec :xf/simple-b [_]
      {:id      :xf/simple-b
       :handler (fn [_ data] {:z (+ (:y data) 1)})
       :schema  {:input [:map [:y :int]] :output [:map [:z :int]]}})
    (let [result (myc/run-workflow
                   {:cells {:start :xf/simple-a :step :xf/simple-b}
                    :edges {:start :step :step :end}}
                   {} {:x 5})]
      (is (nil? (myc/workflow-error result)))
      (is (= 11 (:z result))))))

;; ===== 7. Compile-time validation: invalid cell name in transforms =====

(deftest validate-transforms-invalid-cell-test
  (testing "Transforms referencing nonexistent cell throws at compile time"
    (register-cells!)
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Transform references.*nonexistent"
          (myc/pre-compile
            {:cells      {:start :xf/lookup}
             :edges      {:start :end}
             :transforms {:bogus {:input {:fn identity
                                          :schema {:input [:map] :output [:map]}}}}})))))

;; ===== 8. Compile-time validation: invalid edge label in transforms =====

(deftest validate-transforms-invalid-edge-label-test
  (testing "Transforms with edge label not in cell's edges throws"
    (register-cells!)
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Transform.*invalid edge"
          (myc/pre-compile
            {:cells      {:start   :xf/classify
                          :premium :xf/premium-handler
                          :basic   :xf/basic-handler}
             :edges      {:start   {:premium :premium, :basic :basic}
                          :premium :end
                          :basic   :end}
             :dispatches {:start [[:premium (fn [d] (= :premium (:tier d)))]
                                  [:basic   (fn [d] (= :basic (:tier d)))]]}
             :transforms {:start {:nonexistent {:output {:fn identity
                                                         :schema {:input [:map] :output [:map]}}}}}})))))

;; ===== 9. Compile-time validation: transform must have :fn =====

(deftest validate-transforms-must-have-fn-test
  (testing "Transform without :fn throws"
    (register-cells!)
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Transform.*must be a function"
          (myc/pre-compile
            {:cells      {:start :xf/lookup}
             :edges      {:start :end}
             :transforms {:start {:input {:schema {:input [:map] :output [:map]}}}}})))))

;; ===== 10. Transforms with coercion enabled =====

(deftest transforms-with-coercion-test
  (testing "Transforms compose correctly with :coerce? true"
    (register-cells!)
    (defmethod cell/cell-spec :xf/double-out [_]
      {:id      :xf/double-out
       :handler (fn [_ data] {:value (* (:x data) 1.0)})
       :schema  {:input [:map [:x :int]] :output [:map [:value :double]]}})
    (defmethod cell/cell-spec :xf/int-in [_]
      {:id      :xf/int-in
       :handler (fn [_ data] {:result (inc (:count data))})
       :schema  {:input [:map [:count :int]] :output [:map [:result :int]]}})
    (let [result (myc/run-workflow
                   {:cells      {:start :xf/double-out
                                 :step  :xf/int-in}
                    :edges      {:start :step
                                 :step  :end}
                    :transforms {:step {:input {:fn     (fn [data]
                                                         (assoc data :count (int (:value data))))
                                                :schema {:input  [:map [:value :double]]
                                                         :output [:map [:count :int]]}}}}}
                   {} {:x 5} {:coerce? true})]
      (is (nil? (myc/workflow-error result)))
      (is (= 6 (:result result))))))

;; ===== 11. Output transform only applies to the specific edge taken =====

(deftest output-transform-only-applies-to-taken-edge-test
  (testing "Output transform for untaken edge is not applied"
    (register-cells!)
    (let [result (myc/run-workflow
                   {:cells      {:start   :xf/classify
                                 :premium :xf/premium-handler
                                 :basic   :xf/basic-handler}
                    :edges      {:start   {:premium :premium, :basic :basic}
                                 :premium :end
                                 :basic   :end}
                    :dispatches {:start [[:premium (fn [d] (= :premium (:tier d)))]
                                        [:basic   (fn [d] (= :basic (:tier d)))]]}
                    :transforms {:start {:premium {:output {:fn     (fn [data]
                                                                      (assoc data :level (:tier data)))
                                                            :schema {:input  [:map [:tier :keyword]]
                                                                     :output [:map [:level :keyword]]}}}
                                         :basic   {:output {:fn     (fn [data]
                                                                      (assoc data :category (name (:tier data))))
                                                            :schema {:input  [:map [:tier :keyword]]
                                                                     :output [:map [:category :string]]}}}}}}
                   {} {:score 90})]
      ;; Premium path taken — :level should exist, :category should NOT
      (is (nil? (myc/workflow-error result)))
      (is (some? (:level result)))
      (is (nil? (:category result))))))

;; ===== 12. Manifest with :transforms passes through =====

(deftest manifest-transforms-passthrough-test
  (testing "Manifest with :transforms passes through to workflow"
    (let [xf {:fn identity :schema {:input [:map] :output [:map]}}
          m {:id :test/xf-manifest
             :cells {:start {:id     :xf/m-cell
                              :schema {:input [:map [:x :int]]
                                       :output [:map [:y :int]]}}}
             :edges {:start {:done :end}}
             :dispatches {:start [[:done (constantly true)]]}
             :transforms {:start {:done {:output xf}}}}
          wf-def (manifest/manifest->workflow m)]
      (is (= (:transforms m) (:transforms wf-def))))))

;; ===== 13. Transform schema is validated at compile time =====

(deftest validate-transform-schema-test
  (testing "Transform with invalid Malli schema throws at compile time"
    (register-cells!)
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Invalid.*schema"
          (myc/pre-compile
            {:cells      {:start :xf/lookup}
             :edges      {:start :end}
             :transforms {:start {:output {:fn identity
                                           :schema {:input [:not-a-valid-schema]
                                                    :output [:map]}}}}})))))

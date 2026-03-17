(ns mycelium.lite-schema-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.manifest :as manifest]
            [mycelium.schema :as schema]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. normalize-schema: basic lite map conversion =====

(deftest normalize-schema-basic-test
  (testing "Converts {key type} map to [:map [key type] ...]"
    (is (= [:map [:subtotal :double] [:state :string]]
           (schema/normalize-schema {:subtotal :double :state :string}))))

  (testing "Nested maps are recursively converted"
    (is (= [:map [:address [:map [:street :string] [:city :string]]]]
           (schema/normalize-schema {:address {:street :string :city :string}}))))

  (testing "Vector schemas pass through unchanged"
    (is (= [:map [:x :int]]
           (schema/normalize-schema [:map [:x :int]]))))

  (testing "Keyword schemas pass through unchanged"
    (is (= :int (schema/normalize-schema :int))))

  (testing "nil passes through"
    (is (nil? (schema/normalize-schema nil))))

  (testing "Compound type values (vectors) pass through as values"
    (is (= [:map [:items [:vector :string]] [:count :int]]
           (schema/normalize-schema {:items [:vector :string] :count :int})))))

;; ===== 2. normalize-output-schema: dispatched vs single =====

(deftest normalize-output-schema-test
  (testing "Non-dispatched map output is treated as lite schema"
    (is (= [:map [:tax :double]]
           (schema/normalize-output-schema {:tax :double} false))))

  (testing "Dispatched map output normalizes each transition value"
    (is (= {:success [:map [:result :string]]
            :failure [:map [:error :string]]}
           (schema/normalize-output-schema
            {:success {:result :string}
             :failure {:error :string}}
            true))))

  (testing "Dispatched map output with existing Malli vectors passes through"
    (is (= {:high [:map [:result [:= :high]]]
            :low  [:map [:result [:= :low]]]}
           (schema/normalize-output-schema
            {:high [:map [:result [:= :high]]]
             :low  [:map [:result [:= :low]]]}
            true))))

  (testing "Vector output passes through regardless of dispatch flag"
    (is (= [:map [:x :int]]
           (schema/normalize-output-schema [:map [:x :int]] false)))
    (is (= [:map [:x :int]]
           (schema/normalize-output-schema [:map [:x :int]] true))))

  (testing "nil output passes through"
    (is (nil? (schema/normalize-output-schema nil false)))
    (is (nil? (schema/normalize-output-schema nil true)))))

;; ===== 3. defcell with lite syntax =====

(deftest defcell-lite-schema-test
  (testing "defcell normalizes lite input schema"
    (cell/defcell :test/lite-input
      {:doc    "Sums x and y"
       :input {:x :int :y :int}
       :output [:map [:sum :int]]}
      (fn [_ data] {:sum (+ (:x data) (:y data))}))
    (let [spec (cell/get-cell :test/lite-input)]
      (is (= [:map [:x :int] [:y :int]]
             (get-in spec [:schema :input])))))

  (testing "defcell normalizes lite output schema"
    (cell/defcell :test/lite-output
      {:doc    "Doubles x"
       :input [:map [:x :int]]
       :output {:doubled :int}}
      (fn [_ data] {:doubled (* 2 (:x data))}))
    (let [spec (cell/get-cell :test/lite-output)]
      (is (= [:map [:doubled :int]]
             (get-in spec [:schema :output])))))

  (testing "defcell normalizes both input and output lite schemas"
    (cell/defcell :test/lite-both
      {:doc    "Increments x into y"
       :input  {:x :int}
       :output {:y :int}}
      (fn [_ data] {:y (inc (:x data))}))
    (let [spec (cell/get-cell :test/lite-both)]
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output])))))

  (testing "defcell with per-transition vector output is unchanged"
    (cell/defcell :test/per-transition
      {:doc    "Classifies x as high or low"
       :input  {:x :int}
       :output {:high [:map [:result [:= :high]]]
                :low  [:map [:result [:= :low]]]}}
      (fn [_ data]
        {:result (if (> (:x data) 10) :high :low)}))
    (let [spec (cell/get-cell :test/per-transition)]
      ;; Per-transition values are vectors, so they stay as per-transition map
      (is (map? (get-in spec [:schema :output])))
      (is (= #{:high :low} (set (keys (get-in spec [:schema :output]))))))))

;; ===== 4. set-cell-schema! with lite syntax =====

(deftest set-cell-schema-lite-test
  (testing "set-cell-schema! normalizes lite schemas"
    (cell/defcell :test/schema-override
      {:doc "Increments x into y"}
      (fn [_ data] {:y (inc (:x data))}))
    (cell/set-cell-schema! :test/schema-override
                           {:input {:x :int} :output {:y :int}})
    (let [spec (cell/get-cell :test/schema-override)]
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output]))))))

;; ===== 5. set-cell-meta! receives pre-normalized schemas from manifest =====

(deftest set-cell-meta-normalized-test
  (testing "set-cell-meta! works with pre-normalized lite schemas"
    (cell/defcell :test/meta-override
      {:doc "Passthrough cell for meta override testing"}
      (fn [_ data] data))
    ;; Simulate what manifest->workflow does: normalize first, then set-cell-meta!
    (let [normalized (schema/normalize-cell-schema
                       {:input {:name :string} :output {:greeting :string}})]
      (cell/set-cell-meta! :test/meta-override {:schema normalized})
      (let [spec (cell/get-cell :test/meta-override)]
        (is (= [:map [:name :string]] (get-in spec [:schema :input])))
        (is (= [:map [:greeting :string]] (get-in spec [:schema :output])))))))

;; ===== 6. End-to-end: workflow with lite schemas runs correctly =====

(deftest lite-schema-workflow-e2e-test
  (testing "Workflow runs with lite schemas in defcell"
    (cell/defcell :test/greet
      {:doc    "Greets the user by name"
       :input  {:name :string}
       :output {:greeting :string}}
      (fn [_ data] {:greeting (str "Hello, " (:name data) "!")}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/greet}
                    :edges {:start :end}}
                   {} {:name "Alice"})]
      (is (nil? (myc/workflow-error result)))
      (is (= "Hello, Alice!" (:greeting result)))))

  (testing "Workflow with nested lite schemas validates correctly"
    (cell/defcell :test/extract-city
      {:doc    "Extracts city from nested address map"
       :input  {:address {:street :string :city :string}}
       :output {:city :string}}
      (fn [_ data] {:city (get-in data [:address :city])}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/extract-city}
                    :edges {:start :end}}
                   {} {:address {:street "123 Main" :city "Portland"}})]
      (is (nil? (myc/workflow-error result)))
      (is (= "Portland" (:city result)))))

  (testing "Workflow rejects invalid input against lite schema"
    (cell/defcell :test/typed-add
      {:doc    "Adds two integers x and y"
       :input  {:x :int :y :int}
       :output {:sum :int}}
      (fn [_ data] {:sum (+ (:x data) (:y data))}))
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result   (myc/run-workflow
                     {:cells {:start :test/typed-add}
                      :edges {:start :end}}
                     {} {:x "not-an-int" :y 5} {:on-error on-error})]
      (is (some? (myc/workflow-error result))))))

;; ===== 7. Manifest loading with lite schemas =====

(deftest manifest-lite-schema-test
  (testing "Manifest with lite schemas validates via manifest->workflow"
    (cell/defcell :test/compute
      {:doc "Doubles x into result"}
      (fn [_ data] {:result (* 2 (:x data))}))
    (let [m {:id :test-workflow
             :cells {:start {:id :test/compute
                              :doc "Doubles x into result"
                              :schema {:input {:x :int}
                                       :output {:result :int}}
                              :on-error nil}}
             :edges {:start :end}
             :input-schema {:x :int}}
          ;; validate-manifest normalizes lite schemas
          validated (manifest/validate-manifest m {:strict? true})
          ;; manifest->workflow injects schemas into cells
          wf-def   (manifest/manifest->workflow validated)
          result   (myc/run-workflow wf-def {} {:x 5})]
      (is (nil? (myc/workflow-error result)))
      (is (= 10 (:result result)))))

  (testing "Manifest with per-transition lite schemas normalizes correctly"
    (cell/defcell :test/branch
      {:doc "Classifies x as positive or negative"}
      (fn [_ data]
        (if (pos? (:x data))
          {:sign :positive}
          {:sign :negative})))
    (let [m {:id :test-branch-wf
             :cells {:start {:id :test/branch
                              :doc "Classifies x as positive or negative"
                              :schema {:input {:x :int}
                                       :output {:positive {:sign :keyword}
                                                :negative {:sign :keyword}}}
                              :on-error nil}}
             :edges {:start {:positive :end :negative :end}}
             :dispatches {:start [[:positive (fn [d] (= :positive (:sign d)))]
                                  [:negative (fn [d] (= :negative (:sign d)))]]}}
          validated (manifest/validate-manifest m {:strict? true})
          wf-def   (manifest/manifest->workflow validated)]
      ;; Per-transition output with lite values should work
      (let [result (myc/run-workflow wf-def {} {:x 5})]
        (is (nil? (myc/workflow-error result)))
        (is (= :positive (:sign result)))))))

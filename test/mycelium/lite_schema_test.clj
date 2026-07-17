(ns mycelium.lite-schema-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [malli.registry :as mr]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.manifest :as manifest]
            [mycelium.schema :as schema]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. compile-schema: lite maps compile via malli's lite syntax =====

(deftest compile-schema-lite-test
  (testing "Plain maps compile as [:map ...] via malli.experimental.lite"
    (is (= [:map [:subtotal :double] [:state :string]]
           (m/form (schema/compile-schema {:subtotal :double :state :string})))))

  (testing "Nested maps compile recursively"
    (is (= [:map [:address [:map [:street :string] [:city :string]]]]
           (m/form (schema/compile-schema {:address {:street :string :city :string}})))))

  (testing "Vector values inside lite maps are Malli and compile as-is"
    (is (= [:map [:items [:vector :string]] [:count :int]]
           (m/form (schema/compile-schema {:items [:vector :string] :count :int})))))

  (testing "Malli forms compile unchanged"
    (is (= [:map [:x :int]]
           (m/form (schema/compile-schema [:map [:x :int]])))))

  (testing "nil passes through"
    (is (nil? (schema/compile-schema nil))))

  (testing "Already-compiled schemas pass through identically"
    (let [c (schema/compile-schema {:x :int})]
      (is (identical? c (schema/compile-schema c)))))

  (testing "Lite maps resolve values against a local registry"
    (let [reg (mr/composite-registry (m/default-schemas) {:test/id :uuid})
          c   (schema/compile-schema {:id :test/id} {:malli/registry reg})]
      (is (true? (m/validate c {:id (random-uuid)})))
      (is (false? (m/validate c {:id "not-a-uuid"}))))))

;; ===== 2. Registration stores schemas verbatim =====

(deftest defcell-stores-schemas-verbatim-test
  (testing "Lite input and output schemas are stored as written"
    (cell/defcell :test/lite-both
      {:doc    "Increments x into y"
       :input  {:x :int}
       :output {:y :int}}
      (fn [_ data] {:y (inc (:x data))}))
    (let [spec (cell/get-cell :test/lite-both)]
      (is (= {:x :int} (get-in spec [:schema :input])))
      (is (= {:y :int} (get-in spec [:schema :output])))))

  (testing "Malli property maps are stored as written"
    (cell/defcell :test/bounded-email
      {:doc    "Validates a bounded email"
       :input  [:map [:email [:string {:min 5}]]]
       :output [:map {:closed true} [:ok :boolean]]}
      (fn [_ _] {:ok true}))
    (let [spec (cell/get-cell :test/bounded-email)]
      (is (= [:map [:email [:string {:min 5}]]]
             (get-in spec [:schema :input])))
      (is (= [:map {:closed true} [:ok :boolean]]
             (get-in spec [:schema :output])))))

  (testing "Explicit per-transition output is stored as written"
    (cell/defcell :test/per-transition
      {:doc    "Classifies x as high or low"
       :input  {:x :int}
       :output [:per-transition {:high [:map [:result [:= :high]]]
                                 :low  [:map [:result [:= :low]]]}]}
      (fn [_ data]
        {:result (if (> (:x data) 10) :high :low)}))
    (let [output (get-in (cell/get-cell :test/per-transition) [:schema :output])]
      (is (= :per-transition (first output)))
      (is (= #{:high :low} (set (keys (second output)))))))

  (testing "set-cell-schema! stores lite schemas as written"
    (cell/defcell :test/schema-override
      {:doc "Increments x into y"}
      (fn [_ data] {:y (inc (:x data))}))
    (cell/set-cell-schema! :test/schema-override
                           {:input {:x :int} :output {:y :int}})
    (let [spec (cell/get-cell :test/schema-override)]
      (is (= {:x :int} (get-in spec [:schema :input])))
      (is (= {:y :int} (get-in spec [:schema :output]))))))

;; ===== 3. Malformed per-transition wrapper is rejected at compile =====

(deftest malformed-per-transition-test
  (testing "A bad [:per-transition ...] wrapper throws with a helpful message"
    (is (thrown-with-msg? Exception #"Malformed \[:per-transition"
          (schema/compile-cell-schemas
           {:id :test/bad :schema {:output [:per-transition]}}
           {})))))

;; ===== 4. End-to-end: workflows with lite schemas =====

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

;; ===== 5. End-to-end: Malli property maps =====

(deftest property-map-workflow-e2e-test
  (testing "Workflow runs with a property-carrying input schema"
    (cell/defcell :test/min-length
      {:doc    "Echoes a name at least three characters long"
       :input  [:map [:name [:string {:min 3}]]]
       :output {:seen :string}}
      (fn [_ data] {:seen (:name data)}))
    (let [workflow {:cells {:start :test/min-length}
                    :edges {:start :end}}
          ok        (myc/run-workflow workflow {} {:name "Alice"})
          too-short (myc/run-workflow workflow {} {:name "Al"}
                                      {:on-error (fn [_ fsm-state] (:data fsm-state))})]
      (is (nil? (myc/workflow-error ok)))
      (is (= "Alice" (:seen ok)))
      (is (some? (myc/workflow-error too-short)))))

  (testing "Lite maps inside Malli vector forms are rejected by Malli at compile"
    ;; The two dialects cannot be mixed; every mixed form is invalid Malli.
    (cell/defcell :test/mixed
      {:doc    "Mixes dialects illegally"
       :input  :map
       :output [:vector {:x :int}]}
      (fn [_ data] data))
    (is (thrown? Exception
          (myc/pre-compile {:cells {:start :test/mixed}
                            :edges {:start :end}})))))

;; ===== 6. Manifest loading with lite schemas =====

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
          validated (manifest/validate-manifest m {:strict? true})
          wf-def   (manifest/manifest->workflow validated)
          result   (myc/run-workflow wf-def {} {:x 5})]
      (is (nil? (myc/workflow-error result)))
      (is (= 10 (:result result)))))

  (testing "Manifest with per-transition lite schemas works end to end"
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
                                       :output [:per-transition
                                                {:positive {:sign :keyword}
                                                 :negative {:sign :keyword}}]}
                              :on-error nil}}
             :edges {:start {:positive :end :negative :end}}
             :dispatches {:start [[:positive (fn [d] (= :positive (:sign d)))]
                                  [:negative (fn [d] (= :negative (:sign d)))]]}}
          validated (manifest/validate-manifest m {:strict? true})
          wf-def   (manifest/manifest->workflow validated)]
      (let [result (myc/run-workflow wf-def {} {:x 5})]
        (is (nil? (myc/workflow-error result)))
        (is (= :positive (:sign result)))))))

(ns mycelium.generate-stubs-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.dev :as dev]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. Basic stub generation from workflow definition =====

(deftest generate-stubs-basic-test
  (testing "generate-stubs produces defcell forms for each cell"
    (let [stubs (dev/generate-stubs
                  {:cells {:start :app/validate
                           :process :app/transform}
                   :edges {:start :process
                           :process :end}})]
      (is (string? stubs))
      (is (str/includes? stubs "defcell"))
      (is (str/includes? stubs ":app/validate"))
      (is (str/includes? stubs ":app/transform")))))

;; ===== 2. Stubs include schemas when cells have them =====

(deftest generate-stubs-with-schemas-test
  (testing "generate-stubs includes schema from cell definitions"
    (cell/defcell :stub/with-schema
      {:doc    "Passes data through with x/y schema"
       :input  [:map [:x :int]]
       :output [:map [:y :int]]}
      (fn [_ data] data))
    (let [stubs (dev/generate-stubs
                  {:cells {:start :stub/with-schema}
                   :edges {:start :end}})]
      (is (str/includes? stubs ":input"))
      (is (str/includes? stubs ":output"))
      (is (str/includes? stubs "[:map [:x :int]]")))))

;; ===== 3. Stubs for unregistered cells produce TODO handler =====

(deftest generate-stubs-unregistered-test
  (testing "generate-stubs produces TODO stubs for unregistered cells"
    (let [stubs (dev/generate-stubs
                  {:cells {:start :app/unknown-cell}
                   :edges {:start :end}})]
      (is (str/includes? stubs ":app/unknown-cell"))
      (is (str/includes? stubs "TODO")))))

;; ===== 4. Stubs from manifest with full cell definitions =====

(deftest generate-stubs-from-manifest-test
  (testing "generate-stubs works with manifest-style cell definitions"
    (let [stubs (dev/generate-stubs
                  {:cells {:start {:id     :app/validate
                                   :schema {:input  [:map [:name :string]]
                                            :output [:map [:valid :boolean]]}}}
                   :edges {:start :end}})]
      (is (str/includes? stubs ":app/validate"))
      (is (str/includes? stubs "[:map [:name :string]]"))
      (is (str/includes? stubs "[:map [:valid :boolean]]")))))

;; ===== 5. Stubs include per-transition output schemas =====

(deftest generate-stubs-branching-output-test
  (testing "generate-stubs handles per-transition output schemas"
    (cell/defcell :stub/branching
      {:doc    "Classifies input as high or low"
       :input  [:map [:x :int]]
       :output {:high [:map [:result [:= :high]]]
                :low  [:map [:result [:= :low]]]}}
      (fn [_ data] data))
    (let [stubs (dev/generate-stubs
                  {:cells {:start :stub/branching}
                   :edges {:start {:high :end :low :end}}
                   :dispatches {:start [[:high (fn [d] (= :high (:result d)))]
                                        [:low (fn [d] (= :low (:result d)))]]}})]
      (is (str/includes? stubs ":high"))
      (is (str/includes? stubs ":low")))))

;; ===== 6. Stubs include :requires =====

(deftest generate-stubs-with-requires-test
  (testing "generate-stubs includes :requires from cell spec"
    (cell/defcell :stub/needs-db
      {:doc      "Fetches user by id from database"
       :input    [:map [:id :string]]
       :output   [:map [:user :map]]
       :requires [:db]}
      (fn [{:keys [db]} data] data))
    (let [stubs (dev/generate-stubs
                  {:cells {:start :stub/needs-db}
                   :edges {:start :end}})]
      (is (str/includes? stubs ":requires"))
      (is (str/includes? stubs ":db")))))

;; ===== 7. Stubs include :doc =====

(deftest generate-stubs-with-doc-test
  (testing "generate-stubs includes :doc from cell spec"
    (cell/defcell :stub/documented
      {:doc    "Validates user input"
       :input  [:map]
       :output [:map]}
      (fn [_ data] data))
    (let [stubs (dev/generate-stubs
                  {:cells {:start :stub/documented}
                   :edges {:start :end}})]
      (is (str/includes? stubs "Validates user input")))))

;; ===== 8. Output is valid Clojure (parseable) =====

(deftest generate-stubs-parseable-test
  (testing "generate-stubs produces parseable Clojure code"
    (let [stubs (dev/generate-stubs
                  {:cells {:start :app/step-a
                           :next  :app/step-b}
                   :edges {:start :next
                           :next  :end}})]
      ;; Should parse without error
      (is (every? some?
                  (read-string (str "[" stubs "]")))))))

(ns mycelium.defcell-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== 1. Basic defcell registers cell with correct spec =====

(deftest defcell-basic-registration-test
  (testing "defcell registers a cell retrievable from registry"
    (cell/defcell :test/basic
      {:doc    "Doubles the input x value"
       :input  [:map [:x :int]]
       :output [:map [:y :int]]}
      (fn [_ data] {:y (* 2 (:x data))}))
    (let [spec (cell/get-cell :test/basic)]
      (is (some? spec))
      (is (= :test/basic (:id spec)))
      (is (= "Doubles the input x value" (:doc spec)))
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output])))
      (is (fn? (:handler spec))))))

;; ===== 2. Handler executes correctly =====

(deftest defcell-handler-executes-test
  (testing "defcell handler is callable and produces correct output"
    (cell/defcell :test/double
      {:doc    "Doubles the input x value and returns as :result"
       :input  [:map [:x :int]]
       :output [:map [:result :int]]}
      (fn [_ data] {:result (* 2 (:x data))}))
    (let [handler (:handler (cell/get-cell :test/double))]
      (is (= {:result 10} (handler {} {:x 5}))))))

;; ===== 3. defcell without schema =====

(deftest defcell-no-schema-test
  (testing "defcell works without schema (nil schema) but requires :doc"
    (cell/defcell :test/no-schema
      {:doc "Passes data through unchanged"}
      (fn [_ data] data))
    (let [spec (cell/get-cell :test/no-schema)]
      (is (some? spec))
      (is (= :test/no-schema (:id spec)))
      (is (= "Passes data through unchanged" (:doc spec)))
      (is (nil? (:schema spec)))
      (is (fn? (:handler spec))))))

;; ===== 4. defcell with opts map (doc, requires, async?) =====

(deftest defcell-with-opts-test
  (testing "defcell accepts optional :requires, :async? alongside required :doc"
    (cell/defcell :test/with-opts
      {:input  [:map [:x :int]]
       :output [:map [:y :int]]
       :doc "Increments x using database lookup"
       :requires [:db]
       :async? true}
      (fn [resources data callback _error-callback]
        (callback {:y (inc (:x data))})))
    (let [spec (cell/get-cell :test/with-opts)]
      (is (= "Increments x using database lookup" (:doc spec)))
      (is (= [:db] (:requires spec)))
      (is (true? (:async? spec))))))

;; ===== 5. defcell with per-transition output schema =====

(deftest defcell-per-transition-output-test
  (testing "defcell supports per-transition output schemas"
    (cell/defcell :test/branching
      {:doc    "Classifies input as high or low based on threshold"
       :input  [:map [:x :int]]
       :output {:high [:map [:result [:= :high]]]
                :low  [:map [:result [:= :low]]]}}
      (fn [_ data]
        {:result (if (> (:x data) 10) :high :low)}))
    (let [spec (cell/get-cell :test/branching)]
      (is (map? (get-in spec [:schema :output])))
      (is (= #{:high :low} (set (keys (get-in spec [:schema :output]))))))))

;; ===== 6. defcell works in workflow =====

(deftest defcell-in-workflow-test
  (testing "Cell registered via defcell works in a compiled workflow"
    (cell/defcell :test/add-ten
      {:doc    "Adds 10 to the input x value"
       :input  [:map [:x :int]]
       :output [:map [:result :int]]}
      (fn [_ data] {:result (+ (:x data) 10)}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/add-ten}
                    :edges {:start :end}}
                   {} {:x 5})]
      (is (nil? (myc/workflow-error result)))
      (is (= 15 (:result result))))))

;; ===== 7. defcell ID deduplication — no need to repeat ID =====

(deftest defcell-id-not-duplicated-test
  (testing "defcell sets :id automatically from the first argument"
    (cell/defcell :test/auto-id
      {:doc "Identity cell for testing ID assignment" :input [:map] :output [:map]}
      (fn [_ data] data))
    (is (= :test/auto-id (:id (cell/get-cell :test/auto-id))))))

;; ===== 8. Multiple defcells coexist =====

(deftest defcell-multiple-coexist-test
  (testing "Multiple defcell registrations coexist"
    (cell/defcell :test/cell-a
      {:doc "Extracts x as a" :input [:map [:x :int]] :output [:map [:a :int]]}
      (fn [_ data] {:a (:x data)}))
    (cell/defcell :test/cell-b
      {:doc "Doubles a into b" :input [:map [:a :int]] :output [:map [:b :int]]}
      (fn [_ data] {:b (* 2 (:a data))}))
    (is (some? (cell/get-cell :test/cell-a)))
    (is (some? (cell/get-cell :test/cell-b)))
    (is (= :test/cell-a (:id (cell/get-cell :test/cell-a))))
    (is (= :test/cell-b (:id (cell/get-cell :test/cell-b))))))

;; ===== 9. defcell overrides are cleared by clear-registry! =====

(deftest defcell-cleared-by-registry-test
  (testing "defcell registrations are cleared by clear-registry!"
    (cell/defcell :test/clearable
      {:doc "Passthrough cell for testing registry clear"}
      (fn [_ data] data))
    (is (some? (cell/get-cell :test/clearable)))
    (cell/clear-registry!)
    (is (nil? (cell/get-cell :test/clearable)))))

;; ===== 10. defcell requires :doc =====

(deftest defcell-requires-doc-test
  (testing "defcell throws when :doc is missing"
    (is (thrown-with-msg? Exception #":doc"
          (cell/defcell :test/no-doc
            {:input [:map] :output [:map]}
            (fn [_ data] data)))))
  (testing "defcell throws when :doc is empty string"
    (is (thrown-with-msg? Exception #":doc"
          (cell/defcell :test/empty-doc
            {:doc "" :input [:map] :output [:map]}
            (fn [_ data] data)))))
  (testing "defcell throws when :doc is not a string"
    (is (thrown-with-msg? Exception #":doc"
          (cell/defcell :test/bad-doc
            {:doc 42 :input [:map] :output [:map]}
            (fn [_ data] data))))))

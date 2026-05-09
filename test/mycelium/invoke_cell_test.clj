(ns mycelium.invoke-cell-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== happy path =====

(deftest invoke-cell-with-no-schema-test
  (testing "a cell without :input/:output schemas runs and returns its handler result"
    (myc/defcell :ic-test/echo
      {:doc "Echoes back the input."}
      (fn [_ data] {:got data}))
    (is (= {:got {:x 1}}
           (myc/invoke-cell :ic-test/echo {} {:x 1})))))

(deftest invoke-cell-with-schema-roundtrip-test
  (testing "a cell with both :input and :output schemas validates and runs"
    (myc/defcell :ic-test/double
      {:doc    "Doubles :n."
       :input  [:map [:n :int]]
       :output [:map [:doubled :int]]}
      (fn [_ {:keys [n]}] {:doubled (* n 2)}))
    (is (= {:doubled 14}
           (myc/invoke-cell :ic-test/double {} {:n 7})))))

(deftest invoke-cell-passes-resources-test
  (testing "resources flow through to the handler"
    (myc/defcell :ic-test/uses-db
      {:doc "Reads the :db resource."
       :requires [:db]
       :input  [:map]
       :output [:map [:from :string]]}
      (fn [{:keys [db]} _] {:from db}))
    (is (= {:from "stub-db"}
           (myc/invoke-cell :ic-test/uses-db {:db "stub-db"} {})))))

;; ===== :requires =====

(deftest invoke-cell-throws-on-missing-required-resource-test
  (testing "missing entry in :requires throws a precise error"
    (myc/defcell :ic-test/needs-db
      {:doc "Needs :db."
       :requires [:db :http]}
      (fn [_ _] {}))
    (let [thrown (try (myc/invoke-cell :ic-test/needs-db {:http :ok} {})
                      (catch clojure.lang.ExceptionInfo e e))
          data   (ex-data thrown)]
      (is (some? thrown))
      (is (= :mycelium.invoke-cell/missing-resources (:type data)))
      (is (= [:db]      (:missing data)))
      (is (= [:db :http] (:requires data)))
      (is (= :ic-test/needs-db (:cell-id data))))))

(deftest invoke-cell-allows-extra-resources-test
  (testing "resources may carry keys the cell didn't declare"
    (myc/defcell :ic-test/db-only
      {:doc      "Needs only :db."
       :requires [:db]}
      (fn [_ _] {}))
    (is (= {} (myc/invoke-cell :ic-test/db-only
                               {:db "x" :extra "y"}
                               {})))))

;; ===== input schema =====

(deftest invoke-cell-throws-on-input-schema-failure-test
  (testing "input that doesn't conform to the cell's :input schema throws"
    (myc/defcell :ic-test/strict
      {:doc   "Wants an :int :n."
       :input [:map [:n :int]]}
      (fn [_ d] d))
    (let [thrown (try (myc/invoke-cell :ic-test/strict {} {:n "not-an-int"})
                      (catch clojure.lang.ExceptionInfo e e))
          data   (ex-data thrown)]
      (is (some? thrown))
      (is (= :mycelium.invoke-cell/input-error (:type data)))
      (is (some? (:errors data))))))

(deftest invoke-cell-input-open-map-semantics-test
  (testing "extra keys in the input map are allowed (open-map semantics)"
    (myc/defcell :ic-test/open
      {:doc   "Wants :n; tolerates extras."
       :input [:map [:n :int]]
       :output [:map [:n :int]]}
      (fn [_ {:keys [n]}] {:n n}))
    (is (= {:n 1}
           (myc/invoke-cell :ic-test/open {} {:n 1 :extra "ignored"})))))

;; ===== output schema =====

(deftest invoke-cell-throws-on-output-schema-failure-test
  (testing "a handler that returns the wrong shape throws"
    (myc/defcell :ic-test/bad-output
      {:doc    "Claims it returns :double, actually returns a string."
       :input  [:map]
       :output [:map [:result :double]]}
      (fn [_ _] {:result "oops"}))
    (let [thrown (try (myc/invoke-cell :ic-test/bad-output {} {})
                      (catch clojure.lang.ExceptionInfo e e))
          data   (ex-data thrown)]
      (is (some? thrown))
      (is (= :mycelium.invoke-cell/output-error (:type data))))))

;; ===== :validate? :off =====

(deftest invoke-cell-validate-off-skips-checks-test
  (testing ":validate? :off skips :requires + input + output schema checks"
    (myc/defcell :ic-test/strict
      {:doc      "Strict cell."
       :requires [:db]
       :input    [:map [:n :int]]
       :output   [:map [:n :int]]}
      (fn [_ d] (assoc d :extra "fine because no validation")))
    ;; No :db, bad :n type, output adds an undeclared key.
    ;; All would throw under :strict; passes under :off.
    (is (= {:n "string" :extra "fine because no validation"}
           (myc/invoke-cell :ic-test/strict {} {:n "string"} {:validate? :off})))))

;; ===== cell-not-found =====

(deftest invoke-cell-throws-when-cell-id-unknown-test
  (let [thrown (try (myc/invoke-cell :ic-test/never-defined {} {})
                    (catch clojure.lang.ExceptionInfo e e))
        data   (ex-data thrown)]
    (is (some? thrown))
    (is (= :mycelium.invoke-cell/cell-not-found (:type data)))
    (is (= :ic-test/never-defined (:cell-id data)))))

;; ===== matches workflow semantics for the same cell =====

(deftest invoke-cell-matches-workflow-result-test
  (testing "the same cell run via invoke-cell matches its result inside a 1-cell workflow"
    (myc/defcell :ic-test/inc-n
      {:doc    "Increment :n."
       :input  [:map [:n :int]]
       :output [:map [:n :int]]}
      (fn [_ {:keys [n]}] {:n (inc n)}))
    (let [direct  (myc/invoke-cell :ic-test/inc-n {} {:n 5})
          via-wf  (myc/run-workflow {:cells    {:start :ic-test/inc-n}
                                     :pipeline [:start]}
                                    {} {:n 5})]
      (is (= 6 (:n direct)))
      ;; Workflow auto-merges input keys forward; the result includes :n
      (is (= 6 (:n via-wf))))))

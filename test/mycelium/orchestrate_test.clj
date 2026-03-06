(ns mycelium.orchestrate-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.manifest :as manifest]
            [mycelium.orchestrate :as orchestrate]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(def test-manifest
  {:id :test/workflow
   :doc "Test workflow"
   :cells {:start
           {:id       :test/step-a
            :doc      "Step A"
            :schema   {:input  [:map [:x :int]]
                       :output [:map [:y :int]]}
            :requires []}
           :process
           {:id       :test/step-b
            :doc      "Step B"
            :schema   {:input  [:map [:y :int]]
                       :output [:map [:z :string]]}
            :requires [:db]}}
   :edges {:start   {:next :process}
           :process {:done :end, :error :error}}
   :dispatches {:start   [[:next (constantly true)]]
                :process [[:done  (fn [d] (:z d))]
                          [:error (fn [d] (not (:z d)))]]}})

;; ===== 1. cell-briefs returns brief for every cell =====

(deftest cell-briefs-returns-all-test
  (testing "cell-briefs returns brief for every cell in manifest"
    (let [briefs (orchestrate/cell-briefs test-manifest)]
      (is (= 2 (count briefs)))
      (is (contains? briefs :start))
      (is (contains? briefs :process))
      (is (string? (get-in briefs [:start :prompt])))
      (is (string? (get-in briefs [:process :prompt]))))))

;; ===== 2. reassignment-brief includes error context =====

(deftest reassignment-brief-includes-error-context-test
  (testing "reassignment-brief includes error context in prompt"
    (let [brief (orchestrate/reassignment-brief
                 test-manifest :process
                 {:error  "Output missing key :z"
                  :input  {:y 42}
                  :output {:y 42}})]
      (is (string? (:prompt brief)))
      (is (re-find #"missing" (:prompt brief)))
      (is (re-find #":z" (:prompt brief)))
      (is (re-find #"42" (:prompt brief))))))

;; ===== 3. plan identifies independent cells =====

(deftest plan-identifies-parallelizable-test
  (testing "plan identifies independent cells as parallelizable"
    (let [p (orchestrate/plan test-manifest)]
      (is (contains? p :parallel))
      (is (contains? p :scaffold))
      ;; All cells should appear in scaffold
      (is (= 2 (count (:scaffold p)))))))

;; ===== 4. progress reflects current status =====

(deftest progress-reflects-status-test
  (testing "progress reflects current status"
    ;; Register one cell, leave the other unregistered
    (defmethod cell/cell-spec :test/step-a [_]
      {:id          :test/step-a
       :handler     (fn [_ data]
                      (assoc data :y (inc (:x data))))
       :schema      {:input [:map [:x :int]]
                     :output [:map [:y :int]]}})

    (let [report (orchestrate/progress test-manifest)]
      (is (string? report))
      ;; Should mention the workflow
      (is (re-find #"test/workflow" report))
      ;; Should show total count and pending info
      (is (re-find #"2 total" report))
      (is (re-find #"1 pending" report)))))

;; ===== Region briefs =====

(def region-manifest
  {:id :test/region-workflow
   :cells {:start
           {:id :auth/parse
            :doc "Parse incoming request"
            :schema {:input [:map [:raw :string]]
                     :output [:map [:token :string]]}
            :on-error nil}
           :validate-session
           {:id :auth/validate
            :doc "Validate session token"
            :schema {:input [:map [:token :string]]
                     :output [:map [:user-id :int]]}
            :on-error nil}
           :fetch-profile
           {:id :user/fetch-profile
            :doc "Fetch user profile"
            :schema {:input [:map [:user-id :int]]
                     :output [:map [:profile [:map]]]}
            :requires [:db]
            :on-error nil}
           :render
           {:id :ui/render
            :doc "Render output"
            :schema {:input [:map [:profile [:map]]]
                     :output [:map [:html :string]]}
            :on-error nil}}
   :edges {:start            {:ok :validate-session}
           :validate-session {:authorized :fetch-profile, :unauthorized :end}
           :fetch-profile    :render
           :render           :end}
   :dispatches {:start            [[:ok (constantly true)]]
                :validate-session [[:authorized (fn [d] (:user-id d))]
                                   [:unauthorized (fn [d] (not (:user-id d)))]]}
   :regions {:auth       [:start :validate-session]
             :data-fetch [:fetch-profile]}})

;; ===== Round 1: Validation — region cells must exist =====

(deftest region-cells-must-exist-test
  (testing "Region referencing nonexistent cell throws"
    (is (thrown-with-msg? Exception #"region.*nonexistent"
          (manifest/validate-manifest
            (assoc region-manifest
                   :regions {:bad [:nonexistent]}))))))

;; ===== Round 2: Validation — regions must not overlap =====

(deftest regions-must-not-overlap-test
  (testing "Two regions sharing a cell throws"
    (is (thrown-with-msg? Exception #"overlap|multiple"
          (manifest/validate-manifest
            (assoc region-manifest
                   :regions {:a [:start]
                             :b [:start :validate-session]}))))))

;; ===== Round 3: Basic region-brief returns cell info =====

(deftest region-brief-returns-cells-test
  (testing "region-brief returns cell info for each cell in region"
    (let [brief (orchestrate/region-brief region-manifest :auth)]
      (is (= 2 (count (:cells brief))))
      (is (some #(= :auth/parse (:id %)) (:cells brief)))
      (is (some #(= :auth/validate (:id %)) (:cells brief)))
      (is (some #(some? (:schema %)) (:cells brief))))))

;; ===== Round 4: region-brief returns internal edges =====

(deftest region-brief-internal-edges-test
  (testing "Edges between region cells appear in :internal-edges"
    (let [brief (orchestrate/region-brief region-manifest :auth)]
      (is (= {:start {:ok :validate-session}}
             (:internal-edges brief))))))

;; ===== Round 5: region-brief identifies entry points =====

(deftest region-brief-entry-points-test
  (testing "Entry points are cells reachable from outside the region"
    (let [brief (orchestrate/region-brief region-manifest :auth)]
      (is (= [:start] (:entry-points brief))))))

;; ===== Round 6: region-brief identifies exit points =====

(deftest region-brief-exit-points-test
  (testing "Exit points are cells with edges leaving the region"
    (let [brief (orchestrate/region-brief region-manifest :auth)]
      ;; validate-session has :authorized → :fetch-profile (outside) and :unauthorized → :end
      (is (= 1 (count (:exit-points brief))))
      (let [exit (first (:exit-points brief))]
        (is (= :validate-session (:cell exit)))
        (is (= {:authorized :fetch-profile, :unauthorized :end}
               (:transitions exit)))))))

;; ===== Round 7: region-brief generates prompt =====

(deftest region-brief-prompt-test
  (testing "region-brief generates a prompt string"
    (let [brief (orchestrate/region-brief region-manifest :auth)]
      (is (string? (:prompt brief)))
      (is (re-find #"auth" (:prompt brief)))
      (is (re-find #"auth/parse" (:prompt brief)))
      (is (re-find #"auth/validate" (:prompt brief))))))

;; ===== Round 8: region with unconditional exit edge =====

(deftest region-brief-unconditional-exit-test
  (testing "Region with unconditional edge leaving the region returns map transitions"
    (let [brief (orchestrate/region-brief region-manifest :data-fetch)]
      (is (= 1 (count (:exit-points brief))))
      (let [exit (first (:exit-points brief))]
        (is (= :fetch-profile (:cell exit)))
        ;; Unconditional exit should be wrapped as a map for consistent interface
        (is (map? (:transitions exit))
            "Transitions should always be a map, even for unconditional edges")))))

;; ===== Round 9: region-brief for unknown region throws =====

(deftest region-brief-unknown-region-test
  (testing "region-brief for nonexistent region throws"
    (is (thrown-with-msg? Exception #"region.*:nonexistent"
          (orchestrate/region-brief region-manifest :nonexistent)))))

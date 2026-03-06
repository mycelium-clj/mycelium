(ns expense.workflow-test
  (:require [clojure.test :refer [deftest is testing]]
            [expense.workflows :as wf]
            [mycelium.core :as myc]
            [mycelium.store :as store]))

;; --- Direct run/resume (no store) ---

(deftest small-expense-auto-approved-test
  (testing "Expenses <= $100 are auto-approved without halting"
    (let [result (myc/run-compiled wf/compiled {}
                   {:expense-id  "EXP-001"
                    :submitter   "alice@co.com"
                    :amount      50
                    :description "Office supplies"})]
      (is (= :approved (:decision result)))
      (is (= "policy:auto-small" (:approved-by result)))
      (is (= "alice@co.com" (get-in result [:notification :to]))))))

(deftest medium-expense-halts-for-approval-test
  (testing "Expenses > $100 halt for manager approval"
    (let [result (myc/run-compiled wf/compiled {}
                   {:expense-id  "EXP-002"
                    :submitter   "bob@co.com"
                    :amount      500
                    :description "Conference ticket"})]
      (is (some? (:mycelium/halt result)) "Workflow halted")
      (is (= :manager-approval-needed (get-in result [:mycelium/halt :reason])))
      (is (= 500 (get-in result [:mycelium/halt :amount]))))))

(deftest resume-approved-test
  (testing "Manager approves → notification sent"
    (let [halted (myc/run-compiled wf/compiled {}
                   {:expense-id  "EXP-003"
                    :submitter   "bob@co.com"
                    :amount      500
                    :description "Conference ticket"})
          result (myc/resume-compiled wf/compiled {} halted
                   {:manager-approved true
                    :manager-name     "carol"})]
      (is (= :approved (:decision result)))
      (is (= "manager:carol" (:approved-by result)))
      (is (= "bob@co.com" (get-in result [:notification :to]))))))

(deftest resume-rejected-test
  (testing "Manager rejects → rejection notification sent"
    (let [halted (myc/run-compiled wf/compiled {}
                   {:expense-id  "EXP-004"
                    :submitter   "bob@co.com"
                    :amount      2000
                    :description "New laptop"})
          result (myc/resume-compiled wf/compiled {} halted
                   {:manager-approved false
                    :manager-name     "carol"
                    :rejection-reason "Over budget"})]
      (is (= :rejected (:decision result)))
      (is (= "manager:carol" (:rejected-by result)))
      (is (clojure.string/includes?
            (get-in result [:notification :body]) "rejected")))))

(deftest invalid-expense-test
  (testing "Invalid expense exits early"
    (let [result (myc/run-compiled wf/compiled {}
                   {:expense-id  "EXP-005"
                    :submitter   "bob@co.com"
                    :amount      -10
                    :description "Bad expense"})]
      (is (= :invalid (:validation-status result)))
      (is (nil? (:decision result))))))

;; --- Store-backed workflow ---

(deftest store-full-lifecycle-test
  (testing "Full halt/resume cycle through the store"
    (let [s (store/memory-store)

          ;; 1. Submit expense — halts for approval
          halted (store/run-with-store wf/compiled {}
                   {:expense-id  "EXP-100"
                    :submitter   "dave@co.com"
                    :amount      750
                    :description "Team dinner"}
                   s)
          sid (:mycelium/session-id halted)]

      (is (string? sid) "Got a session ID back")
      (is (= :manager-approval-needed (get-in halted [:mycelium/halt :reason])))

      ;; 2. Inspect pending workflows
      (is (= [sid] (vec (store/list-workflows s))))

      ;; 3. Manager approves via session ID
      (let [result (store/resume-with-store wf/compiled {} sid s
                     {:manager-approved true
                      :manager-name     "eve"})]

        (is (= :approved (:decision result)))
        (is (= "manager:eve" (:approved-by result)))
        (is (some? (:notification result)) "Notification was sent")

        ;; 4. Store cleaned up
        (is (empty? (store/list-workflows s)) "Session removed after completion")))))

(deftest store-rejection-lifecycle-test
  (testing "Store-backed rejection"
    (let [s (store/memory-store)
          halted (store/run-with-store wf/compiled {}
                   {:expense-id  "EXP-101"
                    :submitter   "frank@co.com"
                    :amount      5000
                    :description "Server hardware"}
                   s)
          sid (:mycelium/session-id halted)
          result (store/resume-with-store wf/compiled {} sid s
                   {:manager-approved false
                    :manager-name     "eve"
                    :rejection-reason "Use cloud instead"})]

      (is (= :rejected (:decision result)))
      (is (clojure.string/includes?
            (get-in result [:notification :body]) "Use cloud instead"))
      (is (empty? (store/list-workflows s))))))

(deftest store-small-expense-no-persist-test
  (testing "Small expenses complete without touching the store"
    (let [s (store/memory-store)
          result (store/run-with-store wf/compiled {}
                   {:expense-id  "EXP-102"
                    :submitter   "grace@co.com"
                    :amount      25
                    :description "Lunch"}
                   s)]

      (is (= :approved (:decision result)))
      (is (nil? (:mycelium/session-id result)) "No session ID — didn't halt")
      (is (empty? (store/list-workflows s)) "Nothing persisted"))))

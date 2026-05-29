(ns mycelium.queue-test
  (:require [clojure.test :refer [deftest is testing]]
            [mycelium.queue :as q]))

;; ===== Memory queue basics =====

(deftest memory-queue-enqueue-test
  (testing "enqueue! returns a UUID task-id"
    (let [mq (q/memory-queue)]
      (let [task-id (q/enqueue! mq :my-workflow {:x 1})]
        (is (uuid? task-id) "Returns a UUID")
        (is (= 1 (q/queue-depth mq)) "Queue has one task")))))

(deftest memory-queue-claim-test
  (testing "claim! gets next available task"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf-a {:x 1})
      (q/enqueue! mq :wf-b {:x 2})
      (let [task (q/claim! mq "worker-1")]
        (is (some? task) "Task is claimed")
        (is (= :wf-a (:workflow-name task)))
        (is (= {:x 1} (:data task)))
        (is (uuid? (:task-id task)))
        (is (= 0 (:attempt task)) "First attempt is 0")
        (is (= 2 (q/queue-depth mq)) "Claimed task still counts until completed"))
      (let [task2 (q/claim! mq "worker-1")]
        (is (= :wf-b (:workflow-name task2)))))))

(deftest memory-queue-claim-empty-test
  (testing "claim! on empty queue returns nil"
    (let [mq (q/memory-queue)]
      (is (nil? (q/claim! mq "worker-1"))))))

(deftest memory-queue-complete-test
  (testing "complete! removes task from queue"
    (let [mq (q/memory-queue)]
      (let [task-id (q/enqueue! mq :wf {:x 1})]
        (let [task (q/claim! mq "w1")]
          (q/complete! mq (:task-id task) "w1" {:result "done"})
          (is (= 0 (q/queue-depth mq)) "Queue empty after completion"))))))

(deftest memory-queue-fail-test
  (testing "fail! increments attempt and re-queues with backoff"
    (let [mq (q/memory-queue)]
      (let [task-id (q/enqueue! mq :wf {:x 1} {:max-attempts 3})]
        (let [task (q/claim! mq "w1")]
          (q/fail! mq (:task-id task) "w1" (ex-info "boom" {}))
          ;; Backoff: 1s for attempt 1 — wait then reclaim
          (Thread/sleep 1100)
          (let [retried (q/claim! mq "w2")]
            (is (some? retried) "Task re-queued after backoff")
            (is (= 1 (:attempt retried)) "Attempt incremented")))))))

(deftest memory-queue-fail-backoff-test
  (testing "fail! re-queues task with future run-at"
    (let [mq (q/memory-queue)
          before-ms (System/currentTimeMillis)]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 3})
      (let [task (q/claim! mq "w1")]
        (q/fail! mq (:task-id task) "w1" (ex-info "boom" {}))
        ;; Task not claimable immediately — run-at is in the future
        (is (nil? (q/claim! mq "w2")) "Not claimable during backoff")
        ;; Wait for backoff to expire
        (Thread/sleep 2000)
        (let [retried (q/claim! mq "w3")]
          (is (some? retried) "Claimable after backoff")
          (is (= 1 (:attempt retried)) "Attempt incremented"))))))

(deftest memory-queue-fail-max-attempts-test
  (testing "fail! after max-attempts marks task dead"
    (let [mq (q/memory-queue)]
      (let [task-id (q/enqueue! mq :wf {:x 1} {:max-attempts 2})]
        (let [task (q/claim! mq "w1")]
          (q/fail! mq (:task-id task) "w1" (ex-info "boom1" {})))
        ;; Wait for backoff, reclaim
        (Thread/sleep 1100)
        (let [task (q/claim! mq "w2")]
          (is (= 1 (:attempt task)))
          (q/fail! mq (:task-id task) "w2" (ex-info "boom2" {})))
        ;; After max-attempts exhausted, task is dead
        (Thread/sleep 2100) ;; attempt 2 backoff = 2s
        (is (nil? (q/claim! mq "w3")) "Task exhausted retries")
        (is (= 0 (q/queue-depth mq)) "Dead task removed from queue")))))

(deftest memory-queue-delayed-enqueue-test
  (testing "enqueue! with :run-at schedules for future"
    (let [mq (q/memory-queue)
          future-time (+ (System/currentTimeMillis) 3600000)
          task-id (q/enqueue! mq :wf {:x 1} {:run-at future-time})]
      (is (uuid? task-id))
      (is (nil? (q/claim! mq "w1")) "Future task not claimable")
      (is (= 1 (q/queue-depth mq)) "But still counts as pending"))))

(deftest memory-queue-heartbeat-test
  (testing "heartbeat! refreshes claim"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf {:x 1})
      (let [task (q/claim! mq "w1")]
        (is (some? task))
        (q/heartbeat! mq (:task-id task) "w1")
        (is (= 1 (q/queue-depth mq)))))))

(deftest memory-queue-heartbeat-wrong-worker-is-noop-test
  (testing "heartbeat! from wrong worker is ignored"
    (let [mq (q/memory-queue {:claim-timeout-ms 10})]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 2})
      (let [task (q/claim! mq "worker-a")]
        (q/heartbeat! mq (:task-id task) "worker-b")
        ;; Lease should not be extended — wait past timeout
        (Thread/sleep 20)
        ;; First claim drains the expired lease and re-queues with backoff
        (is (nil? (q/claim! mq "worker-b")) "Not reclaimable during backoff")
        (Thread/sleep 1100) ;; attempt 1 backoff = 1s
        (let [reclaimed (q/claim! mq "worker-b")]
          (is (some? reclaimed) "Task reclaimed — wrong-worker heartbeat was no-op"))))))

(deftest memory-queue-claimed-test
  (testing "claimed? returns true when lease is valid"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf {:x 1})
      (let [task (q/claim! mq "w1")]
        (is (q/claimed? mq (:task-id task) "w1") "Owner sees claim as valid")
        (is (not (q/claimed? mq (:task-id task) "w2")) "Other worker sees claim as invalid")))))

(deftest memory-queue-claimed-expired-test
  (testing "claimed? returns false after lease expiry"
    (let [mq (q/memory-queue {:claim-timeout-ms 5})]
      (q/enqueue! mq :wf {:x 1})
      (let [task (q/claim! mq "w1")]
        (is (q/claimed? mq (:task-id task) "w1") "Claim valid initially")
        (Thread/sleep 10)
        (is (not (q/claimed? mq (:task-id task) "w1")) "Claim expired")))))

(deftest memory-queue-claim-lease-expiry-test
  (testing "claim! lease expires and task is reclaimable after backoff"
    (let [mq (q/memory-queue {:claim-timeout-ms 1})]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 2})
      (let [task (q/claim! mq "w1")]
        (is (some? task))
        (Thread/sleep 10)
        ;; First claim drains the expired lease and re-queues with backoff
        (is (nil? (q/claim! mq "w2")) "Not reclaimable during backoff")
        (Thread/sleep 1100) ;; attempt 1 backoff = 1s
        (let [reclaimed (q/claim! mq "w2")]
          (is (some? reclaimed) "Expired task reclaimable after backoff")
          (is (= (:task-id task) (:task-id reclaimed)) "Same task reclaimed")
          (is (= 1 (:attempt reclaimed)) "Attempt incremented on reclaim"))))))

(deftest memory-queue-lease-expiry-respects-max-attempts-test
  (testing "claim! lease expiry dead-letters when max-attempts exhausted"
    (let [mq (q/memory-queue {:claim-timeout-ms 1})]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 2})
      (let [task (q/claim! mq "w1")]
        (is (= 0 (:attempt task)))
        (Thread/sleep 10)
        (is (nil? (q/claim! mq "w2")) "Not reclaimable during backoff")
        (Thread/sleep 1100) ;; attempt 1 backoff = 1s
        (let [reclaimed (q/claim! mq "w2")]
          (is (some? reclaimed) "Reclaimed after first expiry + backoff")
          (is (= 1 (:attempt reclaimed))))
        (Thread/sleep 10)
        (let [gone (q/claim! mq "w3")]
          (is (nil? gone) "Task dead-lettered when attempts exhausted via lease expiry")
          (is (= 0 (q/queue-depth mq)) "Dead task removed from queue"))))))

(deftest memory-queue-complete-from-wrong-worker-is-noop-test
  (testing "complete! from a worker that doesn't own the claim is a no-op"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf {:x 1})
      (let [task (q/claim! mq "worker-a")]
        (q/complete! mq (:task-id task) "worker-b" {:result "stolen"})
        (is (= 1 (q/queue-depth mq)) "Task still present (wrong-worker complete! was no-op)")
        (q/complete! mq (:task-id task) "worker-a" {:result "legit"})
        (is (= 0 (q/queue-depth mq)) "Owner can still complete")))))

(deftest memory-queue-fail-from-wrong-worker-is-noop-test
  (testing "fail! from a worker that doesn't own the claim is a no-op"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 2})
      (let [task (q/claim! mq "worker-a")]
        (q/fail! mq (:task-id task) "worker-b"  (ex-info "not mine" {}))
        (is (= 1 (q/queue-depth mq)) "Task still present (wrong-worker fail! was no-op)")
        (q/fail! mq (:task-id task) "worker-a" (ex-info "legit failure" {}))
        ;; Wait for backoff, then reclaim
        (Thread/sleep 1100)
        (let [retried (q/claim! mq "worker-a")]
          (is (= 1 (:attempt retried)) "Legit fail! caused re-queue"))))))

(deftest memory-queue-complete-after-lease-expiry-is-noop-test
  (testing "complete! after lease expiry is a no-op"
    (let [mq (q/memory-queue {:claim-timeout-ms 1})]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 2})
      (let [task (q/claim! mq "w1")]
        (Thread/sleep 10)
        (q/complete! mq (:task-id task) "w1" {:result "too late"})
        ;; First claim drains the expired lease and re-queues with backoff
        (is (nil? (q/claim! mq "w2")) "Not reclaimable during backoff")
        (Thread/sleep 1100) ;; attempt 1 backoff = 1s
        (let [reclaimed (q/claim! mq "w2")]
          (is (some? reclaimed) "Task reclaimed after stale complete! was no-op")
          (is (= 1 (:attempt reclaimed))
              "Attempt incremented from lease expiry (not from stale complete!)"))))))

(deftest memory-queue-fifo-order-test
  (testing "Tasks are claimed in FIFO order"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :first {:n 1})
      (q/enqueue! mq :second {:n 2})
      (q/enqueue! mq :third {:n 3})
      (is (= :first (:workflow-name (q/claim! mq "w1"))))
      (is (= :second (:workflow-name (q/claim! mq "w1"))))
      (is (= :third (:workflow-name (q/claim! mq "w1")))))))

(deftest memory-queue-queue-depth-test
  (testing "queue-depth reports accurate count"
    (let [mq (q/memory-queue)]
      (is (= 0 (q/queue-depth mq)))
      (q/enqueue! mq :a {:x 1})
      (q/enqueue! mq :b {:x 2})
      (is (= 2 (q/queue-depth mq)))
      (let [task (q/claim! mq "w1")]
        (is (= 2 (q/queue-depth mq)) "Claimed tasks still counted")
        (q/complete! mq (:task-id task) "w1" {:ok true}))
      (is (= 1 (q/queue-depth mq)) "Completed task removed"))))

;; ===== Dead-letter visibility =====

(deftest memory-queue-dead-lettered-via-fail-test
  (testing "dead-lettered returns tasks that exhausted retries via fail!"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 2})
      (let [task (q/claim! mq "w1")]
        (q/fail! mq (:task-id task) "w1" (ex-info "boom1" {})))
      ;; Wait for backoff, reclaim
      (Thread/sleep 1100)
      (let [retried (q/claim! mq "w2")]
        (q/fail! mq (:task-id retried) "w2" (ex-info "boom2" {})))
      (let [dls (q/dead-lettered mq)]
        (is (= 1 (count dls)) "One dead-lettered entry")
        (is (= :wf (:workflow-name (first dls))))
        (is (= {:x 1} (:data (first dls))))
        (is (some? (:error (first dls))) "Error preserved")
        (is (some? (:failed-at (first dls))) "Timestamp recorded")))))

(deftest memory-queue-dead-lettered-via-lease-expiry-test
  (testing "dead-lettered returns tasks exhausted via lease expiry"
    (let [mq (q/memory-queue {:claim-timeout-ms 1})]
      (q/enqueue! mq :wf {:x 2} {:max-attempts 2})
      (let [task (q/claim! mq "w1")]
        (is (= 0 (:attempt task)))
        (Thread/sleep 10)
        (is (nil? (q/claim! mq "w2")) "Not reclaimable during backoff")
        (Thread/sleep 1100) ;; attempt 1 backoff = 1s
        (let [reclaimed (q/claim! mq "w2")]
          (is (= 1 (:attempt reclaimed)))
          (Thread/sleep 10)
          (is (nil? (q/claim! mq "w3")))
          (let [dls (q/dead-lettered mq)]
            (is (= 1 (count dls)))
            (is (= {:x 2} (:data (first dls))))))))))

(deftest memory-queue-dead-lettered-empty-test
  (testing "dead-lettered returns empty when nothing has failed"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf {:x 1})
      (let [task (q/claim! mq "w1")]
        (q/complete! mq (:task-id task) "w1" {:ok true}))
      (is (= [] (q/dead-lettered mq))))))

(deftest memory-queue-dead-lettered-multiple-entries-test
  (testing "dead-lettered accumulates multiple failed tasks"
    (let [mq (q/memory-queue)]
      (q/enqueue! mq :wf {:x 1} {:max-attempts 1})
      (q/enqueue! mq :wf {:x 2} {:max-attempts 1})
      (let [t1 (q/claim! mq "w1")]
        (q/fail! mq (:task-id t1) "w1" (ex-info "a" {})))
      (let [t2 (q/claim! mq "w1")]
        (q/fail! mq (:task-id t2) "w1" (ex-info "b" {})))
      (let [dls (q/dead-lettered mq)]
        (is (= 2 (count dls)) "Two dead-lettered entries")
        (is (= #{{:x 1} {:x 2}} (set (map :data dls))))))))

(deftest memory-queue-dead-lettered-cap-test
  (testing "dead-letters are capped at :max-dead-letters, oldest dropped first"
    (let [mq (q/memory-queue {:max-dead-letters 3})]
      (doseq [n (range 1 6)]
        (q/enqueue! mq :wf {:x n} {:max-attempts 1})
        (let [t (q/claim! mq "w1")]
          (q/fail! mq (:task-id t) "w1" (ex-info (str "err-" n) {}))))
      (let [dls (q/dead-lettered mq)]
        (is (= 3 (count dls)) "Capped at 3 entries")
        (is (= [3 4 5] (mapv (comp :x :data) dls))
            "Oldest entries (x=1, x=2) dropped — only x=3,4,5 remain")))))

(ns mycelium.queue-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.queue :as q]
            [mycelium.store :as store]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== enqueue-workflow + start-worker =====

(deftest enqueue-and-worker-executes-test
  (testing "Worker picks up enqueued task and executes workflow"
    (defmethod cell/cell-spec :qi/step1 [_]
      {:id :qi/step1
       :handler (fn [_ data] (assoc data :step1 true))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          compiled (myc/pre-compile
                     {:cells {:start :qi/step1}
                      :edges {:start :end}})
          task-id (myc/enqueue-workflow mq :test-wf compiled {})
          worker (myc/start-worker mq {:test-wf compiled} {})]
      (is (uuid? task-id))
      (Thread/sleep 500)
      (future-cancel worker)
      (is (= 0 (q/queue-depth mq)) "Queue empty after processing"))))

(deftest enqueue-workflow-with-delay-test
  (testing "Delayed task not processed before run-at"
    (defmethod cell/cell-spec :qi/delayed [_]
      {:id :qi/delayed
       :handler (fn [_ data] (assoc data :ran true))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          compiled (myc/pre-compile
                     {:cells {:start :qi/delayed}
                      :edges {:start :end}})
          future-time (+ (System/currentTimeMillis) 2000)
          task-id (myc/enqueue-workflow mq :test-wf compiled {}
                    {:run-at future-time})
          worker (myc/start-worker mq {:test-wf compiled} {}
                   {:poll-ms 100})]
      (is (uuid? task-id))
      (Thread/sleep 300)
      (is (= 1 (q/queue-depth mq)) "Task still pending before run-at")
      (future-cancel worker))))

(deftest enqueue-workflow-validates-input-test
  (testing "enqueue-workflow throws on invalid input schema"
    (defmethod cell/cell-spec :qi/validated [_]
      {:id :qi/validated
       :handler (fn [_ data] (assoc data :ok true))
       :schema {:input [:map [:name :string]] :output [:map]}})

    (let [mq (q/memory-queue)
          compiled (myc/pre-compile
                     {:cells {:start :qi/validated}
                      :edges {:start :end}
                      :input-schema [:map [:name :string]]})]
      (is (thrown-with-msg? Exception #"Enqueue input validation failed"
            (myc/enqueue-workflow mq :test-wf compiled {:wrong-key 1})))
      ;; Valid input should succeed
      (let [task-id (myc/enqueue-workflow mq :test-wf compiled {:name "ok"})]
        (is (uuid? task-id))))))

(deftest worker-fails-unknown-workflow-test
  (testing "Worker fails task for unknown workflow name"
    (defmethod cell/cell-spec :qi/dummy [_]
      {:id :qi/dummy
       :handler (fn [_ data] (assoc data :ok true))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          compiled (myc/pre-compile
                     {:cells {:start :qi/dummy}
                      :edges {:start :end}})
          _ (myc/enqueue-workflow mq :unknown-wf compiled {}
              {:max-attempts 1})
          worker (myc/start-worker mq {:test-wf compiled} {}
                   {:poll-ms 50})]
      (Thread/sleep 500)
      (future-cancel worker)
      (is (= 0 (q/queue-depth mq)) "Unknown workflow task removed from queue"))))

(deftest worker-fails-on-error-test
  (testing "Worker dead-letters failing task with max-attempts=1 (no retry)"
    (defmethod cell/cell-spec :qi/boom [_]
      {:id :qi/boom
       :handler (fn [_ _data] (throw (ex-info "boom" {})))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          compiled (myc/pre-compile
                     {:cells {:start :qi/boom}
                      :edges {:start :end}})
          _ (myc/enqueue-workflow mq :test-wf compiled {}
              {:max-attempts 1})
          worker (myc/start-worker mq {:test-wf compiled} {}
                   {:poll-ms 50})]
      (Thread/sleep 500)
      (is (= 0 (q/queue-depth mq)) "Task dead-lettered after one failure with max-attempts=1")
      (future-cancel worker))))

(deftest worker-halt-without-on-halt-dead-letters-test
  (testing "Halted workflow without :on-halt is dead-lettered"
    (defmethod cell/cell-spec :qi/halt-noop [_]
      {:id :qi/halt-noop
       :handler (fn [_ data] (assoc data :done true :mycelium/halt {:reason :review}))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          compiled (myc/pre-compile
                     {:cells {:start :qi/halt-noop}
                      :edges {:start :end}})
          _ (myc/enqueue-workflow mq :test-wf compiled {}
              {:max-attempts 1})
          worker (myc/start-worker mq {:test-wf compiled} {}
                   {:poll-ms 50})]    ;; no :on-halt
      (Thread/sleep 500)
      (future-cancel worker)
      (is (= 0 (q/queue-depth mq)) "Task removed from queue")
      (let [dls (q/dead-lettered mq)]
        (is (= 1 (count dls)) "Halted workflow dead-lettered")
        (is (re-find #"no :on-halt"
              (.getMessage ^Throwable (:error (first dls))))
            "Error message explains missing :on-halt handler")))))

;; ===== Halt/resume with store =====

(deftest worker-halts-and-persists-to-store-test
  (testing "Worker persists halted workflow to store with deterministic session-id"
    (defmethod cell/cell-spec :qi/halt-test [_]
      {:id :qi/halt-test
       :handler (fn [_ data]
                  (assoc data :step1 true :mycelium/halt {:reason :review}))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          s  (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :qi/halt-test}
                      :edges {:start :end}})
          task-id (myc/enqueue-workflow mq :test-wf compiled {})
          expected-sid (str task-id)
          worker (store/start-worker-with-store mq {:test-wf compiled} {} s
                   {:poll-ms 50})]
      (is (uuid? task-id))
      (Thread/sleep 500)
      (future-cancel worker)
      (is (= 0 (q/queue-depth mq)) "Task completed (halted state is in store)")
      ;; Session-id is deterministic: (str task-id)
      (let [halted (store/load-workflow s expected-sid)]
        (is (some? halted) "Session found at (str task-id)")
        (is (true? (:step1 halted)) "Cell output present")
        (is (= {:reason :review} (:mycelium/halt halted)) "Halt context preserved")
        (is (some? (:mycelium/resume halted)) "Resume token present")))))

(deftest worker-resume-via-queue-test
  (testing "Halted workflow can be resumed via enqueue-resume + worker"
    (defmethod cell/cell-spec :qi/halt-step1 [_]
      {:id :qi/halt-step1
       :handler (fn [_ data] (assoc data :step1 true :mycelium/halt {:reason :check}))
       :schema {:input [:map] :output [:map]}})
    (defmethod cell/cell-spec :qi/halt-step2 [_]
      {:id :qi/halt-step2
       :handler (fn [_ data] (assoc data :step2 true))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          s  (store/memory-store)
          compiled (myc/pre-compile
                     {:cells {:start :qi/halt-step1 :next :qi/halt-step2}
                      :edges {:start :next, :next :end}})
          ;; Enqueue and run first step (halts)
          task-id (myc/enqueue-workflow mq :test-wf compiled {})
          worker (store/start-worker-with-store mq {:test-wf compiled} {} s
                   {:poll-ms 50})]
      (is (uuid? task-id))
      (Thread/sleep 500)
      (is (= 0 (q/queue-depth mq)) "Task completed after halt")
      (let [session-id (str task-id)]
        (is (some? (store/load-workflow s session-id)) "State persisted")
        ;; Resume via queue
        (let [resume-task-id (store/enqueue-resume
                               mq :test-wf compiled {} session-id s)]
          (is (uuid? resume-task-id) "Resume returns a task-id")
          ;; Worker picks it up and resumes
          (Thread/sleep 500)
          (is (= 0 (q/queue-depth mq)) "Resume task completed")
          (is (nil? (store/load-workflow s session-id)) "Store cleaned up after completion"))))))

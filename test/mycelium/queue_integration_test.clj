(ns mycelium.queue-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.queue :as q]))

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
          worker (myc/start-worker mq {:test-wf compiled} {}
                   {:poll-ms 50})]
      (is (uuid? task-id))
      ;; Wait for worker to process
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
          ;; Enqueue with a workflow name not in the worker's registry
          _ (myc/enqueue-workflow mq :unknown-wf compiled {}
              {:max-attempts 1})
          worker (myc/start-worker mq {:test-wf compiled} {}
                   {:poll-ms 50})]
      (Thread/sleep 500)
      (future-cancel worker)
      (is (= 0 (q/queue-depth mq)) "Unknown workflow task removed from queue"))))

(deftest worker-fails-on-error-test
  (testing "Worker fails task when workflow throws"
    (defmethod cell/cell-spec :qi/boom [_]
      {:id :qi/boom
       :handler (fn [_ _data] (throw (ex-info "boom" {})))
       :schema {:input [:map] :output [:map]}})

    (let [mq (q/memory-queue)
          compiled (myc/pre-compile
                     {:cells {:start :qi/boom}
                      :edges {:start :end}})
          _ (myc/enqueue-workflow mq :test-wf compiled {}
              {:max-attempts 3})
          worker (myc/start-worker mq {:test-wf compiled} {}
                   {:poll-ms 50})]
      ;; Give worker time to exhaust all retries
      (Thread/sleep 1000)
      (future-cancel worker)
      (is (= 0 (q/queue-depth mq)) "Task dead-lettered after all attempts exhausted"))))

(ns mycelium.error-groups-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(defn- make-cell
  ([id] (make-cell id (fn [_ data] (assoc data id true))))
  ([id handler]
   (defmethod cell/cell-spec id [_]
     {:id id
      :handler handler
      :schema {:input [:map] :output [:map]}})))

;; ===== Round 1: Basic error group routing =====

(deftest error-group-routes-to-handler-test
  (testing "Cell in error group that throws routes to the group's error handler"
    (make-cell :c/explode (fn [_ _data]
                            (throw (ex-info "boom" {:reason :test}))))
    (make-cell :c/error-handler)
    (make-cell :c/next)

    (let [result (myc/run-workflow
                   {:cells {:start :c/explode
                            :next :c/next
                            :err :c/error-handler}
                    :edges {:start :next
                            :next :end
                            :err :end}
                    :error-groups {:pipeline {:cells [:start]
                                              :on-error :err}}}
                   {} {})]
      (is (true? (:c/error-handler result))
          "Error handler cell ran")
      (is (nil? (:c/next result))
          "Normal next cell did not run"))))

;; ===== Round 2: No error — normal routing unaffected =====

(deftest error-group-no-error-test
  (testing "Cell in error group that succeeds routes normally"
    (make-cell :c/ok (fn [_ data] (assoc data :result "fine")))
    (make-cell :c/next2)
    (make-cell :c/err2)

    (let [result (myc/run-workflow
                   {:cells {:start :c/ok
                            :next :c/next2
                            :err :c/err2}
                    :edges {:start :next
                            :next :end
                            :err :end}
                    :error-groups {:pipeline {:cells [:start]
                                              :on-error :err}}}
                   {} {})]
      (is (= "fine" (:result result))
          "Handler result is present")
      (is (true? (:c/next2 result))
          "Normal next cell ran")
      (is (nil? (:mycelium/error result))
          "No error flag"))))

;; ===== Round 3: Validation — error handler must exist =====

(deftest error-group-handler-must-exist-test
  (testing "Error group :on-error must reference a cell in :cells"
    (make-cell :c/val3)

    (is (thrown-with-msg? Exception #"error.*group.*:nonexistent|on-error.*:nonexistent"
          (myc/run-workflow
            {:cells {:start :c/val3}
             :edges {:start :end}
             :error-groups {:grp {:cells [:start]
                                   :on-error :nonexistent}}}
            {} {})))))

;; ===== Round 4: Validation — grouped cells must exist =====

(deftest error-group-cells-must-exist-test
  (testing "Error group cells must exist in :cells"
    (make-cell :c/val4)
    (make-cell :c/err4)

    (is (thrown-with-msg? Exception #"error.*group.*:missing"
          (myc/run-workflow
            {:cells {:start :c/val4, :err :c/err4}
             :edges {:start :end, :err :end}
             :error-groups {:grp {:cells [:missing]
                                   :on-error :err}}}
            {} {})))))

;; ===== Round 5: Validation — groups must not overlap =====

(deftest error-groups-must-not-overlap-test
  (testing "Cell in two error groups throws"
    (make-cell :c/val5)
    (make-cell :c/err5a)
    (make-cell :c/err5b)

    (is (thrown-with-msg? Exception #"multiple.*error.*group|overlap"
          (myc/run-workflow
            {:cells {:start :c/val5, :erra :c/err5a, :errb :c/err5b}
             :edges {:start :end, :erra :end, :errb :end}
             :error-groups {:a {:cells [:start] :on-error :erra}
                            :b {:cells [:start] :on-error :errb}}}
            {} {})))))

;; ===== Round 6: Error group with map edges =====

(deftest error-group-with-map-edges-test
  (testing "Cell with map edges that throws routes to error handler"
    (make-cell :c/explode6 (fn [_ _data]
                              (throw (ex-info "map-edge-fail" {}))))
    (make-cell :c/ok-target)
    (make-cell :c/err6)

    (let [result (myc/run-workflow
                   {:cells {:start :c/explode6
                            :ok :c/ok-target
                            :err :c/err6}
                    :edges {:start {:done :ok, :on-error :err}
                            :ok :end
                            :err :end}
                    :dispatches {:start [[:done (fn [d] (not (:mycelium/error d)))]]}
                    :error-groups {:grp {:cells [:start]
                                         :on-error :err}}}
                   {} {})]
      (is (true? (:c/err6 result))
          "Error handler ran")
      (is (nil? (:c/ok-target result))
          "Normal target did not run"))))

;; ===== Round 7: Error data includes cell name and message =====

(deftest error-data-contents-test
  (testing ":mycelium/error contains cell name and message"
    (make-cell :c/explode7 (fn [_ _data]
                              (throw (ex-info "detailed-error" {:key :val}))))
    (make-cell :c/err7 (fn [_ data]
                          ;; Preserve the error info for assertion
                          (assoc data :captured-error (:mycelium/error data))))

    (let [result (myc/run-workflow
                   {:cells {:start :c/explode7, :err :c/err7}
                    :edges {:start :end, :err :end}
                    :error-groups {:grp {:cells [:start]
                                         :on-error :err}}}
                   {} {})
          err (:captured-error result)]
      (is (some? err) "Error data captured")
      (is (= :start (:cell err)) "Error has cell name")
      (is (= "detailed-error" (:message err)) "Error has message"))))

;; ===== Round 8: :mycelium/error is cleaned up after error handler =====

(deftest error-handler-can-clear-error-test
  (testing "Error handler can dissoc :mycelium/error to clean up"
    (make-cell :c/explode8 (fn [_ _data]
                              (throw (ex-info "cleanup-test" {}))))
    (make-cell :c/err8 (fn [_ data]
                          (-> data
                              (dissoc :mycelium/error)
                              (assoc :recovered true))))

    (let [result (myc/run-workflow
                   {:cells {:start :c/explode8, :err :c/err8}
                    :edges {:start :end, :err :end}
                    :error-groups {:grp {:cells [:start]
                                         :on-error :err}}}
                   {} {})]
      (is (nil? (:mycelium/error result))
          "Error handler cleaned up the error")
      (is (true? (:recovered result))
          "Error handler ran"))))

;; ===== Round 9: Async cell in error group =====

(deftest error-group-async-cell-test
  (testing "Async cell in error group that errors routes to handler"
    (defmethod cell/cell-spec :c/async-explode [_]
      {:id :c/async-explode
       :handler (fn [_resources _data _callback error-cb]
                  (future
                    (error-cb (ex-info "async-boom" {}))))
       :async? true
       :schema {:input [:map] :output [:map]}})
    (make-cell :c/async-err)

    (let [result (myc/run-workflow
                   {:cells {:start :c/async-explode, :err :c/async-err}
                    :edges {:start :end, :err :end}
                    :error-groups {:grp {:cells [:start]
                                         :on-error :err}}}
                   {} {})]
      (is (true? (:c/async-err result))
          "Error handler ran")
      (is (= :start (get-in result [:mycelium/error :cell]))
          "Error has cell name"))))

;; ===== Round 10: Error group + timeout interaction =====

(deftest error-group-with-timeout-test
  (testing "Timeout takes priority over error group catch"
    (make-cell :c/slow-explode (fn [_ data]
                                  (Thread/sleep 200)
                                  (throw (ex-info "should-not-reach" {}))))
    (make-cell :c/timeout-handler)
    (make-cell :c/error-handler10)

    (let [result (myc/run-workflow
                   {:cells {:start :c/slow-explode
                            :timeout-cell :c/timeout-handler
                            :err :c/error-handler10}
                    :edges {:start {:done :end, :timeout :timeout-cell}
                            :timeout-cell :end
                            :err :end}
                    :dispatches {:start [[:done (fn [d] (not (or (:mycelium/timeout d)
                                                                  (:mycelium/error d))))]]}
                    :timeouts {:start 50}
                    :error-groups {:grp {:cells [:start]
                                         :on-error :err}}}
                   {} {})]
      (is (true? (:c/timeout-handler result))
          "Timeout handler ran (timeout fires before error)")
      (is (nil? (:c/error-handler10 result))
          "Error handler did not run"))))

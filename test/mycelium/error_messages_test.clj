(ns mycelium.error-messages-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [mycelium.core :as myc]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== validate-input: enriched error details =====

(deftest validate-input-shows-actual-values-test
  (testing "validate-input error includes actual value and type for each failing key"
    (defmethod cell/cell-spec :test/typed [_]
      {:id      :test/typed
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:amount :int] [:name :string]]
                 :output [:map]}})
    (let [cell  (cell/get-cell! :test/typed)
          error (schema/validate-input cell {:amount 949.5 :name 42})]
      (is (some? error))
      ;; New :failed-keys field shows per-key diagnostics
      (let [fk (:failed-keys error)]
        (is (map? fk))
        ;; :amount — got a double, expected int
        (is (= 949.5 (get-in fk [:amount :value])))
        (is (= "java.lang.Double" (get-in fk [:amount :type])))
        ;; :name — got an int, expected string
        (is (= 42 (get-in fk [:name :value])))
        (is (= "java.lang.Long" (get-in fk [:name :type])))))))

(deftest validate-input-shows-missing-keys-test
  (testing "validate-input error shows missing keys with nil value"
    (defmethod cell/cell-spec :test/typed [_]
      {:id      :test/typed
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:amount :int] [:name :string]]
                 :output [:map]}})
    (let [cell  (cell/get-cell! :test/typed)
          error (schema/validate-input cell {:amount 42})]
      (is (some? error))
      (let [fk (:failed-keys error)]
        ;; :name is missing
        (is (contains? fk :name))
        (is (nil? (get-in fk [:name :value])))))))

(deftest validate-output-shows-actual-values-test
  (testing "validate-output error includes actual value and type for each failing key"
    (defmethod cell/cell-spec :test/typed [_]
      {:id      :test/typed
       :handler (fn [_ data] data)
       :schema  {:input  [:map]
                 :output [:map [:result :int]]}})
    (let [cell  (cell/get-cell! :test/typed)
          error (schema/validate-output cell {:result "not-int"})]
      (is (some? error))
      (let [fk (:failed-keys error)]
        (is (= "not-int" (get-in fk [:result :value])))
        (is (= "java.lang.String" (get-in fk [:result :type])))))))

;; ===== Error data excludes mycelium internal keys =====

(deftest error-data-excludes-internal-keys-test
  (testing "Error :data field excludes :mycelium/* keys to reduce noise"
    (defmethod cell/cell-spec :test/typed [_]
      {:id      :test/typed
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map]}})
    (let [cell (cell/get-cell! :test/typed)
          data {:x "bad"
                :mycelium/trace [{:cell :prev :data {}}]
                :mycelium/params {:factor 3}
                :user-key "kept"}
          error (schema/validate-input cell data)]
      (is (some? error))
      ;; :data should not contain :mycelium/* keys
      (is (not (contains? (:data error) :mycelium/trace)))
      (is (not (contains? (:data error) :mycelium/params)))
      ;; but user keys are preserved
      (is (= "kept" (get-in error [:data :user-key]))))))

;; ===== Post-interceptor includes cell-path =====

(deftest post-interceptor-error-includes-cell-path-test
  (testing "Post-interceptor schema error includes :cell-path from execution trace"
    (defmethod cell/cell-spec :test/step-a [_]
      {:id      :test/step-a
       :handler (fn [_ data] (assoc data :a-done true))
       :schema  {:input [:map [:x :int]] :output [:map [:a-done :boolean]]}})
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] data)
       :schema  {:input [:map [:a-done :boolean]] :output [:map [:result :int]]}})
    (let [state->cell {:test/step-b (cell/get-cell! :test/step-b)}
          state->names {:test/step-b :step-b}
          post (schema/make-post-interceptor state->cell nil state->names)
          ;; Simulate: step-a already ran (its trace entry is in data)
          fsm-state {:last-state-id    :test/step-b
                     :current-state-id :some/next
                     :data {:a-done true
                            :mycelium/trace [{:cell :start :cell-id :test/step-a :transition :done}]}
                     :trace []}
          result (post fsm-state {})]
      ;; Should redirect to error
      (is (= ::fsm/error (:current-state-id result)))
      ;; Schema error should include cell-path
      (let [schema-error (get-in result [:data :mycelium/schema-error])]
        (is (= [:start] (:cell-path schema-error)))))))

(deftest pre-interceptor-error-includes-cell-path-test
  (testing "Pre-interceptor schema error includes :cell-path from execution trace"
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] data)
       :schema  {:input [:map [:x :int]] :output [:map]}})
    (let [state->cell {:test/step-b (cell/get-cell! :test/step-b)}
          pre (schema/make-pre-interceptor state->cell)
          fsm-state {:current-state-id :test/step-b
                     :data {:x "bad"
                            :mycelium/trace [{:cell :start :cell-id :test/step-a :transition :done}
                                             {:cell :validate :cell-id :test/validate :transition :ok}]}
                     :trace []}
          result (pre fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (let [schema-error (get-in result [:data :mycelium/schema-error])]
        (is (= [:start :validate] (:cell-path schema-error)))))))

;; ===== build-error-map includes :message with cell-id and failing keys =====

(deftest build-error-map-includes-message-test
  (testing "build-error-map :message includes cell-id, phase, and specific failing key names"
    (defmethod cell/cell-spec :test/typed [_]
      {:id      :test/typed
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:amount :int] [:name :string]]
                 :output [:map]}})
    (let [cell  (cell/get-cell! :test/typed)
          error (schema/validate-input cell {:amount 949.5 :name 42})]
      (is (some? error))
      ;; :message should be a string containing cell-id and the failing key names
      (is (string? (:message error)))
      (is (re-find #":test/typed" (:message error)))
      (is (re-find #":amount" (:message error)))
      (is (re-find #":name" (:message error)))
      (is (re-find #"input" (:message error))))))

(deftest build-error-map-output-message-test
  (testing "build-error-map :message for output phase includes cell-id and failing keys"
    (defmethod cell/cell-spec :test/typed [_]
      {:id      :test/typed
       :handler (fn [_ data] data)
       :schema  {:input  [:map]
                 :output [:map [:result :int]]}})
    (let [cell  (cell/get-cell! :test/typed)
          error (schema/validate-output cell {:result "not-int"})]
      (is (some? error))
      (is (string? (:message error)))
      (is (re-find #":test/typed" (:message error)))
      (is (re-find #":result" (:message error)))
      (is (re-find #"output" (:message error))))))

;; ===== Interceptor errors include :cell-name =====

(deftest post-interceptor-error-includes-cell-name-test
  (testing "Post-interceptor schema error includes :cell-name from state->names"
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] data)
       :schema  {:input [:map [:a-done :boolean]] :output [:map [:result :int]]}})
    (let [state->cell {:test/step-b (cell/get-cell! :test/step-b)}
          state->names {:test/step-b :step-b}
          post (schema/make-post-interceptor state->cell nil state->names)
          fsm-state {:last-state-id    :test/step-b
                     :current-state-id :some/next
                     :data {:a-done true}
                     :trace []}
          result (post fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (let [schema-error (get-in result [:data :mycelium/schema-error])]
        (is (= :step-b (:cell-name schema-error)))))))

(deftest pre-interceptor-error-includes-cell-name-test
  (testing "Pre-interceptor schema error includes :cell-name from state->names"
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] data)
       :schema  {:input [:map [:x :int]] :output [:map]}})
    (let [state->cell {:test/step-b (cell/get-cell! :test/step-b)}
          state->names {:test/step-b :step-b}
          pre (schema/make-pre-interceptor state->cell {:state->names state->names})
          fsm-state {:current-state-id :test/step-b
                     :data {:x "bad"}
                     :trace []}
          result (pre fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (let [schema-error (get-in result [:data :mycelium/schema-error])]
        (is (= :step-b (:cell-name schema-error)))))))

;; ===== End-to-end: workflow error messages =====

(deftest workflow-error-has-enriched-diagnostics-test
  (testing "Workflow schema error has failed-keys, cell-path, and clean data"
    (defmethod cell/cell-spec :test/step-a [_]
      {:id      :test/step-a
       :handler (fn [_ data] (assoc data :count 42.5))
       :schema  {:input [:map [:x :int]] :output [:map [:count :double]]}})
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] (assoc data :result (:count data)))
       :schema  {:input [:map [:count :int]] :output [:map [:result :int]]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells      {:start  :test/step-a
                                 :step-b :test/step-b}
                    :edges      {:start  {:done :step-b}
                                 :step-b {:done :end}}
                    :dispatches {:start  [[:done (constantly true)]]
                                 :step-b [[:done (constantly true)]]}}
                   {} {:x 42} {:on-error on-error})
          schema-error (:mycelium/schema-error result)]
      ;; Should have cell-path showing :start ran before the failure
      (is (= [:start] (:cell-path schema-error)))
      ;; Should have failed-keys with actual value
      (is (= 42.5 (get-in schema-error [:failed-keys :count :value])))
      ;; :data should not contain :mycelium/trace
      (is (not (contains? (:data schema-error) :mycelium/trace))))))

(deftest workflow-error-message-includes-cell-name-and-keys-test
  (testing "workflow-error :message includes cell-name and specific failing keys"
    (defmethod cell/cell-spec :test/step-a [_]
      {:id      :test/step-a
       :handler (fn [_ data] (assoc data :count 42.5))
       :schema  {:input [:map [:x :int]] :output [:map [:count :double]]}})
    (defmethod cell/cell-spec :test/step-b [_]
      {:id      :test/step-b
       :handler (fn [_ data] (assoc data :result (:count data)))
       :schema  {:input [:map [:count :int]] :output [:map [:result :int]]}})
    (let [on-error (fn [_resources fsm-state] (:data fsm-state))
          result (myc/run-workflow
                   {:cells      {:start  :test/step-a
                                 :step-b :test/step-b}
                    :edges      {:start  {:done :step-b}
                                 :step-b {:done :end}}
                    :dispatches {:start  [[:done (constantly true)]]
                                 :step-b [[:done (constantly true)]]}}
                   {} {:x 42} {:on-error on-error})
          we (myc/workflow-error result)]
      ;; workflow-error message should mention the cell name and the failing key
      (is (string? (:message we)))
      (is (re-find #"step-b" (:message we)))
      (is (re-find #":count" (:message we)))
      ;; Should also surface :cell-name
      (is (= :step-b (:cell-name we))))))

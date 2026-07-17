(ns mycelium.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [malli.registry :as mr]
            [mycelium.cell :as cell]
            [mycelium.compose :as compose]
            [mycelium.core :as myc]
            [mycelium.dev :as dev]
            [mycelium.manifest :as manifest]
            [mycelium.orchestrate :as orchestrate]
            [mycelium.workflow :as wf]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(def custom-schemas
  {:test/id             :uuid
   :test/request        [:map [:id :test/id]]
   :test/response       [:map [:id :test/id] [:status [:= :loaded]]]
   :test/audit-input    [:map [:id :test/id] [:status [:= :loaded]] [:actor :string]]
   :test/optional-input [:map [:optional-value {:optional true} :string]]
   :test/done-output    [:map [:done :boolean]]})

(def registry-opts {:malli/registry (mr/composite-registry (m/default-schemas) custom-schemas)})

(defn- capture-error [f]
  (try
    (f)
    nil
    (catch Exception e
      e)))

(deftest cell-schema-setter-uses-local-registry-test
  (defmethod cell/cell-spec :test/set-schema [_] {:id :test/set-schema :handler (fn [_ data] data)})
  (let [schema-map {:input :test/request :output :test/response}]
    (is (= schema-map (cell/set-cell-schema! :test/set-schema schema-map registry-opts)))))

(defn- register-load-cell! []
  (defmethod cell/cell-spec :test/load [_]
    {:id      :test/load
     :doc     "Loads a test record."
     :handler (fn [_ data] (assoc data :status (if (:bad-output data) "loaded" :loaded)))
     :schema  {:input [:ref :test/request] :output [:ref :test/response]}}))

(defn- manifest-cell [id doc input output]
  {:id id :doc doc :schema {:input input :output output} :on-error nil})

(def custom-manifest
  {:id           :test/manifest
   :input-schema :test/request
   :cells        {:start (manifest-cell :test/load "Loads." :test/request :test/response)}
   :edges        {:start :end}})

(def custom-fragment
  {:id    :test/fragment
   :entry :part
   :exits [:done]
   :cells {:part (manifest-cell :test/load "Loads." :test/request :test/response)}
   :edges {:part :_exit/done}})

(def custom-fragment-manifest
  {:id        :test/fragment-host
   :cells     {:finish (manifest-cell :test/finish "Finishes." :test/response :test/response)}
   :edges     {:finish :end}
   :fragments {:loader {:fragment custom-fragment :as :start :exits {:done :finish}}}})

(deftest pre-compile-propagates-local-registry-test
  (testing "one registry compiles workflow input, cell, and transform schemas"
    (register-load-cell!)
    (let [workflow  {:input-schema :test/request
                     :cells        {:start :test/load}
                     :edges        {:start :end}
                     :transforms   {:start
                                    {:input
                                     {:fn     identity
                                      :schema {:input  :test/request
                                               :output :test/request}}
                                     :output
                                     {:fn     identity
                                      :schema {:input  :test/response
                                               :output :test/response}}}}}
          compiled  (myc/pre-compile
                     workflow
                     (assoc registry-opts
                            :on-error (fn [_ fsm-state]
                                        (:data fsm-state))))
          id        (random-uuid)
          valid     (myc/run-compiled compiled {} {:id id})
          bad-input (myc/run-compiled compiled {} {:id "invalid"})
          bad-output (myc/run-compiled compiled
                                       {}
                                       {:id id :bad-output true})]
      (is (= {:id id :status :loaded}
             (select-keys valid [:id :status])))
      (is (some? (:mycelium/input-error bad-input)))
      (is (= :output
             (get-in bad-output [:mycelium/schema-error :phase])))
      (is (thrown? Exception (m/schema :test/request))))))

(deftest schema-chain-resolves-local-registry-test
  (testing "schema-chain errors include keys hidden behind registry references"
    (register-load-cell!)
    (defmethod cell/cell-spec :test/audit [_]
      {:id      :test/audit
       :handler (fn [_ data] data)
       :schema  {:input  [:ref :test/audit-input]
                 :output :map}})
    (let [error (capture-error
                 #(myc/pre-compile
                   {:cells {:start :test/load
                            :audit :test/audit}
                    :edges {:start :audit
                            :audit :end}}
                   registry-opts))]
      (is (= #{:actor}
             (-> error ex-data :errors first :missing-keys))))))

(deftest inline-local-registry-schema-runs-test
  (testing "schema key extraction dereferences Malli's inline registry form"
    (defmethod cell/cell-spec :test/inline-producer [_]
      {:id      :test/inline-producer
       :handler (fn [_ data] (assoc data :status :loaded))
       :schema  {:input  :test/request
                 :output [:schema
                          {:registry custom-schemas}
                          :test/response]}})
    (defmethod cell/cell-spec :test/inline-consumer [_]
      {:id      :test/inline-consumer
       :handler (fn [_ data] data)
       :schema  {:input  :test/response
                 :output :test/response}})
    (let [id     (random-uuid)
          result (myc/run-workflow
                  {:cells {:start :test/inline-producer
                           :next  :test/inline-consumer}
                   :edges {:start :next
                           :next  :end}}
                  {}
                  {:id id}
                  registry-opts)]
      (is (= {:id id :status :loaded}
             (select-keys result [:id :status]))))))

(deftest invoke-cell-uses-local-registry-test
  (register-load-cell!)
  (defmethod cell/cell-spec :test/unchecked [_]
    {:id      :test/unchecked
     :handler (fn [_ data] (assoc data :invoked true))
     :schema  {:input :test/unresolvable :output :test/unresolvable}})
  (let [id           (random-uuid)
        result       (myc/invoke-cell :test/load {} {:id id} registry-opts)
        input-error  (capture-error #(myc/invoke-cell :test/load {}
                                                      {:id "invalid"} registry-opts))
        output-error (capture-error #(myc/invoke-cell :test/load {}
                                                      {:id id :bad-output true} registry-opts))]
    (is (= {:id id :status :loaded} (select-keys result [:id :status])))
    (is (= :mycelium.invoke-cell/input-error (:type (ex-data input-error))))
    (is (= :mycelium.invoke-cell/output-error (:type (ex-data output-error))))
    (is (= {:invoked true} (myc/invoke-cell :test/unchecked {} {} {:validate :off})))))

(deftest workflow-composition-uses-local-registry-test
  (register-load-cell!)
  (let [child          {:cells {:start :test/load} :edges {:start :end}}
        spec           (compose/workflow->cell :test/composed child
                                               {:input :test/request :output :map}
                                               registry-opts)
        success-schema (get-in spec [:schema :output 1 :success])
        id             (random-uuid)]
    (is (true? (m/validate success-schema {:id id :status :loaded})))
    ;; Maps are open — this fails only if :status was inferred as required.
    (is (false? (m/validate success-schema {:id id})))
    (compose/register-workflow-cell!
     :test/composed child {:input :test/request :output :map} registry-opts)
    (let [result (myc/run-workflow
                  {:cells {:start :test/composed}
                   :edges {:start {:success :end :failure :error}}}
                  {} {:id id} registry-opts)]
      (is (= {:id id :status :loaded} (select-keys result [:id :status]))))))

(deftest manifest-and-fragment-paths-use-local-registry-test
  (register-load-cell!)
  (is (= custom-manifest (manifest/validate-manifest custom-manifest registry-opts)))
  (let [file (java.io.File/createTempFile "mycelium-registry-" ".edn")]
    (try
      (spit file (pr-str custom-fragment-manifest))
      (is (= #{:start :finish}
             (-> (manifest/load-manifest (.getPath file) registry-opts)
                 :cells keys set)))
      (finally
        (.delete file))))
  (let [workflow (manifest/manifest->workflow custom-manifest registry-opts)
        id       (random-uuid)
        result   (myc/run-workflow workflow {} {:id id} registry-opts)]
    (is (= {:id id :status :loaded} (select-keys result [:id :status])))))

(deftest generators-and-orchestration-use-local-registry-test
  (register-load-cell!)
  (let [brief    (manifest/cell-brief custom-manifest :start registry-opts)
        status   (dev/workflow-status custom-manifest registry-opts)
        progress (orchestrate/progress custom-manifest registry-opts)
        inferred (dev/infer-workflow-schema
                  {:cells {:start :test/load} :edges {:start :end}} registry-opts)]
    (is (uuid? (get-in brief [:examples :input :id])))
    (is (= [1 1] ((juxt :passing :total) status)))
    (is (re-find #"\[PASS\]" progress))
    (is (= #{:id :status} (get-in inferred [:start :available-after])))))

(deftest join-input-preserves-optional-registry-entry-test
  (testing "join synthesis does not make optional registry keys required"
    (defmethod cell/cell-spec :test/join-start [_]
      {:id      :test/join-start
       :handler (fn [_ data] data)
       :schema  {:input :map :output :map}})
    (defmethod cell/cell-spec :test/optional-member [_]
      {:id      :test/optional-member
       :handler (fn [_ data] (assoc data :done true))
       :schema  {:input  :test/optional-input
                 :output :test/done-output}})
    (let [result (myc/run-workflow
                  {:cells {:start  :test/join-start
                           :member :test/optional-member}
                   :joins {:joined {:cells [:member]}}
                   :edges {:start  :joined
                           :joined :end}}
                  {}
                  {}
                  registry-opts)]
      (is (true? (:done result)))
      (is (nil? (:mycelium/schema-error result))))))

(deftest per-transition-schemas-use-local-registry-test
  (testing "per-transition cell outputs compile against the local registry"
    (defmethod cell/cell-spec :test/per-transition [_]
      {:id      :test/per-transition
       :handler (fn [_ data] (assoc data :status :loaded))
       :schema  {:input  :test/request
                 :output [:per-transition {:done :test/response}]}})
    (let [id     (random-uuid)
          result (myc/run-workflow
                  {:cells      {:start :test/per-transition}
                   :edges      {:start {:done :end}}
                   :dispatches {:start [[:done (constantly true)]]}}
                  {}
                  {:id id}
                  registry-opts)]
      (is (= {:id id :status :loaded}
             (select-keys result [:id :status]))))))

(deftest invalid-cell-schema-error-is-labelled-test
  (testing "boundary compilation names the cell with an unresolved reference"
    (defmethod cell/cell-spec :test/invalid-schema [_]
      {:id      :test/invalid-schema
       :handler (fn [_ data] data)
       :schema  {:input  :test/missing
                 :output :map}})
    (let [error (capture-error
                 #(wf/validate-workflow
                   {:cells {:start :test/invalid-schema}
                    :edges {:start :end}}
                   registry-opts))]
      (is (re-find #"Invalid schema for cell :test/invalid-schema"
                   (ex-message error)))
      (is (= {:cell-id :test/invalid-schema}
             (select-keys (ex-data error) [:cell-id]))))))

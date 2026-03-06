(ns mycelium.manifest-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.manifest :as manifest]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(def valid-manifest
  {:id :test/workflow
   :doc "A test workflow"
   :cells {:start
           {:id       :test/parse
            :doc      "Parse input"
            :schema   {:input  [:map [:x :int]]
                       :output [:map [:y :int]]}
            :on-error nil
            :requires []}
           :process
           {:id       :test/process
            :doc      "Process data"
            :schema   {:input  [:map [:y :int]]
                       :output [:map [:z :string]]}
            :on-error nil
            :requires [:db]}}
   :edges {:start   {:success :process, :failure :error}
           :process {:done :end}}
   :dispatches {:start   [[:success (fn [data] (:y data))]
                           [:failure (fn [data] (not (:y data)))]]
                :process [[:done (constantly true)]]}})

;; ===== 1. Valid manifest passes validation =====

(deftest valid-manifest-passes-test
  (testing "Valid manifest passes validation"
    (let [result (manifest/validate-manifest valid-manifest)]
      (is (= :test/workflow (:id result))))))

;; ===== 2. Invalid Malli schema in manifest fails =====

(deftest invalid-schema-in-manifest-fails-test
  (testing "Invalid Malli schema in manifest fails"
    (let [bad-manifest (assoc-in valid-manifest [:cells :start :schema :input]
                                 [:not-real-type])]
      (is (thrown? Exception
            (manifest/validate-manifest bad-manifest))))))

;; ===== 3. cell-brief generates example data from schema =====

(deftest cell-brief-generates-examples-test
  (testing "cell-brief generates example data from schema"
    (let [brief (manifest/cell-brief valid-manifest :start)]
      (is (some? (:examples brief)))
      (is (map? (get-in brief [:examples :input])))
      (is (int? (get-in brief [:examples :input :x]))))))

;; ===== 4. cell-brief prompt does NOT mention :mycelium/transition =====

(deftest cell-brief-prompt-no-transition-test
  (testing "cell-brief prompt does not mention :mycelium/transition"
    (let [brief (manifest/cell-brief valid-manifest :process)]
      (is (string? (:prompt brief)))
      (is (re-find #"process" (:prompt brief)))
      (is (re-find #":db" (:prompt brief)))
      (is (re-find #":z" (:prompt brief)))
      ;; Should NOT reference :mycelium/transition
      (is (not (re-find #"mycelium/transition" (:prompt brief)))))))

;; ===== 5. manifest->workflow produces compilable workflow def =====

(deftest manifest-to-workflow-produces-compilable-def-test
  (testing "manifest->workflow produces compilable workflow def with :dispatches"
    (let [workflow-def (manifest/manifest->workflow valid-manifest)]
      (is (map? workflow-def))
      (is (contains? workflow-def :cells))
      (is (contains? workflow-def :edges))
      (is (contains? workflow-def :dispatches))
      ;; All cell IDs should be in the registry after manifest->workflow
      (is (some? (cell/get-cell :test/parse)))
      (is (some? (cell/get-cell :test/process))))))

;; ===== 6. manifest->workflow applies schema to pre-registered cells =====

(deftest manifest-applies-schema-to-existing-cells-test
  (testing "manifest->workflow applies manifest metadata to pre-registered cells"
    ;; Register a cell WITHOUT schema — manifest is the source of truth
    (defmethod cell/cell-spec :test/parse [_]
      {:id      :test/parse
       :handler (fn [_ data] (assoc data :y 1))})
    (is (nil? (:schema (cell/get-cell :test/parse))))

    ;; Now load the manifest — should apply schema from manifest
    (manifest/manifest->workflow valid-manifest)

    (let [cell (cell/get-cell :test/parse)]
      (is (some? (:schema cell)))
      (is (= [:map [:x :int]] (get-in cell [:schema :input])))
      (is (= [:map [:y :int]] (get-in cell [:schema :output]))))))

;; ===== Per-transition output schema in manifest =====

(def per-transition-manifest
  {:id :test/pt-workflow
   :doc "A workflow with per-transition output schemas"
   :cells {:start
           {:id       :test/lookup
            :doc      "Look up a thing"
            :schema   {:input  [:map [:id :string]]
                       :output {:found     [:map [:profile [:map [:name :string]]]]
                                :not-found [:map [:error-message :string]]}}
            :on-error nil
            :requires [:db]}
           :render
           {:id       :test/render
            :doc      "Render output"
            :schema   {:input  [:map [:profile [:map [:name :string]]]]
                       :output [:map [:html :string]]}
            :on-error nil
            :requires []}}
   :edges {:start  {:found     :render
                    :not-found :error}
           :render {:done :end}}
   :dispatches {:start  [[:found     (fn [d] (:profile d))]
                          [:not-found (fn [d] (:error-message d))]]
                :render [[:done (constantly true)]]}})

(deftest per-transition-manifest-validates-test
  (testing "Manifest with per-transition output passes validation"
    (let [result (manifest/validate-manifest per-transition-manifest)]
      (is (= :test/pt-workflow (:id result))))))

(deftest cell-brief-per-transition-prompt-test
  (testing "cell-brief shows per-transition schemas in prompt"
    (let [brief (manifest/cell-brief per-transition-manifest :start)]
      (is (string? (:prompt brief)))
      ;; Should mention per-transition schema details
      (is (re-find #"profile" (:prompt brief)))
      (is (re-find #"error-message" (:prompt brief)))
      ;; Should NOT mention :mycelium/transition
      (is (not (re-find #"mycelium/transition" (:prompt brief)))))))

(deftest manifest-to-workflow-per-transition-test
  (testing "manifest->workflow applies per-transition schema to cells"
    (let [workflow-def (manifest/manifest->workflow per-transition-manifest)]
      (is (map? workflow-def))
      (let [cell (cell/get-cell :test/lookup)]
        (is (map? (get-in cell [:schema :output])))
        (is (= #{:found :not-found} (set (keys (get-in cell [:schema :output])))))))))

;; ===== Dispatch coverage validation =====

(deftest missing-dispatch-for-edge-test
  (testing "Missing dispatch predicate for a map edge label is rejected"
    (let [bad (assoc valid-manifest :dispatches
                     {:start [[:success (fn [d] (:y d))]]  ;; missing :failure
                      :process [[:done (constantly true)]]})]
      (is (thrown-with-msg? Exception #"[Dd]ispatch"
            (manifest/validate-manifest bad))))))

(deftest extra-dispatch-for-edge-test
  (testing "Extra dispatch key not in edges is rejected"
    (let [bad (assoc valid-manifest :dispatches
                     {:start [[:success (fn [d] (:y d))]
                              [:failure (fn [d] (not (:y d)))]
                              [:extra   (fn [d] d)]]
                      :process [[:done (constantly true)]]})]
      (is (thrown-with-msg? Exception #"[Dd]ispatch"
            (manifest/validate-manifest bad))))))

(deftest unconditional-edge-no-dispatch-test
  (testing "Unconditional edge (keyword target) needs no dispatch entry"
    (let [m {:id :test/simple
             :cells {:start {:id :test/simple-cell
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}
             :edges {:start :end}}]
      (is (some? (manifest/validate-manifest m))))))

;; ===== Bug fix: manifest join dispatch validation =====

(def join-manifest
  {:id :test/join-workflow
   :doc "A workflow with a join node"
   :cells {:start
           {:id       :test/jm-start
            :doc      "Start"
            :schema   {:input  [:map [:x :int]]
                       :output {:success [:map [:user-id :string]]
                                :failure [:map [:error :string]]}}
            :on-error nil
            :requires []}
           :fetch-a
           {:id       :test/jm-fetch-a
            :doc      "Fetch A"
            :schema   {:input  [:map [:user-id :string]]
                       :output [:map [:profile [:map [:name :string]]]]}
            :on-error nil
            :requires []}
           :fetch-b
           {:id       :test/jm-fetch-b
            :doc      "Fetch B"
            :schema   {:input  [:map [:user-id :string]]
                       :output [:map [:orders [:vector :map]]]}
            :on-error nil
            :requires []}
           :render
           {:id       :test/jm-render
            :doc      "Render"
            :schema   {:input  [:map
                                [:profile [:map [:name :string]]]
                                [:orders [:vector :map]]]
                       :output [:map [:html :string]]}
            :on-error nil
            :requires []}}
   :joins {:fetch-data {:cells    [:fetch-a :fetch-b]
                        :strategy :parallel}}
   :edges {:start      {:success :fetch-data
                        :failure :error}
           :fetch-data {:done :render
                        :failure :error}
           :render     {:done :end}}
   ;; Note: NO dispatch entry for :fetch-data — should use join defaults
   :dispatches {:start  [[:success (fn [d] (:user-id d))]
                         [:failure (fn [d] (:error d))]]
                :render [[:done (constantly true)]]}})

(deftest manifest-join-validates-without-explicit-dispatch-test
  (testing "Manifest with join node validates even without explicit dispatch for join"
    (let [result (manifest/validate-manifest join-manifest)]
      (is (= :test/join-workflow (:id result))))))

(deftest manifest-join-to-workflow-produces-compilable-def-test
  (testing "manifest->workflow with joins produces a workflow def with :joins key"
    (let [wf-def (manifest/manifest->workflow join-manifest)]
      (is (contains? wf-def :joins))
      (is (contains? (:joins wf-def) :fetch-data)))))

;; ===== :schema :inherit =====

(deftest schema-inherit-resolves-from-registry-test
  (testing ":schema :inherit resolves schema from cell registry"
    (defmethod cell/cell-spec :test/inherit-cell [_]
      {:id      :test/inherit-cell
       :handler (fn [_ data] data)
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:y :int]]}})
    (let [m {:id :test/inherit
             :cells {:start {:id       :test/inherit-cell
                              :schema   :inherit
                              :on-error nil}}
             :edges {:start :end}}
          result (manifest/validate-manifest m)]
      ;; Schema should be resolved from registry
      (is (= [:map [:x :int]] (get-in result [:cells :start :schema :input])))
      (is (= [:map [:y :int]] (get-in result [:cells :start :schema :output]))))))

(deftest schema-inherit-unregistered-cell-throws-test
  (testing ":schema :inherit for unregistered cell throws"
    (let [m {:id :test/inherit-bad
             :cells {:start {:id       :test/nonexistent-cell
                              :schema   :inherit
                              :on-error nil}}
             :edges {:start :end}}]
      (is (thrown-with-msg? Exception #"not registered"
            (manifest/validate-manifest m))))))

(deftest schema-inherit-no-schema-in-registry-throws-test
  (testing ":schema :inherit throws when registered cell has no schema"
    (defmethod cell/cell-spec :test/no-schema-cell [_]
      {:id      :test/no-schema-cell
       :handler (fn [_ data] data)})
    (let [m {:id :test/inherit-no-schema
             :cells {:start {:id       :test/no-schema-cell
                              :schema   :inherit
                              :on-error nil}}
             :edges {:start :end}}]
      (is (thrown-with-msg? Exception #"no schema"
            (manifest/validate-manifest m))))))

(deftest schema-inherit-in-manifest-to-workflow-test
  (testing ":schema :inherit works end-to-end with manifest->workflow"
    (defmethod cell/cell-spec :test/inherit-e2e [_]
      {:id      :test/inherit-e2e
       :handler (fn [_ data] (assoc data :y (* 2 (:x data))))
       :schema  {:input  [:map [:x :int]]
                 :output [:map [:y :int]]}})
    (let [m {:id :test/inherit-e2e-wf
             :cells {:start {:id       :test/inherit-e2e
                              :schema   :inherit
                              :on-error nil}}
             :edges {:start :end}}
          wf-def (manifest/manifest->workflow m)]
      ;; Cell should be usable
      (is (some? (cell/get-cell :test/inherit-e2e))))))

;; ===== Pipeline shorthand =====

(deftest pipeline-basic-test
  (testing "Pipeline shorthand expands to correct edges"
    (let [m {:id :test/pipeline
             :pipeline [:start :process :render]
             :cells {:start   {:id :test/p-start
                                :schema {:input [:map] :output [:map [:x :int]]}
                                :on-error nil}
                     :process {:id :test/p-process
                                :schema {:input [:map [:x :int]] :output [:map [:y :int]]}
                                :on-error nil}
                     :render  {:id :test/p-render
                                :schema {:input [:map [:y :int]] :output [:map [:html :string]]}
                                :on-error nil}}}
          result (manifest/validate-manifest m)]
      ;; Pipeline should be removed, edges generated
      (is (nil? (:pipeline result)))
      (is (= :process (get-in result [:edges :start])))
      (is (= :render  (get-in result [:edges :process])))
      (is (= :end     (get-in result [:edges :render]))))))

(deftest pipeline-single-cell-test
  (testing "Pipeline with single cell works"
    (let [m {:id :test/pipeline-single
             :pipeline [:start]
             :cells {:start {:id :test/ps-start
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}}
          result (manifest/validate-manifest m)]
      (is (= :end (get-in result [:edges :start]))))))

(deftest pipeline-with-edges-throws-test
  (testing "Pipeline with :edges throws"
    (let [m {:id :test/pipeline-conflict
             :pipeline [:start]
             :cells {:start {:id :test/pc-start
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}
             :edges {:start :end}}]
      (is (thrown-with-msg? Exception #"mutually exclusive"
            (manifest/validate-manifest m))))))

(deftest pipeline-with-dispatches-throws-test
  (testing "Pipeline with :dispatches throws"
    (let [m {:id :test/pipeline-disp
             :pipeline [:start]
             :cells {:start {:id :test/pd-start
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}
             :dispatches {}}]
      (is (thrown-with-msg? Exception #"mutually exclusive"
            (manifest/validate-manifest m))))))

(deftest pipeline-with-fragments-throws-test
  (testing "Pipeline with :fragments throws"
    (let [m {:id :test/pipeline-frag
             :pipeline [:start]
             :cells {:start {:id :test/pf-start
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}
             :fragments {:f {}}}]
      (is (thrown-with-msg? Exception #"mutually exclusive"
            (manifest/validate-manifest m))))))

(deftest pipeline-with-joins-throws-test
  (testing "Pipeline with :joins throws"
    (let [m {:id :test/pipeline-join
             :pipeline [:start]
             :cells {:start {:id :test/pj-start
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}
             :joins {:j {:cells [:start]}}}]
      (is (thrown-with-msg? Exception #"mutually exclusive"
            (manifest/validate-manifest m))))))

(deftest pipeline-unknown-cell-throws-test
  (testing "Pipeline referencing unknown cell throws"
    (let [m {:id :test/pipeline-unknown
             :pipeline [:start :nonexistent]
             :cells {:start {:id :test/pu-start
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}}]
      (is (thrown-with-msg? Exception #"not in :cells"
            (manifest/validate-manifest m))))))

(deftest pipeline-empty-throws-test
  (testing "Empty pipeline throws"
    (let [m {:id :test/pipeline-empty
             :pipeline []
             :cells {:start {:id :test/pe-start
                              :schema {:input [:map] :output [:map]}
                              :on-error nil}}}]
      (is (thrown-with-msg? Exception #"at least 1"
            (manifest/validate-manifest m))))))

;; ===== Parameterized cells in manifest =====

(deftest manifest-parameterized-cell-validates-test
  (testing "Manifest with :params on a cell def passes validation"
    (let [m {:id :test/param-wf
             :cells {:start {:id       :test/pm-cell
                              :schema   {:input [:map [:x :int]] :output [:map [:y :int]]}
                              :params   {:multiplier 3}
                              :on-error nil}}
             :edges {:start :end}}]
      (is (some? (manifest/validate-manifest m))))))

(deftest manifest-parameterized-to-workflow-test
  (testing "manifest->workflow passes params through to workflow cells map"
    (let [m {:id :test/param-wf2
             :cells {:start {:id       :test/pm-cell2
                              :schema   {:input [:map [:x :int]] :output [:map [:y :int]]}
                              :params   {:mode "fast"}
                              :on-error nil}}
             :edges {:start :end}}
          wf-def (manifest/manifest->workflow m)
          cell-ref (get-in wf-def [:cells :start])]
      (is (map? cell-ref))
      (is (= :test/pm-cell2 (:id cell-ref)))
      (is (= {:mode "fast"} (:params cell-ref))))))

(deftest manifest-parameterized-pipeline-e2e-test
  (testing "Pipeline manifest with same cell-id, different params, runs correctly"
    (defmethod cell/cell-spec :test/pm-adder [_]
      {:id      :test/pm-adder
       :handler (fn [_ data]
                  (let [amount (get-in data [:mycelium/params :amount])
                        k      (get-in data [:mycelium/params :output-key])]
                    (assoc data k (+ (:x data) amount))))
       :schema  {:input [:map [:x :int]] :output [:map]}})

    (let [m {:id :test/param-pipeline
             :pipeline [:start :step-b]
             :cells {:start  {:id       :test/pm-adder
                                :schema   :inherit
                                :params   {:amount 10 :output-key :first}
                                :on-error nil}
                     :step-b {:id       :test/pm-adder
                                :schema   :inherit
                                :params   {:amount 100 :output-key :second}
                                :on-error nil}}}
          validated (manifest/validate-manifest m)
          wf-def   (manifest/manifest->workflow validated)
          result   (myc/run-workflow wf-def {} {:x 5})]
      (is (= 15  (:first result)))
      (is (= 105 (:second result))))))

;; ===== Resilience in manifest =====

(deftest manifest-resilience-validates-test
  (testing "Manifest with :resilience passes validation"
    (let [m {:id :test/res-wf
             :cells {:start {:id       :test/res-cell
                              :schema   {:input [:map [:x :int]] :output [:map [:y :int]]}
                              :on-error nil}}
             :edges {:start :end}
             :resilience {:start {:timeout {:timeout-ms 5000}}}}]
      (is (some? (manifest/validate-manifest m))))))

(deftest manifest-resilience-to-workflow-test
  (testing "manifest->workflow passes :resilience through"
    (let [m {:id :test/res-wf2
             :cells {:start {:id       :test/res-cell2
                              :schema   {:input [:map [:x :int]] :output [:map [:y :int]]}
                              :on-error nil}}
             :edges {:start :end}
             :resilience {:start {:retry {:max-attempts 3}}}}
          wf-def (manifest/manifest->workflow m)]
      (is (= {:start {:retry {:max-attempts 3}}} (:resilience wf-def))))))

(deftest pipeline-e2e-run-test
  (testing "Pipeline manifest compiles and runs end-to-end"
    (defmethod cell/cell-spec :test/pipe-double [_]
      {:id      :test/pipe-double
       :handler (fn [_ data] (assoc data :y (* 2 (:x data))))
       :schema  {:input [:map [:x :int]] :output [:map [:y :int]]}})
    (defmethod cell/cell-spec :test/pipe-format [_]
      {:id      :test/pipe-format
       :handler (fn [_ data] (assoc data :result (str "val=" (:y data))))
       :schema  {:input [:map [:y :int]] :output [:map [:result :string]]}})
    (let [m {:id :test/pipeline-e2e
             :pipeline [:start :format]
             :cells {:start  {:id :test/pipe-double
                               :schema {:input [:map [:x :int]] :output [:map [:y :int]]}
                               :on-error nil}
                     :format {:id :test/pipe-format
                               :schema {:input [:map [:y :int]] :output [:map [:result :string]]}
                               :on-error nil}}}
          validated (manifest/validate-manifest m)
          wf-def   (manifest/manifest->workflow validated)
          result   (myc/run-workflow wf-def {} {:x 21})]
      (is (= "val=42" (:result result))))))

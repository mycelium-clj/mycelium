(ns mycelium.schema-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [maestro.core :as fsm]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; --- Helper: register a test cell ---
(defn- register-test-cell! []
  (defmethod cell/cell-spec :test/cell-a [_]
    {:id          :test/cell-a
     :handler     (fn [_ data] (assoc data :y 42))
     :schema      {:input  [:map [:x :int]]
                   :output [:map [:y :int]]}}))

;; ===== validate-input tests =====

(deftest validate-input-passes-valid-data-test
  (testing "validate-input passes valid data (including extra passthrough keys)"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (nil? (schema/validate-input cell {:x 1 :extra "ignored"}))))))

(deftest validate-input-fails-missing-key-test
  (testing "validate-input fails on missing required key"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-input cell {:no-x true}))))))

(deftest validate-input-fails-wrong-type-test
  (testing "validate-input fails on wrong type"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-input cell {:x "not-an-int"}))))))

;; ===== validate-output tests (no dispatches - single schema) =====

(deftest validate-output-passes-valid-test
  (testing "validate-output passes valid data (single schema, no dispatches)"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (nil? (schema/validate-output cell {:y 42 :extra true}))))))

(deftest validate-output-fails-missing-key-test
  (testing "validate-output fails on missing output key"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (some? (schema/validate-output cell {:no-y true}))))))

;; ===== Pre interceptor tests =====

(deftest pre-interceptor-passes-valid-test
  (testing "Pre interceptor passes valid data through unchanged"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          pre         (schema/make-pre-interceptor state->cell)
          fsm-state   {:current-state-id :test/cell-a
                       :data             {:x 10}
                       :trace            []}]
      (is (= fsm-state (pre fsm-state {}))))))

(deftest pre-interceptor-redirects-invalid-test
  (testing "Pre interceptor redirects to ::fsm/error on invalid input, attaches :mycelium/schema-error"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          pre         (schema/make-pre-interceptor state->cell)
          fsm-state   {:current-state-id :test/cell-a
                       :data             {:x "wrong"}
                       :trace            []}
          result      (pre fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (is (some? (get-in result [:data :mycelium/schema-error]))))))

(deftest pre-interceptor-skips-terminal-states-test
  (testing "Pre interceptor skips terminal states (::end, ::error, ::halt)"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          pre         (schema/make-pre-interceptor state->cell)]
      (doseq [terminal [::fsm/end ::fsm/error ::fsm/halt]]
        (let [fsm-state {:current-state-id terminal :data {:x 1} :trace []}]
          (is (= fsm-state (pre fsm-state {}))
              (str "Should skip " terminal)))))))

;; ===== Post interceptor tests (single schema, no dispatches) =====

(deftest post-interceptor-passes-valid-test
  (testing "Post interceptor passes valid output through"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          post        (schema/make-post-interceptor state->cell nil nil)
          fsm-state   {:last-state-id    :test/cell-a
                       :current-state-id :some/next
                       :data             {:x 10 :y 42}
                       :trace            []}
          result      (post fsm-state {})]
      ;; Data should pass through (with trace appended)
      (is (= :some/next (:current-state-id result))))))

(deftest post-interceptor-catches-invalid-output-test
  (testing "Post interceptor catches invalid output → redirects to ::fsm/error"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          post        (schema/make-post-interceptor state->cell nil nil)
          fsm-state   {:last-state-id    :test/cell-a
                       :current-state-id :some/next
                       :data             {:x 10}
                       :trace            []}
          result      (post fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (is (some? (get-in result [:data :mycelium/schema-error]))))))

;; ===== wrap-async-callback tests =====

(deftest wrap-async-callback-forwards-valid-test
  (testing "wrap-async-callback forwards valid output to original callback"
    (register-test-cell!)
    (let [cell     (cell/get-cell! :test/cell-a)
          result   (promise)
          err      (promise)
          wrapped  (schema/wrap-async-callback cell #(deliver result %) #(deliver err %))]
      (wrapped {:y 42})
      (is (= {:y 42} (deref result 1000 :timeout)))
      (is (= :timeout (deref err 100 :timeout))))))

(deftest wrap-async-callback-rejects-invalid-test
  (testing "wrap-async-callback calls error-callback on invalid output"
    (register-test-cell!)
    (let [cell     (cell/get-cell! :test/cell-a)
          result   (promise)
          err      (promise)
          wrapped  (schema/wrap-async-callback cell #(deliver result %) #(deliver err %))]
      (wrapped {:no-y true}) ;; missing :y
      (is (= :timeout (deref result 100 :timeout)))
      (is (not= :timeout (deref err 1000 :timeout))))))

;; ===== output-schema-for-transition tests =====

(deftest output-schema-for-transition-nil-test
  (testing "Returns nil when cell has no output schema"
    (defmethod cell/cell-spec :test/no-out [_]
      {:id          :test/no-out
       :handler     (fn [_ data] data)})
    (let [cell (cell/get-cell! :test/no-out)]
      (is (nil? (schema/output-schema-for-transition cell :ok))))))

(deftest output-schema-for-transition-vector-test
  (testing "Returns vector schema for any transition (backward compat)"
    (register-test-cell!)
    (let [cell (cell/get-cell! :test/cell-a)]
      (is (= [:map [:y :int]] (schema/output-schema-for-transition cell :success)))
      (is (= [:map [:y :int]] (schema/output-schema-for-transition cell :failure))))))

(deftest output-schema-for-transition-map-test
  (testing "Returns per-transition schema from map"
    (defmethod cell/cell-spec :test/per-trans [_]
      {:id          :test/per-trans
       :handler     (fn [_ data] data)
       :schema      {:input  [:map [:x :int]]
                     :output [:per-transition {:found     [:map [:profile [:map [:name :string]]]]
                              :not-found [:map [:error-message :string]]}]}})
    (let [cell (cell/get-cell! :test/per-trans)]
      (is (= [:map [:profile [:map [:name :string]]]]
             (schema/output-schema-for-transition cell :found)))
      (is (= [:map [:error-message :string]]
             (schema/output-schema-for-transition cell :not-found)))
      (is (nil? (schema/output-schema-for-transition cell :unknown))))))

(deftest output-schema-for-transition-keyword-test
  (testing "Returns bare keyword schema (e.g. :map) as-is"
    (defmethod cell/cell-spec :test/kw-out [_]
      {:id          :test/kw-out
       :handler     (fn [_ data] data)
       :schema      {:input :map :output :map}})
    (let [cell (cell/get-cell! :test/kw-out)]
      (is (= :map (schema/output-schema-for-transition cell :ok))))))

;; ===== Per-transition validate-output tests (with transition label) =====

(defn- register-per-transition-cell! []
  (defmethod cell/cell-spec :test/pt-cell [_]
    {:id          :test/pt-cell
     :handler     (fn [_ data] data)
     :schema      {:input  [:map [:x :int]]
                   :output [:per-transition {:success   [:map [:y :int]]
                            :failure   [:map [:error-message :string]]}]}}))

(deftest validate-output-per-transition-passes-test
  (testing "Per-transition schema passes correct data for given transition label"
    (register-per-transition-cell!)
    (let [cell (cell/get-cell! :test/pt-cell)]
      (is (nil? (schema/validate-output cell {:y 42} :success)))
      (is (nil? (schema/validate-output cell {:error-message "oops"} :failure))))))

(deftest validate-output-per-transition-rejects-wrong-data-test
  (testing "Per-transition schema rejects wrong data for given transition label"
    (register-per-transition-cell!)
    (let [cell (cell/get-cell! :test/pt-cell)]
      ;; :success transition but :y is wrong type
      (is (some? (schema/validate-output cell {:y "not-int"} :success)))
      ;; :failure transition but :error-message is wrong type
      (is (some? (schema/validate-output cell {:error-message 42} :failure))))))

(deftest validate-output-per-transition-no-label-any-match-test
  (testing "Per-transition schema with no transition label passes if any schema matches"
    (register-per-transition-cell!)
    (let [cell (cell/get-cell! :test/pt-cell)]
      ;; Data matches :success schema — should pass even without label
      (is (nil? (schema/validate-output cell {:y 42})))
      ;; Data matches neither schema — should fail
      (is (some? (schema/validate-output cell {:x 1}))))))

;; ===== Post interceptor with per-transition schema + edge targets =====

(deftest post-interceptor-per-transition-passes-test
  (testing "Post interceptor infers transition from :current-state-id to select correct schema"
    (register-per-transition-cell!)
    (let [state->cell        {:test/pt-cell (cell/get-cell! :test/pt-cell)}
          ;; Reverse map: state → {target → label}
          state->edge-targets {:test/pt-cell {:some/success-target :success
                                              :some/failure-target :failure}}
          post                (schema/make-post-interceptor state->cell state->edge-targets nil)
          ;; :success path — current-state-id is success target
          fsm-state-s {:last-state-id    :test/pt-cell
                       :current-state-id :some/success-target
                       :data             {:y 42}}
          ;; :failure path — current-state-id is failure target
          fsm-state-f {:last-state-id    :test/pt-cell
                       :current-state-id :some/failure-target
                       :data             {:error-message "oops"}}
          result-s (post fsm-state-s {})
          result-f (post fsm-state-f {})]
      ;; Should pass validation (current-state-id unchanged)
      (is (= :some/success-target (:current-state-id result-s)))
      (is (= :some/failure-target (:current-state-id result-f))))))

(deftest post-interceptor-per-transition-rejects-test
  (testing "Post interceptor rejects data that doesn't match transition-selected schema"
    (register-per-transition-cell!)
    (let [state->cell        {:test/pt-cell (cell/get-cell! :test/pt-cell)}
          state->edge-targets {:test/pt-cell {:some/success-target :success
                                              :some/failure-target :failure}}
          post                (schema/make-post-interceptor state->cell state->edge-targets nil)
          ;; Routed to success target but :y is wrong type
          fsm-state   {:last-state-id    :test/pt-cell
                       :current-state-id :some/success-target
                       :data             {:y "not-int"}}
          result      (post fsm-state {})]
      (is (= ::fsm/error (:current-state-id result))))))

;; ===== Async callback with per-transition schema =====

(deftest wrap-async-callback-per-transition-forwards-test
  (testing "wrap-async-callback forwards valid output for per-transition schema (any-match)"
    (register-per-transition-cell!)
    (let [cell    (cell/get-cell! :test/pt-cell)
          result  (promise)
          err     (promise)
          wrapped (schema/wrap-async-callback cell #(deliver result %) #(deliver err %))]
      ;; Matches :failure schema — passes via any-match fallback
      (wrapped {:error-message "oops"})
      (is (= {:error-message "oops"} (deref result 1000 :timeout)))
      (is (= :timeout (deref err 100 :timeout))))))

(deftest wrap-async-callback-per-transition-rejects-test
  (testing "wrap-async-callback rejects data matching no output schema"
    (register-per-transition-cell!)
    (let [cell    (cell/get-cell! :test/pt-cell)
          result  (promise)
          err     (promise)
          wrapped (schema/wrap-async-callback cell #(deliver result %) #(deliver err %))]
      ;; Matches neither :success nor :failure schema
      (wrapped {:x 1})
      (is (= :timeout (deref result 100 :timeout)))
      (is (not= :timeout (deref err 1000 :timeout))))))

;; ===== Post interceptor trace entry tests =====

(deftest post-interceptor-appends-trace-entry-test
  (testing "Post interceptor appends trace entry with correct structure"
    (register-test-cell!)
    (let [state->cell   {:test/cell-a (cell/get-cell! :test/cell-a)}
          state->names  {:test/cell-a :my-cell}
          post          (schema/make-post-interceptor state->cell nil state->names)
          fsm-state     {:last-state-id    :test/cell-a
                         :current-state-id :some/next
                         :data             {:x 10 :y 42}
                         :trace            []}
          result        (post fsm-state {})]
      (is (= 1 (count (get-in result [:data :mycelium/trace]))))
      (let [entry (first (get-in result [:data :mycelium/trace]))]
        (is (= :my-cell (:cell entry)))
        (is (= :test/cell-a (:cell-id entry)))
        (is (nil? (:transition entry)))
        (is (= {:x 10 :y 42} (:data entry)))))))

(deftest post-interceptor-trace-with-transition-test
  (testing "Post interceptor trace entry includes transition label from edge targets"
    (register-per-transition-cell!)
    (let [state->cell         {:test/pt-cell (cell/get-cell! :test/pt-cell)}
          state->edge-targets {:test/pt-cell {:some/success-target :success}}
          state->names        {:test/pt-cell :checker}
          post                (schema/make-post-interceptor state->cell state->edge-targets state->names)
          fsm-state           {:last-state-id    :test/pt-cell
                               :current-state-id :some/success-target
                               :data             {:y 42}
                               :trace            []}
          result              (post fsm-state {})]
      (let [entry (first (get-in result [:data :mycelium/trace]))]
        (is (= :success (:transition entry)))
        (is (= :checker (:cell entry)))))))

(deftest post-interceptor-trace-excludes-self-test
  (testing "Trace entry data snapshot excludes :mycelium/trace to avoid nesting"
    (register-test-cell!)
    (let [state->cell  {:test/cell-a (cell/get-cell! :test/cell-a)}
          state->names {:test/cell-a :step}
          post         (schema/make-post-interceptor state->cell nil state->names)
          fsm-state    {:last-state-id    :test/cell-a
                        :current-state-id :some/next
                        :data             {:x 10 :y 42 :mycelium/trace [{:cell :prev}]}
                        :trace            []}
          result       (post fsm-state {})]
      (let [entry (second (get-in result [:data :mycelium/trace]))]
        (is (not (contains? (:data entry) :mycelium/trace)))))))

(deftest post-interceptor-trace-on-error-test
  (testing "Post interceptor trace entry includes :error on schema failure"
    (register-test-cell!)
    (let [state->cell  {:test/cell-a (cell/get-cell! :test/cell-a)}
          state->names {:test/cell-a :failing}
          post         (schema/make-post-interceptor state->cell nil state->names)
          ;; data missing :y — will fail output validation
          fsm-state    {:last-state-id    :test/cell-a
                        :current-state-id :some/next
                        :data             {:x 10}
                        :trace            []}
          result       (post fsm-state {})]
      (is (= ::fsm/error (:current-state-id result)))
      (let [entry (first (get-in result [:data :mycelium/trace]))]
        (is (= :failing (:cell entry)))
        (is (some? (:error entry)))))))

;; ===== Pre-compiled schema tests =====

(deftest pre-compile-schemas-compiles-input-test
  (testing "pre-compile-schemas converts raw input schemas to Malli schema objects"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          compiled    (schema/pre-compile-schemas state->cell)
          cell        (get compiled :test/cell-a)]
      ;; Input schema should be a compiled Malli schema object, not a raw vector
      (is (not (vector? (get-in cell [:schema :input]))))
      (is (m/schema? (get-in cell [:schema :input]))))))

(deftest pre-compile-schemas-compiles-output-vector-test
  (testing "pre-compile-schemas converts raw vector output schemas to Malli schema objects"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          compiled    (schema/pre-compile-schemas state->cell)
          cell        (get compiled :test/cell-a)]
      ;; Output schema (vector form) should be compiled
      (is (m/schema? (get-in cell [:schema :output]))))))

(deftest pre-compile-schemas-compiles-output-map-test
  (testing "pre-compile-schemas converts per-transition output schema map values"
    (register-per-transition-cell!)
    (let [state->cell {:test/pt-cell (cell/get-cell! :test/pt-cell)}
          compiled    (schema/pre-compile-schemas state->cell)
          cell        (get compiled :test/pt-cell)
          output      (get-in cell [:schema :output])]
      ;; Output should still be the per-transition wrapper, with values as compiled schemas
      (is (= :per-transition (first output)))
      (is (m/schema? (get-in output [1 :success])))
      (is (m/schema? (get-in output [1 :failure]))))))

(deftest pre-compile-schemas-validate-still-works-test
  (testing "validate-input/output still work with pre-compiled schemas"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          compiled    (schema/pre-compile-schemas state->cell)
          cell        (get compiled :test/cell-a)]
      ;; validate-input still works
      (is (nil? (schema/validate-input cell {:x 1})))
      (is (some? (schema/validate-input cell {:x "bad"})))
      ;; validate-output still works
      (is (nil? (schema/validate-output cell {:y 42})))
      (is (some? (schema/validate-output cell {:no-y true}))))))

(deftest pre-compile-schemas-interceptors-work-test
  (testing "Pre/post interceptors work with pre-compiled schemas"
    (register-test-cell!)
    (let [state->cell {:test/cell-a (cell/get-cell! :test/cell-a)}
          compiled    (schema/pre-compile-schemas state->cell)
          pre         (schema/make-pre-interceptor compiled)
          post        (schema/make-post-interceptor compiled nil nil)]
      ;; Pre: valid data passes
      (let [fsm-state {:current-state-id :test/cell-a :data {:x 10} :trace []}]
        (is (= fsm-state (pre fsm-state {}))))
      ;; Pre: invalid data → error
      (let [fsm-state {:current-state-id :test/cell-a :data {:x "bad"} :trace []}
            result    (pre fsm-state {})]
        (is (= ::fsm/error (:current-state-id result))))
      ;; Post: valid output passes
      (let [fsm-state {:last-state-id :test/cell-a :current-state-id :some/next
                        :data {:x 10 :y 42} :trace []}
            result    (post fsm-state {})]
        (is (= :some/next (:current-state-id result))))
      ;; Post: invalid output → error
      (let [fsm-state {:last-state-id :test/cell-a :current-state-id :some/next
                        :data {:x 10} :trace []}
            result    (post fsm-state {})]
        (is (= ::fsm/error (:current-state-id result)))))))

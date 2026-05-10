(ns mycelium.cell-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(deftest register-valid-cell-test
  (testing "defmethod cell-spec registers cell → retrievable from registry"
    (defmethod cell/cell-spec :test/valid-cell [_]
      {:id          :test/valid-cell
       :handler     (fn [_resources data] data)
       :schema      {:input  [:map [:x :int]]
                     :output [:map [:y :int]]}})
    (is (= :test/valid-cell (:id (cell/get-cell :test/valid-cell))))))

(deftest defmethod-cell-test
  (testing "defmethod cell-spec registers and creates working handler"
    (defmethod cell/cell-spec :test/macro-cell [_]
      {:id          :test/macro-cell
       :doc         "A test cell"
       :handler     (fn [_resources data]
                      (assoc data :b (* 2 (:a data))))})
    (let [spec (cell/get-cell :test/macro-cell)]
      (is (some? spec))
      (is (= :test/macro-cell (:id spec)))
      (is (nil? (:schema spec)))
      (is (= {:b 10 :a 5}
             ((:handler spec) {} {:a 5}))))))

(deftest defmethod-async-test
  (testing "defmethod with :async? true works with callback"
    (defmethod cell/cell-spec :test/async-cell [_]
      {:id          :test/async-cell
       :doc         "An async cell"
       :async?      true
       :handler     (fn [_resources data callback _error-callback]
                      (callback (assoc data :y (inc (:x data)))))})
    (let [spec    (cell/get-cell :test/async-cell)
          result  (promise)]
      (is (:async? spec))
      ((:handler spec) {} {:x 41} #(deliver result %) identity)
      (is (= {:x 41 :y 42} @result)))))

(deftest get-cell-bang-throws-test
  (testing "get-cell! throws on missing ID"
    (is (thrown-with-msg? Exception #"not found"
          (cell/get-cell! :test/nonexistent)))))

(deftest schema-in-defmethod-test
  (testing "Cell can include schema directly in defmethod"
    (defmethod cell/cell-spec :test/with-schema [_]
      {:id          :test/with-schema
       :handler     (fn [_ d] d)
       :schema      {:input  [:map [:x :int]]
                     :output [:map [:y :int]]}})
    (let [spec (cell/get-cell :test/with-schema)]
      (is (some? spec))
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output]))))))

(deftest schema-optional-at-registration-test
  (testing "Cell can be registered without schema"
    (defmethod cell/cell-spec :test/no-schema [_]
      {:id          :test/no-schema
       :handler     (fn [_ d] d)})
    (let [spec (cell/get-cell :test/no-schema)]
      (is (some? spec))
      (is (nil? (:schema spec))))))

(deftest set-cell-schema-test
  (testing "set-cell-schema! attaches schema to existing cell"
    (defmethod cell/cell-spec :test/schema-later [_]
      {:id          :test/schema-later
       :handler     (fn [_ d] d)})
    (cell/set-cell-schema! :test/schema-later
                           {:input  [:map [:x :int]]
                            :output [:map [:y :int]]})
    (let [spec (cell/get-cell :test/schema-later)]
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output]))))))

(deftest set-cell-schema-throws-not-found-test
  (testing "set-cell-schema! throws if cell not registered"
    (is (thrown-with-msg? Exception #"not found"
          (cell/set-cell-schema! :test/nonexistent {:input [:map] :output [:map]})))))

(deftest set-cell-schema-throws-invalid-schema-test
  (testing "set-cell-schema! throws on invalid Malli schema"
    (defmethod cell/cell-spec :test/bad-schema-later [_]
      {:id          :test/bad-schema-later
       :handler     (fn [_ d] d)})
    (is (thrown? Exception
          (cell/set-cell-schema! :test/bad-schema-later
                                {:input  [:not-a-real-schema-type]
                                 :output [:map [:y :int]]})))))

;; ===== Per-transition output schema =====

(deftest register-cell-map-output-schema-test
  (testing "Map output schema in defmethod"
    (defmethod cell/cell-spec :test/map-out [_]
      {:id          :test/map-out
       :handler     (fn [_ d] d)
       :schema      {:input  [:map [:x :int]]
                     :output [:per-transition {:found     [:map [:profile :map]]
                              :not-found [:map [:error-message :string]]}]}})
    (let [output (get-in (cell/get-cell :test/map-out) [:schema :output])]
      (is (= :per-transition (first output)))
      (is (= #{:found :not-found} (set (keys (second output))))))))

;; ===== Per-transition output schema in set-cell-schema! =====

(deftest set-cell-schema-map-output-test
  (testing "set-cell-schema! accepts map format"
    (defmethod cell/cell-spec :test/set-map-out [_]
      {:id          :test/set-map-out
       :handler     (fn [_ d] d)})
    (cell/set-cell-schema! :test/set-map-out
                           {:input  [:map [:x :int]]
                            :output [:per-transition {:found     [:map [:profile :map]]
                                     :not-found [:map [:error :string]]}]})
    (let [output (get-in (cell/get-cell :test/set-map-out) [:schema :output])]
      (is (= :per-transition (first output))))))

(deftest redefine-does-not-clear-overrides-test
  (testing "Re-defining via defmethod does NOT clear overrides (they persist until clear-registry!)"
    (defmethod cell/cell-spec :test/override-persist [_]
      {:id          :test/override-persist
       :handler     (fn [_ d] d)})
    (cell/set-cell-schema! :test/override-persist
                           {:input  [:map [:x :int]]
                            :output [:map [:y :int]]})
    (is (some? (:schema (cell/get-cell :test/override-persist))))
    ;; Re-define via defmethod — overrides should persist
    (defmethod cell/cell-spec :test/override-persist [_]
      {:id          :test/override-persist
       :handler     (fn [_ d] d)})
    (is (some? (:schema (cell/get-cell :test/override-persist))))
    ;; clear-registry! clears overrides
    (cell/clear-registry!)
    (is (nil? (cell/get-cell :test/override-persist)))))

;; ===== set-cell-meta! tests =====

(deftest set-cell-meta-test
  (testing "set-cell-meta! sets schema and requires on a cell"
    (defmethod cell/cell-spec :test/meta-cell [_]
      {:id      :test/meta-cell
       :handler (fn [_ d] d)})
    (cell/set-cell-meta! :test/meta-cell
                         {:schema      {:input  [:map [:x :int]]
                                        :output [:map [:y :int]]}
                          :requires    [:db]})
    (let [spec (cell/get-cell :test/meta-cell)]
      (is (= [:map [:x :int]] (get-in spec [:schema :input])))
      (is (= [:map [:y :int]] (get-in spec [:schema :output])))
      (is (= [:db] (:requires spec))))))

(deftest set-cell-meta-without-transitions-test
  (testing "set-cell-meta! works without :transitions (transitions are now optional)"
    (defmethod cell/cell-spec :test/meta-no-trans [_]
      {:id      :test/meta-no-trans
       :handler (fn [_ d] d)})
    (cell/set-cell-meta! :test/meta-no-trans
                         {:schema {:input  [:map [:x :int]]
                                   :output [:map [:y :int]]}})
    (let [spec (cell/get-cell :test/meta-no-trans)]
      (is (some? (:schema spec))))))

(deftest set-cell-meta-validates-schema-test
  (testing "set-cell-meta! rejects invalid Malli schema"
    (defmethod cell/cell-spec :test/meta-bad-schema [_]
      {:id      :test/meta-bad-schema
       :handler (fn [_ d] d)})
    (is (thrown? Exception
          (cell/set-cell-meta! :test/meta-bad-schema
                               {:schema {:input [:not-a-real-schema-type]
                                         :output [:map [:y :int]]}})))))

;; ===== Cell without :transitions registers fine =====

(deftest cell-without-transitions-test
  (testing "Cell can be registered without :transitions"
    (defmethod cell/cell-spec :test/no-trans [_]
      {:id      :test/no-trans
       :handler (fn [_ d] d)
       :schema  {:input [:map] :output [:map]}})
    (let [spec (cell/get-cell :test/no-trans)]
      (is (some? spec))
      (is (nil? (:transitions spec))))))

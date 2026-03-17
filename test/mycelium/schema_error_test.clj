(ns mycelium.schema-error-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.schema :as schema]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

(def ^:private on-error (fn [_resources fsm-state] (:data fsm-state)))

;; ===== 1. Key-diff: typo suggestion (rename :tax-amount to :tax) =====

(deftest key-diff-typo-suggestion-test
  (testing "Schema error suggests rename when extra key is close to missing key"
    (cell/defcell :test/tax
      {:doc    "Computes tax from subtotal"
       :input  {:subtotal :double}
       :output {:tax :double}}
      ;; Intentional typo: :tax-amount instead of :tax
      (fn [_ data] {:tax-amount (* (:subtotal data) 0.1)}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/tax}
                    :edges {:start :end}}
                   {} {:subtotal 100.0} {:on-error on-error})
          err    (myc/workflow-error result)]
      (is (some? err))
      (is (= :schema/output (:error-type err)))
      ;; Error should mention the extra key
      (is (some? (:key-diff err)))
      (is (contains? (:missing (:key-diff err)) :tax))
      (is (contains? (:extra (:key-diff err)) :tax-amount)))))

;; ===== 2. Multiple missing/extra keys =====

(deftest key-diff-multiple-keys-test
  (testing "Schema error shows all missing and extra keys"
    (cell/defcell :test/multi-key
      {:doc    "Produces alpha, beta, gamma from input x"
       :input  {:x :int}
       :output {:alpha :string :beta :int :gamma :double}}
      ;; Return wrong keys
      (fn [_ data] {:alfa "hello" :betta 42 :gama 3.14}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/multi-key}
                    :edges {:start :end}}
                   {} {:x 1} {:on-error on-error})
          err    (myc/workflow-error result)]
      (is (some? err))
      (is (= #{:alpha :beta :gamma} (:missing (:key-diff err))))
      ;; Extra includes typos + propagated input key :x
      (is (every? #(contains? (:extra (:key-diff err)) %)
                  [:alfa :betta :gama])))))

;; ===== 3. Genuinely missing key — no suggestion =====

(deftest key-diff-genuinely-missing-test
  (testing "Missing key with no close extra key has no suggestion"
    (cell/defcell :test/missing
      {:doc    "Doubles x and adds a status flag"
       :input  {:x :int}
       :output {:result :int :status :keyword}}
      ;; Only return :result, forget :status entirely
      (fn [_ data] {:result (* 2 (:x data))}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/missing}
                    :edges {:start :end}}
                   {} {:x 5} {:on-error on-error})
          err    (myc/workflow-error result)]
      (is (some? err))
      (is (contains? (:missing (:key-diff err)) :status))
      ;; :x is from input propagation, not a typo — but :status is genuinely missing
      (is (not (contains? (:extra (:key-diff err)) :status))))))

;; ===== 4. Extra keys with no close match =====

(deftest key-diff-extra-no-match-test
  (testing "Extra keys are reported even without close match to missing keys"
    (cell/defcell :test/extra
      {:doc    "Doubles x into y"
       :input  {:x :int}
       :output {:y :int}}
      ;; Return correct key plus unexpected extra
      (fn [_ data] {:y (* 2 (:x data)) :debug-info "should not be here"}))
    ;; Open-map semantics: extra keys pass through, no error on output
    ;; This test verifies the key-diff captures extras when there IS a schema failure
    (cell/defcell :test/extra-wrong
      {:doc    "Should produce y but intentionally returns z"
       :input  {:x :int}
       :output {:y :int}}
      ;; Missing required :y, has extra :z
      (fn [_ data] {:z 999}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/extra-wrong}
                    :edges {:start :end}}
                   {} {:x 5} {:on-error on-error})
          err    (myc/workflow-error result)]
      (is (some? err))
      (is (contains? (:missing (:key-diff err)) :y))
      (is (contains? (:extra (:key-diff err)) :z)))))

;; ===== 5. workflow-error returns enhanced message with suggestions =====

(deftest workflow-error-enhanced-message-test
  (testing "workflow-error message includes suggestion text"
    (cell/defcell :test/msg
      {:doc    "Greets the user by name"
       :input  {:name :string}
       :output {:greeting :string}}
      ;; Typo: :greting instead of :greeting
      (fn [_ data] {:greting (str "Hello " (:name data))}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/msg}
                    :edges {:start :end}}
                   {} {:name "Alice"} {:on-error on-error})
          err    (myc/workflow-error result)]
      (is (some? err))
      ;; Message should mention the suggestion
      (is (re-find #"(?i)greting" (:message err)))
      (is (re-find #"(?i)greeting" (:message err))))))

;; ===== 6. Key-diff works for input validation too =====

(deftest key-diff-input-validation-test
  (testing "Key-diff suggestions work for input schema errors"
    (cell/defcell :test/input-typo
      {:doc    "Validates username and email"
       :input  {:username :string :email :string}
       :output {:valid :boolean}}
      (fn [_ data] {:valid true}))
    (let [result (myc/run-workflow
                   {:cells {:start :test/input-typo}
                    :edges {:start :end}}
                   {} {:user-name "alice" :emial "alice@test.com"}
                   {:on-error on-error})
          err    (myc/workflow-error result)]
      (is (some? err))
      (is (= :schema/input (:error-type err)))
      ;; Should detect missing :username and extra :user-name
      (is (some? (:key-diff err))))))

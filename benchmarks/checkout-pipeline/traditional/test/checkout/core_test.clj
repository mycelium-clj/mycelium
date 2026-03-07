(ns checkout.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [checkout.core :as checkout]))

;; ── Product catalog (shared fixture) ──────────────────────────────────
(def catalog
  {"widget" {:name "Widget" :price 25.00 :weight 1.0}
   "gadget" {:name "Gadget" :price 150.00 :weight 3.0}
   "gizmo"  {:name "Gizmo"  :price 75.00  :weight 2.0}})

;; ── Coupon database ───────────────────────────────────────────────────
(def coupons
  {"SAVE10"   {:type :percentage :value 10 :min-order nil}
   "SAVE20"   {:type :percentage :value 20 :min-order 100}
   "FLAT15"   {:type :fixed      :value 15 :min-order nil}
   "BIGSPEND" {:type :percentage :value 10 :min-order 50}})

;; ── Inventory (mutable for reservation tests) ─────────────────────────
(defn fresh-inventory []
  (atom {"widget" 100 "gadget" 50 "gizmo" 30}))

;; ── Tax rates ─────────────────────────────────────────────────────────
(def tax-rates
  {"CA" 0.0725
   "NY" 0.08875
   "OR" 0.0
   "TX" 0.0625})

;; ── Helper to build checkout request ──────────────────────────────────
(defn make-request
  [{:keys [items coupon membership state card]}]
  {:items      items
   :coupon     coupon
   :membership (or membership :none)
   :state      state
   :card       card})

;; ── Helper: round to 2 decimal places (half-up) ──────────────────────
(defn round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

;; ══════════════════════════════════════════════════════════════════════
;;  Test Cases
;; ══════════════════════════════════════════════════════════════════════

(deftest test-1-basic-flow
  (testing "Basic checkout: 2x Widget, no discounts, CA tax"
    (let [inventory (fresh-inventory)
          result    (checkout/process-checkout
                      (make-request {:items [{"widget" 2}]
                                     :state "CA"
                                     :card  "4111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :success (:status result)))
      (is (= 60.62 (round2 (:total result))))
      (is (= 50.00 (round2 (:discounted-subtotal result))))
      (is (= 3.63  (round2 (:tax result))))
      (is (= 6.99  (round2 (:shipping result))))
      (is (some? (:transaction-id result)))
      ;; inventory decremented
      (is (= 98 (get @inventory "widget"))))))

(deftest test-2-coupon-plus-membership-shipping
  (testing "SAVE10 coupon + silver membership free shipping"
    (let [inventory (fresh-inventory)
          result    (checkout/process-checkout
                      (make-request {:items      [{"gadget" 1} {"widget" 1}]
                                     :coupon     "SAVE10"
                                     :membership :silver
                                     :state      "NY"
                                     :card       "4111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :success (:status result)))
      (is (= 171.48 (round2 (:total result))))
      (is (= 157.50 (round2 (:discounted-subtotal result))))
      (is (= 13.98  (round2 (:tax result))))
      (is (= 0.0    (round2 (:shipping result)))))))

(deftest test-3-gold-fixed-coupon-no-tax
  (testing "Gold membership + FLAT15 fixed coupon + Oregon (no tax)"
    (let [inventory (fresh-inventory)
          result    (checkout/process-checkout
                      (make-request {:items      [{"widget" 3} {"gadget" 2}]
                                     :coupon     "FLAT15"
                                     :membership :gold
                                     :state      "OR"
                                     :card       "4111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :success (:status result)))
      (is (= 341.25 (round2 (:total result))))
      (is (= 341.25 (round2 (:discounted-subtotal result))))
      (is (= 0.0    (round2 (:tax result))))
      (is (= 0.0    (round2 (:shipping result)))))))

(deftest test-4-platinum-tiered
  (testing "Platinum + tiered discount, priority fulfillment"
    (let [inventory (fresh-inventory)
          result    (checkout/process-checkout
                      (make-request {:items      [{"widget" 10}]
                                     :membership :platinum
                                     :state      "TX"
                                     :card       "4111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :success (:status result)))
      (is (= 239.06 (round2 (:total result))))
      (is (= 225.00 (round2 (:discounted-subtotal result))))
      (is (= 14.06  (round2 (:tax result))))
      (is (= 0.0    (round2 (:shipping result))))
      (is (true? (:priority-fulfillment result))))))

(deftest test-5-coupon-min-threshold-rejected
  (testing "Coupon rejected when cart below minimum"
    (let [inventory (fresh-inventory)
          result    (checkout/process-checkout
                      (make-request {:items  [{"widget" 1}]
                                     :coupon "BIGSPEND"
                                     :state  "CA"
                                     :card   "4111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :success (:status result)))
      (is (= 33.30 (round2 (:total result))))
      (is (= 25.00 (round2 (:discounted-subtotal result))))
      (is (= 1.81  (round2 (:tax result))))
      (is (= 6.49  (round2 (:shipping result))))
      (is (some? (:coupon-warning result))))))

(deftest test-6-payment-failure-rollback
  (testing "Payment decline triggers inventory rollback"
    (let [inventory (fresh-inventory)
          original  @inventory
          result    (checkout/process-checkout
                      (make-request {:items [{"widget" 2}]
                                     :state "CA"
                                     :card  "5111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :error (:status result)))
      (is (= "Payment declined" (:error result)))
      ;; inventory must be restored
      (is (= original @inventory)))))

(deftest test-7-empty-cart
  (testing "Empty cart rejected at validation"
    (let [inventory (fresh-inventory)
          result    (checkout/process-checkout
                      (make-request {:items []
                                     :state "CA"
                                     :card  "4111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :error (:status result)))
      (is (= "Cart is empty" (:error result))))))

(deftest test-8-complex-stacking
  (testing "SAVE10 vs Gold (highest percentage wins) + gold free shipping"
    (let [inventory (fresh-inventory)
          result    (checkout/process-checkout
                      (make-request {:items      [{"widget" 2} {"gadget" 2}]
                                     :coupon     "SAVE10"
                                     :membership :gold
                                     :state      "NY"
                                     :card       "4111111111111111"})
                      {:catalog   catalog
                       :coupons   coupons
                       :inventory inventory
                       :tax-rates tax-rates})]
      (is (= :success (:status result)))
      (is (= 342.96 (round2 (:total result))))
      (is (= 315.00 (round2 (:discounted-subtotal result))))
      (is (= 27.96  (round2 (:tax result))))
      (is (= 0.0    (round2 (:shipping result)))))))

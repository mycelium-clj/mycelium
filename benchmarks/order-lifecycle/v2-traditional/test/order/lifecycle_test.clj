(ns order.lifecycle-test
  (:require [clojure.test :refer [deftest testing is]]
            [order.placement :as placement]
            [order.returns :as returns]
            [order.modification :as modification]))

;; ── Shared fixtures ───────────────────────────────────────────────────

(def catalog
  {"laptop"     {:name "Laptop"     :category :electronics :price 999.99 :weight 5.0 :warehouse "west"}
   "shirt"      {:name "T-Shirt"    :category :clothing    :price  29.99 :weight 0.5 :warehouse "east"}
   "novel"      {:name "Novel"      :category :books       :price  14.99 :weight 0.8 :warehouse "east"}
   "headphones" {:name "Headphones" :category :electronics :price  79.99 :weight 0.3 :warehouse "west"}
   "ebook"      {:name "E-Book"     :category :digital     :price   9.99 :weight 0.0 :warehouse "digital"}})

(def coupons
  {"SAVE10" {:type :percentage :value 10 :min-order nil}
   "SAVE20" {:type :percentage :value 20 :min-order 100}
   "FLAT15" {:type :fixed      :value 15 :min-order nil}})

(def tax-rates
  {"CA" {:base 0.0725 :electronics-surcharge 0.015}
   "NY" {:base 0.08875 :clothing-exempt-under 110.0 :books-exempt true}
   "OR" {:base 0.0}
   "TX" {:base 0.0625 :digital-exempt true}})

(defn fresh-inventory []
  (atom {"laptop" 100 "headphones" 50 "shirt" 200 "novel" 150 "ebook" 999}))

(defn round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

(defn make-order
  [{:keys [items coupon membership state card gift-card-balance store-credit-balance loyalty-points currency]
    :or   {membership :bronze gift-card-balance 0 store-credit-balance 0 loyalty-points 0 currency "USD"}}]
  {:items                items
   :coupon               coupon
   :membership           membership
   :state                state
   :card                 card
   :gift-card-balance    gift-card-balance
   :store-credit-balance store-credit-balance
   :loyalty-points       loyalty-points
   :currency             currency
   :new-customer         false})

(defn resources [inventory]
  {:catalog   catalog
   :coupons   coupons
   :tax-rates tax-rates
   :inventory inventory})

;; ══════════════════════════════════════════════════════════════════════
;;  ORDER PLACEMENT TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t1-single-item-baseline
  (testing "Single laptop, CA, no promos, bronze"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 949.99  (round2 (:discounted-subtotal result))))
      (is (= 83.12   (round2 (:tax result))))
      (is (= 0.0     (round2 (:shipping result))))
      (is (= 1033.11 (round2 (:total result))))
      (is (= 949     (get-in result [:loyalty :points-earned])))
      (is (= 99      (get @inv "laptop"))))))

(deftest t2-elec10-triggers
  (testing "2 electronics items trigger ELEC10"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 923.38  (round2 (:discounted-subtotal result))))
      (is (= 80.79   (round2 (:tax result))))
      (is (= 0.0     (round2 (:shipping result))))
      (is (= 1004.17 (round2 (:total result))))
      (is (= 923     (get-in result [:loyalty :points-earned]))))))

(deftest t3-bundle5-triggers
  (testing "Electronics + books triggers BUNDLE5"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 90.23  (round2 (:discounted-subtotal result))))
      (is (= 7.68   (round2 (:tax result))))
      (is (= 6.39   (round2 (:shipping result))))
      (is (= 104.30 (round2 (:total result))))
      (is (= 90     (get-in result [:loyalty :points-earned]))))))

(deftest t4-complex-stacking
  (testing "COMBO75 + ELEC10 + BUNDLE5 + tiered all stack"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 829.73 (round2 (:discounted-subtotal result))))
      (is (= 72.41  (round2 (:tax result))))
      (is (= 6.39   (round2 (:shipping result))))
      (is (= 908.53 (round2 (:total result))))
      (is (= 829    (get-in result [:loyalty :points-earned])))
      ;; Verify per-item final prices for return tracking
      (let [items (:items-detail result)]
        (is (= 756.61 (round2 (:final-price (first (filter #(= "laptop" (:product-id %)) items))))))
        (is (= 60.52  (round2 (:final-price (first (filter #(= "headphones" (:product-id %)) items))))))
        (is (= 12.60  (round2 (:final-price (first (filter #(= "novel" (:product-id %)) items))))))))))

(deftest t5-ny-tax-exemptions
  (testing "NY: clothing and books exempt from tax"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items  [{"shirt" 1} {"novel" 1}]
                                :coupon "SAVE10"
                                :state  "NY"
                                :card   "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 40.48 (round2 (:discounted-subtotal result))))
      (is (= 0.0   (round2 (:tax result))))
      (is (= 6.64  (round2 (:shipping result))))
      (is (= 47.12 (round2 (:total result))))
      (is (= 40    (get-in result [:loyalty :points-earned]))))))

(deftest t6-loyalty-redemption-gold
  (testing "Loyalty redemption + gold membership + OR no tax"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items          [{"laptop" 1}]
                                :membership     :gold
                                :state          "OR"
                                :card           "4111111111111111"
                                :loyalty-points 500})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 924.99 (round2 (:discounted-subtotal result))))
      (is (= 0.0    (round2 (:tax result))))
      (is (= 0.0    (round2 (:shipping result))))
      (is (= 924.99 (round2 (:total result))))
      (is (= 25.0   (round2 (get-in result [:loyalty :redemption-amount]))))
      (is (= 1849   (get-in result [:loyalty :points-earned]))))))

(deftest t7-split-payment
  (testing "Split payment: gift card + credit card"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items             [{"laptop" 1} {"shirt" 1}]
                                :state             "CA"
                                :card              "4111111111111111"
                                :gift-card-balance  200.0})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 926.98  (round2 (:discounted-subtotal result))))
      (is (= 80.71   (round2 (:tax result))))
      (is (= 6.24    (round2 (:shipping result))))
      (is (= 1013.93 (round2 (:total result))))
      (is (= 200.0   (round2 (get-in result [:payment :gift-card-charged]))))
      (is (= 813.93  (round2 (get-in result [:payment :credit-card-charged]))))
      (is (= 926     (get-in result [:loyalty :points-earned]))))))

(deftest t8-multi-warehouse-shipping
  (testing "Items from west, east, and digital warehouses"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 1} {"shirt" 1} {"novel" 1} {"ebook" 1}]
                                :state "TX"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 903.79 (round2 (:discounted-subtotal result))))
      (is (= 55.93  (round2 (:tax result))))
      (is (= 6.64   (round2 (:shipping result))))
      (is (= 966.36 (round2 (:total result))))
      (is (= 903    (get-in result [:loyalty :points-earned]))))))

(deftest t9-fraud-reject
  (testing "High-value order rejected by fraud check"
    (let [inv    (fresh-inventory)
          orig   @inv
          result (placement/place-order
                   (make-order {:items [{"laptop" 10}]
                                :state "TX"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :error (:status result)))
      (is (= "Order rejected: fraud check failed" (:error result)))
      ;; inventory should NOT be reserved
      (is (= orig @inv)))))

(deftest t10-payment-decline-rollback
  (testing "Payment decline triggers inventory rollback"
    (let [inv    (fresh-inventory)
          orig   @inv
          result (placement/place-order
                   (make-order {:items [{"laptop" 1}]
                                :state "CA"
                                :card  "5111111111111111"})
                   (resources inv))]
      (is (= :error (:status result)))
      (is (= "Payment declined" (:error result)))
      (is (= orig @inv)))))

;; ══════════════════════════════════════════════════════════════════════
;;  RETURN TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t11-full-return-defective
  (testing "Full return of simple order, defective"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"laptop" 1}]
                    :reason         :defective}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 949.99  (round2 (:subtotal-refund result))))
      (is (= 83.12   (round2 (:tax-refund result))))
      (is (= 0.0     (round2 (:shipping-refund result))))
      (is (= 1033.11 (round2 (:total-refund result))))
      (is (= 949     (:loyalty-clawback result)))
      (is (= 1033.11 (round2 (get-in result [:payment :credit-card-refunded]))))
      ;; inventory restored
      (is (= 100 (get @inv "laptop"))))))

(deftest t12-partial-return-changed-mind
  (testing "Partial return of COMBO75 order, changed mind, no shipping refund"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"headphones" 1}]
                    :reason         :changed-mind}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 9.08   (round2 (:restocking-fee result))))
      (is (= 51.44  (round2 (:subtotal-refund result))))
      (is (= 5.30   (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:shipping-refund result))))
      (is (= 56.74  (round2 (:total-refund result))))
      (is (= 51     (:loyalty-clawback result)))
      (is (= 56.74  (round2 (get-in result [:payment :credit-card-refunded]))))
      ;; headphones inventory restored
      (is (= 50 (get @inv "headphones"))))))

(deftest t13-return-split-payment
  (testing "Return from split payment order, refund to credit card first"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items             [{"laptop" 1} {"shirt" 1}]
                                :state             "CA"
                                :card              "4111111111111111"
                                :gift-card-balance  200.0})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"shirt" 1}]
                    :reason         :changed-mind}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 2.70   (round2 (:restocking-fee result))))
      (is (= 24.29  (round2 (:subtotal-refund result))))
      (is (= 1.96   (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:shipping-refund result))))
      (is (= 26.25  (round2 (:total-refund result))))
      (is (= 24     (:loyalty-clawback result)))
      ;; Refund goes to credit card first (reverse of charge order)
      (is (= 26.25  (round2 (get-in result [:payment :credit-card-refunded]))))
      (is (= 0.0    (round2 (get-in result [:payment :gift-card-refunded])))))))

;; ══════════════════════════════════════════════════════════════════════
;;  ORDER MODIFICATION TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t14-increase-quantity
  (testing "Increase laptop qty 1->2: ELEC10 now triggers, charge delta"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (modification/modify-order
                   order
                   {:changes [{"laptop" 2}]}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 1619.98 (round2 (:new-subtotal result))))
      (is (= 141.74  (round2 (:new-tax result))))
      (is (= 0.0     (round2 (:new-shipping result))))
      (is (= 1761.72 (round2 (:new-total result))))
      (is (= 728.61  (round2 (:delta result))))
      (is (= :charge (:delta-action result)))
      (is (= 1619    (:new-points result)))
      (is (= 670     (:points-delta result))))))

(deftest t15-decrease-removes-bundle-discount
  (testing "Remove novel: COMBO75+BUNDLE5 lost, total actually increases"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (modification/modify-order
                   order
                   {:changes [{"laptop" 1} {"headphones" 1}]}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 923.38  (round2 (:new-subtotal result))))
      (is (= 80.79   (round2 (:new-tax result))))
      (is (= 0.0     (round2 (:new-shipping result))))
      (is (= 1004.17 (round2 (:new-total result))))
      ;; Counterintuitive: removing novel INCREASES total because COMBO75+BUNDLE5 lost
      (is (= 95.64   (round2 (:delta result))))
      (is (= :charge (:delta-action result)))
      (is (= 923     (:new-points result)))
      (is (= 94      (:points-delta result))))))

;; ══════════════════════════════════════════════════════════════════════
;;  ROUND 2: COMBO75 BUNDLE PURCHASE DISCOUNT
;;  New feature: when laptop + headphones + novel are ALL in the order,
;;  apply a flat $75 discount distributed proportionally by price
;;  BEFORE ELEC10/BUNDLE5.
;; ══════════════════════════════════════════════════════════════════════

(deftest t16-combo75-stacks-with-all-promos
  (testing "COMBO75 + ELEC10 + BUNDLE5 + tiered all stack"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 829.73 (round2 (:discounted-subtotal result))))
      (is (= 72.41  (round2 (:tax result))))
      (is (= 6.39   (round2 (:shipping result))))
      (is (= 908.53 (round2 (:total result))))
      (is (= 829    (get-in result [:loyalty :points-earned])))
      ;; Verify per-item final prices after all promos stack
      (let [items (:items-detail result)]
        (is (= 756.61 (round2 (:final-price (first (filter #(= "laptop" (:product-id %)) items))))))
        (is (= 60.52  (round2 (:final-price (first (filter #(= "headphones" (:product-id %)) items))))))
        (is (= 12.60  (round2 (:final-price (first (filter #(= "novel" (:product-id %)) items))))))))))

(deftest t17-return-from-combo75-order
  (testing "Return headphones from COMBO75 order, defective"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"headphones" 1}]
                    :reason         :defective}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 60.52  (round2 (:subtotal-refund result))))
      (is (= 5.30   (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:shipping-refund result))))
      (is (= 65.82  (round2 (:total-refund result))))
      (is (= 60     (:loyalty-clawback result)))
      (is (= 65.82  (round2 (get-in result [:payment :credit-card-refunded]))))
      ;; headphones inventory restored
      (is (= 50 (get @inv "headphones"))))))

(deftest t18-modify-combo75-order-removes-discount
  (testing "Remove headphones from COMBO75 order: COMBO75+ELEC10 lost, total increases"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (modification/modify-order
                   order
                   {:changes [{"laptop" 1} {"novel" 1}]}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 916.02 (round2 (:new-subtotal result))))
      (is (= 79.95  (round2 (:new-tax result))))
      (is (= 6.39   (round2 (:new-shipping result))))
      (is (= 1002.36 (round2 (:new-total result))))
      ;; Counterintuitive: removing headphones INCREASES total because COMBO75+ELEC10 lost
      (is (= 93.83  (round2 (:delta result))))
      (is (= :charge (:delta-action result)))
      (is (= 916    (:new-points result)))
      (is (= 87     (:points-delta result))))))

;; ══════════════════════════════════════════════════════════════════════
;;  STORE CREDIT TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t22-three-way-split-payment
  (testing "Three-way split payment: gift card + store credit + credit card"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items                [{"laptop" 1} {"shirt" 1}]
                                :state                "CA"
                                :card                 "4111111111111111"
                                :gift-card-balance     200.0
                                :store-credit-balance  300.0})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 1013.93 (round2 (:total result))))
      (is (= 200.0   (round2 (get-in result [:payment :gift-card-charged]))))
      (is (= 300.0   (round2 (get-in result [:payment :store-credit-charged]))))
      (is (= 513.93  (round2 (get-in result [:payment :credit-card-charged])))))))

;; ══════════════════════════════════════════════════════════════════════
;;  BULK PRICING TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t26-bulk-pricing-3-laptops
  (testing "3x laptop: 5% bulk discount + ELEC10 + tiered 10%"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 3}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 2308.47  (round2 (:discounted-subtotal result))))
      (is (= 201.99   (round2 (:tax result))))
      (is (= 0.0      (round2 (:shipping result))))
      (is (= 2510.46  (round2 (:total result))))
      (is (= 2308     (get-in result [:loyalty :points-earned])))
      (is (= 97       (get @inv "laptop"))))))

(deftest t27-bulk-combo75-elec10-stacking
  (testing "3x laptop + 1x headphones + 1x novel: bulk + COMBO75 + ELEC10 + BUNDLE5 + tiered"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 3} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 2209.67  (round2 (:discounted-subtotal result))))
      (is (= 193.16   (round2 (:tax result))))
      (is (= 6.39     (round2 (:shipping result))))
      (is (= 2409.22  (round2 (:total result))))
      (is (= 2209     (get-in result [:loyalty :points-earned]))))))

(deftest t30-modification-bulk-tier-change
  (testing "Modify 5x laptop (10% bulk) to 2x laptop (no bulk): refund delta"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 5}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (modification/modify-order
                   order
                   {:changes [{"laptop" 2}]}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 1619.98 (round2 (:new-subtotal result))))
      (is (= 141.74  (round2 (:new-tax result))))
      (is (= 0.0     (round2 (:new-shipping result))))
      (is (= 1761.72 (round2 (:new-total result))))
      (is (= 2202.18 (round2 (:delta result))))
      (is (= :refund  (:delta-action result)))
      (is (= 1619    (:new-points result)))
      (is (= 2025    (:points-delta result))))))

;; ══════════════════════════════════════════════════════════════════════
;;  GIFT WRAPPING TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t20-gift-wrapped-laptop-non-wrapped-shirt
  (testing "Gift-wrapped laptop + non-wrapped shirt, CA"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" {:qty 1 :gift-wrap true}} {"shirt" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 926.98  (round2 (:discounted-subtotal result))))
      (is (= 80.71   (round2 (:tax result))))
      (is (= 6.24    (round2 (:shipping result))))
      (is (= 4.99    (round2 (:gift-wrap-total result))))
      (is (= 0.40    (round2 (:gift-wrap-tax result))))
      (is (= 1019.32 (round2 (:total result)))))))

(deftest t21-gift-wrapped-novel-or-gold
  (testing "Gift-wrapped novel in OR, gold membership"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items      [{"novel" {:qty 1 :gift-wrap true}}]
                                :membership :gold
                                :state      "OR"
                                :card       "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 14.99  (round2 (:discounted-subtotal result))))
      (is (= 0.0    (round2 (:tax result))))
      (is (= 0.0    (round2 (:shipping result))))
      (is (= 2.99   (round2 (:gift-wrap-total result))))
      (is (= 0.0    (round2 (:gift-wrap-tax result))))
      (is (= 17.98  (round2 (:total result)))))))

(deftest t24-return-defective-gift-wrapped-laptop
  (testing "Return defective gift-wrapped laptop, CA"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" {:qty 1 :gift-wrap true}}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          ;; Verify placement values
          _      (is (= 949.99 (round2 (:discounted-subtotal order))))
          _      (is (= 83.12  (round2 (:tax order))))
          _      (is (= 0.0    (round2 (:shipping order))))
          _      (is (= 4.99   (round2 (:gift-wrap-total order))))
          _      (is (= 0.40   (round2 (:gift-wrap-tax order))))
          _      (is (= 1038.50 (round2 (:total order))))
          result (returns/process-return
                   order
                   {:returned-items [{"laptop" 1}]
                    :reason         :defective}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 949.99 (round2 (:subtotal-refund result))))
      (is (= 83.12  (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:shipping-refund result))))
      (is (= 4.99   (round2 (:gift-wrap-refund result))))
      (is (= 0.40   (round2 (:gift-wrap-tax-refund result))))
      (is (= 0.0     (round2 (:restocking-fee result))))
      (is (= 1038.50 (round2 (:total-refund result))))
      ;; inventory restored
      (is (= 100 (get @inv "laptop"))))))

;; ══════════════════════════════════════════════════════════════════════
;;  RESTOCKING FEE TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t23-return-headphones-changed-mind-restocking
  (testing "Return headphones changed-mind from T2 order (electronics, 15% restock)"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"headphones" 1}]
                    :reason         :changed-mind}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 10.26  (round2 (:restocking-fee result))))
      (is (= 58.13  (round2 (:subtotal-refund result))))
      (is (= 5.98   (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:shipping-refund result))))
      (is (= 64.11  (round2 (:total-refund result))))
      (is (= 58     (:loyalty-clawback result)))
      (is (= 64.11  (round2 (get-in result [:payment :credit-card-refunded])))))))

(deftest t25-return-changed-mind-gift-wrapped-laptop-restocking
  (testing "Return changed-mind gift-wrapped laptop (restocking + no wrap refund)"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items [{"laptop" {:qty 1 :gift-wrap true}}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"laptop" 1}]
                    :reason         :changed-mind}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 142.50 (round2 (:restocking-fee result))))
      (is (= 807.49 (round2 (:subtotal-refund result))))
      (is (= 83.12  (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:gift-wrap-refund result))))
      (is (= 0.0    (round2 (:gift-wrap-tax-refund result))))
      (is (= 890.61 (round2 (:total-refund result)))))))

(deftest t29-return-from-three-way-payment-restocking
  (testing "Return from 3-way payment with restocking"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items                [{"laptop" 1} {"shirt" 1}]
                                :state                "CA"
                                :card                 "4111111111111111"
                                :gift-card-balance     200.0
                                :store-credit-balance  300.0})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"shirt" 1}]
                    :reason         :changed-mind}
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 2.70   (round2 (:restocking-fee result))))
      (is (= 24.29  (round2 (:subtotal-refund result))))
      (is (= 1.96   (round2 (:tax-refund result))))
      (is (= 26.25  (round2 (:total-refund result))))
      ;; Reverse waterfall: CC first (up to 513.93)
      (is (= 26.25  (round2 (get-in result [:payment :credit-card-refunded]))))
      (is (= 0.0    (round2 (get-in result [:payment :store-credit-refunded]))))
      (is (= 0.0    (round2 (get-in result [:payment :gift-card-refunded])))))))

;; ══════════════════════════════════════════════════════════════════════
;;  MULTI-CURRENCY TESTS
;; ══════════════════════════════════════════════════════════════════════

(deftest t19-multi-currency-eur-order
  (testing "EUR order: same USD calc as T2, display amounts converted at 0.92"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items    [{"laptop" 1} {"headphones" 1}]
                                :state    "CA"
                                :card     "4111111111111111"
                                :currency "EUR"})
                   (resources inv))]
      (is (= :success (:status result)))
      ;; USD amounts unchanged
      (is (= 923.38  (round2 (:discounted-subtotal result))))
      (is (= 80.79   (round2 (:tax result))))
      (is (= 0.0     (round2 (:shipping result))))
      (is (= 1004.17 (round2 (:total result))))
      ;; Display EUR amounts
      (is (= 849.51  (round2 (:display-subtotal result))))
      (is (= 74.33   (round2 (:display-tax result))))
      (is (= 0.0     (round2 (:display-shipping result))))
      (is (= 923.84  (round2 (:display-total result))))
      ;; Loyalty still based on USD
      (is (= 923     (get-in result [:loyalty :points-earned])))
      ;; Currency is stored
      (is (= "EUR"   (:currency result))))))

(deftest t28-multi-currency-return-eur
  (testing "EUR return: refunds in USD, display in EUR"
    (let [inv    (fresh-inventory)
          order  (placement/place-order
                   (make-order {:items    [{"laptop" 1} {"headphones" 1}]
                                :state    "CA"
                                :card     "4111111111111111"
                                :currency "EUR"})
                   (resources inv))
          _      (is (= :success (:status order)))
          result (returns/process-return
                   order
                   {:returned-items [{"headphones" 1}]
                    :reason         :defective}
                   (resources inv))]
      (is (= :success (:status result)))
      ;; USD refund amounts
      (is (= 68.39  (round2 (:subtotal-refund result))))
      (is (= 5.98   (round2 (:tax-refund result))))
      (is (= 74.37  (round2 (:total-refund result))))
      ;; Display EUR refund amounts
      (is (= 62.92  (round2 (:display-subtotal-refund result))))
      (is (= 5.50   (round2 (:display-tax-refund result))))
      (is (= 68.42  (round2 (:display-total-refund result)))))))

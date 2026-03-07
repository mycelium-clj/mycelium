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
  [{:keys [items coupon membership state card gift-card-balance loyalty-points]
    :or   {membership :bronze gift-card-balance 0 loyalty-points 0}}]
  {:items             items
   :coupon            coupon
   :membership        membership
   :state             state
   :card              card
   :gift-card-balance gift-card-balance
   :loyalty-points    loyalty-points
   :new-customer      false})

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
  (testing "ELEC10 + BUNDLE5 + tiered all stack"
    (let [inv    (fresh-inventory)
          result (placement/place-order
                   (make-order {:items [{"laptop" 1} {"headphones" 1} {"novel" 1}]
                                :state "CA"
                                :card  "4111111111111111"})
                   (resources inv))]
      (is (= :success (:status result)))
      (is (= 890.74 (round2 (:discounted-subtotal result))))
      (is (= 77.73  (round2 (:tax result))))
      (is (= 6.39   (round2 (:shipping result))))
      (is (= 974.86 (round2 (:total result))))
      (is (= 890    (get-in result [:loyalty :points-earned])))
      ;; Verify per-item final prices for return tracking
      (let [items (:items-detail result)]
        (is (= 812.24 (round2 (:final-price (first (filter #(= "laptop" (:product-id %)) items))))))
        (is (= 64.97  (round2 (:final-price (first (filter #(= "headphones" (:product-id %)) items))))))
        (is (= 13.53  (round2 (:final-price (first (filter #(= "novel" (:product-id %)) items))))))))))

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
  (testing "Partial return of complex order, changed mind, no shipping refund"
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
      (is (= 64.97  (round2 (:subtotal-refund result))))
      (is (= 5.68   (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:shipping-refund result))))
      (is (= 70.65  (round2 (:total-refund result))))
      (is (= 64     (:loyalty-clawback result)))
      (is (= 70.65  (round2 (get-in result [:payment :credit-card-refunded]))))
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
      (is (= 26.99  (round2 (:subtotal-refund result))))
      (is (= 1.96   (round2 (:tax-refund result))))
      (is (= 0.0    (round2 (:shipping-refund result))))
      (is (= 28.95  (round2 (:total-refund result))))
      (is (= 26     (:loyalty-clawback result)))
      ;; Refund goes to credit card first (reverse of charge order)
      (is (= 28.95  (round2 (get-in result [:payment :credit-card-refunded]))))
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
  (testing "Remove novel: BUNDLE5 lost, total actually increases"
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
      ;; Counterintuitive: removing novel INCREASES total because BUNDLE5 is lost
      (is (= 29.31   (round2 (:delta result))))
      (is (= :charge (:delta-action result)))
      (is (= 923     (:new-points result)))
      (is (= 33      (:points-delta result))))))

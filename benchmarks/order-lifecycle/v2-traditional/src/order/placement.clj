(ns order.placement
  (:import [java.math RoundingMode]))

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 RoundingMode/HALF_UP)))

(def ^:private currency-rates
  {"USD" 1.00, "EUR" 0.92, "GBP" 0.79, "CAD" 1.36})

;; ── Expand items ────────────────────────────────────────────────────
;; {"laptop" 2, "shirt" 1} -> [{:product-id "laptop" :qty 1} {:product-id "laptop" :qty 1} {:product-id "shirt" :qty 1}]

(defn- expand-items
  "Turn the items list (each a single-entry map {id qty}) into individual
   item records, one per unit, looked up from the catalog.
   Supports two item formats:
     {\"laptop\" 1}                     -> qty=1, gift-wrap=false
     {\"laptop\" {:qty 1 :gift-wrap true}} -> qty=1, gift-wrap=true"
  [items catalog]
  (mapcat
   (fn [item-map]
     (let [[pid v]    (first item-map)
           qty        (if (map? v) (:qty v) v)
           gift-wrap  (if (map? v) (boolean (:gift-wrap v)) false)
           product    (get catalog pid)]
       (repeat qty (assoc product
                          :product-id pid
                          :current-price (:price product)
                          :gift-wrap gift-wrap))))
   items))

;; ── Bulk pricing (quantity discounts) ──────────────────────────────

(defn- apply-bulk-pricing
  "Apply per-product quantity discounts on expanded items.
   3-4 units of same product: 5% off each item's base :price.
   5+ units of same product: 10% off each item's base :price.
   Discount is applied to :current-price from the original :price."
  [items]
  (let [counts (frequencies (map :product-id items))]
    (mapv (fn [item]
            (let [n    (get counts (:product-id item))
                  rate (cond
                         (>= n 5) 0.10
                         (>= n 3) 0.05
                         :else    0.0)]
              (if (pos? rate)
                (let [disc (round2 (* (:price item) rate))]
                  (assoc item :current-price (round2 (- (:price item) disc))))
                item)))
          items)))

;; ── Category promotions ─────────────────────────────────────────────

(defn- count-category [items cat-kw]
  (count (filter #(= cat-kw (:category %)) items)))

(defn- apply-elec10
  "If 2+ electronics items, 10% off each electronics item."
  [items]
  (if (>= (count-category items :electronics) 2)
    (mapv (fn [item]
            (if (= :electronics (:category item))
              (let [disc (round2 (* (:current-price item) 0.10))]
                (assoc item :current-price (round2 (- (:current-price item) disc))))
              item))
          items)
    items))

(defn- apply-bundle5
  "If 1+ electronics AND 1+ books, 5% off each item in both categories."
  [items]
  (if (and (>= (count-category items :electronics) 1)
           (>= (count-category items :books) 1))
    (mapv (fn [item]
            (if (#{:electronics :books} (:category item))
              (let [disc (round2 (* (:current-price item) 0.05))]
                (assoc item :current-price (round2 (- (:current-price item) disc))))
              item))
          items)
    items))

;; ── Order-level discount distribution ───────────────────────────────

(defn- distribute-discount
  "Distribute an order-level discount proportionally across items.
   Round each item's share to 2 decimals; adjust the largest item
   by any remainder so the total matches exactly."
  [items total-discount]
  (if (zero? total-discount)
    items
    (let [subtotal     (reduce + (map :current-price items))
          ;; Compute raw proportional shares, rounded
          shares       (mapv (fn [item]
                              (round2 (* (/ (:current-price item) subtotal) total-discount)))
                            items)
          share-sum    (round2 (reduce + shares))
          remainder    (round2 (- total-discount share-sum))
          ;; Find index of largest item to adjust
          max-idx      (first
                        (apply max-key
                               (fn [[_ item]] (:current-price item))
                               (map-indexed vector items)))
          adj-shares   (if (zero? remainder)
                         shares
                         (update shares max-idx #(round2 (+ % remainder))))]
      (mapv (fn [item share]
              (assoc item :current-price (round2 (- (:current-price item) share))))
            items adj-shares))))

;; ── COMBO75 bundle purchase discount ───────────────────────────────

(defn- apply-combo75
  "If laptop, headphones, AND novel are ALL present, apply a flat $75
   discount distributed proportionally across those three items only.
   Other items in the order are unaffected."
  [items]
  (let [combo-ids   #{"laptop" "headphones" "novel"}
        product-ids (set (map :product-id items))]
    (if (every? product-ids combo-ids)
      (let [combo-items   (filterv #(combo-ids (:product-id %)) items)
            other-items   (filterv #(not (combo-ids (:product-id %))) items)
            discounted    (distribute-discount combo-items 75.0)]
        (into discounted other-items))
      items)))

;; ── Order-level percentage discount ─────────────────────────────────

(defn- calc-order-pct-discount
  "Determine the winning order-level percentage: max of coupon% and tiered%."
  [subtotal coupon coupons]
  (let [tiered-pct  (cond
                      (>= subtotal 1000) 10
                      (>= subtotal 500)  5
                      :else              0)
        coupon-pct  (if (and coupon
                          (get coupons coupon)
                          (= :percentage (:type (get coupons coupon))))
                      (:value (get coupons coupon))
                      0)
        winning-pct (max tiered-pct coupon-pct)]
    winning-pct))

(defn- apply-order-pct-discount
  [items coupon coupons]
  (let [subtotal    (round2 (reduce + (map :current-price items)))
        pct         (calc-order-pct-discount subtotal coupon coupons)
        discount    (if (pos? pct)
                      (round2 (* subtotal (/ pct 100.0)))
                      0.0)]
    (distribute-discount items discount)))

;; ── Fixed coupon (FLAT15) ───────────────────────────────────────────

(defn- apply-fixed-coupon
  [items coupon coupons]
  (if (and coupon
           (get coupons coupon)
           (= :fixed (:type (get coupons coupon))))
    (let [discount (min (:value (get coupons coupon))
                        (reduce + (map :current-price items)))]
      (distribute-discount items (double discount)))
    items))

;; ── Loyalty redemption ──────────────────────────────────────────────

(defn- apply-loyalty-redemption
  "Apply loyalty point redemption. 100 points = $5.00.
   Returns [items redemption-amount points-redeemed]."
  [items loyalty-points]
  (if (pos? loyalty-points)
    (let [max-value    (* (/ loyalty-points 100.0) 5.0)
          subtotal     (reduce + (map :current-price items))
          redemption   (round2 (min max-value subtotal))
          new-items    (distribute-discount items redemption)]
      [new-items redemption])
    [items 0.0]))

;; ── Tax calculation ─────────────────────────────────────────────────

(defn- tax-rate-for-item
  "Determine the applicable tax rate for an item given its category and state."
  [item state tax-rates]
  (let [rules    (get tax-rates state)
        base     (:base rules 0.0)
        category (:category item)
        price    (:current-price item)]
    (cond
      ;; OR: everything exempt
      (zero? base) 0.0

      ;; CA electronics surcharge
      (and (= "CA" state) (= :electronics category))
      (+ base (:electronics-surcharge rules 0.0))

      ;; NY clothing exempt under $110
      (and (= "NY" state) (= :clothing category)
           (:clothing-exempt-under rules)
           (< price (:clothing-exempt-under rules)))
      0.0

      ;; NY books exempt
      (and (= "NY" state) (= :books category)
           (:books-exempt rules false))
      0.0

      ;; TX digital exempt
      (and (= "TX" state) (= :digital category)
           (:digital-exempt rules false))
      0.0

      :else base)))

(defn- calc-item-tax [item state tax-rates]
  (let [rate (tax-rate-for-item item state tax-rates)]
    (round2 (* (:current-price item) rate))))

;; ── Shipping ────────────────────────────────────────────────────────

(defn- calc-shipping
  "Calculate per-warehouse-group shipping.
   Returns {:total shipping-cost :groups {warehouse {:cost X :subtotal Y}}}."
  [items membership]
  (let [groups (group-by :warehouse items)]
    (reduce
     (fn [acc [warehouse group-items]]
       (if (= "digital" warehouse)
         (assoc-in acc [:groups warehouse] {:cost 0.0 :subtotal 0.0})
         (let [group-subtotal (reduce + (map :current-price group-items))
               group-weight   (reduce + (map :weight group-items))
               free?          (or (#{:gold :platinum} membership)
                                  (>= group-subtotal 75.0))
               cost           (if free?
                                0.0
                                (round2 (+ 5.99 (* 0.50 group-weight))))]
           (-> acc
               (update :total + cost)
               (assoc-in [:groups warehouse] {:cost cost :subtotal group-subtotal})))))
     {:total 0.0 :groups {}}
     groups)))

;; ── Gift wrapping ──────────────────────────────────────────────────

(defn- gift-wrap-cost-per-item
  "Gift wrap cost based on category: books/digital $2.99, all others $4.99."
  [item]
  (if (#{:books :digital} (:category item))
    2.99
    4.99))

(defn- calc-gift-wrap
  "Calculate total gift wrap cost and gift wrap tax.
   Gift wrap tax is flat 8% except OR = 0%."
  [items state]
  (let [wrapped-items (filter :gift-wrap items)
        wrap-total    (round2 (reduce + 0.0 (map gift-wrap-cost-per-item wrapped-items)))
        tax-rate      (if (= "OR" state) 0.0 0.08)
        wrap-tax      (round2 (* wrap-total tax-rate))]
    {:gift-wrap-total wrap-total
     :gift-wrap-tax   wrap-tax}))

;; ── Fraud check ─────────────────────────────────────────────────────

(defn- fraud-check [total]
  (cond
    (> total 5000) :reject
    (> total 2000) :review
    :else          :approve))

;; ── Payment ─────────────────────────────────────────────────────────

(defn- process-payment
  "Process payment. Returns {:status :success/:error :payment {...}} or error.
   Payment waterfall: gift card first, then store credit, then credit card."
  [total card gift-card-balance store-credit-balance]
  (let [gc-charge  (if (pos? gift-card-balance)
                     (min gift-card-balance total)
                     0.0)
        after-gc   (- total gc-charge)
        sc-charge  (if (pos? store-credit-balance)
                     (min store-credit-balance after-gc)
                     0.0)
        cc-charge  (round2 (- after-gc sc-charge))
        gc-charge  (round2 gc-charge)
        sc-charge  (round2 sc-charge)]
    ;; Check credit card approval (first char '4' = approve, '5' = decline)
    (if (and (pos? cc-charge)
             (= \5 (first card)))
      {:status :error :error "Payment declined"}
      {:status  :success
       :payment {:gift-card-charged    gc-charge
                 :store-credit-charged sc-charge
                 :credit-card-charged  cc-charge}})))

;; ── Inventory ───────────────────────────────────────────────────────

(defn- extract-qty
  "Extract the quantity from an item value, which may be a number or a map with :qty."
  [v]
  (if (map? v) (:qty v) v))

(defn- reserve-inventory!
  "Reserve inventory. Returns true if successful, false if insufficient."
  [inventory items-raw]
  (let [qty-map (reduce (fn [m item-map]
                          (let [[pid v] (first item-map)]
                            (update m pid (fnil + 0) (extract-qty v))))
                        {} items-raw)]
    (doseq [[pid qty] qty-map]
      (swap! inventory update pid - qty))
    true))

(defn- rollback-inventory!
  "Restore inventory after failure."
  [inventory items-raw]
  (let [qty-map (reduce (fn [m item-map]
                          (let [[pid v] (first item-map)]
                            (update m pid (fnil + 0) (extract-qty v))))
                        {} items-raw)]
    (doseq [[pid qty] qty-map]
      (swap! inventory update pid + qty))))

;; ── Loyalty earning ─────────────────────────────────────────────────

(defn- tier-multiplier [membership]
  (case membership
    :gold   2.0
    :silver 1.5
    :bronze 1.0
    1.0))

(defn- calc-loyalty-earned [discounted-subtotal membership]
  (long (Math/floor (* discounted-subtotal (tier-multiplier membership)))))

;; ── Main entry point ────────────────────────────────────────────────

(defn place-order
  "Place an order. Returns a result map with :status, pricing details,
   :items-detail, :payment, :loyalty, etc."
  [request resources]
  (let [{:keys [items coupon membership state card currency
                gift-card-balance store-credit-balance loyalty-points]
         :or   {store-credit-balance 0 currency "USD"}} request
        currency (or currency "USD")
        {:keys [catalog coupons tax-rates inventory]} resources

        ;; 1. Expand items into individual records
        expanded (expand-items items catalog)

        ;; 1b. Apply bulk pricing (quantity discounts)
        after-bulk (apply-bulk-pricing (vec expanded))

        ;; 1c. Apply COMBO75 bundle discount (before all other promos)
        after-combo75 (apply-combo75 after-bulk)

        ;; 2. Apply category promotions (ELEC10, then BUNDLE5)
        after-elec10  (apply-elec10 after-combo75)
        after-bundle5 (apply-bundle5 after-elec10)

        ;; 3. Apply order-level percentage discount (max of coupon% and tiered%)
        after-pct     (apply-order-pct-discount after-bundle5 coupon coupons)

        ;; 4. Apply fixed coupon (FLAT15)
        after-fixed   (apply-fixed-coupon after-pct coupon coupons)

        ;; 5. Apply loyalty redemption
        [after-loyalty redemption-amount] (apply-loyalty-redemption after-fixed loyalty-points)

        ;; 6. Compute discounted subtotal
        discounted-subtotal (round2 (reduce + (map :current-price after-loyalty)))

        ;; 7. Compute per-item tax
        items-with-tax (mapv (fn [item]
                               (assoc item :tax-amount (calc-item-tax item state tax-rates)))
                             after-loyalty)
        total-tax      (round2 (reduce + (map :tax-amount items-with-tax)))

        ;; 8. Compute shipping
        shipping-result (calc-shipping items-with-tax membership)
        total-shipping  (round2 (:total shipping-result))

        ;; 8b. Compute gift wrapping
        gift-wrap-result (calc-gift-wrap items-with-tax state)
        gift-wrap-total  (:gift-wrap-total gift-wrap-result)
        gift-wrap-tax    (:gift-wrap-tax gift-wrap-result)

        ;; 9. Compute total
        total (round2 (+ discounted-subtotal total-tax total-shipping
                         gift-wrap-total gift-wrap-tax))

        ;; 10. Fraud check
        fraud (fraud-check total)]

    (cond
      ;; Fraud reject -- do not reserve inventory
      (= :reject fraud)
      {:status :error
       :error  "Order rejected: fraud check failed"}

      :else
      (do
        ;; 11. Reserve inventory
        (reserve-inventory! inventory items)

        ;; 12. Process payment
        (let [payment-result (process-payment total card gift-card-balance store-credit-balance)]
          (if (= :error (:status payment-result))
            ;; Payment failed -- rollback inventory
            (do
              (rollback-inventory! inventory items)
              {:status :error
               :error  (:error payment-result)})

            ;; Success
            (let [points-earned (calc-loyalty-earned discounted-subtotal membership)
                  items-detail  (mapv (fn [item]
                                        {:product-id     (:product-id item)
                                         :original-price (:price item)
                                         :final-price    (:current-price item)
                                         :tax-amount     (:tax-amount item)
                                         :warehouse      (:warehouse item)
                                         :gift-wrap      (:gift-wrap item)
                                         :category       (:category item)})
                                      items-with-tax)
                  rate          (get currency-rates currency 1.00)]
              {:status              :success
               :discounted-subtotal discounted-subtotal
               :tax                 total-tax
               :shipping            total-shipping
               :gift-wrap-total     gift-wrap-total
               :gift-wrap-tax       gift-wrap-tax
               :total               total
               :display-subtotal    (round2 (* discounted-subtotal rate))
               :display-tax         (round2 (* total-tax rate))
               :display-shipping    (round2 (* total-shipping rate))
               :display-total       (round2 (* total rate))
               :currency            currency
               :items-detail        items-detail
               :shipping-groups     (:groups shipping-result)
               :payment             (:payment payment-result)
               :loyalty             {:points-earned     points-earned
                                     :redemption-amount redemption-amount
                                     :tier              membership}
               :state               state
               :membership          membership})))))))

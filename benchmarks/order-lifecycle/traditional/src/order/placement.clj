(ns order.placement)

;; ── Helpers ───────────────────────────────────────────────────────────

(defn round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

(defn distribute-proportionally
  "Distribute `total-amount` across items proportionally by `:current-price`.
   Rounds each share to 2 decimals, adjusts remainder on largest item."
  [items total-amount]
  (if (= 1 (count items))
    [(assoc (first items) :discount-share (round2 total-amount))]
    (let [subtotal    (reduce + 0.0 (map :current-price items))
          raw-shares  (mapv (fn [item]
                              (let [share (round2 (* total-amount (/ (:current-price item) subtotal)))]
                                (assoc item :discount-share share)))
                            items)
          share-sum   (reduce + 0.0 (map :discount-share raw-shares))
          diff        (round2 (- total-amount share-sum))]
      (if (zero? diff)
        raw-shares
        ;; Adjust the largest item by the remainder
        (let [max-idx (apply max-key #(:current-price (nth raw-shares %)) (range (count raw-shares)))]
          (update-in raw-shares [max-idx :discount-share] + diff))))))

;; ── Step 1: Validate & normalize cart ─────────────────────────────────

(defn validate-cart [items catalog]
  (if (empty? items)
    {:error "Cart is empty"}
    (let [line-items (for [item-map items
                           [pid qty] item-map]
                       (if-let [product (get catalog pid)]
                         {:product-id     pid
                          :name           (:name product)
                          :category       (:category product)
                          :original-price (:price product)
                          :current-price  (:price product)
                          :weight         (:weight product)
                          :warehouse      (:warehouse product)
                          :quantity       qty}
                         {:error (str "Unknown product: " pid)}))]
      (if-let [err (first (filter :error line-items))]
        err
        ;; Expand quantities into individual item records for per-item tracking
        (let [expanded (vec (mapcat (fn [li]
                                      (repeat (:quantity li)
                                              (assoc li :quantity 1)))
                                    line-items))]
          {:items expanded})))))

;; ── Step 2: Apply ELEC10 ─────────────────────────────────────────────

(defn apply-elec10 [items]
  (let [electronics (filter #(= :electronics (:category %)) items)
        elec-count  (count electronics)]
    (if (>= elec-count 2)
      (mapv (fn [item]
              (if (= :electronics (:category item))
                (let [disc (round2 (* (:current-price item) 0.10))]
                  (-> item
                      (update :current-price - disc)
                      (update :current-price round2)))
                item))
            items)
      items)))

;; ── Step 3: Apply BUNDLE5 ────────────────────────────────────────────

(defn apply-bundle5 [items]
  (let [has-electronics (some #(= :electronics (:category %)) items)
        has-books       (some #(= :books (:category %)) items)]
    (if (and has-electronics has-books)
      (mapv (fn [item]
              (if (#{:electronics :books} (:category item))
                (let [disc (round2 (* (:current-price item) 0.05))]
                  (-> item
                      (update :current-price - disc)
                      (update :current-price round2)))
                item))
            items)
      items)))

;; ── Step 4: Apply order-level percentage discount ─────────────────────

(defn tiered-pct [subtotal]
  (cond
    (>= subtotal 1000) 10
    (>= subtotal 500)  5
    :else              0))

(defn apply-order-level-discount [items coupon coupons]
  (let [subtotal   (reduce + 0.0 (map :current-price items))
        tier-pct   (tiered-pct subtotal)
        coupon-data (when coupon (get coupons coupon))
        coupon-ok?  (and coupon-data
                         (= :percentage (:type coupon-data))
                         (or (nil? (:min-order coupon-data))
                             (>= subtotal (:min-order coupon-data))))
        coupon-pct  (if coupon-ok? (:value coupon-data) 0)
        best-pct    (max tier-pct coupon-pct)]
    (if (pos? best-pct)
      (let [total-disc (round2 (* subtotal (/ best-pct 100.0)))
            distributed (distribute-proportionally items total-disc)]
        (mapv (fn [item]
                (-> item
                    (update :current-price - (:discount-share item))
                    (update :current-price round2)
                    (dissoc :discount-share)))
              distributed))
      items)))

;; ── Step 5: Apply fixed coupon ────────────────────────────────────────

(defn apply-fixed-coupon [items coupon coupons]
  (let [coupon-data (when coupon (get coupons coupon))]
    (if (and coupon-data (= :fixed (:type coupon-data)))
      (let [subtotal  (reduce + 0.0 (map :current-price items))
            disc-amt  (min (double (:value coupon-data)) subtotal)
            distributed (distribute-proportionally items disc-amt)]
        (mapv (fn [item]
                (-> item
                    (update :current-price - (:discount-share item))
                    (update :current-price round2)
                    (dissoc :discount-share)))
              distributed))
      items)))

;; ── Step 6: Apply loyalty redemption ──────────────────────────────────

(defn apply-loyalty-redemption [items loyalty-points]
  (if (and loyalty-points (pos? loyalty-points))
    (let [subtotal     (reduce + 0.0 (map :current-price items))
          max-redeem   (* (quot loyalty-points 100) 5.0)
          actual       (min max-redeem subtotal)
          distributed  (distribute-proportionally items actual)]
      {:items          (mapv (fn [item]
                               (-> item
                                   (update :current-price - (:discount-share item))
                                   (update :current-price round2)
                                   (dissoc :discount-share)))
                             distributed)
       :redeemed-points (* (long (/ actual 5.0)) 100)
       :redemption-amt  actual})
    {:items items :redeemed-points 0 :redemption-amt 0.0}))

;; ── Step 7: Calculate per-item tax ────────────────────────────────────

(defn item-tax-rate [item state tax-rates]
  (let [rules    (get tax-rates state)
        base     (or (:base rules) 0.0)
        category (:category item)
        price    (:current-price item)]
    (cond
      ;; Oregon: 0% on everything
      (zero? base) 0.0

      ;; Electronics surcharge (CA)
      (and (= :electronics category) (:electronics-surcharge rules))
      (+ base (:electronics-surcharge rules))

      ;; Clothing exempt (NY, under threshold)
      (and (= :clothing category)
           (:clothing-exempt-under rules)
           (< price (:clothing-exempt-under rules)))
      0.0

      ;; Books exempt (NY)
      (and (= :books category) (:books-exempt rules))
      0.0

      ;; Digital exempt (TX)
      (and (= :digital category) (:digital-exempt rules))
      0.0

      :else base)))

(defn calc-per-item-tax [items state tax-rates]
  (mapv (fn [item]
          (let [rate (item-tax-rate item state tax-rates)
                tax  (-> (bigdec (:current-price item))
                         (.multiply (bigdec rate))
                         (.setScale 2 java.math.RoundingMode/HALF_UP)
                         .doubleValue)]
            (assoc item :tax-amount tax)))
        items))

;; ── Step 8: Calculate shipping ────────────────────────────────────────

(defn calc-shipping [items membership]
  (let [groups    (group-by :warehouse items)
        free-all? (#{:gold :platinum} membership)]
    (reduce-kv
      (fn [acc warehouse wh-items]
        (if (= "digital" warehouse)
          (assoc-in acc [:warehouse-shipping warehouse] 0.0)
          (let [wh-subtotal (reduce + 0.0 (map :current-price wh-items))
                wh-weight   (reduce + 0.0 (map :weight wh-items))
                free?       (or free-all? (>= wh-subtotal 75.0))
                cost        (if free? 0.0
                              (round2 (+ 5.99 (* 0.50 wh-weight))))]
            (-> acc
                (assoc-in [:warehouse-shipping warehouse] cost)
                (assoc-in [:warehouse-subtotals warehouse] wh-subtotal)
                (update :total-shipping + cost)))))
      {:warehouse-shipping {} :warehouse-subtotals {} :total-shipping 0.0}
      groups)))

;; ── Step 9: Fraud check ──────────────────────────────────────────────

(defn fraud-check [total]
  (cond
    (> total 5000) :reject
    (> total 2000) :review
    :else          :approve))

;; ── Step 10: Reserve inventory ────────────────────────────────────────

(defn reserve-inventory! [items inventory]
  (let [by-product (frequencies (map :product-id items))]
    (doseq [[pid qty] by-product]
      (swap! inventory update pid - qty))))

(defn rollback-inventory! [items inventory]
  (let [by-product (frequencies (map :product-id items))]
    (doseq [[pid qty] by-product]
      (swap! inventory update pid + qty))))

;; ── Step 11: Process payment ──────────────────────────────────────────

(defn process-payment [total card gift-card-balance]
  (let [gc-charge (if (and gift-card-balance (pos? gift-card-balance))
                    (min gift-card-balance total)
                    0.0)
        cc-charge (round2 (- total gc-charge))]
    (if (= \4 (first card))
      {:status             :approved
       :gift-card-charged  (round2 gc-charge)
       :credit-card-charged cc-charge
       :transaction-id     (str "txn-" (random-uuid))}
      {:status :declined})))

;; ── Step 12: Earn loyalty points ──────────────────────────────────────

(defn loyalty-multiplier [membership]
  (case membership
    :gold     2.0
    :silver   1.5
    :bronze   1.0
    1.0))

(defn earn-loyalty [discounted-subtotal membership redemption-amt]
  (let [earn-base  discounted-subtotal
        multiplier (loyalty-multiplier membership)]
    (long (Math/floor (* earn-base multiplier)))))

;; ── Main pipeline ─────────────────────────────────────────────────────

(defn place-order [request resources]
  (let [{:keys [items coupon membership state card
                gift-card-balance loyalty-points new-customer]} request
        {:keys [catalog coupons tax-rates inventory]} resources

        ;; Step 1: Validate cart
        cart-result (validate-cart items catalog)]

    (if (:error cart-result)
      {:status :error :error (:error cart-result)}

      (let [raw-items (:items cart-result)

            ;; Steps 2-3: Category promotions
            after-elec10  (apply-elec10 raw-items)
            after-bundle5 (apply-bundle5 after-elec10)

            ;; Step 4: Order-level percentage discount
            after-order-disc (apply-order-level-discount after-bundle5 coupon coupons)

            ;; Step 5: Fixed coupon
            after-fixed (apply-fixed-coupon after-order-disc coupon coupons)

            ;; Step 6: Loyalty redemption
            {:keys [items redeemed-points redemption-amt]}
            (apply-loyalty-redemption after-fixed loyalty-points)

            ;; Discounted subtotal
            discounted-subtotal (round2 (reduce + 0.0 (map :current-price items)))

            ;; Step 7: Per-item tax
            items-with-tax (calc-per-item-tax items state tax-rates)
            total-tax      (round2 (reduce + 0.0 (map :tax-amount items-with-tax)))

            ;; Step 8: Shipping
            shipping-result (calc-shipping items-with-tax membership)
            total-shipping  (round2 (:total-shipping shipping-result))

            ;; Total
            total (round2 (+ discounted-subtotal total-tax total-shipping))

            ;; Step 9: Fraud check
            fraud (fraud-check total)]

        (case fraud
          :reject
          {:status :error :error "Order rejected: fraud check failed"}

          :review
          {:status :error :error "Order flagged for manual review"}

          ;; :approve
          (do
            ;; Step 10: Reserve inventory
            (reserve-inventory! items-with-tax inventory)

            ;; Step 11: Payment
            (let [payment (process-payment total card gift-card-balance)]
              (if (= :declined (:status payment))
                (do
                  (rollback-inventory! items-with-tax inventory)
                  {:status :error :error "Payment declined"})

                ;; Step 12: Loyalty & compile result
                (let [points-earned (earn-loyalty discounted-subtotal membership redemption-amt)]
                  {:status              :success
                   :items-detail        (mapv (fn [item]
                                                {:product-id     (:product-id item)
                                                 :original-price (:original-price item)
                                                 :final-price    (:current-price item)
                                                 :tax-amount     (:tax-amount item)
                                                 :warehouse      (:warehouse item)})
                                              items-with-tax)
                   :discounted-subtotal discounted-subtotal
                   :tax                total-tax
                   :shipping           total-shipping
                   :total              total
                   :warehouse-shipping (:warehouse-shipping shipping-result)
                   :warehouse-subtotals (:warehouse-subtotals shipping-result)
                   :payment            (dissoc payment :status)
                   :loyalty            {:points-earned    points-earned
                                        :points-redeemed  redeemed-points
                                        :redemption-amount redemption-amt}
                   :membership         membership
                   :state              state})))))))))

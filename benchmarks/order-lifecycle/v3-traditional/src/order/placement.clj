(ns order.placement
  (:import [java.math RoundingMode]))

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 RoundingMode/HALF_UP)))

(def ^:private currency-rates
  {"USD" 1.00, "EUR" 0.92, "GBP" 0.79, "CAD" 1.36})

(def ^:private warranty-cost
  {:electronics 49.99 :clothing 9.99 :bundle 59.99})

(def ^:private county-tax-rules
  {"CA" {"Los Angeles"    {:surcharge 0.0225 :overrides {}}
         "San Francisco"  {:surcharge 0.0125 :overrides {:digital :exempt}}}
   "NY" {"New York City"  {:surcharge 0.045 :overrides {}}
         "Buffalo"        {:surcharge 0.04 :overrides {:clothing :not-exempt}}}
   "OR" {"Portland"       {:surcharge 0.0 :overrides {}}}
   "TX" {"Houston"        {:surcharge 0.02 :overrides {}}
         "Austin"         {:surcharge 0.02 :overrides {:digital :not-exempt}}}})

;; ── Expand items ────────────────────────────────────────────────────

(defn- expand-items
  "Turn items list into individual item records from catalog.
   Supports formats: {id qty}, {id {:qty N :gift-wrap bool :subscription bool :warranty bool}}"
  [items catalog]
  (mapcat
   (fn [item-map]
     (let [[pid v]        (first item-map)
           qty            (if (map? v) (:qty v) v)
           gift-wrap      (if (map? v) (boolean (:gift-wrap v)) false)
           subscription   (if (map? v) (boolean (:subscription v)) false)
           warranty       (if (map? v) (boolean (:warranty v)) false)
           product        (get catalog pid)]
       (repeat qty (assoc product
                          :product-id pid
                          :current-price (:price product)
                          :gift-wrap gift-wrap
                          :subscription subscription
                          :warranty warranty))))
   items))

;; ── Subscription pricing ────────────────────────────────────────────

(defn- apply-subscription-pricing
  "15% off base price for subscription items. Applied FIRST, before bulk."
  [items]
  (mapv (fn [item]
          (if (:subscription item)
            (let [disc (round2 (* (:price item) 0.15))]
              (assoc item :current-price (round2 (- (:price item) disc))))
            item))
        items))

;; ── Bulk pricing ────────────────────────────────────────────────────

(defn- apply-bulk-pricing
  "3-4 units: 5% off, 5+ units: 10% off. Applied to :current-price."
  [items]
  (let [counts (frequencies (map :product-id items))]
    (mapv (fn [item]
            (let [n    (get counts (:product-id item))
                  rate (cond
                         (>= n 5) 0.10
                         (>= n 3) 0.05
                         :else    0.0)]
              (if (pos? rate)
                (let [disc (round2 (* (:current-price item) rate))]
                  (assoc item :current-price (round2 (- (:current-price item) disc))))
                item)))
          items)))

;; ── Category promotions ─────────────────────────────────────────────

(defn- count-category [items cat-kw]
  (count (filter #(= cat-kw (:category %)) items)))

(defn- apply-elec10
  "If 2+ electronics items (excluding bundles), 10% off each electronics item."
  [items]
  (let [elec-count (count (filter #(= :electronics (:category %)) items))]
    (if (>= elec-count 2)
      (mapv (fn [item]
              (if (= :electronics (:category item))
                (let [disc (round2 (* (:current-price item) 0.10))]
                  (assoc item :current-price (round2 (- (:current-price item) disc))))
                item))
            items)
      items)))

(defn- apply-bundle5
  "If 1+ electronics AND 1+ books (excluding bundles from both counts), 5% off each."
  [items]
  (let [elec-count (count (filter #(= :electronics (:category %)) items))
        book-count (count (filter #(= :books (:category %)) items))]
    (if (and (>= elec-count 1) (>= book-count 1))
      (mapv (fn [item]
              (if (#{:electronics :books} (:category item))
                (let [disc (round2 (* (:current-price item) 0.05))]
                  (assoc item :current-price (round2 (- (:current-price item) disc))))
                item))
            items)
      items)))

;; ── Order-level discount distribution ───────────────────────────────

(defn- distribute-discount
  [items total-discount]
  (if (zero? total-discount)
    items
    (let [subtotal     (reduce + (map :current-price items))
          shares       (mapv (fn [item]
                              (round2 (* (/ (:current-price item) subtotal) total-discount)))
                            items)
          share-sum    (round2 (reduce + shares))
          remainder    (round2 (- total-discount share-sum))
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

;; ── COMBO75 ─────────────────────────────────────────────────────────

(defn- apply-combo75
  "If laptop, headphones, AND novel ALL present (non-subscription), flat $75 off."
  [items]
  (let [combo-ids   #{"laptop" "headphones" "novel"}
        ;; Subscription items are excluded from COMBO75 eligibility
        eligible    (filter #(and (combo-ids (:product-id %))
                                  (not (:subscription %)))
                            items)
        product-ids (set (map :product-id eligible))]
    (if (every? product-ids combo-ids)
      (let [combo-items   (filterv #(combo-ids (:product-id %)) items)
            other-items   (filterv #(not (combo-ids (:product-id %))) items)
            discounted    (distribute-discount combo-items 75.0)]
        (into discounted other-items))
      items)))

;; ── Order-level percentage discount ─────────────────────────────────

(defn- calc-order-pct-discount
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
  [items loyalty-points]
  (if (pos? loyalty-points)
    (let [max-value    (* (/ loyalty-points 100.0) 5.0)
          subtotal     (reduce + (map :current-price items))
          redemption   (round2 (min max-value subtotal))
          new-items    (distribute-discount items redemption)]
      [new-items redemption])
    [items 0.0]))

;; ── Tax calculation (with county overrides) ─────────────────────────

(defn- tax-rate-for-item
  [item state tax-rates county]
  (let [rules      (get tax-rates state)
        base       (:base rules 0.0)
        category   (:category item)
        price      (:current-price item)
        county-info (when county
                      (get-in county-tax-rules [state county]))
        surcharge  (or (:surcharge county-info) 0.0)
        overrides  (or (:overrides county-info) {})]
    (cond
      ;; OR: everything exempt
      (zero? base) 0.0

      ;; Bundle: use highest component category rate (electronics for gaming bundle)
      (= :bundle category)
      (+ base (:electronics-surcharge rules 0.0) surcharge)

      ;; CA electronics surcharge
      (and (= "CA" state) (= :electronics category))
      (+ base (:electronics-surcharge rules 0.0) surcharge)

      ;; NY clothing exempt under $110
      (and (= "NY" state) (= :clothing category)
           (:clothing-exempt-under rules)
           (< price (:clothing-exempt-under rules)))
      (if (= :not-exempt (get overrides :clothing))
        (+ base surcharge)
        0.0)

      ;; NY books exempt
      (and (= "NY" state) (= :books category)
           (:books-exempt rules false))
      0.0

      ;; TX digital exempt
      (and (= "TX" state) (= :digital category)
           (:digital-exempt rules false))
      (if (= :not-exempt (get overrides :digital))
        (+ base surcharge)
        0.0)

      ;; CA/SF digital exempt at county level
      (and county (= :exempt (get overrides category)))
      base  ;; County surcharge waived, state rate still applies

      :else (+ base surcharge))))

(defn- calc-item-tax [item state tax-rates county]
  (let [rate (tax-rate-for-item item state tax-rates county)]
    (round2 (* (:current-price item) rate))))

;; ── Tiered Shipping ─────────────────────────────────────────────────

(defn- weight-tier-cost [weight]
  (cond
    (<= weight 2.0)  5.99
    (<= weight 10.0) 8.99
    (<= weight 20.0) 12.99
    :else            (+ 15.99 (* 0.25 (- weight 20.0)))))

(defn- compute-shipping-for-items
  "Calculate shipping for given items with tiered rates + surcharges.
   Returns {:total X :groups {warehouse {:cost Y :base-cost B :hazmat-cost H :oversized-cost O :subtotal S}}}"
  [items membership]
  (let [groups (group-by :warehouse items)]
    (reduce
     (fn [acc [warehouse group-items]]
       (if (= "digital" warehouse)
         (assoc-in acc [:groups warehouse] {:cost 0.0 :base-cost 0.0
                                            :hazmat-cost 0.0 :oversized-cost 0.0
                                            :subtotal 0.0})
         (let [group-subtotal (reduce + (map :current-price group-items))
               group-weight   (reduce + (map :weight group-items))

               ;; Base tier cost
               base-cost (weight-tier-cost group-weight)

               ;; Free shipping rules for base
               free-base? (or (= :platinum membership)
                              (= :gold membership)
                              (>= group-subtotal 75.0))
               effective-base (if free-base? 0.0 base-cost)

               ;; Hazmat: $3 per electronics item (bundles count as 1)
               hazmat-count (count (filter #(#{:electronics :bundle} (:category %)) group-items))
               hazmat-cost (* 3.0 hazmat-count)

               ;; Oversized: $5 if any item > 4.0 lb (per group, not per item)
               oversized? (some #(> (:weight %) 4.0) group-items)
               oversized-cost (if oversized? 5.0 0.0)

               ;; Platinum waives everything
               [eff-hazmat eff-oversized] (if (= :platinum membership)
                                            [0.0 0.0]
                                            [hazmat-cost oversized-cost])

               total-cost (round2 (+ effective-base eff-hazmat eff-oversized))]
           (-> acc
               (update :total + total-cost)
               (assoc-in [:groups warehouse]
                         {:cost total-cost
                          :base-cost effective-base
                          :hazmat-cost eff-hazmat
                          :oversized-cost eff-oversized
                          :subtotal group-subtotal})))))
     {:total 0.0 :groups {}}
     groups)))

;; ── Gift wrapping ───────────────────────────────────────────────────

(defn- gift-wrap-cost-per-item [item]
  (if (#{:books :digital} (:category item))
    2.99
    4.99))

(defn- calc-gift-wrap [items state]
  (let [wrapped-items (filter :gift-wrap items)
        wrap-total    (round2 (reduce + 0.0 (map gift-wrap-cost-per-item wrapped-items)))
        tax-rate      (if (= "OR" state) 0.0 0.08)
        wrap-tax      (round2 (* wrap-total tax-rate))]
    {:gift-wrap-total wrap-total
     :gift-wrap-tax   wrap-tax}))

;; ── Warranty ────────────────────────────────────────────────────────

(defn- warranty-cost-per-item [item]
  (get warranty-cost (:category item)))

(defn- calc-warranty [items state]
  (let [warranted (filter (fn [item]
                            (and (:warranty item)
                                 (warranty-cost-per-item item)))
                          items)
        total     (round2 (reduce + 0.0 (map warranty-cost-per-item warranted)))
        tax-rate  (if (= "OR" state) 0.0 0.08)
        tax       (round2 (* total tax-rate))]
    {:warranty-total total
     :warranty-tax   tax}))

;; ── Fraud check ─────────────────────────────────────────────────────

(defn- fraud-check [total]
  (cond
    (> total 5000) :reject
    (> total 2000) :review
    :else          :approve))

;; ── Payment ─────────────────────────────────────────────────────────

(defn- process-payment
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
    (if (and (pos? cc-charge)
             (= \5 (first card)))
      {:status :error :error "Payment declined"}
      {:status  :success
       :payment {:gift-card-charged    gc-charge
                 :store-credit-charged sc-charge
                 :credit-card-charged  cc-charge}})))

;; ── Inventory ───────────────────────────────────────────────────────

(defn- extract-qty [v]
  (if (map? v) (:qty v) v))

(defn- extract-pid-qty-pairs
  "Extract product-id/qty pairs from items-raw, expanding bundles to components."
  [items-raw catalog]
  (reduce (fn [m item-map]
            (let [[pid v] (first item-map)
                  qty (extract-qty v)
                  product (get catalog pid)]
              (if (= :bundle (:category product))
                ;; Bundle: decrement component inventory
                (reduce (fn [mm [comp-pid comp-qty]]
                          (update mm comp-pid (fnil + 0) (* comp-qty qty)))
                        m
                        (:components product))
                (update m pid (fnil + 0) qty))))
          {}
          items-raw))

(defn- reserve-inventory!
  "Reserve inventory. Returns fulfillment info for partial fulfillment."
  [inventory items-raw items-with-tax catalog]
  (let [qty-map (extract-pid-qty-pairs items-raw catalog)
        ;; Check availability and split into fulfilled/backordered
        fulfilled-pids (atom {})
        backordered-pids (atom {})
        partial? (atom false)]
    ;; Determine what can be fulfilled
    (doseq [[pid qty] qty-map]
      (let [available (get @inventory pid 0)
            can-fill  (min available qty)
            backorder (- qty can-fill)]
        (when (pos? can-fill)
          (swap! fulfilled-pids assoc pid can-fill))
        (when (pos? backorder)
          (swap! backordered-pids assoc pid backorder)
          (reset! partial? true))))
    ;; Decrement inventory
    (doseq [[pid qty] @fulfilled-pids]
      (swap! inventory update pid - qty))
    ;; Build fulfillment result
    (if @partial?
      (let [;; Map items-with-tax to fulfilled/backordered
            ;; For bundles, check component availability
            fulfilled-items
            (let [counts (atom @fulfilled-pids)]
              (filterv (fn [item]
                         (let [pid (:product-id item)
                               product (get catalog pid)]
                           (if (= :bundle (:category product))
                             ;; Bundle: check if all components are fulfilled
                             (let [all-fulfilled
                                   (every? (fn [[comp-pid comp-qty]]
                                             (>= (get @counts comp-pid 0) comp-qty))
                                           (:components product))]
                               (when all-fulfilled
                                 (doseq [[comp-pid comp-qty] (:components product)]
                                   (swap! counts update comp-pid - comp-qty)))
                               all-fulfilled)
                             ;; Regular item
                             (let [remaining (get @counts pid 0)]
                               (when (pos? remaining)
                                 (swap! counts update pid dec)
                                 true)))))
                       items-with-tax))
            backordered-items
            (let [counts (atom @backordered-pids)]
              (filterv (fn [item]
                         (let [pid (:product-id item)
                               remaining (get @counts pid 0)]
                           (when (pos? remaining)
                             (swap! counts update pid dec)
                             true)))
                       items-with-tax))]
        {:status :partial
         :fulfilled-items fulfilled-items
         :backordered-items backordered-items})
      {:status :full})))

(defn- rollback-inventory!
  [inventory items-raw catalog]
  (let [qty-map (extract-pid-qty-pairs items-raw catalog)]
    (doseq [[pid qty] qty-map]
      (swap! inventory update pid + qty))))

;; ── Loyalty earning ─────────────────────────────────────────────────

(defn- tier-multiplier [membership]
  (case membership
    :platinum 2.0
    :gold   2.0
    :silver 1.5
    :bronze 1.0
    1.0))

(defn- calc-loyalty-earned [discounted-subtotal membership]
  (long (Math/floor (* discounted-subtotal (tier-multiplier membership)))))

;; ── Auto-upgrade tier ───────────────────────────────────────────────

(defn- next-tier [membership]
  (case membership
    :bronze :silver
    :silver :gold
    :gold   nil
    :platinum nil
    nil))

(defn- tier-threshold [tier]
  (case tier
    :silver 500.0
    :gold   2000.0
    nil))

(defn- auto-upgrade-tier
  "Check if lifetime-spend + discounted-subtotal crosses next tier threshold.
   Returns [new-membership tier-upgraded?]"
  [membership lifetime-spend discounted-subtotal]
  (let [new-tier (next-tier membership)
        threshold (when new-tier (tier-threshold new-tier))]
    (if (and threshold (>= (+ lifetime-spend discounted-subtotal) threshold))
      [new-tier true]
      [membership false])))

;; ── Main entry point ────────────────────────────────────────────────

(defn place-order
  [request resources]
  (let [{:keys [items coupon membership state county card currency
                gift-card-balance store-credit-balance loyalty-points
                lifetime-spend]
         :or   {store-credit-balance 0 currency "USD" lifetime-spend 0}} request
        currency (or currency "USD")
        membership (or membership :bronze)
        lifetime-spend (or lifetime-spend 0)
        {:keys [catalog coupons tax-rates inventory]} resources

        ;; 1. Expand items
        expanded (expand-items items catalog)

        ;; 2. Subscription pricing (15% off, before bulk)
        after-sub (apply-subscription-pricing (vec expanded))

        ;; 3. Bulk pricing
        after-bulk (apply-bulk-pricing after-sub)

        ;; 4. COMBO75 (subscription items excluded)
        after-combo75 (apply-combo75 after-bulk)

        ;; 5. Category promotions
        after-elec10  (apply-elec10 after-combo75)
        after-bundle5 (apply-bundle5 after-elec10)

        ;; 6. Order-level percentage discount
        after-pct (apply-order-pct-discount after-bundle5 coupon coupons)

        ;; 7. Fixed coupon
        after-fixed (apply-fixed-coupon after-pct coupon coupons)

        ;; 8. Loyalty redemption
        [after-loyalty redemption-amount] (apply-loyalty-redemption after-fixed loyalty-points)

        ;; 9. Discounted subtotal
        discounted-subtotal (round2 (reduce + (map :current-price after-loyalty)))

        ;; 10. Per-item tax (with county)
        items-with-tax (mapv (fn [item]
                               (assoc item :tax-amount (calc-item-tax item state tax-rates county)))
                             after-loyalty)
        total-tax (round2 (reduce + (map :tax-amount items-with-tax)))

        ;; 11. Auto-upgrade check
        [effective-membership tier-upgraded] (auto-upgrade-tier membership lifetime-spend discounted-subtotal)

        ;; 12. Shipping (with possibly upgraded tier)
        shipping-result (compute-shipping-for-items items-with-tax effective-membership)
        total-shipping  (round2 (:total shipping-result))

        ;; 13. Gift wrap
        gift-wrap-result (calc-gift-wrap items-with-tax state)
        gift-wrap-total  (:gift-wrap-total gift-wrap-result)
        gift-wrap-tax    (:gift-wrap-tax gift-wrap-result)

        ;; 14. Warranty
        warranty-result (calc-warranty items-with-tax state)
        warranty-total  (:warranty-total warranty-result)
        warranty-tax    (:warranty-tax warranty-result)

        ;; 15. Total
        total (round2 (+ discounted-subtotal total-tax total-shipping
                         gift-wrap-total gift-wrap-tax
                         warranty-total warranty-tax))

        ;; 16. Loyalty points (with possibly upgraded tier)
        points-earned (calc-loyalty-earned discounted-subtotal effective-membership)

        ;; 17. Fraud check
        fraud (fraud-check total)]

    (cond
      (= :reject fraud)
      {:status :error
       :error  "Order rejected: fraud check failed"}

      :else
      (do
        ;; 18. Reserve inventory (with partial fulfillment)
        (let [fulfillment (reserve-inventory! inventory items items-with-tax catalog)]

          ;; 19. Compute shipping for fulfilled items only (if partial)
          (let [actual-shipping-result
                (if (= :partial (:status fulfillment))
                  (compute-shipping-for-items (:fulfilled-items fulfillment) effective-membership)
                  shipping-result)
                actual-shipping (round2 (:total actual-shipping-result))
                ;; Recompute total with actual shipping
                actual-total (if (= :partial (:status fulfillment))
                               (round2 (+ discounted-subtotal total-tax actual-shipping
                                          gift-wrap-total gift-wrap-tax
                                          warranty-total warranty-tax))
                               total)

                ;; 20. Process payment
                payment-result (process-payment actual-total card
                                               (double (or gift-card-balance 0))
                                               (double (or store-credit-balance 0)))]

            (if (= :error (:status payment-result))
              (do
                (rollback-inventory! inventory items catalog)
                {:status :error
                 :error  (:error payment-result)})

              ;; Build fulfillment details
              (let [fulfillment-detail
                    (when (= :partial (:status fulfillment))
                      (let [fulfilled-items (:fulfilled-items fulfillment)
                            backordered-items (:backordered-items fulfillment)
                            fulfilled-sub (round2 (reduce + 0.0 (map :current-price fulfilled-items)))
                            backordered-sub (round2 (reduce + 0.0 (map :current-price backordered-items)))
                            fulfilled-tax (round2 (reduce + 0.0 (map :tax-amount fulfilled-items)))
                            backordered-tax (round2 (reduce + 0.0 (map :tax-amount backordered-items)))
                            fulfilled-charge (round2 (+ fulfilled-sub fulfilled-tax actual-shipping
                                                        gift-wrap-total gift-wrap-tax
                                                        warranty-total warranty-tax))
                            backorder-hold (round2 (+ backordered-sub backordered-tax))]
                        {:status :partial
                         :fulfilled-items (mapv (fn [item]
                                                 {:product-id (:product-id item)
                                                  :original-price (:price item)
                                                  :final-price (:current-price item)
                                                  :tax-amount (:tax-amount item)
                                                  :warehouse (:warehouse item)
                                                  :gift-wrap (:gift-wrap item)
                                                  :warranty (:warranty item)
                                                  :subscription (:subscription item)
                                                  :category (:category item)
                                                  :components (:components item)})
                                               fulfilled-items)
                         :backordered-items (mapv (fn [item]
                                                   {:product-id (:product-id item)
                                                    :original-price (:price item)
                                                    :final-price (:current-price item)
                                                    :tax-amount (:tax-amount item)
                                                    :warehouse (:warehouse item)
                                                    :gift-wrap (:gift-wrap item)
                                                    :warranty (:warranty item)
                                                    :subscription (:subscription item)
                                                    :category (:category item)
                                                    :components (:components item)})
                                                 backordered-items)
                         :fulfilled-subtotal fulfilled-sub
                         :backordered-subtotal backordered-sub
                         :fulfilled-charge fulfilled-charge
                         :backorder-hold backorder-hold}))

                    items-detail (mapv (fn [item]
                                        {:product-id     (:product-id item)
                                         :original-price (:price item)
                                         :final-price    (:current-price item)
                                         :tax-amount     (:tax-amount item)
                                         :warehouse      (:warehouse item)
                                         :gift-wrap      (:gift-wrap item)
                                         :warranty       (:warranty item)
                                         :subscription   (:subscription item)
                                         :category       (:category item)
                                         :components     (:components item)})
                                      items-with-tax)

                    has-subscription (boolean (some :subscription items-with-tax))

                    rate (get currency-rates currency 1.00)]
                {:status              :success
                 :discounted-subtotal discounted-subtotal
                 :tax                 total-tax
                 :shipping            actual-shipping
                 :gift-wrap-total     gift-wrap-total
                 :gift-wrap-tax       gift-wrap-tax
                 :warranty-total      warranty-total
                 :warranty-tax        warranty-tax
                 :total               actual-total
                 :display-subtotal    (round2 (* discounted-subtotal rate))
                 :display-tax         (round2 (* total-tax rate))
                 :display-shipping    (round2 (* actual-shipping rate))
                 :display-total       (round2 (* actual-total rate))
                 :currency            currency
                 :items-detail        items-detail
                 :shipping-groups     (:groups actual-shipping-result)
                 :payment             (:payment payment-result)
                 :loyalty             {:points-earned     points-earned
                                       :redemption-amount redemption-amount
                                       :tier              effective-membership}
                 :state               state
                 :county              county
                 :membership          effective-membership
                 :tier-upgraded       tier-upgraded
                 :has-subscription    has-subscription
                 :fulfillment         (or fulfillment-detail
                                         {:status :full})}))))))))

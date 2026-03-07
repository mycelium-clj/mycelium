(ns checkout.core)

;; ── Helpers ───────────────────────────────────────────────────────────

(defn round2
  "Round to 2 decimal places, half-up."
  [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

;; ── Step 1: Validate Cart ─────────────────────────────────────────────

(defn validate-cart
  "Validates cart items against the catalog. Returns normalized line items
   or an error map."
  [items catalog]
  (if (empty? items)
    {:error "Cart is empty"}
    (let [line-items (for [item-map items
                           [product-id qty] item-map]
                       (if-let [product (get catalog product-id)]
                         {:product-id product-id
                          :name       (:name product)
                          :price      (:price product)
                          :weight     (:weight product)
                          :quantity   qty}
                         {:error (str "Unknown product: " product-id)}))]
      (if-let [err (first (filter :error line-items))]
        err
        {:line-items (vec line-items)
         :subtotal   (reduce + 0 (map #(* (:price %) (:quantity %)) line-items))
         :total-weight (reduce + 0 (map #(* (:weight %) (:quantity %)) line-items))}))))

;; ── Step 2: Apply Discounts ───────────────────────────────────────────

(defn membership-discount-pct
  "Returns the percentage discount for a membership tier."
  [membership]
  (case membership
    :gold     5
    :platinum 10
    0))

(defn tiered-discount-pct
  "Returns the tiered discount percentage based on subtotal."
  [subtotal]
  (cond
    (>= subtotal 1000) 15
    (>= subtotal 500)  10
    (>= subtotal 200)  5
    :else              0))

(defn apply-discounts
  "Apply percentage and fixed discounts. Returns updated data with
   :discounted-subtotal, :percentage-discount-applied, :fixed-discounts-applied."
  [{:keys [subtotal line-items total-weight] :as cart-data}
   coupon membership coupons]
  (let [;; Collect all percentage discounts
        membership-pct (membership-discount-pct membership)
        tiered-pct     (tiered-discount-pct subtotal)

        ;; Coupon handling
        coupon-data    (when coupon (get coupons coupon))
        coupon-valid?  (and coupon-data
                            (or (nil? (:min-order coupon-data))
                                (>= subtotal (:min-order coupon-data))))
        coupon-warning (when (and coupon-data (not coupon-valid?))
                         (str "Coupon " coupon " requires minimum order of $"
                              (format "%.2f" (double (:min-order coupon-data)))))

        coupon-pct     (if (and coupon-valid? (= :percentage (:type coupon-data)))
                         (:value coupon-data)
                         0)

        ;; Highest percentage wins
        best-pct       (max membership-pct tiered-pct coupon-pct)

        ;; Apply percentage
        after-pct      (* subtotal (- 1.0 (/ best-pct 100.0)))

        ;; Fixed discounts
        coupon-fixed   (if (and coupon-valid? (= :fixed (:type coupon-data)))
                         (:value coupon-data)
                         0)

        after-fixed    (max 0.0 (- after-pct coupon-fixed))

        result         (assoc cart-data
                              :discounted-subtotal after-fixed
                              :percentage-applied  best-pct
                              :fixed-applied       coupon-fixed)]
    (if coupon-warning
      (assoc result :coupon-warning coupon-warning)
      result)))

;; ── Step 3: Membership Benefits ───────────────────────────────────────

(defn apply-membership-benefits
  "Add membership-specific flags (free shipping, priority fulfillment)."
  [data membership]
  (let [subtotal (:discounted-subtotal data)]
    (assoc data
           :free-shipping? (case membership
                             :silver   (> subtotal 75)
                             :gold     true
                             :platinum true
                             false)
           :priority-fulfillment (= membership :platinum))))

;; ── Step 4: Calculate Tax ─────────────────────────────────────────────

(defn calc-tax
  "Calculate tax on the discounted subtotal."
  [data state tax-rates]
  (let [rate (get tax-rates state 0.0)
        tax  (-> (bigdec (:discounted-subtotal data))
                 (.multiply (bigdec rate))
                 (.setScale 2 java.math.RoundingMode/HALF_UP)
                 .doubleValue)]
    (assoc data :tax tax :tax-rate rate :state state)))

;; ── Step 5: Calculate Shipping ────────────────────────────────────────

(defn calc-shipping
  "Calculate shipping based on weight. Free if subtotal >= $100 or membership qualifies."
  [data]
  (let [weight   (:total-weight data)
        subtotal (:discounted-subtotal data)
        free?    (or (:free-shipping? data)
                     (>= subtotal 100.0))
        cost     (if free?
                   0.0
                   (+ 5.99 (* 0.50 weight)))]
    (assoc data :shipping (round2 cost))))

;; ── Step 6: Reserve Inventory ─────────────────────────────────────────

(defn reserve-inventory!
  "Attempt to reserve inventory for all line items. Returns data with
   :inventory-reserved? flag. Mutates inventory atom."
  [data inventory]
  (let [items (:line-items data)]
    (doseq [{:keys [product-id quantity]} items]
      (swap! inventory update product-id - quantity))
    (assoc data :inventory-reserved? true)))

(defn rollback-inventory!
  "Restore inventory reservations."
  [data inventory]
  (let [items (:line-items data)]
    (doseq [{:keys [product-id quantity]} items]
      (swap! inventory update product-id + quantity))
    (assoc data :inventory-reserved? false)))

;; ── Step 7: Process Payment ───────────────────────────────────────────

(defn process-payment
  "Mock payment. Cards starting with '4' succeed, '5' decline."
  [data card]
  (let [total (round2 (+ (:discounted-subtotal data)
                         (:tax data)
                         (:shipping data)))]
    (if (= \4 (first card))
      (assoc data
             :total          total
             :transaction-id (str "txn-" (random-uuid))
             :payment-status :approved)
      (assoc data
             :total          total
             :payment-status :declined))))

;; ── Step 8: Compile Result ────────────────────────────────────────────

(defn compile-success-result
  "Build the final success response."
  [data]
  {:status              :success
   :total               (:total data)
   :discounted-subtotal (:discounted-subtotal data)
   :tax                 (:tax data)
   :shipping            (:shipping data)
   :transaction-id      (:transaction-id data)
   :priority-fulfillment (:priority-fulfillment data)
   :coupon-warning      (:coupon-warning data)})

;; ── Main Pipeline ─────────────────────────────────────────────────────

(defn process-checkout
  "Run the full checkout pipeline.

   request keys: :items, :coupon, :membership, :state, :card
   resources keys: :catalog, :coupons, :inventory, :tax-rates"
  [request resources]
  (let [{:keys [items coupon membership state card]} request
        {:keys [catalog coupons inventory tax-rates]} resources

        ;; Step 1: Validate cart
        cart-result (validate-cart items catalog)]

    (if (:error cart-result)
      {:status :error :error (:error cart-result)}

      (let [;; Step 2: Apply discounts
            discounted (apply-discounts cart-result coupon membership coupons)

            ;; Step 3: Membership benefits
            with-membership (apply-membership-benefits discounted membership)

            ;; Step 4 & 5: Tax and shipping (could be parallel)
            with-tax      (calc-tax with-membership state tax-rates)
            with-shipping (calc-shipping with-tax)

            ;; Step 6: Reserve inventory
            reserved (reserve-inventory! with-shipping inventory)

            ;; Step 7: Process payment
            paid (process-payment reserved card)]

        (if (= :declined (:payment-status paid))
          (do
            (rollback-inventory! paid inventory)
            {:status :error :error "Payment declined"})

          (compile-success-result paid))))))

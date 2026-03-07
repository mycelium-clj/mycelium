(ns order.returns
  (:import [java.math RoundingMode]))

(defn- round2
  "Round a numeric value to 2 decimal places using HALF_UP."
  [x]
  (.doubleValue (.setScale (bigdec x) 2 RoundingMode/HALF_UP)))

(defn- warehouse-subtotals
  "Compute per-warehouse subtotal of final-prices from items-detail."
  [items-detail]
  (reduce (fn [acc item]
            (update acc (:warehouse item) (fnil + 0M) (bigdec (:final-price item))))
          {}
          items-detail))

(defn- compute-shipping-refund
  "For defective returns, compute proportional share of original warehouse shipping cost.
   share = item_final_price / warehouse_group_subtotal * warehouse_shipping_cost
   For changed-mind, always $0."
  [reason returned-items-detail items-detail shipping-detail]
  (if (= reason :changed-mind)
    0.0
    (let [wh-subtotals (warehouse-subtotals items-detail)]
      (reduce
        (fn [total item]
          (let [wh        (:warehouse item)
                wh-cost   (bigdec (get shipping-detail wh 0))
                wh-sub    (get wh-subtotals wh 1M)
                item-fp   (bigdec (:final-price item))
                share     (.setScale (.divide (.multiply item-fp wh-cost)
                                              wh-sub
                                              10 RoundingMode/HALF_UP)
                                     2 RoundingMode/HALF_UP)]
            (+ total (.doubleValue share))))
        0.0
        returned-items-detail))))

(defn- find-items-for-return
  "Given returned-items spec [{product-id qty}...] and the original items-detail,
   return a flat list of item detail records for the returned items."
  [returned-items items-detail]
  (let [return-qty-map (reduce (fn [m item-map]
                                 (merge m item-map))
                               {}
                               returned-items)]
    (reduce
      (fn [acc [product-id qty]]
        (let [matching (filter #(= product-id (:product-id %)) items-detail)]
          ;; Take up to qty items from matching records
          (into acc (take qty matching))))
      []
      return-qty-map)))

(defn- compute-loyalty-clawback
  "clawback = floor(subtotal_refund / original_discounted_subtotal * original_points_earned)"
  [subtotal-refund original-discounted-subtotal original-points-earned]
  (if (or (nil? original-points-earned)
          (zero? original-points-earned)
          (zero? original-discounted-subtotal))
    0
    (long (Math/floor (* (/ (double subtotal-refund) (double original-discounted-subtotal))
                         (double original-points-earned))))))

(defn- compute-payment-refund
  "Reverse order of charge: credit card first, then store credit, then gift card.
   Returns {:credit-card-refunded X :store-credit-refunded Y :gift-card-refunded Z}"
  [total-refund original-payment]
  (let [cc-charged (or (:credit-card-charged original-payment) 0.0)
        sc-charged (or (:store-credit-charged original-payment) 0.0)
        cc-refund  (min (double total-refund) (double cc-charged))
        remainder1 (- (double total-refund) cc-refund)
        sc-refund  (min remainder1 (double sc-charged))
        remainder2 (- remainder1 sc-refund)
        gc-refund  (if (pos? remainder2) remainder2 0.0)]
    {:credit-card-refunded  (round2 cc-refund)
     :store-credit-refunded (round2 sc-refund)
     :gift-card-refunded    (round2 gc-refund)}))

(defn- restocking-rate
  "Return the restocking fee rate for a given item category."
  [category]
  (case category
    :electronics 0.15
    :clothing    0.10
    :books       0.05
    :digital     0.0
    0.0))

(defn compute-restocking-fee
  "Compute total restocking fee for returned items.
   Changed-mind returns incur a per-item restocking fee based on category.
   Defective returns have no restocking fee.
   Each per-item fee is rounded to 2 decimal places before summing."
  [returned-detail reason]
  (if (= reason :defective)
    0.0
    (reduce + 0.0
            (map (fn [item]
                   (round2 (* (:final-price item) (restocking-rate (:category item)))))
                 returned-detail))))

(defn- gift-wrap-cost-per-item
  "Gift wrap cost based on category: books/digital $2.99, all others $4.99."
  [item]
  (if (#{:books :digital} (:category item))
    2.99
    4.99))

(defn- compute-gift-wrap-refund
  "Compute gift wrap refund for returned items.
   Defective: refund gift wrap cost for returned gift-wrapped items.
   Changed-mind: no gift wrap refund.
   Returns {:gift-wrap-refund X :gift-wrap-tax-refund Y}"
  [reason returned-detail original-order]
  (if (= reason :changed-mind)
    {:gift-wrap-refund     0.0
     :gift-wrap-tax-refund 0.0}
    (let [wrapped-returned (filter :gift-wrap returned-detail)
          wrap-refund      (round2 (reduce + 0.0 (map gift-wrap-cost-per-item wrapped-returned)))
          ;; Compute proportional gift wrap tax refund
          orig-gw-total    (or (:gift-wrap-total original-order) 0.0)
          orig-gw-tax      (or (:gift-wrap-tax original-order) 0.0)
          tax-refund       (if (pos? orig-gw-total)
                             (round2 (* orig-gw-tax (/ wrap-refund orig-gw-total)))
                             0.0)]
      {:gift-wrap-refund     wrap-refund
       :gift-wrap-tax-refund tax-refund})))

(defn- restore-inventory!
  "Add back returned item quantities to the inventory atom."
  [inventory returned-items]
  (let [return-qty-map (reduce merge {} returned-items)]
    (doseq [[product-id qty] return-qty-map]
      (swap! inventory update product-id (fnil + 0) qty))))

(def ^:private currency-rates
  {"USD" 1.00, "EUR" 0.92, "GBP" 0.79, "CAD" 1.36})

(defn process-return
  "Process a return for an original order.

   original-order: the result map from place-order containing:
     :items-detail          - vec of {:product-id :final-price :tax-amount :warehouse ...}
     :discounted-subtotal   - the post-discount subtotal
     :shipping              - total shipping cost
     :shipping-detail       - map of warehouse -> shipping cost
     :total                 - order grand total
     :loyalty               - {:points-earned N}
     :payment               - {:credit-card-charged X :gift-card-charged Y}
     :currency              - original order currency (default \"USD\")

   return-request: {:returned-items [{product-id qty} ...], :reason :defective|:changed-mind}

   resources: {:catalog :coupons :tax-rates :inventory (atom)}"
  [original-order return-request resources]
  (let [{:keys [items-detail discounted-subtotal shipping shipping-detail
                loyalty payment currency]} original-order
        currency (or currency "USD")
        {:keys [returned-items reason]}                     return-request
        inventory (:inventory resources)

        ;; Find the detail records for returned items
        returned-detail (find-items-for-return returned-items items-detail)

        ;; Restocking fee (changed-mind only, per category)
        restocking-fee (round2 (compute-restocking-fee returned-detail reason))

        ;; Subtotal refund: sum of returned items' final prices minus restocking fee
        raw-subtotal    (round2 (reduce + 0M (map #(bigdec (:final-price %)) returned-detail)))
        subtotal-refund (round2 (- raw-subtotal restocking-fee))

        ;; Tax refund: sum of returned items' per-item tax (full, not affected by restocking)
        tax-refund (round2 (reduce + 0M (map #(bigdec (:tax-amount %)) returned-detail)))

        ;; Shipping refund
        shipping-refund (round2 (compute-shipping-refund reason returned-detail items-detail shipping-detail))

        ;; Gift wrap refund
        gw-refund-result     (compute-gift-wrap-refund reason returned-detail original-order)
        gift-wrap-refund     (:gift-wrap-refund gw-refund-result)
        gift-wrap-tax-refund (:gift-wrap-tax-refund gw-refund-result)

        ;; Total refund
        total-refund (round2 (+ subtotal-refund tax-refund shipping-refund
                                gift-wrap-refund gift-wrap-tax-refund))

        ;; Loyalty clawback (based on adjusted subtotal-refund, after restocking deduction)
        points-earned   (get-in original-order [:loyalty :points-earned] 0)
        loyalty-clawback (compute-loyalty-clawback subtotal-refund discounted-subtotal points-earned)

        ;; Payment refund (reverse order: credit card first)
        payment-refund (compute-payment-refund total-refund payment)]

    ;; Restore inventory
    (restore-inventory! inventory returned-items)

    (let [rate (get currency-rates currency 1.00)]
      {:status               :success
       :restocking-fee       restocking-fee
       :subtotal-refund      subtotal-refund
       :tax-refund           tax-refund
       :shipping-refund      shipping-refund
       :gift-wrap-refund     gift-wrap-refund
       :gift-wrap-tax-refund gift-wrap-tax-refund
       :total-refund         total-refund
       :display-subtotal-refund (round2 (* subtotal-refund rate))
       :display-tax-refund      (round2 (* tax-refund rate))
       :display-total-refund    (round2 (* total-refund rate))
       :loyalty-clawback     loyalty-clawback
       :payment              payment-refund})))

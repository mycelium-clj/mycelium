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
  "Reverse order of charge: credit card first, then gift card.
   Returns {:credit-card-refunded X :gift-card-refunded Y}"
  [total-refund original-payment]
  (let [cc-charged (or (:credit-card-charged original-payment) 0.0)
        cc-refund  (min (double total-refund) (double cc-charged))
        remainder  (- (double total-refund) cc-refund)
        gc-refund  (if (pos? remainder) remainder 0.0)]
    {:credit-card-refunded (round2 cc-refund)
     :gift-card-refunded   (round2 gc-refund)}))

(defn- restore-inventory!
  "Add back returned item quantities to the inventory atom."
  [inventory returned-items]
  (let [return-qty-map (reduce merge {} returned-items)]
    (doseq [[product-id qty] return-qty-map]
      (swap! inventory update product-id (fnil + 0) qty))))

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

   return-request: {:returned-items [{product-id qty} ...], :reason :defective|:changed-mind}

   resources: {:catalog :coupons :tax-rates :inventory (atom)}"
  [original-order return-request resources]
  (let [{:keys [items-detail discounted-subtotal shipping shipping-detail
                loyalty payment]} original-order
        {:keys [returned-items reason]}                     return-request
        inventory (:inventory resources)

        ;; Find the detail records for returned items
        returned-detail (find-items-for-return returned-items items-detail)

        ;; Subtotal refund: sum of returned items' final prices
        subtotal-refund (round2 (reduce + 0M (map #(bigdec (:final-price %)) returned-detail)))

        ;; Tax refund: sum of returned items' per-item tax
        tax-refund (round2 (reduce + 0M (map #(bigdec (:tax-amount %)) returned-detail)))

        ;; Shipping refund
        shipping-refund (round2 (compute-shipping-refund reason returned-detail items-detail shipping-detail))

        ;; Total refund
        total-refund (round2 (+ subtotal-refund tax-refund shipping-refund))

        ;; Loyalty clawback
        points-earned   (get-in original-order [:loyalty :points-earned] 0)
        loyalty-clawback (compute-loyalty-clawback subtotal-refund discounted-subtotal points-earned)

        ;; Payment refund (reverse order: credit card first)
        payment-refund (compute-payment-refund total-refund payment)]

    ;; Restore inventory
    (restore-inventory! inventory returned-items)

    {:status           :success
     :subtotal-refund  subtotal-refund
     :tax-refund       tax-refund
     :shipping-refund  shipping-refund
     :total-refund     total-refund
     :loyalty-clawback loyalty-clawback
     :payment          payment-refund}))

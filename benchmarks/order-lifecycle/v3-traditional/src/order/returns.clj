(ns order.returns
  (:import [java.math RoundingMode]))

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 RoundingMode/HALF_UP)))

(def ^:private currency-rates
  {"USD" 1.00, "EUR" 0.92, "GBP" 0.79, "CAD" 1.36})

(def ^:private warranty-cost
  {:electronics 49.99 :clothing 9.99 :bundle 59.99})

(defn- warranty-cost-per-item [item]
  (get warranty-cost (:category item)))

;; ── Find returned items ─────────────────────────────────────────────

(defn- find-items-for-return
  "Given returned-items spec and original items-detail, return detail records.
   For partial fulfillment, only fulfilled items can be returned."
  [returned-items items-detail fulfillment]
  (let [;; For partial fulfillment, filter to fulfilled items only
        eligible-items
        (if (and fulfillment (= :partial (:status fulfillment)))
          (let [fulfilled-pids (frequencies
                                 (map :product-id (:fulfilled-items fulfillment)))
                counts (atom fulfilled-pids)]
            (filterv (fn [item]
                       (let [pid (:product-id item)
                             remaining (get @counts pid 0)]
                         (when (pos? remaining)
                           (swap! counts update pid dec)
                           true)))
                     items-detail))
          items-detail)

        return-qty-map (reduce merge {} returned-items)]
    (reduce
      (fn [acc [product-id qty]]
        (let [matching (filter #(= product-id (:product-id %)) eligible-items)]
          (into acc (take qty matching))))
      []
      return-qty-map)))

;; ── Restocking fee ──────────────────────────────────────────────────

(defn- restocking-rate [category]
  (case category
    :electronics 0.15
    :clothing    0.10
    :books       0.05
    :digital     0.0
    :bundle      0.0
    0.0))

(defn compute-restocking-fee [returned-detail reason]
  (if (= reason :defective)
    0.0
    (reduce + 0.0
            (map (fn [item]
                   (round2 (* (:final-price item) (restocking-rate (:category item)))))
                 returned-detail))))

;; ── Shipping refund (V3 tiered) ─────────────────────────────────────

(defn- item-is-hazmat? [item]
  (#{:electronics :bundle} (:category item)))

(defn- compute-shipping-refund
  "For defective returns, compute shipping refund including base + hazmat + oversized.
   Changed-mind: always $0."
  [reason returned-detail items-detail shipping-detail]
  (if (= reason :changed-mind)
    0.0
    (if (nil? shipping-detail)
      0.0
      (let [wh-subtotals (reduce (fn [acc item]
                                   (update acc (:warehouse item)
                                           (fnil + 0M) (bigdec (:final-price item))))
                                 {}
                                 items-detail)]
        (reduce
          (fn [total item]
            (let [wh        (:warehouse item)
                  group     (get shipping-detail wh)
                  base-cost     (bigdec (or (:base-cost group) (:cost group) 0))
                  oversized-cost (bigdec (or (:oversized-cost group) 0))
                  wh-sub    (get wh-subtotals wh 1M)
                  item-fp   (bigdec (:final-price item))

                  base-share (.setScale (.divide (.multiply item-fp base-cost)
                                                 wh-sub
                                                 10 RoundingMode/HALF_UP)
                                        2 RoundingMode/HALF_UP)

                  oversized-share (.setScale (.divide (.multiply item-fp oversized-cost)
                                                     wh-sub
                                                     10 RoundingMode/HALF_UP)
                                             2 RoundingMode/HALF_UP)

                  hazmat-refund (if (item-is-hazmat? item) 3.0 0.0)]
              (+ total
                 (.doubleValue base-share)
                 (.doubleValue oversized-share)
                 hazmat-refund)))
          0.0
          returned-detail)))))

;; ── Gift wrap refund ────────────────────────────────────────────────

(defn- gift-wrap-cost-per-item [item]
  (if (#{:books :digital} (:category item))
    2.99
    4.99))

(defn- compute-gift-wrap-refund
  [reason returned-detail original-order]
  (if (= reason :changed-mind)
    {:gift-wrap-refund     0.0
     :gift-wrap-tax-refund 0.0}
    (let [wrapped-returned (filter :gift-wrap returned-detail)
          wrap-refund      (round2 (reduce + 0.0 (map gift-wrap-cost-per-item wrapped-returned)))
          orig-gw-total    (or (:gift-wrap-total original-order) 0.0)
          orig-gw-tax      (or (:gift-wrap-tax original-order) 0.0)
          tax-refund       (if (pos? orig-gw-total)
                             (round2 (* orig-gw-tax (/ wrap-refund orig-gw-total)))
                             0.0)]
      {:gift-wrap-refund     wrap-refund
       :gift-wrap-tax-refund tax-refund})))

;; ── Warranty refund ─────────────────────────────────────────────────

(defn- compute-warranty-refund
  "Defective: full warranty refund. Changed-mind: 50% refund."
  [reason returned-detail original-order]
  (let [warranted-items (filter (fn [item]
                                  (and (:warranty item)
                                       (warranty-cost-per-item item)))
                                returned-detail)
        orig-wt-total   (or (:warranty-total original-order) 0.0)
        orig-wt-tax     (or (:warranty-tax original-order) 0.0)
        raw-warranty-refund (reduce + 0.0 (map warranty-cost-per-item warranted-items))
        raw-warranty-tax-refund (if (pos? orig-wt-total)
                                  (* orig-wt-tax (/ raw-warranty-refund orig-wt-total))
                                  0.0)]
    (case reason
      :defective
      {:warranty-refund     (round2 raw-warranty-refund)
       :warranty-tax-refund (round2 raw-warranty-tax-refund)}

      ;; changed-mind: 50% refund
      {:warranty-refund     (round2 (* raw-warranty-refund 0.50))
       :warranty-tax-refund (round2 (* raw-warranty-tax-refund 0.50))})))

;; ── Loyalty clawback ────────────────────────────────────────────────

(defn- compute-loyalty-clawback
  [subtotal-refund original-discounted-subtotal original-points-earned]
  (if (or (nil? original-points-earned)
          (zero? original-points-earned)
          (zero? original-discounted-subtotal))
    0
    (long (Math/floor (* (/ (double subtotal-refund) (double original-discounted-subtotal))
                         (double original-points-earned))))))

;; ── Payment refund ──────────────────────────────────────────────────

(defn- compute-payment-refund
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

;; ── Inventory restore ───────────────────────────────────────────────

(defn- restore-inventory!
  "Restore inventory for returned items. Bundles restore component inventory."
  [inventory returned-items returned-detail]
  (let [return-qty-map (reduce merge {} returned-items)
        detail-by-pid  (reduce (fn [acc item]
                                 (assoc acc (:product-id item) item))
                               {}
                               returned-detail)]
    (doseq [[product-id qty] return-qty-map]
      (let [detail (get detail-by-pid product-id)]
        (if (and detail (:components detail))
          ;; Bundle: restore component inventory
          (doseq [[component-pid component-qty] (:components detail)]
            (swap! inventory update component-pid (fnil + 0) (* component-qty qty)))
          ;; Regular item
          (swap! inventory update product-id (fnil + 0) qty))))))

;; ── Main entry point ────────────────────────────────────────────────

(defn process-return
  [original-order return-request resources]
  (let [{:keys [items-detail discounted-subtotal shipping shipping-detail
                loyalty payment currency fulfillment
                gift-wrap-total gift-wrap-tax warranty-total warranty-tax]} original-order
        currency (or currency "USD")
        {:keys [returned-items reason]} return-request
        inventory (:inventory resources)

        ;; Find returned items (partial fulfillment: only fulfilled items)
        returned-detail (find-items-for-return returned-items items-detail fulfillment)

        ;; Restocking fee
        restocking-fee (round2 (compute-restocking-fee returned-detail reason))

        ;; Subtotal refund
        raw-subtotal    (round2 (reduce + 0M (map #(bigdec (:final-price %)) returned-detail)))
        subtotal-refund (round2 (- raw-subtotal restocking-fee))

        ;; Tax refund
        tax-refund (round2 (reduce + 0M (map #(bigdec (:tax-amount %)) returned-detail)))

        ;; Shipping refund (uses shipping-detail -- BUG: should be shipping-groups)
        shipping-refund (round2 (compute-shipping-refund reason returned-detail items-detail shipping-detail))

        ;; Gift wrap refund
        gw-result            (compute-gift-wrap-refund reason returned-detail original-order)
        gift-wrap-refund     (:gift-wrap-refund gw-result)
        gift-wrap-tax-refund (:gift-wrap-tax-refund gw-result)

        ;; Warranty refund
        wt-result            (compute-warranty-refund reason returned-detail original-order)
        warranty-refund      (:warranty-refund wt-result)
        warranty-tax-refund  (:warranty-tax-refund wt-result)

        ;; Total refund
        total-refund (round2 (+ subtotal-refund tax-refund shipping-refund
                                gift-wrap-refund gift-wrap-tax-refund
                                warranty-refund warranty-tax-refund))

        ;; Loyalty clawback
        points-earned   (get-in original-order [:loyalty :points-earned] 0)
        loyalty-clawback (compute-loyalty-clawback subtotal-refund discounted-subtotal points-earned)

        ;; Payment refund
        payment-refund (compute-payment-refund total-refund payment)

        ;; Subscription cancellation
        subscription-cancelled (boolean (some :subscription returned-detail))]

    ;; Restore inventory
    (restore-inventory! inventory returned-items returned-detail)

    (let [rate (get currency-rates currency 1.00)]
      {:status               :success
       :restocking-fee       restocking-fee
       :subtotal-refund      subtotal-refund
       :tax-refund           tax-refund
       :shipping-refund      shipping-refund
       :gift-wrap-refund     gift-wrap-refund
       :gift-wrap-tax-refund gift-wrap-tax-refund
       :warranty-refund      warranty-refund
       :warranty-tax-refund  warranty-tax-refund
       :total-refund         total-refund
       :display-subtotal-refund (round2 (* subtotal-refund rate))
       :display-tax-refund      (round2 (* tax-refund rate))
       :display-total-refund    (round2 (* total-refund rate))
       :loyalty-clawback     loyalty-clawback
       :subscription-cancelled subscription-cancelled
       :payment              payment-refund})))

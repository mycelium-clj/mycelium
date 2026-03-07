(ns order.returns-cells
  (:require [mycelium.cell :as cell])
  (:import [java.math RoundingMode]))

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 RoundingMode/HALF_UP)))

(def ^:private currency-rates
  {"USD" 1.00 "EUR" 0.92 "GBP" 0.79 "CAD" 1.36})

(def ^:private gift-wrap-cost
  {:books 2.99 :digital 2.99})

(defn- gift-wrap-cost-per-item [item]
  (get gift-wrap-cost (:category item) 4.99))

;; ── Cell 1: find-items ──────────────────────────────────────────────────

(defmethod cell/cell-spec :return/find-items [_]
  {:id      :return/find-items
   :handler (fn [_ data]
              (let [returned-items (:returned-items data)
                    items-detail   (:items-detail data)
                    return-qty-map (reduce merge {} returned-items)
                    returned-detail
                    (reduce
                      (fn [acc [product-id qty]]
                        (let [matching (filter #(= product-id (:product-id %)) items-detail)]
                          (into acc (take qty matching))))
                      []
                      return-qty-map)]
                (assoc data :returned-detail returned-detail)))
   :schema {:input  [:map
                     [:items-detail [:vector :any]]
                     [:discounted-subtotal :double]
                     [:shipping-groups :any]
                     [:payment :any]
                     [:points-earned :int]
                     [:gift-wrap-total :double]
                     [:gift-wrap-tax :double]
                     [:currency :string]
                     [:returned-items [:vector :any]]
                     [:reason :keyword]]
            :output [:map
                     [:returned-detail [:vector :any]]]}})

;; ── Cell 2: calc-restocking ─────────────────────────────────────────────

(defn- restocking-rate [category]
  (case category
    :electronics 0.15
    :clothing    0.10
    :books       0.05
    :digital     0.0
    0.0))

(defmethod cell/cell-spec :return/calc-restocking [_]
  {:id      :return/calc-restocking
   :handler (fn [_ data]
              (let [returned-detail (:returned-detail data)
                    reason          (:reason data)
                    fee (if (= reason :defective)
                          0.0
                          (reduce + 0.0
                                  (map (fn [item]
                                         (round2 (* (:final-price item)
                                                    (restocking-rate (:category item)))))
                                       returned-detail)))]
                (assoc data :restocking-fee (round2 fee))))
   :schema {:input  [:map
                     [:returned-detail [:vector :any]]
                     [:reason :keyword]]
            :output [:map
                     [:restocking-fee :double]]}})

;; ── Cell 3: calc-refunds ────────────────────────────────────────────────

(defmethod cell/cell-spec :return/calc-refunds [_]
  {:id      :return/calc-refunds
   :handler (fn [_ data]
              (let [returned-detail  (:returned-detail data)
                    items-detail     (:items-detail data)
                    shipping-groups  (:shipping-groups data)
                    reason           (:reason data)
                    restocking-fee   (:restocking-fee data)

                    raw-subtotal (round2 (reduce + 0M (map #(bigdec (:final-price %)) returned-detail)))
                    subtotal-refund (round2 (- raw-subtotal restocking-fee))

                    tax-refund (round2 (reduce + 0M (map #(bigdec (:tax-amount %)) returned-detail)))

                    shipping-refund
                    (if (= reason :changed-mind)
                      0.0
                      (let [wh-subtotals (reduce (fn [acc item]
                                                   (update acc (:warehouse item)
                                                           (fnil + 0M) (bigdec (:final-price item))))
                                                 {}
                                                 items-detail)]
                        (reduce
                          (fn [total item]
                            (let [wh       (:warehouse item)
                                  wh-cost  (bigdec (get-in shipping-groups [wh :cost] 0))
                                  wh-sub   (get wh-subtotals wh 1M)
                                  item-fp  (bigdec (:final-price item))
                                  share    (.setScale (.divide (.multiply item-fp wh-cost)
                                                               wh-sub
                                                               10 RoundingMode/HALF_UP)
                                                      2 RoundingMode/HALF_UP)]
                              (+ total (.doubleValue share))))
                          0.0
                          returned-detail)))

                    shipping-refund (round2 shipping-refund)]
                (assoc data
                       :subtotal-refund subtotal-refund
                       :tax-refund      tax-refund
                       :shipping-refund shipping-refund)))
   :schema {:input  [:map
                     [:returned-detail [:vector :any]]
                     [:items-detail [:vector :any]]
                     [:shipping-groups :any]
                     [:reason :keyword]
                     [:restocking-fee :double]]
            :output [:map
                     [:subtotal-refund :double]
                     [:tax-refund :double]
                     [:shipping-refund :double]]}})

;; ── Cell 4: calc-gift-wrap-refund ───────────────────────────────────────

(defmethod cell/cell-spec :return/calc-gift-wrap-refund [_]
  {:id      :return/calc-gift-wrap-refund
   :handler (fn [_ data]
              (let [reason          (:reason data)
                    returned-detail (:returned-detail data)]
                (if (= reason :changed-mind)
                  (assoc data
                         :gift-wrap-refund     0.0
                         :gift-wrap-tax-refund 0.0)
                  (let [wrapped-returned (filter :gift-wrap returned-detail)
                        wrap-refund      (round2 (reduce + 0.0 (map gift-wrap-cost-per-item wrapped-returned)))
                        orig-gw-total    (or (:gift-wrap-total data) 0.0)
                        orig-gw-tax      (or (:gift-wrap-tax data) 0.0)
                        tax-refund       (if (pos? orig-gw-total)
                                           (round2 (* orig-gw-tax (/ wrap-refund orig-gw-total)))
                                           0.0)]
                    (assoc data
                           :gift-wrap-refund     wrap-refund
                           :gift-wrap-tax-refund tax-refund)))))
   :schema {:input  [:map
                     [:returned-detail [:vector :any]]
                     [:reason :keyword]
                     [:gift-wrap-total :double]
                     [:gift-wrap-tax :double]]
            :output [:map
                     [:gift-wrap-refund :double]
                     [:gift-wrap-tax-refund :double]]}})

;; ── Cell 5: calc-totals ─────────────────────────────────────────────────

(defmethod cell/cell-spec :return/calc-totals [_]
  {:id      :return/calc-totals
   :handler (fn [_ data]
              (let [total (round2 (+ (:subtotal-refund data)
                                     (:tax-refund data)
                                     (:shipping-refund data)
                                     (:gift-wrap-refund data)
                                     (:gift-wrap-tax-refund data)))]
                (assoc data :total-refund total)))
   :schema {:input  [:map
                     [:subtotal-refund :double]
                     [:tax-refund :double]
                     [:shipping-refund :double]
                     [:gift-wrap-refund :double]
                     [:gift-wrap-tax-refund :double]]
            :output [:map
                     [:total-refund :double]]}})

;; ── Cell 6: calc-adjustments ────────────────────────────────────────────

(defmethod cell/cell-spec :return/calc-adjustments [_]
  {:id      :return/calc-adjustments
   :handler (fn [_ data]
              (let [subtotal-refund      (:subtotal-refund data)
                    total-refund         (:total-refund data)
                    discounted-subtotal  (:discounted-subtotal data)
                    points-earned        (:points-earned data)
                    payment              (:payment data)

                    loyalty-clawback
                    (if (or (nil? points-earned)
                            (zero? points-earned)
                            (zero? discounted-subtotal))
                      0
                      (long (Math/floor (* (/ (double subtotal-refund)
                                              (double discounted-subtotal))
                                           (double points-earned)))))

                    ;; 3-way reverse waterfall: CC first, SC second, GC last
                    cc-charged  (or (:credit-card-charged payment) 0.0)
                    sc-charged  (or (:store-credit-charged payment) 0.0)
                    cc-refund   (min (double total-refund) (double cc-charged))
                    remainder1  (- (double total-refund) cc-refund)
                    sc-refund   (min remainder1 (double sc-charged))
                    remainder2  (- remainder1 sc-refund)
                    gc-refund   (if (pos? remainder2) remainder2 0.0)

                    payment-refund {:credit-card-refunded  (round2 cc-refund)
                                    :store-credit-refunded (round2 sc-refund)
                                    :gift-card-refunded    (round2 gc-refund)}]
                (assoc data
                       :loyalty-clawback loyalty-clawback
                       :payment-refund   payment-refund)))
   :schema {:input  [:map
                     [:subtotal-refund :double]
                     [:total-refund :double]
                     [:discounted-subtotal :double]
                     [:points-earned :int]
                     [:payment :any]]
            :output [:map
                     [:loyalty-clawback :int]
                     [:payment-refund :any]]}})

;; ── Cell 7: convert-currency ────────────────────────────────────────────

(defmethod cell/cell-spec :return/convert-currency [_]
  {:id      :return/convert-currency
   :handler (fn [_ data]
              (let [currency (:currency data)
                    rate     (get currency-rates currency 1.00)]
                (assoc data
                       :display-subtotal-refund (round2 (* (:subtotal-refund data) rate))
                       :display-tax-refund      (round2 (* (:tax-refund data) rate))
                       :display-total-refund    (round2 (* (:total-refund data) rate)))))
   :schema {:input  [:map
                     [:subtotal-refund :double]
                     [:tax-refund :double]
                     [:total-refund :double]
                     [:currency :string]]
            :output [:map
                     [:display-subtotal-refund :double]
                     [:display-tax-refund :double]
                     [:display-total-refund :double]]}})

;; ── Cell 8: restore-inventory ───────────────────────────────────────────

(defmethod cell/cell-spec :return/restore-inventory [_]
  {:id       :return/restore-inventory
   :handler  (fn [{:keys [inventory]} data]
               (let [returned-items (:returned-items data)
                     return-qty-map (reduce merge {} returned-items)]
                 (doseq [[product-id qty] return-qty-map]
                   (swap! inventory update product-id (fnil + 0) qty))
                 (assoc data :inventory-restored true)))
   :schema   {:input  [:map [:returned-items [:vector :any]]]
              :output [:map [:inventory-restored :boolean]]}
   :requires [:inventory]})

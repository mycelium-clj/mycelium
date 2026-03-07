(ns order.returns-cells
  (:require [mycelium.cell :as cell])
  (:import [java.math RoundingMode]))

(defn- round2
  "Round a numeric value to 2 decimal places using HALF_UP."
  [x]
  (.doubleValue (.setScale (bigdec x) 2 RoundingMode/HALF_UP)))

;; ── Cell 1: find-items ──────────────────────────────────────────────────
;; Given returned-items (vec of {product-id qty} maps) and items-detail
;; (vec of {:product-id :final-price :tax-amount :warehouse}), produce
;; returned-detail — a flat list of item detail records for returned items.

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
                     [:returned-items [:vector :any]]
                     [:reason :keyword]]
            :output [:map
                     [:returned-detail [:vector :any]]]}})

;; ── Cell 2: calc-refunds ────────────────────────────────────────────────
;; Compute subtotal-refund, tax-refund, shipping-refund, and total-refund.
;; Shipping refund is proportional share of warehouse shipping cost for
;; :defective reason, $0 for :changed-mind.
;; shipping-groups is a map of warehouse -> {:cost X :subtotal Y}.

(defmethod cell/cell-spec :return/calc-refunds [_]
  {:id      :return/calc-refunds
   :handler (fn [_ data]
              (let [returned-detail  (:returned-detail data)
                    items-detail     (:items-detail data)
                    shipping-groups  (:shipping-groups data)
                    reason           (:reason data)

                    ;; Subtotal refund: sum of returned items' final prices
                    subtotal-refund (round2 (reduce + 0M (map #(bigdec (:final-price %)) returned-detail)))

                    ;; Tax refund: sum of returned items' tax amounts
                    tax-refund (round2 (reduce + 0M (map #(bigdec (:tax-amount %)) returned-detail)))

                    ;; Shipping refund
                    shipping-refund
                    (if (= reason :changed-mind)
                      0.0
                      ;; For :defective, compute proportional share per warehouse
                      ;; Warehouse subtotals come from ALL items-detail (not just returned)
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

                    shipping-refund (round2 shipping-refund)

                    ;; Total refund
                    total-refund (round2 (+ subtotal-refund tax-refund shipping-refund))]
                (assoc data
                       :subtotal-refund subtotal-refund
                       :tax-refund      tax-refund
                       :shipping-refund shipping-refund
                       :total-refund    total-refund)))
   :schema {:input  [:map
                     [:returned-detail [:vector :any]]
                     [:items-detail [:vector :any]]
                     [:shipping-groups :any]
                     [:reason :keyword]]
            :output [:map
                     [:subtotal-refund :double]
                     [:tax-refund :double]
                     [:shipping-refund :double]
                     [:total-refund :double]]}})

;; ── Cell 3: calc-adjustments ────────────────────────────────────────────
;; Compute loyalty clawback and payment refund split.
;; Clawback: floor(subtotal_refund / discounted_subtotal * points_earned)
;; Payment: reverse order of charge — credit card first, then gift card.

(defmethod cell/cell-spec :return/calc-adjustments [_]
  {:id      :return/calc-adjustments
   :handler (fn [_ data]
              (let [subtotal-refund      (:subtotal-refund data)
                    total-refund         (:total-refund data)
                    discounted-subtotal  (:discounted-subtotal data)
                    points-earned        (:points-earned data)
                    payment              (:payment data)

                    ;; Loyalty clawback
                    loyalty-clawback
                    (if (or (nil? points-earned)
                            (zero? points-earned)
                            (zero? discounted-subtotal))
                      0
                      (long (Math/floor (* (/ (double subtotal-refund)
                                              (double discounted-subtotal))
                                           (double points-earned)))))

                    ;; Payment refund — reverse order: credit card first, then gift card
                    cc-charged  (or (:credit-card-charged payment) 0.0)
                    cc-refund   (min (double total-refund) (double cc-charged))
                    remainder   (- (double total-refund) cc-refund)
                    gc-refund   (if (pos? remainder) remainder 0.0)

                    payment-refund {:credit-card-refunded (round2 cc-refund)
                                    :gift-card-refunded   (round2 gc-refund)}]
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

;; ── Cell 4: restore-inventory ───────────────────────────────────────────
;; Add returned item quantities back to the inventory atom.

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

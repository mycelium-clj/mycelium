(ns checkout.cells
  (:require [mycelium.cell :as cell]))

;; ── Cell: validate-cart ───────────────────────────────────────────────
;; Input:  [:map [:items [:vector :any]] [:catalog :any]]
;; Output: {:valid   [:map [:line-items ...] [:subtotal :double] [:total-weight :double]]
;;          :invalid [:map [:checkout-error :string]]}

(defmethod cell/cell-spec :checkout/validate-cart [_]
  {:id      :checkout/validate-cart
   :handler (fn [_ data]
              (let [items   (:items data)
                    catalog (:catalog data)]
                (if (empty? items)
                  (assoc data :checkout-error "Cart is empty")
                  (let [line-items (for [item-map items
                                         [product-id qty] item-map]
                                    (when-let [product (get catalog product-id)]
                                      {:product-id product-id
                                       :name       (:name product)
                                       :price      (:price product)
                                       :weight     (:weight product)
                                       :quantity   qty}))
                        invalid    (some nil? line-items)]
                    (if invalid
                      (assoc data :checkout-error "Unknown product in cart")
                      (let [items-vec (vec line-items)]
                        (assoc data
                               :line-items    items-vec
                               :subtotal      (reduce + 0.0 (map #(* (:price %) (:quantity %)) items-vec))
                               :total-weight  (reduce + 0.0 (map #(* (:weight %) (:quantity %)) items-vec)))))))))
   :schema {:input  [:map [:items [:vector :any]] [:catalog :any]]
            :output {:valid   [:map [:line-items [:vector :any]] [:subtotal :double] [:total-weight :double]]
                     :invalid [:map [:checkout-error :string]]}}})

;; ── Cell: apply-discounts ─────────────────────────────────────────────
;; Input:  [:map [:subtotal :double] [:line-items [:vector :any]]]
;; Output: [:map [:discounted-subtotal :double] [:percentage-applied :int] [:fixed-applied :double]]
;;
;; Rules:
;; - Percentage discounts: coupon (if percentage type), membership tier, tiered by subtotal
;; - Only the highest percentage applies
;; - Fixed discounts stack (coupon if fixed type)
;; - Percentage applied first, then fixed subtracted
;; - Floor at $0

(defmethod cell/cell-spec :checkout/apply-discounts [_]
  {:id      :checkout/apply-discounts
   :handler (fn [_ data]
              (let [subtotal   (:subtotal data)
                    coupon     (:coupon data)
                    membership (or (:membership data) :none)
                    coupons    (:coupons data)

                    ;; Membership percentage
                    mem-pct    (case membership
                                 :gold     5
                                 :platinum 10
                                 0)

                    ;; Tiered percentage
                    tier-pct   (cond
                                 (>= subtotal 1000) 15
                                 (>= subtotal 500)  10
                                 (>= subtotal 200)  5
                                 :else              0)

                    ;; Coupon lookup
                    coupon-data (when coupon (get coupons coupon))
                    coupon-ok?  (and coupon-data
                                    (or (nil? (:min-order coupon-data))
                                        (>= subtotal (:min-order coupon-data))))
                    coupon-warn (when (and coupon-data (not coupon-ok?))
                                 (str "Coupon " coupon " requires minimum order of $"
                                      (format "%.2f" (double (:min-order coupon-data)))))

                    coupon-pct  (if (and coupon-ok? (= :percentage (:type coupon-data)))
                                 (:value coupon-data)
                                 0)

                    ;; Highest percentage wins
                    best-pct    (max mem-pct tier-pct coupon-pct)

                    ;; Apply percentage
                    after-pct   (* subtotal (- 1.0 (/ best-pct 100.0)))

                    ;; Fixed discounts
                    fixed-amt   (if (and coupon-ok? (= :fixed (:type coupon-data)))
                                  (double (:value coupon-data))
                                  0.0)

                    after-fixed (max 0.0 (- after-pct fixed-amt))]
                (cond-> (assoc data
                               :discounted-subtotal after-fixed
                               :percentage-applied  best-pct
                               :fixed-applied       fixed-amt)
                  coupon-warn (assoc :coupon-warning coupon-warn))))
   :schema {:input  [:map [:subtotal :double] [:line-items [:vector :any]]]
            :output [:map [:discounted-subtotal :double] [:percentage-applied :int] [:fixed-applied :double]]}})

;; ── Cell: membership-benefits ─────────────────────────────────────────
;; Input:  [:map [:discounted-subtotal :double] [:membership :keyword]]
;; Output: [:map [:free-shipping? :boolean] [:priority-fulfillment :boolean]]

(defmethod cell/cell-spec :checkout/membership-benefits [_]
  {:id      :checkout/membership-benefits
   :handler (fn [_ data]
              (let [membership (or (:membership data) :none)
                    subtotal   (:discounted-subtotal data)]
                (assoc data
                       :free-shipping?       (case membership
                                               :silver   (> subtotal 75.0)
                                               :gold     true
                                               :platinum true
                                               false)
                       :priority-fulfillment (= membership :platinum))))
   :schema {:input  [:map [:discounted-subtotal :double] [:membership :keyword]]
            :output [:map [:free-shipping? :boolean] [:priority-fulfillment :boolean]]}})

;; ── Cell: calc-tax ────────────────────────────────────────────────────
;; Input:  [:map [:discounted-subtotal :double] [:state :string]]
;; Output: [:map [:tax :double]]

(defmethod cell/cell-spec :checkout/calc-tax [_]
  {:id      :checkout/calc-tax
   :handler (fn [_ data]
              (let [rate     (get (:tax-rates data) (:state data) 0.0)
                    tax      (-> (bigdec (:discounted-subtotal data))
                                 (.multiply (bigdec rate))
                                 (.setScale 2 java.math.RoundingMode/HALF_UP)
                                 .doubleValue)]
                (assoc data :tax tax)))
   :schema {:input  [:map [:discounted-subtotal :double] [:state :string]]
            :output [:map [:tax :double]]}})

;; ── Cell: calc-shipping ───────────────────────────────────────────────
;; Input:  [:map [:total-weight :double] [:discounted-subtotal :double] [:free-shipping? :boolean]]
;; Output: [:map [:shipping :double]]

(defmethod cell/cell-spec :checkout/calc-shipping [_]
  {:id      :checkout/calc-shipping
   :handler (fn [_ data]
              (let [weight   (:total-weight data)
                    subtotal (:discounted-subtotal data)
                    free?    (or (:free-shipping? data)
                                 (>= subtotal 100.0))
                    cost     (if free? 0.0 (+ 5.99 (* 0.50 weight)))]
                (assoc data :shipping (-> (bigdec cost)
                                          (.setScale 2 java.math.RoundingMode/HALF_UP)
                                          .doubleValue))))
   :schema {:input  [:map [:total-weight :double] [:discounted-subtotal :double] [:free-shipping? :boolean]]
            :output [:map [:shipping :double]]}})

;; ── Cell: reserve-inventory ───────────────────────────────────────────
;; Input:  [:map [:line-items [:vector :any]]]
;; Output: [:map [:inventory-reserved? :boolean]]

(defmethod cell/cell-spec :checkout/reserve-inventory [_]
  {:id      :checkout/reserve-inventory
   :handler (fn [{:keys [inventory]} data]
              (doseq [{:keys [product-id quantity]} (:line-items data)]
                (swap! inventory update product-id - quantity))
              (assoc data :inventory-reserved? true))
   :schema  {:input  [:map [:line-items [:vector :any]]]
             :output [:map [:inventory-reserved? :boolean]]}
   :requires [:inventory]})

;; ── Cell: process-payment ─────────────────────────────────────────────
;; Input:  [:map [:discounted-subtotal :double] [:tax :double] [:shipping :double] [:card :string]]
;; Output: {:approved [:map [:total :double] [:transaction-id :string] [:payment-status :keyword]]
;;          :declined [:map [:total :double] [:payment-status :keyword]]}

(defmethod cell/cell-spec :checkout/process-payment [_]
  {:id      :checkout/process-payment
   :handler (fn [_ data]
              (let [total (-> (bigdec (:discounted-subtotal data))
                              (.add (bigdec (:tax data)))
                              (.add (bigdec (:shipping data)))
                              (.setScale 2 java.math.RoundingMode/HALF_UP)
                              .doubleValue)
                    card  (:card data)]
                (if (= \4 (first card))
                  (assoc data
                         :total          total
                         :transaction-id (str "txn-" (random-uuid))
                         :payment-status :approved)
                  (assoc data
                         :total          total
                         :payment-status :declined))))
   :schema {:input  [:map [:discounted-subtotal :double] [:tax :double] [:shipping :double] [:card :string]]
            :output {:approved [:map [:total :double] [:transaction-id :string] [:payment-status :keyword]]
                     :declined [:map [:total :double] [:payment-status :keyword]]}}})

;; ── Cell: rollback-inventory ──────────────────────────────────────────
;; Input:  [:map [:line-items [:vector :any]]]
;; Output: [:map [:inventory-reserved? :boolean] [:checkout-error :string]]

(defmethod cell/cell-spec :checkout/rollback-inventory [_]
  {:id      :checkout/rollback-inventory
   :handler (fn [{:keys [inventory]} data]
              (doseq [{:keys [product-id quantity]} (:line-items data)]
                (swap! inventory update product-id + quantity))
              (assoc data
                     :inventory-reserved? false
                     :checkout-error      "Payment declined"))
   :schema  {:input  [:map [:line-items [:vector :any]]]
             :output [:map [:inventory-reserved? :boolean] [:checkout-error :string]]}
   :requires [:inventory]})

;; ── Cell: confirm-order ───────────────────────────────────────────────
;; Input:  [:map [:total :double] [:discounted-subtotal :double] [:tax :double]
;;               [:shipping :double] [:transaction-id :string]]
;; Output: [:map [:order-status :keyword]]

(defmethod cell/cell-spec :checkout/confirm-order [_]
  {:id      :checkout/confirm-order
   :handler (fn [_ data]
              (assoc data :order-status :confirmed))
   :schema {:input  [:map [:total :double] [:discounted-subtotal :double]
                     [:tax :double] [:shipping :double] [:transaction-id :string]]
            :output [:map [:order-status :keyword]]}})

(ns order.placement-cells
  (:require [mycelium.cell :as cell])
  (:import [java.math RoundingMode]))

;; ── Helpers ─────────────────────────────────────────────────────────────

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 RoundingMode/HALF_UP)))

(defn- count-category [items cat-kw]
  (count (filter #(= cat-kw (:category %)) items)))

(defn- tier-multiplier [membership]
  (case membership
    :gold   2.0
    :silver 1.5
    :bronze 1.0
    1.0))

(defn- distribute-discount
  "Distribute an order-level discount proportionally across items by :current-price.
   Round each item's share to 2 decimals; adjust the largest item by any remainder
   so the total matches exactly."
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

;; ── Cell 1: expand-items ────────────────────────────────────────────────

(defmethod cell/cell-spec :order/expand-items [_]
  {:id      :order/expand-items
   :handler (fn [_ data]
              (let [items   (:items data)
                    catalog (:catalog data)
                    expanded (vec
                              (mapcat
                               (fn [item-map]
                                 (let [[pid qty] (first item-map)
                                       product   (get catalog pid)]
                                   (repeat qty (assoc product
                                                      :product-id pid
                                                      :current-price (:price product)))))
                               items))]
                (assoc data :expanded-items expanded)))
   :schema {:input  [:map
                     [:items [:vector :any]]
                     [:catalog :any]
                     [:coupon :any]
                     [:coupons :any]
                     [:membership :keyword]
                     [:state :string]
                     [:card :string]
                     [:gift-card-balance :double]
                     [:loyalty-points :int]
                     [:tax-rates :any]]
            :output [:map
                     [:expanded-items [:vector :any]]]}})

;; ── Cell 2: apply-combo75 ───────────────────────────────────────────────

(defmethod cell/cell-spec :order/apply-combo75 [_]
  {:id      :order/apply-combo75
   :handler (fn [_ data]
              (let [items       (:expanded-items data)
                    combo-ids   #{"laptop" "headphones" "novel"}
                    product-ids (set (map :product-id items))]
                (if (every? product-ids combo-ids)
                  (let [combo-items (filterv #(combo-ids (:product-id %)) items)
                        other-items (filterv #(not (combo-ids (:product-id %))) items)
                        discounted  (distribute-discount combo-items 75.0)]
                    (assoc data :expanded-items (into discounted other-items)))
                  data)))
   :schema {:input  [:map [:expanded-items [:vector :any]]]
            :output [:map [:expanded-items [:vector :any]]]}})

;; ── Cell 3: apply-promotions ────────────────────────────────────────────

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

(defn- calc-order-pct-discount
  "Determine the winning order-level percentage: max of coupon% and tiered%."
  [subtotal coupon coupons]
  (let [tiered-pct (cond
                     (>= subtotal 1000) 10
                     (>= subtotal 500)  5
                     :else              0)
        coupon-pct (if (and coupon
                            (get coupons coupon)
                            (= :percentage (:type (get coupons coupon))))
                     (:value (get coupons coupon))
                     0)]
    (max tiered-pct coupon-pct)))

(defn- apply-order-pct-discount
  [items coupon coupons]
  (let [subtotal (round2 (reduce + (map :current-price items)))
        pct      (calc-order-pct-discount subtotal coupon coupons)
        discount (if (pos? pct)
                   (round2 (* subtotal (/ pct 100.0)))
                   0.0)]
    (distribute-discount items discount)))

(defn- apply-fixed-coupon
  [items coupon coupons]
  (if (and coupon
           (get coupons coupon)
           (= :fixed (:type (get coupons coupon))))
    (let [discount (min (:value (get coupons coupon))
                        (reduce + (map :current-price items)))]
      (distribute-discount items (double discount)))
    items))

(defmethod cell/cell-spec :order/apply-promotions [_]
  {:id      :order/apply-promotions
   :handler (fn [_ data]
              (let [items   (:expanded-items data)
                    coupon  (:coupon data)
                    coupons (:coupons data)
                    ;; Category promotions
                    after-elec10  (apply-elec10 items)
                    after-bundle5 (apply-bundle5 after-elec10)
                    ;; Order-level percentage discount
                    after-pct     (apply-order-pct-discount after-bundle5 coupon coupons)
                    ;; Fixed coupon
                    after-fixed   (apply-fixed-coupon after-pct coupon coupons)]
                (assoc data :expanded-items after-fixed)))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:coupon :any]
                     [:coupons :any]]
            :output [:map [:expanded-items [:vector :any]]]}})

;; ── Cell 4: apply-loyalty-redemption ────────────────────────────────────

(defmethod cell/cell-spec :order/apply-loyalty-redemption [_]
  {:id      :order/apply-loyalty-redemption
   :handler (fn [_ data]
              (let [items          (:expanded-items data)
                    loyalty-points (:loyalty-points data)]
                (if (pos? loyalty-points)
                  (let [max-value  (* (/ loyalty-points 100.0) 5.0)
                        subtotal   (reduce + (map :current-price items))
                        redemption (round2 (min max-value subtotal))
                        new-items  (distribute-discount items redemption)
                        disc-sub   (round2 (reduce + (map :current-price new-items)))]
                    (assoc data
                           :expanded-items      new-items
                           :redemption-amount   redemption
                           :discounted-subtotal disc-sub))
                  (let [disc-sub (round2 (reduce + (map :current-price items)))]
                    (assoc data
                           :expanded-items      items
                           :redemption-amount   0.0
                           :discounted-subtotal disc-sub)))))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:loyalty-points :int]]
            :output [:map
                     [:expanded-items [:vector :any]]
                     [:redemption-amount :double]
                     [:discounted-subtotal :double]]}})

;; ── Cell 5: calc-tax ────────────────────────────────────────────────────

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

(defmethod cell/cell-spec :order/calc-tax [_]
  {:id      :order/calc-tax
   :handler (fn [_ data]
              (let [items      (:expanded-items data)
                    state      (:state data)
                    tax-rates  (:tax-rates data)
                    with-tax   (mapv (fn [item]
                                      (assoc item :tax-amount (calc-item-tax item state tax-rates)))
                                    items)
                    total-tax  (round2 (reduce + (map :tax-amount with-tax)))]
                (assoc data
                       :items-with-tax with-tax
                       :total-tax      total-tax)))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:state :string]
                     [:tax-rates :any]]
            :output [:map
                     [:items-with-tax [:vector :any]]
                     [:total-tax :double]]}})

;; ── Cell 6: calc-shipping ───────────────────────────────────────────────

(defmethod cell/cell-spec :order/calc-shipping [_]
  {:id      :order/calc-shipping
   :handler (fn [_ data]
              (let [items      (:expanded-items data)
                    membership (:membership data)
                    groups     (group-by :warehouse items)
                    result     (reduce
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
                                groups)]
                (assoc data
                       :total-shipping  (round2 (:total result))
                       :shipping-groups (:groups result))))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:membership :keyword]]
            :output [:map
                     [:total-shipping :double]
                     [:shipping-groups :any]]}})

;; ── Cell 7: compute-total ───────────────────────────────────────────────

(defmethod cell/cell-spec :order/compute-total [_]
  {:id      :order/compute-total
   :handler (fn [_ data]
              (let [total (round2 (+ (:discounted-subtotal data)
                                     (:total-tax data)
                                     (:total-shipping data)))]
                (assoc data :total total)))
   :schema {:input  [:map
                     [:discounted-subtotal :double]
                     [:total-tax :double]
                     [:total-shipping :double]]
            :output [:map [:total :double]]}})

;; ── Cell 8: fraud-check ─────────────────────────────────────────────────

(defmethod cell/cell-spec :order/fraud-check [_]
  {:id      :order/fraud-check
   :handler (fn [_ data]
              (let [total (:total data)]
                (if (> total 5000)
                  (assoc data
                         :fraud-status :reject
                         :order-error  "Order rejected: fraud check failed")
                  (assoc data :fraud-status :approve))))
   :schema {:input  [:map [:total :double]]
            :output {:approved [:map [:fraud-status :keyword]]
                     :reject   [:map [:fraud-status :keyword]
                                     [:order-error :string]]}}})

;; ── Cell 9: reserve-inventory ───────────────────────────────────────────

(defmethod cell/cell-spec :order/reserve-inventory [_]
  {:id       :order/reserve-inventory
   :handler  (fn [{:keys [inventory]} data]
               (let [items   (:items data)
                     qty-map (reduce (fn [m item-map]
                                       (let [[pid qty] (first item-map)]
                                         (update m pid (fnil + 0) qty)))
                                     {} items)]
                 (doseq [[pid qty] qty-map]
                   (swap! inventory update pid - qty))
                 (assoc data :inventory-reserved true)))
   :schema   {:input  [:map [:items [:vector :any]]]
              :output [:map [:inventory-reserved :boolean]]}
   :requires [:inventory]})

;; ── Cell 10: process-payment ────────────────────────────────────────────

(defmethod cell/cell-spec :order/process-payment [_]
  {:id      :order/process-payment
   :handler (fn [_ data]
              (let [total            (:total data)
                    card             (:card data)
                    gift-card-balance (:gift-card-balance data)
                    gc-charge        (if (pos? gift-card-balance)
                                      (round2 (min gift-card-balance total))
                                      0.0)
                    cc-charge        (round2 (- total gc-charge))]
                (if (and (pos? cc-charge)
                         (= \5 (first card)))
                  (assoc data
                         :payment-status :declined
                         :order-error    "Payment declined")
                  (assoc data
                         :payment        {:gift-card-charged   gc-charge
                                          :credit-card-charged cc-charge}
                         :payment-status :approved))))
   :schema {:input  [:map
                     [:total :double]
                     [:card :string]
                     [:gift-card-balance :double]]
            :output {:approved [:map
                                [:payment :any]
                                [:payment-status :keyword]]
                     :declined [:map
                                [:payment-status :keyword]
                                [:order-error :string]]}}})

;; ── Cell 11: rollback-inventory ─────────────────────────────────────────

(defmethod cell/cell-spec :order/rollback-inventory [_]
  {:id       :order/rollback-inventory
   :handler  (fn [{:keys [inventory]} data]
               (let [items   (:items data)
                     qty-map (reduce (fn [m item-map]
                                       (let [[pid qty] (first item-map)]
                                         (update m pid (fnil + 0) qty)))
                                     {} items)]
                 (doseq [[pid qty] qty-map]
                   (swap! inventory update pid + qty))
                 (assoc data
                        :inventory-reserved false
                        :order-error        "Payment declined")))
   :schema   {:input  [:map [:items [:vector :any]]]
              :output [:map
                       [:inventory-reserved :boolean]
                       [:order-error :string]]}
   :requires [:inventory]})

;; ── Cell 12: finalize-result ────────────────────────────────────────────

(defmethod cell/cell-spec :order/finalize-result [_]
  {:id      :order/finalize-result
   :handler (fn [_ data]
              (let [items-with-tax      (:items-with-tax data)
                    discounted-subtotal (:discounted-subtotal data)
                    membership          (:membership data)
                    redemption-amount   (:redemption-amount data)
                    items-detail        (mapv (fn [item]
                                               {:product-id     (:product-id item)
                                                :original-price (:price item)
                                                :final-price    (:current-price item)
                                                :tax-amount     (:tax-amount item)
                                                :warehouse      (:warehouse item)})
                                             items-with-tax)
                    points-earned       (long (Math/floor (* discounted-subtotal
                                                            (tier-multiplier membership))))]
                (assoc data
                       :items-detail  items-detail
                       :points-earned points-earned
                       :order-status  :success)))
   :schema {:input  [:map
                     [:items-with-tax [:vector :any]]
                     [:discounted-subtotal :double]
                     [:membership :keyword]
                     [:redemption-amount :double]]
            :output [:map
                     [:items-detail [:vector :any]]
                     [:points-earned :int]
                     [:order-status :keyword]]}})

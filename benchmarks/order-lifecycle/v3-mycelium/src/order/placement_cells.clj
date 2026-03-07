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

(def ^:private gift-wrap-cost
  {:books 2.99 :digital 2.99})

(defn- gift-wrap-cost-per-item [item]
  (get gift-wrap-cost (:category item) 4.99))

(def ^:private warranty-pricing
  {:electronics 49.99
   :clothing    9.99
   :bundle      59.99})

;; ── Shipping helpers (reused by calc-shipping and auto-upgrade) ─────────

(defn- weight-tier-cost [weight]
  (cond
    (<= weight 2.0)  5.99
    (<= weight 10.0) 8.99
    (<= weight 20.0) 12.99
    :else            (round2 (+ 15.99 (* 0.25 (- weight 20.0))))))

(defn- compute-shipping-for-items
  [items membership]
  (let [groups (group-by :warehouse items)
        result (reduce
                 (fn [acc [warehouse group-items]]
                   (if (= "digital" warehouse)
                     (assoc-in acc [:groups warehouse]
                               {:base-cost 0.0 :hazmat-cost 0.0
                                :oversized-cost 0.0 :cost 0.0 :subtotal 0.0})
                     (let [group-subtotal (reduce + (map :current-price group-items))
                           group-weight   (reduce + (map :weight group-items))
                           base-cost      (weight-tier-cost group-weight)
                           hazmat-count   (count (filter #(#{:electronics :bundle} (:category %))
                                                         group-items))
                           hazmat-cost    (* 3.0 hazmat-count)
                           oversized?     (some #(> (:weight %) 4.0) group-items)
                           oversized-cost (if oversized? 5.0 0.0)
                           platinum?      (= :platinum membership)
                           free-base?     (or platinum?
                                             (= :gold membership)
                                             (>= group-subtotal 75.0))
                           actual-base    (if free-base? 0.0 base-cost)
                           actual-hazmat  (if platinum? 0.0 hazmat-cost)
                           actual-oversized (if platinum? 0.0 oversized-cost)
                           total-cost     (round2 (+ actual-base actual-hazmat actual-oversized))]
                       (-> acc
                           (update :total + total-cost)
                           (assoc-in [:groups warehouse]
                                     {:base-cost actual-base
                                      :hazmat-cost actual-hazmat
                                      :oversized-cost actual-oversized
                                      :cost total-cost
                                      :subtotal group-subtotal})))))
                 {:total 0.0 :groups {}}
                 groups)]
    {:total-shipping (round2 (:total result))
     :shipping-groups (:groups result)}))

;; ── Cell 1: expand-items ────────────────────────────────────────────────

(defmethod cell/cell-spec :order/expand-items [_]
  {:id      :order/expand-items
   :handler (fn [_ data]
              (let [items   (:items data)
                    catalog (:catalog data)
                    expanded (vec
                              (mapcat
                               (fn [item-map]
                                 (let [[pid v]      (first item-map)
                                       qty          (if (map? v) (:qty v) v)
                                       gift-wrap    (if (map? v) (boolean (:gift-wrap v)) false)
                                       warranty     (if (map? v) (boolean (:warranty v)) false)
                                       subscription (if (map? v) (boolean (:subscription v)) false)
                                       product      (get catalog pid)]
                                   (repeat qty (assoc product
                                                      :product-id pid
                                                      :current-price (:price product)
                                                      :gift-wrap gift-wrap
                                                      :warranty warranty
                                                      :subscription subscription))))
                               items))]
                (assoc data :expanded-items expanded)))
   :schema {:input  [:map
                     [:items [:vector :any]]
                     [:catalog :any]
                     [:coupon :any]
                     [:coupons :any]
                     [:membership :keyword]
                     [:state :string]
                     [:county :any]
                     [:card :string]
                     [:gift-card-balance :double]
                     [:store-credit-balance :double]
                     [:loyalty-points :int]
                     [:tax-rates :any]
                     [:county-tax-rules :any]
                     [:currency :string]
                     [:lifetime-spend :double]]
            :output [:map
                     [:expanded-items [:vector :any]]]}})

;; ── Cell 2: apply-subscription-pricing ─────────────────────────────────

(defmethod cell/cell-spec :order/apply-subscription-pricing [_]
  {:id      :order/apply-subscription-pricing
   :handler (fn [_ data]
              (let [items (:expanded-items data)
                    updated (mapv (fn [item]
                                    (if (:subscription item)
                                      (let [disc (round2 (* (:price item) 0.15))]
                                        (assoc item :current-price (round2 (- (:price item) disc))))
                                      item))
                                  items)]
                (assoc data :expanded-items updated)))
   :schema {:input  [:map [:expanded-items [:vector :any]]]
            :output [:map [:expanded-items [:vector :any]]]}})

;; ── Cell 3: apply-bulk-pricing ──────────────────────────────────────────

(defmethod cell/cell-spec :order/apply-bulk-pricing [_]
  {:id      :order/apply-bulk-pricing
   :handler (fn [_ data]
              (let [items  (:expanded-items data)
                    counts (frequencies (map :product-id items))
                    updated (mapv (fn [item]
                                    (let [n    (get counts (:product-id item))
                                          rate (cond
                                                 (>= n 5) 0.10
                                                 (>= n 3) 0.05
                                                 :else    0.0)]
                                      (if (pos? rate)
                                        (let [disc (round2 (* (:current-price item) rate))]
                                          (assoc item :current-price (round2 (- (:current-price item) disc))))
                                        item)))
                                  items)]
                (assoc data :expanded-items updated)))
   :schema {:input  [:map [:expanded-items [:vector :any]]]
            :output [:map [:expanded-items [:vector :any]]]}})

;; ── Cell 4: apply-combo75 ───────────────────────────────────────────────

(defmethod cell/cell-spec :order/apply-combo75 [_]
  {:id      :order/apply-combo75
   :handler (fn [_ data]
              (let [items       (:expanded-items data)
                    combo-ids   #{"laptop" "headphones" "novel"}
                    ;; Only non-subscription standalone items can satisfy COMBO75
                    eligible    (filter #(and (combo-ids (:product-id %))
                                             (not (:subscription %)))
                                        items)
                    eligible-pids (set (map :product-id eligible))]
                (if (every? eligible-pids combo-ids)
                  (let [combo-items (filterv #(and (combo-ids (:product-id %))
                                                    (not (:subscription %)))
                                             items)
                        other-items (filterv #(or (not (combo-ids (:product-id %)))
                                                   (:subscription %))
                                             items)
                        discounted  (distribute-discount combo-items 75.0)]
                    (assoc data :expanded-items (into discounted other-items)))
                  data)))
   :schema {:input  [:map [:expanded-items [:vector :any]]]
            :output [:map [:expanded-items [:vector :any]]]}})

;; ── Cell 5: apply-promotions ────────────────────────────────────────────

(defn- apply-elec10 [items]
  ;; Bundles have :category :bundle so naturally excluded from :electronics count
  (if (>= (count-category items :electronics) 2)
    (mapv (fn [item]
            (if (= :electronics (:category item))
              (let [disc (round2 (* (:current-price item) 0.10))]
                (assoc item :current-price (round2 (- (:current-price item) disc))))
              item))
          items)
    items))

(defn- apply-bundle5 [items]
  ;; Bundles have :category :bundle so naturally excluded from counts
  (if (and (>= (count-category items :electronics) 1)
           (>= (count-category items :books) 1))
    (mapv (fn [item]
            (if (#{:electronics :books} (:category item))
              (let [disc (round2 (* (:current-price item) 0.05))]
                (assoc item :current-price (round2 (- (:current-price item) disc))))
              item))
          items)
    items))

(defn- calc-order-pct-discount [subtotal coupon coupons]
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

(defn- apply-order-pct-discount [items coupon coupons]
  (let [subtotal (round2 (reduce + (map :current-price items)))
        pct      (calc-order-pct-discount subtotal coupon coupons)
        discount (if (pos? pct)
                   (round2 (* subtotal (/ pct 100.0)))
                   0.0)]
    (distribute-discount items discount)))

(defn- apply-fixed-coupon [items coupon coupons]
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
                    after-elec10  (apply-elec10 items)
                    after-bundle5 (apply-bundle5 after-elec10)
                    after-pct     (apply-order-pct-discount after-bundle5 coupon coupons)
                    after-fixed   (apply-fixed-coupon after-pct coupon coupons)]
                (assoc data :expanded-items after-fixed)))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:coupon :any]
                     [:coupons :any]]
            :output [:map [:expanded-items [:vector :any]]]}})

;; ── Cell 6: apply-loyalty-redemption ────────────────────────────────────

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

;; ── Cell 7: calc-tax ────────────────────────────────────────────────────

(defn- tax-rate-for-item
  [item state county tax-rates county-tax-rules catalog]
  (let [rules         (get tax-rates state)
        base          (:base rules 0.0)
        category      (:category item)
        price         (:current-price item)
        ;; For bundles, use the highest component category rate
        effective-cat (if (= :bundle category)
                        (let [components (:components item)
                              comp-cats  (map (fn [[comp-pid _]]
                                                (:category (get catalog comp-pid)))
                                              components)]
                          (if (some #{:electronics} comp-cats) :electronics (first comp-cats)))
                        category)]
    (let [state-exempt? (cond
                          (zero? base) true
                          (and (= "NY" state) (= :clothing effective-cat)
                               (:clothing-exempt-under rules)
                               (< price (:clothing-exempt-under rules)))
                          true
                          (and (= "NY" state) (= :books effective-cat)
                               (:books-exempt rules false))
                          true
                          (and (= "TX" state) (= :digital effective-cat)
                               (:digital-exempt rules false))
                          true
                          :else false)
          state-rate    (if state-exempt?
                          0.0
                          (if (and (= "CA" state) (= :electronics effective-cat))
                            (+ base (:electronics-surcharge rules 0.0))
                            base))
          county-rules  (when county
                          (get-in county-tax-rules [state county]))
          county-surcharge (or (:surcharge county-rules) 0.0)
          county-overrides (:overrides county-rules)]
      (cond
        (nil? county-rules)
        state-rate

        (and state-exempt?
             county-overrides
             (= :not-exempt (get county-overrides effective-cat)))
        (let [full-state-rate (if (and (= "CA" state) (= :electronics effective-cat))
                                (+ base (:electronics-surcharge rules 0.0))
                                base)]
          (+ full-state-rate county-surcharge))

        state-exempt?
        0.0

        (and county-overrides
             (= :exempt (get county-overrides effective-cat)))
        state-rate

        :else
        (+ state-rate county-surcharge)))))

(defn- calc-item-tax [item state county tax-rates county-tax-rules catalog]
  (let [rate (tax-rate-for-item item state county tax-rates county-tax-rules catalog)]
    (round2 (* (:current-price item) rate))))

(defmethod cell/cell-spec :order/calc-tax [_]
  {:id      :order/calc-tax
   :handler (fn [_ data]
              (let [items            (:expanded-items data)
                    state            (:state data)
                    county           (:county data)
                    tax-rates        (:tax-rates data)
                    county-tax-rules (:county-tax-rules data)
                    catalog          (:catalog data)
                    with-tax   (mapv (fn [item]
                                      (assoc item :tax-amount
                                             (calc-item-tax item state county
                                                            tax-rates county-tax-rules catalog)))
                                    items)
                    total-tax  (round2 (reduce + (map :tax-amount with-tax)))]
                (assoc data
                       :items-with-tax with-tax
                       :total-tax      total-tax)))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:state :string]
                     [:county :any]
                     [:tax-rates :any]
                     [:county-tax-rules :any]
                     [:catalog :any]]
            :output [:map
                     [:items-with-tax [:vector :any]]
                     [:total-tax :double]]}})

;; ── Cell 8: calc-shipping ───────────────────────────────────────────────

(defmethod cell/cell-spec :order/calc-shipping [_]
  {:id      :order/calc-shipping
   :handler (fn [_ data]
              (let [items      (:expanded-items data)
                    membership (:membership data)
                    result     (compute-shipping-for-items items membership)]
                (assoc data
                       :total-shipping  (:total-shipping result)
                       :shipping-groups (:shipping-groups result))))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:membership :keyword]]
            :output [:map
                     [:total-shipping :double]
                     [:shipping-groups :any]]}})

;; ── Cell 9: calc-gift-wrap ──────────────────────────────────────────────

(defmethod cell/cell-spec :order/calc-gift-wrap [_]
  {:id      :order/calc-gift-wrap
   :handler (fn [_ data]
              (let [items         (:expanded-items data)
                    state         (:state data)
                    wrapped-items (filter :gift-wrap items)
                    wrap-total    (round2 (reduce + 0.0 (map gift-wrap-cost-per-item wrapped-items)))
                    tax-rate      (if (= "OR" state) 0.0 0.08)
                    wrap-tax      (round2 (* wrap-total tax-rate))]
                (assoc data
                       :gift-wrap-total wrap-total
                       :gift-wrap-tax   wrap-tax)))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:state :string]]
            :output [:map
                     [:gift-wrap-total :double]
                     [:gift-wrap-tax :double]]}})

;; ── Cell 10: calc-warranty ──────────────────────────────────────────────

(defmethod cell/cell-spec :order/calc-warranty [_]
  {:id      :order/calc-warranty
   :handler (fn [_ data]
              (let [items           (:expanded-items data)
                    state           (:state data)
                    warranted-items (filter (fn [item]
                                              (and (:warranty item)
                                                   (get warranty-pricing (:category item))))
                                            items)
                    warranty-total  (round2 (reduce + 0.0
                                                    (map #(get warranty-pricing (:category %))
                                                         warranted-items)))
                    tax-rate        (if (= "OR" state) 0.0 0.08)
                    warranty-tax    (round2 (* warranty-total tax-rate))]
                (assoc data
                       :warranty-total warranty-total
                       :warranty-tax   warranty-tax)))
   :schema {:input  [:map
                     [:expanded-items [:vector :any]]
                     [:state :string]]
            :output [:map
                     [:warranty-total :double]
                     [:warranty-tax :double]]}})

;; ── Cell 11: compute-total ──────────────────────────────────────────────

(defmethod cell/cell-spec :order/compute-total [_]
  {:id      :order/compute-total
   :handler (fn [_ data]
              (let [total (round2 (+ (:discounted-subtotal data)
                                     (:total-tax data)
                                     (:total-shipping data)
                                     (:gift-wrap-total data)
                                     (:gift-wrap-tax data)
                                     (:warranty-total data)
                                     (:warranty-tax data)))]
                (assoc data :total total)))
   :schema {:input  [:map
                     [:discounted-subtotal :double]
                     [:total-tax :double]
                     [:total-shipping :double]
                     [:gift-wrap-total :double]
                     [:gift-wrap-tax :double]
                     [:warranty-total :double]
                     [:warranty-tax :double]]
            :output [:map [:total :double]]}})

;; ── Cell 12: auto-upgrade-tier ──────────────────────────────────────────

(defmethod cell/cell-spec :order/auto-upgrade-tier [_]
  {:id      :order/auto-upgrade-tier
   :handler (fn [_ data]
              (let [disc-sub   (:discounted-subtotal data)
                    lifetime   (:lifetime-spend data)
                    membership (:membership data)
                    combined   (+ lifetime disc-sub)
                    next-tier  (case membership
                                 :bronze (when (>= combined 500) :silver)
                                 :silver (when (>= combined 2000) :gold)
                                 nil)]
                (if next-tier
                  (let [items      (:expanded-items data)
                        ship       (compute-shipping-for-items items next-tier)
                        old-ship   (:total-shipping data)
                        new-ship   (:total-shipping ship)
                        ship-delta (- new-ship old-ship)
                        new-total  (round2 (+ (:total data) ship-delta))]
                    (assoc data
                           :membership     next-tier
                           :tier-upgraded  true
                           :total-shipping new-ship
                           :shipping-groups (:shipping-groups ship)
                           :total          new-total))
                  (assoc data :tier-upgraded false))))
   :schema {:input  [:map
                     [:discounted-subtotal :double]
                     [:total :double]
                     [:total-shipping :double]
                     [:shipping-groups :any]
                     [:membership :keyword]
                     [:lifetime-spend :double]
                     [:expanded-items [:vector :any]]]
            :output [:map
                     [:total :double]
                     [:total-shipping :double]
                     [:shipping-groups :any]
                     [:membership :keyword]
                     [:tier-upgraded :boolean]]}})

;; ── Cell 13: fraud-check ────────────────────────────────────────────────

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

;; ── Cell 14: reserve-inventory ──────────────────────────────────────────

(defn- extract-qty [v] (if (map? v) (:qty v) v))

(defmethod cell/cell-spec :order/reserve-inventory [_]
  {:id       :order/reserve-inventory
   :handler  (fn [{:keys [inventory]} data]
               (let [items      (:items data)
                     catalog    (:catalog data)
                     items-tax  (:items-with-tax data)
                     qty-map    (reduce (fn [m item-map]
                                          (let [[pid v] (first item-map)]
                                            (update m pid (fnil + 0) (extract-qty v))))
                                        {} items)
                     inv        @inventory
                     fulfillment-split
                     (reduce (fn [acc [pid requested]]
                               (let [product    (get catalog pid)
                                     is-bundle? (= :bundle (:category product))
                                     available  (if is-bundle?
                                                  (let [components (:components product)]
                                                    (reduce (fn [min-a [comp-pid comp-qty]]
                                                              (min min-a
                                                                   (long (/ (get inv comp-pid 0) comp-qty))))
                                                            Long/MAX_VALUE
                                                            components))
                                                  (get inv pid 0))
                                     fulfilled   (min requested available)
                                     backordered (- requested fulfilled)]
                                 (assoc acc pid {:requested requested
                                                 :fulfilled fulfilled
                                                 :backordered backordered
                                                 :is-bundle? is-bundle?
                                                 :product product})))
                             {}
                             qty-map)
                     _ (doseq [[pid {:keys [fulfilled is-bundle? product]}] fulfillment-split]
                         (when (pos? fulfilled)
                           (if is-bundle?
                             (doseq [[comp-pid comp-qty] (:components product)]
                               (swap! inventory update comp-pid - (* comp-qty fulfilled)))
                             (swap! inventory update pid - fulfilled))))
                     fulfilled-counts (atom (reduce-kv (fn [m pid {:keys [fulfilled]}]
                                                         (assoc m pid fulfilled))
                                                       {} fulfillment-split))
                     split-items (reduce (fn [acc item]
                                           (let [pid (:product-id item)
                                                 remaining (get @fulfilled-counts pid 0)]
                                             (if (pos? remaining)
                                               (do (swap! fulfilled-counts update pid dec)
                                                   (update acc :fulfilled conj item))
                                               (update acc :backordered conj item))))
                                         {:fulfilled [] :backordered []}
                                         items-tax)
                     has-backordered? (seq (:backordered split-items))
                     fulfilled-subtotal (round2 (reduce + 0.0 (map :current-price (:fulfilled split-items))))
                     backordered-subtotal (round2 (reduce + 0.0 (map :current-price (:backordered split-items))))
                     fulfilled-tax (round2 (reduce + 0.0 (map :tax-amount (:fulfilled split-items))))
                     backordered-tax (round2 (reduce + 0.0 (map :tax-amount (:backordered split-items))))
                     fulfilled-ship (if has-backordered?
                                      (compute-shipping-for-items
                                       (:fulfilled split-items) (:membership data))
                                      nil)
                     actual-shipping (if has-backordered?
                                       (:total-shipping fulfilled-ship)
                                       (:total-shipping data))
                     actual-ship-groups (if has-backordered?
                                          (:shipping-groups fulfilled-ship)
                                          (:shipping-groups data))
                     gw-total (:gift-wrap-total data)
                     gw-tax   (:gift-wrap-tax data)
                     w-total  (:warranty-total data)
                     w-tax    (:warranty-tax data)
                     fulfilled-charge (round2 (+ fulfilled-subtotal fulfilled-tax
                                                  actual-shipping gw-total gw-tax w-total w-tax))
                     backorder-hold (round2 (+ backordered-subtotal backordered-tax))
                     fulfillment (if has-backordered?
                                   {:status :partial
                                    :fulfilled-items (:fulfilled split-items)
                                    :backordered-items (:backordered split-items)
                                    :fulfilled-subtotal fulfilled-subtotal
                                    :backordered-subtotal backordered-subtotal
                                    :fulfilled-charge fulfilled-charge
                                    :backorder-hold backorder-hold}
                                   {:status :full
                                    :fulfilled-items items-tax})]
                 (cond-> (assoc data
                                :inventory-reserved true
                                :fulfillment fulfillment)
                   has-backordered?
                   (assoc :total-shipping actual-shipping
                          :shipping-groups actual-ship-groups
                          :total (round2 (+ fulfilled-charge backorder-hold))))))
   :schema   {:input  [:map
                        [:items [:vector :any]]
                        [:expanded-items [:vector :any]]
                        [:catalog :any]]
               :output [:map
                        [:inventory-reserved :boolean]
                        [:fulfillment :any]]}
   :requires [:inventory]})

;; ── Cell 15: process-payment ────────────────────────────────────────────

(defmethod cell/cell-spec :order/process-payment [_]
  {:id      :order/process-payment
   :handler (fn [_ data]
              (let [total                (:total data)
                    card                 (:card data)
                    gift-card-balance    (:gift-card-balance data)
                    store-credit-balance (:store-credit-balance data)
                    fulfillment          (:fulfillment data)
                    gc-charge  (if (pos? gift-card-balance)
                                 (round2 (min gift-card-balance total))
                                 0.0)
                    after-gc   (- total gc-charge)
                    sc-charge  (if (pos? store-credit-balance)
                                 (round2 (min store-credit-balance after-gc))
                                 0.0)
                    cc-charge  (round2 (- after-gc sc-charge))]
                (if (and (pos? cc-charge)
                         (= \5 (first card)))
                  (assoc data
                         :payment-status :declined
                         :order-error    "Payment declined")
                  (let [payment (cond-> {:gift-card-charged    gc-charge
                                         :store-credit-charged sc-charge
                                         :credit-card-charged  cc-charge}
                                  (= :partial (:status fulfillment))
                                  (assoc :fulfilled-charge (:fulfilled-charge fulfillment)
                                         :backorder-hold   (:backorder-hold fulfillment)))]
                    (assoc data
                           :payment        payment
                           :payment-status :approved)))))
   :schema {:input  [:map
                     [:total :double]
                     [:card :string]
                     [:gift-card-balance :double]
                     [:store-credit-balance :double]
                     [:fulfillment :any]]
            :output {:approved [:map
                                [:payment :any]
                                [:payment-status :keyword]]
                     :declined [:map
                                [:payment-status :keyword]
                                [:order-error :string]]}}})

;; ── Cell 16: rollback-inventory ─────────────────────────────────────────

(defmethod cell/cell-spec :order/rollback-inventory [_]
  {:id       :order/rollback-inventory
   :handler  (fn [{:keys [inventory]} data]
               (let [items   (:items data)
                     catalog (:catalog data)
                     qty-map (reduce (fn [m item-map]
                                       (let [[pid v] (first item-map)]
                                         (update m pid (fnil + 0) (extract-qty v))))
                                     {} items)]
                 (doseq [[pid qty] qty-map]
                   (let [product (get catalog pid)]
                     (if (= :bundle (:category product))
                       (doseq [[comp-pid comp-qty] (:components product)]
                         (swap! inventory update comp-pid (fnil + 0) (* comp-qty qty)))
                       (swap! inventory update pid (fnil + 0) qty))))
                 (assoc data
                        :inventory-reserved false
                        :order-error        "Payment declined")))
   :schema   {:input  [:map
                        [:items [:vector :any]]
                        [:catalog :any]]
               :output [:map
                        [:inventory-reserved :boolean]
                        [:order-error :string]]}
   :requires [:inventory]})

;; ── Cell 17: finalize-result ────────────────────────────────────────────

(def ^:private currency-rates
  {"USD" 1.00 "EUR" 0.92 "GBP" 0.79 "CAD" 1.36})

(defmethod cell/cell-spec :order/finalize-result [_]
  {:id      :order/finalize-result
   :handler (fn [_ data]
              (let [items-with-tax      (:items-with-tax data)
                    discounted-subtotal (:discounted-subtotal data)
                    membership          (:membership data)
                    tier-upgraded       (:tier-upgraded data)
                    currency            (:currency data)
                    fulfillment         (:fulfillment data)
                    rate                (get currency-rates currency 1.00)
                    items-detail        (mapv (fn [item]
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
                    points-earned       (long (Math/floor (* discounted-subtotal
                                                            (tier-multiplier membership))))
                    has-subscription    (boolean (some :subscription items-with-tax))]
                (assoc data
                       :items-detail     items-detail
                       :points-earned    points-earned
                       :order-status     :success
                       :has-subscription has-subscription
                       :display-subtotal (round2 (* discounted-subtotal rate))
                       :display-tax      (round2 (* (:total-tax data) rate))
                       :display-shipping (round2 (* (:total-shipping data) rate))
                       :display-total    (round2 (* (:total data) rate)))))
   :schema {:input  [:map
                     [:items-with-tax [:vector :any]]
                     [:discounted-subtotal :double]
                     [:total-tax :double]
                     [:total-shipping :double]
                     [:gift-wrap-total :double]
                     [:gift-wrap-tax :double]
                     [:warranty-total :double]
                     [:warranty-tax :double]
                     [:total :double]
                     [:membership :keyword]
                     [:tier-upgraded :boolean]
                     [:redemption-amount :double]
                     [:currency :string]
                     [:fulfillment :any]]
            :output [:map
                     [:items-detail [:vector :any]]
                     [:points-earned :int]
                     [:order-status :keyword]
                     [:display-subtotal :double]
                     [:display-tax :double]
                     [:display-shipping :double]
                     [:display-total :double]
                     [:has-subscription :boolean]]}})

(ns order.returns)

(defn round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

(defn process-return
  "Process a return against an original order.
   original-order: result from place-order (with :items-detail, :payment, :loyalty, etc.)
   return-request: {:returned-items [{product-id qty}...], :reason :defective|:changed-mind}
   resources: {:inventory atom}"
  [original-order return-request resources]
  (let [{:keys [returned-items reason]} return-request
        {:keys [inventory]} resources

        ;; Build a map of product-id -> item detail from original order
        orig-items (:items-detail original-order)

        ;; Match returned items to original order details
        return-details
        (for [ret-map returned-items
              [pid qty] ret-map]
          (let [matching (filter #(= pid (:product-id %)) orig-items)]
            {:product-id  pid
             :quantity    qty
             :final-price (:final-price (first matching))
             :tax-amount  (:tax-amount (first matching))
             :warehouse   (:warehouse (first matching))}))

        ;; Step 1: Item refund (per-item final price × quantity)
        subtotal-refund (round2 (reduce + 0.0
                                  (map #(* (:final-price %) (:quantity %))
                                       return-details)))

        ;; Step 2: Tax refund (per-item tax × quantity)
        tax-refund (round2 (reduce + 0.0
                             (map #(* (:tax-amount %) (:quantity %))
                                  return-details)))

        ;; Step 3: Shipping refund
        shipping-refund
        (if (= :defective reason)
          ;; Proportional share of original warehouse shipping
          (round2
            (reduce + 0.0
              (for [{:keys [product-id final-price warehouse quantity]} return-details]
                (let [wh-shipping (get-in original-order [:warehouse-shipping warehouse] 0.0)
                      wh-subtotal (get-in original-order [:warehouse-subtotals warehouse] 1.0)]
                  (* quantity (round2 (* (/ final-price wh-subtotal) wh-shipping)))))))
          0.0)

        ;; Step 4: Total refund
        total-refund (round2 (+ subtotal-refund tax-refund shipping-refund))

        ;; Step 5: Loyalty clawback
        orig-subtotal (:discounted-subtotal original-order)
        orig-points   (get-in original-order [:loyalty :points-earned])
        clawback      (long (Math/floor (* (/ subtotal-refund orig-subtotal) orig-points)))

        ;; Step 6: Payment refund (reverse order: credit card first)
        orig-cc-charged (get-in original-order [:payment :credit-card-charged] 0.0)
        orig-gc-charged (get-in original-order [:payment :gift-card-charged] 0.0)
        cc-refund       (min total-refund orig-cc-charged)
        gc-refund       (round2 (- total-refund cc-refund))]

    ;; Restore inventory
    (when inventory
      (doseq [{:keys [product-id quantity]} return-details]
        (swap! inventory update product-id + quantity)))

    {:status           :success
     :subtotal-refund  subtotal-refund
     :tax-refund       tax-refund
     :shipping-refund  shipping-refund
     :total-refund     total-refund
     :loyalty-clawback clawback
     :payment          {:credit-card-refunded (round2 cc-refund)
                        :gift-card-refunded   (round2 gc-refund)}}))

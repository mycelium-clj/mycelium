(ns order.modification
  (:require [order.placement :as placement]))

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

(defn modify-order
  "Recomputes an order with new item quantities and returns the delta.

   Arguments:
     original-order - the result map from place-order
     modification   - {:changes [{\"product-id\" new-qty} ...]}
     resources      - {:catalog, :coupons, :tax-rates, :inventory (atom)}

   Returns a map with :status, :new-subtotal, :new-tax, :new-shipping,
   :new-total, :delta, :delta-action, :new-points, :points-delta."
  [original-order modification resources]
  (let [;; Build the new items list from changes
        new-items (:changes modification)

        ;; Use original order's membership and state, approved card, no extras
        new-order-input {:items             new-items
                         :coupon            nil
                         :membership        (:membership original-order)
                         :state             (:state original-order)
                         :card              "4111111111111111"
                         :gift-card-balance 0
                         :loyalty-points    0
                         :new-customer      false}

        ;; Recompute pricing with new quantities
        new-result (placement/place-order new-order-input resources)]

    (if (not= :success (:status new-result))
      ;; If the new order fails, propagate the error
      new-result

      ;; Calculate deltas
      (let [original-total  (round2 (:total original-order))
            new-total       (round2 (:total new-result))
            raw-delta       (- new-total original-total)
            delta           (round2 (Math/abs raw-delta))
            delta-action    (if (pos? raw-delta) :charge :refund)

            original-points (get-in original-order [:loyalty :points-earned])
            new-points      (get-in new-result [:loyalty :points-earned])
            points-delta    (Math/abs (- new-points original-points))]

        {:status       :success
         :new-subtotal (round2 (:discounted-subtotal new-result))
         :new-tax      (round2 (:tax new-result))
         :new-shipping (round2 (:shipping new-result))
         :new-total    new-total
         :delta        delta
         :delta-action delta-action
         :new-points   new-points
         :points-delta points-delta}))))

(ns order.modification
  (:require [order.placement :as placement]))

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

(def ^:private currency-rates
  {"USD" 1.00, "EUR" 0.92, "GBP" 0.79, "CAD" 1.36})

(defn modify-order
  "Recomputes an order with new items and returns the delta."
  [original-order modification resources]
  (let [new-items (:changes modification)
        currency (or (:currency original-order) "USD")
        new-order-input {:items                new-items
                         :coupon               nil
                         :membership           (:membership original-order)
                         :state                (:state original-order)
                         :county               (:county original-order)
                         :currency             currency
                         :card                 "4111111111111111"
                         :gift-card-balance    0
                         :store-credit-balance 0
                         :loyalty-points       0
                         :lifetime-spend       0
                         :new-customer         false}

        new-result (placement/place-order new-order-input resources)]

    (if (not= :success (:status new-result))
      new-result

      (let [original-total  (round2 (:total original-order))
            new-total       (round2 (:total new-result))
            raw-delta       (- new-total original-total)
            delta           (round2 (Math/abs raw-delta))
            delta-action    (if (pos? raw-delta) :charge :refund)

            original-points (get-in original-order [:loyalty :points-earned])
            new-points      (get-in new-result [:loyalty :points-earned])
            points-delta    (Math/abs (- new-points original-points))]

        (let [rate (get currency-rates currency 1.00)]
          {:status            :success
           :new-subtotal      (round2 (:discounted-subtotal new-result))
           :new-tax           (round2 (:tax new-result))
           :new-shipping      (round2 (:shipping new-result))
           :new-total         new-total
           :delta             delta
           :delta-action      delta-action
           :display-new-total (round2 (* new-total rate))
           :display-delta     (round2 (* delta rate))
           :new-points        new-points
           :points-delta      points-delta})))))

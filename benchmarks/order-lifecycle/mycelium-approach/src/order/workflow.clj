(ns order.workflow
  (:require [mycelium.core :as myc]
            [mycelium.manifest :as manifest]
            ;; Load cell implementations
            [order.placement-cells]
            [order.returns-cells]))

(defn- round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

;; ── Load manifests ────────────────────────────────────────────────────

(def placement-manifest
  (manifest/load-manifest
    (.getPath (clojure.java.io/resource "workflows/placement.edn"))))

(def placement-workflow
  (manifest/manifest->workflow placement-manifest))

(def returns-manifest
  (manifest/load-manifest
    (.getPath (clojure.java.io/resource "workflows/returns.edn"))))

(def returns-workflow
  (manifest/manifest->workflow returns-manifest))

;; ── Order Placement ───────────────────────────────────────────────────

(defn place-order
  "Place an order. Returns a result map matching the traditional API."
  [request resources]
  (let [{:keys [items coupon membership state card
                gift-card-balance loyalty-points]} request
        {:keys [catalog coupons tax-rates inventory]} resources
        initial-data {:items             items
                      :coupon            coupon
                      :membership        (or membership :bronze)
                      :state             state
                      :card              card
                      :gift-card-balance (double (or gift-card-balance 0))
                      :loyalty-points    (int (or loyalty-points 0))
                      :catalog           catalog
                      :coupons           coupons
                      :tax-rates         tax-rates}
        result (myc/run-workflow
                 placement-workflow
                 {:inventory inventory}
                 initial-data)]
    (if (:order-error result)
      {:status :error
       :error  (:order-error result)}
      {:status              :success
       :discounted-subtotal (:discounted-subtotal result)
       :tax                 (:total-tax result)
       :shipping            (:total-shipping result)
       :total               (:total result)
       :items-detail        (:items-detail result)
       :shipping-groups     (:shipping-groups result)
       :payment             (:payment result)
       :loyalty             {:points-earned     (:points-earned result)
                             :redemption-amount (:redemption-amount result)
                             :tier              (:membership result)}
       :state               state
       :membership          (or membership :bronze)})))

;; ── Returns Processing ────────────────────────────────────────────────

(defn process-return
  "Process a return. Returns a result map matching the traditional API."
  [original-order return-request resources]
  (let [{:keys [returned-items reason]} return-request
        {:keys [inventory]} resources
        ;; Feed placement output keys into returns workflow input
        initial-data {:items-detail        (:items-detail original-order)
                      :discounted-subtotal (:discounted-subtotal original-order)
                      :shipping-groups     (:shipping-groups original-order)
                      :payment             (:payment original-order)
                      :points-earned       (int (get-in original-order [:loyalty :points-earned] 0))
                      :returned-items      returned-items
                      :reason              reason}
        result (myc/run-workflow
                 returns-workflow
                 {:inventory inventory}
                 initial-data)]
    {:status           :success
     :subtotal-refund  (:subtotal-refund result)
     :tax-refund       (:tax-refund result)
     :shipping-refund  (:shipping-refund result)
     :total-refund     (:total-refund result)
     :loyalty-clawback (:loyalty-clawback result)
     :payment          (:payment-refund result)}))

;; ── Order Modification ────────────────────────────────────────────────

(defn modify-order
  "Modify an order by recomputing with new items and calculating deltas."
  [original-order modification resources]
  (let [new-items (:changes modification)
        new-order-input {:items             new-items
                         :coupon            nil
                         :membership        (:membership original-order)
                         :state             (:state original-order)
                         :card              "4111111111111111"
                         :gift-card-balance 0
                         :loyalty-points    0
                         :new-customer      false}
        new-result (place-order new-order-input resources)]
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
        {:status       :success
         :new-subtotal (round2 (:discounted-subtotal new-result))
         :new-tax      (round2 (:tax new-result))
         :new-shipping (round2 (:shipping new-result))
         :new-total    new-total
         :delta        delta
         :delta-action delta-action
         :new-points   new-points
         :points-delta points-delta}))))

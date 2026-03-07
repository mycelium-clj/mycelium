(ns checkout.workflow
  (:require [mycelium.core :as myc]
            [mycelium.manifest :as manifest]
            [checkout.cells]))

(def checkout-manifest
  (manifest/load-manifest
    (.getPath (clojure.java.io/resource "workflows/checkout.edn"))))

(def checkout-workflow
  (manifest/manifest->workflow checkout-manifest))

(defn process-checkout
  "Run the checkout pipeline.
   request: {:items, :coupon, :membership, :state, :card}
   resources: {:catalog, :coupons, :inventory, :tax-rates}"
  [request resources]
  (let [{:keys [items coupon membership state card]} request
        {:keys [catalog coupons inventory tax-rates]} resources
        initial-data {:items      items
                      :coupon     coupon
                      :membership (or membership :none)
                      :state      state
                      :card       card
                      :catalog    catalog
                      :coupons    coupons
                      :tax-rates  tax-rates}
        result (myc/run-workflow
                 checkout-workflow
                 {:inventory inventory}
                 initial-data)]
    (if (:checkout-error result)
      {:status :error :error (:checkout-error result)}
      {:status              :success
       :total               (:total result)
       :discounted-subtotal (:discounted-subtotal result)
       :tax                 (:tax result)
       :shipping            (:shipping result)
       :transaction-id      (:transaction-id result)
       :priority-fulfillment (:priority-fulfillment result)
       :coupon-warning      (:coupon-warning result)})))

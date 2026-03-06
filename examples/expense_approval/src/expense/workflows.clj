(ns expense.workflows
  (:require [expense.cells]
            [mycelium.core :as myc]))

(def expense-workflow
  {:cells {:start      :expense/validate
           :categorize :expense/categorize
           :auto       :expense/auto-approve
           :request    :expense/request-approval
           :decision   :expense/record-decision
           :notify     :expense/notify}

   :edges {:start      {:valid :categorize, :default :end}
           :categorize {:small :auto, :default :request}
           :auto       :notify
           :request    :decision
           :decision   :notify
           :notify     :end}

   :dispatches {:start      [[:valid (fn [d] (= :valid (:validation-status d)))]]
                :categorize [[:small (fn [d] (= :small (:category d)))]]}})

(def compiled (myc/pre-compile expense-workflow))

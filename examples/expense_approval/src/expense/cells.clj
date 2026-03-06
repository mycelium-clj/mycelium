(ns expense.cells
  (:require [mycelium.cell :as cell]))

;; --- Validate expense report ---

(defmethod cell/cell-spec :expense/validate [_]
  {:id      :expense/validate
   :handler (fn [_ data]
              (let [{:keys [amount description]} data]
                (cond
                  (nil? amount)
                  (assoc data :validation-status :invalid
                              :validation-error "Amount is required")

                  (not (pos? amount))
                  (assoc data :validation-status :invalid
                              :validation-error "Amount must be positive")

                  (empty? description)
                  (assoc data :validation-status :invalid
                              :validation-error "Description is required")

                  :else
                  (assoc data :validation-status :valid))))
   :schema {:input  [:map [:amount [:maybe number?]] [:description :string]]
            :output {:valid   [:map [:validation-status [:= :valid]]]
                     :invalid [:map [:validation-status [:= :invalid]]
                                    [:validation-error :string]]}}})

;; --- Categorize by amount ---

(defmethod cell/cell-spec :expense/categorize [_]
  {:id      :expense/categorize
   :handler (fn [_ data]
              (let [amount (:amount data)]
                (assoc data :category
                       (cond
                         (<= amount 100)  :small
                         (<= amount 1000) :medium
                         :else            :large))))
   :schema {:input  [:map [:amount number?]]
            :output [:map [:category [:enum :small :medium :large]]]}})

;; --- Auto-approve small expenses ---

(defmethod cell/cell-spec :expense/auto-approve [_]
  {:id      :expense/auto-approve
   :handler (fn [_ data]
              (assoc data :decision :approved
                          :approved-by "policy:auto-small"))
   :schema {:input  [:map]
            :output [:map [:decision [:= :approved]]
                          [:approved-by :string]]}})

;; --- Request manager approval (halts workflow) ---

(defmethod cell/cell-spec :expense/request-approval [_]
  {:id      :expense/request-approval
   :handler (fn [_ data]
              (assoc data :mycelium/halt
                     {:reason     :manager-approval-needed
                      :message    (str "Expense of $" (:amount data) " requires manager approval")
                      :expense-id (:expense-id data)
                      :amount     (:amount data)}))
   :schema {:input  [:map [:amount number?]]
            :output [:map]}})

;; --- Record manager decision (runs after resume) ---
;; :manager-approved and :manager-name come from resume merge-data,
;; so input uses open [:map] (schema chain can't track external input).

(defmethod cell/cell-spec :expense/record-decision [_]
  {:id      :expense/record-decision
   :handler (fn [_ data]
              (let [{:keys [manager-approved manager-name]} data]
                (if manager-approved
                  (assoc data :decision :approved
                              :approved-by (str "manager:" manager-name))
                  (assoc data :decision :rejected
                              :rejected-by (str "manager:" manager-name)
                              :rejection-reason (or (:rejection-reason data)
                                                    "Manager declined")))))
   :schema {:input  [:map]
            :output [:map [:decision [:enum :approved :rejected]]]}})

;; --- Send notification ---
;; :submitter comes from initial data (not tracked by schema chain).

(defmethod cell/cell-spec :expense/notify [_]
  {:id      :expense/notify
   :handler (fn [_ data]
              (assoc data :notification
                     {:to      (:submitter data)
                      :subject (str "Expense " (name (:decision data)))
                      :body    (if (= :approved (:decision data))
                                 (str "Your expense of $" (:amount data) " has been approved.")
                                 (str "Your expense of $" (:amount data) " was rejected: "
                                      (:rejection-reason data "")))}))
   :schema {:input  [:map [:decision [:enum :approved :rejected]]]
            :output [:map [:notification [:map [:to [:maybe :string]]
                                               [:subject :string]
                                               [:body :string]]]]}})

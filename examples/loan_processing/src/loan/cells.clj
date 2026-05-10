(ns loan.cells
  "Cell registrations for the loan processing example.
   13 cells covering validation, credit assessment, eligibility routing,
   status management, notifications, and audit logging."
  (:require [mycelium.cell :as cell]))

;; ===== Validation =====

(defmethod cell/cell-spec :loan/validate-application [_]
  {:id      :loan/validate-application
   :doc     "Validates that required fields are present on the loan application."
   :handler (fn [_resources data]
              (let [{:keys [applicant-name income loan-amount]} data]
                (if (and applicant-name income loan-amount
                         (string? applicant-name) (not (empty? applicant-name))
                         (number? income) (pos? income)
                         (number? loan-amount) (pos? loan-amount))
                  (assoc data :validation-status :valid)
                  (assoc data
                         :validation-status :invalid
                         :validation-errors
                         (cond-> []
                           (or (nil? applicant-name) (not (string? applicant-name)) (empty? applicant-name))
                           (conj "applicant-name is required")
                           (or (nil? income) (not (number? income)) (not (pos? income)))
                           (conj "income must be a positive number")
                           (or (nil? loan-amount) (not (number? loan-amount)) (not (pos? loan-amount)))
                           (conj "loan-amount must be a positive number"))))))
   :schema  {:input  [:map
                       [:applicant-name {:optional true} [:maybe :string]]
                       [:income {:optional true} [:maybe number?]]
                       [:loan-amount {:optional true} [:maybe number?]]]
             :output [:per-transition {:valid   [:map [:validation-status [:= :valid]]]
                      :invalid [:map
                                [:validation-status [:= :invalid]]
                                [:validation-errors [:vector :string]]]}]}})

;; ===== Credit Assessment (subworkflow cells) =====

(defmethod cell/cell-spec :loan/credit-bureau-lookup [_]
  {:id      :loan/credit-bureau-lookup
   :doc     "Looks up credit history from the credit bureau database."
   :handler (fn [{:keys [credit-db]} data]
              (let [name    (:applicant-name data)
                    history (get credit-db name)]
                (if history
                  (assoc data :credit-history history)
                  (assoc data :credit-history {:accounts 0 :late-payments 0
                                               :bankruptcies 0 :years-of-history 0}))))
   :schema  {:input  [:map [:applicant-name :string]]
             :output [:map [:credit-history [:map
                                             [:accounts :int]
                                             [:late-payments :int]
                                             [:bankruptcies :int]
                                             [:years-of-history :int]]]]}
   :requires [:credit-db]})

(defmethod cell/cell-spec :loan/calculate-score [_]
  {:id      :loan/calculate-score
   :doc     "Calculates a credit score (300-850) from credit history."
   :handler (fn [_resources data]
              (let [{:keys [accounts late-payments bankruptcies years-of-history]}
                    (:credit-history data)
                    ;; Simple scoring algorithm
                    base-score   500
                    account-bonus (min (* accounts 30) 150)
                    history-bonus (min (* years-of-history 20) 200)
                    late-penalty  (* late-payments 40)
                    bankrupt-penalty (* bankruptcies 150)
                    raw-score    (-> base-score
                                     (+ account-bonus)
                                     (+ history-bonus)
                                     (- late-penalty)
                                     (- bankrupt-penalty))
                    score        (max 300 (min 850 raw-score))]
                (assoc data :credit-score score)))
   :schema  {:input  [:map [:credit-history [:map
                                              [:accounts :int]
                                              [:late-payments :int]
                                              [:bankruptcies :int]
                                              [:years-of-history :int]]]]
             :output [:map [:credit-score :int]]}})

(defmethod cell/cell-spec :loan/classify-risk [_]
  {:id      :loan/classify-risk
   :doc     "Classifies risk level based on credit score."
   :handler (fn [_resources data]
              (let [score (:credit-score data)
                    risk  (cond
                            (>= score 720) :low
                            (>= score 620) :medium
                            :else          :high)]
                (assoc data :risk-level risk)))
   :schema  {:input  [:map [:credit-score :int]]
             :output [:map [:risk-level [:enum :low :medium :high]]]}})

;; ===== Eligibility Decision =====

(defmethod cell/cell-spec :loan/eligibility-decision [_]
  {:id      :loan/eligibility-decision
   :doc     "Routes application based on risk level and loan amount.
             Low risk + amount <= 50k → auto-approve
             High risk → auto-reject
             Everything else → manual review"
   :handler (fn [_resources data]
              (let [{:keys [risk-level loan-amount]} data]
                (cond
                  (= risk-level :high)
                  (assoc data :decision :reject)

                  (and (= risk-level :low) (<= loan-amount 50000))
                  (assoc data :decision :approve)

                  :else
                  (assoc data :decision :review))))
   :schema  {:input  [:map [:risk-level [:enum :low :medium :high]] [:loan-amount number?]]
             :output [:per-transition {:approve [:map [:decision [:= :approve]]]
                      :reject  [:map [:decision [:= :reject]]]
                      :review  [:map [:decision [:= :review]]]}]}})

;; ===== Decision Outcomes =====

(defmethod cell/cell-spec :loan/auto-approve [_]
  {:id      :loan/auto-approve
   :doc     "Sets approved status and calculates interest rate based on credit score."
   :handler (fn [_resources data]
              (let [score (:credit-score data)
                    rate  (cond
                            (>= score 780) 3.5
                            (>= score 740) 4.0
                            :else          4.5)]
                (assoc data
                       :application-status :approved
                       :interest-rate rate
                       :decision-reason "Auto-approved: low risk, standard amount")))
   :schema  {:input  [:map [:credit-score :int]]
             :output [:map
                      [:application-status [:= :approved]]
                      [:interest-rate :double]
                      [:decision-reason :string]]}})

(defmethod cell/cell-spec :loan/auto-reject [_]
  {:id      :loan/auto-reject
   :doc     "Sets rejected status with reason."
   :handler (fn [_resources data]
              (assoc data
                     :application-status :rejected
                     :decision-reason "Auto-rejected: high credit risk"))
   :schema  {:input  [:map [:risk-level [:enum :low :medium :high]]]
             :output [:map
                      [:application-status [:= :rejected]]
                      [:decision-reason :string]]}})

(defmethod cell/cell-spec :loan/queue-for-review [_]
  {:id      :loan/queue-for-review
   :doc     "Queues application for manual review with appropriate queue assignment."
   :handler (fn [_resources data]
              (let [{:keys [risk-level loan-amount]} data
                    queue (cond
                            (> loan-amount 75000) :senior
                            (= risk-level :medium) :standard
                            :else :standard)]
                (assoc data
                       :application-status :pending-review
                       :review-queue queue
                       :decision-reason (str "Queued for " (name queue) " review"))))
   :schema  {:input  [:map [:risk-level [:enum :low :medium :high]] [:loan-amount number?]]
             :output [:map
                      [:application-status [:= :pending-review]]
                      [:review-queue [:enum :standard :senior]]
                      [:decision-reason :string]]}})

;; ===== Shared Cells =====

(defmethod cell/cell-spec :loan/send-notification [_]
  {:id      :loan/send-notification
   :doc     "Generates a notification based on the current application status."
   :handler (fn [_resources data]
              (let [status (:application-status data)
                    msg    (case status
                             :approved       (str "Congratulations! Your loan has been approved at "
                                                  (:interest-rate data) "% interest.")
                             :rejected       (str "We're sorry, your loan application has been declined. "
                                                  (:decision-reason data))
                             :pending-review (str "Your application is under review. "
                                                  "You will be notified once a decision is made.")
                             (str "Application status: " (name status)))]
                (assoc data :notification {:to (:applicant-name data)
                                           :subject (str "Loan Application - " (name status))
                                           :body msg})))
   :schema  {:input  [:map [:application-status :keyword] [:applicant-name :string]]
             :output [:map [:notification [:map
                                           [:to :string]
                                           [:subject :string]
                                           [:body :string]]]]}})

(defmethod cell/cell-spec :loan/audit-log [_]
  {:id      :loan/audit-log
   :doc     "Appends an entry to the audit trail."
   :handler (fn [_resources data]
              (let [entry {:timestamp (System/currentTimeMillis)
                           :action    (or (:application-status data) :status-check)
                           :applicant (:applicant-name data)
                           :details   (select-keys data [:decision-reason :review-queue
                                                         :interest-rate :risk-level
                                                         :credit-score])}
                    trail (conj (or (:audit-trail data) []) entry)]
                (assoc data :audit-trail trail)))
   :schema  {:input  [:map [:applicant-name :string]]
             :output [:map [:audit-trail [:vector :map]]]}})

;; ===== Status Check Cells =====

(defmethod cell/cell-spec :loan/fetch-application [_]
  {:id      :loan/fetch-application
   :doc     "Looks up an application from the app store by applicant name."
   :handler (fn [{:keys [app-store]} data]
              (let [name (:applicant-name data)
                    app  (get @app-store name)]
                (if app
                  (merge data app {:fetch-status :found})
                  (assoc data :fetch-status :not-found
                              :error-message (str "No application found for " name)))))
   :schema  {:input  [:map [:applicant-name :string]]
             :output [:per-transition {:found     [:map [:fetch-status [:= :found]]]
                      :not-found [:map
                                  [:fetch-status [:= :not-found]]
                                  [:error-message :string]]}]}
   :requires [:app-store]})

(defmethod cell/cell-spec :loan/format-status [_]
  {:id      :loan/format-status
   :doc     "Formats a human-readable status report."
   :handler (fn [_resources data]
              (let [{:keys [applicant-name application-status loan-amount
                            credit-score interest-rate review-queue]} data
                    report (str "=== Loan Application Status ===\n"
                                "Applicant: " applicant-name "\n"
                                "Status: " (when application-status (name application-status)) "\n"
                                (when loan-amount (str "Amount: $" loan-amount "\n"))
                                (when credit-score (str "Credit Score: " credit-score "\n"))
                                (when interest-rate (str "Interest Rate: " interest-rate "%\n"))
                                (when review-queue (str "Review Queue: " (name review-queue) "\n")))]
                (assoc data :status-report report)))
   :schema  {:input  [:map [:applicant-name :string]]
             :output [:map [:status-report :string]]}})

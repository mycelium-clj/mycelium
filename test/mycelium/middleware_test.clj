(ns mycelium.middleware-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [mycelium.middleware :as mw]))

(use-fixtures :each (fn [f] (cell/clear-registry!) (f)))

;; ===== Test helpers =====

(defn- setup-simple-workflow []
  (defmethod cell/cell-spec :mw-test/render [_]
    {:id      :mw-test/render
     :handler (fn [_ data]
                (assoc data :html (str "<h1>" (:message data "Hello") "</h1>")))
     :schema  {:input  [:map]
               :output [:map [:html :string]]}})
  (myc/pre-compile
   {:cells {:start :mw-test/render}
    :edges {:start :end}}))

;; ===== 1. html-response =====

(deftest html-response-test
  (testing "html-response extracts :html and returns 200"
    (let [resp (mw/html-response {:html "<h1>Hi</h1>"})]
      (is (= 200 (:status resp)))
      (is (= "<h1>Hi</h1>" (:body resp)))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"])))))

  (testing "html-response returns 500 when no :html key"
    (let [resp (mw/html-response {:other "data"})]
      (is (= 500 (:status resp))))))

;; ===== 1b. ring-response =====

(deftest ring-response-body-test
  (testing "ring-response uses :body / :status / :headers from the workflow"
    (let [resp (mw/ring-response {:status 201
                                  :headers {"Content-Type" "application/json"
                                            "X-Trace-Id"   "abc"}
                                  :body    "{\"ok\":true}"})]
      (is (= 201 (:status resp)))
      (is (= "{\"ok\":true}" (:body resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"])))
      (is (= "abc" (get-in resp [:headers "X-Trace-Id"]))))))

(deftest ring-response-defaults-test
  (testing "ring-response defaults :status to 200 and :headers to {}"
    (let [resp (mw/ring-response {:body "ok"})]
      (is (= 200 (:status resp)))
      (is (= "ok" (:body resp)))
      (is (= {} (:headers resp))))))

(deftest ring-response-html-fallback-test
  (testing "ring-response falls back to :html with text/html when no :body"
    (let [resp (mw/ring-response {:html "<h1>Hi</h1>"})]
      (is (= 200 (:status resp)))
      (is (= "<h1>Hi</h1>" (:body resp)))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"])))))

  (testing "ring-response :html honours per-request :status and merges :headers"
    (let [resp (mw/ring-response {:html    "<h1>Gone</h1>"
                                  :status  410
                                  :headers {"X-Reason" "tombstone"}})]
      (is (= 410 (:status resp)))
      (is (= "tombstone" (get-in resp [:headers "X-Reason"])))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"]))))))

(deftest ring-response-missing-test
  (testing "ring-response returns 500 when neither :body nor :html is present"
    (let [resp (mw/ring-response {:other "data"})]
      (is (= 500 (:status resp))))))

(deftest workflow-handler-with-ring-response-test
  (testing "workflow-handler + ring-response wires JSON through end-to-end"
    (defmethod cell/cell-spec :mw-test/json [_]
      {:id      :mw-test/json
       :handler (fn [_ _]
                  {:status  200
                   :headers {"Content-Type" "application/json"}
                   :body    "{\"ok\":true}"})
       :schema  {:input  [:map]
                 :output [:map [:body :string] [:status :int] [:headers :map]]}})
    (let [compiled (myc/pre-compile
                    {:cells {:start :mw-test/json}
                     :edges {:start :end}})
          handler  (mw/workflow-handler compiled
                     {:resources {}
                      :output-fn mw/ring-response})
          resp     (handler {:uri "/api/thing"})]
      (is (= 200 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"])))
      (is (= "{\"ok\":true}" (:body resp))))))

;; ===== 2. workflow-handler basic =====

(deftest workflow-handler-basic-test
  (testing "workflow-handler runs workflow and returns HTML response"
    (let [compiled (setup-simple-workflow)
          handler  (mw/workflow-handler compiled {:resources {}})
          resp     (handler {:uri "/test"})]
      (is (= 200 (:status resp)))
      (is (= "<h1>Hello</h1>" (:body resp))))))

;; ===== 3. workflow-handler with custom input-fn =====

(deftest workflow-handler-custom-input-fn-test
  (testing "workflow-handler uses custom input-fn"
    (defmethod cell/cell-spec :mw-test/greet [_]
      {:id      :mw-test/greet
       :handler (fn [_ data]
                  (assoc data :html (str "<h1>Hello " (:name data) "</h1>")))
       :schema  {:input  [:map [:name :string]]
                 :output [:map [:html :string]]}})
    (let [compiled (myc/pre-compile
                    {:cells {:start :mw-test/greet}
                     :edges {:start :end}})
          handler  (mw/workflow-handler compiled
                     {:resources {}
                      :input-fn  (fn [req] {:name (get-in req [:params :name])})})
          resp     (handler {:params {:name "World"}})]
      (is (= 200 (:status resp)))
      (is (= "<h1>Hello World</h1>" (:body resp))))))

;; ===== 4. workflow-handler with custom output-fn =====

(deftest workflow-handler-custom-output-fn-test
  (testing "workflow-handler uses custom output-fn"
    (let [compiled (setup-simple-workflow)
          handler  (mw/workflow-handler compiled
                     {:resources {}
                      :output-fn (fn [result]
                                   {:status 200
                                    :headers {"Content-Type" "application/json"}
                                    :body (pr-str result)})})
          resp     (handler {:uri "/test"})]
      (is (= 200 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"]))))))

;; ===== 5. workflow-handler with resources as fn =====

(deftest workflow-handler-resources-fn-test
  (testing "workflow-handler accepts resources as a function of request"
    (defmethod cell/cell-spec :mw-test/db-render [_]
      {:id      :mw-test/db-render
       :handler (fn [{:keys [db]} data]
                  (assoc data :html (str "<p>DB: " db "</p>")))
       :schema  {:input  [:map]
                 :output [:map [:html :string]]}})
    (let [compiled (myc/pre-compile
                    {:cells {:start :mw-test/db-render}
                     :edges {:start :end}})
          handler  (mw/workflow-handler compiled
                     {:resources (fn [req] {:db (:db-conn req)})})
          resp     (handler {:db-conn "test-db"})]
      (is (= 200 (:status resp)))
      (is (= "<p>DB: test-db</p>" (:body resp))))))

;; ===== 6. workflow-handler returns 400 on input validation error =====

(deftest workflow-handler-input-error-test
  (testing "workflow-handler returns 400 on input validation failure"
    (let [compiled (setup-simple-workflow)
          ;; Add input-schema to the compiled workflow
          compiled (assoc compiled
                         :input-schema-raw [:map [:required-key :string]]
                         :input-schema-compiled (malli.core/schema [:map [:required-key :string]]))
          handler  (mw/workflow-handler compiled {:resources {}})
          resp     (handler {:uri "/test"})]
      (is (= 400 (:status resp))))))

;; ===== 7. wrap-workflow middleware =====

(deftest wrap-workflow-test
  (testing "wrap-workflow returns workflow response"
    (let [compiled   (setup-simple-workflow)
          fallback   (fn [_] {:status 404 :body "Not found"})
          middleware (mw/wrap-workflow fallback
                       {:compiled compiled :resources {}})
          resp       (middleware {:uri "/test"})]
      (is (= 200 (:status resp)))
      (is (= "<h1>Hello</h1>" (:body resp))))))

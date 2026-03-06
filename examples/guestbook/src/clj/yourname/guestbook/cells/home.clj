(ns yourname.guestbook.cells.home
  (:require [mycelium.cell :as cell]
            [selmer.parser :as selmer]))

(defmethod cell/cell-spec :request/parse-home [_]
  {:id      :request/parse-home
   :doc     "Extract name parameter from the HTTP request"
   :handler (fn [_resources data]
              (let [params (or (get-in data [:http-request :query-params]) {})
                    name   (or (get params "name") (get params :name) "World")]
                (assoc data :name name)))
   :schema  {:input  [:map [:http-request :map]]
             :output [:map [:name :string]]}})

(defmethod cell/cell-spec :page/render-home [_]
  {:id      :page/render-home
   :doc     "Render the home page HTML"
   :handler (fn [_resources data]
              (assoc data :html
                     (selmer/render-file "html/home.html"
                                         {:name (:name data)})))
   :schema  {:input  [:map [:name :string]]
             :output [:map [:html :string]]}})

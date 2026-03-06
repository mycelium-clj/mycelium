(ns yourname.guestbook.cells.guestbook
  (:require [mycelium.cell :as cell]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [selmer.parser :as selmer]))

(defmethod cell/cell-spec :guestbook/load-messages [_]
  {:id       :guestbook/load-messages
   :doc      "Load all guestbook messages from the database"
   :requires [:db]
   :handler  (fn [{:keys [db]} data]
               (assoc data :messages
                      (jdbc/execute! db
                        ["SELECT * FROM guestbook ORDER BY timestamp DESC"]
                        {:builder-fn rs/as-unqualified-maps})))
   :schema   {:input  [:map]
              :output [:map [:messages [:sequential :map]]]}})

(defmethod cell/cell-spec :page/render-guestbook [_]
  {:id      :page/render-guestbook
   :doc     "Render the guestbook page with messages"
   :handler (fn [_resources data]
              (assoc data :html
                     (selmer/render-file "html/guestbook.html"
                                         {:messages (:messages data)})))
   :schema  {:input  [:map [:messages [:sequential :map]]]
             :output [:map [:html :string]]}})

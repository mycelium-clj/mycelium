(ns yourname.guestbook.web.routes.pages
  (:require [integrant.core :as ig]
            [mycelium.middleware :as mw]
            [next.jdbc :as jdbc]
            [yourname.guestbook.workflows.guestbook :as guestbook]))

(defn save-message-handler [db]
  (fn [{:keys [params]}]
    (let [{:keys [name message]} params]
      (when (and (seq name) (seq message))
        (jdbc/execute! db
          ["INSERT INTO guestbook (name, message) VALUES (?, ?)"
           name message])))
    {:status  302
     :headers {"Location" "/"}
     :body    ""}))

(defn page-routes [{:keys [db]}]
  [["/" {:get {:handler (mw/workflow-handler
                          guestbook/home-compiled
                          {:resources {:db db}})}}]
   ["/save-message" {:post {:handler (save-message-handler db)}}]])

(derive :reitit.routes/pages :reitit/routes)

(defmethod ig/init-key :reitit.routes/pages
  [_ opts]
  (fn []
    ["" (page-routes opts)]))

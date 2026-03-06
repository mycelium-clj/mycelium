(ns yourname.guestbook.db
  (:require [integrant.core :as ig]
            [next.jdbc :as jdbc]))

(defmethod ig/init-key :db/connection [_ {:keys [jdbc-url]}]
  (let [ds (jdbc/get-datasource {:jdbcUrl jdbc-url})]
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS guestbook
        (id INTEGER PRIMARY KEY AUTOINCREMENT,
         name VARCHAR(30),
         message VARCHAR(200),
         timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"])
    ds))

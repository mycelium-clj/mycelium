(ns yourname.guestbook.guestbook-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [mycelium.core :as myc]
            [yourname.guestbook.cells.guestbook]
            [yourname.guestbook.workflows.guestbook :as guestbook])
  (:import [java.io File]))

(defn test-db []
  (let [tmp  (File/createTempFile "guestbook-test" ".db")
        path (.getAbsolutePath tmp)
        ds   (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path)})]
    (.deleteOnExit tmp)
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS guestbook
        (id INTEGER PRIMARY KEY AUTOINCREMENT,
         name VARCHAR(30),
         message VARCHAR(200),
         timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"])
    ds))

(deftest test-home-workflow
  (let [ds (test-db)
        _  (jdbc/execute! ds
             ["INSERT INTO guestbook (name, message) VALUES (?, ?)"
              "Test User" "Hello World"])
        result (myc/run-compiled
                 guestbook/home-compiled
                 {:db ds}
                 {})]
    (is (= 1 (count (:messages result))))
    (is (= "Test User" (:name (first (:messages result)))))
    (is (string? (:html result)))))

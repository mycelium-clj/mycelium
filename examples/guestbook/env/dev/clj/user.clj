(ns user
  (:require
    [clojure.tools.namespace.repl :as repl]
    [integrant.core :as ig]
    [integrant.repl :refer [clear go halt prep init reset reset-all]]
    [integrant.repl.state :as state]
    [yourname.guestbook.core]
    [yourname.guestbook.config :as config]))

(defn dev-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (config/system-config {:profile :dev})
                                  (ig/expand)))))

(defn test-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (config/system-config {:profile :test})
                                  (ig/expand)))))

(dev-prep!)

(repl/set-refresh-dirs "src/clj")

(comment
  (go)
  (reset)
  (halt))

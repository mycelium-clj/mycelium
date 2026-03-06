(ns yourname.guestbook.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[yourname/guestbook starting]=-"))
   :start      (fn []
                 (log/info "\n-=[yourname/guestbook started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[yourname/guestbook has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})

(ns yourname.guestbook.env
  (:require
    [clojure.tools.logging :as log]
    [yourname.guestbook.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[yourname/guestbook starting using the development profile]=-"))
   :start      (fn []
                 (log/info "\n-=[yourname/guestbook started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[yourname/guestbook has shut down successfully]=-"))
   :middleware wrap-dev
   :opts       {:profile :dev}})

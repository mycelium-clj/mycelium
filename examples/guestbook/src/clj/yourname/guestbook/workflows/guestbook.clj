(ns yourname.guestbook.workflows.guestbook
  (:require [mycelium.core :as myc]
            ;; Load cell definitions
            [yourname.guestbook.cells.guestbook]))

(def home-def
  {:cells    {:start  :guestbook/load-messages
              :render :page/render-guestbook}
   :pipeline [:start :render]})

(def home-compiled (myc/pre-compile home-def))

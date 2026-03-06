(ns yourname.guestbook.dev-middleware)

(defn wrap-dev [handler _opts]
  (-> handler))

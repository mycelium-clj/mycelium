(ns yourname.guestbook.web.handler
  (:require
    [yourname.guestbook.web.middleware.core :as middleware]
    [integrant.core :as ig]
    [reitit.ring :as ring]))

(defmethod ig/init-key :handler/ring
  [_ {:keys [router] :as opts}]
  (ring/ring-handler
    (router)
    (ring/routes
      (ring/redirect-trailing-slash-handler)
      (ring/create-resource-handler {:path "/"})
      (ring/create-default-handler
        {:not-found
         (constantly {:status 404
                      :headers {"Content-Type" "text/html"}
                      :body "<h1>404 - Page not found</h1>"})}))
    {:middleware [(middleware/wrap-base opts)]}))

(defmethod ig/init-key :router/routes
  [_ {:keys [routes]}]
  (mapv (fn [route]
          (if (fn? route) (route) route))
        routes))

(defmethod ig/init-key :router/core
  [_ {:keys [routes env] :as opts}]
  (let [router-opts {:conflicts nil}]
    (if (= env :dev)
      #(ring/router ["" opts routes] router-opts)
      (constantly (ring/router ["" opts routes] router-opts)))))

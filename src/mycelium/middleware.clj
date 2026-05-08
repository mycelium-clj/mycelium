(ns mycelium.middleware
  "Ring middleware and handlers for bridging HTTP requests to Mycelium workflows."
  (:require [malli.core :as m]
            [maestro.core :as fsm]
            [promesa.core :as p]))

(defn html-response
  "Standard HTML response from workflow result. Extracts :html key."
  [result]
  (if (:html result)
    {:status  200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (:html result)}
    {:status  500
     :headers {"Content-Type" "text/plain"}
     :body    "Workflow produced no :html key"}))

(defn ring-response
  "Output adapter for workflows that produce arbitrary HTTP responses
  (JSON, redirects, file streams, etc.) rather than only HTML.

  The terminal render cell returns :status / :headers / :body keys
  on the accumulated workflow data; this helper assembles those into
  a Ring response, defaulting :status to 200 and :headers to {}.

  Falls back to :html extraction (matching `html-response`) so a single
  workflow can mix HTML pages and JSON endpoints without per-route
  output-fn juggling."
  [result]
  (cond
    (contains? result :body)
    {:status  (:status  result 200)
     :headers (:headers result {})
     :body    (:body    result)}

    (contains? result :html)
    {:status  (:status  result 200)
     :headers (merge {"Content-Type" "text/html; charset=utf-8"}
                     (:headers result {}))
     :body    (:html result)}

    :else
    {:status  500
     :headers {"Content-Type" "text/plain"}
     :body    "Workflow produced no :body or :html"}))

(defn- run-compiled
  "Runs a pre-compiled workflow. Inlined to avoid cyclic dependency on mycelium.core."
  [{:keys [compiled-fsm input-schema-raw input-schema-compiled]} resources initial-data]
  (if-let [input-error (when input-schema-compiled
                         (when-let [explanation (m/explain input-schema-compiled initial-data)]
                           {:schema input-schema-raw
                            :errors (:errors explanation)
                            :data   initial-data}))]
    {:mycelium/input-error input-error}
    (let [result (fsm/run compiled-fsm resources {:data initial-data})]
      (if (p/promise? result) @result result))))

(defn workflow-handler
  "Creates a Ring handler from a pre-compiled workflow.
   Options:
     :resources — resources map or (fn [request] resources-map)
     :input-fn  — (fn [request] initial-data), default: (fn [req] {:http-request req})
     :output-fn — (fn [workflow-result] ring-response), default: html-response"
  [compiled {:keys [resources input-fn output-fn]
             :or   {input-fn  (fn [req] {:http-request req})
                    output-fn html-response}}]
  (let [resources-fn (if (fn? resources) resources (constantly resources))]
    (fn [request]
      (let [res    (resources-fn request)
            input  (input-fn request)
            result (run-compiled compiled res input)]
        (if (:mycelium/input-error result)
          {:status  400
           :headers {"Content-Type" "text/plain"}
           :body    (str "Input validation failed: "
                         (pr-str (:mycelium/input-error result)))}
          (output-fn result))))))

(defn wrap-workflow
  "Ring middleware that runs a pre-compiled workflow for each request.
   Options:
     :compiled  — pre-compiled workflow (from myc/pre-compile)
     :resources — resources map or (fn [request] resources-map)
     :input-fn  — (fn [request] initial-data), default: (fn [req] {:http-request req})
     :output-fn — (fn [workflow-result] ring-response), default: html-response"
  [handler {:keys [compiled] :as opts}]
  (let [wf-handler (workflow-handler compiled (dissoc opts :compiled))]
    (fn [request]
      (wf-handler request))))

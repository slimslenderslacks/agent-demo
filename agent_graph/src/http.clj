(ns http
  (:require
   [aleph.http :as http]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [manifold.stream :as s]
   [reitit.ring :as ring]))

(defn format-event
  "Return a properly formatted event payload"
  [body]
  (str "data: " (json/generate-string body) "\n\n"))

(def channels (atom []))

(defn add-channel []
  (let [c (async/chan 1 (map format-event))]
    (swap! channels conj c)
    c))

(defn broadcast [message]
  (doseq [c @channels]
    (async/put! c message)))

(defn close-all []
  (doseq [c @channels]
    (async/close! c))
  (swap! channels (constantly [])))

;; this is for starting an SSE stream without an initial payload
(defn mcp-endpoint-get [_req]
  {:status 200
   :headers {"content-type" "text/event-stream"}
   :body (let [c (async/chan)]
           (s/->source (add-channel)))})

;; check Origin
;; support single request, notification, or response
;;   or batches of either responses or request/notifications
;; if only responses or notifications then 202 with no body is okay
;; can return one application/json response or an text/event-stream
;; if sse, close the stream after all messages have been
(defn mcp-endpoint [_req])

(defn create-app
  "Return a ring handler that will route /events to the SSE handler
       and that will servr  static content form project's resource/public directory"
  []
  (ring/ring-handler
   (ring/router
    [["/mcp" {:post {:handler mcp-endpoint}
              :get {:handler mcp-endpoint-get}}]])))

;; Web server maangement code to make it easy to start and stop a server
    ;; after changesto router or handlers
(def server_ (atom nil))

(defn start-server! []
  (reset! server_ (http/start-server (create-app)
                                     {:port 8080
                                      :join? false})))

(defn stop-server! []
  (swap! server_ (fn [s]
                   (when s
                     (.close s)))))
(comment
  (import [java.io BufferedInputStream])
  (require '[clj-http.client :as client]
           '[clojure.java.io :as io])

  (start-server!)

  (def response (client/get "http://localhost:8080/mcp" {:as :stream}))

  (.start
    (Thread.
      (fn []
        (loop []
          (when-let [line (.readLine (io/reader (BufferedInputStream. (:body response))))]
            (do
              (println line)
              (recur)))))))

  (broadcast {:message "hello"})
  (close-all)

  (stop-server!))

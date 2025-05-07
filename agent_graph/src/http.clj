(ns http
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [reitit.ring :as ring]))

(defn format-event
  "Return a properly formatted event payload"
  [body]
  (str "data: " body "\n\n"))

(defn sse-events [_req]
  {:status 200
   :headers {"content-type" "text/event-stream"}
   :body (let [counter (atom 0)]
           (s/periodically
            1000
            #(format-event (str "Sending event #" (swap! counter inc)))))})

(defn create-app
  "Return a ring handler that will route /events to the SSE handler
       and that will servr  static content form project's resource/public directory"
  []
  (ring/ring-handler
   (ring/router
    [["/events" {:get {:handler sse-events
                       :name ::events}}]])

   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))))

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

  (start-server!)

  (stop-server!))

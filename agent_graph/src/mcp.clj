(ns mcp
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [java.io BufferedInputStream]
   [java.net Socket]))

(def counter (atom 0))
(def request-map (atom {}))

(defn- send-jsonrpc-message [writer m]
  (.write writer (json/generate-string m))
  (.write writer "\n")
  (.flush writer))

(defprotocol MCPClient
  (tool-call [this m])
  (initialize [this])
  (list-tools [this])
  (shutdown [list]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Socket Client
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Client [request notify reader socket]
  MCPClient
  (tool-call [_ m]
    (request {:method "tools/call" :params m}))
  (initialize [_]
    (async/thread
      (loop []
        (let [line (.readLine reader)]
          (when line
            (try
              (let [m (json/parse-string line keyword)]
                (cond
                  (and (:method m) (:id m))
                  (println "Receiving request: " m)
                  (contains? @request-map (:id m))
                  (async/put! (get @request-map (:id m)) (:result m))))
              (catch Throwable ex
                (println "error " ex)
                (println "Error parsing incoming jsonrpc message: " line)))
            (recur)))))
    (async/go
      (let [response
            (async/<!
             (request {:method "initialize"
                       :params {:protocolVersion "2024-11-05"
                                :capabilities {}
                                :client-info {:name "Socket Client" :version "0.1"}}}))]
        (notify {:method "notifications/initialized" :params {}})
        response)))
  (list-tools [_]
    (request {:method "tools/list" :params {}}))
  (shutdown [_]
    (.close socket)))

(defn create-stdio-socket-client [endpoint]
  (let [[_ host port] (re-find #"(.*):(.*)" endpoint)
        socket (Socket. host (Integer/parseInt port))
        reader (io/reader (BufferedInputStream. (.getInputStream socket)))
        writer (io/writer (.getOutputStream socket))]
    (Client.
     (fn [m] (let [id (swap! counter inc)
                   c (async/promise-chan)]
               (send-jsonrpc-message writer (assoc m :jsonrpc "2.0" :id id))
               (swap! request-map assoc id c)
               c))
     (fn [m] (send-jsonrpc-message writer (assoc m :jsonrpc "2.0")))
     reader
     socket)))

(declare parse-sse-events)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Streaming
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord SSEClient [request notify]
  MCPClient
  (tool-call [_ m]
    (request {:method "tools/call" :params m}))
  (initialize [_]
    (async/go
      (let [response
            (async/<!
             (request {:method "initialize"
                       :params {:protocolVersion "2024-11-05"
                                :capabilities {}
                                :client-info {:name "Streaming Client" :version "0.1"}}}))]
        (notify {:method "notifications/initialized" :params {}})
        response)))
  (list-tools [_]
    (request {:method "tools/list" :params {}}))
  (shutdown [_]))

(defn create-streaming-client [url]
  (SSEClient.
   (fn request [m]
     (let [id (swap! counter inc)
           c (async/promise-chan)]
                     ;; POST request
       (swap! request-map assoc id c)
       (let [response (client/post url
                                   {:headers {"Accept" "text/event-stream"
                                              "Content-Type" "application/json"
                                              "Connection" "keep-alive"}
                                    :body (json/generate-string (assoc m :jsonrpc "2.0" :id id))
                                    :throw-exceptions false
                                    :as :stream})]
         (if (= 200 (:status response))
           (parse-sse-events (io/reader (BufferedInputStream. (:body response))) (async/promise-chan))
           (println (format "request error: %s\nresponse: %s" m response))))
       c))
   (fn notify [m]
     (let [response (client/post url
                                 {:headers {"Accept" "text/event-stream"
                                            "Content-Type" "application/json"
                                            "Connection" "keep-alive"}
                                  :body (json/generate-string (assoc m :jsonrpc "2.0"))
                                  :throw-exceptions false
                                  :as :stream})]
       (if (= 200 (:status response))
         (parse-sse-events (io/reader (BufferedInputStream. (:body response))) (async/promise-chan))
         (println (format "notify error: %s\nresponse: %s" m response)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http/SSE client
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord HttpSSEClient [request notify]
  MCPClient
  (tool-call [_ m]
    (request {:method "tools/call" :params m}))
  (initialize [_]
    (async/go
      (let [response
            (async/<!
             (request {:method "initialize"
                       :params {:protocolVersion "2024-11-05"
                                :capabilities {}
                                :client-info {:name "Http/SSE Client" :version "0.1"}}}))]
        (notify {:method "notifications/initialized" :params {}})
        response)))
  (list-tools [_]
    (request {:method "tools/list" :params {}}))
  (shutdown [_]))

(defn open-sse-channel [url c]
  (let [response (client/get url
                             {:headers {"Accept" "text/event-stream"
                                        "Content-Type" "application/json"
                                        "Connection" "keep-alive"}
                              :throw-exceptions false
                              :as :stream})]
    (println "sse channel response" response)
    (parse-sse-events (io/reader (BufferedInputStream. (:body response))) c)))

(defn send-sse-request [url m]
  (let [id (swap! counter inc)
        c (async/promise-chan)]
              ;; POST request
    (swap! request-map assoc id c)
    (let [response (client/post url
                                {:headers {"Accept" "text/event-stream"
                                           "Content-Type" "application/json"
                                           "Connection" "keep-alive"}
                                 :body (json/generate-string (assoc m :jsonrpc "2.0" :id id))
                                 :throw-exceptions false
                                 :as :stream})]
      (println response)
      (when (not (= 201 (:status response)))
        (println (format "request error: %s\nresponse: %s" m response))))
    c))

(defn send-sse-notification [url m]
  (let [response (client/post url
                              {:headers {"Accept" "text/event-stream"
                                         "Content-Type" "application/json"
                                         "Connection" "keep-alive"}
                               :body (json/generate-string (assoc m :jsonrpc "2.0"))
                               :throw-exceptions false
                               :as :stream})]
    (when (not (= 201 (:status response)))
      (println (format "request error: %s\nresponse: %s" m response)))))

;; TODO url is hard coded hered
(defn create-http-sse-client [url]
  (async/go
    (let [url-channel (async/promise-chan)]
      (open-sse-channel url url-channel)
      (let [message-url (format "http://host.docker.internal:9011%s" (async/<! url-channel))]
        (println "use message url: " message-url)
        (HttpSSEClient.
         (partial send-sse-request message-url)
         (partial send-sse-notification message-url))))))

(defn json-or-string [s]
  (try
    (json/parse-string s keyword)
    (catch Throwable _
      s)))

(defn parse-sse-events
  "parse an SSE event-stream
     could be either streaming or Http/SSE
     resolves request promises
     assumes message events but also looks for endpoint events (http/sse only)"
  [reader endpoint-event-channel]
  (async/thread
    (loop [state :message]
      (let [line (.readLine reader)]
        (if line
          (cond
            ;; data
            (string/starts-with? line "data:")
            (let [m (-> line
                        (string/replace #"data:" "")
                        (string/trim)
                        (json-or-string))]
              (cond
                (= state :endpoint)
                (async/put! endpoint-event-channel m) 
                (and (:method m) (:id m))
                (println "WARN: Received request: " m)
                (contains? @request-map (:id m))
                (async/put! (get @request-map (:id m)) (:result m)))
              (recur :message))
              ;; event
            (string/starts-with? line "event:")
            (let [type (-> line
                           (string/replace #"event:" "")
                           (string/trim))]
              (if (= type "endpoint")
                (recur :endpoint)
                (recur :message)))
            :else
            (recur :message))
          (do
            (println "sse stream closing")
            :done))))))

(def tools-to-use #{"brave_web_search"
                    "brave_local_search"
                    "send-email"
                    "get_article"
                    "get_summary"
                    "get_related_topics"})

(defn ->tool-functions [mcp-tool]
  {:type "function"
   :function (-> mcp-tool
                 (assoc :parameters (:inputSchema mcp-tool))
                 (dissoc :inputSchema))})

(comment
  ;; STDIO Socket Client
  (def client (create-stdio-socket-client "localhost:8812"))
  (def tools
    (->>
     (async/<!!
      (async/go
        (println "initializing client: "
                 (async/<! (.initialize client)))
        (:tools (async/<! (.list-tools client)))))
     (filter (comp tools-to-use :name))
     (map ->tool-functions)))

  (async/<!!
   (.tool-call client {:name "brave_web_search"
                       :arguments {:query "mcp and docker"}}))
  (.shutdown client)

  ;; Streaming Client
  (def client (create-streaming-client "http://localhost:9011/mcp/researcher"))
  (.initialize client)
  (def tools
    (->>
     (async/<!!
      (async/go
        (println "initializing client: "
                 (async/<! (.initialize client)))
        (:tools (async/<! (.list-tools client)))))
     (filter (comp tools-to-use :name))
     (map ->tool-functions)))

  ;; HTTP SSE Client is async because it has to wait for the stream to open
  (def client (async/<!! (create-http-sse-client "http://localhost:9011/sse/research")))
  (println "done" (async/<!! (.initialize client)))
  (def tools
    (->>
     (async/<!!
      (async/go
        (println "initializing client: "
                 (async/<! (.initialize client)))
        (:tools (async/<! (.list-tools client)))))
     (filter (comp tools-to-use :name))
     (map ->tool-functions))))


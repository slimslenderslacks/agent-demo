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

(defn create-client [endpoint]
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

(defn parse-sse-events [reader]
  (async/thread
    (loop []
      (let [line (.readLine reader)]
        (when line
          (try
            (let [m (-> line
                        (string/replace #"data:" "")
                        (string/trim)
                        (json/parse-string keyword))]
              (cond
                (and (:method m) (:id m))
                (println "Receiving request: " m)
                (contains? @request-map (:id m))
                (async/put! (get @request-map (:id m)) (:result m))))
            (catch Throwable ex
              (println "error " ex)
              (println "Error parsing incoming jsonrpc message: " line)))
          (recur))))))

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
                                :client-info {:name "Socket Client" :version "0.1"}}}))]
        (notify {:method "notifications/initialized" :params {}})
        response)))
  (list-tools [_]
    (request {:method "tools/list" :params {}}))
  (shutdown [_]))

(defn create-sse-client [url]
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
           (parse-sse-events (io/reader (BufferedInputStream. (:body response))))
           (println "error: " response)))
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
         (parse-sse-events (io/reader (BufferedInputStream. (:body response))))
         (println "error: " response))))))

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
  (def client (create-client "localhost:8812"))
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

  (def client (create-sse-client "http://localhost:9011/mcp/researcher"))
  (.initialize client)
  (def tools
    (->>
     (async/<!!
      (async/go
        (println "initializing client: "
                 (async/<! (.initialize client)))
        (:tools (async/<! (.list-tools client)))))
     (filter (comp tools-to-use :name))
     (map ->tool-functions))))


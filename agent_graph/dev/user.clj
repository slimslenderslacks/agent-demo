(ns user
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.core.async :as async]
   [graph]
   [mcp]
   [openai]) 
  (:import
   [java.util UUID]))

(def zipkin-url
  "http://localhost:9411/api/v2/spans")

(def local-url
  "http://localhost:12434/engines/llama.cpp/v1/chat/completions")

(comment
  "just make sure the server works"
  (->
    (http/post "http://localhost:12434/engines/llama.cpp/v1/chat/completions"
               {:body 
                (json/generate-string
                  {:model "ai/llama3.2"
                   :messages [{:role "user" :content "hello"}]})})
    :body
    (json/parse-string true))

  (http/post zipkin-url
             {:body (json/generate-string
                      [{:id "352bff9a74ca9ad2"
                        :traceId "5af7183fb1d4cf5f" 
                        :name "tool-call"
                        ; epoch microsseconds
                        :timestamp 1556604172355737
                        ; microseconds
                        :duration 100011
                        :localEndpoint {:serviceName "agent"}
                        :remoteEndpoint {:serviceName "mcp"}}])
              :throw-exceptions false})
  )

(comment
  "making sure that the chunk handlers works with a localhost model runner"
  (let [[c f] (openai/chunk-handler)]
    (openai/openai
      {:url local-url
       :model "ai/llama3.2"
       :stream false
       :messages [{:role "user" :content "do a brave search of the web"}]} 
      f)
    (println "done: " (async/<!! c))))

(comment
  (def client (mcp/create-client "localhost:8811"))
  (.initialize client)
  (def tools
    (->>
     (async/<!!
      (async/go
        (println "initializing client: "
                 (async/<! (.initialize client)))
        (:tools (async/<! (.list-tools client)))))
     (filter (comp mcp/tools-to-use :name))
     (map mcp/->tool-functions)))
  (println
    (dissoc 
      (async/<!!
        (graph/stream 
          (graph/chat-with-tools {}) 
          {:opts {:url local-url
                  :model "ai/llama3.2"
                  :stream false
                  :client client}
           :functions tools
           :messages [{:role "user" :content "do a brave search of the web for mcp and docker"}]}))
      :opts
      :functions
      )))

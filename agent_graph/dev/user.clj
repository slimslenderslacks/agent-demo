(ns user
  (:require
   [clj-http.client :as http]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [graph]
   [mcp]
   [openai]))

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
    (json/parse-string true)))

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

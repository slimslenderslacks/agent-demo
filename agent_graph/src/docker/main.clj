(ns docker.main
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :refer [pprint]]
   [graph]
   [mcp])
  (:gen-class))

(def local-url
  "http://localhost:12434/engines/llama.cpp/v1/chat/completions")

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
    :functions)))

;; MODEL-LLAMA3.2-MODEL_MODEL=ai/llama3.2, 
;; MODEL-LLAMA3.2-MODEL_URL=http://model-runner.docker.internal/engines/v1/
(defn -main [& args]
  (println (System/getenv))
  (let [{endpoint "MCP-GATEWAY_ENDPOINT"
         url "MODEL-LLAMA3.2-MODEL_URL"
         model "MODEL-LLAMA3.2-MODEL_MODEL"} (System/getenv)
        client (mcp/create-sse-client endpoint)]
    (println (format "Available Models: ([%s,%s])" url model))
    (async/<!!
     (async/go
       (println (format "initializing MCP client (gateway %s): \n" endpoint)
                (async/<! (.initialize client)))
       (let [tools (:tools (async/<! (.list-tools client)))]
         (println "tools to use: "
                  (->> tools
                       (map mcp/->tool-functions)
                       (map (comp :name :function))))
         (println (format "user %s\nrun agent: (streaming disabled) ..." (first args)))
         (pprint
           (:messages
             (async/<!
               (graph/stream
                 (graph/chat-with-tools {})
                 {:opts {:url (format "%s/chat/completions" url)
                         :model model
                         :parallel_tool_calls false
                         :stream false
                         :client client}
                  :functions (->> tools
                                  (map mcp/->tool-functions))
                  :messages [{:role "user" :content (first args)}]})))))))))

(ns docker.main
  (:require
   [mcp]
   [clojure.core.async :as async]
   [graph]
   [clj-http.client :as http]
   [cheshire.core :as json])
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
        client (mcp/create-client endpoint)]
    (async/<!!
      (async/go
        (println
          (async/<! (.initialize client)))
        (println
          (->>
            (async/<! (.list-tools client))
            :tools
            (map mcp/->tool-functions)))))))

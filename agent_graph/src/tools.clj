(ns tools
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.pprint :as pp]
   [jsonrpc]))

(set! *warn-on-reflection* true)

(defn function-handler
  "make tool call
   supports container tool definitions and prompt tool definitions 
   (prompt tools can have their own child tools definitions)
   does not stream - calls resolve or fail only once 
   should not throw exceptions
     params
       defaults - valid elements of a container definition (functions dissoced)
       function-name - the name of the function that the LLM has selected
       json-arg-string - the JSON arg string that the LLM has generated
       resolve fail - callbacks"
  [client {:keys [functions] :as defaults} function-name json-arg-string {:keys [resolve fail respond]}]
  (let [response
        (async/<!!
          (.tool-call client {:name function-name
                              :arguments (json/parse-string json-arg-string keyword)}))]
    (jsonrpc/notify :message {:content (with-out-str (pp/pprint response))})
    (resolve (->> response
                  :content
                  (map :text)
                  (interpose "\n")
                  (apply str)))))

(defn call-function
  "  returns a promise channel that will emit one message and then close"
  [level function-handler function-name arguments tool-call-id]
  (let [c (async/chan)]
    (try
      (function-handler
       function-name
       arguments
       {:respond
        ;; just forward the tool call response
        (fn [response]
          (jsonrpc/notify :start {:level level :role "tool" :content function-name})
          (jsonrpc/notify :message {:content (format "\n%s\n" response)})
          (async/go
            (async/>! c response)
            (async/close! c)))
        :resolve
        ;; regular containers resolving successfully
        (fn [output]
          (jsonrpc/notify :start {:level level :role "tool" :content function-name})
          (jsonrpc/notify :message {:content (format "\n%s\n" output)})
          (async/go
            (async/>! c {:content output :role "tool" :tool_call_id tool-call-id})
            (async/close! c)))
        :fail
        ;; regular containers failing
        (fn [output]
          (jsonrpc/notify :start {:level level :role "tool" :content function-name})
          (jsonrpc/notify :message {:content (format "function call failed %s" output)})
          (async/go
            (async/>! c {:content output :role "tool" :tool_call_id tool-call-id})
            (async/close! c)))})
      (catch Throwable t
        ;; function-handlers should handle this on their own but this is just in case
        (jsonrpc/notify t)
        (async/go
          (async/>! c {:content (format "unable to run %s - %s" function-name t) :role "tool" :tool_call_id tool-call-id})
          (async/close! c))))
    c))

(defn make-tool-calls
  " returns channel with all messages from completed executions of tools"
  [level function-handler tool-calls]
  (->>
   (for [{{:keys [arguments name]} :function tool-call-id :id} tool-calls]
     (call-function level function-handler name arguments tool-call-id))
   (async/merge)))


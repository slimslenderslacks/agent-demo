(ns jsonrpc
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [java.io
    EOFException
    IOException
    InputStream
    OutputStream]))

(set! *warn-on-reflection* true)

(def ^:private write-lock (Object.))

(defn ^:private read-n-bytes [^InputStream input content-length charset-s]
  (let [buffer (byte-array content-length)]
    (loop [total-read 0]
      (when (< total-read content-length)
        (let [new-read (.read input buffer total-read (- content-length total-read))]
          (when (< new-read 0)
            ;; TODO: return nil instead?
            (throw (EOFException.)))
          (recur (+ total-read new-read)))))
    (String. ^bytes buffer ^String charset-s)))

(defn ^:private parse-header [line headers]
  (let [[h v] (string/split line #":\s*" 2)]
    (assoc headers h v)))

(defn ^:private parse-charset [content-type]
  (or (when content-type
        (when-let [[_ charset] (re-find #"(?i)charset=(.*)$" content-type)]
          (when (not= "utf8" charset)
            charset)))
      "utf-8"))

(defn ^:private read-message [input headers keyword-function]
  (try
    (let [content-length (Long/valueOf ^String (get headers "Content-Length"))
          charset-s (parse-charset (get headers "Content-Type"))
          content (read-n-bytes input content-length charset-s)
          m (json/parse-string content keyword-function)]
      ;; even if the params should not be transformed to keywords,
      ;; the top-level keywords still must be transformed
      (cond-> m
        (get m "id") (assoc :id (get m "id"))
        (get m "jsonrpc") (assoc :jsonrpc (get m "jsonrpc"))
        (get m "method") (assoc :method (get m "method"))
        (get m "params") (assoc :params (get m "params"))
        (get m "error") (assoc :error (get m "error"))
        (get m "result") (assoc :result (get m "result"))))
    (catch Exception _
      :parse-error)))

(defn ^:private read-header-line
  "Reads a line of input. Blocks if there are no messages on the input."
  [^InputStream input]
  (try
    (let [s (java.lang.StringBuilder.)]
      (loop []
        (let [b (.read input)] ;; blocks, presumably waiting for next message
          (case b
            -1 ::eof ;; end of stream
            #_lf 10 (str s) ;; finished reading line
            #_cr 13 (recur) ;; ignore carriage returns
            (do (.append s (char b)) ;; byte == char because header is in US-ASCII
                (recur))))))
    (catch IOException _e
      ::eof)))

(defn input-stream->input-chan [input {:keys [close? keyword-function]
                                       :or {close? true, keyword-function keyword}}]
  (let [input (io/input-stream input)
        messages (async/chan 1)]
    (async/thread
      (loop [headers {}]
        (let [line (read-header-line input)]
          (cond
            ;; input closed; also close channel
            (= line ::eof) (async/close! messages)
            ;; a blank line after the headers indicates start of message
            (string/blank? line) (if (async/>!! messages (read-message input headers keyword-function))
                                   ;; wait for next message
                                   (recur {})
                                   ;; messages closed
                                   (when close? (.close input)))
            :else (recur (parse-header line headers))))))
    messages))

(defn write-message [^OutputStream output msg]
  (let [content (json/generate-string msg)
        content-bytes (.getBytes content "utf-8")]
    (locking write-lock
      (doto output
        (.write (-> (str "Content-Length: " (count content-bytes) "\r\n"
                         "\r\n")
                    (.getBytes "US-ASCII"))) ;; headers are in ASCII, not UTF-8
        (.write content-bytes)
        (.flush)))))

(defn notification [method params]
  {:jsonrpc "2.0"
   :method method
   :params params})

(defn request [method params get-id]
  {:jsonrpc "2.0"
   :method method
   :id (get-id)
   :params params})

;; message({:debug ""}) - debug messages are often serialized edn but still meant to be streamed
;; message({:content ""}) - meant to be streamed
;; prompts({:messages [{:role "", :content ""}]})
;; functions("") - meant to be updated in place
;; functions-done("")
;; error({:content ""})
(defn -notify [method params]
  (write-message (io/output-stream System/out) (notification method params)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn -println [method params & opts]
  (case method
    :message (cond
               (:content params) (do (print (:content params)) (flush))
               (and (first opts) (:debug params)) (do (println "### DEBUG\n") (println (:debug params))))
    :functions (do (print ".") (flush))
    :functions-done (println params)
    :error (binding [*out* *err*]
             (println (:content params)))
    :prompts nil
    :start (println (format "start %s\n" params))
    (binding [*out* *err*] (println (format "%s\n%s\n" method params)))))

(defn create-stdout-notifier [{:keys [debug]}]
  (fn [method params]
    (-println method params debug)))

(def ^:dynamic notify -println)
(alter-var-root #'notify (constantly -println))

(comment
  (notify :message {:content "message"}))


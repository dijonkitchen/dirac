(ns dirac.agent.nrepl-client
  (require [clojure.tools.nrepl :as nrepl]
           [clojure.tools.nrepl.transport :as nrepl.transport]
           [clojure.core.async :refer [chan <!! <! >!! put! alts!! timeout close! go go-loop]]))

;(def current-client (atom nil))
;(def message-channel (chan))

(def current-command-id (atom nil))
(def current-session (atom nil))
(def current-connection (atom nil))
(def current-client (atom nil))
(def current-ns (atom (str *ns*)))
(def nrepl-server (atom nil))
(def response-poller (atom nil))
(def response-queues (atom {}))

(defn make-client [connection nrepl-client session]
  {:connection   connection
   :nrepl-client nrepl-client
   :session      session})

(defn get-connenction [client]
  (:connection client))

(defn get-session [client]
  (:session client))

(defn get-nrepl-client [client]
  (:nrepl-client client))

;(defn ->fn [config default]
;  (cond (fn? config) config
;        (seq? config) (eval config)
;        (symbol? config) (eval config)
;        :else default))

(defn- url-for [host port]
  (format "nrepl://%s:%s" (or host "localhost") port))

(defn connect-with-options [{:keys [host port]}]
  (let [url (url-for host port)]
    (nrepl/url-connect url)))

;(declare execute-with-client)

;(defn- end-of-stream? [client options command-id message]
;  (let [relevant-message (or (= command-id (:id message)) (:global message))
;        error (some #{"error" "eval-error"} (:status message))
;        done (some #{"done" "interrupted"} (:status message))]
;    (when error
;      (let [caught (:caught options)]
;        (when (or (symbol? caught) (list? caught))
;          (execute-with-client client options (str "(" (pr-str caught) ")"))))
;      (when (:global message)
;        (throw (:error message))))
;
;    (and relevant-message (or error done))))
;
;(defn execute-with-client [client options form]
;  (let [command-id (nrepl.misc/uuid)
;        session (or (:session options) @current-session)
;        session-sender (nrepl/client-session client :session session)
;        message-to-send (merge (get-in options [:nrepl-context :interactive-eval])
;                               {:op "eval" :code form :id command-id})
;        ;read-input-line-fn (:read-input-line-fn options)
;        ]
;    (session-sender message-to-send)
;    (reset! current-command-id command-id)
;    #_(doseq [{:keys [ns value out err] :as res}
;              (take-while
;                #(not (end-of-stream? client options command-id %))
;                (filter identity (session-responses session)))]
;        (when (some #{"need-input"} (:status res))
;          (reset! current-command-id nil)
;          (let [input-result (read-input-line-fn)
;                in-message-id (nrepl.misc/uuid)
;                message {:op "stdin" :stdin (when input-result
;                                              (str input-result "\n"))
;                         :id in-message-id}]
;            (session-sender message)
;            (reset! current-command-id command-id)))
;        (when value ((:print-value options) value))
;        (flush)
;        (when (and ns (not (:session options)))
;          (reset! current-ns ns)))
;    (when (:interactive options) (println))
;    (reset! current-command-id nil)
;    @current-ns))

;(defn send-message [message]
;  (let [session @current-session
;        client @current-client
;        session-sender (nrepl/client-session client :session session)]
;    (session-sender message)))

(defn send! [client message]
  (let [session (get-session client)
        nrepl-client (get-nrepl-client client)
        session-sender (nrepl/client-session nrepl-client :session session)]
    (session-sender message)))

(defn set-default-options [options]
  options
  #_(let [options (assoc options :prompt (->fn (:custom-prompt options)
                                               (fn [ns] (str ns "=> "))))
          options (assoc options :subsequent-prompt (->fn (:subsequent-prompt options)
                                                          (constantly nil)))
          options (assoc options :print-value (->fn (:print-value options)
                                                    print))
          ]
      options))

;(defn notify-all-queues-of-error [e]
;  (doseq [session-key (keys @response-queues)]
;    (.offer ^LinkedBlockingQueue (@response-queues session-key)
;            {:status ["error"] :global true :error e})))

(defn poll-for-responses [options connection]
  (let [{:keys [messages-channel]} options]
    (loop []
      (if (try
            (when-let [{:keys [out err] :as resp} (nrepl.transport/recv connection 100)]
              ;(when err ((or print-err print) err))
              ;(when out ((or print-out print) out))
              ;(when-not (or err out)
              (println "RESP" resp)
              (put! messages-channel resp))
            true
            (catch Throwable t
              (println "problem processing response")
              ;(notify-all-queues-of-error t)
              ;(when (System/getenv "DEBUG") (clojure.repl/pst t))
              false))
        (recur)
        (println "leaving poll-for-responses")))))

;
;(let [continue
;      (try
;        (when-let [{:keys [out err] :as resp} (nrepl.transport/recv connection 100)]
;          (when err ((or print-err print) err))
;          (when out ((or print-out print) out))
;          (when-not (or err out)
;            (println "RESP" resp)
;            (put! server->client-chan resp))
;          (flush))
;        :success
;        (catch Throwable t
;          (notify-all-queues-of-error t)
;          (when (System/getenv "DEBUG") (clojure.repl/pst t))
;          :failure))]
;  (when (= :success continue)
;    (recur options connection))))

(defn connect! [options]
  (let [connection (connect-with-options options)
        nrepl-client (nrepl/client connection Long/MAX_VALUE)
        session (nrepl/new-session nrepl-client)
        client (make-client connection nrepl-client session)]
    ;
    ;
    ;(reset! current-connection connection)
    ;(reset! current-session session)
    ;(reset! current-client client)
    ;(swap! response-queues assoc session (LinkedBlockingQueue.))
    (let [options (set-default-options options)]
      (let [^Runnable operation (bound-fn [] (poll-for-responses options connection))]
        (reset! response-poller (Thread. operation)))
      (doto ^Thread @response-poller
        (.setName "nREPL client - response poller")
        (.setDaemon true)
        (.start))
      client)))

#_(defn connect! [& {:as opts}]
    (let [timeout (or (:timeout opts) 1000)]
      (with-open [conn (apply repl/connect (mapcat identity opts))]
        (println conn)
        (if-let [client (repl/client conn timeout)]
          (reset! current-client client)
          (println "no client?")))))

;(defn send! [client msg]
;  (execute-with-client client {:session @current-session} msg))

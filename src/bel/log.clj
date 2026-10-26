(ns bel.log
  "Structured request traces with publisher lifecycle and SSE context propagation."
  (:require
    [bel.validation :as validation]
    [charred.api :as charred]
    [clojure.java.io :as io]
    [com.brunobonacci.mulog :as mu]
    [starfederation.datastar.clojure.api :as datastar])
  (:import
    [java.util Locale]))


(def ^:private log-file
  "logs/events.jsonl")


(def ^:private event-keys
  [:mulog/event-name :mulog/trace-id :mulog/root-trace :mulog/parent-trace
   :mulog/timestamp :mulog/duration :mulog/outcome :mulog/namespace
   :app-name :env :request-id :method :datastar? :status
   :page :view :action :topic :patches :characters :topics :error-type])


(defn- safe-events
  "Exceptions may contain credentials or submitted values; publish only their class."
  [events]
  (map (fn [event]
         (cond-> (select-keys event event-keys)
           (:exception event) (assoc :error-type (.getName (class (:exception event))))))
       events))


(defn start!
  "Start one owned JSON publisher. The returned stop function is idempotent."
  {:malli/schema
   [:=> [:cat [:map [:env [:enum :dev :prod]]]] [:fn ifn?]]}
  [options]
  (let [{:keys [env]} (validation/check! [:map [:env [:enum :dev :prod]]] options)
        before        (mu/global-context)]
    (mu/set-global-context! (assoc before :app-name "tover" :env env))
    (try
      (let [publisher (mu/start-publisher!
                        (assoc (case env
                                 :dev {:type :file-json :filename log-file}
                                 :prod {:type :console-json})
                               :transform safe-events))
            running?  (atom true)]
        (fn []
          (when (compare-and-set! running? true false)
            (try (publisher)
                 (finally (mu/set-global-context! before))))))
      (catch Throwable error
        (mu/set-global-context! before)
        (throw error)))))


(defn capture-context
  "Capture only tracing metadata for callbacks that run on another thread."
  {:malli/schema [:=> [:cat] :map]}
  []
  (select-keys (mu/local-context)
               [:request-id :method :datastar? :mulog/root-trace :mulog/parent-trace]))


(defn wrap-request-context
  "Trace request processing before the SSE adapter commits response headers."
  {:malli/schema [:=> [:cat [:fn ifn?]] [:fn ifn?]]}
  [handler]
  (fn [request]
    (let [request-id (str (random-uuid))
          started    (System/nanoTime)]
      (mu/with-context {:request-id request-id
                        :method     (:request-method request)
                        :datastar?  (boolean (datastar/datastar-request? request))}
        (mu/trace ::http-request {:pairs [] :capture #(select-keys % [:status])}
          (let [response (handler request)
                timing   (String/format Locale/ROOT "app;dur=%.3f"
                                        (to-array [(/ (double (- (System/nanoTime) started)) 1e6)]))]
            (update response :headers
                    (fn [headers]
                      (assoc headers
                             "x-request-id" request-id
                             "server-timing" (if-let [existing (get headers "server-timing")]
                                               (str existing ", " timing)
                                               timing))))))))))


(defn recent
  "Read the latest development JSON events without parsing the whole log file."
  {:malli/schema [:=> [:cat [:int {:min 0 :max 10000}]] [:vector :map]]}
  [n]
  (validation/check! [:int {:min 0 :max 10000}] n)
  (if (.isFile (io/file log-file))
    (with-open [reader (io/reader log-file)]
      (mapv #(charred/read-json % :key-fn keyword) (take-last n (line-seq reader))))
    []))

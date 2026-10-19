(ns bel.realtime
  "In-memory per-tab Datastar connections and direct topic publication."
  (:require
    [bel.core :as core]
    [bel.handler :as handler]
    [bel.validation :as validation]
    [malli.core :as m]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as d*])
  (:import
    [clojure.lang Atom]))


(def Topic
  :qualified-keyword)


(def State
  [:map
   [:connections
    [:map-of :uuid
     [:map-of :uuid
      [:map
       [:sse :any]
       [:topics [:set Topic]]]]]]
   [:stopped? :boolean]])


(def Realtime
  (let [valid-state? (m/validator State)]
    [:map
     [:state [:fn #(and (instance? Atom %) (valid-state? @%))]]]))


(def Event
  [:map
   [:topic Topic]
   [:data :map]
   [:session-id {:optional true} :uuid]
   [:tab-id {:optional true} :uuid]])


(def StreamInput
  [:map
   [:tabId :uuid]
   [:topics [:vector Topic]]])


(defn- remove-connection!
  {:malli/schema [:=> [:cat Realtime :uuid :uuid :any] :nil]}
  [realtime session-id tab-id sse]
  (swap! (:state realtime)
         (fn [state]
           (if (identical? sse (get-in state [:connections session-id tab-id :sse]))
             (let [tabs (-> (get-in state [:connections session-id])
                            (dissoc tab-id))]
               (if (seq tabs)
                 (assoc-in state [:connections session-id] tabs)
                 (update state :connections dissoc session-id)))
             state)))
  nil)


(defn- close-connection!
  {:malli/schema [:=> [:cat Realtime :uuid :uuid :any] :nil]}
  [realtime session-id tab-id sse]
  (remove-connection! realtime session-id tab-id sse)
  (try (d*/close-sse! sse)
       (catch Throwable _))
  nil)


(defn- stream-definition
  {:malli/schema [:=> [:cat Topic] :map]}
  [topic]
  (or (core/get-stream topic)
      (throw (ex-info "Undeclared realtime stream" {:type :validation :topic topic}))))


(defn- check-event
  {:malli/schema [:=> [:cat Event] Event]}
  [event]
  (validation/check! Event event)
  (when (and (:tab-id event) (not (:session-id event)))
    (throw (ex-info "Tab targeting requires a session" {:type :validation})))
  (validation/check! (:bel/schema (stream-definition (:topic event))) (:data event))
  event)


(defn- deliver!
  {:malli/schema [:=> [:cat Realtime :map :uuid :uuid :map Event] :nil]}
  [realtime context session-id tab-id connection {:keys [topic data]}]
  (try
    (when (identical? (:sse connection) (get-in @(:state realtime) [:connections session-id tab-id :sse]))
      (let [context (assoc context :session-id session-id :tab-id tab-id)
            definition (stream-definition topic)
            result (validation/check! (:bel/output definition)
                                      ((:bel/fn definition) context data))]
        (handler/send-result! context (:sse connection) result)))
    (catch Throwable error
      (binding [*out* *err*]
        (println "Bel realtime delivery failed:" (.getMessage error)))
      (close-connection! realtime session-id tab-id (:sse connection))))
  nil)


(defn publish!
  "Send immediately to subscribed tabs, optionally narrowed to a session and tab."
  {:malli/schema [:=> [:cat Realtime :map Event] :nil]}
  [realtime context event]
  (check-event event)
  (when (:stopped? @(:state realtime))
    (throw (ex-info "Realtime is stopped" {:type :realtime-stopped})))
  (let [{:keys [topic session-id tab-id]} event
        connections (:connections @(:state realtime))]
    (doseq [[sid tabs] (if session-id (select-keys connections [session-id]) connections)
            [tid connection] (if tab-id (select-keys tabs [tab-id]) tabs)
            :when (contains? (:topics connection) topic)]
      (deliver! realtime context sid tid connection event)))
  nil)


(defn start!
  {:malli/schema [:=> [:cat] Realtime]}
  []
  {:state (atom {:connections {} :stopped? false})})


(defn stop!
  {:malli/schema [:=> [:cat Realtime] :nil]}
  [realtime]
  (let [[before _] (swap-vals! (:state realtime) assoc :stopped? true :connections {})]
    (doseq [[session-id tabs] (:connections before)
            [tab-id connection] tabs]
      (close-connection! realtime session-id tab-id (:sse connection))))
  nil)


(defn open!
  "Register the connection and its topics, closing any displaced stream."
  {:malli/schema [:=> [:cat Realtime :uuid :uuid [:vector Topic] :any] :nil]}
  [realtime session-id tab-id topics sse]
  (validation/check! :uuid session-id)
  (validation/check! :uuid tab-id)
  (validation/check! [:vector Topic] topics)

  (doseq [topic topics]
    (stream-definition topic))

  (let [connection {:sse    sse
                    :topics (set topics)}
        [before _] (swap-vals! (:state realtime)
                               (fn [state]
                                 (if (:stopped? state)
                                   state
                                   (assoc-in state [:connections session-id tab-id] connection))))]
    (if (:stopped? before)
      (close-connection! realtime session-id tab-id sse)

      (let [old (get-in before [:connections session-id tab-id])]
        (when (and old (not (identical? sse (:sse old))))
          (close-connection! realtime session-id tab-id (:sse old))))))
  nil)


(defn stream-handler
  {:malli/schema [:=> [:cat Realtime] [:fn ifn?]]}
  [realtime]
  (fn [request]
    (try
      (let [{:keys [tabId topics]} (validation/decode-input StreamInput (handler/parse-signals request))
            session-id (validation/check! :uuid (get-in request [:session :bel/session-id]))]
        (doseq [topic topics]
          (stream-definition topic))

        (hk/->sse-response
          request
          {hk/on-open      #(open! realtime session-id tabId topics %)
           hk/on-close     (fn [sse _status] (remove-connection! realtime session-id tabId sse))
           hk/on-exception (fn [sse _error _event] (close-connection! realtime session-id tabId sse) false)}))
      (catch Exception _
        {:status  400
         :headers {"content-type" "text/plain"}
         :body    "Invalid realtime stream input."}))))


(defn routes
  {:malli/schema [:=> [:cat Realtime] [:vector :any]]}
  [realtime]
  [["/_bel/realtime/stream" {:get (stream-handler realtime)}]])

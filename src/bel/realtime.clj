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


(def ConnectionContext
  [:map
   [:scope-id {:optional true} :uuid]
   [:auth-session-id {:optional true} :string]
   [:auth-version {:optional true} :uuid]])


(def State
  [:map
   [:connections
    [:map-of :uuid
     [:map-of :uuid
      [:map
       [:sse :any]
       [:topics [:set Topic]]
       [:scope-id {:optional true} :uuid]
       [:auth-session-id {:optional true} :string]
       [:auth-version {:optional true} :uuid]]]]]
   [:stopped? :boolean]])


(def Realtime
  (let [valid-state? (m/validator State)]
    [:map
     [:state [:fn #(and (instance? Atom %) (valid-state? @%))]]
     [:authorize {:optional true} [:fn ifn?]]]))


(def Event
  [:map
   [:topic Topic]
   [:data :map]
   [:scope-id {:optional true} :uuid]
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
      (if-let [recipient-context (if-let [authorize (:authorize realtime)]
                                   (authorize (assoc connection :session-id session-id :tab-id tab-id))
                                   context)]
        (let [context    (assoc recipient-context :session-id session-id :tab-id tab-id)
              definition (stream-definition topic)
              result     (validation/check! (:bel/output definition)
                                            ((:bel/fn definition) context data))]
          (handler/send-result! context (:sse connection) result))
        (close-connection! realtime session-id tab-id (:sse connection))))
    (catch Throwable error
      (binding [*out* *err*]
        (println "Bel realtime delivery failed:" (.getMessage error)))
      (close-connection! realtime session-id tab-id (:sse connection))))
  nil)


(defn publish!
  "Send to subscribed tabs in the event scope, optionally narrowed to a session and tab."
  {:malli/schema [:=> [:cat Realtime :map Event] :nil]}
  [realtime context event]
  (check-event event)
  (when (:stopped? @(:state realtime))
    (throw (ex-info "Realtime is stopped" {:type :realtime-stopped})))
  (let [{:keys [topic session-id tab-id scope-id]} event
        connections (:connections @(:state realtime))]
    (doseq [[sid tabs]       (if session-id (select-keys connections [session-id]) connections)
            [tid connection] (if tab-id (select-keys tabs [tab-id]) tabs)
            :when            (and (= scope-id (:scope-id connection))
                                  (contains? (:topics connection) topic))]
      (deliver! realtime context sid tid connection event)))
  nil)


(defn start!
  "Optionally reauthorize a connection before each delivery. Return its recipient context or nil."
  {:malli/schema [:function
                  [:=> [:cat] Realtime]
                  [:=> [:cat [:map [:authorize {:optional true} [:fn ifn?]]]] Realtime]]}
  ([]
   (start! {}))

  ([options]
   (cond-> {:state (atom {:connections {} :stopped? false})}
     (:authorize options) (assoc :authorize (:authorize options)))))


(defn stop!
  {:malli/schema [:=> [:cat Realtime] :nil]}
  [realtime]
  (let [[before _] (swap-vals! (:state realtime) assoc :stopped? true :connections {})]
    (doseq [[session-id tabs]   (:connections before)
            [tab-id connection] tabs]
      (close-connection! realtime session-id tab-id (:sse connection))))
  nil)


(defn close-session!
  "Close all connections associated with an application authentication session."
  {:malli/schema [:=> [:cat Realtime :string] :nil]}
  [realtime auth-session-id]
  (doseq [[session-id tabs]   (:connections @(:state realtime))
          [tab-id connection] tabs
          :when               (= auth-session-id (:auth-session-id connection))]
    (close-connection! realtime session-id tab-id (:sse connection)))
  nil)


(defn open!
  "Register a connection with trusted scope metadata, closing any displaced stream."
  {:malli/schema [:function
                  [:=> [:cat Realtime :uuid :uuid [:vector Topic] :any] :nil]
                  [:=> [:cat Realtime :uuid :uuid [:vector Topic] :any ConnectionContext] :nil]]}
  ([realtime session-id tab-id topics sse]
   (open! realtime session-id tab-id topics sse {}))

  ([realtime session-id tab-id topics sse connection-context]
   (validation/check! :uuid session-id)
   (validation/check! :uuid tab-id)
   (validation/check! [:vector Topic] topics)
   (validation/check! ConnectionContext connection-context)
   (doseq [topic topics]
     (stream-definition topic))
   (let [connection (assoc (select-keys connection-context [:scope-id :auth-session-id :auth-version])
                           :sse sse :topics (set topics))
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
   nil))


(defn stream-handler
  {:malli/schema [:=> [:cat Realtime] [:fn ifn?]]}
  [realtime]
  (fn [request]
    (try
      (let [{:keys [tabId topics]} (validation/decode-input StreamInput (handler/parse-signals request))
            session-id             (validation/check! :uuid (get-in request [:session :bel/session-id]))
            connection-context     (select-keys
                                     (validation/check! ConnectionContext (or (:bel/realtime-context request) {}))
                                     [:scope-id :auth-session-id :auth-version])]
        (doseq [topic topics]
          (stream-definition topic))

        (hk/->sse-response
          request
          {hk/on-open      #(open! realtime session-id tabId topics % connection-context)
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

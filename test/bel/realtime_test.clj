(ns bel.realtime-test
  (:require
    [bel.core :as core]
    [bel.realtime :as realtime]
    [charred.api :as charred]
    [clojure.test :refer [deftest is]]
    [malli.core :as m]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as d*]
    [starfederation.datastar.clojure.protocols :as p])
  (:import
    [java.util UUID]))


(core/defstream events
  {:schema [:map
            [:type {:optional true} [:= :changed]]
            [:n {:optional true} :int]]}
  [ctx data]
  (when-let [error (:projection-error ctx)]
    (when (= (:projection-tab ctx) (:tab-id ctx)) (throw error)))
  {:type   :signals
   :schema [:map [:n :int] [:session :uuid] [:tab :uuid]]
   :data   {:n (or (:n data) 0) :session (:session-id ctx) :tab (:tab-id ctx)}})


(core/defstream other-events
  {:schema [:map [:n :int]]}
  [_ctx _data]
  [])


(defn- fake-stream
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [events closed failure]
  (reify p/SSEGenerator
    (get-lock [_] (throw (AssertionError. "Bel must not acquire the stream lock")))
    (sse-gen? [_] true)
    (send-event! [_ event-type lines _options]
      (case @failure
        :throw (throw (IllegalStateException. "Socket failed"))
        :assert (throw (AssertionError. "Socket assertion failed"))
        :false false
        (do (swap! events conj {:type event-type :lines lines}) true)))
    (close-sse! [_]
      (let [open? (not @closed)]
        (reset! closed true)
        open?))))


(defn- messages
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [events]
  (mapv #(charred/read-json (subs (first (:lines %)) (count "signals ")) :key-fn keyword) @events))


(defn- with-realtime
  {:malli/schema [:=> [:cat [:fn ifn?]] :any]}
  [f]
  (with-redefs [core/registry (atom {:pages   {} :views {} :actions {}
                                     :streams {::events #'events ::other-events #'other-events}})]
    (let [realtime (realtime/start!)]
      (try (f realtime)
           (finally (realtime/stop! realtime))))))


(defn- connection
  {:malli/schema [:=> [:cat realtime/Realtime :uuid :uuid [:vector realtime/Topic]] :map]}
  [realtime session tab topics]
  (let [events (atom [])
        closed (atom false)
        failure (atom nil)
        sse (fake-stream events closed failure)]
    (realtime/open! realtime session tab topics sse)
    {:events events :closed closed :failure failure :sse sse}))


(deftest realtime-schema-validates-atom-contents
  (let [sid (UUID/randomUUID)
        tid (UUID/randomUUID)
        connection {:sse :stream :topics #{::events}}
        state {:connections {sid {tid connection}} :stopped? false}]
    (is (m/validate realtime/Realtime (realtime/start!)))
    (is (m/validate realtime/Realtime {:state (atom state)}))
    (is (not (m/validate realtime/Realtime {:state state})))
    (is (not (m/validate realtime/Realtime {:state (delay state)})))
    (doseq [invalid [nil
                     (dissoc state :stopped?)
                     (assoc state :stopped? "false")
                     (assoc state :connections [])
                     (assoc state :connections {:session {tid connection}})
                     (assoc state :connections {sid {:tab connection}})
                     (assoc state :connections {[sid tid] connection})
                     (assoc-in state [:connections sid tid :topics] #{:events})
                     (assoc-in state [:connections sid tid :topics] [::events])
                     (update-in state [:connections sid tid] dissoc :sse)]]
      (is (not (m/validate realtime/Realtime {:state (atom invalid)}))))))


(deftest topics-are-attached-at-open-and-reconnect-without-delivery
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            tid (UUID/randomUUID)
            topic ::events
            old (connection realtime sid tid [topic topic])]
        (is (= #{topic} (get-in @(:state realtime) [:connections sid tid :topics])))
        (is (empty? @(:events old)))
        (let [current (connection realtime sid tid [topic])]
          (is @(:closed old))
          (#'realtime/remove-connection! realtime sid tid (:sse old))
          (is (identical? (:sse current) (get-in @(:state realtime) [:connections sid tid :sse])))
          (is (empty? @(:events current)))
          (realtime/publish! realtime {} {:topic topic :data {:type :changed :n 1}})
          (is (= ["1"] (mapv :n (messages (:events current))))))))))


(deftest required-payload-stream-registers-without-invocation
  (with-realtime
    (fn [realtime]
      (let [received (atom [])]
        (with-redefs [other-events (fn [_context data] (swap! received conj data) [])]
          (let [tab (connection realtime (UUID/randomUUID) (UUID/randomUUID) [::other-events])]
            (is (not @(:closed tab)))
            (is (empty? @received))
            (is (empty? @(:events tab)))
            (is (thrown? Exception (realtime/publish! realtime {} {:topic ::other-events :data {}})))
            (realtime/publish! realtime {} {:topic ::other-events :data {:n 7}})
            (is (= [{:n 7}] @received))))))))


(deftest closing-tabs-keeps-siblings-and-removes-empty-sessions
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            other-sid (UUID/randomUUID)
            tid-a (UUID/randomUUID)
            tid-b (UUID/randomUUID)
            other-tid (UUID/randomUUID)
            a (connection realtime sid tid-a [::events])
            b (connection realtime sid tid-b [::events])
            other (connection realtime other-sid other-tid [::events])]
        (#'realtime/close-connection! realtime sid tid-a (:sse a))
        (is @(:closed a))
        (is (nil? (get-in @(:state realtime) [:connections sid tid-a])))
        (is (identical? (:sse b) (get-in @(:state realtime) [:connections sid tid-b :sse])))
        (is (not @(:closed b)))
        (#'realtime/close-connection! realtime sid tid-b (:sse b))
        (is @(:closed b))
        (is (not (contains? (:connections @(:state realtime)) sid)))
        (is (identical? (:sse other) (get-in @(:state realtime) [:connections other-sid other-tid :sse])))
        (is (not @(:closed other)))))))


(deftest overlapping-opens-close-each-displaced-stream
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            tid (UUID/randomUUID)
            old (connection realtime sid tid [::events])
            closing (promise)
            resume (promise)
            close! d*/close-sse!]
        (with-redefs [d*/close-sse! (fn [sse]
                                      (is (not (Thread/holdsLock (:state realtime))))
                                      (let [current (get-in @(:state realtime) [:connections sid tid :sse])]
                                        (is (not (identical? sse current)))
                                        (when (identical? sse (:sse old))
                                          (deliver closing current)
                                          (is (= true (deref resume 5000 ::timeout)))))
                                      (close! sse))]
          (let [opening (future (connection realtime sid tid [::events]))]
            (try
              (is (some? (deref closing 5000 nil)))
              (let [replacement (future (connection realtime sid tid [::events]))]
                (is (map? (deref replacement 5000 nil)))
                (deliver resume true)
                (let [displaced (deref opening 5000 nil)
                      current (deref replacement 5000 nil)]
                  (is @(:closed old))
                  (is (= true (some-> displaced :closed deref)))
                  (is (= false (some-> current :closed deref)))
                  (is (identical? (:sse current) (get-in @(:state realtime) [:connections sid tid :sse])))))
              (finally (deliver resume true)))))))))


(deftest stop-clears-admitted-stream-and-rejects-overlapping-open
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            tid (UUID/randomUUID)
            old (connection realtime sid tid [::events])
            closing (promise)
            resume (promise)
            close! d*/close-sse!]
        (with-redefs [d*/close-sse! (fn [sse]
                                      (is (not (Thread/holdsLock (:state realtime))))
                                      (if (identical? sse (:sse old))
                                        (do
                                          (deliver closing (get-in @(:state realtime) [:connections sid tid :sse]))
                                          (is (= true (deref resume 5000 ::timeout))))
                                        (is (= {:connections {} :stopped? true} @(:state realtime))))
                                      (close! sse))]
          (let [opening (future (connection realtime sid tid [::events]))]
            (try
              (is (some? (deref closing 5000 nil)))
              (let [stopping (future (realtime/stop! realtime))]
                (is (nil? (deref stopping 5000 ::timeout)))
                (let [rejected (future (connection realtime sid tid [::events]))]
                  (is (= true (some-> (deref rejected 5000 nil) :closed deref))))
                (is (= {:connections {} :stopped? true} @(:state realtime))))
              (finally (deliver resume true)))
            (is (= true (some-> (deref opening 5000 nil) :closed deref)))
            (is @(:closed old))))))))


(deftest publications-are-immediate-and-respect-topics-session-and-tab
  (with-realtime
    (fn [realtime]
      (let [sid-a (UUID/randomUUID)
            sid-b (UUID/randomUUID)
            tab-a (UUID/randomUUID)
            topic ::events
            a (connection realtime sid-a tab-a [topic])
            b (connection realtime sid-a (UUID/randomUUID) [topic])
            c (connection realtime sid-b (UUID/randomUUID) [topic])
            unsubscribed (connection realtime sid-a (UUID/randomUUID) [])]
        (realtime/publish! realtime {} {:topic topic :data {:type :changed :n 1}})
        (realtime/publish! realtime {} {:topic topic :data {:type :changed :n 2} :session-id sid-a})
        (realtime/publish! realtime {} {:topic topic :data {:type :changed :n 3} :session-id sid-a :tab-id tab-a})
        (realtime/publish! realtime {} {:topic ::other-events :data {:type :changed :n 4}})
        (is (= ["1" "2" "3"] (mapv :n (messages (:events a)))))
        (is (= ["1" "2"] (mapv :n (messages (:events b)))))
        (is (= ["1"] (mapv :n (messages (:events c)))))
        (is (empty? @(:events unsubscribed)))
        (is (= (str sid-a) (:session (first (messages (:events a))))))
        (is (= (str tab-a) (:tab (first (messages (:events a))))))))))


(deftest topics-and-events-are-validated-before-use
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            tid (UUID/randomUUID)
            topic ::events
            tab (connection realtime sid tid [topic])]
        (doseq [invalid [{:topic :unknown/stream :data {}}
                         {:topic :events :data {}}
                         {:topic [::events] :data {}}
                         {:topic topic :data {:type :unknown}}
                         {:topic topic :data {:n "invalid"}}
                         {:topic topic :data []}
                         {:topic topic :data {} :tab-id tid}
                         {:topic topic :data {} :session-id "spoofed"}]]
          (is (thrown? Exception (realtime/publish! realtime {} invalid))))
        (is (thrown? Exception (connection realtime sid tid [topic :unknown/stream])))
        (is (thrown? Exception (connection realtime sid tid [:events])))
        (is (identical? (:sse tab) (get-in @(:state realtime) [:connections sid tid :sse])))
        (is (empty? @(:events tab)))))))


(deftest projection-failures-close-the-affected-stream-and-other-tabs-continue
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            tid-a (UUID/randomUUID)
            topic ::events
            a (connection realtime sid tid-a [topic])
            b (connection realtime sid (UUID/randomUUID) [topic])]
        (realtime/publish! realtime
                           {:projection-error (IllegalStateException. "Projection failed")
                            :projection-tab   tid-a}
                           {:topic topic :data {:type :changed :n 1}})
        (is @(:closed a))
        (is (nil? (get-in @(:state realtime) [:connections sid tid-a])))
        (is (not @(:closed b)))
        (is (= ["1"] (mapv :n (messages (:events b)))))))))


(deftest false-and-throwing-sends-close-sockets
  (doseq [failure [:false :throw :assert]]
    (with-realtime
      (fn [realtime]
        (let [sid (UUID/randomUUID)
              topic ::events
              tab (connection realtime sid (UUID/randomUUID) [topic])]
          (reset! (:failure tab) failure)
          (realtime/publish! realtime {} {:topic topic :data {:type :changed :n 1}})
          (is @(:closed tab))
          (is (empty? (:connections @(:state realtime)))))))))


(deftest stopping-closes-connections-and-refuses-publication-and-new-connections
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            topic ::events
            tabs [(connection realtime sid (UUID/randomUUID) [topic])
                  (connection realtime sid (UUID/randomUUID) [topic])
                  (connection realtime (UUID/randomUUID) (UUID/randomUUID) [topic])]]
        (realtime/stop! realtime)
        (realtime/stop! realtime)
        (doseq [tab tabs] (is @(:closed tab)))
        (is (empty? (:connections @(:state realtime))))
        (is (thrown? Exception (realtime/publish! realtime {} {:topic topic :data {}})))
        (is @(:closed (connection realtime sid (UUID/randomUUID) [topic])))))))


(deftest streams-and-view-queries-use-shared-context-without-retaining-request
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            tid (UUID/randomUUID)
            context {:marker "shared"}
            projected (atom [])
            queried (atom [])
            callbacks (atom nil)
            sent (atom [])
            sse (fake-stream sent (atom false) (atom nil))
            view {:bel/schema [:map] :bel/output core/Result
                  :bel/query  (fn [context _] (swap! queried conj context) {:marker (:marker context)})
                  :bel/fn     (fn [data] {:type :signals :data data})}]
        (with-redefs [events (fn [context _]
                               (swap! projected conj context)
                               {:type :view :id ::context-view})
                      core/get-view (constantly view)
                      hk/->sse-response (fn [_ options] (reset! callbacks options) {:status 200})]
          (let [open (get-in (into {} (realtime/routes realtime)) ["/_bel/realtime/stream" :get])
                request {:request-method :get :session {:bel/session-id sid}
                         :query-params   {"datastar" (charred/write-json-str
                                                       {:tabId (str tid) :topics ["bel.realtime-test/events"]})}}]
            (is (= 200 (:status (open request))))
            ((get @callbacks hk/on-open) sse)
            (is (= {:sse sse :topics #{::events}} (get-in @(:state realtime) [:connections sid tid])))
            (is (empty? @projected))
            (is (empty? @queried))
            (is (empty? @sent))
            (realtime/publish! realtime context {:topic ::events :data {}})
            (realtime/publish! realtime {:marker "updated"} {:topic ::events :data {}})
            (is (= [{:marker "shared" :session-id sid :tab-id tid}
                    {:marker "updated" :session-id sid :tab-id tid}] @projected))
            (is (= @projected @queried))
            (is (= ["shared" "updated"] (mapv :marker (messages sent))))))))))


(deftest stream-get-decodes-topics-derives-session-and-cleans-up-on-close
  (with-realtime
    (fn [realtime]
      (let [sid (UUID/randomUUID)
            tid (UUID/randomUUID)
            route-map (into {} (realtime/routes realtime))
            open (get-in route-map ["/_bel/realtime/stream" :get])
            input {:tabId (str tid) :topics ["bel.realtime-test/events"] :sessionId "spoofed"}
            request {:request-method :get :session {:bel/session-id sid}
                     :query-params   {"datastar" (charred/write-json-str input)}}
            callbacks (atom nil)]
        (is (= 1 (count route-map)))
        (with-redefs [hk/->sse-response (fn [_ options] (reset! callbacks options) {:status 200})]
          (is (= 200 (:status (open request))))
          (is (= 400 (:status (open (dissoc request :session)))))
          (is (= 400 (:status (open (assoc-in request [:query-params "datastar"] "invalid JSON")))))
          (doseq [invalid [(dissoc input :topics)
                           (assoc input :topics ["missing/topic"])
                           (assoc input :topics ["events"])
                           (assoc input :topics [["bel.realtime-test/events"]])]]
            (is (= 400 (:status (open (assoc-in request [:query-params "datastar"]
                                                (charred/write-json-str invalid))))))))
        (let [events (atom [])
              closed (atom false)
              sse (fake-stream events closed (atom nil))]
          ((get @callbacks hk/on-open) sse)
          (is (= #{::events} (get-in @(:state realtime) [:connections sid tid :topics])))
          (is (empty? @events))
          (realtime/publish! realtime {} {:topic ::events :data {:type :changed :n 1}})
          (is (= ["1"] (mapv :n (messages events))))
          (is (= (str sid) (:session (first (messages events)))))
          ((get @callbacks hk/on-close) sse :client-close)
          (is (empty? (:connections @(:state realtime))))
          ((get @callbacks hk/on-open) sse)
          (is (false? ((get @callbacks hk/on-exception) sse (Exception.) nil)))
          (is @closed)
          (is (empty? (:connections @(:state realtime)))))))))

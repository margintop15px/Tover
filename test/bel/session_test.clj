(ns bel.session-test
  (:require
    [bel.session :as session]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]])
  (:import
    [java.util UUID]))


(defn- session-cookie
  {:malli/schema [:=> [:cat :map] :string]}
  [response]
  (let [cookies (get-in response [:headers "Set-Cookie"])]
    (first (str/split (if (string? cookies) cookies (first cookies)) #";" 2))))


(deftest ring-session-keeps-native-identity-and-response-session-fields
  (let [requests (atom [])
        handler  (session/wrap-session
                   (fn [request]
                     (swap! requests conj (:session request))
                     {:status 200 :body "ok" :session {:setting true}}))
        response (handler {:headers {}})
        cookie   (session-cookie response)]
    (handler {:headers {"cookie" cookie}})
    (is (instance? UUID (:bel/session-id (first @requests))))
    (is (= (:bel/session-id (first @requests)) (:bel/session-id (second @requests))))
    (is (:setting (second @requests)))
    (is (re-find #"HttpOnly" (first (get-in response [:headers "Set-Cookie"]))))
    (is (re-find #"SameSite=Lax" (first (get-in response [:headers "Set-Cookie"]))))))


(deftest sessions-are-distinct-and-request-fields-cannot-select-identity
  (let [ids     (atom [])
        forged  (UUID/randomUUID)
        handler (session/wrap-session
                  (fn [request]
                    (swap! ids conj (get-in request [:session :bel/session-id]))
                    {:status 200 :body "ok"}))]
    (handler {:headers {} :session {:bel/session-id forged} :body {:session-id forged}})
    (handler {:headers {}})
    (is (not= forged (first @ids)))
    (is (not= (first @ids) (second @ids)))))


(deftest tampered-cookie-does-not-reuse-a-session
  (let [ids     (atom [])
        handler (session/wrap-session
                  (fn [request]
                    (swap! ids conj (get-in request [:session :bel/session-id]))
                    {:status 200 :body "ok"}))
        cookie  (session-cookie (handler {:headers {}}))]
    (handler {:headers {"cookie" (str cookie "tampered")}})
    (is (not= (first @ids) (second @ids)))))


(deftest explicit-nil-session-clears-prior-state-and-identity
  (let [requests       (atom [])
        handler        (session/wrap-session
                         (fn [request]
                           (swap! requests conj (:session request))
                           (case (:uri request)
                             "/set" {:status 200 :body "ok" :session {:setting true}}
                             "/clear" {:status 200 :body "ok" :session nil}
                             {:status 200 :body "ok"})))
        initial-cookie (session-cookie (handler {:uri "/set" :headers {}}))
        cleared        (handler {:uri "/clear" :headers {"cookie" initial-cookie}})
        next-cookie    (if (get-in cleared [:headers "Set-Cookie"])
                         (session-cookie cleared)
                         initial-cookie)]
    (handler {:uri "/read" :headers {"cookie" next-cookie}})
    (let [[initial before-clear after-clear] @requests]
      (is (true? (:setting before-clear)))
      (is (= (:bel/session-id initial) (:bel/session-id before-clear)))
      (is (nil? (:setting after-clear)))
      (is (instance? UUID (:bel/session-id after-clear)))
      (is (not= (:bel/session-id before-clear) (:bel/session-id after-clear))))))

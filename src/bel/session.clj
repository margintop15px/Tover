(ns bel.session
  (:require
    [malli.core :as m]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :refer [cookie-store]])
  (:import
    [java.util UUID]))


(defn- wrap-session-id
  {:malli/schema [:=> [:cat [:fn ifn?]] [:fn ifn?]]}
  [handler]
  (fn [request]
    (let [current (:session request)
          id (:bel/session-id current)
          current (assoc current :bel/session-id (if (m/validate :uuid id) id (UUID/randomUUID)))]
      (when-let [response (handler (assoc request :session current))]
        (if (and (contains? response :session) (nil? (:session response)))
          response
          (update response :session #(merge current %)))))))


(defn wrap-session
  "Give requests a server-owned UUID session identity through Ring's signed cookie store."
  {:malli/schema [:=> [:cat [:fn ifn?]] [:fn ifn?]]}
  [handler]
  (session/wrap-session
    (wrap-session-id handler)
    {:store        (cookie-store)
     :cookie-name  "bel-session"
     :cookie-attrs {:http-only true :same-site :lax}}))

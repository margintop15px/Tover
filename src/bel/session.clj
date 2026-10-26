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
          id      (:bel/session-id current)
          current (assoc current :bel/session-id (if (m/validate :uuid id) id (UUID/randomUUID)))]
      (when-let [response (handler (assoc request :session current))]
        (if (and (contains? response :session) (nil? (:session response)))
          response
          (update response :session #(with-meta (merge current %) (meta %))))))))


(defn wrap-session
  "Give requests a server-owned UUID identity, using an optional Ring session store."
  {:malli/schema [:function
                  [:=> [:cat [:fn ifn?]] [:fn ifn?]]
                  [:=> [:cat [:fn ifn?] :map] [:fn ifn?]]]}
  ([handler]
   (wrap-session handler {}))

  ([handler options]
   (session/wrap-session
     (wrap-session-id handler)
     (merge {:store        (cookie-store)
             :cookie-name  "bel-session"
             :cookie-attrs {:http-only true :same-site :lax}}
            options))))

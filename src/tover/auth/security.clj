(ns tover.auth.security
  (:require
    [bel.validation :as validation]
    [ring.middleware.session.store :as ring-store]
    [tover.auth.core :as auth]
    [tover.auth.sessions :as sessions]
    [tover.workspaces.core :as workspaces])
  (:import
    [java.time Instant]))


(defn failure
  {:malli/schema [:=> [:cat :int :keyword :string] :any]} [status code message]
  (throw (ex-info message {:type :identity :status status :code code})))


(defn authenticate
  "Resolve current user and membership while serializing token refresh for this session."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [{:keys [auth workspaces session-store]} key]
  (let [result
        (sessions/update-session! session-store key
                                  (fn [{:keys [tokens workspace-id] :as row}]
                                    (if-not tokens
                                      [row nil]
                                      (let [tokens (if (<= (long (or (:expires-at tokens) 0)) (+ 30 (.getEpochSecond (Instant/now))))
                                                     (try (auth/refresh! auth (:refresh-token tokens))
                                                          (catch clojure.lang.ExceptionInfo error
                                                            (if (= :invalid-refresh-token (:code (ex-data error)))
                                                              (failure 401 :unauthenticated "Please sign in again.")
                                                              (throw error))))
                                                     tokens)
                                            row    (assoc row :tokens tokens)]
                                        (try
                                          (let [user        (auth/current-user auth (:access-token tokens))
                                                memberships (workspaces/list-memberships workspaces (:access-token tokens))
                                                selected    (or (some #(when (= workspace-id (:workspace-id %)) %) memberships)
                                                                (first memberships))
                                                selected-id (:workspace-id selected)
                                                row         (cond-> (assoc row :user-id (:user-id user) :workspace-id selected-id)
                                                              (not= workspace-id selected-id) (assoc :version (random-uuid)))]
                                            [row {:user-id            (:user-id user) :user user             :memberships memberships
                                                  :password-required? (get-in row [:data :identity/password-required?])
                                                  :workspace-id       selected-id :role     (:role selected)
                                                  :auth-session-id    key :auth-version     (:version row)}])
                  ;; Save a rotated pair even if the following authorization read fails.
                                          (catch Throwable error [row {:error error}]))))))]
    (if-let [error (:error result)] (throw error) result)))


(defn require-manager!
  {:malli/schema [:=> [:cat [:maybe :map]] :nil]} [identity]
  (when-not (contains? #{:owner :admin} (:role identity))
    (failure 403 :forbidden "Workspace administration requires an owner or admin.")))


(defn access-token
  {:malli/schema [:=> [:cat :map :map] :string]} [{:keys [session-store]} request]
  (or (get-in (sessions/session session-store (:session/key request)) [:tokens :access-token])
      (failure 401 :unauthenticated "Please sign in.")))


(defn datastar-request?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [request]
  (= "true" (get-in request [:headers "datastar-request"])))


(defn- response
  {:malli/schema [:=> [:cat :map :int :keyword :string] :map]} [request status code message]
  (let [location (case code
                   :unauthenticated "/auth/login"
                   :workspace-required "/workspace"
                   :password-required "/auth/password"
                   nil)]
    (if (and location (= :get (:request-method request)) (not (datastar-request? request)))
      {:status 303 :headers {"location" location} :body ""}
      {:status  status
       :headers (cond-> {"content-type" "text/plain; charset=utf-8" "x-tover-error" (name code)}
                  location (assoc "x-tover-redirect" location))
       :body    message})))


(def IdentityPolicy
  [:enum :optional :authenticated :workspace :manager :password :finish :logout])


(defn- local-identity
  {:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  [{:keys [session-store]} request]
  (let [row (sessions/session session-store (:session/key request))]
    (when (:tokens row)
      {:user-id (:user-id row) :auth-version (:version row) :auth-session-id (:session/key request)})))


(defn- with-context-token
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (if-let [token (or (get-in request [:headers "x-tover-context"])
                     (get-in request [:params :_tover-context]))]
    (assoc-in request [:headers "x-tover-context"] token)
    request))


(defn wrap-identity
  "Resolve identity and enforce the explicit policy declared on this route group."
  {:malli/schema [:function
                  [:=> [:cat [:fn ifn?] :map] [:fn ifn?]]
                  [:=> [:cat [:fn ifn?] :map IdentityPolicy] [:fn ifn?]]]}
  ([handler services]
   (wrap-identity handler services :workspace))

  ([handler services policy]
   (fn [request]
     (try
       (let [request    (with-context-token request)
             identity   (when (:identity/session-id (:session request))
                          (if (= policy :logout)
                            (local-identity services request)
                            (authenticate services (:session/key request))))
             protected? (not (contains? #{:optional :logout} policy))]
         (when (and protected? (nil? identity))
           (failure 401 :unauthenticated "Please sign in."))
         ;; The one-use email response cannot contain the newly rotated session's context yet.
         (when (and identity (not (contains? #{:optional :finish} policy))
                    (or (datastar-request? request)
                        (not (contains? #{:get :head :options} (:request-method request)))))
           (let [version (get-in request [:headers "x-tover-context"])]
             (when-not (= (str (:auth-version identity)) version)
               (failure 409 :workspace-changed "Workspace changed. Reload this page before continuing."))))
         (when (and protected? (not (contains? #{:password :finish} policy)) (:password-required? identity))
           (failure 409 :password-required "Set your password before continuing."))
         (when (and (contains? #{:workspace :manager} policy) (nil? (:workspace-id identity)))
           (failure 409 :workspace-required "Create or select a workspace first."))
         (when (= policy :manager)
           (require-manager! identity))
         (handler (assoc request :tover/identity identity :bel/context identity
                         :bel/realtime-context (when identity
                                                 {:scope-id        (:workspace-id identity)
                                                  :auth-session-id (:auth-session-id identity)
                                                  :auth-version    (:auth-version identity)}))))
       (catch clojure.lang.ExceptionInfo error
         (let [{:keys [status code]} (ex-data error)]
           (if status
             (do
               (when (= status 401)
                 (ring-store/delete-session (:session-store services) (:session/key request)))
               (if (and (= status 401) (= policy :optional))
                 (handler (assoc request :tover/identity nil :bel/context nil))
                 (response request status (or code :identity-error) (.getMessage error))))
             (throw error))))))))


(defn identity-middleware
  "Compile the explicit application access policy on each route endpoint."
  {:malli/schema [:=> [:cat :map] :map]}
  [services]
  {:name    ::identity
   :compile (fn [data _options]
              (let [policy (validation/check! IdentityPolicy (:tover/access data))]
                #(wrap-identity % services policy)))})


(defn wrap-origin
  {:malli/schema [:=> [:cat [:fn ifn?] :string] [:fn ifn?]]} [handler origin]
  (fn [request]
    (let [supplied (get-in request [:headers "origin"])
          unsafe?  (not (contains? #{:get :head :options} (:request-method request)))
          stream?  (= "/_bel/realtime/stream" (:uri request))]
      (if (and (or unsafe? stream?)
               (or (and supplied (not= supplied origin))
                   (= "cross-site" (get-in request [:headers "sec-fetch-site"]))
                   (and unsafe? (nil? supplied))))
        {:status 403 :headers {"content-type" "text/plain"} :body "Invalid request origin."}
        (handler request)))))

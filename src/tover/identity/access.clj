(ns tover.identity.access
  "Route access. Every request reads its identity from Postgres: the locked session row, the profile, the
  active memberships and the reset boundary. Provider tokens are refreshed under the same lock only so later
  provider calls work; identity never comes from token claims.

  Route data `:tover/access` (required on every route):
  - :optional       anyone; identity when signed in
  - :logout         the session row only, without provider or directory reads
  - :password       signed in, also while a new password is required
  - :finish         like :password and skips the context-version check (one-use email responses)
  - :profile        signed in with a usable password; the profile may be incomplete
  - :authenticated  signed in, password usable, profile complete
  - :workspace      :authenticated plus a selected workspace that is a current active membership
  - :manager        :workspace with role owner or admin"
  (:require
    [bel.validation :as validation]
    [com.brunobonacci.mulog :as mu]
    [ring.util.codec :as codec]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.shared.i18n :as i18n])
  (:import
    [clojure.lang ExceptionInfo]
    [java.time Instant]))


(def Policy
  [:enum :optional :authenticated :workspace :manager :password :finish :logout :profile])


(def Identity
  [:map
   [:user-id :uuid]
   [:user [:map [:user-id :uuid] [:email {:optional true} :string] [:email-confirmed? {:optional true} :boolean]
           [:locale {:optional true} [:enum "en" "ru"]]]]
   [:session-id :uuid]
   [:auth-session-id :string]
   [:auth-version :uuid]
   [:memberships [:vector session/Membership]]
   [:workspace-id [:maybe :uuid]]
   [:role [:maybe :keyword]]
   [:profile-complete? :boolean]
   [:password-required? :boolean]])


(defn failure
  {:malli/schema [:=> [:cat :int :keyword :string] :any]} [status code message]
  (throw (ex-info message {:type :identity :status status :code code :locale i18n/*locale*})))


(defn datastar-request?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [request]
  (= "true" (get-in request [:headers "datastar-request"])))


(defn local-path
  "A same-origin path to return to; anything else becomes the home page.
  Control characters are rejected: browsers drop tab and newline, so /<tab>/host would leave the origin."
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (if (and (string? value) (re-matches #"/(?!/)[^\p{Cntrl}\\]*" value)) value "/"))


(defn identity-of
  "Build the request identity from a valid session row and its directory facts. The selected workspace counts
  only while it is a current active membership; there is no fallback to another membership."
  {:malli/schema [:=> [:cat :string :map session/Directory] Identity]}
  [key row {:keys [profile memberships]}]
  (let [selected (some #(when (= (:workspace-id row) (:workspace-id %)) %) memberships)
        language (i18n/supported (:language profile))]
    {:user-id            (:user-id row)
     :user               (cond-> (merge {:user-id (:user-id row)}
                                        (select-keys (get-in row [:tokens :user]) [:email :email-confirmed?]))
                           language (assoc :locale language))
     :session-id         (:session-id row)
     :auth-session-id    key
     :auth-version       (:version row)
     :memberships        memberships
     :workspace-id       (:workspace-id selected)
     :role               (:role selected)
     :profile-complete?  (session/profile-complete? profile)
     :password-required? (boolean (get-in row [:data :identity/password-required?]))}))


(defn- refreshed
  "Renew expiring provider tokens. A rejected refresh token ends the sign-in; an unavailable provider keeps
  the old pair for a later attempt."
  {:malli/schema [:=> [:cat :any :map] [:or [:map [:tokens :map]] [:map [:error :any]]]]}
  [provider tokens]
  (if (> (long (or (:expires-at tokens) 0)) (+ 30 (.getEpochSecond (Instant/now))))
    {:tokens tokens}
    (try {:tokens (provider/refresh! provider (:refresh-token tokens))}
         (catch ExceptionInfo error
           (if (contains? #{:invalid-refresh-token :unauthenticated} (:code (ex-data error)))
             {:error (ex-info "Please sign in again." {:type :identity :status 401 :code :unauthenticated})}
             (do (mu/log ::refresh-deferred :code (:code (ex-data error)))
                 {:tokens tokens}))))))


(defn current-identity
  "The identity of a valid signed-in session `key`, or nil. Throws a 401 identity error when the provider
  rejected the sign-in (the row is then deleted)."
  {:malli/schema [:=> [:cat [:map [:session-store :any] [:provider {:optional true} :any]] [:maybe :string]] [:maybe Identity]]}
  [{:keys [session-store provider]} key]
  (let [result (session/transact!
                 session-store key
                 (fn [row directory]
                   (if-not (and (session/live? row) (:tokens row) (:user-id row))
                     [row nil]
                     (let [{:keys [tokens error]} (if provider (refreshed provider (:tokens row)) {:tokens (:tokens row)})]
                       (if error
                         [nil {:error error}]
                         (let [row   (assoc row :tokens tokens)
                               facts (directory (:user-id row))]
                           [row (when (session/valid? row (:boundary facts)) (identity-of key row facts))]))))))]
    (if-let [error (:error result)]
      (throw error)
      result)))


(defn- session-identity
  "For sign-out: the session row alone, so ending a sign-in needs neither the provider nor the directory."
  {:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  [{:keys [session-store]} request]
  (let [key (:session/key request)
        row (session/session session-store key)]
    (when (:tokens row)
      {:user-id         (:user-id row)
       :session-id      (:session-id row)
       :auth-version    (:version row)
       :auth-session-id key})))


(defn- requested-path
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [uri query-string]}]
  (local-path (str uri (when query-string (str "?" query-string)))))


(defn- profile-location
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [request-method] :as request}]
  (if (= :get request-method)
    (str "/account/profile?return-to=" (codec/url-encode (requested-path request)))
    "/account/profile"))


(defn- response
  {:malli/schema [:=> [:cat :map :int :keyword :string] :map]} [request status code message]
  (let [location (case code
                   :unauthenticated "/auth/login"
                   :workspace-required "/workspace"
                   :password-required "/auth/password"
                   :profile-required (profile-location request)
                   nil)]
    (if (and location (= :get (:request-method request)) (not (datastar-request? request)))
      (cond-> {:status 303 :headers {"location" location} :body ""}
        ;; Sign-in continues to the requested screen (such as an invitation); it never acts on it.
        (= code :unauthenticated)
        (assoc :session (assoc (:session request) :identity/return-to (requested-path request))))
      {:status  status
       :headers (cond-> {"content-type" "text/plain; charset=utf-8" "x-tover-error" (name code)}
                  location (assoc "x-tover-redirect" location))
       :body    message})))


(defn- with-context-token
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (if-let [token (or (get-in request [:headers "x-tover-context"])
                     (get-in request [:params :_tover-context]))]
    (assoc-in request [:headers "x-tover-context"] token)
    request))


(defn- check!
  "Enforce `policy` for `identity`, in the order a person must resolve them."
  {:malli/schema [:=> [:cat :map [:maybe :map] Policy] :nil]}
  [request identity policy]
  (let [protected? (not (contains? #{:optional :logout} policy))]
    (when (and protected? (nil? identity))
      (failure 401 :unauthenticated "Please sign in."))
    ;; The one-use email response cannot contain the newly rotated session's context yet.
    (when (and identity (not (contains? #{:optional :finish} policy))
               (or (datastar-request? request)
                   (not (contains? #{:get :head :options} (:request-method request)))))
      (when-not (= (str (:auth-version identity)) (get-in request [:headers "x-tover-context"]))
        (failure 409 :workspace-changed "Workspace changed. Reload this page before continuing.")))
    (when (and protected? (not (contains? #{:password :finish} policy)) (:password-required? identity))
      (failure 409 :password-required "Set your password before continuing."))
    (when (and protected? (not (contains? #{:password :finish :profile} policy)) (not (:profile-complete? identity)))
      (failure 409 :profile-required "Add your name before continuing."))
    (when (and (contains? #{:workspace :manager} policy) (nil? (:workspace-id identity)))
      (failure 409 :workspace-required "Create or select a workspace first."))
    (when (and (= policy :manager) (not (contains? #{:owner :admin} (:role identity))))
      (failure 403 :forbidden "Workspace administration requires an owner or admin."))
    nil))


(defn wrap-access
  "Resolve identity and enforce the policy declared on this route. Downstream receives `:tover/identity`,
  `:bel/context` (identity plus :locale; nil when signed out) and `:bel/realtime-context`."
  {:malli/schema [:=> [:cat [:fn ifn?] :map Policy] [:fn ifn?]]}
  [handler services policy]
  (fn [request]
    (try
      (let [request  (with-context-token request)
            identity (when (:identity/session-id (:session request))
                       (if (= policy :logout)
                         (session-identity services request)
                         (current-identity services (:session/key request))))]
        (binding [i18n/*locale* (i18n/resolve-locale (assoc request :tover/identity identity))]
          (check! request identity policy)
          (let [request (assoc request :tover/identity identity)
                locale  (i18n/resolve-locale request)]
            ((i18n/wrap-locale handler)
             (assoc request
                    :bel/context (when identity (assoc identity :locale locale))
                    :bel/realtime-context (when identity
                                            {:scope-id        (:workspace-id identity)
                                             :auth-session-id (:auth-session-id identity)
                                             :auth-version    (:auth-version identity)
                                             :locale          locale}))))))
      (catch ExceptionInfo error
        (let [{:keys [status code]} (ex-data error)]
          (if status
            (if (and (= status 401) (= policy :optional))
              ((i18n/wrap-locale handler) (assoc request :tover/identity nil :bel/context nil))
              (assoc-in (response request status (or code :identity-error) (i18n/error-message error))
                        [:headers "x-tover-language"] (or (:locale (ex-data error)) i18n/*locale*)))
            (throw error)))))))


(defn access-middleware
  "Compile the route's `:tover/access` policy on each endpoint. `services` needs :session-store and :provider."
  {:malli/schema [:=> [:cat :map] :map]}
  [services]
  {:name    ::access
   :compile (fn [data _options]
              (let [policy (validation/check! Policy (:tover/access data))]
                #(wrap-access % services policy)))})


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
        {:status  403
         :headers {"content-type" "text/plain"}
         :body    (i18n/t "Invalid request origin.")}
        (handler request)))))

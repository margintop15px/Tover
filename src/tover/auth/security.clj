(ns tover.auth.security
  (:require
    [bel.validation :as validation]
    [com.brunobonacci.mulog :as mu]
    [ring.middleware.session.store :as ring-store]
    [ring.util.codec :as codec]
    [tover.auth.core :as auth]
    [tover.auth.sessions :as sessions]
    [tover.shared.i18n :as i18n])
  (:import
    [java.time Instant]))


(defn failure
  {:malli/schema [:=> [:cat :int :keyword :string] :any]} [status code message]
  (throw (ex-info message {:type :identity :status status :code code :locale i18n/*locale*})))


(defn- refreshed-tokens
  {:malli/schema [:=> [:cat :any :map] :map]}
  [auth tokens]
  (try (auth/refresh! auth (:refresh-token tokens))
       (catch clojure.lang.ExceptionInfo error
         (if (= :invalid-refresh-token (:code (ex-data error)))
           (failure 401 :unauthenticated "Please sign in again.")
           (throw error)))))


(defn- token-claims
  "Read the claims. A token issued before the Auth hook was enabled is replaced once; then missing claims fail closed."
  {:malli/schema [:=> [:cat :any :map :boolean] [:tuple :map :map]]}
  [auth tokens refreshed?]
  (let [read   #(try {:claims (auth/token-identity auth (:access-token %))}
                     (catch Throwable error {:error error}))
        result (read tokens)]
    (if (and (not refreshed?) (= :identity-claims-missing (:code (ex-data (:error result)))))
      (let [tokens (refreshed-tokens auth tokens)] [tokens (read tokens)])
      [tokens result])))


(defn authenticate
  "Resolve the current user and memberships from token claims, serializing token refresh for this session."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [{:keys [auth session-store]} key]
  (let [result
        (sessions/update-session! session-store key
                                  (fn [{:keys [tokens workspace-id] :as row}]
                                    (if-not tokens
                                      [row nil]
                                      (let [expiring? (<= (long (or (:expires-at tokens) 0)) (+ 30 (.getEpochSecond (Instant/now))))
                                            [tokens {:keys [claims error]}] (token-claims auth (cond->> tokens expiring? (refreshed-tokens auth)) expiring?)
                                            row (assoc row :tokens tokens)]
                                        (if error
                                          ;; Save a rotated pair even if reading its claims fails.
                                          [row {:error error}]
                                          (let [{:keys [user memberships]} claims
                                                selected                   (or (some #(when (= workspace-id (:workspace-id %)) %) memberships)
                                                                               (first memberships))
                                                selected-id                (:workspace-id selected)
                                                row (cond-> (assoc row :user-id (:user-id user) :workspace-id selected-id)
                                                      (not= workspace-id selected-id) (assoc :version (random-uuid)))]
                                            [row {:user-id            (:user-id user) :user user             :memberships memberships
                                                  :profile-complete?  (:profile-complete? claims)
                                                  :password-required? (get-in row [:data :identity/password-required?])
                                                  :workspace-id       selected-id :role     (:role selected)
                                                  :auth-session-id    key :auth-version     (:version row)}]))))))]
    (if-let [error (:error result)] (throw error) result)))


(defn refresh-claims!
  "Reissue this session's tokens so changed profile or membership claims apply now; the page context stays valid."
  {:malli/schema [:=> [:cat :map :string] :nil]}
  [{:keys [auth session-store]} key]
  (sessions/update-session! session-store key
                            (fn [{:keys [tokens] :as row}]
                              [(cond-> row tokens (assoc :tokens (auth/refresh! auth (:refresh-token tokens)))) nil]))
  nil)


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


(defn local-path
  "A same-origin path to return to; anything else becomes the home page.
  Control characters are rejected: browsers drop tab and newline, so /<tab>/host would leave the origin."
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (if (and (string? value) (re-matches #"/(?!/)[^\p{Cntrl}\\]*" value)) value "/"))


(defn- profile-location
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [request-method uri query-string]}]
  (if (= :get request-method)
    (str "/account/profile?return-to=" (codec/url-encode (str uri (when query-string (str "?" query-string)))))
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
      {:status 303 :headers {"location" location} :body ""}
      {:status  status
       :headers (cond-> {"content-type" "text/plain; charset=utf-8" "x-tover-error" (name code)}
                  location (assoc "x-tover-redirect" location))
       :body    message})))


(def IdentityPolicy
  [:enum :optional :authenticated :workspace :manager :password :finish :logout :profile])


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
         (binding [i18n/*locale* (i18n/resolve-locale (assoc request :tover/identity identity))]
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
           (when (and protected? (not (contains? #{:password :finish :profile} policy)) (not (:profile-complete? identity)))
             (failure 409 :profile-required "Add your name before continuing."))
           (when (and (contains? #{:workspace :manager} policy) (nil? (:workspace-id identity)))
             (failure 409 :workspace-required "Create or select a workspace first."))
           (when (= policy :manager)
             (require-manager! identity))
           (let [request (assoc request :tover/identity identity)
                 locale  (i18n/resolve-locale request)]
             ((i18n/wrap-locale handler) (assoc request :bel/context (cond-> (assoc identity :locale locale)
                                                                       identity (assoc :session-id (get-in request [:session :bel/session-id])))
                                                :bel/realtime-context (when identity
                                                                        {:scope-id        (:workspace-id identity)
                                                                         :auth-session-id (:auth-session-id identity)
                                                                         :auth-version    (:auth-version identity)
                                                                         :locale          locale}))))))
       (catch clojure.lang.ExceptionInfo error
         (let [{:keys [status code]} (ex-data error)]
           (if status
             (do
               (when (= :configuration (:type (ex-data error)))
                 (mu/log ::identity-configuration-error :code code :status status))
               (when (= status 401)
                 (ring-store/delete-session (:session-store services) (:session/key request)))
               (if (and (= status 401) (= policy :optional))
                 ((i18n/wrap-locale handler) (assoc request :tover/identity nil :bel/context nil))
                 (assoc-in (response request status (or code :identity-error) (i18n/error-message error))
                           [:headers "x-tover-language"] (or (:locale (ex-data error)) i18n/*locale*))))
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
        {:status 403 :headers {"content-type" "text/plain"} :body (i18n/t "Invalid request origin.")}
        (handler request)))))

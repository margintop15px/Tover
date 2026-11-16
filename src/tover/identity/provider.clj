(ns tover.identity.provider
  "Supabase GoTrue, the external identity provider, reached only through this namespace.

  A provider is `{:request! (fn [{:keys [method path query body token admin?]}] parsed-body)}`; `client` builds
  the HTTP one, tests supply a fake `:request!`. Failures throw ex-info {:type :identity :code :status
  :uncertain?} and never carry provider bodies, passwords or tokens.

  Bel effects (`runtime/effect!`):
  - Credential effects take or return secrets: ::sign-up ::sign-in ::verify ::set-password. Run them only with
    `call!` from an action; never list them in a command's :fx (fx input and outcomes are journaled).
  - Plain effects are safe in :fx with :then/:catch: ::resend ::recover ::sign-out. When run from :fx a
    provider failure is rethrown with :type :temporary, :permanent or :uncertain (the runtime's problem types)."
  (:require
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [charred.api :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http]
    [tover.identity.session :as session]
    [tover.shared.i18n :as i18n])
  (:import
    [java.net URI URLEncoder]
    [java.nio.charset StandardCharsets]
    [java.time Instant]
    [java.util Map UUID]
    [java.util.concurrent ExecutionException]
    [org.httpkit DynamicBytes]
    [org.httpkit.client IFilter]))


(def Text
  [:string {:min 1}])


(def User
  [:map
   [:user-id :uuid]
   [:email :string]
   [:email-confirmed? :boolean]
   [:locale {:optional true} [:enum "en" "ru"]]])


(def Tokens
  [:map
   [:access-token :string]
   [:refresh-token :string]
   [:expires-at :int]
   [:user User]])


(def Provider
  [:map [:request! [:fn ifn?]]])


(defn fail!
  "Throw only application error codes and safe metadata, never provider bodies."
  {:malli/schema
   [:function
    [:=> [:cat :keyword :int :boolean] :any]
    [:=> [:cat :keyword :int :boolean :map] :any]]}
  ([code status uncertain?]
   (fail! code status uncertain? {}))

  ([code status uncertain? details]
   (throw (ex-info "Identity request failed."
                   (merge {:type :identity :code code :status status :uncertain? uncertain?}
                          (select-keys details [:type :retry-after]))))))


(defn check-input!
  "Use the shared validator without attaching submitted credentials to errors."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [schema value]
  (try (validation/check! schema value)
       (catch clojure.lang.ExceptionInfo _
         (fail! :invalid-input 400 false))))


;; HTTP


(defn base-url
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (check-input! :string value)
  (try
    (let [uri (URI/create value)]
      (when-not (and (contains? #{"http" "https"} (.getScheme uri))
                     (.getHost uri)
                     (nil? (.getUserInfo uri))
                     (nil? (.getRawQuery uri))
                     (nil? (.getRawFragment uri)))
        (fail! :invalid-configuration 500 false))
      (str/replace value #"/+$" ""))
    (catch IllegalArgumentException _
      (fail! :invalid-configuration 500 false))))


(defn query
  {:malli/schema [:=> [:cat [:maybe :map]] :string]}
  [params]
  (if (seq params)
    (str "?" (str/join "&" (for [[key value] params]
                             (str (URLEncoder/encode (name key) StandardCharsets/UTF_8)
                                  "=" (URLEncoder/encode (str value) StandardCharsets/UTF_8)))))
    ""))


(def ^:private error-codes
  {"invalid_credentials"        :invalid-credentials
   "email_not_confirmed"        :email-not-confirmed
   "email_exists"               :email-exists
   "user_already_exists"        :email-exists
   "otp_expired"                :verification-expired
   "otp_disabled"               :verification-invalid
   "bad_jwt"                    :unauthenticated
   "no_authorization"           :unauthenticated
   "session_not_found"          :unauthenticated
   "user_not_found"             :unauthenticated
   "refresh_token_not_found"    :invalid-refresh-token
   "refresh_token_already_used" :invalid-refresh-token
   "weak_password"              :weak-password
   "same_password"              :same-password
   "over_email_send_rate_limit" :rate-limited
   "over_request_rate_limit"    :rate-limited
   "email_address_invalid"      :invalid-input
   "validation_failed"          :invalid-input})


(defn- read-body
  {:malli/schema [:=> [:cat :string :boolean] :any]}
  [body uncertain?]
  (when-not (str/blank? body)
    (try (json/read-json body :key-fn keyword)
         (catch Exception _
           (fail! :invalid-response 502 uncertain?)))))


(defn request!
  "One bounded HTTP exchange, including body reads. Never follow redirects or retry writes."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [{:keys [client api-key timeout-ms] :or {timeout-ms 15000}}
   {:keys [method url token body] :or {method :post}}]
  (let [timeout-ms (min 15000 (max 1 timeout-ms))
        deadline   (+ (System/nanoTime) (* timeout-ms 1000000))
        write?     (not= :get method)
        pending    (future
                     @(http/request
                        {:client           client
                         :url              url
                         :method           method
                         :headers          (cond-> {"Accept" "application/json" "Content-Type" "application/json" "apikey" api-key}
                                             token (assoc "Authorization" (str "Bearer " token)))
                         :body             (when (some? body) (json/write-json-str body))
                         :as               :text
                         :timeout          timeout-ms
                         :follow-redirects false
                         :keepalive        -1
                         ;; Abort active responses after the caller's deadline too.
                         :filter           (reify IFilter
                                             (^boolean accept [_ ^Map _headers] (< (System/nanoTime) deadline))
                                             (^boolean accept [_ ^DynamicBytes _body] (< (System/nanoTime) deadline)))}))
        {:keys [status headers body error]}
        (try
          ;; Include synchronous DNS and body reads in the caller's deadline.
          (deref pending timeout-ms {:error :timeout})
          (catch ExecutionException _ {:error :request-failed})
          (catch InterruptedException _
            (.interrupt (Thread/currentThread))
            {:error :interrupted}))
        body       (or body "")]
    (when error
      (future-cancel pending)
      (fail! :unavailable 503 write?))
    (if (<= 200 status 299)
      (read-body body write?)
      (let [data        (try (read-body body false) (catch Exception _ nil))
            code        (or (get error-codes (:error_code data)) (get error-codes (:code data))
                            (cond (>= status 500) :unavailable
                                  (= status 429) :rate-limited
                                  (= status 401) :unauthenticated
                                  (= status 403) :forbidden
                                  :else :invalid-input))
            retry-after (:retry-after headers)
            retry-after (when (and retry-after (re-matches #"\d{1,6}" retry-after))
                          (Long/parseLong retry-after))]
        (fail! code (if (>= status 500) 503 status)
               (and write? (>= status 500))
               (cond-> {} retry-after (assoc :retry-after retry-after)))))))


(defn client
  "The GoTrue HTTP provider; the same adapter serves hosted and self-hosted Supabase."
  {:malli/schema [:=> [:cat :map] Provider]}
  [{:keys [auth-url api-key admin-key timeout-ms]}]
  (check-input! Text api-key)
  (check-input! Text admin-key)
  (let [base      (base-url auth-url)
        transport (cond-> {:client @http/default-client :api-key api-key}
                    timeout-ms (assoc :timeout-ms timeout-ms))]
    {:request! (fn [{:keys [admin? path] :as options}]
                 (request! (cond-> transport admin? (assoc :api-key admin-key))
                           (-> (select-keys options [:method :token :body])
                               (assoc :url (str base path (query (:query options))))
                               (cond-> admin? (assoc :token admin-key)))))}))


(defn health!
  "Probe the provider's health endpoint without side effects."
  {:malli/schema [:=> [:cat [:map [:auth-url :string] [:api-key :string]]] :any]}
  [{:keys [auth-url api-key]}]
  (request! {:client @http/default-client :api-key api-key :timeout-ms 2000}
            {:url (str (base-url auth-url) "/health") :method :get}))


;; Operations


(defn- user
  {:malli/schema [:=> [:cat :any] User]}
  [data]
  (try
    (check-input! [:map [:id :string] [:email :string]] data)
    (cond-> {:user-id          (UUID/fromString (:id data))
             :email            (:email data)
             :email-confirmed? (boolean (:email_confirmed_at data))}
      (i18n/supported (get-in data [:user_metadata :locale]))
      (assoc :locale (get-in data [:user_metadata :locale])))
    (catch Exception _
      (fail! :invalid-response 502 false))))


(defn- tokens
  {:malli/schema [:=> [:cat :any] Tokens]}
  [data]
  (try
    (check-input! [:map [:access_token Text] [:refresh_token Text]
                   [:expires_in {:optional true} :int] [:expires_at {:optional true} :int]] data)
    (let [expires-at (or (:expires_at data)
                         (when-let [seconds (:expires_in data)]
                           (+ (.getEpochSecond (Instant/now)) seconds)))]
      (when-not (and (integer? expires-at) (pos? expires-at))
        (fail! :invalid-response 502 true))
      {:access-token  (:access_token data)
       :refresh-token (:refresh_token data)
       :expires-at    expires-at
       :user          (user (:user data))})
    (catch Exception _
      (fail! :invalid-response 502 true))))


(def EmailInput
  [:map [:email Text] [:redirect-to {:optional true} Text]])


(defn- redirect
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [redirect-to]}]
  (when redirect-to {:redirect_to redirect-to}))


(defn- call
  {:malli/schema [:=> [:cat Provider :map] :any]}
  [{:keys [request!]} options]
  (request! options))


(defn sign-up!
  "Register an account. Returns {:user …}, or Tokens when the provider confirms immediately."
  {:malli/schema [:=> [:cat Provider :map] [:or [:map [:user User]] Tokens]]}
  [provider input]
  (check-input! [:map [:email Text] [:password Text] [:redirect-to {:optional true} Text]
                 [:locale {:optional true} [:enum "en" "ru"]]] input)
  (let [data (call provider {:path "/signup" :query (redirect input)
                             :body (assoc (select-keys input [:email :password])
                                          :data (select-keys input [:locale]))})]
    (if (:access_token data) (tokens data) {:user (user (or (:user data) data))})))


(defn sign-in!
  {:malli/schema [:=> [:cat Provider :map] Tokens]}
  [provider input]
  (check-input! [:map [:email Text] [:password Text]] input)
  (tokens (call provider {:path "/token" :query {:grant_type "password"} :body (select-keys input [:email :password])})))


(defn refresh!
  {:malli/schema [:=> [:cat Provider :string] Tokens]}
  [provider refresh-token]
  (tokens (call provider {:path "/token" :query {:grant_type "refresh_token"} :body {:refresh_token refresh-token}})))


(defn verify!
  "Redeem an email link (`token_hash` and `type` from the link)."
  {:malli/schema [:=> [:cat Provider :map] Tokens]}
  [provider input]
  (check-input! [:map [:token-hash Text] [:type [:enum :signup :invite :recovery :magiclink :email]]] input)
  (tokens (call provider {:path "/verify" :body {:token_hash (:token-hash input) :type (name (:type input))}})))


(defn resend!
  "Send another signup confirmation email."
  {:malli/schema [:=> [:cat Provider :map] :map]}
  [provider input]
  (check-input! EmailInput input)
  (call provider {:path "/resend" :query (redirect input) :body {:type "signup" :email (:email input)}})
  {})


(defn recover!
  "Send a password recovery email. Unknown addresses look the same to the caller."
  {:malli/schema [:=> [:cat Provider :map] :map]}
  [provider input]
  (check-input! EmailInput input)
  (call provider {:path "/recover" :query (redirect input) :body {:email (:email input)}})
  {})


(defn set-password!
  {:malli/schema [:=> [:cat Provider :string :string] User]}
  [provider access-token password]
  (check-input! Text password)
  (user (call provider {:path "/user" :method :put :token access-token :body {:password password}})))


(defn sign-out!
  "End provider sessions: `local` this one, `others` every other one of the user, `global` all."
  {:malli/schema [:=> [:cat Provider :string [:enum "local" "others" "global"]] :map]}
  [provider access-token scope]
  (call provider {:path "/logout" :query {:scope scope} :token access-token})
  {})


;; Bel effects


(defn call!
  "Run a provider effect synchronously from an action, with the Bel system's resources.
  The only way to run the credential effects."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [context id input]
  (runtime/effect! id {:resources (get-in context [:bel/system :resources]) ::direct? true} input))


(defn- provider!
  {:malli/schema [:=> [:cat :map] Provider]}
  [{:keys [resources]}]
  (or (:provider resources) (throw (ex-info "Missing identity provider" {:type :configuration}))))


(defn- direct!
  {:malli/schema [:=> [:cat :map] :nil]}
  [context]
  (when-not (::direct? context)
    (throw (ex-info "Credential effects run only through provider/call!" {:type :permanent})))
  nil)


(defn- outcome
  "Inside :fx, report provider failures with the runtime's problem types; codes stay for the continuation."
  {:malli/schema [:=> [:cat :map [:fn fn?]] :any]}
  [context f]
  (if (::direct? context)
    (f)
    (try (f)
         (catch clojure.lang.ExceptionInfo error
           (let [{:keys [status uncertain?] :as data} (ex-data error)]
             (if (= :identity (:type data))
               (throw (ex-info (.getMessage error)
                               (assoc (select-keys data [:code :status :retry-after])
                                      :type (cond uncertain? :uncertain
                                                  (or (= 429 status) (>= (long status) 500)) :temporary
                                                  :else :permanent))))
               (throw error)))))))


(defn- session-tokens
  "The stored tokens of a live session, found by its secret key (direct calls) or its Bel session id."
  {:malli/schema [:=> [:cat :map :map] Tokens]}
  [{:keys [resources]} {:keys [session-key session-id]}]
  (let [store (:session-store resources)
        key   (or session-key (when session-id (session/session-key store session-id)))]
    (or (:tokens (session/session store key))
        (fail! :unauthenticated 401 false))))


(defmethod runtime/effect! ::sign-up
  [_ context input]
  (direct! context)
  (sign-up! (provider! context) input))


(defmethod runtime/effect! ::sign-in
  [_ context input]
  (direct! context)
  (sign-in! (provider! context) input))


(defmethod runtime/effect! ::verify
  [_ context input]
  (direct! context)
  (verify! (provider! context) input))


(defmethod runtime/effect! ::set-password
  ;; Input {:password … :session-key …}; returns the provider's user.
  [_ context {:keys [password] :as input}]
  (direct! context)
  (set-password! (provider! context) (:access-token (session-tokens context input)) password))


(defmethod runtime/effect! ::resend
  [_ context input]
  (outcome context #(resend! (provider! context) input)))


(defmethod runtime/effect! ::recover
  [_ context input]
  (outcome context #(recover! (provider! context) input)))


(defmethod runtime/effect! ::sign-out
  ;; Input {:session-id uuid :scope "local"|"others"|"global"} (or :session-key in a direct call).
  [_ context {:keys [scope] :or {scope "local"} :as input}]
  (outcome context #(sign-out! (provider! context) (:access-token (session-tokens context input)) scope)))

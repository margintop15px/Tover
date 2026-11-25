(ns tover.identity.sign-in.web
  "02 · Sign in and find workspace access: the Sign In screen, establishing a sign-in and continuing to the
  next authorized screen. The helpers here are shared by the other identity screens."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.handler :as html]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.identity.access :as access]
    [tover.identity.limits :as limits]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.identity.shell :as shell]
    [tover.identity.sign-in.model :as model]
    [tover.identity.sign-out.web :as sign-out]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [field form]]
    [tover.shared.web.address :as address]
    [tover.shared.web.core :as web]))


;; Shared screen helpers


(defn hidden
  {:malli/schema [:=> [:cat :string :any] schema/Hiccup]}
  [name value]
  [:input {:type "hidden" :name name :value (str value)}])


(defn page-response
  "Render page `id` in answer to a form post with `message` as its alert, keeping only `retained` fields."
  {:malli/schema [:=> [:cat :map :qualified-keyword :int :string [:map-of :string :any]] :map]}
  [request id status message retained]
  (let [response (html/error-page-response (assoc request :tover/error message) id
                                           (into {} (remove (comp nil? val)) retained))]
    (cond-> response (= 200 (:status response)) (assoc :status status))))


(defn failure
  "A provider failure as a public message and status that never reveal account status."
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)]] [:map [:outcome :keyword] [:status :int] [:message :string]]]}
  [error]
  (let [data    (ex-data error)
        outcome (if (= :identity (:type data)) (model/outcome data) :unavailable)
        data    (or data {})]
    {:outcome outcome
     :status  (case outcome :invalid 422 :rejected 400 :link-invalid 400 :throttled 429 :neutral 200 503)
     :message (case outcome
                :rejected (i18n/t "Invalid email or password.")
                :throttled (if-let [seconds (:retry-after data)]
                             (i18n/t "Too many requests. Try again in {seconds} seconds." {:seconds seconds})
                             (i18n/t "Too many requests. Please try again later."))
                :link-invalid (i18n/t "This link has expired or has already been used.")
                :unknown (i18n/t "We could not confirm whether this request went through. Check your email or use another option below.")
                (i18n/error-message (assoc (select-keys data [:code]) :message "Identity request failed.")))}))


(defn too-soon
  "The email limit's answer, the same for every email."
  {:malli/schema [:=> [:cat :int] :string]}
  [seconds]
  (i18n/t "Too many requests. Try again in {seconds} seconds." {:seconds seconds}))


(defn signed-in-choice
  "An already signed-in person keeps that account or explicitly signs out first; `return-to` is where the
  sign-out continues (an unused email link stays unused)."
  {:malli/schema [:=> [:cat [:map [:request :map] [:return-to {:optional true} [:maybe :string]]]] schema/Hiccup]}
  [{:keys [request return-to]}]
  [:div.identity-signed-in
   [:p (i18n/t "You are signed in as {email}." {:email (get-in request [:tover/identity :user :email] "")})]
   [:p (i18n/t "To use this email link with another account, sign out first. The link stays unused until you do.")]
   [:p [:a {:href "/auth/login"} (i18n/t "Continue with this account")]]
   (sign-out/sign-out-form {:request request :return-to return-to})])


;; Establishing a sign-in


(defn session-cookie
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [origin]} key]
  {"tover-session" {:value  key :path "/" :http-only true :same-site :lax
                    :secure (str/starts-with? (str origin) "https:")}})


(defn record!
  "Record User Signed In for the ordinary sign-in `row`, acting as that sign-in."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [id user-id session-id created-at expires-at]}]
  (runtime/execute! (assoc context :user-id user-id :session-id session-id :auth-session-id id)
                    ::model/sign-in
                    {:command-id (model/command-id session-id)
                     :input      {:session-id session-id :authenticated-at created-at :expires-at expires-at}}))


(defn establish!
  "Start a fresh sign-in for verified provider `tokens`: a new secret key, a new fixed 30-day deadline and,
  for an ordinary sign-in, its User Signed In fact. A restricted recovery sign-in records nothing yet. If the
  fact cannot be recorded the row is removed: accepted credentials alone are no sign-in. Returns the row."
  {:malli/schema [:=> [:cat :map :map :boolean] :map]}
  [{:keys [session-store] :as context} tokens password-required?]
  (let [key (session/random-key)
        row (assoc (session/new-row key)
                   :tokens tokens :user-id (get-in tokens [:user :user-id])
                   :data {:ring.middleware.anti-forgery/anti-forgery-token (session/random-key)
                          :identity/password-required? password-required?})]
    (session/transact! session-store key (fn [_ _] [row nil]))
    (when-not password-required?
      (try (record! context row)
           (catch Exception error
             (session/transact! session-store key (fn [_ _] [nil nil]))
             (throw error))))
    row))


(defn as-session
  "Render an answer for the sign-in `row` this request just established: its forms carry the new
  session's anti-forgery token and context."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [request row]
  (assoc request
         :anti-forgery-token (get-in row [:data :ring.middleware.anti-forgery/anti-forgery-token])
         :tover/identity {:auth-version (:version row) :session-id (:session-id row)}))


(defn- retry-page
  "Signed in, but the next step could not be read: keep the sign-in and retry only that read."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] :map]}
  [request row return-to]
  (assoc (shell/page (as-session request row) (i18n/t "Sign in")
                     [:div
                      [:p {:role "alert"} (i18n/t "You are signed in, but your account could not be loaded.")]
                      [:form {:method "post" :action (bel/action-url ::continue)}
                       (hidden "__anti-forgery-token" (get-in row [:data :ring.middleware.anti-forgery/anti-forgery-token]))
                       (hidden "_tover-context" (:version row))
                       (when return-to (hidden "return-to" return-to))
                       [:button {:type "submit"} (i18n/t "Retry")]]])
         :status 503))


(defn requested
  "The screen to continue to: the posted `return-to`, else the one a protected route (such as an invitation)
  kept in the anonymous session as `:identity/return-to`."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :string]]}
  [request return-to]
  (or (not-empty return-to) (get-in request [:session :identity/return-to])))


(defn signed-in-response
  "Answer a form post that established `row`: the browser gets the new key (the previous anonymous session
  is dropped) and continues to its next authorized step."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] :map]}
  [{:keys [request] :as context} row return-to]
  (let [return-to (requested request return-to)
        identity  (try (access/current-identity context (:id row)) (catch Exception _ ::unavailable))]
    (assoc (if (map? identity)
             {:status 303 :headers {"location" (model/next-step identity return-to)} :body ""}
             (retry-page request row return-to))
           :session nil
           :cookies (session-cookie context (:id row)))))


(defn continue-signed-in
  "Route middleware for public entry screens: a usable sign-in continues without another prompt."
  {:malli/schema [:=> [:cat [:fn ifn?]] [:fn ifn?]]}
  [handler]
  (fn [request]
    (if-let [identity (:tover/identity request)]
      {:status 303 :headers {"location" (model/next-step identity (requested request (get-in request [:params :return-to])))}
       :body   ""}
      (handler request))))


;; Screens


(defn invitation-hint
  "A visit that started from an invitation link says so, because the invitee may not have an account yet."
  {:malli/schema [:=> [:cat :map] [:maybe schema/Hiccup]]}
  [request]
  (when (str/starts-with? (str (get-in request [:session :identity/return-to])) "/invitations/")
    [:p {:role "status"} (i18n/t "To open your invitation, sign in or create an account with the email address it was sent to.")]))


(defpage login
  {:route      "/auth/login"
   :title      "Sign in · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :optional :middleware [continue-signed-in]}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content
           request (i18n/t "Sign in")
           [:div
            (invitation-hint request)
            (form request (bel/action-url ::sign-in) (i18n/t "Sign in")
                  (field request "email" (i18n/t "Email") "email")
                  (field request "password" (i18n/t "Password") "password")
                  (when-let [return-to (model/destination (get-in request [:params :return-to]))]
                    (hidden "return-to" return-to)))
            [:p [:a {:href "/auth/signup"} (i18n/t "Create an account")] " · "
             [:a {:href "/auth/recover"} (i18n/t "Forgot password?")] " · "
             [:a {:href "/auth/resend"} (i18n/t "Need a new confirmation email?")]]])})


(defn- settle!
  "Record a reserved attempt's outcome. A failure here never changes the answer: an unsettled attempt stops
  counting by itself."
  {:malli/schema [:=> [:cat :any :uuid [:enum :failed :succeeded :discarded]] :nil]}
  [limits attempt-id outcome]
  (try (limits/-settle-attempt! limits attempt-id outcome)
       (catch Exception error
         (mu/log ::attempt-not-settled :outcome outcome :error-type (.getName (class error)))))
  nil)


(defn- attempt!
  "Sign in within a reserved attempt. Only credentials the provider rejected count as failed; a service
  failure, an unknown outcome or a sign-in that could not be recorded does not."
  {:malli/schema [:=> [:cat :map :uuid [:map [:email :string] [:password :string]]] :map]}
  [{:keys [limits] :as context} attempt-id credentials]
  (try
    (let [row (establish! context (provider/call! context ::provider/sign-in credentials) false)]
      (settle! limits attempt-id :succeeded)
      row)
    (catch Throwable error
      (settle! limits attempt-id (if (contains? #{:rejected :invalid} (:outcome (failure error))) :failed :discarded))
      (throw error))))


(defn- attempts-exceeded
  {:malli/schema [:=> [:cat :int] :string]}
  [seconds]
  (i18n/t "Too many sign-in attempts. Try again in {minutes} min." {:minutes (quot (+ seconds 59) 60)}))


(defaction sign-in
  {:schema     [:map [:email {:optional true} :string] [:password {:optional true} :string]
                [:return-to {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [request limits trusted-proxy?] :as context} {:keys [email password return-to]}]
  (if-let [identity (:tover/identity request)]
    ;; An obsolete attempt never replaces the current sign-in; changing accounts needs Sign Out first.
    {:status 303 :headers {"location" (model/next-step identity (requested request return-to))} :body ""}
    (let [credentials {:email (str/trim (or email "")) :password (or password "")}
          refused     (fn [status message]
                        (page-response request ::login status message
                                       {"email" email "return-to" (model/destination return-to)}))]
      (if (some str/blank? (vals credentials))
        (refused 400 (i18n/t "Invalid email or password."))
        (try
          (let [{:keys [attempt-id retry-after]} (limits/-start-attempt! limits (limits/email-key (:email credentials))
                                                                         (address/visitor request trusted-proxy?))]
            (if retry-after
              (refused 429 (attempts-exceeded retry-after))
              (signed-in-response context (attempt! context attempt-id credentials) return-to)))
          (catch Exception error
            (let [{:keys [outcome status message]} (failure error)]
              (if (= :invalid outcome)
                ;; Input the provider would not check is a wrong email or password here.
                (refused 400 (i18n/t "Invalid email or password."))
                (refused status message)))))))))


(defaction continue
  {:schema     [:map [:return-to {:optional true} :string]]
   :route-data {:tover/access :password}}
  [{:keys [request]} {:keys [return-to]}]
  {:status 303 :headers {"location" (model/next-step (:tover/identity request) (requested request return-to))} :body ""})

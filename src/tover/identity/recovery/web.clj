(ns tover.identity.recovery.web
  "02a · Recover account access: Forgot Password, Check Your Recovery Email, and Set New Password from the
  recovery link (or `/auth/password` for a restricted recovery sign-in)."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.identity.input :as input]
    [tover.identity.limits :as limits]
    [tover.identity.provider :as provider]
    [tover.identity.recovery.model :as model]
    [tover.identity.reset-completion.model :as completion]
    [tover.identity.reset-completion.web :as completion-web]
    [tover.identity.session :as session]
    [tover.identity.shell :as shell]
    [tover.identity.sign-in.model :as sign-in-model]
    [tover.identity.sign-in.web :as sign-in]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [field form password-field]]
    [tover.shared.web.core :as web])
  (:import
    [clojure.lang ExceptionInfo]))


;; Request a recovery message


(defpage recover
  {:route      "/auth/recover"
   :title      "Recover your account · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content
           request (i18n/t "Recover your account")
           [:div
            (form request (bel/action-url ::request-recovery) (i18n/t "Send recovery email")
                  (field request "email" (i18n/t "Email") "email"))
            [:p [:a {:href "/auth/login"} (i18n/t "Back to sign in")]]])})


(defn- check-recovery-email
  {:malli/schema [:=> [:cat :map :string [:maybe :string] :int] :map]}
  [request email message status]
  (assoc (shell/page request (i18n/t "Check your email")
                     [:div
                      [:p (or message (i18n/t "If recovery is available and the message can be sent, follow the link in your email."))]
                      (form request (bel/action-url ::request-recovery) (i18n/t "Send another recovery email")
                            (field (assoc-in request [:params :email] email) "email" (i18n/t "Email") "email"))
                      [:p [:a {:href "/auth/login"} (i18n/t "Back to sign in")]]])
         :status status))


(defaction request-recovery
  ;; Password Recovery Requested is the provider's fact; the answer is given before the provider is asked, the
  ;; same for every email, and nothing changes the password or ends a sign-in.
  {:schema     [:map [:email {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [request origin limits] :as context} {:keys [email]}]
  (let [email (str/trim (or email ""))]
    (if-let [error (input/email-error email)]
      (sign-in/page-response request ::recover 422 error {"email" email})
      (if-let [seconds (limits/-start-email! limits :recovery (limits/email-key email))]
        (check-recovery-email request email (sign-in/too-soon seconds) 429)
        (do (provider/call-later! context ::provider/recover {:email email :redirect-to (str origin "/auth/confirm")})
            (check-recovery-email request email nil 200))))))


;; Set a new password


(def PasswordFormProps
  [:map [:action :string] [:csrf :any] [:context :any] [:fields {:optional true} [:map-of :string :string]]])


(defn new-password-form
  {:malli/schema [:=> [:cat PasswordFormProps] schema/Hiccup]}
  [{:keys [action csrf context fields]}]
  (into [:form {:method "post" :action action}
         (sign-in/hidden "__anti-forgery-token" csrf)
         (sign-in/hidden "_tover-context" context)]
        (concat (for [[name value] fields] (sign-in/hidden name value))
                [[:p (i18n/t "Resetting your password ends your other sign-ins.")]
                 (password-field {:label (i18n/t "New password") :autocomplete "new-password"})
                 [:button {:type "submit"} (i18n/t "Reset password")]])))


(defn- change-password!
  "Retain a non-secret attempt, request the change from the provider, then complete it or record a definite
  rejection. An unknown provider outcome stays unknown: the password is never resubmitted automatically."
  {:malli/schema [:=> [:cat :map :map :string] [:map [:outcome :keyword] [:message {:optional true} :string]]]}
  [{:keys [session-store] :as context} row password]
  (let [key        (:id row)
        acting     (completion-web/acting context row)
        attempt-id (random-uuid)]
    (runtime/execute! acting ::model/reset-password
                      {:command-id (str "attempt/" attempt-id) :input {:attempt-id attempt-id :session-id (:session-id row)}})
    (session/update-session! session-store key #(vector (assoc-in % [:data :identity/reset-attempt] attempt-id) nil))
    (if-let [failure (try (provider/call! context ::provider/set-password {:password password :session-key key})
                          nil
                          (catch ExceptionInfo error (sign-in/failure error)))]
      (if (= :invalid (:outcome failure))
        (do (runtime/execute! acting ::model/reject-password-reset {:command-id (str "reject/" attempt-id) :input {:attempt-id attempt-id}})
            {:outcome :rejected :message (:message failure)})
        {:outcome :unconfirmed})
      (let [valid-after (completion-web/now)]
        (try
          (completion-web/finish! context key attempt-id valid-after)
          {:outcome :completed}
          (catch Exception _
            (try (session/update-session! session-store key #(vector (assoc-in % [:data :identity/reset-pending?] true) nil))
                 (catch Exception _ nil))
            (completion-web/resume! context key attempt-id valid-after)
            {:outcome :pending}))))))


(defn- password-page
  "The Set New Password screen, or the retained reset's status when the last change is not settled."
  {:malli/schema [:=> [:cat :map [:map [:status [:maybe :string]] [:pending? :boolean] [:csrf :any] [:context :any]
                                  [:message {:optional true} [:maybe :string]]]] schema/Hiccup]}
  [request {:keys [status pending? csrf context message]}]
  (let [form-view (new-password-form {:action (bel/action-url ::set-password) :csrf csrf :context context})
        request   (cond-> request message (assoc :tover/error message))]
    (shell/page-content request (i18n/t "Set your password")
                        (if (contains? #{"requested" "completed" "unavailable"} status)
                          (completion-web/status-view {:request request :status status :pending? pending?} form-view)
                          form-view))))


(defn- outcome-response
  "Answer a reset submission from the sign-in `row` (`fresh?` when this request established it)."
  {:malli/schema [:=> [:cat :map :map :boolean [:map [:outcome :keyword] [:message {:optional true} :string]]] :map]}
  [{:keys [request session-store] :as context} row fresh? {:keys [outcome message]}]
  (if (= :completed outcome)
    (if fresh?
      (sign-in/signed-in-response context row nil)
      {:status  303 :body ""
       :headers {"location" (sign-in-model/next-step (assoc (:tover/identity request) :password-required? false) nil)}})
    (let [row     (or (session/session session-store (:id row)) row)
          request (if fresh? (sign-in/as-session request row) request)
          content (password-page request {:status   (case outcome :rejected nil "requested")
                                          :pending? (= :pending outcome)
                                          :csrf     (get-in row [:data :ring.middleware.anti-forgery/anti-forgery-token])
                                          :context  (:version row)
                                          :message  message})]
      (cond-> (assoc (shell/page request (i18n/t "Set your password") content)
                     :status (case outcome :rejected 422 :pending 202 503))
        fresh? (assoc :session nil :cookies (sign-in/session-cookie context (:id row)))))))


(defaction reset-password
  ;; From the recovery link. Opening the link changed nothing; this explicit submission verifies the
  ;; recovery authority, which then permits only completing this account's reset.
  {:schema     [:map [:token-hash {:optional true} :string] [:password {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [request] :as context} {:keys [token-hash password]}]
  (let [retained {"token_hash" token-hash "type" "recovery"}]
    (cond
      (:tover/identity request)
      ;; Never silently change accounts: keep this sign-in or sign out first; the link stays unused.
      (sign-in/page-response request :tover.identity.confirm.web/confirm 409
                             (i18n/t "You are signed in as {email}." {:email (get-in request [:tover/identity :user :email] "")})
                             retained)

      ;; Refused before the link is used.
      (input/new-password-error (or password ""))
      (sign-in/page-response request :tover.identity.confirm.web/confirm 422 (input/new-password-error (or password "")) retained)

      :else
      (let [verified (try {:tokens (provider/call! context ::provider/verify {:token-hash (or token-hash "") :type :recovery})}
                          (catch ExceptionInfo error {:failure (sign-in/failure error)}))]
        (if-let [tokens (:tokens verified)]
          (let [row (sign-in/establish! context tokens true)]
            (outcome-response context row true (change-password! context row (or password ""))))
          (let [{:keys [outcome status message]} (:failure verified)]
            (if (= :link-invalid outcome)
              ;; Expired, invalid or used: offer a new recovery request or ordinary sign-in.
              (sign-in/page-response request ::recover status message {})
              (sign-in/page-response request :tover.identity.confirm.web/confirm status message retained))))))))


(defn- reset-status
  {:malli/schema [:=> [:cat :map [:maybe :uuid]] [:maybe :string]]}
  [context attempt-id]
  (when attempt-id
    (try (:status (runtime/read-model context ::completion/reset-status {:attempt-id attempt-id}))
         (catch Exception _ "unavailable"))))


(defn- settled
  "Route middleware: an ordinary sign-in with no reset to show continues to its next step."
  {:malli/schema [:=> [:cat [:fn ifn?]] [:fn ifn?]]}
  [handler]
  (fn [request]
    (let [identity (:tover/identity request)]
      (if (and identity (not (:password-required? identity)) (nil? (get-in request [:session :identity/reset-attempt])))
        {:status 303 :headers {"location" (sign-in-model/next-step identity nil)} :body ""}
        (handler request)))))


(defn- query-password
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} _input]
  {:request request
   :status  (reset-status context (get-in request [:session :identity/reset-attempt]))
   :pending (boolean (get-in request [:session :identity/reset-pending?]))})


(defpage password
  {:route      "/auth/password"
   :title      "Set your password · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :password :middleware [settled]}
   :query      query-password}
  [{:keys [request status pending]}]
  {:type :elements
   :html (password-page request {:status   status
                                 :pending? pending
                                 :csrf     (force (:anti-forgery-token request))
                                 :context  (get-in request [:tover/identity :auth-version])})})


(defaction set-password
  {:schema     [:map [:password {:optional true} :string]]
   :route-data {:tover/access :password}}
  [{:keys [request session-store] :as context} {:keys [password]}]
  (let [identity (:tover/identity request)]
    (cond
      (not (:password-required? identity))
      {:status 303 :headers {"location" (sign-in-model/next-step identity nil)} :body ""}

      (input/new-password-error (or password ""))
      (sign-in/page-response request ::password 422 (input/new-password-error (or password "")) {})

      :else
      (let [row (session/session session-store (:session/key request))]
        (outcome-response context row false (change-password! context row (or password "")))))))

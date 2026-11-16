(ns tover.identity.confirm.web
  "01 · Open the confirmation email link and confirm ownership. The link page (`/auth/confirm`, also the
  recovery link's landing page) is inert: only an explicit POST uses the link. Confirm Email is a provider
  effect and Email Confirmed the provider's fact; confirmation creates no workspace, grants no membership and
  accepts no invitation."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [clojure.string :as str]
    [tover.identity.provider :as provider]
    [tover.identity.recovery.web :as recovery]
    [tover.identity.shell :as shell]
    [tover.identity.sign-in.web :as sign-in]
    [tover.shared.i18n :as i18n]
    [tover.shared.ui.forms :refer [form]]
    [tover.shared.web.core :as web])
  (:import
    [clojure.lang ExceptionInfo]))


(def confirmation-types
  #{"email" "signup"})


(defn- link-url
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [uri query-string]}]
  (str uri (when (seq query-string) (str "?" query-string))))


(defn- link-content
  {:malli/schema [:=> [:cat :map] :any]}
  [request]
  (let [{:keys [token_hash type]} (:params request)
        token-hash                (or token_hash (get-in request [:params :token-hash]))]
    (cond
      ;; Signed in: show only that account and require an explicit choice before the link is used.
      (:tover/identity request)
      (sign-in/signed-in-choice {:request request :return-to (link-url request)})

      (str/blank? token-hash)
      [:div [:p (i18n/t "This link is incomplete.")]
       [:p [:a {:href "/auth/resend"} (i18n/t "Need a new confirmation email?")] " · "
        [:a {:href "/auth/recover"} (i18n/t "Forgot password?")]]]

      (= "recovery" type)
      [:div
       (recovery/new-password-form {:action  (bel/action-url ::recovery/reset-password) :csrf (force (:anti-forgery-token request))
                                    :context "" :fields {"token-hash" token-hash}})
       [:p [:a {:href "/auth/recover"} (i18n/t "Request a new recovery email")]]]

      (contains? confirmation-types type)
      [:div
       [:p (i18n/t "Confirm this email address to finish creating your account.")]
       (form request (bel/action-url ::confirm-email) (i18n/t "Confirm email")
             (sign-in/hidden "token-hash" token-hash) (sign-in/hidden "type" type))
       [:p [:a {:href "/auth/resend"} (i18n/t "Need a new confirmation email?")]]]

      :else
      [:div [:p (i18n/t "This link has expired or has already been used.")]
       [:p [:a {:href "/auth/resend"} (i18n/t "Need a new confirmation email?")] " · "
        [:a {:href "/auth/recover"} (i18n/t "Forgot password?")]]])))


(defpage confirm
  {:route      "/auth/confirm"
   :title      "Confirm email link · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content request (i18n/t (if (= "recovery" (get-in request [:params :type])) "Set your password" "Confirm email link"))
                             (link-content request))})


(defaction confirm-email
  {:schema     [:map [:token-hash {:optional true} :string] [:type {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [request] :as context} {:keys [token-hash type]}]
  (let [retained {"token_hash" token-hash "type" type}]
    (cond
      ;; Never silently change accounts: the link stays unused until this sign-in is kept or ended.
      (:tover/identity request)
      (sign-in/page-response request ::confirm 409
                             (i18n/t "You are signed in as {email}." {:email (get-in request [:tover/identity :user :email] "")})
                             retained)

      (not (contains? confirmation-types type))
      (sign-in/page-response request ::confirm 400 (i18n/t "This link has expired or has already been used.") {})

      :else
      (let [verified (try {:tokens (provider/call! context ::provider/verify {:token-hash (or token-hash "") :type (keyword type)})}
                          (catch ExceptionInfo error {:failure (sign-in/failure error)}))]
        (if-let [tokens (:tokens verified)]
          ;; Confirmed with a usable sign-in for the same account: continue to profile setup.
          (try (sign-in/signed-in-response context (sign-in/establish! context tokens false) nil)
               (catch Exception _
                 ;; Confirmation stands even when the sign-in could not be established: sign in normally.
                 (sign-in/page-response request ::sign-in/login 503 (i18n/t "Your email is confirmed. Sign in to continue.") {})))
          (let [{:keys [outcome status message]} (:failure verified)]
            (if (= :link-invalid outcome)
              ;; Expired, invalid or used proves nothing about the account: offer the signup recovery choices.
              (sign-in/page-response request :tover.identity.resend.web/recover-signup status message {})
              ;; Unknown or unavailable: never claim confirmation or failure; the person may retry or sign in.
              (sign-in/page-response request ::confirm status
                                     (str message " " (i18n/t "If your email is already confirmed, sign in."))
                                     retained))))))))

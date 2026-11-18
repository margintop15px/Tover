(ns tover.identity.signup.web
  "01 · Create and confirm an account: Create Account and the neutral Check Your Email screen. Register
  Account is a provider effect; Account Registered is the provider's fact, so nothing is journaled here and
  no workspace or membership is created."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [clojure.string :as str]
    [tover.identity.provider :as provider]
    [tover.identity.shell :as shell]
    [tover.identity.sign-in.web :as sign-in]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [field form]]
    [tover.shared.web.core :as web])
  (:import
    [clojure.lang ExceptionInfo]))


(defn check-email
  "Neutral guidance after a signup or resend request; repeats only the address the visitor entered."
  {:malli/schema [:=> [:cat [:map [:request :map] [:email [:maybe :string]] [:message {:optional true} [:maybe :string]]]] schema/Hiccup]}
  [{:keys [request email message]}]
  [:div
   [:p (or message (i18n/t "If confirmation is needed and the message can be sent, follow the link in your email."))]
   (when-not (str/blank? email) [:p (i18n/t "Email entered: {email}" {:email email})])
   (form request (bel/action-url :tover.identity.resend.web/resend) (i18n/t "Resend confirmation email")
         [:input {:type "hidden" :name "email" :value (or email "")}])
   [:p [:a {:href "/auth/signup"} (i18n/t "Use another email")] " · "
    (i18n/t "Already have an account?") " " [:a {:href "/auth/login"} (i18n/t "Sign in")]]])


(defn check-email-response
  {:malli/schema [:=> [:cat :map [:maybe :string] [:maybe :string] :int] :map]}
  [request email message status]
  (assoc (shell/page request (i18n/t "Check your email") (check-email {:request request :email email :message message}))
         :status status))


(defpage signup
  {:route      "/auth/signup"
   :title      "Create an account · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :optional :middleware [sign-in/continue-signed-in]}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content
           request (i18n/t "Create an account")
           [:div
            (sign-in/invitation-hint request)
            (form request (bel/action-url ::register) (i18n/t "Create account")
                  (field request "email" (i18n/t "Email") "email")
                  (field request "password" (i18n/t "Password") "password"))
            [:p (i18n/t "Already have an account?") " " [:a {:href "/auth/login"} (i18n/t "Sign in")]]])})


(defaction register
  {:schema     [:map [:email {:optional true} :string] [:password {:optional true} :string]]
   :route-data {:tover/access :optional :middleware [sign-in/continue-signed-in]}}
  [{:keys [request origin] :as context} {:keys [email password]}]
  (let [email (str/trim (or email ""))]
    (try
      ;; The new account starts with the language chosen on this screen; an existing account keeps its own.
      (let [result (provider/call! context ::provider/sign-up {:email       email :password (or password "")
                                                               :locale      i18n/*locale*
                                                               :redirect-to (str origin "/auth/confirm")})]
        (if (:access-token result)
          ;; The provider confirmed immediately: confirmed account plus usable sign-in, continue to setup.
          (sign-in/signed-in-response context (sign-in/establish! context result false) nil)
          (check-email-response request email nil 200)))
      (catch ExceptionInfo error
        (let [{:keys [outcome status message]} (sign-in/failure error)]
          (case outcome
            :neutral (check-email-response request email nil 200)
            ;; A lost response is never repeated with the password: offer the recovery choices instead.
            :unknown (check-email-response request email message status)
            (sign-in/page-response request ::signup status message {"email" email})))))))

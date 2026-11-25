(ns tover.identity.signup.web
  "01 · Create and confirm an account: Create Account and the neutral Check Your Email screen. Register
  Account is a provider effect; Account Registered is the provider's fact, so nothing is journaled here and
  no workspace or membership is created."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [clojure.string :as str]
    [tover.identity.input :as input]
    [tover.identity.limits :as limits]
    [tover.identity.provider :as provider]
    [tover.identity.shell :as shell]
    [tover.identity.sign-in.web :as sign-in]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [field form password-field]]
    [tover.shared.web.core :as web]))


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


(defn request-confirmation!
  "Start the email's limit shared by signup and resend, ask the provider in the background and answer alike."
  {:malli/schema [:=> [:cat :map :string :qualified-keyword :map] :map]}
  [{:keys [request limits] :as context} email effect input]
  (if-let [seconds (limits/-start-email! limits :confirmation (limits/email-key email))]
    (check-email-response request email (sign-in/too-soon seconds) 429)
    (do (provider/call-later! context effect input)
        (check-email-response request email nil 200))))


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
                  (password-field {:label (i18n/t "Password") :autocomplete "new-password"}))
            [:p (i18n/t "Already have an account?") " " [:a {:href "/auth/login"} (i18n/t "Sign in")]]])})


(defaction register
  ;; The answer is given before the provider is asked, so it is the same for every email and outcome. A
  ;; password is never repeated automatically; after the limit the person may sign up again.
  {:schema     [:map [:email {:optional true} :string] [:password {:optional true} :string]]
   :route-data {:tover/access :optional :middleware [sign-in/continue-signed-in]}}
  [{:keys [request origin] :as context} {:keys [email password]}]
  (let [email    (str/trim (or email ""))
        password (or password "")]
    (if-let [error (or (input/email-error email) (input/new-password-error password))]
      (sign-in/page-response request ::signup 422 error {"email" email})
      (request-confirmation! context email ::provider/sign-up
                             ;; The new account starts with the language chosen on this screen.
                             {:email       email :password password
                              :locale      i18n/*locale*
                              :redirect-to (str origin "/auth/confirm")}))))

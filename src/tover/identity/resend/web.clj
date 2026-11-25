(ns tover.identity.resend.web
  "01a · Recover signup and resend confirmation. Resend Confirmation is a provider effect under
  Tover's email limit, requested after the answer; Confirmation Email Requested is the provider's fact."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [clojure.string :as str]
    [tover.identity.input :as input]
    [tover.identity.provider :as provider]
    [tover.identity.shell :as shell]
    [tover.identity.sign-in.web :as sign-in]
    [tover.identity.signup.web :as signup]
    [tover.shared.i18n :as i18n]
    [tover.shared.ui.forms :refer [field form]]
    [tover.shared.web.core :as web]))


(defpage recover-signup
  {:route      "/auth/resend"
   :title      "Recover signup · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content
           request (i18n/t "Recover signup")
           [:div
            (when (:tover/identity request)
              (sign-in/signed-in-choice {:request request :return-to "/auth/resend"}))
            (form request (bel/action-url ::resend) (i18n/t "Resend confirmation email")
                  (field request "email" (i18n/t "Email") "email"))
            [:p [:a {:href "/auth/login"} (i18n/t "Sign in")] " · "
             [:a {:href "/auth/signup"} (i18n/t "Use another email")] " · "
             [:a {:href "/auth/recover"} (i18n/t "Forgot password?")]]])})


(defaction resend
  {:schema     [:map [:email {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [request origin] :as context} {:keys [email]}]
  (let [email (str/trim (or email ""))]
    (if-let [error (input/email-error email)]
      (sign-in/page-response request ::recover-signup 422 error {"email" email})
      (signup/request-confirmation! context email ::provider/resend
                                    {:email email :redirect-to (str origin "/auth/confirm")}))))

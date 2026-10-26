(ns tover.auth.web
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.handler :as html]
    [bel.realtime :as realtime]
    [ring.middleware.session.store :as ring-store]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui]
    [tover.shared.ui.forms :refer [field form]]
    [tover.shared.web.core :as web]
    [tover.workspaces.core :as workspaces])
  (:import
    [clojure.lang ExceptionInfo]))


(defn document
  {:malli/schema [:=> [:cat :map [:* :any]] schema/Hiccup]}
  [{:keys [request title]} & children]
  [:html {:lang i18n/*locale*}
   [:head [:meta {:charset "utf-8"}]
    (i18n/catalog-meta)
    [:script {:src "/language.js"}]
    [:script {:type "module" :src "/i18n.js"}]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
    [:meta {:name "tover-context" :content (str (or (get-in request [:tover/identity :auth-version]) "anonymous"))}]
    [:title title]
    [:link {:rel "stylesheet" :href "/app.css"}]
    [:script {:src "/theme.js" :defer true}]
    [:script {:src "/identity.js" :defer true}]]
   [:body.identity-document
    (into [:main.identity [:div.identity-theme (ui/language-switch request) (ui/theme-toggle)]] children)]])


(defn- page-content
  {:malli/schema [:=> [:cat :map :string :any] schema/Hiccup]}
  [request title content]
  [:section
   [:h1 title]
   (when-let [error (:tover/error request)] [:p {:role "alert"} error])
   content])


(defn page
  {:malli/schema [:=> [:cat :map :string :any] :map]}
  [request title content]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    (html/render-html
              (document {:request request :title (str title " · Tover")}
                        (page-content request title content)))})


(defpage auth-shell
  {:document document}
  [_request & children]
  {:type :elements :html (into [:div.auth-content] children)})


(defpage login
  {:route      "/auth/login"
   :title      "Sign in · Tover"
   :parent     ::auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (page-content request (i18n/t "Sign in")
                       [:div
                        (form request (bel/action-url ::sign-in) (i18n/t "Sign in")
                              (field request "email" (i18n/t "Email") "email") (field request "password" (i18n/t "Password") "password"))
                        [:p [:a {:href "/auth/signup"} (i18n/t "Create an account")] " · " [:a {:href "/auth/recover"} (i18n/t "Forgot password?")]]])})


(defpage signup
  {:route      "/auth/signup"
   :title      "Create an account · Tover"
   :parent     ::auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (page-content request (i18n/t "Create an account")
                       (form request (bel/action-url ::sign-up) (i18n/t "Create account")
                             (field request "email" (i18n/t "Email") "email") (field request "password" (i18n/t "Password") "password")
                             (field request "workspace-name" (i18n/t "Workspace name") "text")))})


(defpage recover
  {:route      "/auth/recover"
   :title      "Recover your account · Tover"
   :parent     ::auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (page-content request (i18n/t "Recover your account")
                       (form request (bel/action-url ::send-recovery) (i18n/t "Send recovery email") (field request "email" (i18n/t "Email") "email")))})


(defpage verify
  {:route      "/auth/verify"
   :title      "Confirm email link · Tover"
   :parent     ::auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (page-content request (i18n/t "Confirm email link")
                       (form request (bel/action-url ::verify-email) (i18n/t "Continue")
                             [:input {:type "hidden" :name "token-hash" :value (or (get-in request [:params :token_hash])
                                                                                   (get-in request [:params :token-hash]))}]
                             [:input {:type "hidden" :name "type" :value (get-in request [:params :type])}]
                             [:p (i18n/t "Continue to verify this link and sign in.")]))})


(defpage confirm
  {:route      "/auth/confirm"
   :title      "Confirm email link · Tover"
   :parent     ::auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  (verify request))


(defpage password
  {:route      "/auth/password"
   :title      "Set your password · Tover"
   :parent     ::auth-shell
   :route-data {:tover/access :password}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (page-content request (i18n/t "Set your password")
                       (form request (bel/action-url ::set-password) (i18n/t "Save password") (field request "password" (i18n/t "New password") "password")))})


(defn- login-response
  {:malli/schema [:=> [:cat :map :qualified-keyword :boolean] :map]} [tokens page-id password-required?]
  {:type    :page
   :id      page-id
   :reload? true
   :session (with-meta {:identity/tokens tokens        :identity/password-required? password-required?
                        :bel/session-id  (random-uuid)} {:recreate              true})})


(defn- complete-sign-in
  {:malli/schema [:=> [:cat :map :map :map :boolean] :map]}
  [{:keys [workspaces]} request tokens password-required?]
  (let [response (login-response tokens (if password-required? ::password :tover.workspaces.web/page) password-required?)]
    (try
      (workspaces/accept-invites! workspaces (:access-token tokens))
      response
      (catch ExceptionInfo error
        (if-let [status (:status (ex-data error))]
          (assoc (page request (i18n/t "Workspace access unavailable")
                       [:div [:p (i18n/t "You are signed in. Retry to finish accepting your invitations.")]
                        (form request (bel/action-url ::finish-sign-in) (i18n/t "Retry workspace access"))])
                 :status status :session (:session response))
          (throw error))))))


(defaction sign-in
  {:route-data {:tover/access :optional :bel/error-page ::login :bel/retain ["email"]}}
  [{:keys [auth request] :as services} {:keys [email password]}]
  (let [tokens (auth/sign-in! auth {:email email :password password})]
    (complete-sign-in services request tokens false)))


(defaction sign-up
  {:route-data {:tover/access :optional :bel/error-page ::signup :bel/retain ["email" "workspace-name"]}}
  [{:keys [auth origin request]} {:keys [email password workspace-name]}]
  (let [result (auth/sign-up! auth {:email          email
                                    :password       password
                                    :workspace-name workspace-name
                                    :locale         i18n/*locale*
                                    :redirect-to    (str origin "/auth/verify")})]
    (if (:access-token result)
      (login-response result :tover.workspaces.web/page false)
      (page request (i18n/t "Check your email") [:p (i18n/t "Open the email link to confirm your account.")]))))


(defaction send-recovery
  {:route-data {:tover/access :optional :bel/error-page ::recover :bel/retain ["email"]}}
  [{:keys [auth origin request]} {:keys [email]}]
  (auth/recover! auth {:email email :redirect-to (str origin "/auth/verify")})
  (page request (i18n/t "Check your email") [:p (i18n/t "If this account exists, a recovery link will arrive.")]))


(defaction verify-email
  {:route-data {:tover/access :optional :bel/error-page ::verify :bel/retain ["type"]}}
  [{:keys [auth request] :as services} {:keys [token-hash type]}]
  (let [type      (keyword type)
        tokens    (auth/verify-email! auth {:token-hash token-hash :type type})
        password? (contains? #{:invite :recovery} type)]
    (complete-sign-in services request tokens password?)))


(defaction finish-sign-in
  {:route-data {:tover/access :finish}}
  [{:keys [workspaces request] :as services} _input]
  (workspaces/accept-invites! workspaces (security/access-token services request))
  {:type    :page
   :id      (if (get-in request [:tover/identity :password-required?])
              ::password :tover.workspaces.web/page)
   :reload? true})


(defaction set-password
  {:route-data {:tover/access :password :bel/error-page ::password}}
  [{:keys [auth session-store request] :as services} {:keys [password]}]
  (auth/set-password! auth (security/access-token services request) password)
  (sessions/update-session! session-store (:session/key request)
                            #(vector (-> % (assoc-in [:data :identity/password-required?] false)
                                         (assoc :version (random-uuid))) nil))
  {:type :page :id :tover.workspaces.web/page :reload? true :session {}})


(defaction sign-out
  {:route-data {:tover/access :logout}}
  [{:keys [auth session-store realtime request]} _input]
  (let [key   (:session/key request)
        token (get-in (sessions/session session-store key) [:tokens :access-token])]
    (ring-store/delete-session session-store key)
    (realtime/close-session! realtime key)
    (when token (try (auth/sign-out! auth token) (catch Exception _)))
    {:type :page :id ::login :reload? true :session nil}))


(defaction set-language
  {:schema     [:map [:locale [:enum "en" "ru"]] [:return-to {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [auth request] :as services} {:keys [locale return-to]}]
  (try
    (when (:tover/identity request)
      (auth/set-locale! auth (security/access-token services request) locale))
    {:status  303
     :headers {"location" (if (and (string? return-to) (re-matches #"/(?!/)[^\r\n\\]*" return-to)) return-to "/")}
     :cookies {"tover-language" {:value  locale :path "/" :max-age 31536000 :http-only true :same-site :lax
                                 :secure (= :https (:scheme request))}}
     :body    ""}
    (catch Exception error
      (binding [*out* *err*]
        (println "Language preference save failed:" (select-keys (ex-data error) [:type :code :status])))
      (assoc (page request (i18n/t "Language")
                   [:p {:role "alert"} (i18n/t "Could not save language. Please try again.")]) :status 503))))

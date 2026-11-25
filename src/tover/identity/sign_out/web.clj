(ns tover.identity.sign-out.web
  "02b · Sign out: the Sign Out form and action. Ending the Tover sign-in never waits for the provider."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.realtime :as realtime]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.identity.access :as access]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.identity.shell :as shell]
    [tover.identity.sign-out.model :as model]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [csrf-field]]
    [tover.shared.web.core :as web])
  (:import
    [clojure.lang ExceptionInfo]))


(def Props
  [:map [:request :map] [:return-to {:optional true} [:maybe :string]]])


(defn sign-out-form
  "Sign Out of the current sign-in. `:return-to` is the public screen to continue on (default Sign In)."
  {:malli/schema [:=> [:cat Props] schema/Hiccup]}
  [{:keys [request return-to]}]
  (let [identity (:tover/identity request)]
    [:form.sign-out {:method "post" :action (bel/action-url ::sign-out)}
     (csrf-field request)
     [:input {:type "hidden" :name "_tover-context" :value (str (:auth-version identity))}]
     [:input {:type "hidden" :name "session-id" :value (str (:session-id identity))}]
     (when return-to [:input {:type "hidden" :name "return-to" :value return-to}])
     [:button {:type "submit"} (i18n/t "Sign out")]]))


(defpage confirm-sign-out
  ;; GET target for links such as an invitation's wrong-account message; only the POST below ends anything.
  {:route      "/auth/sign-out"
   :title      "Sign out · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content
           request (i18n/t "Sign out")
           (if-let [email (get-in request [:tover/identity :user :email])]
             [:div.identity-signed-in
              [:p (i18n/t "You are signed in as {email}." {:email email})]
              (sign-out-form {:request request :return-to (get-in request [:params :return-to])})]
             [:p (i18n/t "You are signed out.") " " [:a {:href "/auth/login"} (i18n/t "Sign in")]]))})


(defn public-destination
  "Only a public identity screen (such as an unconsumed confirmation link) or an invitation survives sign-out;
  else Sign In. The access gate keeps the invitation and shows Sign In without its content."
  {:malli/schema [:=> [:cat :any] :string]}
  [return-to]
  (let [path (access/local-path return-to)]
    (if (or (and (str/starts-with? path "/auth/") (not (str/starts-with? path "/auth/password")))
            (re-matches #"/invitations/\p{XDigit}{8}(?:-\p{XDigit}{4}){3}-\p{XDigit}{12}" path))
      path
      "/auth/login")))


(defn- signed-out
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [origin]} location]
  {:status  303 :headers {"location" location} :body ""
   :cookies {"tover-session" {:value  "" :max-age 0 :path "/" :http-only true :same-site :lax
                              :secure (str/starts-with? (str origin) "https:")}}})


(defn- provider-cleanup!
  "End the provider's refresh token in the background; its outcome never affects the Tover sign-out."
  {:malli/schema [:=> [:cat :any [:maybe :map]] :nil]}
  [provider tokens]
  (when (and provider (:access-token tokens))
    (Thread/startVirtualThread
      #(try (provider/sign-out! provider (:access-token tokens) "local")
            (catch Exception error (mu/log ::provider-cleanup-failed :code (:code (ex-data error)))))))
  nil)


(defn- retry-response
  "The sign-out could not be confirmed: say so and keep the sign-in, without claiming success."
  {:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  [request return-to]
  (assoc (shell/page request (i18n/t "Sign-out not confirmed")
                     [:div [:p (i18n/t "We could not confirm that you signed out. Try again.")]
                      (sign-out-form {:request request :return-to return-to})])
         :status 503))


(defn- end!
  "End this sign-in: a workflow, so it calls `runtime/execute!` itself. The stored tokens are read before the
  end (an ended row has none), an already ended sign-in counts as signed out, and streams and the provider
  session close only after the end is accepted."
  {:malli/schema [:=> [:cat :map :string [:maybe :string]] :map]}
  [{:keys [request realtime session-store provider] :as context} location return-to]
  (let [key        (:session/key request)
        session-id (get-in request [:tover/identity :session-id])
        tokens     (:tokens (session/session session-store key))
        ended?     (try
                     (runtime/execute! context ::model/sign-out {:command-id (model/command-id session-id)
                                                                 :input      {:session-id session-id}})
                     true
                     (catch ExceptionInfo error
                       ;; Forbidden: this sign-in already ended or is no longer valid, a safe no-op.
                       (= :forbidden (:type (ex-data error))))
                     (catch Exception _ false))]
    (if ended?
      (do (when realtime (realtime/close-session! realtime key))
          (provider-cleanup! provider tokens)
          (signed-out context location))
      (retry-response request return-to))))


(defaction sign-out
  {:schema     [:map [:session-id {:optional true} :uuid] [:return-to {:optional true} :string]]
   :route-data {:tover/access :logout}}
  [{:keys [request] :as context} {:keys [session-id return-to]}]
  (let [identity (:tover/identity request)
        location (public-destination return-to)]
    (cond
      ;; Already signed out: nothing to end and no credentials needed.
      (nil? identity)
      (signed-out context location)

      ;; A page of another sign-in never ends this one (its context token is already checked by access).
      (and session-id (not= session-id (:session-id identity)))
      {:status 303 :headers {"location" "/auth/login"} :body ""}

      :else
      (end! context location return-to))))

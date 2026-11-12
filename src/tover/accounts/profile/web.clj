(ns tover.accounts.profile.web
  "Profile setup: the display name and account language, saved through the profile commands."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.handler :as html]
    [bel.runtime :as runtime]
    [com.brunobonacci.mulog :as mu]
    [tover.accounts.profile.model :as model]
    [tover.auth.security :as security]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [csrf-field]])
  (:import
    [clojure.lang ExceptionInfo]))


(def FormProps
  [:map
   [:request :map]
   [:profile model/Profile]
   [:email {:optional true} [:maybe :string]]
   [:draft {:optional true} [:maybe :string]]
   [:return-to :string]
   [:latest {:optional true} [:maybe :map]]])


(defn- hidden
  {:malli/schema [:=> [:cat :string :any] schema/Hiccup]}
  [name value]
  [:input {:type "hidden" :name name :value (str value)}])


(defn profile-form
  "One form: Continue saves the name; the language buttons post the same fields to set-language, so the draft survives."
  {:malli/schema [:=> [:cat FormProps] schema/Hiccup]}
  [{:keys [request profile email draft return-to latest]}]
  [:div.management-form
   [:form.profile-form {:method "post" :action (bel/action-url ::save-name)}
    (csrf-field request)
    (hidden "_tover-context" (get-in request [:tover/identity :auth-version]))
    (hidden "account" (:user-id profile))
    (hidden "name-revision" (:name-revision profile))
    (hidden "language-revision" (:language-revision profile))
    (hidden "return-to" return-to)
    [:label (i18n/t "Email") [:input {:type "email" :value (or email "") :readonly true}]]
    [:label (i18n/t "Display name")
     [:input {:name  "display-name" :type "text" :required true :maxlength 100 :autocomplete "name"
              :value (or draft (:display-name profile) "")}]]
    (when-let [saved (:display-name latest)]
      [:p.profile-latest {:role "status"} (i18n/t "Saved name: {name}" {:name saved})])
    ;; First in tree order, so Enter in the name field saves instead of switching language.
    [:button {:type "submit"} (i18n/t "Continue")]
    [:div.profile-language {:role "group" :aria-label (i18n/t "Language")}
     (for [[locale text label] [["en" "EN" (i18n/t "English")] ["ru" "RU" (i18n/t "Russian")]]]
       [:button {:type         "submit" :formaction (bel/action-url :tover.auth.web/set-language) :formnovalidate true
                 :name         "locale" :value      locale
                 :aria-label   label :title         label
                 :aria-pressed (str (= locale i18n/*locale*))}
        text])]]])


(defn query-profile
  {:malli/schema [:=> [:cat :map :map] FormProps]}
  [{:keys [request] :as context} _input]
  {:request   request
   :profile   (runtime/read-model context ::model/my-profile {})
   :email     (get-in request [:tover/identity :user :email])
   :draft     (get-in request [:params :display-name])
   :return-to (security/local-path (get-in request [:params :return-to]))
   :latest    (:tover/latest-profile request)})


(defpage profile
  {:route      "/account/profile"
   :title      "Profile · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :profile}
   :query      query-profile}
  [props]
  {:type :elements
   :html [:section.management-page {:aria-labelledby "profile-title"}
          [:header.management-header [:h1#profile-title {:tabindex -1} (i18n/t "Profile")]]
          (profile-form props)]})


(defn error-status
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)]] :int]}
  [error]
  (case (:type (ex-data error))
    :stale-profile 409
    :validation 422
    :forbidden 403
    (or (:status (ex-data error)) 500)))


(defn error-message
  "The message for a failed profile save; `invalid` describes a rejected value."
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)] :string] :string]}
  [error invalid]
  (case (:type (ex-data error))
    :stale-profile (i18n/t "This profile changed. Review the latest value.")
    :validation invalid
    :forbidden (i18n/t "You do not have permission to do this.")
    (i18n/error-message error)))


(defn page-response
  "Render the profile page in answer to a form post, keeping the submitted name."
  {:malli/schema [:=> [:cat :map :int [:map [:draft {:optional true} [:maybe :string]] [:return-to :string]
                                       [:error {:optional true} :string] [:latest {:optional true} [:maybe :map]]]] :map]}
  [request status {:keys [draft return-to error latest]}]
  (let [response (html/error-page-response (cond-> request
                                             error (assoc :tover/error error)
                                             latest (assoc :tover/latest-profile latest))
                                           ::profile (cond-> {"return-to" return-to} draft (assoc "display-name" draft)))]
    (cond-> response (= 200 (:status response)) (assoc :status status))))


(defn refresh-session!
  "Apply an accepted save to this session's claims before answering; other sessions follow through the effect."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [auth-session-id] :as context}]
  (try
    (security/refresh-claims! context auth-session-id)
    (catch Exception error
      (mu/log ::refresh-claims-failed :error-type (.getName (class error)))))
  nil)


(defaction save-name
  {:schema     [:map [:display-name {:optional true} :string] [:return-to {:optional true} :string]]
   :route-data {:tover/access :profile}}
  [{:keys [request] :as context} {:keys [display-name return-to] :as input}]
  (let [return-to (security/local-path return-to)]
    (try
      (runtime/execute! context ::model/set-display-name
                        {:command-id (str (random-uuid)) :input (select-keys input [:account :display-name :name-revision])})
      (refresh-session! context)
      {:status 303 :headers {"location" return-to} :body ""}
      (catch ExceptionInfo error
        (page-response request (error-status error)
                       {:draft  display-name :return-to return-to
                        :error  (error-message error (i18n/t "Enter a name of 1 to 100 characters."))
                        :latest (:latest (ex-data error))})))))

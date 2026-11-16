(ns tover.accounts.profile.web
  "Profile setup: the display name and account language, saved through the profile commands."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.handler :as html]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.accounts.profile.model :as model]
    [tover.identity.access :as access]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [csrf-field]])
  (:import
    [clojure.lang ExceptionInfo]))


(def FormProps
  [:map
   [:request :map]
   [:profile {:optional true} [:maybe model/Profile]]
   [:email {:optional true} [:maybe :string]]
   [:draft {:optional true} [:maybe :string]]
   [:command-id :string]
   [:return-to :string]
   [:latest {:optional true} [:maybe :map]]])


(defn- hidden
  {:malli/schema [:=> [:cat :string :any] schema/Hiccup]}
  [name value]
  [:input {:type "hidden" :name name :value (str value)}])


(defn destination
  "The requested destination; without one, setup continues to the workspace chooser."
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (if (str/blank? value) "/workspace" (access/local-path value)))


(defn profile-form
  "One form: Continue (Save once a name is saved) saves the name; the language buttons post the same fields to
  set-language, so the draft survives. The attempt id lets a repeated submission recover its first outcome."
  {:malli/schema [:=> [:cat FormProps] schema/Hiccup]}
  [{:keys [request profile email draft command-id return-to latest]}]
  [:div.management-form
   [:form.profile-form {:method "post" :action (bel/action-url ::save-name)}
    (csrf-field request)
    (hidden "_tover-context" (get-in request [:tover/identity :auth-version]))
    (hidden "command-id" command-id)
    (hidden "account" (:user-id profile))
    (hidden "name-revision" (:name-revision profile))
    (hidden "language-revision" (:language-revision profile))
    (hidden "return-to" return-to)
    [:label (i18n/t "Email") [:input {:type "email" :value (or email "") :readonly true}]]
    [:label (i18n/t "Display name")
     ;; No maxlength: an overlong name is rejected with the full attempt kept, never truncated.
     [:input {:name  "display-name" :type "text" :required true :autocomplete "name"
              :value (or draft (:display-name profile) "")}]
     [:span.management-secondary (i18n/t "1 to 100 characters.")]]
    (when-let [saved (:display-name latest)]
      [:p.profile-latest {:role "status"} (i18n/t "Saved name: {name}" {:name saved})])
    ;; First in tree order, so Enter in the name field saves instead of switching language.
    [:button {:type "submit"} (if (model/valid-name? (:display-name profile)) (i18n/t "Save") (i18n/t "Continue"))]
    [:div.profile-language {:role "group" :aria-label (i18n/t "Language")}
     (for [[locale text label] [["en" "EN" (i18n/t "English")] ["ru" "RU" (i18n/t "Russian")]]]
       [:button {:type         "submit" :formaction (bel/action-url ::set-language) :formnovalidate true
                 :name         "locale" :value      locale
                 :aria-label   label :title         label
                 :aria-pressed (str (= locale i18n/*locale*))}
        text])]]])


(defn query-profile
  "A failed read is not an empty profile: the page offers Retry instead of a blank form."
  {:malli/schema [:=> [:cat :map :map] FormProps]}
  [{:keys [request] :as context} _input]
  (let [params (:params request)]
    {:request    request
     :profile    (try (runtime/read-model context ::model/my-profile {})
                      (catch ExceptionInfo error
                        (when (= :forbidden (:type (ex-data error))) (throw error))
                        (mu/log ::profile-unavailable :error-type (:type (ex-data error)))
                        nil))
     :email      (get-in request [:tover/identity :user :email])
     :draft      (:display-name params)
     :command-id (or (not-empty (:command-id params)) (str (random-uuid)))
     :return-to  (destination (:return-to params))
     :latest     (:tover/latest-profile request)}))


(defpage profile
  {:route      "/account/profile"
   :title      "Profile · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :profile}
   :query      query-profile}
  [{:keys [request profile] :as props}]
  {:type :elements
   :html [:section.management-page {:aria-labelledby "profile-title"}
          [:header.management-header [:h1#profile-title {:tabindex -1} (i18n/t "Profile")]]
          (if profile
            (profile-form props)
            [:div.management-empty {:role "alert"}
             [:p (i18n/t "Could not load your profile.")]
             [:p [:a {:href (str (:uri request) (some->> (:query-string request) (str "?")))} (i18n/t "Retry")]]])]})


(defn error-status
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)]] :int]}
  [error]
  (case (:type (ex-data error))
    (:stale-profile :idempotency-conflict) 409
    :validation 422
    :forbidden 403
    (or (:status (ex-data error)) 500)))


(defn error-message
  "The message for a failed profile save; `invalid` describes a rejected value."
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)] :string] :string]}
  [error invalid]
  (case (:type (ex-data error))
    (:stale-profile :idempotency-conflict) (i18n/t "This profile changed. Review the latest value.")
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


(defn- latest
  "The saved name after a conflict: from the rejection, or read again when another request used the attempt id."
  {:malli/schema [:=> [:cat :map [:fn #(instance? Throwable %)]] [:maybe :map]]}
  [context error]
  (or (:latest (ex-data error))
      (when (= :idempotency-conflict (:type (ex-data error)))
        (select-keys (runtime/read-model context ::model/my-profile {}) [:display-name :name-revision]))))


(defaction save-name
  {:schema     [:map [:display-name {:optional true} :string] [:return-to {:optional true} :string]
                [:command-id {:optional true} [:string {:min 1 :max 100}]]]
   :route-data {:tover/access :profile}}
  [{:keys [request] :as context} {:keys [display-name command-id] :as input}]
  (let [return-to (destination (:return-to input))]
    (try
      (runtime/execute! context ::model/set-display-name
                        {:command-id (or command-id (str (random-uuid)))
                         :input      (select-keys input [:account :display-name :name-revision])})
      {:status 303 :headers {"location" return-to} :body ""}
      (catch ExceptionInfo error
        (page-response request (error-status error)
                       {:draft  display-name :return-to return-to
                        :error  (error-message error (i18n/t "Enter a name of 1 to 100 characters."))
                        :latest (latest context error)})))))


(defn- language-cookie
  {:malli/schema [:=> [:cat :map :string] :map]}
  [request locale]
  {"tover-language" {:value  locale :path "/" :max-age 31536000 :http-only true :same-site :lax
                     :secure (= :https (:scheme request))}})


(defn- language-not-saved
  "The account language stays as saved; a stale switch names the language saved elsewhere."
  {:malli/schema [:=> [:cat :map :string [:fn #(instance? ExceptionInfo %)]] :map]}
  [request return-to error]
  (let [latest (get-in (ex-data error) [:latest :language])]
    (page-response request (error-status error)
                   {:return-to return-to
                    :error     (str (error-message error (i18n/t "Could not save language. Please try again."))
                                    (when latest
                                      (str " " (i18n/t "Your account language is {language}."
                                                       {:language (i18n/t (if (= "ru" latest) "Russian" "English"))}))))})))


(defaction set-language
  "The signed-in account language (03a). The profile form posts its account and revisions, so a stale or
  foreign save is caught; the header switch posts only the language and saves at the revision read now."
  {:schema     [:map [:locale [:enum "en" "ru"]] [:return-to {:optional true} :string] [:display-name {:optional true} :string]]
   :route-data {:tover/access :profile}}
  [{:keys [request] :as context} {:keys [locale] :as input}]
  (let [back     (access/local-path (:return-to input))
        identity (:tover/identity request)
        draft?   (contains? input :display-name)
        cookies  (language-cookie request locale)]
    (try
      (runtime/execute! context ::model/change-account-language
                        {:command-id (str (random-uuid))
                         :input      {:account           (or (:account input) (:user-id identity))
                                      :language          locale
                                      :language-revision (or (:language-revision input)
                                                             (:language-revision (runtime/read-model context ::model/my-profile {})))}})
      (if draft?
        (assoc (page-response request 200 {:draft (:display-name input) :return-to (destination (:return-to input))}) :cookies cookies)
        {:status 303 :headers {"location" back} :cookies cookies :body ""})
      (catch ExceptionInfo error
        (if draft?
          (page-response request (error-status error)
                         {:draft (:display-name input) :return-to (destination (:return-to input))
                          :error (error-message error (i18n/t "Could not save language. Please try again."))})
          (language-not-saved request back error))))))

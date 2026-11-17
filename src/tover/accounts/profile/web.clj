(ns tover.accounts.profile.web
  "Profile setup: the display name and account language, saved through the profile commands."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
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
    (when-let [language (:language latest)]
      [:p.profile-latest {:role "status"}
       (i18n/t "Your account language is {language}." {:language (i18n/t (if (= "ru" language) "Russian" "English"))})])
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
  (let [params    (:params request)
        profile   (try (runtime/read-model context ::model/my-profile {})
                       (catch ExceptionInfo error
                         (when (= :forbidden (:type (ex-data error))) (throw error))
                         (mu/log ::profile-unavailable :error-type (:type (ex-data error)))
                         nil))
        rejection (:tover/rejection request)]
    {:request    request
     :profile    profile
     :email      (get-in request [:tover/identity :user :email])
     :draft      (:display-name params)
     :command-id (or (not-empty (:command-id params)) (str (random-uuid)))
     :return-to  (destination (:return-to params))
     ;; After a conflict: the saved value from the rejection, or the one read now when another request used
     ;; the attempt id.
     :latest     (when (contains? #{:stale-profile :idempotency-conflict} (:type rejection))
                   (or (:latest rejection) (select-keys profile [:display-name :name-revision])))}))


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


(defn- problems
  "Profile rejections shown on the profile form; `invalid` describes a rejected value."
  {:malli/schema [:=> [:cat :string] :map]}
  [invalid]
  (let [changed [409 "This profile changed. Review the latest value."]]
    {:stale-profile changed :idempotency-conflict changed :validation [422 invalid]
     :forbidden     [403 "You do not have permission to do this."]}))


(defaction save-name
  {:schema     [:map [:display-name {:optional true} :string] [:return-to {:optional true} :string]
                [:command-id {:optional true} [:string {:min 1 :max 100}]]]
   :route-data {:tover/access   :profile :bel/error-page ::profile :bel/retain ["display-name" "return-to"]
                :tover/problems (problems "Enter a name of 1 to 100 characters.")}}
  [_ {:keys [command-id] :as input}]
  {:type  :command :id ::model/set-display-name :command-id (or command-id (str (random-uuid)))
   :input (select-keys input [:account :display-name :name-revision])
   :then  {:status 303 :headers {"location" (destination (:return-to input))} :body ""}})


(defn- language-cookie
  {:malli/schema [:=> [:cat :map :string] :map]}
  [request locale]
  {"tover-language" {:value  locale :path "/" :max-age 31536000 :http-only true :same-site :lax
                     :secure (= :https (:scheme request))}})


(defaction set-language
  "The signed-in account language (03a). The profile form posts its account and revisions, so a stale or
  foreign save is caught; the header switch posts only the language and saves at the revision read now.
  From the profile form the page renders again in place, so the draft name survives without entering the URL."
  {:schema     [:map [:locale [:enum "en" "ru"]] [:return-to {:optional true} :string] [:display-name {:optional true} :string]]
   :route-data {:tover/access   :profile :bel/error-page ::profile :bel/retain ["display-name" "return-to"]
                :tover/problems (problems "Could not save language. Please try again.")}}
  [{:keys [request] :as context} {:keys [locale return-to display-name] :as input}]
  (let [cookies (language-cookie request locale)]
    {:type  :command :id ::model/change-account-language :command-id (str (random-uuid))
     :input {:account           (or (:account input) (get-in request [:tover/identity :user-id]))
             :language          locale
             :language-revision (or (:language-revision input)
                                    (:language-revision (runtime/read-model context ::model/my-profile {})))}
     :then  (if (contains? input :display-name)
              {:type :page :id ::profile :cookies cookies
               :form (cond-> {"display-name" display-name} return-to (assoc "return-to" return-to))}
              {:status 303 :headers {"location" (access/local-path return-to)} :cookies cookies :body ""})}))

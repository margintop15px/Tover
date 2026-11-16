(ns tover.identity.shell
  "The document for signed-out and account-setup pages (`body.identity-document > main.identity`) and the
  header language switch. Pages declare `:parent ::auth-shell` and render `page-content`."
  (:require
    [bel.core :refer [defaction defpage]]
    [bel.handler :as html]
    [bel.runtime :as runtime]
    [tover.accounts.profile.model :as profile]
    [tover.accounts.profile.web :as profile-web]
    [tover.identity.access :as access]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui])
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
    (into [:main.identity [:div.identity-theme (ui/language-switch {:request request}) (ui/theme-toggle)]] children)]])


(defn page-content
  "A titled section; shows `:tover/error` from the request as an alert."
  {:malli/schema [:=> [:cat :map :string :any] schema/Hiccup]}
  [request title content]
  [:section
   [:h1 title]
   (when-let [error (:tover/error request)] [:p {:role "alert"} error])
   content])


(defn page
  "A complete identity page as a Ring response, for actions that answer with a page directly."
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


(defn- language-not-saved
  "The account language stays as saved; a stale switch names the language saved elsewhere."
  {:malli/schema [:=> [:cat :map :string [:fn #(instance? ExceptionInfo %)]] :map]}
  [request return-to error]
  (let [latest (get-in (ex-data error) [:latest :language])]
    (assoc (page request (i18n/t "Language not changed")
                 [:div
                  [:p (profile-web/error-message error (i18n/t "Could not save language. Please try again."))]
                  (when latest
                    [:p (i18n/t "Your account language is {language}." {:language (i18n/t (if (= "ru" latest) "Russian" "English"))})])
                  [:p [:a {:href return-to} (i18n/t "Back")]]])
           :status (profile-web/error-status error))))


(defaction set-language
  ;; ponytail: the signed-in branch belongs to 03a (agent B) and may move into tover.accounts.profile.web.
  {:schema     [:map [:locale [:enum "en" "ru"]] [:return-to {:optional true} :string] [:display-name {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [request] :as context} {:keys [locale return-to] :as input}]
  (let [return-to (access/local-path return-to)
        signed-in (:tover/identity request)
        draft?    (contains? input :display-name)
        cookies   {"tover-language" {:value  locale :path "/" :max-age 31536000 :http-only true :same-site :lax
                                     :secure (= :https (:scheme request))}}
        redirect  {:status 303 :headers {"location" return-to} :cookies cookies :body ""}]
    ;; A guest, or a recovery session that may not edit the profile, keeps the choice in this browser only.
    (if (or (nil? signed-in) (:password-required? signed-in))
      redirect
      (try
        ;; The profile form posts its account and revision, so a stale save there is caught. The header switch
        ;; posts only the language: it saves for the signed-in account at the revision read now.
        (runtime/execute! context ::profile/change-account-language
                          {:command-id (str (random-uuid))
                           :input      {:account           (or (:account input) (:user-id signed-in))
                                        :language          locale
                                        :language-revision (or (:language-revision input)
                                                               (:language-revision (runtime/read-model context ::profile/my-profile {})))}})
        (if draft?
          (assoc (profile-web/page-response request 200 {:draft (:display-name input) :return-to return-to}) :cookies cookies)
          redirect)
        (catch ExceptionInfo error
          (if draft?
            (profile-web/page-response request (profile-web/error-status error)
                                       {:draft (:display-name input) :return-to return-to
                                        :error (profile-web/error-message error (i18n/t "Could not save language. Please try again."))})
            (language-not-saved request return-to error)))))))

(ns tover.identity.shell
  "The document for signed-out and account-setup pages (`body.identity-document > main.identity`) and the
  header language switch. Pages declare `:parent ::auth-shell` and render `page-content`."
  (:require
    [bel.core :refer [defaction defpage]]
    [bel.handler :as html]
    [tover.identity.access :as access]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui]))


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
    [:script {:src "/identity.js?v=3" :defer true}]]
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


(defaction set-language
  "A guest, or a recovery sign-in that may not edit the profile, keeps the language in this browser only.
  Ordinary sign-ins save the account language through `tover.accounts.profile.web/set-language` (03a)."
  {:schema     [:map [:locale [:enum "en" "ru"]] [:return-to {:optional true} :string]]
   :route-data {:tover/access :optional}}
  [{:keys [request]} {:keys [locale return-to]}]
  {:status  303 :headers {"location" (access/local-path return-to)} :body ""
   :cookies {"tover-language" {:value  locale :path "/" :max-age 31536000 :http-only true :same-site :lax
                               :secure (= :https (:scheme request))}}})

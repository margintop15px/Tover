(ns tover.shared.ui.core
  (:require
    [bel.nav :as nav]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]))


(defn theme-toggle
  {:malli/schema [:=> [:cat] schema/Hiccup]}
  []
  [:button#theme-toggle.theme-toggle {:type               "button"           :aria-label (i18n/t "Switch appearance") :title (i18n/t "Switch appearance")
                                      :data-preserve-attr "aria-label title"}
   [:svg.theme-icon-light {:viewBox "0 0 24 24" :aria-hidden true}
    [:circle {:cx 12 :cy 12 :r 4}]
    [:path {:d "M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"}]]
   [:svg.theme-icon-dark {:viewBox "0 0 24 24" :aria-hidden true}
    [:path {:d "M20.9 13A9 9 0 0 1 11 3.1 9 9 0 1 0 20.9 13Z"}]]])


(defn language-switch
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [request]
  (let [english? (= "en" i18n/*locale*)
        label    (i18n/t (if english? "Switch to Russian" "Switch to English"))]
    [:form.language-switch {:method "post" :action "/action/tover.auth.web/set-language"}
     [:input {:type "hidden" :name "__anti-forgery-token" :value (force (:anti-forgery-token request))}]
     [:input {:type "hidden" :name "return-to" :value (or (get-in request [:params :return-to])
                                                          (str (:uri request) (when-let [query (:query-string request)] (str "?" query))))}]
     [:button.language-toggle {:type       "submit" :name  "locale" :value (if english? "ru" "en")
                               :aria-label label    :title label}
      [:svg {:viewBox "0 0 24 24" :aria-hidden true}
       [:path {:d "M2 5h12M7 2v3m-2 3 6 6m-7 0 6-6m4 14 4-11 4 11m-6.5-4h5"}]]
      [:span {:aria-hidden true} (if english? "EN" "RU")]]]))


(defn app-shell
  {:malli/schema [:=> [:cat :map [:* :any]] schema/Hiccup]}
  [{:keys [identity path error request]} & children]
  [:div#tover-app
   [:nav.app-nav {:aria-label (i18n/t "Application")}
    [:div.app-nav-pages
     (when (:workspace-id identity)
       (for [[url label] [["/" (i18n/t "Stock")] ["/operations" (i18n/t "Operations")]]]
         [:a (nav/link url {:class        (when (= url path) "active")
                            :aria-current (when (= url path) "page")}) label]))]
    [:div.app-nav-tools
     [:a.nav-icon (nav/link "/workspace" {:aria-label   (i18n/t "Workspaces")               :title (i18n/t "Workspaces")
                                          :aria-current (when (= path "/workspace") "page")})
      [:svg {:viewBox "0 0 24 24" :aria-hidden true}
       [:rect {:x 3 :y 3 :width 18 :height 18 :rx 2}]
       [:path {:d "M3 9h18M9 9v12"}]]]
     (when (and (:workspace-id identity) (contains? #{:owner :admin} (:role identity)))
       [:a.nav-icon (nav/link "/team" {:aria-label   (i18n/t "Team")                :title (i18n/t "Team")
                                       :aria-current (when (= path "/team") "page")})
        [:svg {:viewBox "0 0 24 24" :aria-hidden true}
         [:circle {:cx 9 :cy 8 :r 3}]
         [:path {:d "M3 21v-2a6 6 0 0 1 12 0v2M16 5a3 3 0 0 1 0 6M21 21v-2a6 6 0 0 0-4-5.65"}]]])
     (language-switch request)
     (theme-toggle)]]
   (when error [:p.app-notice.error {:role "alert"} error])
   (into [:main#page-content {:tabindex -1}] children)])

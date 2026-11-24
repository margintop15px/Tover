(ns tover.shared.ui.core
  (:require
    [bel.nav :as nav]
    [clojure.string :as str]
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


(def LanguageSwitchProps
  [:map [:request :map]])


(defn language-switch
  "Posts only the language. An ordinary sign-in saves the account language (the save reads the current
  revision itself); a guest or a recovery sign-in keeps the choice in this browser."
  {:malli/schema [:=> [:cat LanguageSwitchProps] schema/Hiccup]}
  [{:keys [request]}]
  (let [identity (:tover/identity request)
        account? (and identity (not (:password-required? identity)))]
    [:form.language-switch {:method "post"  :action     (if account?
                                                         "/action/tover.accounts.profile.web/set-language"
                                                         "/action/tover.identity.shell/set-language")
                            :role   "group" :aria-label (i18n/t "Language")}
     [:input {:type "hidden" :name "__anti-forgery-token" :value (force (:anti-forgery-token request))}]
     (when identity
       [:input {:type "hidden" :name "_tover-context" :value (str (:auth-version identity))}])
     [:input {:type "hidden" :name "return-to" :value (or (get-in request [:params :return-to])
                                                          (str (:uri request) (when-let [query (:query-string request)] (str "?" query))))}]
     (for [[locale text label] [["en" "EN" (i18n/t "English")] ["ru" "RU" (i18n/t "Russian")]]]
       [:button.language-toggle {:type         "submit" :name "locale" :value locale
                                 :aria-label   label :title   label
                                 :aria-pressed (str (= locale i18n/*locale*))}
        text])]))


(defn app-shell
  {:malli/schema [:=> [:cat :map [:* :any]] schema/Hiccup]}
  [{:keys [identity path error request]} & children]
  [:div#tover-app
   [:nav.app-nav {:aria-label (i18n/t "Application")}
    [:div.app-nav-pages
     (when (:workspace-id identity)
       (for [[url label] [["/" (i18n/t "Stock")] ["/operations" (i18n/t "Operations")] ["/catalogues" (i18n/t "Catalogues")] ["/settings" (i18n/t "Settings")]]
             :let        [active? (if (= url "/catalogues") (str/starts-with? path "/catalogues") (= url path))]]
         [:a (nav/link url {:class        (when active? "active")
                            :aria-current (when active? "page")}) label]))]
    [:div.app-nav-tools
     ;; The selected workspace is named wherever its data is shown.
     (when-let [current (some #(when (= (:workspace-id identity) (:workspace-id %)) %) (:memberships identity))]
       [:span.management-secondary {:data-workspace-id (str (:workspace-id current))} (:name current)])
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
     [:a.nav-icon (nav/link "/account/profile" {:aria-label   (i18n/t "Profile") :title (i18n/t "Profile")
                                                :aria-current (when (= path "/account/profile") "page")})
      [:svg {:viewBox "0 0 24 24" :aria-hidden true}
       [:circle {:cx 12 :cy 8 :r 4}]
       [:path {:d "M4 21v-1a8 8 0 0 1 16 0v1"}]]]
     (language-switch {:request request})
     (theme-toggle)]]
   (when error [:p.app-notice.error {:role "alert"} error])
   (into [:main#page-content {:tabindex -1}] children)])


(defn sheet-icon
  {:malli/schema [:=> [:cat :keyword] :any]}
  [name]
  [:svg {:viewBox        "0 0 24 24" :fill            "none"  :stroke      "currentColor" :stroke-width 1.6
         :stroke-linecap "round"     :stroke-linejoin "round" :aria-hidden true}
   (case name
     :modified [:path {:d "m16 3 5 5-12 12-6 1 1-6L16 3Z m-2 2 5 5"}]
     :deleted [:path {:d "M5 12h14"}]
     :added [:path {:d "M5 12h14 M12 5v14"}]
     :revert [:path {:d "m9 4-5 5 5 5 M4 9h10a5.5 5.5 0 0 1 0 11h-3"}]
     :preview [:g [:path {:d "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"}]
               [:circle {:cx 12 :cy 12 :r 3}]]
     :submit [:path {:d "M12 21V3 m-7 7 7-7 7 7"}]
     :refresh [:path {:d "M20 10a8 8 0 1 0-2 8 M20 4v6h-6"}]
     :close [:path {:d "m6 6 12 12 M6 18 18 6"}]
     :active [:g [:rect {:x 3 :y 4 :width 18 :height 16 :rx 2}] [:path {:d "M3 10h18 M9 4v16"}]]
     :archived [:path {:d "M3 4h18v4H3z M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8 M10 12h4"}]
     :hide-empty [:path {:d "M3 3l18 18 M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4 M6.6 6.6A17 17 0 0 0 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6 M9.9 9.9a3 3 0 0 0 4.2 4.2"}]
     :columns [:path {:d "M3 4h18v16H3z M9 4v16 M15 4v16"}]
     :units [:path {:d "M9 4 7 20 M17 4l-2 16 M4 9h16 M3 15h16"}]
     :money [:g [:circle {:cx 12 :cy 12 :r 9}] [:path {:d "M15 9.5a3 2.5 0 0 0-3-1.5c-1.7 0-3 .9-3 2s1.3 1.7 3 2 3 .9 3 2-1.3 2-3 2a3 2.5 0 0 1-3-1.5 M12 6v12"}]])])

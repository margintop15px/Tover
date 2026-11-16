(ns tover.identity.placeholders
  "Landing pages for the access redirects until the onboarding slices replace them. Delete a page here in the
  same commit that adds its real route."
  (:require
    [bel.core :refer [defpage]]
    [tover.identity.shell :as shell]
    [tover.shared.i18n :as i18n]
    [tover.shared.web.core :as web]))


;; ponytail: placeholder until chunk A (02 · Return to Tover)
(defpage login
  {:route      "/auth/login"
   :title      "Sign in · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :optional}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content request (i18n/t "Sign in") [:p (i18n/t "The service is unavailable. Please try again.")])})


;; ponytail: placeholder until chunk A (02a · Recover account access)
(defpage password
  {:route      "/auth/password"
   :title      "Set your password · Tover"
   :parent     ::shell/auth-shell
   :route-data {:tover/access :password}
   :query      web/query-request}
  [request]
  {:type :elements
   :html (shell/page-content request (i18n/t "Set your password") [:p (i18n/t "The service is unavailable. Please try again.")])})


;; ponytail: placeholder until chunk B (07 · Switch workspace)
(defpage workspace
  {:route      "/workspace"
   :title      "Workspaces · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :authenticated}
   :query      web/query-request}
  [request]
  {:type :elements
   :html [:section.management-page
          [:header.management-header [:h1 (i18n/t "Workspaces")]]
          [:ul (for [{:keys [name role]} (get-in request [:tover/identity :memberships])]
                 [:li name " · " (clojure.core/name role)])]]})

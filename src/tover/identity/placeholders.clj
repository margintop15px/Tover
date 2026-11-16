(ns tover.identity.placeholders
  "Landing pages for the access redirects until the onboarding slices replace them. Delete a page here in the
  same commit that adds its real route."
  (:require
    [bel.core :refer [defpage]]
    [tover.shared.i18n :as i18n]
    [tover.shared.web.core :as web]))


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

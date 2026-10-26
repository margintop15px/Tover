(ns tover.workspaces.web
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.realtime :as realtime]
    [clojure.string :as str]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.shared.i18n :as i18n]
    [tover.shared.ui.forms :refer [field form]]
    [tover.shared.web.core :as web]
    [tover.workspaces.core :as workspaces]))


(defn workspace-content
  {:malli/schema [:=> [:cat :map] [:vector :any]]} [request]
  (let [{:keys [memberships workspace-id]} (:tover/identity request)]
    [:div.management-content
     [:details.management-disclosure {:open (or (empty? memberships) (contains? (:params request) :name))}
      [:summary (i18n/t "Create workspace")]
      [:div.management-form
       (form request ::create-workspace (i18n/t "Create workspace")
             (field request "name" (i18n/t "Workspace name") "text"))]]
     [:section.management-section {:aria-label (i18n/t "Workspaces")}
      (if (seq memberships)
        [:div.management-table-wrap
         [:table.management-table
          [:thead
           [:tr
            [:th {:scope "col"} (i18n/t "Workspace")]
            [:th.management-role {:scope "col"} (i18n/t "Role")]
            [:th {:scope "col"} (i18n/t "Actions")]]]
          (into [:tbody]
                (for [membership memberships
                      :let       [role (i18n/t (str/capitalize (name (:role membership))))]]
                  [:tr
                   [:td.management-primary
                    [:span.management-name (:name membership)]
                    (when (= workspace-id (:workspace-id membership))
                      [:span.management-current (i18n/t "Current workspace")])
                    [:span.management-mobile-meta role]]
                   [:td.management-role role]
                   [:td.management-actions
                    (form request ::select-workspace (i18n/t "Open")
                          [:input {:type "hidden" :name "workspace-id" :value (str (:workspace-id membership))}])]]))]]
        [:div.management-empty
         [:p (i18n/t "No workspaces yet.")]
         [:p (i18n/t "Create a workspace to get started.")]])]
     [:footer.management-footer
      (form request (bel/action-url :tover.auth.web/sign-out) (i18n/t "Sign out"))]]))


(defpage page
  {:route      "/workspace"
   :title      "Workspaces · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :authenticated}
   :query      web/query-request}
  [request]
  {:type :elements
   :html [:section.management-page {:aria-labelledby "workspace-title"}
          [:header.management-header
           [:h1#workspace-title {:tabindex -1} (i18n/t "Workspaces")]]
          (workspace-content request)]})


(defn- activate-workspace!
  {:malli/schema [:=> [:cat :map :map :uuid] :map]}
  [{:keys [session-store realtime]} request workspace-id]
  (sessions/select-workspace! session-store (:session/key request) workspace-id)
  (realtime/close-session! realtime (:session/key request))
  {:type :page :id :tover.stock.web/page :reload? true})


(defaction select-workspace
  {:route-data {:tover/access :authenticated :bel/error-page ::page}}
  [{:keys [request] :as services} {:keys [workspace-id]}]
  (let [id (some-> workspace-id parse-uuid)]
    (when-not (some #(= id (:workspace-id %)) (get-in request [:tover/identity :memberships]))
      (security/failure 403 :forbidden "This workspace is not accessible."))
    (activate-workspace! services request id)))


(defaction create-workspace
  {:route-data {:tover/access :authenticated :bel/error-page ::page :bel/retain ["name"]}}
  [{:keys [workspaces request] :as services} {:keys [name]}]
  (let [id (workspaces/create-workspace! workspaces (security/access-token services request) name)]
    (activate-workspace! services request id)))

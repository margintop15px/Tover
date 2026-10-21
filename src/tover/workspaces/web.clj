(ns tover.workspaces.web
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.realtime :as realtime]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.shared.ui.forms :refer [field form]]
    [tover.shared.web.core :as web]
    [tover.workspaces.core :as workspaces]))


(defn workspace-content
  {:malli/schema [:=> [:cat :map] [:vector :any]]} [request]
  (let [{:keys [memberships workspace-id]} (:tover/identity request)]
    [:div.identity-content
     (when (seq memberships)
       (form request ::select-workspace "Open workspace"
             [:label "Workspace"
              (into [:select {:name "workspace-id"}]
                    (map (fn [membership]
                           [:option {:value    (str (:workspace-id membership))
                                     :selected (= workspace-id (:workspace-id membership))}
                            (:name membership)]) memberships))]))
     [:h2 "Create workspace"]
     (form request ::create-workspace "Create workspace" (field request "name" "Workspace name" "text"))
     (form request (bel/action-url :tover.auth.web/sign-out) "Sign out")]))


(defpage page
  {:route      "/workspace"
   :title      "Workspaces · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :authenticated}
   :query      web/query-request}
  [request]
  {:type :elements
   :html [:section.identity {:aria-labelledby "workspace-title"}
          [:h1#workspace-title {:tabindex -1} "Workspaces"]
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

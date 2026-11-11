(ns tover.workspaces.create.web
  "Isolated action/view path. Live discovery remains unchanged until onboarding cutover."
  (:require
    [bel.core :refer [defaction defview]]
    [tover.workspaces.create.model :as model]))


(defview ready
  {:schema     model/CreationQuery            :read-model ::model/created-workspace
   :route-data {:tover/access :authenticated}}
  [{:keys [model]}]
  {:type :elements
   :html [:section#workspace-creation {:data-workspace-id (:workspace-id model) :role "status"}
          [:h1 "Workspace created"]
          [:p (:name model)]
          (if (:can-open? model)
            [:p "Current role: " (:role model)]
            [:p "You no longer have access to this workspace."])]})


(defview failed
  {:schema     [:map [:command-id :string] [:workspace-id :uuid] [:name :string] [:error :map]]
   :route-data {:tover/access :authenticated}}
  [{:keys [error command-id workspace-id name]}]
  {:type :elements
   :html [:section#workspace-creation {:role "alert" :data-command-id command-id :data-workspace-id workspace-id}
          [:h1 "Workspace request needs attention"]
          [:p (:message error)]
          [:p name]]})


(defaction create
  {:schema     (conj model/CreateWorkspace [:command-id [:string {:min 1 :max 100}]])
   :route-data {:tover/access :authenticated}}
  [_ {:keys [command-id workspace-id name]}]
  {:type  :command :id ::model/create-workspace :command-id command-id
   :input {:workspace-id workspace-id :name name}
   :then  {:type :view :id ::ready :input {:command-id command-id :workspace-id workspace-id}}
   :catch {:type :view :id ::failed :input {:command-id command-id :workspace-id workspace-id :name name}}})

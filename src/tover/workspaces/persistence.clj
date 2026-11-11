(ns tover.workspaces.persistence
  "Bindings to Tover's existing directory tables."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]
    [tover.workspaces.create.model :as model]))


(defmethod postgres/model-binding ::model/profiles
  [_]
  {:table   :public.profiles
   :columns {:user-id :user_id :display-name :display_name}})


(defmethod postgres/model-binding ::model/workspaces
  [_]
  {:table   :public.organizations
   :columns {:workspace-id :id :name :name :created-by :created_by :created-at :created_at}})


(defmethod postgres/model-binding ::model/memberships
  [_]
  {:table   :public.organization_memberships
   :columns {:membership-id :id      :workspace-id :organization_id
             :user-id       :user_id :role         :role_id         :status :status}})


(defmethod runtime/coeffect :postgres/membership
  [_ {:keys [tx actor]} {:keys [workspace-id]}]
  (let [{:keys [table columns]} (postgres/model-binding {:model-id ::model/memberships})]
    (db/execute-one! tx {:select (db/selection columns) :from [table]
                         :where  [:and [:= :organization_id workspace-id] [:= :user_id actor]]})))

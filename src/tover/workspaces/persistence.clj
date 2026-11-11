(ns tover.workspaces.persistence
  "Bindings to Tover's existing directory tables."
  (:require
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]
    [tover.workspaces.create.model :as model]))


(def tables
  {::model/profiles    {:table   :public.profiles
                        :columns {:user-id :user_id :display-name :display_name}}
   ::model/workspaces  {:table   :public.organizations
                        :columns {:workspace-id :id :name :name :created-by :created_by :created-at :created_at}}
   ::model/memberships {:table   :public.organization_memberships
                        :columns {:membership-id :id      :workspace-id :organization_id
                                  :user-id       :user_id :role         :role_id         :status :status}}})


(defmethod runtime/coeffect ::membership
  [_ context {:keys [workspace-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx actor]}]
      (let [{:keys [table columns]} (::model/memberships tables)]
        (db/execute-one! tx {:select (db/selection columns) :from [table]
                             :where  [:and [:= :organization_id workspace-id] [:= :user_id actor]]})))))

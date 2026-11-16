(ns tover.workspaces.persistence
  "Bindings to Tover's existing directory tables."
  (:require
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]
    [tover.identity.session :as session]
    [tover.workspaces.create.model :as model]
    [tover.workspaces.select.model :as select]))


(def tables
  {::model/workspaces  {:table   :public.organizations
                        :columns {:workspace-id :id :name :name :created-by :created_by :created-at :created_at}}
   ::model/memberships {:table   :public.organization_memberships
                        :columns {:membership-id :id      :workspace-id :organization_id
                                  :user-id       :user_id :role         :role_id         :status :status}}
   ::select/selections (session/session-table [:workspace-id :version])})


(defprotocol Memberships
  (^{:malli/schema [:=> [:cat :any :uuid :uuid] [:maybe :map]]}
    -membership [tx user-id workspace-id] "The account's membership in the workspace, read in the store transaction."))


(extend-protocol Memberships
  java.sql.Connection
  (-membership [tx user-id workspace-id]
    (let [{:keys [table columns]} (::model/memberships tables)]
      (db/execute-one! tx {:select (db/selection columns) :from [table]
                           :where  [:and [:= :organization_id workspace-id] [:= :user_id user-id]]}))))


(defmethod runtime/coeffect ::membership
  [_ context {:keys [workspace-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx actor]}]
      (-membership tx actor workspace-id))))

(ns tover.workspaces.persistence
  "Directory reads beyond model loads."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-postgres.jdbc :as db]
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [tover.workspaces.create.model :as model]))


(defprotocol Memberships
  (^{:malli/schema [:=> [:cat :any :uuid :uuid] [:maybe :map]]}
    -membership [tx user-id workspace-id] "The account's membership in the workspace, read in the store transaction."))


(extend-protocol Memberships
  java.sql.Connection
  (-membership [tx user-id workspace-id]
    (let [{:keys [table columns]} (postgres/table-binding (bel/get-model ::model/memberships))]
      (db/execute-one! tx {:select (db/selection columns) :from [table]
                           :where  [:and [:= :workspace_id workspace-id] [:= :user_id user-id]]}))))


(defmethod runtime/coeffect ::membership
  [_ context {:keys [workspace-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx actor]}]
      (-membership tx actor workspace-id))))

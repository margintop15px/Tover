(ns tover.memberships.persistence
  "Membership reads for the membership commands, inside the command's directory transaction. `::target` needs
  manager read permission (row policy `membership_manager_read`); `::active-owners` locks the workspace's
  active Owner rows so concurrent departures cannot both see a co-owner."
  (:require
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]))


(def ^:private columns
  [[:membership_id :membership-id] [:workspace_id :workspace-id] [:user_id :user-id] :role :status :revision
   [:removed_at :removed-at] [:removal_kind :removal-kind] [:suspended_at :suspended-at]
   [:suspension_reason :suspension-reason]])


(defn- fields
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]}
  [row]
  (some->> row (into {} (remove (comp nil? val)))))


(defmethod runtime/coeffect ::target
  [_ {:keys [scope] :as context} {:keys [membership-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (fields (db/execute-one! tx {:select columns :from [:memberships]
                                   :where  [:and [:= :membership_id membership-id] [:= :workspace_id (:workspace-id scope)]]})))))


(defmethod runtime/coeffect ::own
  [_ {:keys [scope] :as context} _]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx actor]}]
      (fields (db/execute-one! tx {:select columns :from [:memberships]
                                   :where  [:and [:= :workspace_id (:workspace-id scope)] [:= :user_id actor]]})))))


(defmethod runtime/coeffect ::active-owners
  [_ {:keys [scope] :as context} _]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (mapv :owner (db/execute! tx {:select [[[:app_private.bel_active_owners (:workspace-id scope)] :owner]]})))))

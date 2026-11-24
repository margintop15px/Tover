(ns tover.invitations.persistence
  "Invitation reads beyond model loads. Coeffects return field maps; the team, recipient and membership reads
  go through the SECURITY DEFINER functions of the baseline migration."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-postgres.jdbc :as db]
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [tover.invitations.issue.model :as model]))


(defn- binding-of
  {:malli/schema [:=> [:cat :qualified-keyword] :map]}
  [model-id]
  (postgres/table-binding (bel/get-model model-id)))


(defn- fields
  "Drop absent values so rows read like model entities."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]}
  [row]
  (some->> row (into {} (remove (comp nil? val)))))


(defmethod runtime/coeffect ::open-invitation
  [_ {:keys [scope] :as context} {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (let [{:keys [table columns]} (binding-of ::model/invitations)]
        (db/execute-one! tx {:select   (db/selection columns) :from [table]
                             :where    [:and [:= :workspace_id (:workspace-id scope)] [:= :status "pending"]
                                        [:= [:lower [:btrim :email]] email]]
                             :order-by [[:created_at :desc]] :limit 1})))))


(defmethod runtime/coeffect ::recipient-access
  [_ {:keys [scope] :as context} {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (fields (db/execute-one! tx {:select [:status [:removed_at :removed-at]]
                                   :from   [[[:app_private.bel_recipient_access (:workspace-id scope) email] :access]]})))))


(defmethod runtime/coeffect ::due-deliveries
  [_ context _]
  ;; Due times are written from the application clock, so compare with it, not the database's now().
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      ;; Unconfirmed requests are due when their 60-second reconciliation deadline passes.
      (db/execute! tx {:select   [[:delivery_id :delivery-id] [:workspace_id :workspace-id] [:requested_by :requested-by] :attempts
                                  [:delivery_status :delivery-status]]
                       :from     [:invitation_deliveries]
                       :where    [:and [:in :delivery_status ["queued" "retrying" "unconfirmed"]]
                                  [:<= :next_attempt_at (runtime/coeffect :bel/now context {})]]
                       :order-by [:next_attempt_at]
                       :limit    100}))))


(defmethod runtime/coeffect ::recipient-language
  [_ context {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (:language (db/execute-one! tx {:select [[[:app_private.bel_recipient_language email] :language]]})))))


(defmethod runtime/coeffect ::workspace-name
  [_ {:keys [scope] :as context} _]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (:name (db/execute-one! tx {:select [:name] :from [:workspaces] :where [:= :workspace_id (:workspace-id scope)]})))))


(defmethod runtime/coeffect ::latest-invitation
  [_ {:keys [scope] :as context} {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (let [{:keys [table columns]} (binding-of ::model/invitations)]
        (db/execute-one! tx {:select   (db/selection columns) :from [table]
                             :where    [:and [:= :workspace_id (:workspace-id scope)] [:= [:lower [:btrim :email]] email]]
                             :order-by [[:created_at :desc]] :limit 1})))))


(defmethod runtime/coeffect ::latest-delivery
  [_ context {:keys [invitation-id]}]
  (when invitation-id
    (runtime/with-store context :directory :repeatable-read
      (fn [{:keys [tx]}]
        (let [{:keys [table columns]} (binding-of ::model/invitation-deliveries)]
          (db/execute-one! tx {:select   (db/selection columns)  :from  [table] :where [:= :invitation_id invitation-id]
                               :order-by [[:requested_at :desc]] :limit 1}))))))


(defmethod runtime/coeffect ::received-invitation
  [_ context {:keys [invitation-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (fields (db/execute-one! tx {:select [[:invitation_id :invitation-id] [:workspace_id :workspace-id] [:workspace_name :workspace-name]
                                            :email :role :inviter :status
                                            [:expires_at :expires-at] [:created_at :created-at]]
                                   :from   [[[:app_private.bel_received_invitation invitation-id] :invitation]]})))))


(defmethod runtime/coeffect ::own-membership
  [_ context {:keys [workspace-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx actor]}]
      (fields (db/execute-one! tx {:select [[:membership_id :membership-id] :role :status :revision [:removed_at :removed-at]]
                                   :from   [:memberships]
                                   :where  [:and [:= :workspace_id workspace-id] [:= :user_id actor]]})))))


(defmethod runtime/coeffect ::team
  [_ {:keys [scope] :as context} _]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (let [workspace-id (:workspace-id scope)
            invitations  (binding-of ::model/invitations)
            deliveries   (binding-of ::model/invitation-deliveries)]
        {:members     (mapv fields (db/execute! tx {:select [[:membership_id :membership-id] [:user_id :user-id] :name :email
                                                             :role :status :revision [:removed_at :removed-at]
                                                             [:removal_kind :removal-kind] [:suspended_at :suspended-at]
                                                             [:suspension_reason :suspension-reason]]
                                                    :from   [[[:app_private.bel_workspace_team workspace-id] :team]]}))
         :invitations (mapv fields (db/execute! tx {:select   (db/selection (:columns invitations)) :from  [(:table invitations)]
                                                    :where    [:= :workspace_id workspace-id]
                                                    :order-by [[:created_at :desc]]                 :limit 200}))
         :deliveries  (mapv fields (db/execute! tx {:select   (db/selection (:columns deliveries)) :from  [(:table deliveries)]
                                                    :where    [:= :workspace_id workspace-id]
                                                    :order-by [[:requested_at :desc]]              :limit 400}))}))))

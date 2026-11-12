(ns tover.invitations.persistence
  "Bindings to the invitation tables and private recipient lookups."
  (:require
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]
    [tover.invitations.issue.model :as model]))


(def tables
  {::model/invitations {:table   :public.organization_invites
                        :columns {:invitation-id :id         :workspace-id :organization_id :email  :email
                                  :role          :role_id    :invited-by   :invited_by      :status :status
                                  :expires-at    :expires_at :created-at   :created_at}}
   ::model/deliveries  {:table   :public.invitation_deliveries
                        :columns {:delivery-id  :id           :invitation-id   :invitation_id   :workspace-id    :organization_id
                                  :requested-by :requested_by :requested-at    :requested_at    :delivery-status :status
                                  :attempts     :attempts     :next-attempt-at :next_attempt_at
                                  :last-problem :last_problem :attempted-at    :attempted_at    :submitted-at    :submitted_at}}})


(defmethod runtime/coeffect ::open-invitation
  [_ {:keys [scope] :as context} {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (let [{:keys [table columns]} (::model/invitations tables)]
        (db/execute-one! tx {:select   (db/selection columns) :from [table]
                             :where    [:and [:= :organization_id (:workspace-id scope)] [:= :status "pending"]
                                        [:= [:lower [:btrim :email]] email]]
                             :order-by [[:created_at :desc]] :limit 1})))))


(defmethod runtime/coeffect ::recipient-membership
  [_ {:keys [scope] :as context} {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (:status (db/execute-one! tx {:select [[[:app_private.bel_recipient_membership (:workspace-id scope) email] :status]]})))))


(defmethod runtime/coeffect ::due-deliveries
  [_ context _]
  ;; Due times are written from the application clock, so compare with it, not the database's now().
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (db/execute! tx {:select   [[:id :delivery-id] [:organization_id :workspace-id] [:requested_by :requested-by] :attempts]
                       :from     [:public.invitation_deliveries]
                       :where    [:and [:in :status ["queued" "retrying"]] [:<= :next_attempt_at (runtime/coeffect :bel/now context {})]]
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
      (:name (db/execute-one! tx {:select [:name] :from [:public.organizations] :where [:= :id (:workspace-id scope)]})))))


(defmethod runtime/coeffect ::latest-invitation
  [_ {:keys [scope] :as context} {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (let [{:keys [table columns]} (::model/invitations tables)]
        (db/execute-one! tx {:select   (db/selection columns) :from [table]
                             :where    [:and [:= :organization_id (:workspace-id scope)] [:= [:lower [:btrim :email]] email]]
                             :order-by [[:created_at :desc]] :limit 1})))))


(defmethod runtime/coeffect ::latest-delivery
  [_ context {:keys [invitation-id]}]
  (when invitation-id
    (runtime/with-store context :directory :repeatable-read
      (fn [{:keys [tx]}]
        (let [{:keys [table columns]} (::model/deliveries tables)]
          (db/execute-one! tx {:select   (db/selection columns)  :from  [table] :where [:= :invitation_id invitation-id]
                               :order-by [[:requested_at :desc]] :limit 1}))))))

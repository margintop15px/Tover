(ns tover.invitations.persistence
  "Bindings to the invitation tables and private recipient lookups. Coeffects return field maps; the team,
  recipient and membership reads go through the SECURITY DEFINER functions of migration 042."
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
                                  :last-problem :last_problem :attempted-at    :attempted_at    :submitted-at    :submitted_at}}
   :tover.invitations.accept.model/memberships
   {:table   :public.organization_memberships
    :columns {:membership-id :id :workspace-id :organization_id :user-id :user_id :role :role_id :status :status}}})


(defn- fields
  "Drop absent values so rows read like model entities."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]}
  [row]
  (some->> row (into {} (remove (comp nil? val)))))


(defmethod runtime/coeffect ::open-invitation
  [_ {:keys [scope] :as context} {:keys [email]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (let [{:keys [table columns]} (::model/invitations tables)]
        (db/execute-one! tx {:select   (db/selection columns) :from [table]
                             :where    [:and [:= :organization_id (:workspace-id scope)] [:= :status "pending"]
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
      (db/execute! tx {:select   [[:id :delivery-id] [:organization_id :workspace-id] [:requested_by :requested-by] :attempts
                                  [:status :delivery-status]]
                       :from     [:public.invitation_deliveries]
                       :where    [:and [:in :status ["queued" "retrying" "unconfirmed"]]
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


(defmethod runtime/coeffect ::received-invitation
  [_ context {:keys [invitation-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (fields (db/execute-one! tx {:select [[:id :invitation-id] [:organization_id :workspace-id] [:workspace_name :workspace-name]
                                            :email [:role_id :role] [:inviter_name :inviter] :status
                                            [:expires_at :expires-at] [:created_at :created-at]]
                                   :from   [[[:app_private.bel_received_invitation invitation-id] :invitation]]})))))


(defmethod runtime/coeffect ::own-membership
  [_ context {:keys [workspace-id]}]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx actor]}]
      (fields (db/execute-one! tx {:select [[:id :membership-id] [:role_id :role] :status [:removed_at :removed-at]]
                                   :from   [:public.organization_memberships]
                                   :where  [:and [:= :organization_id workspace-id] [:= :user_id actor]]})))))


(defmethod runtime/coeffect ::team
  [_ {:keys [scope] :as context} _]
  (runtime/with-store context :directory :repeatable-read
    (fn [{:keys [tx]}]
      (let [workspace-id (:workspace-id scope)
            invitations  (::model/invitations tables)
            deliveries   (::model/deliveries tables)]
        {:members     (mapv fields (db/execute! tx {:select [[:user_id :user-id] [:display_name :name] :email [:role_id :role] :status
                                                             [:removed_at :removed-at]]
                                                    :from   [[[:app_private.bel_workspace_team workspace-id] :team]]}))
         :invitations (mapv fields (db/execute! tx {:select   (db/selection (:columns invitations)) :from  [(:table invitations)]
                                                    :where    [:= :organization_id workspace-id]
                                                    :order-by [[:created_at :desc]]                 :limit 200}))
         :deliveries  (mapv fields (db/execute! tx {:select   (db/selection (:columns deliveries)) :from  [(:table deliveries)]
                                                    :where    [:= :organization_id workspace-id]
                                                    :order-by [[:requested_at :desc]]              :limit 400}))}))))

(ns tover.invitations.fixtures
  "Invitation test support: the slice registry, and in-memory versions of the SQL coeffects of
  `tover.invitations.persistence` over the `tover.fixtures` state, installed only while a test runs.
  The SQL and its row policies are covered by test/invitation_delivery_integration.clj."
  (:require
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [tover.invitations.accept.model]
    [tover.invitations.cancel.model]
    [tover.invitations.delivery.model]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.resend.model]
    [tover.invitations.status.model]
    [tover.invitations.team.model]
    [tover.workspaces.create.model]))


(def model-namespaces
  ['tover.accounts.profile.model 'tover.workspaces.create.model
   'tover.invitations.issue.model 'tover.invitations.delivery.model 'tover.invitations.status.model
   'tover.invitations.resend.model 'tover.invitations.cancel.model 'tover.invitations.accept.model
   'tover.invitations.team.model])


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! model-namespaces)
  (f))


(defn- db
  "The fixture database: the directory store's transaction is the state atom."
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  (runtime/with-store context :directory :repeatable-read (fn [{:keys [tx]}] (:db @tx))))


(defn- present
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (into {} (remove (comp nil? val)) row))


(defn- invitation
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (present {:invitation-id (:id row)         :workspace-id (:organization_id row) :email      (:email row)      :role       (:role_id row)
            :invited-by    (:invited_by row) :status       (:status row)          :expires-at (:expires_at row) :created-at (:created_at row)}))


(defn- delivery
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (present {:delivery-id  (:id row)           :invitation-id   (:invitation_id row)   :workspace-id    (:organization_id row)
            :requested-by (:requested_by row) :requested-at    (:requested_at row)    :delivery-status (:status row)
            :attempts     (:attempts row)     :next-attempt-at (:next_attempt_at row) :last-problem    (:last_problem row)
            :attempted-at (:attempted_at row) :submitted-at    (:submitted_at row)}))


(defn- email-of
  {:malli/schema [:=> [:cat :map [:maybe :uuid]] [:maybe :string]]}
  [db user-id]
  (let [user (get-in db [:auth.users user-id])]
    (when (:email_confirmed_at user) (issue/normalize (:email user)))))


(defn- manager?
  {:malli/schema [:=> [:cat :map :uuid [:maybe :uuid]] :boolean]}
  [db workspace-id user-id]
  (boolean (some #(and (= workspace-id (:organization_id %)) (= user-id (:user_id %)) (= "active" (:status %))
                       (contains? #{"owner" "admin"} (:role_id %)))
                 (vals (:public.organization_memberships db)))))


(defn- recipient-access
  {:malli/schema [:=> [:cat :map :uuid :string] [:maybe :map]]}
  [db workspace-id email]
  (some (fn [membership]
          (when (and (= workspace-id (:organization_id membership))
                     (= (issue/normalize email) (email-of db (:user_id membership))))
            (present {:status (:status membership) :removed-at (:removed_at membership)})))
        (vals (:public.organization_memberships db))))


(def coeffects
  "Memory versions keyed by the persistence coeffect they replace."
  {:tover.invitations.persistence/open-invitation
   (fn [{:keys [scope] :as context} {:keys [email]}]
     (->> (vals (:public.organization_invites (db context)))
          (filter #(and (= (:workspace-id scope) (:organization_id %)) (= "pending" (:status %))
                        (= email (issue/normalize (:email %)))))
          (sort-by :created_at #(compare %2 %1))
          first
          (#(some-> % invitation))))

   :tover.invitations.persistence/recipient-access
   (fn [{:keys [scope actor] :as context} {:keys [email]}]
     (let [db (db context)]
       (when (manager? db (:workspace-id scope) actor) (recipient-access db (:workspace-id scope) email))))

   :tover.invitations.persistence/latest-invitation
   (fn [{:keys [scope] :as context} {:keys [email]}]
     (->> (vals (:public.organization_invites (db context)))
          (filter #(and (= (:workspace-id scope) (:organization_id %)) (= email (issue/normalize (:email %)))))
          (sort-by :created_at #(compare %2 %1))
          first
          (#(some-> % invitation))))

   :tover.invitations.persistence/latest-delivery
   (fn [context {:keys [invitation-id]}]
     (->> (vals (:public.invitation_deliveries (db context)))
          (filter #(= invitation-id (:invitation_id %)))
          (sort-by :requested_at #(compare %2 %1))
          first
          (#(some-> % delivery))))

   :tover.invitations.persistence/received-invitation
   (fn [{:keys [actor] :as context} {:keys [invitation-id]}]
     (let [db  (db context)
           row (get-in db [:public.organization_invites invitation-id])]
       (when (and row (= (issue/normalize (:email row)) (email-of db actor)))
         (assoc (invitation row)
                :email (issue/normalize (:email row))
                :workspace-name (get-in db [:public.organizations (:organization_id row) :name])
                :inviter (get-in db [:public.profiles (:invited_by row) :display_name])))))

   :tover.invitations.persistence/own-membership
   (fn [{:keys [actor] :as context} {:keys [workspace-id]}]
     (some (fn [m]
             (when (and (= workspace-id (:organization_id m)) (= actor (:user_id m)))
               (present {:membership-id (:id m) :role (:role_id m) :status (:status m) :removed-at (:removed_at m)})))
           (vals (:public.organization_memberships (db context)))))

   :tover.invitations.persistence/team
   (fn [{:keys [scope actor] :as context} _]
     (let [db        (db context)
           workspace (:workspace-id scope)]
       (when-not (manager? db workspace actor) (throw (ex-info "Forbidden" {:type :forbidden :status 403})))
       {:members     (vec (for [m     (vals (:public.organization_memberships db))
                                :when (and (= workspace (:organization_id m)) (contains? #{"active" "suspended" "removed"} (:status m)))]
                            (present {:user-id    (:user_id m)               :name (get-in db [:public.profiles (:user_id m) :display_name])
                                      :email      (email-of db (:user_id m)) :role (:role_id m) :status (:status m)
                                      :removed-at (:removed_at m)})))
        :invitations (vec (for [row   (vals (:public.organization_invites db))
                                :when (= workspace (:organization_id row))]
                            (invitation row)))
        :deliveries  (vec (for [row   (vals (:public.invitation_deliveries db))
                                :when (= workspace (:organization_id row))]
                            (delivery row)))}))})


(defn with-memory-coeffects
  "Install the memory coeffects for the duration of f, then restore the SQL ones."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (let [previous (into {} (map (fn [id] [id (get-method runtime/coeffect id)])) (keys coeffects))]
    (try
      (doseq [[id coeffect] coeffects]
        (.addMethod ^clojure.lang.MultiFn runtime/coeffect id (fn [_ context input] (coeffect context input))))
      (f)
      (finally
        (doseq [[id method] previous]
          (.addMethod ^clojure.lang.MultiFn runtime/coeffect id method))))))

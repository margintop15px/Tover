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
  (present {:invitation-id (:invitation_id row) :workspace-id (:workspace_id row) :email      (:email row)      :role       (:role row)
            :invited-by    (:invited_by row)    :status       (:status row)       :expires-at (:expires_at row) :created-at (:created_at row)}))


(defn- delivery
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (present {:delivery-id  (:delivery_id row)  :invitation-id   (:invitation_id row)   :workspace-id    (:workspace_id row)
            :requested-by (:requested_by row) :requested-at    (:requested_at row)    :delivery-status (:delivery_status row)
            :attempts     (:attempts row)     :next-attempt-at (:next_attempt_at row) :last-problem    (:last_problem row)
            :attempted-at (:attempted_at row) :submitted-at    (:submitted_at row)}))


(defn- membership
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (present {:membership-id (:membership_id row) :workspace-id      (:workspace_id row)      :user-id  (:user_id row)
            :role          (:role row)          :status            (:status row)            :revision (or (:revision row) 1)
            :removed-at    (:removed_at row)    :removal-kind      (:removal_kind row)
            :suspended-at  (:suspended_at row)  :suspension-reason (:suspension_reason row)}))


(defn- email-of
  {:malli/schema [:=> [:cat :map [:maybe :uuid]] [:maybe :string]]}
  [db user-id]
  (let [user (get-in db [:auth.users user-id])]
    (when (:email_confirmed_at user) (issue/normalize (:email user)))))


(defn- manager?
  {:malli/schema [:=> [:cat :map :uuid [:maybe :uuid]] :boolean]}
  [db workspace-id user-id]
  (boolean (some #(and (= workspace-id (:workspace_id %)) (= user-id (:user_id %)) (= "active" (:status %))
                       (contains? #{"owner" "admin"} (:role %)))
                 (vals (:memberships db)))))


(defn- recipient-access
  {:malli/schema [:=> [:cat :map :uuid :string] [:maybe :map]]}
  [db workspace-id email]
  (some (fn [membership]
          (when (and (= workspace-id (:workspace_id membership))
                     (= (issue/normalize email) (email-of db (:user_id membership))))
            (present {:status (:status membership) :removed-at (:removed_at membership)})))
        (vals (:memberships db))))


(def coeffects
  "Memory versions keyed by the persistence coeffect they replace."
  {:tover.invitations.persistence/open-invitation
   (fn [{:keys [scope] :as context} {:keys [email]}]
     (->> (vals (:invitations (db context)))
          (filter #(and (= (:workspace-id scope) (:workspace_id %)) (= "pending" (:status %))
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
     (->> (vals (:invitations (db context)))
          (filter #(and (= (:workspace-id scope) (:workspace_id %)) (= email (issue/normalize (:email %)))))
          (sort-by :created_at #(compare %2 %1))
          first
          (#(some-> % invitation))))

   :tover.invitations.persistence/latest-delivery
   (fn [context {:keys [invitation-id]}]
     (->> (vals (:invitation_deliveries (db context)))
          (filter #(= invitation-id (:invitation_id %)))
          (sort-by :requested_at #(compare %2 %1))
          first
          (#(some-> % delivery))))

   :tover.invitations.persistence/received-invitation
   (fn [{:keys [actor] :as context} {:keys [invitation-id]}]
     (let [db  (db context)
           row (get-in db [:invitations invitation-id])]
       (when (and row (= (issue/normalize (:email row)) (email-of db actor)))
         (assoc (invitation row)
                :email (issue/normalize (:email row))
                :workspace-name (get-in db [:workspaces (:workspace_id row) :name])
                :inviter (get-in db [:profiles (:invited_by row) :display_name])))))

   :tover.invitations.persistence/own-membership
   (fn [{:keys [actor] :as context} {:keys [workspace-id]}]
     (some (fn [m]
             (when (and (= workspace-id (:workspace_id m)) (= actor (:user_id m)))
               (present {:membership-id (:membership_id m) :role (:role m) :status (:status m) :revision (:revision m)
                         :removed-at    (:removed_at m)})))
           (vals (:memberships (db context)))))

   :tover.memberships.persistence/target
   (fn [{:keys [scope] :as context} {:keys [membership-id]}]
     (let [m (get-in (db context) [:memberships membership-id])]
       (when (= (:workspace-id scope) (:workspace_id m)) (membership m))))

   :tover.memberships.persistence/own
   (fn [{:keys [scope actor] :as context} _]
     (some #(when (and (= (:workspace-id scope) (:workspace_id %)) (= actor (:user_id %))) (membership %))
           (vals (:memberships (db context)))))

   :tover.memberships.persistence/active-owners
   (fn [{:keys [scope] :as context} _]
     (vec (sort (for [m     (vals (:memberships (db context)))
                      :when (and (= (:workspace-id scope) (:workspace_id m)) (= "owner" (:role m)) (= "active" (:status m)))]
                  (:user_id m)))))

   :tover.invitations.persistence/team
   (fn [{:keys [scope actor] :as context} _]
     (let [db        (db context)
           workspace (:workspace-id scope)]
       (when-not (manager? db workspace actor) (throw (ex-info "Forbidden" {:type :forbidden :status 403})))
       {:members     (vec (for [m     (vals (:memberships db))
                                :when (and (= workspace (:workspace_id m)) (contains? #{"active" "suspended" "removed"} (:status m)))]
                            (present (assoc (membership m)
                                            :name (get-in db [:profiles (:user_id m) :display_name])
                                            :email (email-of db (:user_id m))))))
        :invitations (vec (for [row   (vals (:invitations db))
                                :when (= workspace (:workspace_id row))]
                            (invitation row)))
        :deliveries  (vec (for [row   (vals (:invitation_deliveries db))
                                :when (= workspace (:workspace_id row))]
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

(ns tover.workspaces.supabase
  (:require
    [clojure.string :as str]
    [tover.auth.core :as auth]
    [tover.shared.supabase :as http]
    [tover.workspaces.core :as workspaces])
  (:import
    [java.util UUID]))


(def ^:private Text
  [:string {:min 1}])


(def ^:private rpc-codes
  {"INVALID_INPUT"   [400 :invalid-input]
   "NOT_FOUND"       [404 :not-found]
   "INVITE_CHANGED"  [409 :invite-changed]
   "INVITE_EXISTS"   [409 :invite-exists]
   "MEMBER_EXISTS"   [409 :member-exists]
   "MEMBER_INACTIVE" [409 :member-inactive]
   "RATE_LIMITED"    [429 :rate-limited]})


(defn- rpc-result
  {:malli/schema [:=> [:cat :any] :any]}
  [data]
  (if-let [[status code] (get rpc-codes (:code data))]
    (auth/fail! code status false
                (cond-> {}
                  (integer? (:retryAfter data)) (assoc :retry-after (:retryAfter data))
                  (string? (:inviteId data)) (assoc :invite-id (parse-uuid (:inviteId data)))))
    (if (:code data)
      (auth/fail! :invalid-response 502 true)
      data)))


(defn- normalize-record
  {:malli/schema [:=> [:cat :map] :map]}
  [record]
  (into {}
        (map (fn [[key value]]
               [(case key
                  :userId :user-id
                  :emailConfirmedAt :email-confirmed-at
                  :lastSignInAt :last-sign-in-at
                  :recoveryRequestedAt :recovery-requested-at
                  :createdAt :created-at
                  :expiresAt :expires-at
                  :lastRequestedAt :last-requested-at
                  key)
                (cond
                  (contains? #{:id :userId} key) (some-> value parse-uuid)
                  (contains? #{:role :status} key) (keyword value)
                  :else value)]))
        record))


(defn workspaces
  "PostgREST RPCs run as the user, with the database enforcing workspace policy."
  {:malli/schema [:=> [:cat :map] [:fn #(satisfies? workspaces/Directory %)]]}
  [{:keys [rest-url api-key timeout-ms]}]
  (auth/check-input! Text api-key)
  (let [base      (http/base-url rest-url)
        transport (cond-> {:client (http/client) :api-key api-key}
                    timeout-ms (assoc :timeout-ms timeout-ms))
        rpc!      (fn [token procedure args]
                    (rpc-result (http/request! transport {:url   (str base "/rpc/" procedure)
                                                          :token token
                                                          :body  args})))]
    (reify workspaces/Directory
      (list-memberships [_ token]
        (let [data (rpc! token "my_workspaces" {})]
          (try
            (auth/check-input! [:vector :map] data)
            (mapv (fn [membership]
                    (auth/check-input! workspaces/Membership
                                       {:workspace-id (UUID/fromString (:organization_id membership))
                                        :name         (:name membership)
                                        :role         (keyword (:role_id membership))
                                        :created-at   (:created_at membership)}))
                  data)
            (catch Exception _ (auth/fail! :invalid-response 502 false)))))
      (create-workspace! [_ token name]
        (auth/check-input! [:string {:min 2}] (str/trim name))
        (or (some-> (rpc! token "create_organization" {:p_name (str/trim name)}) parse-uuid)
            (auth/fail! :invalid-response 502 true)))
      (team [_ token workspace-id]
        (let [data (rpc! token "workspace_team" {:p_workspace_id (str workspace-id)})]
          {:members     (mapv normalize-record (:members data))
           :invitations (mapv normalize-record (:invitations data))}))
      (actor-labels [_ token workspace-id actor-ids]
        (auth/check-input! [:vector :uuid] actor-ids)
        (reduce (fn [labels ids]
                  (let [data      (rpc! token "workspace_actor_labels"
                                        {:p_workspace_id (str workspace-id)
                                         :p_actor_ids    (mapv str ids)})
                        requested (set (map str ids))]
                    (try
                      (auth/check-input! [:vector [:map [:userId :string] [:label [:string {:min 1}]]]] data)
                      (when-not (every? #(and (contains? requested (:userId %))
                                              (not (str/blank? (:label %)))) data)
                        (auth/fail! :invalid-response 502 false))
                      (catch Exception _ (auth/fail! :invalid-response 502 false)))
                    (when-not (= (count data) (count (distinct (map :userId data))))
                      (auth/fail! :invalid-response 502 false))
                    (into labels (map (juxt :userId :label)) data)))
                {} (partition-all 200 (distinct actor-ids))))
      (manage-invitation! [_ token {:keys [workspace-id action invite-id email role] :as input}]
        (auth/check-input! [:map [:workspace-id :uuid] [:action [:enum :create :resend :cancel]]]
                           input)
        (normalize-record
          (rpc! token "manage_workspace_invitation"
                (cond-> {:p_workspace_id (str workspace-id) :p_action (name action)}
                  invite-id (assoc :p_invite_id (str invite-id))
                  email (assoc :p_email email)
                  role (assoc :p_role (name role))))))
      (revoke-reservation! [_ token workspace-id invite-id]
        (rpc! token "revoke_workspace_invitation_reservation"
              {:p_workspace_id (str workspace-id) :p_invite_id (str invite-id)})
        nil)
      (request-member-recovery! [_ token workspace-id user-id]
        (rpc! token "request_member_recovery" {:p_workspace_id (str workspace-id) :p_user_id (str user-id)}))
      (accept-invites! [_ token]
        (let [accepted (rpc! token "accept_my_organization_invites" {})]
          (if (integer? accepted) accepted (auth/fail! :invalid-response 502 true)))))))

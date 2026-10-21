(ns tover.workspaces.supabase-test
  (:require
    [charred.api :as json]
    [clojure.test :refer [deftest is]]
    [tover.support :as support]
    [tover.workspaces.core :as workspaces]
    [tover.workspaces.supabase :as supabase]))


(def workspace-id
  #uuid "20000000-0000-4000-8000-000000000001")


(def invite-id
  #uuid "20000000-0000-4000-8000-000000000002")


(defn- adapter
  {:malli/schema [:=> [:cat :string] :any]}
  [url]
  (supabase/workspaces {:rest-url (str url "/rest/v1/") :api-key "public-key"}))


(deftest membership-rpc-is-user-authenticated-and-normalized
  (support/with-server
    (fn [_] {:body [{:organization_id (str workspace-id)     :name "Workspace" :role_id "owner"
                     :created_at      "2026-01-01T00:00:00Z"}]})
    (fn [{:keys [url requests]}]
      (is (= [{:workspace-id workspace-id :name "Workspace" :role :owner :created-at "2026-01-01T00:00:00Z"}]
             (workspaces/list-memberships (adapter url) "user-token")))
      (is (= "/rest/v1/rpc/my_workspaces" (:path (first @requests))))
      (is (= "Bearer user-token" (get-in (first @requests) [:headers "authorization"])))
      (is (= "public-key" (get-in (first @requests) [:headers "apikey"])))
      (is (= {} (:body (first @requests)))))))


(deftest team-rpc-normalizes-member-and-invitation-shapes
  (support/with-server
    (fn [_] {:body {:members     [{:userId           (str support/user-id) :email               "person@example.test" :role "admin" :status "active"
                                   :emailConfirmedAt "2026-01-01"          :recoveryRequestedAt nil}]
                    :invitations [{:id        (str invite-id) :email     "new@example.test" :role            "member"     :status "pending"
                                   :createdAt "2026-01-01"    :expiresAt "2026-01-08"       :lastRequestedAt "2026-01-01"}]}})
    (fn [{:keys [url requests]}]
      (is (= {:members     [{:user-id            support/user-id :email                 "person@example.test" :role :admin :status :active
                             :email-confirmed-at "2026-01-01"    :recovery-requested-at nil}]
              :invitations [{:id         invite-id    :email      "new@example.test" :role              :member      :status :pending
                             :created-at "2026-01-01" :expires-at "2026-01-08"       :last-requested-at "2026-01-01"}]}
             (workspaces/team (adapter url) "user-token" workspace-id)))
      (is (= {:p_workspace_id (str workspace-id)} (:body (first @requests)))))))


(deftest mutations-call-only-restricted-rpcs-with-explicit-workspace
  (support/with-server
    (fn [request]
      {:body (case (:path request)
               "/rest/v1/rpc/create_organization" (json/write-json-str (str workspace-id))
               "/rest/v1/rpc/manage_workspace_invitation" {:id (str invite-id) :email "person@example.test" :role "member"}
               "/rest/v1/rpc/request_member_recovery" {:email "stored@example.test"}
               "/rest/v1/rpc/accept_my_organization_invites" 2
               "/rest/v1/rpc/revoke_workspace_invitation_reservation" {:ok true})})
    (fn [{:keys [url requests]}]
      (let [provider (adapter url)]
        (is (= workspace-id (workspaces/create-workspace! provider "user-token" " Workspace ")))
        (is (= {:id invite-id :email "person@example.test" :role :member}
               (workspaces/manage-invitation! provider "user-token"
                                              {:workspace-id workspace-id :action :create :email "person@example.test" :role :member})))
        (is (= {:email "stored@example.test"}
               (workspaces/request-member-recovery! provider "user-token" workspace-id support/user-id)))
        (is (= 2 (workspaces/accept-invites! provider "user-token")))
        (is (nil? (workspaces/revoke-reservation! provider "user-token" workspace-id invite-id)))
        (is (= {:p_name "Workspace"} (:body (nth @requests 0))))
        (is (= {:p_workspace_id (str workspace-id) :p_action "create" :p_email "person@example.test" :p_role "member"}
               (:body (nth @requests 1))))
        (is (= {:p_workspace_id (str workspace-id) :p_user_id (str support/user-id)} (:body (nth @requests 2))))
        (is (= {:p_workspace_id (str workspace-id) :p_invite_id (str invite-id)} (:body (nth @requests 4))))
        (is (every? #(= "Bearer user-token" (get-in % [:headers "authorization"])) @requests))
        (is (every? #(= :post (:method %)) @requests))))))


(deftest rpc-business-errors-have-uniform-safe-codes
  (support/with-server
    (fn [_] {:body {:code "RATE_LIMITED" :retryAfter 42 :untrusted "upstream-secret"}})
    (fn [{:keys [url]}]
      (is (= {:type        :identity :code    :rate-limited              :status 429 :uncertain? false
              :retry-after 42        :message "Identity request failed."}
             (support/error-data #(workspaces/manage-invitation! (adapter url) "user-token"
                                                                 {:workspace-id workspace-id :action :resend :invite-id invite-id})))))))


(deftest cross-workspace-denial-is-never-retried-with-admin-credentials
  (support/with-server
    (fn [_] {:status 403 :body {:code "42501" :message "Forbidden upstream-secret"}})
    (fn [{:keys [url requests]}]
      (is (= :forbidden (:code (support/error-data #(workspaces/team (adapter url) "user-token" workspace-id)))))
      (is (= 1 (count @requests)))
      (is (= "Bearer user-token" (get-in (first @requests) [:headers "authorization"]))))))

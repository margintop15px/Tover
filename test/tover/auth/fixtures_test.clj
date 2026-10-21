(ns tover.auth.fixtures-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.auth.core :as auth]
    [tover.identity :as identity]
    [tover.support :as support]
    [tover.workspaces.core :as workspaces]))


(deftest fixture-services-enforce-membership-and-manager-policy
  (let [{:keys [auth workspaces users workspace-id other-workspace-id state]} (identity/fixtures)
        [owner member other] users
        owner-token (:access-token (auth/sign-in! auth owner))
        member-token (:access-token (auth/sign-in! auth member))
        other-token (:access-token (auth/sign-in! auth other))]
    (is (= [workspace-id other-workspace-id] (mapv :workspace-id (workspaces/list-memberships workspaces owner-token))))
    (is (= [workspace-id] (mapv :workspace-id (workspaces/list-memberships workspaces member-token))))
    (is (= 2 (count (:members (workspaces/team workspaces owner-token workspace-id)))))
    (is (= :forbidden (:code (support/error-data #(workspaces/team workspaces member-token workspace-id)))))
    (is (= :forbidden (:code (support/error-data #(workspaces/team workspaces other-token workspace-id)))))
    (swap! state assoc-in [:memberships workspace-id (:user-id owner) :status] :suspended)
    (is (= [other-workspace-id] (mapv :workspace-id (workspaces/list-memberships workspaces owner-token))))
    (is (= :forbidden (:code (support/error-data #(workspaces/team workspaces owner-token workspace-id)))))))


(deftest fixture-refresh-rotation-and-logout-invalidate-server-sessions
  (let [{:keys [auth users]} (identity/fixtures)
        original             (auth/sign-in! auth (first users))
        refreshed            (auth/refresh! auth (:refresh-token original))]
    (is (not= (:access-token original) (:access-token refreshed)))
    (is (= :invalid-refresh-token (:code (support/error-data #(auth/refresh! auth (:refresh-token original))))))
    (is (= (:user original) (auth/current-user auth (:access-token refreshed))))
    (auth/sign-out! auth (:access-token refreshed))
    (is (= :unauthenticated (:code (support/error-data #(auth/current-user auth (:access-token refreshed))))))
    (is (= :invalid-refresh-token (:code (support/error-data #(auth/refresh! auth (:refresh-token refreshed))))))))


(deftest fixture-signup-verification-and-password-setup-work-in-fresh-browser
  (let [{:keys [auth workspaces state]} (identity/fixtures)
        input {:email "New@Example.test" :password "first-password" :workspace-name "New workspace"}
        signup (auth/sign-up! auth input)]
    (is (false? (get-in signup [:user :email-confirmed?])))
    (is (= :email-not-confirmed (:code (support/error-data #(auth/sign-in! auth input)))))
    (let [email    (last (:emails @state))
          verified (auth/verify-email! auth (select-keys email [:type :token-hash]))]
      (is (true? (get-in verified [:user :email-confirmed?])))
      (is (= "New workspace" (:name (first (workspaces/list-memberships workspaces (:access-token verified))))))
      (auth/set-password! auth (:access-token verified) "new-password")
      (is (= (:user verified) (:user (auth/sign-in! auth (assoc input :password "new-password")))))
      (is (= :verification-expired (:code (support/error-data #(auth/verify-email! auth (select-keys email [:type :token-hash])))))))))


(deftest fixture-invitations-use-verified-email-and-stored-recovery-address
  (let [{:keys [auth workspaces users workspace-id state]} (identity/fixtures)
        owner-token (:access-token (auth/sign-in! auth (first users)))
        reserved (workspaces/manage-invitation! workspaces owner-token
                                                {:workspace-id workspace-id           :action :create
                                                 :email        "Invitee@Example.test" :role   :member})]
    (auth/invite! auth {:email (:email reserved)})
    (let [email    (last (:emails @state))
          verified (auth/verify-email! auth (select-keys email [:type :token-hash]))
          token    (:access-token verified)]
      (is (= 1 (workspaces/accept-invites! workspaces token)))
      (is (= [workspace-id] (mapv :workspace-id (workspaces/list-memberships workspaces token))))
      (is (= {:email "invitee@example.test"}
             (workspaces/request-member-recovery! workspaces owner-token workspace-id (get-in verified [:user :user-id]))))
      (is (= :rate-limited (:code (support/error-data #(workspaces/request-member-recovery! workspaces owner-token workspace-id (get-in verified [:user :user-id]))))))
      (is (= :accepted (get-in @state [:invitations (:id reserved) :status]))))))


(deftest definitive-failure-revokes-only-the-new-reservation
  (let [{:keys [auth workspaces users workspace-id state]} (identity/fixtures)
        token (:access-token (auth/sign-in! auth (first users)))
        original (workspaces/manage-invitation! workspaces token {:workspace-id workspace-id :action :create :email "first@example.test"})
        second (workspaces/manage-invitation! workspaces token {:workspace-id workspace-id :action :create :email "second@example.test"})]
    (workspaces/revoke-reservation! workspaces token workspace-id (:id second))
    (is (= :pending (get-in @state [:invitations (:id original) :status])))
    (is (= :revoked (get-in @state [:invitations (:id second) :status])))))

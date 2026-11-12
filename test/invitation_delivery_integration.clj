(ns invitation-delivery-integration
  "Run only through scripts/test-bel-workspace.sh against its newly created cluster."
  (:require
    [charred.api :as json]
    [clojure.test :refer [deftest is]]
    [next.jdbc :as jdbc]
    [workspace-creation-integration :as workspace]))


(defn sql-state
  {:malli/schema [:=> [:cat :string :string] [:or :int :string :map :nil]]}
  [role sql]
  (with-open [connection (jdbc/get-connection (workspace/db role))]
    (try (jdbc/execute-one! connection [sql])
         (catch java.sql.SQLException error (.getSQLState error)))))


(defn member!
  "A user with the given email and an active workspace membership; returns {:user :workspace}."
  {:malli/schema [:=> [:cat :string] :map]}
  [email]
  (let [user (random-uuid) workspace-id (random-uuid)]
    (workspace/admin! ["insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values(?,?,now(),?::jsonb)"
                       user email (json/write-json-str {:name "Ann"})])
    (workspace/admin! ["insert into public.organizations(id,name,created_by) values(?,?,?)" workspace-id "Shop" user])
    (workspace/admin! ["insert into public.organization_memberships(organization_id,user_id,role_id,status) values(?,?,'owner','active')"
                       workspace-id user])
    {:user user :workspace workspace-id}))


(defn session!
  "A live session row for the user; returns its key."
  {:malli/schema [:=> [:cat :uuid] :string]}
  [user]
  (let [key (str "test-session-" (random-uuid))]
    (workspace/admin! ["insert into app_private.sessions(id,user_id,data) values(?,?,?)" key user (pr-str {})])
    key))


(defn recipient-status
  "Runs the lookup as tover_app; `as` is the acting user (nil for no claim)."
  {:malli/schema [:=> [:cat [:maybe :uuid] :uuid :string] [:maybe :string]]}
  [as workspace-id email]
  (with-open [connection (jdbc/get-connection (workspace/db "tover_app"))]
    (when as
      (jdbc/execute-one! connection ["select set_config('request.jwt.claim.sub', ?, false), set_config('tover.session_key', ?, false)"
                                     (str as) (session! as)]))
    (:status (jdbc/execute-one! connection ["select app_private.bel_recipient_membership(?, ?) as status" workspace-id email]
                                {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))))


(deftest migration-038-applies-twice
  (is (= 1 (:n (workspace/admin! ["select count(*) as n from pg_class where relname='invitation_deliveries'"]))))
  (is (= 1 (:n (workspace/admin! ["select count(*) as n from pg_policies where policyname='bel_deliveries_delivery_update'"])))))


(deftest journal-role-cannot-read-auth-users
  (is (= "42501" (sql-state "tover_journal" "select 1 from auth.users")))
  (is (= "42501" (sql-state "tover_journal" "select 1 from app_private.sessions"))))


(deftest recipient-membership-matches-normalized-email
  (let [{:keys [user workspace]} (member! "ann@example.test")]
    (is (= "active" (recipient-status user workspace " ANN@example.test ")))
    (is (nil? (recipient-status user workspace "bob@example.test")))))


(deftest recipient-membership-is-not-an-oracle
  (let [{:keys [workspace]} (member! "ann@example.test")
        outsider            (:user (member! "eve@example.test"))]
    (is (nil? (recipient-status outsider workspace "ann@example.test")))
    (is (nil? (recipient-status nil workspace "ann@example.test")))))


(deftest delivery-cannot-reference-another-workspaces-invitation
  (let [a      (member! "a@example.test")
        b      (member! "b@example.test")
        invite (random-uuid)
        insert (fn [organization-id invitation-id]
                 (with-open [connection (jdbc/get-connection (workspace/db "tover_app"))]
                   (jdbc/execute-one! connection ["select set_config('request.jwt.claim.sub', ?, false), set_config('tover.session_key', ?, false)"
                                                  (str (:user a)) (session! (:user a))])
                   (try (jdbc/execute-one! connection ["insert into public.invitation_deliveries(id,invitation_id,organization_id,requested_by,requested_at,status) values(gen_random_uuid(),?,?,?,now(),'queued')"
                                                       invitation-id organization-id (:user a)])
                        :inserted
                        (catch java.sql.SQLException error (.getSQLState error)))))]
    (workspace/admin! ["insert into public.organization_invites(id,organization_id,email,role_id,invited_by) values(?,?,'x@example.test','member',?)"
                       invite (:workspace b) (:user b)])
    (is (= "42501" (insert (:workspace a) invite)))
    (let [own (random-uuid)]
      (workspace/admin! ["insert into public.organization_invites(id,organization_id,email,role_id,invited_by) values(?,?,'y@example.test','member',?)"
                         own (:workspace a) (:user a)])
      (is (= :inserted (insert (:workspace a) own))))))


(deftest recipient-language-is-null-for-unknown-email
  (with-open [connection (jdbc/get-connection (workspace/db "tover_journal"))]
    (is (nil? (:language (jdbc/execute-one! connection ["select app_private.bel_recipient_language('nobody@example.test') as language"]
                                            {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))))))


(deftest app-role-cannot-update-delivery-status
  (is (= "42501" (sql-state "tover_app" "update public.invitation_deliveries set status='stopped'"))))

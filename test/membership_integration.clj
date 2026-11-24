(ns membership-integration
  "Board chapters 34–39 against Postgres: the membership row policies are the floor under the commands, and the
  active-Owner lock keeps two concurrent departures from leaving a workspace without an Owner.
  Run only through scripts/test-bel-workspace.sh against its newly created cluster."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [tover.memberships.departure.model :as departure]
    [tover.memberships.role.model :as role]
    [tover.memberships.support :as memberships]
    [tover.support :as support]
    [workspace-creation-integration :as workspace]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! memberships/model-namespaces)
  (f))


(use-fixtures :each fixture/isolated-registry register)


(defn team!
  "A workspace with one person per role; returns {:workspace id, role {:user :session-key :membership-id}}."
  {:malli/schema [:=> [:cat [:sequential :string]] :map]}
  [roles]
  (let [workspace-id (random-uuid)
        people       (mapv (fn [_] (workspace/context)) roles)]
    (workspace/admin! ["insert into public.workspaces(workspace_id,name,created_by) values(?,?,?)"
                       workspace-id "Shop" (:user-id (first people))])
    (into {:workspace workspace-id}
          (map (fn [role {:keys [user-id] :as ctx}]
                 [(keyword role)
                  (assoc ctx
                         :workspace-id workspace-id
                         :membership-id (:membership_id (workspace/admin! ["insert into public.memberships(workspace_id,user_id,role,status) values(?,?,?,'active') returning membership_id"
                                                                           workspace-id user-id role])))])
               roles people))))


(defn claim!
  {:malli/schema [:=> [:cat :any :map] :any]}
  [connection {:keys [user-id auth-session-id]}]
  (jdbc/execute-one! connection ["select set_config('request.jwt.claim.sub', ?, false), set_config('tover.session_key', ?, false)"
                                 (str user-id) auth-session-id]))


(defn as-app
  "Runs sql as tover_app for the person; returns rows affected or the SQL state of the failure."
  {:malli/schema [:=> [:cat :map :string [:* :any]] [:or :int :string]]}
  [person sql & params]
  (with-open [connection (jdbc/get-connection (workspace/db "tover_app"))]
    (claim! connection person)
    (try (:next.jdbc/update-count (jdbc/execute-one! connection (into [sql] params)))
         (catch java.sql.SQLException error (.getSQLState error)))))


(defn row
  {:malli/schema [:=> [:cat :uuid] [:maybe :map]]}
  [membership-id]
  (workspace/admin! ["select role, status, revision, removal_kind from public.memberships where membership_id=?" membership-id]))


(deftest admin-cannot-change-an-owner-row
  (let [{:keys [owner admin member]} (team! ["owner" "admin" "member"])]
    (is (= 0 (as-app admin "update public.memberships set role='admin' where membership_id=?" (:membership-id owner))))
    (is (= "owner" (:role (row (:membership-id owner)))))
    (is (= "42501" (as-app admin "update public.memberships set role='owner' where membership_id=?" (:membership-id member))))
    (is (= "member" (:role (row (:membership-id member)))))
    (is (= 1 (as-app admin "update public.memberships set status='suspended', revision=2 where membership_id=?" (:membership-id member))))))


(deftest member-cannot-change-other-rows
  (let [{:keys [admin member]} (team! ["owner" "admin" "member"])]
    (is (= 0 (as-app member "update public.memberships set status='suspended' where membership_id=?" (:membership-id admin))))
    (is (= "active" (:status (row (:membership-id admin)))))))


(deftest leaving-only-ends-the-own-row
  (let [{:keys [member]} (team! ["owner" "member"])
        id               (:membership-id member)]
    (is (= "42501" (as-app member "update public.memberships set role='owner' where membership_id=?" id)))
    (is (= "42501" (as-app member "update public.memberships set status='removed', removal_kind='removed' where membership_id=?" id)))
    (is (= 1 (as-app member "update public.memberships set status='removed', removal_kind='left', removed_at=now(), revision=2 where membership_id=?" id)))
    (is (= {:role "member" :status "removed" :revision 2 :removal_kind "left"} (row id)))))


(deftest concurrent-owner-departure-keeps-an-owner
  (let [{:keys [workspace] a :owner b :admin} (team! ["owner" "admin"])
        _ (workspace/admin! ["update public.memberships set role='owner' where membership_id=?" (:membership-id b)])
        owners (fn [connection] (mapv :owner (jdbc/execute! connection ["select app_private.bel_active_owners(?) as owner" workspace]
                                                            {:builder-fn rs/as-unqualified-lower-maps})))]
    (with-open [one (jdbc/get-connection (workspace/db "tover_app"))
                two (jdbc/get-connection (workspace/db "tover_app"))]
      (doseq [[connection person] [[one a] [two b]]]
        (.setTransactionIsolation connection java.sql.Connection/TRANSACTION_REPEATABLE_READ)
        (.setAutoCommit connection false)
        (claim! connection person))
      ;; Both snapshots predate the first departure.
      (is (= 2 (count (owners one))))
      (jdbc/execute-one! one ["update public.memberships set status='removed', removal_kind='left', removed_at=now(), revision=revision+1 where membership_id=?"
                              (:membership-id a)])
      (.commit one)
      (is (= "40001" (try (owners two) nil (catch java.sql.SQLException error (.getSQLState error)))))
      (.rollback two))
    (is (= "active" (:status (row (:membership-id b)))))))


(deftest role-change-and-departure-run-through-the-store
  (let [{:keys [owner member]} (team! ["owner" "member"])
        system                 (support/bel-system {:datasource (workspace/db "tover_app")})
        as (fn [person] (assoc person :bel/system system))]
    (runtime/execute! (as owner) ::role/change-workspace-member-role
                      {:command-id (str (random-uuid))
                       :input      {:membership-id (:membership-id member) :expected-revision 1 :role "admin"}})
    (is (= {:role "admin" :status "active" :revision 2 :removal_kind nil} (row (:membership-id member))))
    (is (= :last-owner (:type (workspace/error-data
                                #(runtime/execute! (as owner) ::departure/leave-workspace
                                                   {:command-id (str (random-uuid)) :input {:expected-revision 1}})))))
    (runtime/execute! (as member) ::departure/leave-workspace
                      {:command-id (str (random-uuid)) :input {:expected-revision 2 :reason "  Moving on "}})
    (is (= {:role "admin" :status "removed" :revision 3 :removal_kind "left"} (row (:membership-id member))))))

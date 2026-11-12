(ns invitation-delivery-integration
  "Run only through scripts/test-bel-workspace.sh against its newly created cluster."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [charred.api :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [next.jdbc :as jdbc]
    [tover.invitations.delivery.model]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.status.model :as status]
    [tover.shared.bel :as wiring]
    [tover.shared.email :as mailer]
    [workspace-creation-integration :as workspace]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.accounts.profile.model 'tover.workspaces.create.model 'tover.invitations.issue.model
                        'tover.invitations.delivery.model
                        'tover.invitations.status.model])
  (f))


(use-fixtures :each fixture/isolated-registry register)


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


(defn owner-context
  "An owner session in a freshly created workspace, under the workspace-scoped system on the runtime role."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [ctx (workspace/context) id (random-uuid)]
    (workspace/execute ctx {:command-id "C" :input {:workspace-id id :name "Shop"}})
    (assoc ctx :workspace-id id :bel/system (wiring/system {:datasource (workspace/db "tover_app")}))))


(defn invite-counts
  {:malli/schema [:=> [:cat :uuid] [:vector :int]]}
  [workspace-id]
  (mapv (fn [table] (:n (workspace/admin! [(str "select count(*) as n from public." table " where organization_id=?") workspace-id])))
        ["organization_invites" "invitation_deliveries"]))


(deftest invite-teammate-commits-offer-and-delivery
  (let [ctx     (owner-context)
        request {:command-id "invite-1" :input {:email " Ann@Example.test "}}
        receipt (runtime/execute! ctx ::issue/invite-teammate request)
        invite  (workspace/admin! ["select email, role_id, status, invited_by, expires_at - created_at as lifetime from public.organization_invites where organization_id=?"
                                   (:workspace-id ctx)])
        pending (workspace/admin! ["select status, attempts, requested_by from public.invitation_deliveries where organization_id=?"
                                   (:workspace-id ctx)])]
    (is (= :accepted (:status receipt)))
    (is (= 1 (count (:event-ids receipt))) "one event feeds both tables")
    (is (= [1 1] (invite-counts (:workspace-id ctx))))
    (is (= {:email "ann@example.test" :role_id "member" :status "pending" :invited_by (:user-id ctx)}
           (select-keys invite [:email :role_id :status :invited_by])))
    (is (= "queued" (:status pending)))
    (is (= 0 (:attempts pending)))
    (is (= (:user-id ctx) (:requested_by pending)))
    (is (= receipt (runtime/execute! ctx ::issue/invite-teammate request)))
    (is (= [1 1] (invite-counts (:workspace-id ctx))))
    (runtime/execute! ctx ::issue/invite-teammate {:command-id "invite-2" :input {:email "ANN@example.test"}})
    (is (= [1 1] (invite-counts (:workspace-id ctx))))))


(deftest member-cannot-issue-invitations
  (let [owner  (owner-context)
        member (workspace/context)]
    (workspace/admin! ["insert into public.organization_memberships(organization_id,user_id,role_id,status) values(?,?,'member','active')"
                       (:workspace-id owner) (:user-id member)])
    (is (= :forbidden (:type (workspace/error-data
                               #(runtime/execute! (assoc member :workspace-id (:workspace-id owner) :bel/system (:bel/system owner))
                                                  ::issue/invite-teammate {:command-id "m-1" :input {:email "x@example.test"}})))))
    (is (= [0 0] (invite-counts (:workspace-id owner))))))


(deftest runtime-role-cannot-journal-for-a-foreign-workspace
  (let [{:keys [user]}      (member! "a@example.test")
        {:keys [workspace]} (member! "b@example.test")]
    (with-open [connection (jdbc/get-connection (workspace/db "tover_app"))]
      (jdbc/execute-one! connection ["select set_config('request.jwt.claim.sub', ?, false)" (str user)])
      (is (= "42501" (try (jdbc/execute-one! connection ["insert into app_private.bel_events(id,scope_kind,scope_id,actor_id,command_id,event_type,occurred_at,data) values(gen_random_uuid(),'workspace',?,?,'x','t',now(),'{}'::jsonb)"
                                                         workspace user])
                          :inserted
                          (catch java.sql.SQLException error (.getSQLState error))))))))


(defn worker-system
  "The delivery worker on the journal login, with `mailer` as its effect resource."
  {:malli/schema [:=> [:cat [:fn fn?]] :map]}
  [mailer]
  (wiring/delivery-system {:datasource (workspace/db "tover_journal") :mailer mailer :origin "https://app.test"}))


(defn invited!
  "An owner context whose workspace has one issued invitation to `email`."
  {:malli/schema [:=> [:cat :string] :map]}
  [email]
  (let [ctx (owner-context)]
    (runtime/execute! ctx ::issue/invite-teammate {:command-id "invite-1" :input {:email email}})
    ctx))


(defn delivery-row
  {:malli/schema [:=> [:cat :uuid] [:maybe :map]]}
  [workspace-id]
  (workspace/admin! ["select status, attempts, last_problem, next_attempt_at, submitted_at,
                             extract(epoch from next_attempt_at - attempted_at) as wait
                      from public.invitation_deliveries where organization_id=?" workspace-id]))


(defn await-row
  "Effects and their outcomes run after commit on another thread; wait up to 5 s for `done?`."
  {:malli/schema [:=> [:cat :uuid [:fn fn?]] [:maybe :map]]}
  [workspace-id done?]
  (loop [n 0]
    (let [row (delivery-row workspace-id)]
      (if (or (done? row) (= n 50)) row (do (Thread/sleep 100) (recur (inc n)))))))


(defn recorder
  "A mailer that records messages to `email` and answers with `outcome` (a map, or a thrown ex-info)."
  {:malli/schema [:=> [:cat :any :string [:or :map [:fn #(instance? Throwable %)]]] [:fn fn?]]}
  [sent email outcome]
  (fn [message]
    (when (= email (:to message)) (swap! sent conj message))
    (if (map? outcome) outcome (throw outcome))))


(deftest issued-invitation-is-submitted
  (let [email (str "sub-" (random-uuid) "@example.test")
        sent  (atom [])
        ctx   (invited! (str " " (str/upper-case email) " "))]
    (runtime/run-processors! (worker-system (recorder sent email {})))
    (let [row (await-row (:workspace-id ctx) #(= "submitted" (:status %)))]
      (is (= {:status "submitted" :attempts 1} (select-keys row [:status :attempts])))
      (is (some? (:submitted_at row)))
      (is (nil? (:next_attempt_at row)) "the attempt clears the due time"))
    (is (= 1 (count @sent)))
    (is (str/includes? (:text (first @sent)) "https://app.test/invitations/"))
    (testing "a second pass does not resend"
      (runtime/run-processors! (worker-system (recorder sent email {})))
      (Thread/sleep 300)
      (is (= 1 (count @sent)))
      (is (= 1 (:attempts (delivery-row (:workspace-id ctx))))))))


(deftest temporary-failure-schedules-retry
  (let [email (str "tmp-" (random-uuid) "@example.test")
        sent  (atom [])
        ctx   (invited! email)]
    (runtime/run-processors! (worker-system (recorder sent email (ex-info "busy" {:type :temporary}))))
    (let [row (await-row (:workspace-id ctx) #(= "retrying" (:status %)))]
      (is (= {:status "retrying" :attempts 1 :last_problem "temporary"} (select-keys row [:status :attempts :last_problem])))
      (is (<= 60 (double (:wait row)) 70) "first retry waits one minute"))
    (testing "the retry is not due yet"
      (runtime/run-processors! (worker-system (recorder sent email {})))
      (Thread/sleep 300)
      (is (= 1 (count @sent)))
      (is (= "retrying" (:status (delivery-row (:workspace-id ctx))))))))


(defn mailpit-messages
  "Messages the disposable Mailpit holds for `email`."
  {:malli/schema [:=> [:cat :string] [:sequential :map]]}
  [email]
  (:messages (json/read-json (slurp (str (System/getenv "TOVER_BEL_TEST_MAILPIT_URL") "/api/v1/search?query="
                                         (java.net.URLEncoder/encode (str "to:" email) "UTF-8")))
                             :key-fn keyword)))


(deftest smtp-mailer-reaches-mailpit
  (let [email  (str "smtp-" (random-uuid) "@example.test")
        mailer (mailer/smtp-mailer {:host "127.0.0.1"          :port (parse-long (System/getenv "TOVER_BEL_TEST_SMTP_PORT"))
                                    :from "tover@example.test"})
        ctx    (invited! email)]
    (runtime/run-processors! (worker-system mailer))
    (is (= "submitted" (:status (await-row (:workspace-id ctx) #(= "submitted" (:status %))))))
    (let [messages (mailpit-messages email)]
      (is (= 1 (count messages)))
      (is (str/includes? (:Subject (first messages)) "Shop")))))


(deftest status-reads-latest-offer-and-delivery
  (let [email (str "status-" (random-uuid) "@example.test")
        ctx   (invited! email)
        read  #(runtime/read-model ctx ::status/invitation-status {:email %})]
    (is (= "queued" (get-in (read email) [:delivery :status])))
    (runtime/run-processors! (worker-system (recorder (atom []) email (ex-info "busy" {:type :temporary}))))
    (await-row (:workspace-id ctx) #(= "retrying" (:status %)))
    (let [status (read (str " " (str/upper-case email) " "))]
      (is (= {:email email :role "member" :status "pending"} (select-keys status [:email :role :status])))
      (is (= {:status "failed" :attempts-remaining 3} (select-keys (:delivery status) [:status :attempts-remaining])))
      (is (some? (get-in status [:delivery :next-attempt-at]))))
    (is (nil? (read "nobody@example.test")))))

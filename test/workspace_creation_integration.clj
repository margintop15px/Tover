(ns workspace-creation-integration
  "Run only through scripts/test-bel-workspace.sh against its newly created cluster."
  (:require
    [bel-postgres.adapter :as pg]
    [bel-postgres.journal :as journal-store]
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.handler :as handler]
    [bel.journal :as journal]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [charred.api :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :as test :refer [deftest is use-fixtures]]
    [instrumentation :as instrumentation]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as datastar]
    [taoensso.nippy :as nippy]
    [tover.accounts.profile.model :as profile-model]
    [tover.identity.session :as session]
    [tover.shared.bel :as wiring]
    [tover.workspaces.create.model :as model]
    [tover.workspaces.create.web :as web])
  (:import
    [java.nio.file Files]
    [java.time Instant]
    [java.util.concurrent CountDownLatch TimeUnit]))


(defn db [role]
  (jdbc/get-datasource {:jdbcUrl (System/getenv "TOVER_BEL_TEST_URL") :user role}))


(defn admin! [statement]
  (jdbc/execute-one! (db "postgres") statement {:builder-fn rs/as-unqualified-lower-maps}))


(defn error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn register [f]
  (bel/build-registry! ['tover.accounts.profile.model 'tover.workspaces.create.model 'tover.workspaces.create.web])
  (f))


(use-fixtures :each fixture/isolated-registry register)


(defn system
  "Directory reads in these checks are user-scoped."
  []
  (assoc (wiring/system {:datasource (db "tover_app")}) :scope :user))


(defn context
  ([] (context {}))
  ([{:keys [confirmed? name recovery?] :or {confirmed? true name "Alice"}}]
   (let [user-id     (random-uuid)
         session-id  (random-uuid)
         session-key (str "test-session-" (random-uuid))]
     (admin! ["insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values(?,?,case when ? then now() end,?::jsonb)"
              user-id (str user-id "@example.test") confirmed? (json/write-json-str {:name name})])
     (admin! ["insert into app_private.sessions(id,session_id,user_id,data) values(?,?,?,?)"
              session-key session-id user-id (pr-str {:identity/password-required? (boolean recovery?)})])
     {:bel/system (system) :user-id user-id :session-id session-id :auth-session-id session-key})))


(defn request []
  {:command-id (str (random-uuid)) :input {:workspace-id (random-uuid) :name "Workshop"}})


(defn execute [ctx req]
  (runtime/execute! ctx ::model/create-workspace req))


(defn current [ctx req]
  (runtime/read-model ctx ::model/created-workspace
                      {:command-id (:command-id req) :workspace-id (get-in req [:input :workspace-id])}))


(defn counts [ctx req]
  (let [id (get-in req [:input :workspace-id])]
    (mapv (fn [statement] (:n (admin! statement)))
          [["select count(*) as n from public.workspaces where workspace_id=?" id]
           ["select count(*) as n from public.memberships where workspace_id=?" id]
           ["select count(*) as n from app_private.bel_events where scope_id=? and command_id=?" (:user-id ctx) (:command-id req)]
           ["select count(*) as n from app_private.bel_receipts where scope_id=? and command_id=?" (:user-id ctx) (:command-id req)]])))


(deftest create-retry-normalization-and-native-domain-data
  (let [ctx     (context) req (request)
        receipt (execute ctx (-> req (assoc-in [:input :name] "  Workshop  ")
                                 (update-in [:input :workspace-id] (comp str/upper-case str))))]
    (is (= :accepted (:status receipt)))
    (is (= 2 (count (:event-ids receipt))))
    (is (every? uuid? (:event-ids receipt)))
    (is (= [1 1 2 1] (counts ctx req)))
    (is (= receipt (execute ctx req)))
    (is (= {:workspace-id (get-in req [:input :workspace-id]) :name "Workshop" :role "owner" :can-open? true}
           (current ctx req)))
    (is (= :idempotency-conflict (:type (error-data #(execute ctx (assoc-in req [:input :name] "Other"))))))
    (is (nil? (:workspace_id (admin! ["select workspace_id from app_private.sessions where id=?" (:auth-session-id ctx)]))))
    (let [workspace (runtime/read-model ctx ::model/workspaces (:input req))
          event     (admin! ["select id,actor_id,occurred_at,data from app_private.bel_events where scope_id=? and event_type=?"
                             (:user-id ctx) "tover.workspaces.create.model/workspace-created"])]
      (is (= "Workshop" (:name workspace)))
      (is (= (get-in req [:input :workspace-id]) (:workspace-id workspace)))
      (is (= (:user-id ctx) (:created-by workspace) (:actor_id event)))
      (is (instance? Instant (:created-at workspace)))
      (is (= (:created-at workspace) (:occurred_at event)))
      (is (uuid? (:id event)))
      ;; JSON has strings; UUID columns, model reads and receipts retain native values.
      (is (= (str (:workspace-id workspace)) (get-in event [:data :workspace-id]))))
    (let [row    (admin! ["select payload from app_private.bel_receipts where scope_id=? and command_id=?"
                          (:user-id ctx) (:command-id req)])
          stored (nippy/thaw (:payload row))]
      (is (= receipt (get-in stored [:result :receipt])))
      (is (= (:user-id ctx) (get-in stored [:request :actor])))
      (is (= (:input req) (get-in stored [:request :input])))
      (is (not (str/includes? (pr-str stored) (:auth-session-id ctx))))
      (is (= {:user-id (:user-id ctx)} (get-in stored [:request :scope]))))))


(deftest rollback-at-every-persistence-stage
  (doseq [[stage target] [[#'journal-store/append-event! nil]
                          [#'pg/insert-model! ::model/workspaces]
                          [#'pg/insert-model! ::model/memberships]
                          [#'journal-store/save-receipt! nil]]]
    (let [ctx (context) req (request) original @stage touched (atom false)]
      (with-redefs-fn {stage (fn [& args]
                               (let [result (apply original args)]
                                 (when (or (nil? target) (= target (:model-id (second args))))
                                   (reset! touched true)
                                   (throw (ex-info "Injected failure" {:type :injected})))
                                 result))}
        #(is (= :injected (:type (error-data (fn [] (execute ctx req)))))))
      (is @touched)
      (is (= [0 0 0 0] (counts ctx req)))
      (is (= :accepted (:status (execute ctx req)))))))


(deftest user-receipts-survive-another-session-without-changing-its-selection
  (let [ctx       (context)
        req       (request)
        receipt   (execute ctx req)
        other-id  (random-uuid)
        other-key (str "test-session-" (random-uuid))
        other     (assoc ctx :session-id other-id :auth-session-id other-key :workspace-id (random-uuid))]
    (admin! ["insert into app_private.sessions(id,session_id,user_id,data) values(?,?,?,'{}')"
             other-key other-id (:user-id ctx)])
    (is (= receipt (execute other req)))
    (is (= (current ctx req) (current other req)))
    (is (= [1 1 2 1] (counts ctx req)))))


(deftest names-and-identities-validate-before-any-write
  (let [ctx (context)]
    (doseq [input [{:workspace-id (str (random-uuid)) :name "  "}
                   {:workspace-id (str (random-uuid)) :name (apply str (repeat 201 "x"))}
                   {:workspace-id "not-a-uuid" :name "Workshop"}]]
      (is (= :validation (:type (error-data #(execute ctx {:command-id "invalid" :input input}))))))
    (let [first-request  (request)
          second-request (request)]
      (is (= :accepted (:status (execute ctx first-request))))
      (is (= :accepted (:status (execute ctx second-request))))
      (is (not= (get-in first-request [:input :workspace-id]) (get-in second-request [:input :workspace-id]))))))


(deftest malformed-input-is-a-validation-response
  (let [ctx    (context)                    req (request)
        handle (handler/action-handler ctx)]
    (doseq [[field value] [[:name nil] [:name 42] [:name ["Workshop"]]
                           [:workspace-id nil] [:workspace-id 42] [:workspace-id []]]]
      (let [input (assoc (:input req) :command-id (:command-id req) field value)]
        (is (= 422 (:status (handle {:request-method    :post :body (json/write-json-str input)
                                     :headers           {"content-type" "application/json"}
                                     :reitit.core/match {:result {:post {:data {:bel/action ::web/create}}}}}))))))
    (is (= [0 0 0 0] (counts ctx req)))))


(deftest a-unique-conflict-can-recover-a-matching-acceptance
  (let [ctx          (context)       req         (request)   accepted (execute ctx req)
        read-receipt journal/receipt first-read? (atom true)]
    ;; Reproduce the missed receipt followed by a real uniqueness failure.
    (with-redefs [journal/receipt (fn [context command-id]
                                    (when-not (compare-and-set! first-read? true false)
                                      (read-receipt context command-id)))]
      (is (= accepted (execute ctx req))))
    (is (= [1 1 2 1] (counts ctx req)))))


(deftest overlapping-retries-have-one-acceptance
  (let [ctx     (context) req (request) gate (CountDownLatch. 2) decide model/create-workspace
        results (with-redefs [model/create-workspace
                              (fn [facts input]
                                (.countDown gate)
                                (when-not (.await gate 10 TimeUnit/SECONDS)
                                  (throw (ex-info "Decisions did not overlap" {:type :test-timeout})))
                                (decide facts input))]
                  (let [jobs (mapv (fn [_] (future (try (execute ctx req) (catch Exception e e)))) (range 2))]
                    (try (mapv #(deref % 15000 ::timeout) jobs)
                         (finally (doseq [job jobs] (future-cancel job))))))
        receipt (execute ctx req)]
    (is (some #{receipt} results))
    (is (not-any? #{::timeout} results))
    (doseq [result results :when (instance? Exception result)]
      (is (= {:type :conflict :retryable? true :command-id (:command-id req)} (ex-data result))))
    (is (= [1 1 2 1] (counts ctx req)))))


(deftest isolation-and-no-overwrite-of-existing-workspaces
  (let [alice   (context)           bob (context) req (request)
        receipt (execute alice req)]
    (is (= :creation-unconfirmed (:type (error-data #(current bob req)))))
    (is (nil? (runtime/read-model bob ::model/workspaces (:input req))))
    (doseq [_ (range 2)]
      (let [problem (error-data #(execute bob (assoc-in req [:input :name] "Stolen")))]
        (is (= :identity-conflict (:type problem)))
        (is (not (:retryable? problem)))))
    (is (= [1 1 0 0] (counts bob req)))
    (is (= "Workshop" (:name (current alice req))))
    (is (= receipt (execute alice req)))
    (is (= :identity-conflict (:type (error-data #(execute alice (assoc req :command-id "different-attempt"))))))))


(deftest current-identity-profile-and-session-are-required
  (doseq [[options expected] [[{:confirmed? false} :forbidden]
                              [{:recovery? true} :forbidden]
                              [{:name "  "} :profile-incomplete]
                              [{:name nil} :profile-incomplete]]]
    (let [ctx (assoc (context options) :confirmed? true :password-required? false) req (request)]
      (is (= expected (:type (error-data #(execute ctx req)))))
      (is (= [0 0 0 0] (counts ctx req)))))
  (let [ctx (context) req (request)]
    (is (= :forbidden (:type (error-data #(execute (assoc ctx :session-id (random-uuid)) req)))))
    (admin! ["update app_private.sessions set expires_at=now()-interval '1 minute' where id=?" (:auth-session-id ctx)])
    (is (= :forbidden (:type (error-data #(execute ctx req)))))
    (is (= [0 0 0 0] (counts ctx req)))))


(deftest retries-do-not-restore-access-and-user-scope-ignores-selection
  (let [ctx          (context) req (request) receipt (execute ctx req)
        workspace-id (get-in req [:input :workspace-id])]
    (admin! ["update public.memberships set status='suspended' where workspace_id=?" workspace-id])
    (admin! ["update public.profiles set display_name=null where user_id=?" (:user-id ctx)])
    (is (= receipt (execute (assoc ctx :workspace-id (random-uuid)) req)))
    (is (false? (:can-open? (current ctx req))))
    (is (not (contains? (current ctx req) :role)))
    (is (nil? (runtime/read-model ctx ::model/workspaces {:workspace-id (str workspace-id)})))
    (is (= [1 1 2 1] (counts ctx req)))
    (admin! ["update app_private.sessions set user_id=? where id=?" (:user-id (context)) (:auth-session-id ctx)])
    (is (= :forbidden (:type (error-data #(execute ctx req)))))))


(deftest acceptance-waits-for-profile-change-and-sees-its-result
  (let [ctx (context) req (request)]
    (with-open [connection (jdbc/get-connection (db "postgres"))]
      (.setAutoCommit connection false)
      (jdbc/execute-one! connection ["update public.profiles set display_name=null where user_id=?" (:user-id ctx)])
      (let [job (future (error-data #(execute ctx req)))]
        (try
          (is (= ::waiting (deref job 200 ::waiting)))
          (.commit connection)
          (is (contains? #{:profile-incomplete :conflict} (:type (deref job 10000 {}))))
          (is (= :profile-incomplete (:type (error-data #(execute ctx req)))))
          (is (= [0 0 0 0] (counts ctx req)))
          (finally (.rollback connection) (future-cancel job)))))))


(deftest session-revocation-before-acceptance-prevents-writes
  (let [ctx (context) req (request)]
    (with-open [connection (jdbc/get-connection (db "postgres"))]
      (.setAutoCommit connection false)
      (jdbc/execute-one! connection ["update app_private.sessions set expires_at=now()-interval '1 minute' where id=?"
                                     (:auth-session-id ctx)])
      (let [started (promise)
            job     (future (deliver started true) (error-data #(execute ctx req)))]
        (try
          (is (= true (deref started 10000 ::timeout)))
          (is (= ::waiting (deref job 200 ::waiting)))
          (.commit connection)
          (is (contains? #{:forbidden :conflict} (:type (deref job 10000 {}))))
          (is (= :forbidden (:type (error-data #(execute ctx req)))))
          (is (= [0 0 0 0] (counts ctx req)))
          (finally (.rollback connection) (future-cancel job)))))))


(deftest acceptance-holds-session-authority-through-commit
  (let [ctx (context) req (request) save journal-store/save-receipt! staged (promise) resume (promise)]
    (with-redefs [journal-store/save-receipt!
                  (fn [context invocation result]
                    (let [inserted? (save context invocation result)]
                      (deliver staged true)
                      (assert (= true (deref resume 10000 ::timeout)))
                      inserted?))]
      (let [acceptance (future (execute ctx req))]
        (try
          (is (= true (deref staged 10000 ::timeout)))
          (let [started    (promise)
                revocation (future
                             (deliver started true)
                             (admin! ["update app_private.sessions set expires_at=now()-interval '1 minute' where id=?"
                                      (:auth-session-id ctx)]))]
            (try
              (is (= true (deref started 10000 ::timeout)))
              (is (= ::waiting (deref revocation 200 ::waiting)))
              (deliver resume true)
              (is (= :accepted (:status (deref acceptance 10000 {}))))
              (is (not= ::timeout (deref revocation 10000 ::timeout)))
              (finally (deliver resume true) (future-cancel revocation))))
          (finally (deliver resume true) (future-cancel acceptance)))))
    (is (= :forbidden (:type (error-data #(execute ctx req)))))
    (is (= [1 1 2 1] (counts ctx req)))))


(deftest uncommitted-work-is-invisible
  (let [ctx (context) req (request) save journal-store/save-receipt! staged (promise) resume (promise)]
    (with-redefs [journal-store/save-receipt!
                  (fn [context invocation result]
                    (let [inserted? (save context invocation result)]
                      (deliver staged true)
                      (assert (= true (deref resume 10000 ::timeout)))
                      inserted?))]
      (let [job (future (execute ctx req))]
        (try
          (is (= true (deref staged 10000 ::timeout)))
          (is (= [0 0 0 0] (counts ctx req)))
          (deliver resume true)
          (is (= :accepted (:status (deref job 10000 {}))))
          (finally (deliver resume true) (future-cancel job)))))
    (is (= [1 1 2 1] (counts ctx req)))))


(deftest plain-action-ring-view-and-render-recovery
  (let [ctx (context) req (request) input (assoc (:input req) :command-id (:command-id req)) events (atom [])]
    (with-redefs [runtime/execute! (fn [& _] (throw (Exception. "Action must be pure")))]
      (is (= ::model/create-workspace (:id (web/create {} input)))))
    (with-redefs [hk/->sse-response      (fn [_ options] ((get options hk/on-open) nil) {:status 200})
                  handler/send-prepared! (fn [_ result] (swap! events into result))
                  datastar/close-sse!    (fn [_])]
      (is (= 200 (:status ((handler/action-handler ctx)
                           {:request-method    :post :body (json/write-json-str input)
                            :headers           {"content-type" "application/json"}
                            :reitit.core/match {:result {:post {:data {:bel/action ::web/create}}}}})))))
    (is (some #(str/includes? (pr-str %) "Workspace created") @events))
    (let [receipt (execute ctx req) result (handler/execute-action ctx ::web/create input)]
      (with-redefs [web/ready (fn [_] (throw (ex-info "Rendering unavailable" {:type :injected})))]
        (is (= :injected (:type (error-data #(handler/prepare-result ctx result))))))
      (is (= receipt (execute ctx req)))
      (is (str/includes? (handler/render-html (:html (handler/render-view (assoc ctx :request {}) ::web/ready (:input result)))) "Workshop")))))


(deftest runtime-role-has-no-data-api-or-rewrite-capability
  (doseq [table ["app_private.bel_events" "app_private.bel_receipts"]]
    (is (false? (:allowed (admin! ["select has_table_privilege('authenticated',?,'SELECT') as allowed" table]))))
    (is (false? (:allowed (admin! ["select has_table_privilege('tover_app',?,'UPDATE') as allowed" table])))))
  (doseq [table ["public.workspaces" "public.memberships"]]
    (is (false? (:allowed (admin! ["select has_table_privilege('tover_app',?,'UPDATE') as allowed" table]))))
    (is (false? (:allowed (admin! ["select has_table_privilege('tover_app',?,'DELETE') as allowed" table])))))
  (is (false? (:allowed (admin! ["select has_table_privilege('tover_app','auth.users','SELECT') as allowed"])))))


(defn write!
  "Adapter semantics only: the runtime role deliberately has no UPDATE grant on directory tables."
  [model fields row]
  (let [directory (pg/store {:datasource (db "postgres")})]
    (store/-transact directory :serializable
                     #(store/-write! directory % {:kind :user :id nil} (get-in (bel/operations) [:models model]) fields row))))


(defn profile [user-id]
  (admin! ["select display_name, created_at from public.profiles where user_id=?" user-id]))


(deftest set-replaces-mapped-columns-of-one-row
  (let [alice (:user-id (context)) bob (:user-id (context {:name "Bob"})) before (profile alice)]
    (write! ::profile-model/profiles [:display-name] {:user-id alice :display-name "Alicia"})
    (is (= "Alicia" (:display_name (profile alice))))
    (is (= (:created_at before) (:created_at (profile alice))))
    (is (= "Bob" (:display_name (profile bob))))))


(deftest set-on-an-existing-incomplete-row-updates-only-mapped-columns
  (let [alice (context) req (request) id (get-in req [:input :workspace-id])]
    (execute alice req)
    (write! ::model/workspaces [:name] {:workspace-id id :name "Renamed"})
    (is (= {:name "Renamed" :created_by (:user-id alice)}
           (admin! ["select name, created_by from public.workspaces where workspace_id=?" id])))))


(deftest set-on-an-existing-row-needs-only-update-privilege
  ;; Everything here, including the probe role, rolls back.
  (let [alice (:user-id (context))]
    (jdbc/with-transaction [tx (db "postgres") {:rollback-only true}]
      (doseq [statement ["create role bel_p2_probe"
                         "grant usage on schema public to bel_p2_probe"
                         "grant select, update (display_name) on public.profiles to bel_p2_probe"
                         "create policy bel_p2_probe_all on public.profiles to bel_p2_probe using (true)"
                         "set local role bel_p2_probe"]]
        (jdbc/execute! tx [statement]))
      (pg/write-model! {:tx tx} (get-in (bel/operations) [:models ::profile-model/profiles])
                       [:display-name] {:user-id alice :display-name "Alicia"})
      (is (= "Alicia" (:display_name (jdbc/execute-one! tx ["select display_name from public.profiles where user_id=?" alice]
                                                        {:builder-fn rs/as-unqualified-lower-maps})))))))


(deftest set-on-a-missing-complete-entity-inserts-it
  (let [alice (:user-id (context)) id (random-uuid)]
    (write! ::model/workspaces [:name :created-by :created-at]
            {:workspace-id id :name "New" :created-by alice :created-at (Instant/now)})
    (is (= {:name "New" :created_by alice} (admin! ["select name, created_by from public.workspaces where workspace_id=?" id])))))


(deftest set-without-an-optional-mapped-field-clears-it
  (let [alice (:user-id (context))]
    (write! ::profile-model/profiles [:display-name] {:user-id alice})
    (is (nil? (:display_name (profile alice))))))


(deftest set-on-a-missing-incomplete-entity-is-a-validation-error
  (let [id (random-uuid)]
    (is (= :validation (:type (error-data #(write! ::model/workspaces [:name] {:workspace-id id :name "X"})))))
    (is (= 0 (:n (admin! ["select count(*) as n from public.workspaces where workspace_id=?" id]))))))


(deftest principal-is-the-current-session
  (let [ctx (context)]
    (is (= {:user-id (:user-id ctx) :session-id (:session-id ctx) :confirmed? true :password-required? false :memberships []}
           (runtime/with-authority (runtime/prepare-context ctx) #{} :read-committed #(:principal %))))))


(defn profile-user!
  "A confirmed user with a profile name and optional locale metadata."
  [name locale]
  (let [user-id (random-uuid)]
    (admin! ["insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values(?,?,now(),?::jsonb)"
             user-id (str user-id "@example.test") (json/write-json-str (cond-> {:name name} locale (assoc :locale locale)))])
    user-id))


(defn as-app
  "Runs sql as tover_app with the user's claim; returns rows affected or the SQL state of the failure."
  [user-id sql]
  (with-open [connection (jdbc/get-connection (db "tover_app"))]
    (jdbc/execute-one! connection ["select set_config('request.jwt.claim.sub', ?, false)" (str user-id)])
    (try (:next.jdbc/update-count (jdbc/execute-one! connection [sql]))
         (catch java.sql.SQLException error (.getSQLState error)))))


(deftest profile-update-is-own-row-and-columns-only
  (let [alice (profile-user! "Alice" nil)
        bob   (profile-user! "Bob" nil)]
    (is (= 0 (as-app alice (str "update public.profiles set display_name='Hacked' where user_id='" bob "'"))))
    (is (= "Bob" (:display_name (profile bob))))
    (is (= 1 (as-app alice (str "update public.profiles set display_name='Alicia', name_revision=1, language='en', language_revision=1 where user_id='" alice "'"))))
    (is (= "42501" (as-app alice (str "update public.profiles set created_at=now() where user_id='" alice "'"))))
    (is (= "23514" (as-app alice (str "update public.profiles set language='de' where user_id='" alice "'"))))
    (is (= "23514" (as-app alice (str "update public.profiles set display_name='   ' where user_id='" alice "'"))))))


(defn save-profile [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input (assoc input :account (:user-id ctx))}))


(defn profile-row [user-id]
  (admin! ["select display_name, language, name_revision, language_revision from public.profiles where user_id=?" user-id]))


(deftest name-and-language-saves-do-not-overwrite
  (let [ctx (context)]
    (is (= :accepted (:status (save-profile ctx ::profile-model/set-display-name "N" {:display-name "Alicia" :name-revision 0}))))
    (is (= :accepted (:status (save-profile ctx ::profile-model/change-account-language "L"
                                            {:language "ru" :language-revision 0}))))
    (is (= {:display_name "Alicia" :language "ru" :name_revision 1 :language_revision 1} (profile-row (:user-id ctx))))
    (is (= {:user-id (:user-id ctx) :display-name "Alicia" :language "ru" :name-revision 1 :language-revision 1}
           (runtime/read-model ctx ::profile-model/my-profile {})))
    (is (= :stale-profile (:type (error-data #(save-profile ctx ::profile-model/set-display-name "S"
                                                            {:display-name "Other" :name-revision 0})))))))


(deftest live-workspace-system-runs-user-scoped-profile-saves
  ;; The application's system defaults to workspace scope; the profile commands declare user scope.
  (let [ctx (assoc (context) :bel/system (wiring/system {:datasource (db "tover_app")}) :workspace-id (random-uuid))]
    (is (= :accepted (:status (save-profile ctx ::profile-model/set-display-name "W" {:display-name "Alicia" :name-revision 0}))))
    (is (= "Alicia" (:display-name (runtime/read-model ctx ::profile-model/my-profile {}))))))


(deftest the-session-store-finds-a-key-only-for-its-own-bel-session
  (let [{:keys [session-id auth-session-id]} (context)
        store (session/jdbc-store {:datasource (db "tover_app") :encryption-key "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})]
    (is (= auth-session-id (session/session-key store session-id)))
    (is (nil? (session/session-key store (random-uuid))))))


(deftest repeated-command-returns-original-after-later-edit
  (let [ctx        (context)
        first-save (save-profile ctx ::profile-model/set-display-name "C1" {:display-name "A" :name-revision 0})]
    (is (= :accepted (:status (save-profile ctx ::profile-model/set-display-name "C2" {:display-name "B" :name-revision 1}))))
    (is (= first-save (save-profile ctx ::profile-model/set-display-name "C1" {:display-name "A" :name-revision 0})))
    (is (= {:display_name "B" :name_revision 2} (select-keys (profile-row (:user-id ctx)) [:display_name :name_revision])))))


(deftest check-violations-are-validation-errors
  (let [alice (:user-id (context))]
    (is (= :validation (:type (error-data #(write! ::profile-model/profiles [:display-name]
                                                   {:user-id alice :display-name "   "})))))
    (is (= "Alice" (:display_name (profile alice))))))


(deftest signup-locale-seeds-profile-language
  (let [ru (profile-user! "Ru" "ru")
        de (profile-user! "De" "de")]
    (is (= "ru" (:language (admin! ["select language from public.profiles where user_id=?" ru]))))
    (is (nil? (:language (admin! ["select language from public.profiles where user_id=?" de]))))))


(deftest restart-receipt
  (let [file (io/file (System/getenv "TOVER_BEL_TEST_STATE"))]
    (if (= "1" (System/getenv "TOVER_BEL_RESTART_CHECK"))
      (let [{:keys [context request receipt]} (nippy/thaw (Files/readAllBytes (.toPath file)))
            ctx (assoc context :bel/system (system))]
        (is (= receipt (execute ctx request)))
        (is (= "Workshop" (:name (current ctx request))))
        (is (= [1 1 2 1] (counts ctx request))))
      (let [ctx (context) req (request) receipt (execute ctx req)]
        (with-open [out (io/output-stream file)]
          (.write out ^bytes (nippy/freeze {:context (dissoc ctx :bel/system) :request req :receipt receipt})))
        (is (= [1 1 2 1] (counts ctx req)))))))


(defn -main [& _]
  (let [url  (System/getenv "TOVER_BEL_TEST_URL")
        root (System/getenv "TOVER_BEL_TEST_ROOT")]
    (when-not (and url root (str/starts-with? url "jdbc:postgresql://127.0.0.1:")
                   (str/starts-with? root "/private/tmp/tover-bel-workspace."))
      (throw (ex-info "Use scripts/test-bel-workspace.sh; an existing database is not a test target" {})))
    (when-not (= (System/getenv "TOVER_BEL_TEST_DATA_DIRECTORY")
                 (:data_directory (admin! ["show data_directory"])))
      (throw (ex-info "Refusing a database outside the disposable cluster" {}))))
  (require 'goods-journal-integration 'invitation-delivery-integration)
  (instrumentation/start!)
  (let [result (try
                 (if (= "1" (System/getenv "TOVER_BEL_RESTART_CHECK"))
                   (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                     (test/test-vars [#'restart-receipt (resolve 'goods-journal-integration/restart-delivery)])
                     (let [result @test/*report-counters*]
                       (test/do-report (assoc result :type :summary)) result))
                   (test/run-tests 'workspace-creation-integration 'goods-journal-integration 'invitation-delivery-integration))
                 (finally (instrumentation/stop!) (shutdown-agents)))]
    (flush)
    ;; The disposable runner restarts the stores after an abrupt exit, without JVM shutdown hooks.
    (.halt (Runtime/getRuntime) (if (zero? (+ (:fail result) (:error result))) 0 1))))

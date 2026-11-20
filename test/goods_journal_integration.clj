(ns goods-journal-integration
  "Real RDF/PostgreSQL checks, only through the disposable store test runner."
  (:require
    [bel-postgres.journal :as journal-store]
    [bel-rdf.adapter :as source]
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.delivery :as delivery]
    [bel.handler :as handler]
    [bel.journal :as journal]
    [bel.runtime :as runtime]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [taoensso.nippy :as nippy]
    [tover.catalogues.persistence :as persistence]
    [tover.catalogues.rename-test :as rename]
    [tover.catalogues.rename.model :as goods]
    [tover.shared.rdf :as rdf]
    [tover.support :as support]
    [workspace-creation-integration :as workspace])
  (:import
    [java.nio.file Files]
    [java.util.concurrent CountDownLatch TimeUnit]))


(defn register [f]
  (bel/build-registry! ['tover.accounts.profile.model 'tover.workspaces.create.model 'tover.workspaces.create.web
                        'tover.catalogues.rename.model 'tover.catalogues.rename.web])
  (f))


(use-fixtures :each fixture/isolated-registry register)


(defn system [repo]
  (support/bel-system {:repo repo :datasource (workspace/db "tover_app")}))


(defn context
  ([repo] (context repo false))
  ([repo same-identity?]
   (let [ctx (workspace/context)
         id  (if same-identity? (:user-id ctx) (random-uuid))]
     (workspace/execute ctx {:command-id "C" :input {:workspace-id id :name "Workshop"}})
     (let [ctx (runtime/prepare-context (assoc ctx :repo repo :workspace-id id :bel/system (system repo)))]
       (rename/baseline! repo (:scope ctx))
       ctx))))


(defn with-feature [f]
  (support/with-store (fn [repo _] (f (context repo)))))


(defn execute [ctx]
  (runtime/execute! ctx ::goods/rename-good rename/request))


(defn row [ctx]
  (runtime/read-model ctx ::goods/goods {:id "G"}))


(defn writer [] (workspace/db "tover_journal"))


(defn delivery-system [repo]
  (support/delivery-system {:repo repo :datasource (writer)}))


(defn counts [ctx]
  (mapv (fn [query] (:n (workspace/admin! query)))
        [["select count(*) as n from app_private.bel_events where scope_kind='workspace' and scope_id=?" (:workspace-id ctx)]
         ["select count(*) as n from app_private.bel_receipts where scope_kind='workspace' and scope_id=?" (:workspace-id ctx)]]))


(defn pending [ctx]
  (filterv #(= (:scope ctx) (get-in % [:request :scope])) (delivery/pending (:bel/system ctx))))


(defn eventually [pred]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (cond (pred) true
            (> (System/nanoTime) deadline) false
            :else (do (Thread/sleep 20) (recur))))))


(deftest source-rollback-and-uncommitted-work-never-reach-the-journal
  (doseq [stage [#'runtime/apply-event! #'source/save-pending!]]
    (with-feature
      (fn [ctx]
        (let [original @stage]
          (with-redefs-fn {stage (fn [& args] (apply original args) (throw (ex-info "After source write" {:type :injected})))}
            #(is (= :injected (:type (workspace/error-data (fn [] (execute ctx))))))))
        (is (= ["Bolt" 4] ((juxt :name :revision) (row ctx))))
        (is (empty? (pending ctx)))
        (is (= [0 0] (counts ctx)))
        (is (= :accepted (:status (execute ctx))))))))


(deftest postgres-is-the-only-history-and-acknowledged-retries-skip-decisions
  (with-feature
    (fn [ctx]
      (let [receipt  (execute ctx)
            delivery (first (pending ctx))]
        (is (= ["Nut" 5] ((juxt :name :revision) (row ctx))))
        (is (= [0 0] (counts ctx)))
        (delivery/deliver-one! (delivery-system (:repo ctx)) :catalogue delivery)
        (is (= [1 1] (counts ctx)))
        (is (empty? (pending ctx)))
        (let [event (first (runtime/history ctx))]
          (is (= (first (:event-ids receipt)) (:id event)))
          (is (= "Nut" (get-in event [:data :name])))
          (is (= 0 (:event_index event)))
          (is (some? (:recorded_at event))))
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :snapshot}]
          (doseq [kind [:events :receipts]]
            (is (empty? (rdf/select tx '{:select [?s ?p ?o] :where [[:graph ?g [[?s ?p ?o]]]]}
                                    {:g (persistence/graph (:scope ctx) kind)}))))
          (is (= [true] (mapv :value (rdf/select tx '{:select [?value] :where [[:graph ?g [[?s ?p ?value]]]]}
                                                 {:g (persistence/graph (:scope ctx) :delivery)})))))
        (with-redefs [goods/rename-good    (fn [& _] (throw (Exception. "Decision reran")))
                      runtime/apply-event! (fn [& _] (throw (Exception. "Mapping reran")))]
          (is (= receipt (execute ctx)))
          (is (seq (handler/prepare-result ctx (handler/execute-action ctx :tover.catalogues.rename.web/save rename/cell)))))
        (is (= :idempotency-conflict
               (:type (workspace/error-data #(runtime/execute! ctx ::goods/rename-good
                                                               (assoc-in rename/request [:input :name] "Other"))))))))))


(deftest pre-refactor-pending-record-still-delivers
  (with-feature
    (fn [ctx]
      (let [receipt  (execute ctx)
            delivery (first (pending ctx))
            graph    (persistence/graph (:scope ctx) :delivery)
            subject  (#'source/node graph (:command-id delivery))
            marker   (#'source/term :pending)]
        ;; Written as the pre-P1 adapter did: Tover-scope graph lookup, (assoc invocation :events :result) payload.
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
          (rdf/remove! tx graph subject marker nil)
          (rdf/add! tx graph subject marker
                    (nippy/freeze-to-string (assoc (dissoc delivery :events :result)
                                                   :events (:events delivery) :result (:result delivery)))))
        (is (= {:delivered 1 :failed 0} (delivery/deliver-pending! (delivery-system (:repo ctx)))))
        (is (= [1 1] (counts ctx)))
        (is (empty? (pending ctx)))
        (with-redefs [goods/rename-good (fn [& _] (throw (Exception. "Decision reran")))]
          (is (= receipt (execute ctx))))))))


(deftest delivery-failure-rolls-back-both-postgres-writes-and-retries-the-same-batch
  (with-feature
    (fn [ctx]
      (let [receipt (execute ctx) append journal-store/append-event!]
        (with-redefs [journal-store/append-event! (fn [& args] (apply append args) (throw (ex-info "Journal unavailable" {:type :injected})))]
          (is (= {:delivered 0 :failed 1} (delivery/deliver-pending! (delivery-system (:repo ctx)))))
          (is (= receipt (execute ctx))))
        (is (= [0 0] (counts ctx)))
        (is (= 1 (count (pending ctx))))
        (is (= 5 (:revision (row ctx))))
        (is (= {:delivered 1 :failed 0} (delivery/deliver-pending! (delivery-system (:repo ctx)))))
        (is (= [1 1] (counts ctx)))
        (is (= receipt (execute ctx)))))))


(deftest crashes-on-either-side-of-source-acknowledgement-are-recoverable
  (doseq [after? [false true]]
    (with-feature
      (fn [ctx]
        (let [receipt  (execute ctx) delivery (first (pending ctx)) acknowledge source/acknowledge!
              fail-ack (fn [& args]
                         (when after? (apply acknowledge args))
                         (throw (ex-info "Lost acknowledgement" {:type :injected})))]
          (with-redefs [source/acknowledge! fail-ack]
            (is (= :injected (:type (workspace/error-data #(delivery/deliver-one! (delivery-system (:repo ctx)) :catalogue delivery))))))
          (is (= [1 1] (counts ctx)))
          (is (= receipt (execute ctx)))
          (delivery/deliver-one! (delivery-system (:repo ctx)) :catalogue delivery)
          (is (empty? (pending ctx)))
          (is (= [1 1] (counts ctx)))
          (is (= receipt (execute ctx))))))))


(deftest acknowledged-marker-never-allows-a-decision-when-journal-lookup-fails
  (with-feature
    (fn [ctx]
      (let [receipt (execute ctx)]
        (delivery/deliver-pending! (delivery-system (:repo ctx)))
        (with-redefs [journal/receipt   (constantly nil)
                      goods/rename-good (fn [& _] (throw (Exception. "Decision reran")))]
          (is (= :journal-unavailable (:type (workspace/error-data #(execute ctx)))))
          (is (= :journal-unavailable
                 (:type (workspace/error-data #(handler/execute-action ctx :tover.catalogues.rename.web/save rename/cell))))))
        (is (= receipt (execute ctx)))
        (is (= [1 1] (counts ctx)))
        (is (= 5 (:revision (row ctx))))))))


(deftest delivery-between-authority-check-and-rdf-read-is-visible-to-the-retry
  (with-feature
    (fn [ctx]
      (let [receipt             (execute ctx)
            delivery            (first (pending ctx))
            authorize           runtime/with-authority
            deliver-before-read (fn [context owning isolation read]
                                  (authorize context owning isolation
                                             (fn [authorized]
                                               (delivery/deliver-one! (delivery-system (:repo ctx)) :catalogue delivery)
                                               (read authorized))))]
        (with-redefs [runtime/with-authority deliver-before-read
                      goods/rename-good      (fn [& _] (throw (Exception. "Decision reran")))]
          (is (= receipt (execute ctx))))
        (is (empty? (pending ctx)))
        (is (= [1 1] (counts ctx)))))))


(deftest concurrent-source-retries-and-delivery-workers-have-one-acceptance
  (with-feature
    (fn [ctx]
      (let [results (rename/concurrent-results ctx [rename/request rename/request])
            receipt (execute ctx)]
        (is (some #{receipt} results))
        (is (= 5 (:revision (row ctx))))
        (is (= 1 (count (pending ctx))))
        (let [gate (CountDownLatch. 2) deliver journal/deliver!]
          (with-redefs [journal/deliver! (fn [& args]
                                           (.countDown gate)
                                           (assert (.await gate 10 TimeUnit/SECONDS))
                                           (apply deliver args))]
            (let [jobs (mapv (fn [_] (future (delivery/deliver-pending! (delivery-system (:repo ctx))))) (range 2))]
              (try
                (let [results (mapv #(deref % 15000 ::timeout) jobs)]
                  (is (not-any? #{::timeout} results))
                  (is (pos? (reduce + (map :delivered results)))))
                (finally (doseq [job jobs] (future-cancel job)))))))
        (is (= [1 1] (counts ctx)))
        (is (empty? (pending ctx)))
        (is (= receipt (execute ctx)))))))


(deftest a-conflicting-journal-batch-cannot-be-acknowledged-or-overwrite-history
  (doseq [difference [:request :event :missing-event]]
    (with-feature
      (fn [ctx]
        (execute ctx)
        (let [delivery    (first (pending ctx))
              conflicting (case difference
                            :request (assoc-in delivery [:request :input :name] "Different acceptance")
                            :event (assoc-in delivery [:events 0 :data :name] "Different fact")
                            :missing-event (assoc delivery :events []))]
          (journal/deliver! {:bel/system (delivery-system (:repo ctx))} conflicting)
          (let [history (runtime/history ctx)]
            (is (= :journal-conflict
                   (:type (workspace/error-data #(delivery/deliver-one! (delivery-system (:repo ctx)) :catalogue delivery))))
                (name difference))
            (is (= history (runtime/history ctx))))
          (is (= [(count (:events conflicting)) 1] (counts ctx)))
          (is (= [delivery] (pending ctx)))
          (is (= "Nut" (:name (row ctx)))))))))


(deftest an-unreadable-or-misplaced-record-does-not-block-other-workspaces
  (doseq [problem [:unreadable :wrong-scope]]
    (support/with-store
      (fn [repo _]
        (let [broken (context repo) healthy (context repo)]
          (execute broken)
          (let [receipt   (execute healthy)
                graph     (persistence/graph (:scope broken) :delivery)
                subject   (rdf/iri (str graph ":C"))
                predicate (rdf/iri "urn:bel:journal:pending")
                payload   (case problem
                            :unreadable "Unreadable delivery"
                            :wrong-scope (nippy/freeze-to-string
                                           (assoc-in (first (pending broken)) [:request :scope] (:scope healthy))))]
            (rdf/with-transaction [tx {:repo repo :level :serializable}]
              (rdf/remove! tx graph subject predicate nil)
              (rdf/add! tx graph subject predicate payload))
            (is (= {:delivered 1 :failed 1} (delivery/deliver-pending! (delivery-system repo))))
            (is (= [1 1] (counts healthy)))
            (is (= [0 0] (counts broken)))
            (is (= receipt (execute healthy)))
            (is (= "Nut" (:name (row broken))))
            (rdf/with-transaction [tx {:repo repo :level :snapshot}]
              (is (= payload (rdf/value-at tx graph subject predicate))))))))))


(deftest workspace-and-scope-kind-isolation-cover-domain-journal-and-receipts
  (support/with-store
    (fn [repo _]
      (let [alice          (context repo true) bob (context repo)
            first-receipt  (execute alice)
            second-receipt (runtime/execute! bob ::goods/rename-good (assoc-in rename/request [:input :name] "Bob"))]
        (is (not= first-receipt second-receipt))
        (is (= {:delivered 2 :failed 0} (delivery/deliver-pending! (delivery-system repo))))
        (is (= ["Nut"] (mapv #(get-in % [:data :name]) (runtime/history alice))))
        (is (= ["Bob"] (mapv #(get-in % [:data :name]) (runtime/history bob))))
        (is (= first-receipt (execute alice)))
        (is (= #{"user" "workspace"}
               (set (map :scope_kind (jdbc/execute! (workspace/db "postgres")
                                                    ["select scope_kind from app_private.bel_receipts where scope_id=? and command_id='C'" (:user-id alice)]
                                                    {:builder-fn rs/as-unqualified-lower-maps})))))
        (is (= [0 1] (mapv :event_index
                           (jdbc/execute! (workspace/db "postgres")
                                          ["select event_index from app_private.bel_events where scope_kind='user' and scope_id=? order by event_index" (:user-id alice)]
                                          {:builder-fn rs/as-unqualified-lower-maps}))))
        (let [spoofed (runtime/prepare-context (assoc alice :workspace-id (:workspace-id bob)
                                                      :memberships [{:workspace-id (:workspace-id bob)}]))]
          (is (= :forbidden (:type (workspace/error-data #(execute spoofed)))))
          (is (= :forbidden (:type (workspace/error-data #(row spoofed)))))
          (is (= :forbidden (:type (workspace/error-data #(runtime/history spoofed))))))
        (runtime/with-authority alice #{} :read-committed
          (fn [{:keys [tx]}]
            (is (empty? (jdbc/execute! tx ["select id from app_private.bel_events where scope_kind='workspace' and scope_id=?" (:workspace-id bob)])))
            (is (empty? (jdbc/execute! tx ["select payload from app_private.bel_receipts where scope_kind='workspace' and scope_id=?" (:workspace-id bob)])))))
        (is (= "Nut" (:name (row alice))))
        (is (= "Bob" (:name (row bob))))))))


(deftest revocation-prevents-new-work-but-cannot-strand-already-accepted-delivery
  (with-feature
    (fn [ctx]
      (execute ctx)
      (workspace/admin! ["update public.memberships set status='suspended' where workspace_id=?" (:workspace-id ctx)])
      (is (= :forbidden (:type (workspace/error-data #(execute ctx)))))
      (is (= :forbidden (:type (workspace/error-data #(row ctx)))))
      (is (= :forbidden (:type (workspace/error-data #(runtime/history ctx)))))
      (is (= {:delivered 1 :failed 0} (delivery/deliver-pending! (delivery-system (:repo ctx)))))
      (is (= [1 1] (counts ctx)))
      (is (empty? (pending ctx))))))


(deftest membership-lock-is-held-until-rdf-acceptance-commits
  (with-feature
    (fn [ctx]
      (let [save       source/save-pending! staged (promise) resume (promise)
            pause-save (fn [& args]
                         (apply save args)
                         (deliver staged true)
                         (assert (= true (deref resume 10000 ::timeout))))]
        (with-redefs [source/save-pending! pause-save]
          (let [acceptance (future (execute ctx))]
            (try
              (is (= true (deref staged 10000 ::timeout)))
              (is (empty? (pending ctx)))
              (is (= {:delivered 0 :failed 0} (delivery/deliver-pending! (delivery-system (:repo ctx)))))
              (let [started (promise)
                    revoke  (future (deliver started true)
                                    (workspace/admin! ["update public.memberships set status='suspended' where workspace_id=?" (:workspace-id ctx)]))]
                (try
                  (is (= true (deref started 10000 ::timeout)))
                  (is (= ::waiting (deref revoke 200 ::waiting)))
                  (deliver resume true)
                  (is (= :accepted (:status (deref acceptance 10000 {}))))
                  (is (not= ::timeout (deref revoke 10000 ::timeout)))
                  (finally (deliver resume true) (future-cancel revoke))))
              (finally (deliver resume true) (future-cancel acceptance)))))
        (is (= :forbidden (:type (workspace/error-data #(execute ctx)))))
        (is (= {:delivered 1 :failed 0} (delivery/deliver-pending! (delivery-system (:repo ctx)))))))))


(deftest background-delivery-retries-without-blocking-acceptance-or-view-refresh
  (with-feature
    (fn [ctx]
      (let [append    journal-store/append-event! attempts (atom 0) started (promise) release (promise)
            fail-once (fn [& args]
                        (apply append args)
                        (when (= 1 (swap! attempts inc))
                          (deliver started true)
                          (assert (= true (deref release 10000 ::timeout)))
                          (throw (ex-info "Temporary delivery failure" {:type :injected}))))]
        (with-redefs [journal-store/append-event! fail-once]
          (with-open [_worker (delivery/start! (delivery-system (:repo ctx)))]
            (let [receipt (execute ctx)]
              (try
                (is (= true (deref started 10000 ::timeout)))
                (is (= receipt (execute ctx)))
                (is (= "Nut" (:name (row ctx))))
                (is (seq (handler/prepare-result ctx (handler/execute-action ctx :tover.catalogues.rename.web/save rename/cell))))
                (is (= [0 0] (counts ctx)))
                (finally (deliver release true)))
              (is (eventually #(empty? (pending ctx))))
              (is (= [1 1] (counts ctx)))
              (is (= receipt (execute ctx))))))))))


(deftest worker-role-is-private-and-legacy-rdf-history-is-not-migrated-implicitly
  (doseq [table ["auth.users" "app_private.sessions" "public.memberships"]]
    (is (false? (:allowed (workspace/admin! ["select has_table_privilege('tover_journal',?,'SELECT') as allowed" table])))))
  ;; 038 narrows the worker: it reads organizations (for the invitation email) but nothing else of the directory.
  (is (true? (:allowed (workspace/admin! ["select has_table_privilege('tover_journal','public.workspaces','SELECT') as allowed"]))))
  (is (false? (:allowed (workspace/admin! ["select has_table_privilege('tover_journal','public.workspaces','INSERT,UPDATE,DELETE') as allowed"]))))
  (doseq [table ["app_private.bel_events" "app_private.bel_receipts"]]
    (is (false? (:allowed (workspace/admin! ["select has_table_privilege('tover_journal',?,'UPDATE,DELETE') as allowed" table])))))
  (with-feature
    (fn [ctx]
      (let [graph (persistence/graph (:scope ctx) :receipts) subject (rdf/iri "urn:test:legacy") predicate (rdf/iri "urn:test:value")]
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
          (rdf/add! tx graph subject predicate "Leave unchanged"))
        (is (= :migration-required (:type (workspace/error-data #(execute ctx)))))
        (is (= ["Bolt" 4] ((juxt :name :revision) (row ctx))))
        (is (= [0 0] (counts ctx)))
        (is (empty? (pending ctx)))
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :snapshot}]
          (is (= "Leave unchanged" (rdf/value-at tx graph subject predicate))))))))


(deftest restart-delivery
  (let [root (System/getenv "TOVER_BEL_TEST_ROOT")
        file (io/file root "goods-state.bin")
        repo (rdf/native-store (io/file root "goods-rdf"))]
    (try
      (if (= "1" (System/getenv "TOVER_BEL_RESTART_CHECK"))
        (let [states (nippy/thaw (Files/readAllBytes (.toPath file)))]
          (with-open [_worker (delivery/start! (delivery-system repo))]
            (is (eventually #(empty? (delivery/pending (delivery-system repo)))))
            (doseq [{:keys [context receipt]} states]
              (let [ctx (runtime/prepare-context (assoc context :repo repo :bel/system (system repo)))]
                (with-redefs [goods/rename-good (fn [& _] (throw (Exception. "Restart reran the decision")))]
                  (is (= receipt (execute ctx))))
                (is (= ["Nut" 5] ((juxt :name :revision) (row ctx))))
                (is (= [1 1] (counts ctx)))))))
        (let [states (mapv (fn [stage]
                             (let [ctx (context repo) receipt (execute ctx) delivery (first (pending ctx))]
                               (case stage
                                 :pending nil
                                 :committed (journal/deliver! {:bel/system (delivery-system (:repo ctx))} delivery)
                                 :acknowledged (delivery/deliver-one! (delivery-system repo) :catalogue delivery))
                               {:context (dissoc ctx :repo :bel/system :resources) :receipt receipt}))
                           [:pending :committed :acknowledged])]
          (with-open [out (io/output-stream file)] (.write out ^bytes (nippy/freeze states)))
          (is (= 2 (count (delivery/pending (delivery-system repo)))))))
      ;; The first test JVM halts with this store open. Recovery must not depend on close/shutdown hooks.
      (finally
        (when (= "1" (System/getenv "TOVER_BEL_RESTART_CHECK"))
          (rdf/close! repo))))))

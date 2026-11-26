(ns tover.marketplace.connections-test
  "Board chapter 40 · Connect a marketplace account. One test per slice example: decisions without a store,
  then the store lifecycle for retries, reads and the seeded `Connection Credentials Rejected` (chapter 41)."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.realtime]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.products.model]
    [tover.inventory.stock-items.model]
    [tover.marketplace.connections.model :as model]
    [tover.marketplace.connector :as connector]
    [tover.marketplace.products.model]
    [tover.marketplace.warehouses.model]
    [tover.support :as support])
  (:import
    [java.time Duration Instant]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.stores.model
                        'tover.catalogues.products.model 'tover.inventory.stock-items.model 'tover.marketplace.products.model 'tover.marketplace.warehouses.model
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model
                        'tover.workspaces.currency.model])
  (f))


(defn unit-authority
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                         (read (assoc context :principal {:user-id (:user-id context) :memberships (:memberships context)})))]
    (f)))


(use-fixtures :each bel-test/isolated-registry register unit-authority)


(def workspace (:workspace-id support/scope))
(def scope {:workspace-id workspace})
(def admin #uuid "00000000-0000-0000-0000-0000000000a1")
(def member #uuid "00000000-0000-0000-0000-0000000000b1")
(def c "00000000-0000-0000-0000-00000000c001")
(def secret "00000000-0000-0000-0000-00000000e001")
(def new-secret "00000000-0000-0000-0000-00000000e002")
(def run "00000000-0000-0000-0000-00000000f001")
(def now (Instant/parse "2026-11-20T10:00:00Z"))
(def all-domains connector/domains)


(def admin-id (str admin))
(def at (str now))


(defn hours-ago
  "ISO text, as the model stores instants."
  {:malli/schema [:=> [:cat :int] :string]}
  [hours]
  (str (.minus ^Instant now (Duration/ofHours hours))))


(def connection
  {:connection-id       c :workspace-id             (str workspace) :provider         "ozon"
   :provider-account-id "123" :secret-ref           secret          :account-currency "RUB"
   :readable-domains    (model/encode-domains all-domains)
   :sync-state          "active" :credentials-state "valid"         :connected-by     admin-id
   :connected-at        (hours-ago 100)})


(defn decide
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [command facts input]
  (runtime/decide command (merge {:scope scope :actor admin :now now} facts) input))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn data
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [plan]
  (mapv #(select-keys % [:type :data]) (:events plan)))


(defn after
  "The connection after a plan, as the model's event mappings write it."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [connection plan]
  (reduce #(merge %1 (:data %2)) connection (:events plan)))


(def verified
  {:verification-outcome "verified" :account-currency "RUB" :secret-ref secret :readable-domains all-domains})


(defn connect
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (decide ::model/connect-marketplace-account (merge {:connections [] :sealed? true} facts)
          (merge {:connection-id c :provider "ozon" :client-id " 123 "} verified input)))


(defn unverified
  {:malli/schema [:=> [:cat :string] :map]}
  [outcome]
  (decide ::model/connect-marketplace-account {:connections []}
          {:connection-id c :provider "ozon" :client-id "123" :verification-outcome outcome}))


(defn manager?
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [role]
  (runtime/policy ::model/manager {:principal {:memberships [{:workspace-id workspace :role role}]} :scope scope} {}))


;; Slice 2 · Connect the marketplace account


(deftest connect-a-seller-account
  (let [plan (connect {} {:account-label "Shop LLC" :key-expires-at "2027-01-01T00:00:00Z"})]
    (is (= [{:type ::model/marketplace-account-connected
             :data {:connection-id       c :workspace-id             (str workspace)        :provider     "ozon"
                    :provider-account-id "123" :account-label        "Shop LLC"             :secret-ref   secret
                    :account-currency    "RUB" :key-expires-at       "2027-01-01T00:00:00Z"
                    :readable-domains    (model/encode-domains all-domains)
                    :sync-state          "active" :credentials-state "valid"                :connected-by admin-id :connected-at at}}]
           (data plan)))
    (let [status (model/status (after {} plan) {:manager? true})]
      (is (false? (:automatic-acceptance-possible? status)))
      (is (= ["no baseline"] (:blocking-reasons status))))))


;; Counts come from later chapters; here only what the status does with them.
(deftest only-three-things-block-the-connection
  (let [opts   {:manager? true :currency "RUB"}
        status #(model/status % %2)]
    (is (= [] (:blocking-reasons (status connection (assoc opts :counts {:baselines 1 :unmatched-listings 9 :unmatched-warehouses 2})))))
    (is (:automatic-acceptance-possible? (status connection (assoc opts :counts {:baselines 1}))))
    (is (= ["paused" "needs credentials" "no baseline"]
           (:blocking-reasons (status (assoc connection :sync-state "paused" :credentials-state "rejected") (assoc opts :counts {:baselines 0})))))
    (is (= ["no baseline"] (:blocking-reasons (status connection opts))) "counts absent: zeros")
    (is (:currency-notice? (status (assoc connection :account-currency "KZT") opts)))
    (is (not (:currency-notice? (status connection opts))))))


(deftest coverage-by-next-step
  (let [counts {:unmatched-listings 3 :unmatched-warehouses 1 :scopes-without-baseline 4 :baselines 2
                :next-steps         {"Match product" 2 "Confirm" 5 "Skip" 0}}
        status (model/status connection {:counts counts})]
    (is (= [3 1 4] ((juxt :unmatched-listings :unmatched-warehouses :scopes-without-baseline) status)))
    (is (= [{:next-step "Confirm" :count 5} {:next-step "Match product" :count 2}] (:imported-operations-by-next-step status))
        "sorted by next step; steps with nothing waiting are left out")
    (is (= [] (:blocking-reasons status)))))


(deftest counts-are-zero-until-a-chapter-supplies-them
  ;; Chapters 42 and 43 supply unmatched listings and warehouses; set aside here, as this context has no store.
  (let [unmatched  (get-method model/count-part :unmatched-listings)
        warehouses (get-method model/count-part :unmatched-warehouses)]
    (remove-method model/count-part :unmatched-listings)
    (remove-method model/count-part :unmatched-warehouses)
    (try
      (is (= {:unmatched-listings 0 :unmatched-warehouses 0 :scopes-without-baseline 0 :baselines 0 :next-steps {}}
             (runtime/coeffect ::model/counts {} {:connection-id c})))
      (defmethod model/count-part :baselines [_ _ _] 7)
      (is (= 7 (:baselines (runtime/coeffect ::model/counts {} {:connection-id c}))))
      (finally
        (remove-method model/count-part :baselines)
        (when unmatched (.addMethod ^clojure.lang.MultiFn model/count-part :unmatched-listings unmatched))
        (when warehouses (.addMethod ^clojure.lang.MultiFn model/count-part :unmatched-warehouses warehouses))))))


(deftest key-without-analytics-access
  (let [domains (vec (remove #{"stock-analytics"} all-domains))
        plan    (connect {} {:readable-domains domains})
        rows    (:domains (model/status (after {} plan) {:manager? true}))]
    (is (= (model/encode-domains domains) (get-in (data plan) [0 :data :readable-domains])))
    (is (= [{:domain "stock-analytics" :readable? false}] (filterv #(= "stock-analytics" (:domain %)) rows)))
    (is (every? :readable? (remove #(= "stock-analytics" (:domain %)) rows)))))


(deftest key-that-cannot-read-products
  (is (= :products-not-readable (rejection #(connect {} {:readable-domains ["warehouses" "stock-analytics"]})))))


(deftest wrong-api-key
  (is (= :credentials-rejected
         (rejection #(unverified "rejected")))))


(deftest provider-timeout
  (is (= :could-not-verify
         (rejection #(unverified "unreachable")))))


(deftest same-seller-account-again
  (let [error (try (connect {:connections [connection]} {:connection-id "c2"})
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:type :already-connected :connection-id c} error))))


(deftest member-is-not-a-manager
  (is (manager? :owner))
  (is (manager? :admin))
  (is (not (manager? :member))))


;; Slice 4 · Pause the connection


(deftest pause-an-active-connection
  (let [plan (decide ::model/pause-marketplace-connection {:connection connection} {:connection-id c})]
    (is (= [{:type ::model/marketplace-connection-paused
             :data {:connection-id c :sync-state "paused" :reason "manager" :paused-by admin-id :paused-at at}}]
           (data plan)))
    (is (model/due? connection now))
    (is (not (model/due? (after connection plan) now)) "the scheduler no longer finds C due")))


(deftest pause-during-a-run
  ;; Chapter 40's half: pausing changes only the sync state, so the run's committed batches and positions
  ;; stay. Record Source Observations rechecking that state belongs to chapter 41.
  (let [running (assoc connection :last-completed-sync-at (hours-ago 30))
        paused  (after running (decide ::model/pause-marketplace-connection {:connection running} {:connection-id c}))]
    (is (= (dissoc running :sync-state) (select-keys paused (keys (dissoc running :sync-state)))))
    (is (= "paused" (:sync-state paused)))))


(deftest pause-wins-over-automatic-acceptance
  ;; Chapter 40's half: once the pause commits, the connection reads paused and blocks automatic acceptance.
  ;; The automatic path's recheck inside its own commit belongs to chapter 45.
  (let [paused (after connection (decide ::model/pause-marketplace-connection {:connection connection} {:connection-id c}))
        status (model/status paused {:manager? true})]
    (is (false? (:automatic-acceptance-possible? status)))
    (is (some #{"paused"} (:blocking-reasons status)))))


(deftest already-paused
  (let [paused (assoc connection :sync-state "paused" :reason "manager" :paused-by (str member) :paused-at (hours-ago 1))]
    (is (= {:fx [] :events []} (decide ::model/pause-marketplace-connection {:connection paused} {:connection-id c})))))


(deftest manual-review-still-works
  ;; Chapter 40's half: pausing records no change to proposals or history; accepting them in Review is chapter 45.
  (let [plan (decide ::model/pause-marketplace-connection {:connection connection} {:connection-id c})]
    (is (= #{::model/marketplace-connection-paused} (set (map :type (:events plan)))))
    (is (= [[:tover.marketplace.sync.model/mark-dirty {:connection-id c}]] (:fx plan)) "only the re-evaluation mark")))


(deftest pause-unknown-connection
  (is (= :unknown-connection (rejection #(decide ::model/pause-marketplace-connection {:connection nil} {:connection-id c})))))


;; Slice 6 · Resume the connection


(defn resume
  {:malli/schema [:=> [:cat :map] :map]}
  [connection]
  (decide ::model/resume-marketplace-connection {:connection connection} {:connection-id c}))


(def paused
  (assoc connection :sync-state "paused" :reason "manager" :paused-by admin-id :paused-at (hours-ago 72)))


(deftest resume-after-a-long-pause
  (let [before (assoc paused :last-completed-sync-at (hours-ago 72))
        plan   (resume before)]
    (is (= [{:type ::model/marketplace-connection-resumed
             :data {:connection-id c :sync-state "active" :resumed-by admin-id :resumed-at at}}]
           (data plan)))
    (is (model/due? (after before plan) now) "due at once")))


(deftest resume-soon-after-a-run
  (let [before (assoc paused :last-completed-sync-at (hours-ago 2))
        after  (after before (resume before))]
    (is (not (model/due? after now)))
    (is (= (.plus (Instant/parse (hours-ago 2)) (Duration/ofHours 24)) (model/next-sync-at after)))))


(deftest resume-needs-credentials
  (is (= :needs-credentials (rejection #(resume (assoc paused :credentials-state "rejected"))))))


(deftest resume-an-active-connection-accepts-nothing
  (is (= {:fx [] :events []} (resume connection))))


;; Slice 8 · Replace the connection credentials


(defn replace-credentials
  {:malli/schema [:=> [:cat :map :map] :map]}
  [connection input]
  (decide ::model/replace-connection-credentials {:connection connection :sealed? true}
          (merge {:connection-id c :client-id "123"} verified {:secret-ref new-secret :account-currency "KZT"} input)))


(deftest fix-rejected-credentials
  (let [rejected (assoc connection :credentials-state "rejected" :run-id run :rejected-at (hours-ago 1))
        plan     (replace-credentials rejected {})
        fixed    (after rejected plan)]
    (is (= [{:type ::model/connection-credentials-replaced
             :data {:connection-id        c       :secret-ref  new-secret :readable-domains (model/encode-domains all-domains)
                    :credentials-state    "valid" :replaced-by admin-id   :replaced-at      at :account-currency "KZT"
                    :consecutive-failures 0}}]
           (data plan))
        "replacing credentials also resets chapter 41's failure count")
    (is (= [[::model/destroy-secret {:secret-ref secret}]] (:fx plan)) "the old secret is destroyed")
    (is (not (model/due? rejected now)))
    (is (model/due? fixed now) "C is due")
    (is (= "active" (:sync-state fixed)))))


(deftest paused-stays-paused
  (let [fixed (after (assoc paused :credentials-state "rejected") (replace-credentials (assoc paused :credentials-state "rejected") {}))]
    (is (= "valid" (:credentials-state fixed)))
    (is (= "paused" (:sync-state fixed)))))


(deftest another-seller-account
  (is (= :different-seller-account (rejection #(replace-credentials connection {:client-id "456"})))))


(deftest key-rotation
  (let [rotated (after connection (replace-credentials connection {:account-currency "RUB"}))]
    (is (= (assoc (dissoc connection :secret-ref) :secret-ref new-secret :replaced-by admin-id :replaced-at at
                  :consecutive-failures 0)
           rotated))))


(deftest replacing-resets-the-failure-count
  (let [failed (assoc connection :run-id run :run-state "failed" :consecutive-failures 5)]
    (doseq [before [failed
                    (assoc failed :consecutive-failures 2 :next-attempt-at (hours-ago -1))
                    (assoc failed :sync-state "paused")]]
      (let [data (:data (first (:events (replace-credentials before {:key-expires-at "2027-01-01T00:00:00Z"}))))]
        (is (= 0 (:consecutive-failures data)))
        (is (not (contains? data :next-attempt-at)) "absent clears the failure wait")
        (is (not (contains? data :sync-state)) "sync state unchanged")
        (is (= "2027-01-01T00:00:00Z" (:key-expires-at data)))))))


(deftest a-destroyed-secret-is-never-referenced
  (is (= :conflict (rejection #(connect {:sealed? false} {}))))
  (is (= :conflict (rejection #(decide ::model/replace-connection-credentials {:connection connection :sealed? false}
                                       (merge {:connection-id c :client-id "123"} verified))))))


(deftest replace-rejections-follow-verification
  (doseq [[outcome type] {"rejected" :credentials-rejected "unreachable" :could-not-verify}]
    (is (= type (rejection #(decide ::model/replace-connection-credentials {:connection connection}
                                    {:connection-id c :client-id "123" :verification-outcome outcome})))))
  (is (= :products-not-readable (rejection #(replace-credentials connection {:readable-domains ["stock-analytics"]}))))
  (is (= :unknown-connection (rejection #(decide ::model/replace-connection-credentials {:connection nil}
                                                 (merge {:connection-id c :client-id "123"} verified))))))


;; Store lifecycle: retries, reads (slices 1, 3, 5, 7, 9) and the seeded chapter 41 rejection


(defn context
  {:malli/schema [:=> [:cat :any :keyword] :map]}
  [repo role]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo :marketplace {:repo repo}}) :repo repo :workspace-id workspace
                            :user-id     (if (= :member role) member admin)
                            :memberships [{:workspace-id workspace :role role}]}))


(defn execute!
  {:malli/schema [:=> [:cat :map :keyword :string :map] :map]}
  [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input input}))


(defn seed!
  "Apply an event produced outside this chapter, as its command would."
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [ctx event]
  (store/with-store (assoc ctx :bel/definitions (bel/operations)) :catalogue :serializable
    #(runtime/apply-event! % event)))


(def connect-input
  (merge {:connection-id c :provider "ozon" :client-id "123"} verified))


(deftest connections-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx    (context repo :admin)
            viewer (context repo :member)
            read   #(runtime/read-model %1 %2 %3)]
        (doseq [ref [secret new-secret]]
          (connector/store-secret! {:repo repo :key (byte-array 32)} workspace ref "api-key"))

        (testing "slice 2 · double-click: one connection, both responses show it"
          (let [first  (execute! ctx ::model/connect-marketplace-account "attempt-1" connect-input)
                second (execute! ctx ::model/connect-marketplace-account "attempt-1" connect-input)]
            (is (= first second))
            (is (= 1 (count (:connections (read ctx ::model/marketplace-connections-and-requirements {})))))))

        (testing "slice 2 · a member is not a manager"
          (is (= :forbidden (rejection #(execute! viewer ::model/connect-marketplace-account "attempt-2"
                                                  (assoc connect-input :connection-id (str (random-uuid))))))))

        (testing "slice 1 · every member reads connections, never secrets"
          (let [list (read viewer ::model/marketplace-connections-and-requirements {})]
            (is (= {:connections [{:connection-id c        :provider          "ozon"  :provider-account-id "123" :account-label "123"
                                   :sync-state    "active" :credentials-state "valid"}]
                    :can-manage? false}
                   list))
            (is (not (re-find #"secret" (pr-str list))))
            (is (:can-manage? (read ctx ::model/marketplace-connections-and-requirements {})))))

        (testing "slice 3 · the connection and its sync coverage"
          (let [status (read viewer ::model/connection-status-and-sync-coverage {:connection-id c})]
            (is (= {:sync-state           "active"        :credentials-state       "valid" :automatic-acceptance-possible?   false :account-currency "RUB"
                    :blocking-reasons     ["no baseline"] :can-manage?             false   :unmatched-listings               0
                    :unmatched-warehouses 0               :scopes-without-baseline 0       :imported-operations-by-next-step []}
                   (select-keys status [:sync-state :credentials-state :account-currency :automatic-acceptance-possible? :blocking-reasons
                                        :can-manage? :unmatched-listings :unmatched-warehouses :scopes-without-baseline
                                        :imported-operations-by-next-step])))
            (is (= (count all-domains) (count (:domains status))))
            (is (some? (:next-sync-at status)))
            (is (not (re-find #"secret" (pr-str status))))))

        (testing "slice 5 · the paused connection"
          (execute! ctx ::model/pause-marketplace-connection "pause-1" {:connection-id c})
          (let [paused (read viewer ::model/paused-connection {:connection-id c})]
            (is (= {:account-id admin-id} (:paused-by paused)) "no directory store here, so no display name")
            (is (= "manager" (:reason paused)))
            (is (= (mapv (fn [d] {:domain d}) all-domains) (:resume-positions paused)))
            (is (= 0 (:pending-imported-proposals paused)))
            (is (false? (:can-resume? paused)) "a member cannot resume")
            (is (true? (:can-resume? (read ctx ::model/paused-connection {:connection-id c}))))
            (is (= ["paused" "no baseline"] (:blocking-reasons (read ctx ::model/connection-status-and-sync-coverage {:connection-id c}))))))

        (testing "slice 7 · a connection that needs new credentials"
          (seed! ctx {:type ::model/connection-credentials-rejected
                      :data {:connection-id c :run-id run :credentials-state "rejected" :rejected-at at}})
          (is (= {:connection-id c :rejected-at now :run-id run :sync-state "paused" :can-replace-credentials? false}
                 (read viewer ::model/connection-needing-attention {:connection-id c})))
          (is (false? (:can-resume? (read ctx ::model/paused-connection {:connection-id c}))))
          (is (= :needs-credentials (rejection #(execute! ctx ::model/resume-marketplace-connection "resume-1" {:connection-id c})))))

        (testing "slice 8 · replacing keeps the connection paused"
          (execute! ctx ::model/replace-connection-credentials "replace-1"
                    (merge {:connection-id c :client-id "123"} verified {:secret-ref new-secret}))
          (is (= :credentials-valid (rejection #(read ctx ::model/connection-needing-attention {:connection-id c}))))
          (is (= "paused" (:sync-state (read ctx ::model/connection-status-and-sync-coverage {:connection-id c})))))

        (testing "slice 9 · the active connection"
          (execute! ctx ::model/resume-marketplace-connection "resume-2" {:connection-id c})
          (let [status (read viewer ::model/connection-status-and-sync-coverage {:connection-id c})]
            (is (= "active" (:sync-state status)))
            (is (= ["no baseline"] (:blocking-reasons status)))
            (is (some? (:next-sync-at status)) "never synced, so due at once")))

        (testing "slice 2 · the same seller account again points to the existing connection"
          (is (= :already-connected (rejection #(execute! ctx ::model/connect-marketplace-account "attempt-3"
                                                          (assoc connect-input :connection-id (str (random-uuid))))))))))))

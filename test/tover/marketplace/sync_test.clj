(ns tover.marketplace.sync-test
  "Board chapter 41 · Synchronize marketplace data. One test per slice example: decisions with given facts,
  fetcher requests over a fake Ozon, and a store run from scheduler to completed coverage."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.realtime]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [charred.api :as json]
    [clojure.test :refer [are deftest is testing use-fixtures]]
    [malli.core :as m]
    [tover.catalogues.records :as records]
    [tover.catalogues.stores.model :as stores]
    [tover.inventory.actors :as actors]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.connector :as connector]
    [tover.marketplace.sync-ozon-test :as fake]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.sync.ozon :as ozon]
    [tover.support :as support])
  (:import
    [java.time Duration Instant LocalDate]
    [java.util Base64]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.stores.model
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model])
  (f))


(defn unit-authority
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                         (read (assoc context :principal (if-let [service (:bel/service context)]
                                                                           {:service service}
                                                                           {:user-id     (:user-id context)
                                                                            :memberships (:memberships context)}))))]
    (f)))


(use-fixtures :each bel-test/isolated-registry register unit-authority)


(def workspace (:workspace-id support/scope))
(def scope {:workspace-id workspace})
(def member #uuid "00000000-0000-0000-0000-0000000000b1")
(def c "00000000-0000-0000-0000-00000000c001")
(def r "00000000-0000-0000-0000-00000000f001")
(def all-domains connector/domains)


(defn t
  {:malli/schema [:=> [:cat :string] validation/Instant]}
  [text]
  (Instant/parse text))


(def connection
  {:connection-id    c :workspace-id             (str workspace) :store-id         "s"          :provider "ozon" :provider-account-id "123"
   :account-label    "Shop LLC" :secret-ref      "secret"        :account-currency "RUB"
   :readable-domains (connections/encode-domains all-domains)
   :sync-state       "active" :credentials-state "valid"         :connected-by     (str member)
   :connected-at     "2026-12-01T06:00:00Z"})


(defn run
  "An open run of C that started at `started-at` over every domain."
  {:malli/schema [:=> [:cat :map] :map]}
  [fields]
  (merge {:run-id             r :connection-id                  c      :trigger "schedule" :triggered-by (str "connector:" c)
          :domains            (connections/encode-domains all-domains)
          :starting-positions (json/write-json-str (zipmap ["fbs-postings" "fbo-postings" "returns" "rfbs-returns" "supplies" "removals"]
                                                           (repeat "2026-11-10T06:00:00Z")))
          :started-at         "2026-12-10T06:00:00Z" :run-state "open"}
         fields))


(defn decide
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [command facts input]
  (runtime/decide command (merge {:scope scope :actor member :now (t "2026-12-11T06:00:00Z")} facts) input))


(def as-connector
  "The facts of a command sent by C's connector principal."
  {:actor (parse-uuid c)})


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn nothing?
  "Nothing is recorded."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [plan]
  (empty? (:events plan)))


(defn events
  {:malli/schema [:=> [:cat :map] [:vector :keyword]]}
  [plan]
  (mapv :type (:events plan)))


(defn event
  {:malli/schema [:=> [:cat :map :keyword] [:maybe :map]]}
  [plan type]
  (some #(when (= type (:type %)) (:data %)) (:events plan)))


(defn after
  "A row after a plan's events with the given id key."
  {:malli/schema [:=> [:cat :map :map :keyword] :map]}
  [row plan id-key]
  (reduce #(if (= (get row id-key) (get-in %2 [:data id-key])) (merge %1 (:data %2)) %1) row (:events plan)))


;; Slice 1 · Find connections due for sync


(defn due
  {:malli/schema [:=> [:cat [:sequential :map] :string] [:vector :map]]}
  [connections now]
  (sync/due-rows connections (t now)))


(deftest due-after-24-hours
  (is (= [{:workspace-id (str workspace) :connection-id c :due-since "2026-12-11T06:00:00Z"}]
         (due [(assoc connection :last-completed-sync-at "2026-12-10T06:00:00Z" :run-state "completed")] "2026-12-11T06:01:00Z"))))


(deftest not-yet-due
  (is (= [] (due [(assoc connection :last-completed-sync-at "2026-12-10T06:00:00Z")] "2026-12-11T05:00:00Z"))))


(deftest never-synced
  (is (= ["2026-12-01T06:00:00Z"] (map :due-since (due [connection] "2026-12-01T06:00:00Z"))) "due since it was connected"))


(deftest open-run-after-restart
  (is (= [{:workspace-id (str workspace) :connection-id c :due-since "2026-12-10T06:00:00Z" :open-run r}]
         (due [(assoc connection :run-id r :run-state "open" :started-at "2026-12-10T06:00:00Z"
                      :last-completed-sync-at "2026-12-10T05:00:00Z")]
              "2026-12-10T06:30:00Z"))))


(deftest throttled-or-backing-off
  (is (= [] (due [(assoc connection :resume-not-before "2026-12-11T07:00:00Z")] "2026-12-11T06:00:00Z")))
  (is (= [] (due [(assoc connection :run-state "open" :started-at "2026-12-11T05:00:00Z" :resume-not-before "2026-12-11T07:00:00Z")]
                 "2026-12-11T06:00:00Z")))
  (is (= [] (due [(assoc connection :run-state "failed" :consecutive-failures 2 :next-attempt-at "2026-12-11T06:05:00Z")]
                 "2026-12-11T06:00:00Z")))
  (is (= 1 (count (due [(assoc connection :run-state "failed" :consecutive-failures 2 :next-attempt-at "2026-12-11T06:05:00Z")]
                       "2026-12-11T06:05:00Z")))))


(deftest paused-rejected-or-stopped
  (doseq [c [(assoc connection :sync-state "paused")
             (assoc connection :credentials-state "rejected")
             (assoc connection :run-state "failed" :consecutive-failures 5)]]
    (is (= [] (due [c] "2026-12-20T06:00:00Z")))))


(deftest oldest-first
  (let [c1 (assoc connection :connection-id "c1" :connected-at "2026-12-11T06:00:00Z")
        c2 (assoc connection :connection-id "c2" :connected-at "2026-12-11T05:00:00Z")]
    (is (= ["c2" "c1"] (map :connection-id (due [c1 c2] "2026-12-11T07:00:00Z"))))))


(deftest due-rows-name-the-run-they-follow
  (is (= [{:workspace-id (str workspace) :connection-id c :due-since "2026-12-01T06:00:00Z" :after-run r}]
         (due [(assoc connection :run-id r :run-state "failed" :consecutive-failures 0)] "2026-12-01T06:00:00Z")))
  (let [item    {:workspace-id (str workspace) :connection-id c :due-since "2026-12-01T06:00:00Z"}
        before  (sync/marketplace-sync-scheduler item)
        next    (sync/marketplace-sync-scheduler (assoc item :after-run r))
        command #(runtime/processor-command-id ::sync/marketplace-sync-scheduler %)]
    (is (= next (sync/marketplace-sync-scheduler (assoc item :after-run r))) "repeated ticks keep their identity")
    (is (not= (get-in before [:input :run-id]) (get-in next [:input :run-id])))
    (is (not= (command before) (command next)))))


(deftest scheduler-acts-as-the-connector
  (let [request (sync/marketplace-sync-scheduler {:workspace-id (str workspace) :connection-id c :due-since "2026-12-11T06:00:00Z"})]
    (is (= {:type  :command :id ::sync/start-marketplace-sync :scope scope :actor (parse-uuid c)
            :input {:attempt-id    "schedule/2026-12-11T06:00:00Z" :run-id (sync/run-id-for c "2026-12-11T06:00:00Z" nil)
                    :connection-id c :trigger "schedule"}}
           request))
    (is (= request (sync/marketplace-sync-scheduler {:workspace-id (str workspace) :connection-id c :due-since "2026-12-11T06:00:00Z"}))
        "a repeated tick proposes the same run")))


;; Slice 2 · Start a sync run


(defn start
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (decide ::sync/start-marketplace-sync (merge {:connection connection :positions {}} (when-not (= "member" (:trigger input)) as-connector) facts)
          (merge {:attempt-id "a1" :run-id r :connection-id c :trigger "schedule"} input)))


(deftest scheduled-start
  (let [plan (start {:connection (assoc connection :last-completed-sync-at "2026-12-10T05:00:00Z" :run-state "completed")} {})]
    (is (= {:run-id             r :connection-id                  c      :trigger "schedule" :triggered-by (str "connector:" c)
            :domains            (connections/encode-domains all-domains)
            :starting-positions (json/write-json-str (into (sorted-map) (for [d ["fbs-postings" "fbo-postings" "returns" "rfbs-returns" "removals"]]
                                                                          [d "2026-11-11T06:00:00Z"])))
            :started-at         "2026-12-11T06:00:00Z" :run-state "open"}
           (event plan ::sync/marketplace-sync-started)))))


(deftest sync-now-during-a-run
  (is (nothing? (start {:connection (assoc connection :run-state "open" :run-id "R")} {:trigger "member" :run-id "other"}))
      "nothing is recorded; the member sees R"))


(deftest paused-connection-cannot-sync
  (is (= :connection-paused (rejection #(start {:connection (assoc connection :sync-state "paused")} {:trigger "member"}))))
  (is (= :needs-credentials (rejection #(start {:connection (assoc connection :credentials-state "rejected")} {:trigger "member"}))))
  (is (= :throttled (rejection #(start {:connection (assoc connection :resume-not-before "2026-12-11T07:00:00Z")} {:trigger "member"})))))


(deftest member-of-another-workspace
  (let [policy #(runtime/policy ::sync/sync-actor {:principal %1 :scope scope} {:trigger %2})]
    (is (false? (policy {:memberships [{:workspace-id #uuid "00000000-0000-0000-0000-000000000002" :role :owner}]} "member")))
    (is (true? (policy {:memberships [{:workspace-id workspace :role :member}]} "member")) "any active member")
    (is (true? (policy {:service ::sync/marketplace-sync-scheduler} "schedule")))
    (is (false? (policy {:memberships [{:workspace-id workspace :role :owner}]} "schedule")) "a member never acts as the connector")))


(deftest sync-now-ignores-the-schedule-and-failure-wait
  (let [stopped (assoc connection :run-state "failed" :consecutive-failures 5 :last-completed-sync-at "2026-12-11T05:00:00Z")]
    (is (nothing? (start {:connection stopped} {})) "not due on schedule")
    (is (= (str member) (:triggered-by (event (start {:connection stopped} {:trigger "member"}) ::sync/marketplace-sync-started))))))


(deftest first-run-reads-30-days
  (let [positions (sync/starting-positions all-domains {} (t "2026-12-01T00:00:00Z"))]
    (is (= {"fbs-postings" "2026-11-01T00:00:00Z" "fbo-postings" "2026-11-01T00:00:00Z" "returns" "2026-11-01T00:00:00Z"
            "rfbs-returns" "2026-11-01T00:00:00Z" "removals"     "2026-11-01T00:00:00Z"}
           positions))))


(deftest later-run-overlaps-1-day
  (is (= "2026-12-09T06:00:00Z"
         (get (sync/starting-positions all-domains {"fbs-postings" {:covered-until "2026-12-10T06:00:00Z"}} (t "2026-12-11T06:00:00Z"))
              "fbs-postings"))))


(deftest included-domains-are-the-readable-ones
  (let [plan (start {:connection (assoc connection :readable-domains "products warehouses")} {:trigger "member"})]
    (is (= "warehouses products" (:domains (event plan ::sync/marketplace-sync-started))) "fixed domain order")))


;; Slice 3 · Fetch the next source batch and slice 5 · Check the run's remaining work


(defn item
  {:malli/schema [:=> [:cat :map :map :any] :map]}
  [run positions responses]
  {:workspace-id (str workspace) :run run :positions positions :env (:env (fake/env responses))
   :products     (constantly {(str c ":9001") {:source-id "9001" :identifiers "[{\"type\":\"sku\",\"value\":\"100\",\"active\":true}]"}})})


(declare record complete)


(def finished-before-analytics
  (connections/encode-domains (take-while #(not= "stock-analytics" %) all-domains)))


(deftest key-loses-a-role
  (let [open    (run {:finished-domains finished-before-analytics})
        request (sync/fetch-request (item open {} [{:status 403}]))
        input   (:input request)]
    (is (= [::sync/record-source-observations "stock-analytics" true [] false]
           (into [(:id request)] (map input) [:domain :domain-unavailable :observations :domain-finished])))
    (is (= (parse-uuid c) (:actor request)))
    (let [plan        (record {:run open} input)
          unavailable (after open plan :run-id)]
      (is (= [::sync/source-domain-unavailable] (events plan)) "the position does not move")
      (is (= "fbs-postings" (first (:unfinished-domains (sync/coverage unavailable)))) "continues with fbs-postings")
      (is (= (connections/encode-domains (remove #{"stock-analytics"} all-domains))
             (:readable-domains (after connection plan :connection-id))) "it leaves the connection's readable domains"))))


(deftest credentials-revoked
  (let [request (sync/fetch-request (item (run {}) {} [{:status 401}]))]
    (is (= [::sync/complete-marketplace-sync {:run-id              r  :outcome "failed" :failed-domain "warehouses" :reason "credentials rejected"
                                              :unavailable-domains []}]
           ((juxt :id :input) request)))))


(deftest more-to-fetch
  (let [run     (run {:finished-domains "warehouses products"})
        request (sync/fetch-request (item run {} [(fake/ok {:items [{:sku 100 :product_id 9001}]})]))]
    (is (= ["sku-aliases"] (take 1 (:unfinished-domains (sync/coverage run)))))
    (is (= [::sync/record-source-observations "sku-aliases" ""] [(:id request) (get-in request [:input :domain]) (get-in request [:input :batch-key])]))
    (is (= [{:kind "product-aliases" :source-id "9001" :aliases [{:type "sku" :value "100" :role "source"}]}]
           (get-in request [:input :observations])))))


(deftest all-done
  (let [run (run {:finished-domains (connections/encode-domains (remove #{"supplies"} all-domains)) :unavailable-domains "supplies"})]
    (is (= [] (:unfinished-domains (sync/coverage run))))
    (is (= {:run-id r :outcome "completed" :unavailable-domains ["supplies"]}
           (:input (sync/fetch-request (item run {} [])))) "unavailable is not unfinished")))


(deftest blocked-domain
  (let [finished (connections/encode-domains (take-while #(not= "returns" %) all-domains))
        request  (sync/fetch-request (item (run {:finished-domains finished})
                                           {"returns" {:run-id r :cursor "{:last-id \"7\"}" :finished false}}
                                           [(fake/ok {:returns [{:id 7}] :has_next true})]))]
    (is (= {:run-id r :outcome "failed" :failed-domain "returns" :reason "repeated cursor" :unavailable-domains []}
           (:input request)))))


(deftest throttled-for-20-minutes
  (let [finished (connections/encode-domains (take-while #(not= "returns" %) all-domains))
        position {:run-id r :cursor "{:last-id \"7\"}" :batch-key "" :finished false}
        request  (sync/fetch-request (assoc (item (run {:finished-domains finished}) {"returns" position} [])
                                            :env (:env (fake/env [{:status 429 :headers {"retry-after" "1200"}}]
                                                                 (.toEpochMilli (t "2026-12-10T06:00:00Z"))))))
        input    (:input request)
        plan     (record {:position position} input)]
    (is (= [::sync/record-source-observations "{:last-id \"7\"}" "{:last-id \"7\"}" []]
           [(:id request) (get-in input [:next-position :cursor]) (:batch-key input) (:observations input)]))
    (is (= [::sync/provider-throttled] (events plan)) "the position does not move")
    (let [throttled (after (assoc connection :run-state "open" :started-at "2026-12-10T06:00:00Z") plan :connection-id)]
      (is (= "open" (:run-state throttled)) "R stays open")
      (is (= "2026-12-10T06:20:00Z" (:resume-not-before throttled)) "20 minutes ahead")
      (is (not (connections/due? throttled (t "2026-12-10T06:10:00Z"))))
      (is (connections/due? throttled (t "2026-12-10T06:20:00Z")) "continues after that time"))
    (doseq [[facts type] [[{:connection (assoc connection :sync-state "paused")} :connection-paused]
                          [{:run (run {:run-state "failed"})} :run-not-open]
                          [{:position (assoc position :cursor "{:last-id \"8\"}")} :stale-position]]]
      (is (= type (rejection #(record (merge {:position position} facts) input)))))
    (is (= :validation (rejection #(record {:position position}
                                           (assoc input :observations [{:kind "warehouse" :warehouse-type "fbs" :source-id "1"}]))))
        "set with no observations only")))


(deftest unavailable-leaves-the-readable-domains
  (let [unavailable (fn [marked]
                      (record {:run (run {:unavailable-domains marked}) :connection (assoc connection :readable-domains "products supplies")}
                              {:domain "supplies" :domain-unavailable true}))]
    (is (= [["prices supplies" "products"]]
           (map (comp (juxt :unavailable-domains :readable-domains) :data) (:events (unavailable "prices")))))
    (is (nothing? (unavailable "supplies")) "a repeat records nothing new")))


(deftest another-connections-connector-is-refused
  (let [other {:actor #uuid "00000000-0000-0000-0000-00000000c002"}]
    (is (= :not-permitted (rejection #(record other {}))))
    (is (= :not-permitted (rejection #(record other {:resume-not-before "2026-12-11T06:20:00Z"}))))
    (is (= :not-permitted (rejection #(complete other {:outcome "failed" :failed-domain "returns" :reason "incomplete page"}))))
    (is (= :not-permitted (rejection #(start other {}))))
    (is (some? (start {} {:trigger "member"})) "a member starts as a member")))


(deftest batch-read-before-a-setback-is-kept
  (let [open     (run {:finished-domains (connections/encode-domains (take-while #(not= "fbs-postings" %) all-domains))})
        position {:run-id r :cursor "{:started? true, :pending [\"A\" \"B\" \"C\"]}" :finished false}
        fetch    (fn [answer]
                   (sync/fetch-request
                     (item open {"fbs-postings" position}
                           (fn [_ body]
                             (if (= "A" (:posting_number body))
                               (fake/ok {:result {:posting_number "A" :status "delivering"}})
                               answer)))))
        requests (fetch {:status 429 :headers {"retry-after" "1200"}})
        batch    (get requests 0)
        throttle (get requests 1)
        cursor   (get-in batch [:input :next-position :cursor])]
    (is (= 2 (count requests)))
    (is (= ["A"] (map :source-id (get-in batch [:input :observations]))))
    (is (= "{:started? true, :pending [\"B\" \"C\"]}" cursor))
    (is (= [cursor cursor []] (map #(get-in throttle %) [[:input :batch-key] [:input :next-position :cursor] [:input :observations]]))
        "from the new position")
    (is (some? (get-in throttle [:input :resume-not-before])))
    (is (= {:run-id r :outcome "failed" :failed-domain "fbs-postings" :reason "unexpected provider response" :unavailable-domains []}
           (get-in (fetch {:status 404}) [1 :input])) "the run fails after the batch is recorded")
    (is (true? (get-in (fetch {:status 403}) [1 :input :domain-unavailable])))))


(deftest throttling-that-outlasts-its-tries-keeps-the-run-open
  (let [open     (run {:finished-domains (connections/encode-domains (take-while #(not= "fbs-postings" %) all-domains))})
        position {:run-id r :cursor "{:started? true, :pending [\"A\" \"B\"]}" :finished false}
        fetch    (fn [throttled status]
                   (sync/fetch-request (item open {"fbs-postings" position}
                                             (fn [_ body]
                                               (if (throttled (:posting_number body))
                                                 {:status status}
                                                 (fake/ok {:result {:posting_number "A" :status "delivering"}}))))))]
    (let [{:keys [id input]} (fetch #{"A" "B"} 429)
          plan               (record {:run open :position position} input)]
      (is (= [::sync/record-source-observations (:cursor position) (:cursor position) []]
             [id (:batch-key input) (get-in input [:next-position :cursor]) (:observations input)]))
      (is (= [::sync/provider-throttled] (events plan)) "the run stays open and the position does not move")
      ;; The fake clock starts at 1000 s; the tries wait 3.8 s.
      (is (= "1970-01-01T00:17:43.800Z" (:resume-not-before input))))
    (let [[batch wait] (fetch #{"B"} 429)
          cursor       (get-in batch [:input :next-position :cursor])]
      (is (= [["A"] "{:started? true, :pending [\"B\"]}"] [(map :source-id (get-in batch [:input :observations])) cursor])
          "what was read before it is recorded")
      (is (= [cursor cursor true] [(get-in wait [:input :batch-key]) (get-in wait [:input :next-position :cursor])
                                   (some? (get-in wait [:input :resume-not-before]))])))
    (is (= "retries exhausted" (get-in (fetch #{"A" "B"} 503) [:input :reason])))))


(deftest unreadable-page-fails-the-run
  (let [open   (run {:finished-domains "warehouses"})
        fetch  #(sync/fetch-request (item open {} %))
        failed {:run-id r :outcome "failed" :failed-domain "products" :reason "unexpected provider response" :unavailable-domains []}]
    (testing "a page that cannot be translated"
      (is (= failed (:input (sync/fetch-request (item (run {:finished-domains "warehouses"})
                                                      {"products" {:run-id r :cursor "{:pending [\"9001\"]}" :finished false}}
                                                      [(fake/ok {:items [{:id 9001 :barcodes 5}]})]))))))
    (testing "a position that does not move"
      (with-redefs [ozon/step (fn [& _] {:observations [] :state {:last-id "a"} :finished? false})]
        (is (= "repeated cursor"
               (get-in (sync/fetch-request (item open {"products" {:run-id r :cursor "{:last-id \"a\"}" :finished false}} []))
                       [:input :reason])))))
    (testing "the store and a shutdown are not the provider's fault"
      (is (= ::sync/lookup-failed
             (rejection #(sync/fetch-request (assoc (item (run {:finished-domains "warehouses products"}) {} [])
                                                    :products (fn [] (throw (RuntimeException. "store down"))))))))
      (let [attempts (atom {})
            try-it   #(try (sync/fetch-request (assoc (item (run {:finished-domains "warehouses products"}) {} [])
                                                      :attempts attempts :products (fn [] (throw (RuntimeException. "store down")))))
                           (catch clojure.lang.ExceptionInfo _ nil))]
        (is (= [nil nil nil "retries exhausted"] (map #(get-in % [:input :reason]) (doall (repeatedly 4 try-it))))
            "but it does not go on forever"))
      (with-redefs [ozon/step (fn [& _] (throw (InterruptedException.)))]
        (is (thrown? InterruptedException (fetch [])))))))


(deftest batch-that-cannot-be-recorded-fails-the-run
  (let [attempts (atom {})
        request  #(sync/fetch-request (assoc (item (run {:finished-domains "warehouses"}) {} [(fake/ok {:result {:items [] :last_id ""}})])
                                             :attempts attempts))
        failed   (fn [error] (when-let [f (:catch (request))] (get-in (f error) [:input :reason])))]
    (is (= "unexpected provider response" (failed (ex-info "Payload text" {:type :validation}))))
    (is (= "unexpected provider response" (failed (ex-info "Personal data present" {:type :personal-data-present}))))
    (is (= "incomplete page" (failed (ex-info "Conflicting figures" {:type :conflicting-figures}))))
    (doseq [type [:run-not-open :connection-paused :stale-position :unknown-run :not-permitted]]
      (is (= [nil] (distinct (repeatedly 6 #(failed (ex-info "x" {:type type}))))) "nothing is wrong with the batch"))
    (is (= [nil nil nil "retries exhausted"] (vec (repeatedly 4 #(failed (RuntimeException. "store")))))
        "one position is tried 4 times")
    (when-let [f (:catch (request))]
      (is (= {:run-id r :outcome "failed" :failed-domain "products" :reason "unexpected provider response" :unavailable-domains []}
             (:input (f (ex-info "Payload text" {:type :validation})))) "no payload or error text"))))


(deftest pause-between-batches
  (let [paused (assoc connection :sync-state "paused" :run-state "open")]
    (is (= :connection-paused (rejection #(record {:connection paused} {:domain "warehouses"}))))))


;; Slice 4 · Record source observations


(defn record
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (decide ::sync/record-source-observations
          (merge as-connector {:run (run {}) :connection connection :position nil :existing {}} facts)
          (merge {:run-id       r  :domain          "products" :batch-key "" :next-position {:cursor "{}"} :domain-finished false
                  :observations [] :adapter-version "ozon-1"} input)))


(def p
  (str c ":9001"))


(defn product
  {:malli/schema [:=> [:cat :string] :map]}
  [article]
  {:kind        "product" :source-id "9001" :name "Tee" :archived false
   :identifiers [{:type "product-id" :value "9001"} {:type "article" :value article} {:type "barcode" :value "4600000000017"}]})


(deftest article-renamed
  (let [first  (event (record {} {:observations [(product "A-1")]}) ::sync/external-product-observed)
        second (record {:existing {p first}} {:observations [(product "A-2")]})
        data   (event second ::sync/external-product-observed)]
    (is (= [1 2] [(:revision first) (:revision data)]))
    (is (= [(str p "#1") (str p "#2")] [(:source-revision-id first) (:source-revision-id data)]) "revision 1 stays")
    (is (= [{:type "product-id" :value "9001" :active true} {:type "article" :value "A-2" :active true}
            {:type "barcode" :value "4600000000017" :active true} {:type "article" :value "A-1" :active false}]
           (map #(select-keys % [:type :value :active]) (json/read-json (:identifiers data) :key-fn keyword))))))


(deftest unchanged-product
  (let [first (event (record {} {:observations [(product "A-1")]}) ::sync/external-product-observed)
        plan  (record {:existing {p first}} {:observations [(product "A-1")]})]
    (is (= [::sync/external-product-seen ::sync/source-batch-recorded] (events plan)))
    (is (= "2026-12-11T06:00:00Z" (:last-observed-at (event plan ::sync/external-product-seen))))))


(defn active-bindings
  {:malli/schema [:=> [:cat [:maybe :map]] [:vector :any]]}
  [data]
  (mapv (juxt :value :active) (json/read-json (:identifiers data) :key-fn keyword)))


(def old-row
  "Listing 9001 as an earlier run left it."
  (delay (assoc (event (record {} {:observations [(product "A-1")]}) ::sync/external-product-observed)
                :last-observed-at "2026-12-01T06:00:00Z")))


(deftest full-product-pass-expires-unseen-bindings
  (let [finish #(record {:pass-rows {p @old-row}} (merge {:domain-finished true} %))]
    (is (= [["9001" false] ["A-1" false] ["4600000000017" false]]
           (active-bindings (event (finish {}) ::sync/external-product-bindings-expired)))
        "not seen by this run's complete pass")
    (is (nil? (event (finish {:observations [(product "A-1")]}) ::sync/external-product-bindings-expired))
        "seen in the pass's last batch")
    (is (nil? (event (record {:pass-rows {p @old-row}} {}) ::sync/external-product-bindings-expired))
        "a pass that has not finished expires nothing")))


(deftest expired-listing-seen-again-is-reactivated
  (let [expired (assoc @old-row :identifiers (:identifiers (event (record {:pass-rows {p @old-row}} {:domain-finished true})
                                                                  ::sync/external-product-bindings-expired)))
        plan    (record {:existing {p expired}} {:observations [(product "A-1")]})]
    (is (= [["9001" true] ["A-1" true] ["4600000000017" true]]
           (active-bindings (event plan ::sync/external-product-observed))))))


(deftest full-alias-pass-expires-only-alias-bindings
  (let [aliased (event (record {:existing {p @old-row}}
                               {:observations [{:kind "product-aliases" :source-id "9001" :aliases [{:type "sku" :value "555" :role "source"}]}]})
                       ::sync/external-product-observed)
        row     (assoc aliased :aliases-observed-at "2026-12-01T06:00:00Z" :last-observed-at "2026-12-10T07:00:00Z")
        plan    (record {:pass-rows {p row}} {:domain "sku-aliases" :domain-finished true})]
    (is (= [["9001" true] ["A-1" true] ["4600000000017" true] ["555" false]]
           (active-bindings (event plan ::sync/external-product-bindings-expired))))
    (is (nil? (event (record {:pass-rows {p (assoc row :aliases-observed-at "2026-12-10T06:30:00Z")}}
                             {:domain "sku-aliases" :domain-finished true})
                     ::sync/external-product-bindings-expired))
        "aliases seen in this pass")))


(deftest full-warehouse-pass-expires-unseen-warehouses
  (let [w    (fn [id at] {:external-warehouse-id (str c ":" id) :connection-id c :last-observed-at at :active true})
        plan (record {:pass-rows {(str c ":1") (w 1 "2026-12-01T06:00:00Z") (str c ":2") (w 2 "2026-12-10T06:00:01Z")}}
                     {:domain "warehouses" :domain-finished true})]
    (is (= [{:external-warehouse-id (str c ":1") :active false}]
           (keep #(when (= ::sync/external-warehouse-expired (:type %)) (:data %)) (:events plan))))))


(defn warehouse
  {:malli/schema [:=> [:cat :string :string [:maybe :string]] :map]}
  [type id name]
  {:kind "warehouse" :warehouse-type type :source-id id :name name})


(deftest same-id-two-types-are-two-warehouses
  (is (= ["c:fbs:1" "c:supply-origin:1"] (map #(observations/warehouse-id "c" % "1") ["fbs" "supply-origin"])))
  (let [plan (record {} {:domain "warehouses" :observations [(warehouse "fbs" "1" "A") (warehouse "supply-origin" "1" "A")]})]
    (is (= [[(str c ":fbs:1") "fbs" "1"] [(str c ":supply-origin:1") "supply-origin" "1"]]
           (keep #(when (= ::sync/external-warehouse-observed (:type %))
                    ((juxt :external-warehouse-id :warehouse-type :source-id) (:data %)))
                 (:events plan))))
    (is (= 2 (:found-warehouses (event plan ::sync/source-batch-recorded))))))


(deftest fbo-warehouses-never-expire
  (let [row  #(assoc (event (record {} {:observations [(warehouse % "1" "W")]}) ::sync/external-warehouse-observed)
                     :last-observed-at "2026-12-01T06:00:00Z")
        rows (into {} (map (juxt :external-warehouse-id identity)) (map row ["fbo" "fbs" "rfbs" "supply-origin"]))
        plan (record {:pass-rows rows} {:domain "warehouses" :domain-finished true})]
    (is (= (mapv #(str c ":" %) ["fbs:1" "rfbs:1" "supply-origin:1"])
           (keep #(when (= ::sync/external-warehouse-expired (:type %)) (:external-warehouse-id (:data %))) (:events plan)))
        "a full pass that does not state the FBO warehouse leaves it active")))


(deftest unnamed-fbo-fact-keeps-the-known-name
  (let [named (event (record {} {:observations [(warehouse "fbo" "77" "Hub")]}) ::sync/external-warehouse-observed)
        plan  (record {:existing {(str c ":fbo:77") named}} {:observations [(warehouse "fbo" "77" nil)]})]
    (is (= [::sync/external-warehouse-seen ::sync/source-batch-recorded] (events plan)) "a blank states no new name")))


(deftest fbo-posting-handed-over
  (let [hub     {:type "fbo" :id "77" :name "Hub"}
        posting {:kind           "operation" :operation-kind "fbo-posting" :source-id "P" :status "delivering" :terminal false :complete false
                 :from-warehouse hub
                 :lines          [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "1" :warehouse hub}]}
        plan    (record {} {:domain "fbo-postings" :observations [posting (warehouse "fbo" "77" "Hub")]})
        data    (event plan ::sync/source-operation-observed)
        found   (event plan ::sync/external-warehouse-observed)
        id      (str c ":fbo:77")]
    (is (= id (:external-warehouse-id found)) "observed if new")
    (is (= [id [id]] [(:from-warehouse data) (map :warehouse (json/read-json (:lines data) :key-fn keyword))]))
    (is (= [::sync/source-operation-seen ::sync/external-warehouse-seen ::sync/source-batch-recorded]
           (events (record {:existing {(:source-operation-id data) data id found}}
                           {:domain       "fbo-postings"
                            :observations [(assoc-in posting [:from-warehouse :name] "Hub 2") (warehouse "fbo" "77" "Hub")]})))
        "a warehouse's name is no part of the operation")))


(def fbs-id (str c ":fbs:3"))
(def rfbs-id (str c ":rfbs:3"))


(def fbs-named
  "A complete posting that names warehouse 3, at operation and line level."
  {:kind           "operation" :operation-kind "fbs-posting" :source-id "P" :status "delivered" :terminal true :complete true
   :occurred-at    "2026-12-09T10:00:00Z"
   :from-warehouse {:type "fbs" :id "3"}
   :lines          [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "1" :warehouse {:type "fbs" :id "3"}}]})


(defn observe-posting
  "The events of `fbs-named` over stored `rows`, and the stored posting after them."
  {:malli/schema [:=> [:cat :map [:maybe :map]] [:tuple [:vector :keyword] :map]]}
  [rows row]
  (let [id   (str c ":fbs-posting:P")
        plan (record {:existing (cond-> rows row (assoc id row))} {:domain "fbs-postings" :observations [fbs-named]})]
    [(vec (remove #{::sync/source-batch-recorded} (events plan)))
     (or (event plan ::sync/source-operation-observed) row)]))


(defn stored
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [row]
  [(:revision row) (:from-warehouse row) (mapv :warehouse (json/read-json (:lines row) :key-fn keyword)) (:complete row)
   (some-> (:missing row) json/read-json)])


(deftest rfbs-posting-names-the-listed-warehouse
  (let [from (fn [rows] (:from-warehouse (second (observe-posting rows nil))))
        on   {:active true}
        off  {:active false}]
    (is (= fbs-id (from {fbs-id on})))
    (is (= rfbs-id (from {rfbs-id on})))
    (is (= rfbs-id (from {rfbs-id off})) "an expired row is still the listed warehouse")
    (is (= rfbs-id (from {fbs-id off rfbs-id on})) "the active row first")
    (is (= fbs-id (from {fbs-id on rfbs-id off})))
    (is (= fbs-id (from {fbs-id on rfbs-id on})))
    (is (= fbs-id (from {fbs-id off rfbs-id off})))
    (is (nil? (from {})))))


(deftest warehouse-list-change-alone-revises-nothing
  (let [[_ first-seen] (observe-posting {fbs-id {:active true}} nil)]
    (is (= [1 fbs-id [fbs-id] true nil] (stored first-seen)))
    (doseq [rows [{fbs-id {:active true} rfbs-id {:active true}}
                  {fbs-id {:active false} rfbs-id {:active true}}
                  {fbs-id {:active false}}]]
      (is (= [[::sync/source-operation-seen] first-seen] (observe-posting rows first-seen)))))
  (let [[_ first-seen] (observe-posting {rfbs-id {:active true}} nil)]
    (is (= [[::sync/source-operation-seen] first-seen]
           (observe-posting {fbs-id {:active true} rfbs-id {:active false}} first-seen)))))


(deftest unlisted-warehouse-is-recovered-once
  (let [[types unlisted] (observe-posting {} nil)]
    (is (= [::sync/source-operation-observed] types))
    (is (= [1 nil [nil] false ["warehouse"]] (stored unlisted)))
    (is (= [[::sync/source-operation-seen] unlisted] (observe-posting {} unlisted)) "still unlisted")
    (doseq [id [fbs-id rfbs-id]]
      (let [[types listed] (observe-posting {id {:active true}} unlisted)]
        (is (= [::sync/source-operation-observed] types))
        (is (= [2 id [id] true nil] (stored listed)))
        (is (= (:payload-hash unlisted) (:payload-hash listed)) "the provider stated nothing new")
        (is (= [[::sync/source-operation-seen] listed] (observe-posting {id {:active true}} listed)))
        (is (= [[::sync/source-operation-seen] listed]
               (observe-posting {fbs-id {:active false} rfbs-id {:active true}} listed)))))))


(defn posting-in
  "FBO posting P in `status`, handed over at `fact`, as translated."
  {:malli/schema [:=> [:cat :string [:maybe :string]] :map]}
  [status fact]
  (ozon/fbo-posting {"posting_number" "P"                 "status"   status "fact_delivery_date" fact
                     "analytics_data" {"warehouse_id" 77} "products" [{"sku" 100 "quantity" 1}]}))


(defn revise
  "Rows and event types after `observation` is recorded over `rows`."
  {:malli/schema [:function [:=> [:cat :map :map] :any] [:=> [:cat :map :map :string] :any]]}
  ([rows observation] (revise rows observation ozon/adapter-version))
  ([rows observation adapter]
   (let [plan (record {:existing rows} {:domain "fbo-postings" :observations [observation] :adapter-version adapter})
         data (event plan ::sync/source-operation-observed)]
     [(cond-> rows data (assoc (:source-operation-id data) data))
      (vec (remove #{::sync/source-batch-recorded} (events plan)))])))


(defn after-revision
  "The stored operation after these observations in turn."
  {:malli/schema [:=> [:cat [:* :map]] :map]}
  [& observations]
  (val (first (reduce #(first (revise %1 %2)) {} observations))))


(def moment
  "2026-11-26T10:00:00Z")


(deftest handover-survives-cancellation
  (let [handed    (posting-in "delivering" moment)
        cancelled (posting-in "cancelled" nil)
        row       (after-revision handed cancelled)]
    (is (= [2 moment true nil "cancelled"] ((juxt :revision :occurred-at :complete :missing :status) row)))
    (is (= row (after-revision handed cancelled cancelled cancelled)) "the same payload adds no revision")
    (is (= (:payload-hash row) (:payload-hash (after-revision cancelled))) "hashed as the provider stated it")
    (is (= [nil false "[\"actual time\"]"] ((juxt :occurred-at :complete :missing) (after-revision cancelled))))
    (is (= [3 moment true] ((juxt :revision :occurred-at :complete)
                            (after-revision handed cancelled (assoc cancelled :planned-at "2026-11-25T10:00:00Z")))))
    (is (= moment (:occurred-at (after-revision handed (posting-in "delivering" nil) cancelled)))
        "also when an earlier revision already stopped stating it")
    (is (= [nil false] ((juxt :occurred-at :complete) (after-revision handed (posting-in "delivering" nil))))
        "only cancelled revisions keep it")
    (let [id (str c ":fbo-posting:P")]
      (is (nil? (:occurred-at (get (first (revise {id {:source-operation-id id           :revision    1      :payload-hash "x" :adapter-version "ozon-1"
                                                       :status              "delivering" :occurred-at moment}} cancelled)) id)))
          "a time no translation of handover observed is not kept"))
    (is (= "2026-11-27T10:00:00Z" (:occurred-at (after-revision handed (posting-in "cancelled" "2026-11-27T10:00:00Z"))))
        "a stated time wins")
    (is (= ["[\"line quantity\"]" false]
           ((juxt :missing :complete) (after-revision handed (assoc cancelled :missing ["actual time" "line quantity"])))))))


(deftest fbs-handover-survives-cancellation
  (let [posting #(ozon/fbs-posting {"posting_number" "P" "status" %1 "fact_delivery_date" %2 "delivery_method" {"warehouse_id" 3}
                                    "products"       [{"sku" 100 "quantity" 1}]})
        [rows]  (revise {} (posting "delivering" moment))
        [rows]  (revise rows (posting "cancelled" nil))
        id      (str c ":fbs-posting:P")]
    (is (= [moment false "[\"warehouse\"]"] ((juxt :occurred-at :complete :missing) (rows id))))
    (let [[listed types] (revise (assoc rows fbs-id {:active true}) (posting "cancelled" nil))]
      (is (= [::sync/source-operation-observed] types))
      (is (= [3 moment true nil fbs-id] ((juxt :revision :occurred-at :complete :missing :from-warehouse) (listed id))))
      (is (= [::sync/source-operation-seen] (second (revise listed (posting "cancelled" nil))))))))


(deftest new-adapter-observes-an-unchanged-operation-once
  (let [posting       (posting-in "delivering" moment)
        id            (str c ":fbo-posting:P")
        [old]         (revise {} posting "ozon-1")
        [again first] (revise old posting)
        [_ second]    (revise again posting)]
    (is (= "ozon-2" ozon/adapter-version))
    (is (= [[::sync/source-operation-observed] [::sync/source-operation-seen]] [first second]))
    (is (= [2 "ozon-2" (:payload-hash (old id))] ((juxt :revision :adapter-version :payload-hash) (again id))))))


(deftest operation-evidence-is-recorded
  (let [ret     (after-revision (ozon/fbo-fbs-return {"id"                  7 "schema" "Fbo"
                                                      "compensation_status" {"status" {"sys_name" "Sent"}}
                                                      "product"             {"sku" 100 "quantity" 1 "price" {"price" 9.50M "currency_code" "RUB"}}}))
        removal (after-revision (ozon/removal {"return_id" 7 "utilization_date" moment} "from-stock"))]
    (is (= ["fbo" true nil] ((juxt :return-schema :compensated :disposed) ret)))
    (is (= [["9.50" "RUB"]] (map (juxt :price :price-currency) (json/read-json (:lines ret) :key-fn keyword))))
    (is (= [nil nil true] ((juxt :return-schema :compensated :disposed) removal)))))


(deftest same-barcode-under-two-connections
  (let [one (event (record {} {:observations [(product "A-1")]}) ::sync/external-product-observed)
        two (event (record {:run (run {:connection-id "c2"}) :actor (sync/connector-actor "c2")} {:observations [(product "A-1")]}) ::sync/external-product-observed)]
    (is (= [p "c2:9001"] [(:external-product-id one) (:external-product-id two)]) "one binding per connection")))


(deftest repeated-batch
  (is (nothing? (record {:position {:run-id r :batch-key "abc" :cursor "{:last-id \"x\"}"}} {:batch-key "abc"})))
  (is (= :stale-position (rejection #(record {:position {:run-id r :batch-key "abc" :cursor "{:last-id \"x\"}"}} {:batch-key "zzz"})))))


(deftest totals-and-analytics
  (let [plan   (record {:existing {p {:external-product-id p}}}
                       {:domain       "stock-totals"
                        :observations [{:kind "stock" :source "fulfilment-totals" :product-source-id "9001" :fulfilment-type "fbo" :present 10}
                                       {:kind "stock" :source "warehouse-analytics" :product-source-id "9001" :warehouse {:type "fbo" :id "1"} :present 6}
                                       {:kind "stock" :source "warehouse-analytics" :product-source-id "9001" :warehouse {:type "fbo" :id "2"} :present 4}]})
        stocks (filter #(= ::sync/source-stock-observed (:type %)) (:events plan))]
    (is (= [10 6 4] (map (comp :present :data) stocks)) "no figure of 20 is derived")
    (is (= 3 (count (distinct (map (comp :stock-id :data) stocks)))))))


(defn figure
  {:malli/schema [:=> [:cat :map] :map]}
  [fields]
  (merge {:kind "stock" :source "warehouse-stock" :product-source-id "9001" :warehouse {:type "fbo" :id "77"} :present 10} fields))


(defn figures
  "The figures of one batch over stored `rows`, by id."
  {:malli/schema [:=> [:cat :map [:vector :map] :map] [:map-of :string :map]]}
  [rows observations facts]
  (into {} (comp (filter #(= ::sync/source-stock-observed (:type %))) (map (juxt (comp :stock-id :data) :data)))
        (:events (record (merge {:existing (merge {p {:external-product-id p}} rows)} facts)
                         {:domain "stock-by-warehouse" :observations observations :run-id (get-in facts [:run :run-id] r)}))))


(deftest figure-ids
  (is (= {(str p "|warehouse-stock|" c ":fbo:77|" r)  ["fbo" r]
          (str p "|warehouse-stock|" c ":fbs:77|" r)  ["fbs" r]
          (str p "|warehouse-stock|" c ":rfbs:3|" r)  ["rfbs" r]
          (str p "|warehouse-analytics|" c ":fbo:77") ["fbo" r]
          (str p "|fulfilment-totals|fbp")            ["fbp" r]}
         (update-vals (figures {rfbs-id {:active true}}
                               [(figure {}) (figure {:warehouse {:type "fbs" :id "77" :name "X"}}) (figure {:warehouse {:type "fbs" :id "3"}})
                                (figure {:source "warehouse-analytics"})
                                (dissoc (figure {:source "fulfilment-totals" :fulfilment-type "fbp"}) :warehouse)] {})
                      (juxt :fulfilment-type :run-id)))
      "an unlisted FBS warehouse keeps its figure under fbs; a listed rFBS one is told from the list"))


(deftest two-unlisted-warehouses-are-two-figures
  (is (= {(str c ":fbs:8") 1 (str c ":fbs:9") 2}
         (into {} (map (juxt :external-warehouse-id :present))
               (vals (figures {} [(figure {:warehouse {:type "fbs" :id "8"} :present 1})
                                  (figure {:warehouse {:type "fbs" :id "9"} :present 2})] {}))))))


(deftest reserved-is-inside-present
  (is (= [[10 3]] (map (juxt :present :reserved) (vals (figures {} [(figure {:reserved 3})] {}))))))


(deftest skus-of-one-listing-add-up-in-their-warehouse
  (let [one   (figure {:part "100" :present 10 :reserved 3})
        two   (figure {:part "101" :present 4})
        value #(mapv (juxt :present :reserved) (vals %))
        first (figures {} [one] {})]
    (is (= [[14 3]] (value (figures {} [one two] {}))) "in one page")
    (is (= [[14 3]] (value (figures first [two] {}))) "over two pages")
    (is (= [[10 3]] (value (figures first [one] {}))) "a row stated twice counts once")
    (is (= [[14 3]] (value (figures (figures first [two] {}) [one] {}))))
    (let [analytics (figure {:source "warehouse-analytics" :part "100" :present 6})
          before    (figures {} [analytics] {})]
      (is (= [[4 nil]] (value (figures before [(assoc analytics :part "101" :present 4)] {:run (run {:run-id "R2"})})))
          "the latest figure only: an earlier run's parts do not add"))))


(deftest contradicting-stock-rows-over-two-pages
  (let [row     {:sku 100 :product_id 9001 :warehouse_id 77 :present 10 :reserved 3}
        stored  (figures {} [(figure {:part "100" :present 10 :reserved 3})] {})
        domains (connections/encode-domains (take-while #(not= "stock-by-warehouse" %) all-domains))
        fetch   (fn [stored row]
                  (sync/fetch-request
                    (assoc (item (run {:finished-domains domains}) {"stock-by-warehouse" {:run-id r :finished false :cursor "{:phase :fbo :pending [\"100\"] :cursor \"c1\"}"}}
                                 [(fake/ok {:has_next false :products [row]})])
                           :figures (constantly stored))))
        failed  {:run-id r :outcome "failed" :failed-domain "stock-by-warehouse" :reason "incomplete page" :unavailable-domains []}]
    (is (= failed (:input (fetch stored (assoc row :present 11)))))
    (is (= failed (:input (fetch stored (assoc row :reserved 4)))))
    (doseq [[stored row] [[stored row] [{} (assoc row :present 11)] [(update-vals stored #(assoc % :run-id "R0")) (assoc row :present 11)]]]
      (is (= ::sync/record-source-observations (:id (fetch stored row)))))
    (is (= :conflicting-figures (rejection #(figures stored [(figure {:part "100" :present 11 :reserved 3})] {})))
        "nothing is recorded either when a batch reaches the command")))


(deftest cost-is-a-listing-fact
  (let [observe (fn [row o] (record {:existing {p row}} {:domain "prices" :observations [o]}))
        seen    #(event % ::sync/external-product-observed)
        entered {:kind "product-cost" :source-id "9001" :cost "350.10" :cost-currency "RUB"}
        blank   {:kind "product-cost" :source-id "9001"}
        priced  (seen (observe @old-row entered))]
    (is (= [2 "350.10" "RUB" "Tee" (:identifiers @old-row)] ((juxt :revision :cost :cost-currency :name :identifiers) priced)))
    (is (= [::sync/external-product-seen ::sync/source-batch-recorded] (events (observe priced entered))))
    (is (= ["350.10" "RUB"] ((juxt :cost :cost-currency) (seen (observe priced (product "A-2"))))) "the other views keep it")
    (is (= [3 nil nil] ((juxt :revision :cost :cost-currency) (seen (observe priced blank)))) "no longer entered")
    (is (= [::sync/external-product-seen ::sync/source-batch-recorded] (events (observe @old-row blank))) "no cost, no revision")
    (is (= [::sync/source-batch-recorded] (events (record {} {:domain "prices" :observations [entered]})))
        "an unknown listing has no cost")))


(deftest cost-does-not-reactivate-bindings
  (let [expired (assoc @old-row :identifiers (:identifiers (event (record {:pass-rows {p @old-row}} {:domain-finished true})
                                                                  ::sync/external-product-bindings-expired)))
        observe (fn [o] (record {:existing {p expired}} {:domain "prices" :observations [o]}))]
    (is (= (:identifiers expired)
           (:identifiers (event (observe {:kind "product-cost" :source-id "9001" :cost "350" :cost-currency "RUB"})
                                ::sync/external-product-observed))))
    (is (= [::sync/external-product-seen ::sync/source-batch-recorded] (events (observe {:kind "product-cost" :source-id "9001"}))))))


(deftest definitions-say-whether-they-separate-variants
  (let [definition {:kind     "attribute-definition" :category-id "200" :type-id            "300" :attribute-id "85" :name "Size" :value-type "string"
                    :multiple false                  :complex     false :separates-variants true}
        data       (event (record {} {:observations [definition]}) ::sync/attribute-definition-observed)]
    (is (true? (:separates-variants data)))
    (is (= [2 false] ((juxt :revision :separates-variants)
                      (event (record {:existing {(:field-definition-id data) data}} {:observations [(assoc definition :separates-variants false)]})
                             ::sync/attribute-definition-observed))))))


(deftest first-coverage-is-set-once
  (let [first-covered (fn [domain position]
                        (:first-covered-at (event (record {:position position} {:domain domain :domain-finished true}) ::sync/source-domain-traversed)))]
    (is (= "2026-11-10T06:00:00Z" (first-covered "fbs-postings" nil)) "the window start of a dated domain")
    (is (= "2026-12-10T06:00:00Z" (first-covered "stock-by-warehouse" nil)) "the run start of a full one")
    (is (= "2026-12-10T06:00:00Z" (first-covered "supplies" nil)) "supplies are read in full")
    (is (= "2026-10-01T00:00:00Z" (first-covered "fbs-postings" {:first-covered-at "2026-10-01T00:00:00Z"})))
    (is (nil? (event (record {} {:domain "fbs-postings"}) ::sync/source-domain-traversed)) "nothing before the domain finished")))


(deftest no-row-in-a-finished-pass-is-zero
  (let [rows  (vec (vals (figures {} [(figure {:present 10 :reserved 3})] {})))
        pass  {:run-id r :observed-at "2026-12-11T06:00:00Z" :asked #{p (str c ":9002")}}
        value #(sync/stated-present %1 rows %2 %3)]
    (is (= 10 (value pass p (str c ":fbo:77"))))
    (is (= 0 (value pass p (str c ":fbo:78"))))
    (is (= 0 (value pass (str c ":9002") (str c ":fbo:77"))))
    (is (nil? (value pass (str c ":9003") (str c ":fbo:77"))) "the pass did not ask for it")
    (is (nil? (value nil p (str c ":fbo:78"))) "unfinished")
    (is (nil? (value nil p (str c ":fbo:77"))) "figures of an unfinished pass state nothing")
    (is (= 0 (value (assoc pass :run-id "R2") p (str c ":fbo:77"))) "another pass's figure is not its own")))


(deftest finished-stock-pass-names-the-listings-it-asked-for
  (let [sku  {:identifiers "[{\"type\":\"sku\",\"value\":\"100\",\"active\":true}]"}
        gone {:identifiers "[{\"type\":\"sku\",\"value\":\"101\",\"active\":false},{\"type\":\"article\",\"value\":\"A\",\"active\":true}]"}
        rows {p (assoc sku :source-id "9001") (str c ":9002") (assoc gone :source-id "9002")}
        data (fn [domain finished] (event (record {:pass-rows rows} {:domain domain :domain-finished finished}) ::sync/source-domain-traversed))]
    (is (= "[\"9001\"]" (:asked (data "stock-by-warehouse" true))))
    (is (nil? (:asked (data "stock-analytics" true))))
    (is (nil? (data "stock-by-warehouse" false)))))


(deftest zero-is-not-invented-for-another-candidate
  (let [listing (fn [id & skus]
                  [(str c ":" id) {:external-product-id (str c ":" id) :source-id id
                                   :identifiers         (json/write-json-str (for [s skus, role ["primary" "source"]]
                                                                               {:type "sku" :value s :role role :active true}))}])
        rows    (into {} [(listing "9001" "100" "101") (listing "9002" "100") (listing "9003" "100") (listing "9004" "100")])
        data    (fn [facts observations]
                  (json/read-json (:asked (event (record (merge {:pass-rows rows :existing rows} facts)
                                                         {:domain       "stock-by-warehouse" :domain-finished true
                                                          :observations observations})
                                                 ::sync/source-domain-traversed))))]
    (is (= ["9001"] (data {} [])) "sole candidate of SKU 101; SKU 100 speaks for none of its candidates")
    (is (= ["9001" "9002" "9003"] (data {:named #{(str c ":9003")}} [(figure {:product-source-id "9002" :part "100"})]))
        "a row of the last batch or of an earlier one named it")))


(deftest both-candidates-are-read-from-the-store
  (let [products {(str c ":1") {:source-id "1" :identifiers "[{\"type\":\"sku\",\"value\":\"100\",\"active\":true}]"}
                  (str c ":2") {:source-id "2" :identifiers "[{\"type\":\"sku\",\"value\":\"100\",\"active\":true,\"role\":\"historical\"}]"}}
        domains  (connections/encode-domains (take-while #(not= "stock-by-warehouse" %) all-domains))
        request  (sync/fetch-request (assoc (item (run {:finished-domains domains}) {}
                                                  [(fake/ok {:has_next false :products [{:sku 100 :product_id 1 :warehouse_id 77 :present 1}
                                                                                        {:sku 100 :product_id 2 :warehouse_id 77 :present 2}]})])
                                            :products (constantly products)))]
    (is (= [["1" 1] ["2" 2]]
           (map (juxt :product-source-id :present) (filter #(= "stock" (:kind %)) (get-in request [:input :observations])))))))


(deftest buyer-data-in-a-batch
  (is (= :personal-data-present
         (rejection #(record {} {:domain       "fbs-postings"
                                 :observations [{:kind     "operation" :operation-kind "fbs-posting" :source-id "1" :status  "delivering"
                                                 :terminal false       :complete       false         :lines     []  :address {:city "Moscow"}}]})))))


(deftest posting-without-handover-time-is-recorded-incomplete
  (let [data (event (record {} {:domain       "fbs-postings"
                                :observations [{:kind     "operation" :operation-kind "fbs-posting" :source-id "123-0001-1"    :status "delivering"
                                                :terminal false :complete             false         :missing   ["actual time"]
                                                :lines    [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "2"}]}]})
                    ::sync/source-operation-observed)]
    (is (= [false "[\"actual time\"]" "fbs-posting"] ((juxt :complete :missing :kind) data)))))


(deftest domain-finished-advances-coverage
  (let [plan (record {:position {:covered-until "2026-12-09T06:00:00Z"}} {:domain-finished true})]
    (is (= {:run-id       r :connection-id c :domain "products" :covered-until "2026-12-10T06:00:00Z"
            :traversed-at "2026-12-11T06:00:00Z" :position-id      (str c "/products")    :finished-domains "products"
            :pass-id      (str r "/products")    :first-covered-at "2026-12-10T06:00:00Z"}
           (event plan ::sync/source-domain-traversed)))
    (is (= "2026-12-12T06:00:00Z"
           (:covered-until (event (record {:position {:covered-until "2026-12-12T06:00:00Z"}} {:domain-finished true})
                                  ::sync/source-domain-traversed)))
        "never moves backwards")))


(deftest non-terminal-operations-stay-on-the-refetch-list
  (let [op   (fn [id terminal] {:kind     "operation" :operation-kind "fbs-posting" :source-id id :status "x" :terminal terminal
                                :complete false       :lines          []})
        plan (record {:position {:refetch-ids "[\"old\",\"done\"]"}} {:domain "fbs-postings" :observations [(op "new" false) (op "done" true)]})]
    (is (= "[\"old\",\"new\"]" (:refetch-ids (event plan ::sync/source-batch-recorded))))))


(deftest non-terminal-returns-are-refetched-by-posting
  (let [ret  (fn [id posting status terminal]
               {:kind  "operation" :operation-kind    "return" :source-id id :status status :terminal terminal :complete terminal
                :lines []          :related-operation (str "fbs-posting:" posting) :refetch-key posting})
        plan (record {:position {:refetch-ids "[\"P-1\",\"P-2\"]"}}
                     {:domain       "returns"
                      :observations [(ret "7" "P-1" "ReceivedBySeller" true) (ret "8" "P-2" "ReceivedBySeller" true)
                                     (ret "9" "P-2" "MovingToSeller" false) (ret "10" "P-3" "MovingToSeller" false)]})]
    (is (= "[\"P-2\",\"P-3\"]" (:refetch-ids (event plan ::sync/source-batch-recorded)))
        "P-1 reached a terminal state; P-2 still has a return on the way")))


(deftest supplies-of-one-order-are-refetched-apart
  (let [op  (fn [id terminal] {:kind     "operation" :operation-kind "supply" :source-id   id            :status "x" :terminal terminal
                               :complete false       :lines          []       :refetch-key (str "5/" id)})
        ids (fn [previous observations]
              (:refetch-ids (event (record {:position {:refetch-ids previous}} {:domain "supplies" :observations observations})
                                   ::sync/source-batch-recorded)))]
    (is (= ["5/52"] (json/read-json (ids (ids nil [(op "52" false)]) [(op "51" true)]))) "in whichever batch each is read")))


(deftest unread-supplies-of-an-order-are-kept-for-the-next-run
  (is (= ["4/41" "5/52"]
         (json/read-json (:refetch-ids (event (record {:position {:refetch-ids "[\"4/41\"]"}}
                                                      {:domain "supplies" :next-position {:cursor "{}" :pending-ids ["5/52"]}})
                                              ::sync/source-batch-recorded)))))
  (let [finished (connections/encode-domains (remove #{"supplies"} all-domains))
        request  (sync/fetch-request
                   (item (run {:finished-domains finished}) {"supplies" {:run-id r :cursor "{:pending [\"5\"]}" :finished false}}
                         (fn [path _]
                           (fake/ok (case path
                                      "/v3/supply-order/get" {:orders [{:order_id 5 :state "COMPLETED"
                                                                        :supplies [{:supply_id 51} {:supply_id 52}]}]}
                                      "/v1/supply-order/act/product/get" {:supply_acts []})))))]
    (is (= ["5/52"] (get-in request [:input :next-position :pending-ids])))))


(deftest unreadable-position-ends-the-run
  (doseq [position [{:cursor "{:last-id"} {:cursor "#secret/tag 1"} {:cursor "{}" :refetch-ids "[\"x"}]]
    (let [attempts (atom {})
          try-it   #(try (sync/fetch-request (assoc (item (run {:finished-domains "warehouses"})
                                                          {"products" (merge {:run-id r :finished false} position)} [])
                                                    :attempts attempts))
                         (catch Exception _ nil))]
      (is (= [nil nil nil {:run-id r :outcome "failed" :failed-domain "products" :reason "retries exhausted" :unavailable-domains []}]
             (map :input (doall (repeatedly 4 try-it))))))))


(deftest open-removals-widen-the-window
  (let [finished (connections/encode-domains (remove #{"removals"} all-domains))
        from     (fn [refetch]
                   (let [{:keys [env calls]} (fake/env [(fake/ok {:returns_summary_report_rows [] :last_id ""})])]
                     (sync/fetch-request (assoc (item (run {:finished-domains finished})
                                                      {"removals" {:run-id           "R0"                   :cursor "{}" :finished true :refetch-ids refetch
                                                                   :first-covered-at "2026-10-20T06:00:00Z"}} [])
                                                :env env))
                     (:date_from (:body (first @calls)))))]
    (is (= "2026-10-19" (from "[\"from-stock/1\"]")))
    (is (= "2026-11-09" (from "[]")))))


;; Slice 6 · Finish the sync run


(defn complete
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (decide ::sync/complete-marketplace-sync (merge as-connector {:run (run {}) :connection connection :positions {}} facts)
          (merge {:run-id r} input)))


(deftest all-domains-finished
  (let [plan (complete {:run (run {:finished-domains (connections/encode-domains all-domains) :found-listings 3})} {:outcome "completed"})
        data (event plan ::sync/marketplace-sync-completed)]
    (is (= [::sync/marketplace-sync-completed] (events plan)))
    (is (= ["2026-12-12T06:00:00Z" 3 0] ((juxt :next-sync-at :found-listings :consecutive-failures) data)))))


(deftest completing-with-unfinished-domains
  (is (= :domains-unfinished (rejection #(complete {} {:outcome "completed"})))))


(deftest outage-on-returns-after-products-finished
  (let [plan (complete {:positions {"products" {:covered-until "2026-12-10T06:00:00Z"}}}
                       {:outcome "failed" :failed-domain "returns" :reason "retries exhausted"})
        data (event plan ::sync/marketplace-sync-failed)]
    (is (= ["returns" "retries exhausted" "{\"products\":\"2026-12-10T06:00:00Z\"}" 1 "2026-12-11T06:01:00Z"]
           ((juxt :failed-domain :reason :saved-positions :consecutive-failures :next-attempt-at) data)))
    (is (nil? (event plan ::sync/source-domain-traversed)) "returns coverage does not move")))


(deftest unexpected-provider-response
  (let [finished (connections/encode-domains (take-while #(not= "returns" %) all-domains))
        request  (sync/fetch-request (item (run {:finished-domains finished})
                                           {"returns" {:run-id r :cursor "{:last-id \"7\"}" :finished false}}
                                           [{:status 404}]))
        input    (:input request)]
    (is (= [::sync/complete-marketplace-sync {:run-id              r  :outcome "failed" :failed-domain "returns" :reason "unexpected provider response"
                                              :unavailable-domains []}]
           [(:id request) input])
        "no batch is recorded, so the returns position does not move")
    (let [data (event (complete {} (dissoc input :run-id)) ::sync/marketplace-sync-failed)]
      (is (= [1 "2026-12-11T06:01:00Z"] ((juxt :consecutive-failures :next-attempt-at) data)) "counts toward the failure backoff"))))


(deftest credentials-revoked-mid-run
  (let [plan  (complete {} {:outcome "failed" :failed-domain "fbs-postings" :reason "credentials rejected"})
        after (after (assoc connection :run-state "open") plan :connection-id)]
    (is (= [::sync/marketplace-sync-failed ::connections/connection-credentials-rejected] (events plan)))
    (is (= "rejected" (:credentials-state after)))
    (is (not (connections/due? after (t "2026-12-30T00:00:00Z"))) "no run starts until credentials are replaced")
    (is (= :needs-credentials (rejection #(start {:connection after} {:trigger "member"}))))))


(deftest repeat-of-a-closed-run
  (is (nothing? (complete {:run (run {:run-state "completed"})} {:outcome "completed"}))))


(deftest unknown-run
  (is (= :unknown-run (rejection #(complete {:run nil} {:outcome "completed"})))))


(deftest failure-waits-and-fifth-failure
  (doseq [[n wait] [[0 "2026-12-11T06:01:00Z"] [1 "2026-12-11T06:05:00Z"] [2 "2026-12-11T06:15:00Z"] [3 "2026-12-11T07:00:00Z"]]]
    (is (= wait (:next-attempt-at (event (complete {:connection (assoc connection :consecutive-failures n)}
                                                   {:outcome "failed" :failed-domain "returns" :reason "retries exhausted"})
                                         ::sync/marketplace-sync-failed)))))
  (let [plan    (complete {:connection (assoc connection :consecutive-failures 4)}
                          {:outcome "failed" :failed-domain "returns" :reason "retries exhausted"})
        data    (event plan ::sync/marketplace-sync-failed)
        stopped (after connection plan :connection-id)]
    (is (= [5 nil] ((juxt :consecutive-failures :next-attempt-at) data)))
    (is (= [] (sync/due-rows [stopped] (t "2026-12-30T00:00:00Z"))))))


(deftest sync-now-after-the-stop
  (let [stopped   (assoc connection :run-state "failed" :consecutive-failures 5)
        started   (after stopped (start {:connection stopped} {:trigger "member"}) :connection-id)
        completed (after started (complete {:connection started :run (run {:finished-domains (connections/encode-domains all-domains)})}
                                           {:outcome "completed"})
                         :connection-id)]
    (is (= "open" (:run-state started)))
    (is (= 0 (:consecutive-failures completed)))))


(deftest replacing-credentials-resets-the-failure-count
  (let [stopped (assoc connection :run-state "failed" :consecutive-failures 5 :credentials-state "rejected")
        plan    (decide ::connections/replace-connection-credentials {:connection stopped :sealed? true}
                        {:connection-id c    :client-id        "123"       :verification-outcome "verified" :account-currency "RUB"
                         :secret-ref    "s2" :readable-domains all-domains})]
    (is (connections/due? (after stopped plan :connection-id) (t "2026-12-30T00:00:00Z")))))


(deftest supplies-forbidden-mid-run
  (let [plan (complete {:run (run {:finished-domains    (connections/encode-domains (remove #{"supplies"} all-domains))
                                   :unavailable-domains "supplies"})}
                       {:outcome "completed" :unavailable-domains ["supplies"]})
        data (event plan ::sync/marketplace-sync-completed)]
    (is (= "supplies" (:unavailable-domains data)))
    (is (not (contains? (connections/decode-domains (:readable-domains data)) "supplies")) "listed as unavailable")))


;; Slice 7 · Read sync status and coverage


(defn status
  {:malli/schema [:=> [:cat :map [:maybe :map] :map :boolean] :map]}
  [connection run positions member?]
  (sync/sync-status connection run positions member?))


(deftest run-in-progress
  (let [s (status (assoc connection :run-state "open" :run-id r :started-at "2026-12-10T06:00:00Z")
                  (run {:finished-domains "warehouses products"})
                  {"products" {:covered-until "2026-12-10T06:00:00Z"}} true)]
    (is (= ["warehouses" "products"] (get-in s [:current-run :finished-domains])))
    (is (some #{"returns"} (get-in s [:current-run :unfinished-domains])) "returns still in progress")
    (is (= (t "2026-12-10T06:00:00Z") (some #(when (= "products" (:domain %)) (:covered-until %)) (:covered-until s))))))


(deftest last-run-failed
  (let [failed (run {:run-state "failed" :failed-domain "returns" :reason "retries exhausted" :failed-at "2026-12-11T06:14:00Z"})
        s      (status (assoc connection :run-state "failed" :consecutive-failures 2 :next-attempt-at "2026-12-11T06:15:00Z") failed {} true)]
    (is (= {:failed-domain "returns" :reason "retries exhausted" :failed-at (t "2026-12-11T06:14:00Z")} (:last-failure s)))
    (is (= (t "2026-12-11T06:15:00Z") (:next-sync-at s)))
    (is (not (re-find #"(?i)secret|cursor|api" (pr-str s))) "no provider error text, cursors or secrets")))


(deftest stopped-after-5-failures
  (let [s (status (assoc connection :run-state "failed" :consecutive-failures 5) (run {:run-state "failed"}) {} true)]
    (is (:scheduling-stopped? s))
    (is (:can-sync-now s))
    (is (nil? (:next-sync-at s)))))


(deftest paused-connection-status
  (let [s (status (assoc connection :sync-state "paused") nil {"products" {:covered-until "2026-12-10T06:00:00Z"}} true)]
    (is (false? (:can-sync-now s)))
    (is (some :covered-until (:covered-until s)) "coverage per domain is still shown")))


(deftest unavailable-domain-status
  (let [s (status (assoc connection :readable-domains (connections/encode-domains (remove #{"supplies"} all-domains))) nil
                  {"supplies" {:covered-until "2026-12-10T06:00:00Z"}} true)]
    (is (= {:domain "supplies" :readable? false} (some #(when (= "supplies" (:domain %)) %) (:covered-until s))))))


;; Store run: scheduler to completed coverage over a fake Ozon


(def key-bytes
  (.decode (Base64/getDecoder) (.encodeToString (Base64/getEncoder) (byte-array (range 32)))))


(defn ozon
  "Minimal answers for every domain: one warehouse pair, one listing, one posting."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [path body]
  (fake/ok
    (case path
      "/v2/warehouse/list" {:warehouses [{:warehouse_id 1 :name "FBS 1"}] :has_next false}
      "/v1/warehouse/fbo/seller/list" {:warehouses [{:seller_warehouse_id 2 :seller_warehouse_name "FBO" :is_active true :is_pickup false}]}
      "/v3/product/list" (if (= "" (:last_id body))
                           {:result {:items [{:product_id 9001}] :last_id "abc"}}
                           {:result {:items [] :last_id ""}})
      "/v3/product/info/list" {:items [{:id 9001 :name "Tee" :offer_id "A-1" :sku 100 :barcodes ["4600000000017"]
                                        :description_category_id 200 :type_id 300}]}
      "/v1/product/related-sku/get" {:items [{:sku 100 :product_id 9001}]}
      "/v4/product/info/attributes" {:result [{:id 9001 :attributes [{:id 85 :values [{:value "Brand"}]}]}] :last_id ""}
      "/v1/description-category/attribute" {:result [{:id 85 :name "Brand" :type "String"}]}
      "/v4/product/info/stocks" {:items [{:product_id 9001 :stocks [{:type "fbo" :present 10 :reserved 1}]}] :cursor ""}
      "/v5/product/info/prices" {:items [{:product_id 9001 :price {:net_price 350.5 :currency_code "RUB"}}] :cursor ""}
      "/v1/product/info/stocks-by-warehouse/fbo" {:products [{:product_id 9001 :sku 100 :present 10 :reserved 3 :warehouse_id 77}] :has_next false}
      "/v2/product/info/stocks-by-warehouse/fbs" {:products [{:product_id   9001 :sku            100     :present 4 :reserved 0 :free_stock 4
                                                              :warehouse_id 1    :warehouse_name "FBS 1"}]
                                                  :has_next false}
      "/v1/analytics/stocks" {:items [{:sku 100 :warehouse_id 2 :available_stock_count 6}]}
      "/v4/posting/fbs/list" {:postings [{:posting_number "123-0001-1"}] :has_next false}
      "/v3/posting/fbs/get" {:result {:posting_number "123-0001-1" :status "delivering" :fact_delivery_date "2026-11-26T10:00:00Z" :delivery_method {:warehouse_id 1}
                                      :products       [{:sku 100 :offer_id "A-1" :quantity 2}]}}
      "/v3/posting/fbo/list" {:postings [] :has_next false}
      "/v1/returns/list" {:returns  [{:id       7 :schema "Fbo" :posting_number "F-1" :visual {:status {:sys_name "ReturnedToOzon"}}
                                      :logistic {:final_moment "2026-11-26T10:00:00Z"} :product {:sku 100 :quantity 1}}]
                          :has_next false}
      "/v2/returns/rfbs/list" {:returns []}
      "/v3/supply-order/list" {:order_ids [] :last_id ""}
      ("/v1/removal/from-stock/list" "/v1/removal/from-supply/list") {:returns_summary_report_rows [] :last_id ""})))


(defn context
  {:malli/schema [:=> [:cat :map] :map]}
  [system]
  (runtime/prepare-context {:bel/system  system :workspace-id workspace :user-id member
                            :memberships [{:workspace-id workspace :role :owner}]}))


(def listed-states
  "What a supply list has to ask for."
  #{"DATA_FILLING" "READY_TO_SUPPLY" "ACCEPTED_AT_SUPPLY_WAREHOUSE" "IN_TRANSIT" "ACCEPTANCE_AT_STORAGE_WAREHOUSE"
    "REPORTS_CONFIRMATION_AWAITING" "REPORT_REJECTED" "COMPLETED" "REJECTED_AT_SUPPLY_WAREHOUSE" "CANCELLED" "OVERDUE"})


(def Listed
  "A `cursor` list of products."
  [:map [:limit [:int {:min 1 :max 1000}]] [:cursor [:enum "" "c2"]]
   [:filter [:or [:map [:visibility [:= "ALL"]]] [:map [:product_id [:vector {:min 1 :max 1000} :string]]]]]])


(def Skus
  [:vector {:min 1 :max 1000} :string])


(def Id
  "The reference states no type."
  [:or :int [:re #"\d+"]])


(def Moment
  [:and :string [:fn #(try (Instant/parse %) (catch Exception _ false))]])


(def Day
  [:and [:re #"\d{4}-\d\d-\d\d"] [:fn #(try (LocalDate/parse %) (catch Exception _ false))]])


(def Removals
  "Whole days, a period of at most 3 months (live)."
  [:and
   [:map [:date_from Day] [:date_to Day] [:limit [:int {:min 1 :max 500}]] [:last_id [:= ""]]]
   [:fn (fn [{:keys [date_from date_to]}]
          (let [from (LocalDate/parse date_from) to (LocalDate/parse date_to)]
            (not (or (.isAfter from to) (.isAfter to (.plusMonths from 3))))))]])


(def requests
  "What the account accepts as the JSON body of each call, after the Ozon reference and docs/ozon-source-contracts.md."
  {"/v2/warehouse/list" [:map [:limit [:int {:min 1 :max 200}]] [:cursor {:optional true} [:= ""]]]
   "/v1/warehouse/fbo/seller/list"            [:and :map [:fn empty?]]
   "/v3/product/list" [:map [:filter [:map [:visibility [:= "ALL"]]]] [:limit [:int {:min 1 :max 1000}]] [:last_id [:enum "" "abc"]]]
   ;; One identifier family per request.
   "/v3/product/info/list"                    [:and [:map [:product_id [:vector {:min 1 :max 1000} Id]]] [:fn #(not-any? % [:offer_id :sku])]]
   "/v1/product/related-sku/get"              [:map [:sku [:vector {:min 1 :max 200} Id]]]
   "/v4/product/info/attributes"              [:map [:filter [:map [:visibility [:= "ALL"]]]] [:limit [:int {:min 1 :max 1000}]] [:last_id :string]]
   "/v1/description-category/attribute"       [:map [:description_category_id Id] [:type_id Id] [:language {:optional true} :string]]
   "/v5/product/info/prices"                  Listed
   "/v4/product/info/stocks"                  Listed
   "/v1/product/info/stocks-by-warehouse/fbo" [:map [:limit [:int {:min 1 :max 1000}]] [:cursor [:= ""]] [:skus Skus]]
   "/v2/product/info/stocks-by-warehouse/fbs" [:map [:limit [:int {:min 1 :max 1000}]] [:cursor [:= ""]] [:sku Skus]]
   "/v1/analytics/stocks" [:map [:skus [:vector {:min 1 :max 100} Id]]]
   ;; `offset` is ignored by Ozon: a client that sends it pages wrongly.
   "/v4/posting/fbs/list" [:and
                           [:map [:filter [:map [:since Moment] [:to Moment]
                                           [:last_changed_status_date [:map [:from Moment] [:to Moment]]]]]
                            [:limit [:int {:min 1 :max 100}]] [:cursor [:enum "" "p2"]] [:sort_dir [:= "ASC"]]]
                           [:fn #(not (contains? % :offset))]
                           [:fn {:error/message "PERIOD_IS_TOO_LONG"}
                            (fn [{{:keys [since to]} :filter}]
                              (>= 365 (.toDays (Duration/between (Instant/parse since) (Instant/parse to)))))]]
   "/v3/posting/fbs/get" [:map [:posting_number :string] [:with {:optional true} :map]]
   "/v3/posting/fbo/list" [:and
                           [:map [:filter [:map [:since Moment] [:to Moment]]]
                            [:limit [:int {:min 1 :max 100}]] [:cursor [:enum "" "p2"]] [:sort_dir [:= "ASC"]]]
                           [:fn #(not (contains? % :offset))]]
   "/v2/posting/fbo/get" [:map [:posting_number :string] [:with {:optional true} :map]]
   ;; One date filter.
   "/v1/returns/list" [:map [:limit [:int {:min 1 :max 500}]] [:last_id :int]
                       [:filter [:or
                                 [:map [:posting_numbers [:vector {:min 1 :max 50} :string]]]
                                 [:and [:map [:visual_status_change_moment [:map [:time_from Moment] [:time_to Moment]]]]
                                  [:fn #(= 1 (count %))]]]]]
   "/v2/returns/rfbs/list"                    [:map [:filter [:map [:created_at [:map [:from Moment] [:to Moment]]]]] [:limit [:int {:min 1 :max 500}]]
                                               [:last_id [:enum 0 70]]]
   "/v2/returns/rfbs/get" [:map [:return_id Id]]
   "/v3/supply-order/list"                    [:map [:filter [:map [:states [:fn #(= listed-states (set %))]]]] [:limit [:int {:min 1 :max 100}]]
                                               [:sort_by [:= "ORDER_STATE_UPDATED_AT"]] [:sort_dir [:= "DESC"]] [:last_id [:enum "" "500"]]]
   "/v3/supply-order/get" [:map [:order_ids [:vector {:min 1 :max 50} Id]]]
   "/v1/supply-order/bundle"                  [:map [:bundle_ids [:vector {:min 1} :string]] [:limit [:int {:min 1 :max 100}]] [:last_id [:= ""]]]
   "/v1/supply-order/act/product/get"         [:map [:supply_id :int]]
   "/v1/removal/from-stock/list"              Removals
   "/v1/removal/from-supply/list"             Removals})


(def changed
  "When everything in the account last changed: inside the window of a run started now, the first or a later one."
  (.minus (Instant/now) (Duration/ofHours 2)))


(defn- within?
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [from to]
  (not (or (.isBefore ^Instant changed (Instant/parse from)) (.isAfter ^Instant changed (Instant/parse to)))))


(defn account
  "A small seller account answering every call in the verified shapes of the Ozon reference: 400 for a body outside
  `requests`, 404 for an unknown posting or return, and otherwise what the ids, window and page asked for hold. One
  FBO warehouse, 77, is named by stock, analytics, postings and a supply, and listed nowhere."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [path sent]
  (let [{:keys [cursor last_id filter] :as body} (json/read-json (json/write-json-str sent) :key-fn keyword)
        asked (fn [k row-key rows]
                (let [ids (set (map str (get body k)))] (filterv #(ids (str (get % row-key))) rows)))
        ;; Parts a posting states only when asked for.
        with (fn [posting]
               (apply dissoc posting (remove #(get-in body [:with %]) [:analytics_data :financial_data])))
        postings (fn [from to first-page second-page]
                   (cond (not (within? from to)) {:postings [] :has_next false}
                         (= "" cursor) {:postings (mapv #(hash-map :posting_number %) first-page) :has_next true :cursor "p2"}
                         :else {:postings (mapv #(hash-map :posting_number %) second-page) :has_next false}))
        fbo (fn [status fields]
              (merge {:posting_number (:posting_number body)
                      :status         status
                      :in_process_at  "2026-11-20T08:00:00Z"
                      :analytics_data {:warehouse_id 77 :warehouse_name "KHOROGRAD"}
                      :products       [{:sku 100 :offer_id "A-1" :quantity 1 :price "900.00" :currency_code "RUB"}]
                      :financial_data {:products [{:product_id 100 :price 850.5 :currency_code "RUB" :quantity 1}]}}
                     fields))
        fbs (fn [status fields]
              (merge {:posting_number       (:posting_number body)
                      :status               status
                      :shipment_date        "2026-11-21T08:00:00Z"
                      :tpl_integration_type "ozon"
                      :delivery_method      {:id 5 :warehouse_id 1 :warehouse "FBS 1"}
                      :analytics_data       {:warehouse_id 1 :warehouse "FBS 1"}
                      :products             [{:sku 100 :offer_id "A-1" :quantity 2 :price "900.00" :currency_code "RUB"}]
                      :financial_data       {:products [{:product_id 100 :price 1750 :currency_code "RUB" :quantity 2}]}}
                     fields))
        removal (fn [id fields]
                  {:returns_summary_report_rows
                   (if (not-any? neg? [(compare (subs (str changed) 0 10) (:date_from body)) (compare (:date_to body) (subs (str changed) 0 10))])
                     [(merge {:return_id id :box_id 8 :sku 100 :quantity_for_return 1 :return_state "Done" :given_out_date "" :utilization_date ""}
                             fields)]
                     [])
                   :last_id "r1"})
        of-products (fn [rows]
                      (if-let [ids (:product_id filter)] (filterv #((set ids) (str (:product_id %))) rows) rows))
        listed (fn [item]
                 {:items (if (= "" cursor) (of-products [item]) []) :total_items 1 :cursor (if (= "" cursor) "c2" "")})]
    (if-not (m/validate (get requests path [:fn (constantly false)]) body)
      {:status 400 :body {}}
      (if-let [answer
               (case path
                 "/v2/warehouse/list" {:warehouses [{:warehouse_id 1 :name "FBS 1" :status "created" :is_rfbs false}
                                                    {:warehouse_id 3 :name "rFBS 3" :status "created" :is_rfbs true}]
                                       :has_next   false
                                       :cursor     ""}
                 "/v1/warehouse/fbo/seller/list"
                 {:warehouses [{:seller_warehouse_id 2 :seller_warehouse_name "Origin" :is_active true :is_pickup false}]}
                 "/v3/product/list" (if (= "" last_id)
                                      {:result {:items [{:product_id 9001 :offer_id "A-1"}] :total 1 :last_id "abc"}}
                                      {:result {:items [] :total 1 :last_id ""}})
                 "/v3/product/info/list"
                 {:items (asked :product_id :id
                                [{:id          9001              :name                    "Tee"                  :offer_id "A-1" :sku 100 :sources [{:sku 100 :source "sds"}]
                                  :barcodes    ["4600000000017"] :description_category_id 200                    :type_id  300
                                  :is_archived false             :updated_at              "2026-11-20T08:00:00Z"}])}
                 "/v1/product/related-sku/get" (if (some #{"100" "101"} (map str (:sku body)))
                                                 {:items  [{:sku 100 :product_id 9001 :deleted_at nil}
                                                           {:sku 101 :product_id 9001 :deleted_at "2026-01-01T00:00:00Z"}]
                                                  :errors []}
                                                 {:items [] :errors []})
                 ;; The last page states a last_id too, and asking with it is "item not found" (live).
                 "/v4/product/info/attributes"
                 (when (= "" last_id)
                   {:result  [{:id 9001 :attributes [{:id 85 :complex_id 0 :values [{:dictionary_value_id 0 :value "Brand"}]}] :complex_attributes []}]
                    :last_id "a2"
                    :total   1})
                 "/v1/description-category/attribute"
                 {:result (if (= ["200" "300"] (map str [(:description_category_id body) (:type_id body)]))
                            [{:id                   85    :name                  "Brand" :type            "String" :is_aspect true :is_required true :dictionary_id 0
                              :category_dependent   false :is_collection         false   :max_value_count 1
                              :attribute_complex_id 0     :complex_is_collection false   :group_id        0}]
                            [])}
                 "/v5/product/info/prices" (listed {:offer_id "A-1" :product_id 9001 :price {:currency_code "RUB" :net_price 350.5 :price 900}})
                 "/v4/product/info/stocks"
                 (listed {:offer_id "A-1" :product_id 9001
                          :stocks   [{:present 10 :reserved 3 :sku 100 :type "fbo" :warehouse_ids [] :shipment_type "SHIPMENT_TYPE_GENERAL"}
                                     {:present 4 :reserved 0 :sku 100 :type "fbs" :warehouse_ids [] :shipment_type "SHIPMENT_TYPE_GENERAL"}]})
                 "/v1/product/info/stocks-by-warehouse/fbo"
                 {:products (asked :skus :sku [{:offer_id "A-1" :product_id 9001 :sku 100 :present 10 :reserved 3 :warehouse_id 77}])
                  :has_next false
                  :cursor   ""}
                 "/v2/product/info/stocks-by-warehouse/fbs"
                 {:products (asked :sku :sku [{:offer_id     "A-1" :product_id     9001    :sku 100 :present 4 :reserved 0 :free_stock 4
                                               :warehouse_id 1     :warehouse_name "FBS 1"}])
                  :has_next false
                  :cursor   ""}
                 "/v1/analytics/stocks"
                 {:items (asked :skus :sku [{:sku                   100 :offer_id "A-1" :warehouse_id 77 :warehouse_name "KHOROGRAD" :cluster_id 4
                                             :available_stock_count 7}])}
                 "/v4/posting/fbs/list" (postings (get-in filter [:last_changed_status_date :from]) (get-in filter [:last_changed_status_date :to])
                                                  ["S-1"] ["S-2" "S-3"])
                 "/v3/posting/fbs/get"
                 (some->> (case (:posting_number body)
                            "S-1" (fbs "delivered" {:fact_delivery_date "2026-11-21T10:00:00Z" :delivering_date "2026-11-21T10:00:00Z"})
                            "S-2" (fbs "awaiting_deliver" {:fact_delivery_date nil})
                            "S-3" (fbs "cancelled" {:cancellation {:cancelled_after_ship true}})
                            nil)
                          with (hash-map :result))
                 "/v3/posting/fbo/list" (postings (:since filter) (:to filter) ["F-1"] ["F-2" "F-3"])
                 "/v2/posting/fbo/get"
                 (some->> (case (:posting_number body)
                            "F-1" (fbo "delivering" {:fact_delivery_date "2026-11-21T09:00:00Z"})
                            "F-2" (fbo "awaiting_packaging" {})
                            "F-3" (fbo "cancelled" {})
                            nil)
                          with (hash-map :result))
                 "/v1/returns/list"
                 (let [{{:keys [time_from time_to]} :visual_status_change_moment :keys [posting_numbers]} filter]
                   {:returns  (if (and (< last_id 7) (if posting_numbers (some #{"F-1"} posting_numbers) (within? time_from time_to)))
                                [{:id                  7
                                  :schema              "Fbo"
                                  :posting_number      "F-1"
                                  :visual              {:status {:id 34 :sys_name "ReturnedToOzon"}}
                                  :logistic            {:final_moment "2026-11-24T10:00:00Z"}
                                  :place               {:id 55 :name "Returns centre"}
                                  :target_place        {:id 56 :name ""}
                                  :compensation_status {:status {:id 1 :sys_name "Received"} :change_moment "2026-11-24T10:00:00Z"}
                                  :product             {:sku 100 :offer_id "A-1" :quantity 1}}]
                                [])
                    :has_next false})
                 "/v2/returns/rfbs/list" {:returns (if (and (= 0 last_id) (within? (get-in filter [:created_at :from]) (get-in filter [:created_at :to])))
                                                     [{:return_id 70 :posting_number "S-1"}]
                                                     [])}
                 "/v2/returns/rfbs/get"
                 (when (= "70" (str (:return_id body)))
                   {:returns {:return_id 70 :posting_number "S-1" :state {:state "MoneyReturned"} :product {:sku 100 :offer_id "A-1"}}})
                 "/v3/supply-order/list" (if (= "" last_id) {:order_ids [500] :last_id "500"} {:order_ids []})
                 "/v3/supply-order/get"
                 {:orders (asked :order_ids :order_id
                                 [{:order_id           500
                                   :state              "COMPLETED"
                                   :state_updated_date (str changed)
                                   :dropoff_warehouse  {:warehouse_id 9 :name "Drop-off"}
                                   :order_tags         {:seller_warehouse_id 2}
                                   :supplies           [{:supply_id         600
                                                         :bundle_id         "b"
                                                         :state             "COMPLETED"
                                                         :storage_warehouse {:warehouse_id 77 :name "KHOROGRAD" :arrival_date "2026-11-19T08:00:00Z"}}]}])}
                 "/v1/supply-order/bundle"
                 (let [items (if (= ["b"] (:bundle_ids body)) [{:sku 100 :offer_id "A-1" :product_id 9001 :quantity 5}] [])]
                   {:items items :total_count (count items) :last_id "" :has_next false})
                 "/v1/supply-order/act/product/get"
                 {:supply_id    (:supply_id body)
                  :supply_acts  (if (= 600 (:supply_id body))
                                  [{:act_id                1
                                    :type                  "ACCEPTANCE"
                                    :unidentified_quantity 0
                                    :items                 [{:sku_info          {:sku 100 :offer_id "A-1" :name "Tee"}
                                                             :declared_quantity 5
                                                             :fact_quantity     5
                                                             :approved_quantity 5
                                                             :fact_amount       0
                                                             :approved_amount   0}]}]
                                  [])
                  :skus_defects []}
                 "/v1/removal/from-stock/list" (removal 80 {:given_out_date "2026-11-22T10:00:00Z"})
                 "/v1/removal/from-supply/list" (removal 81 {:utilization_date "2026-11-22T10:00:00Z"}))]
        (fake/ok answer)
        {:status 404 :body {}}))))


(deftest the-account-refuses-wrong-requests
  (is (= ozon/allowlist (set (keys requests))))
  (doseq [path ozon/allowlist]
    (is (= 400 (:status (account path {:wrong 1}))) path)
    (is (= 400 (:status (account path {:limit 5000}))) path))
  (are [path body] (= 400 (:status (account path body)))
    "/v1/supply-order/act/product/get" {:supply_id "600"}
    "/v3/supply-order/get" {:order_ids (vec (range 51))}
    "/v1/product/related-sku/get" {:sku (vec (repeat 201 "100"))}
    "/v3/product/info/list" {:product_id ["9001"] :sku ["100"]}
    "/v1/returns/list" {:limit 500 :last_id "" :filter {:posting_numbers ["F-1"]}}
    "/v1/removal/from-stock/list" {:date_from "2026-11-01T00:00:00Z" :date_to "2026-11-02T00:00:00Z" :limit 500 :last_id ""}
    "/v1/removal/from-stock/list" {:date_from "2026-11-01" :date_to "2026-11-02" :limit 501 :last_id ""}
    "/v1/removal/from-stock/list" {:date_from "2026-08-01" :date_to "2026-11-02" :limit 500 :last_id ""})
  (is (= 404 (:status (account "/v4/product/info/attributes" {:filter {:visibility "ALL"} :limit 1000 :last_id "a2"}))))
  (are [path body expected] (= expected (:body (account path body)))
    "/v3/product/info/list" {:product_id ["9"]} {:items []}
    "/v3/supply-order/get" {:order_ids ["5"]} {:orders []}
    "/v1/supply-order/act/product/get" {:supply_id 6} {:supply_id 6 :supply_acts [] :skus_defects []}
    "/v1/analytics/stocks" {:skus ["9"]} {:items []}
    "/v1/returns/list" {:limit 500 :last_id 0 :filter {:posting_numbers ["F-9"]}} {:returns [] :has_next false}
    "/v2/returns/rfbs/get" {:return_id "7"} {}))


(deftest sync-through-the-store
  (support/with-store
    (fn [repo _]
      (let [calls       (atom [])
            interrupt   (atom nil)
            marketplace (fn []
                          {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {}) :last-look (atom nil) :attempts (atom {})
                           :post   (fn [path body _headers]
                                     (when (= path @interrupt)
                                       (reset! interrupt nil)
                                       (throw (InterruptedException. "the application stops")))
                                     (swap! calls conj path)
                                     (update (account path body) :body json/write-json-str))})
            boot        #(support/bel-system {:repo repo :marketplace (marketplace)})
            system      (boot)
            ctx         (context system)
            in-scope    (assoc ctx :scope scope)
            secret      (connector/store-secret! (marketplace) workspace "api-key")
            read        #(runtime/read-model ctx ::sync/sync-status-and-coverage {:connection-id c})
            row         #(runtime/read-model ctx ::connections/connections {:connection-id c})
            run-row     #(runtime/coeffect ::sync/run in-scope {:run-id (:run-id (row))})
            rows        #(records/all-records in-scope %1 %2)
            products    #(rows ::sync/external-products :external-product-id)
            warehouses  #(rows ::sync/external-warehouses :external-warehouse-id)
            operations  #(rows ::sync/source-operations :source-operation-id)
            fields      #(rows ::sync/field-definitions :field-definition-id)
            tick        #(runtime/run-processors! %)
            finish      (fn [system]
                          (loop [n 120]
                            (when (and (pos? n) (= "open" (:run-state (row)))) (tick system) (recur (dec n)))))
            positions   #(runtime/coeffect ::sync/connection-positions in-scope {:connection-id c})
            recorded    (fn []
                          [(set (rows ::sync/source-revisions :source-revision-id))
                           (for [[read id-key] [[products :external-product-id] [warehouses :external-warehouse-id]
                                                [operations :source-operation-id] [fields :field-definition-id]]]
                             (into {} (map (juxt id-key :revision)) (read)))])
            state       (fn [] [(row) (positions) (recorded) @calls])
            sync-now    (fn [run-id]
                          (runtime/execute! ctx ::sync/start-marketplace-sync
                                            {:command-id run-id :input {:attempt-id run-id :run-id run-id :connection-id c :trigger "member"}}))]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      {:connection-id        c          :store-id         "s"   :provider         "ozon"      :client-id "123" :secret-ref secret
                                        :verification-outcome "verified" :account-currency "RUB" :readable-domains all-domains}})

        (testing "never synced: the scheduler starts a run as the connector"
          (tick system)
          (let [status (read)]
            (is (= "schedule" (get-in status [:current-run :trigger])))
            (is (= (str "connector:" c) (get-in status [:current-run :triggered-by])))))

        (testing "two members press Sync now during the run: nothing new starts"
          (let [run-id (get-in (read) [:current-run :run-id])]
            (sync-now "now-1")
            (sync-now "now-2")
            (is (= run-id (get-in (read) [:current-run :run-id])))))

        (testing "a pause stops the fetcher at the next checkpoint and Resume continues the same run"
          (tick system)
          (runtime/execute! ctx ::connections/pause-marketplace-connection {:command-id "pause-1" :input {:connection-id c}})
          (let [before (state)]
            (dotimes [_ 3] (tick system))
            (is (= before (state)) "reads nothing more; the open run keeps its positions"))
          (runtime/execute! ctx ::connections/resume-marketplace-connection {:command-id "resume-1" :input {:connection-id c}}))

        (testing "an application stopped in the middle resumes the same run where it was"
          (let [run-id (:run-id (row))
                path   "/v2/posting/fbo/get"]
            (reset! interrupt path)
            (loop [n 120] (when (and (pos? n) @interrupt) (tick system) (recur (dec n))))
            (is (nil? @interrupt))
            (is (= "fbo-postings" (first (:unfinished-domains (sync/coverage (run-row))))))
            (is (not-any? #{path} @calls))
            (finish (boot))
            (is (= [run-id "completed"] ((juxt :run-id :run-state) (row))))
            (is (= {"/v2/warehouse/list" 1 "/v3/product/info/list" 1 "/v3/posting/fbs/get" 3 "/v3/posting/fbo/list" 2 path 3}
                   (select-keys (frequencies @calls) ["/v2/warehouse/list" "/v3/product/info/list" "/v3/posting/fbs/get" "/v3/posting/fbo/list" path]))
                "nothing recorded before is read again")))

        (testing "all 15 domains are covered by the one run"
          (let [status (read)]
            (is (= 15 (count all-domains)))
            (is (= ozon/allowlist (set @calls)) "through every call of the board's table")
            (is (nil? (:current-run status)))
            (is (= "completed" (:last-outcome status)))
            (is (= all-domains (map :domain (filter :covered-until (:covered-until status)))))
            (is (= (set all-domains) (set (map :domain (rows ::sync/stock-passes :pass-id)))))
            (is (= {:finished-domains all-domains :unavailable-domains [] :unfinished-domains []}
                   (dissoc (sync/coverage (run-row)) :run-id)))
            (is (= {:listings 1 :warehouses 4 :operations 11}
                   (update-vals {:listings (products) :warehouses (warehouses) :operations (operations)} count)
                   (:found-counts status)))
            (is (some? (:next-sync-at status)))))

        (testing "source records are evidence, stored per connection"
          (let [product (first (products))]
            (is (= [(str c ":9001") 4 "Tee" "350.5" "RUB"] ((juxt :external-product-id :revision :name :cost :cost-currency) product))
                "aliases, attributes and cost each added a revision")
            (is (some #(= {:type "barcode" :value "4600000000017"} (select-keys % [:type :value]))
                      (json/read-json (:identifiers product) :key-fn keyword)))
            (is (= [["85" "Brand" true]] (map (juxt :attribute-id :name :separates-variants) (fields))))
            (is (= #{["fulfilment-totals" "fbo" 10] ["fulfilment-totals" "fbs" 4] ["warehouse-stock" "fbo" 10] ["warehouse-stock" "fbs" 4]
                     ["warehouse-analytics" "fbo" 7]}
                   (set (map (juxt :source :fulfilment-type :present) (rows ::sync/source-stock :stock-id)))))))

        (testing "one FBO warehouse is discovered, which no list states; one row per warehouse identity"
          (let [posting {:kind "operation" :operation-kind "fbs-posting" :source-id "X" :from-warehouse {:type "fbs" :id "1"}}]
            (is (= [["fbo:77" "fbo" "KHOROGRAD" true] ["fbs:1" "fbs" "FBS 1" true]
                    ["rfbs:3" "rfbs" "rFBS 3" true] ["supply-origin:2" "supply-origin" "Origin" true]]
                   (sort-by first (map (juxt #(subs (:external-warehouse-id %) (inc (count c))) :warehouse-type :name :active) (warehouses)))))
            (is (contains? (runtime/coeffect ::sync/existing in-scope {:connection-id c :observations [posting]}) (str c ":fbs:1"))
                "a posting's warehouse is loaded to tell FBS from rFBS")))

        (testing "only a handed-over posting is complete"
          (let [operations (operations)]
            (is (= {"fbs-posting" 3 "fbo-posting" 3 "return" 1 "rfbs-return" 1 "supply" 1 "removal" 2} (frequencies (map :kind operations)))
                "every kind")
            (is (= #{["fbs-posting" "S-1" "2026-11-21T10:00:00Z" "2026-11-21T10:00:00Z" (str c ":fbs:1")]
                     ["fbo-posting" "F-1" "2026-11-21T09:00:00Z" "2026-11-21T09:00:00Z" (str c ":fbo:77")]}
                   (set (map (juxt :kind :source-id :occurred-at :handed-over-at :from-warehouse) (filter :complete operations)))))
            (is (every? #(seq (json/read-json (:missing %))) (remove :complete operations)) "any other says what is missing")
            (is (= [["fbo" true "[\"receiving warehouse\"]"]]
                   (map (juxt :return-schema :compensated :missing) (filter #(= "return" (:kind %)) operations))))))

        (testing "what the details, acts and windows state is recorded"
          (let [line (fn [kind id]
                       (some #(when (= [kind id] [(:kind %) (:source-id %)]) (first (json/read-json (:lines %) :key-fn keyword))) (operations)))]
            (is (= [["1" "850.5" "RUB"] ["2" "1750" "RUB"]]
                   (map (juxt :quantity :price :price-currency) [(line "fbo-posting" "F-1") (line "fbs-posting" "S-1")])))
            (is (= ["5" "1"] ((juxt :quantity :act-id) (line "supply" "600"))) "the accepted quantity of the supply's own act")
            (is (some #{{:type "product-id" :value "9001"}} (:identifiers (line "supply" "600"))) "on the line of its own bundle")))

        (testing "history shows the connector principal as Ozon · account"
          (is (= "Ozon · 123" (:actor-label (first (actors/enrich ctx [{:actor (str "connector:" c)}]))))))

        (testing "a second identical run adds no revisions"
          (let [before (recorded)]
            (sync-now "R2")
            (finish system)
            (is (= ["R2" "completed"] ((juxt :run-id :run-state) (row))))
            (is (= {:listings 0 :warehouses 0 :operations 0} (:found-counts (read))))
            (is (= before (recorded)))))

        (testing "pausing the connection stops the running sync from recording"
          (sync-now "R3")
          (dotimes [_ 4] (tick system))
          (is (= "open" (:run-state (row))))
          (let [request (sync/marketplace-fetcher (first (runtime/coeffect ::sync/runs-to-fetch {:bel/system system} {})))
                _       (runtime/execute! ctx ::connections/pause-marketplace-connection {:command-id "pause-3" :input {:connection-id c}})
                before  (state)]
            (is (= ["paused" "manager" "R3" "open"] ((juxt :sync-state :reason :run-id :run-state) (row))))
            (is (= ::sync/record-source-observations (:id request)))
            (is (= :connection-paused
                   (rejection #(runtime/execute! (merge (:scope request) {:bel/system system           :bel/service ::sync/marketplace-fetcher
                                                                          :user-id    (:actor request)})
                                                 (:id request) {:command-id "late" :input (:input request)})))
                "a page read before the pause is not recorded")
            (dotimes [_ 3] (tick system))
            (is (= before (state)) "nothing runs while paused")
            (runtime/execute! ctx ::connections/resume-marketplace-connection {:command-id "resume-3" :input {:connection-id c}})
            (finish system)
            (is (= ["R3" "completed"] ((juxt :run-id :run-state) (row))) "Resume continues the run")
            (is (= (nth before 2) (recorded)))))))))


(deftest figures-belong-to-their-run
  (support/with-store
    (fn [repo _]
      (let [marketplace {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {})
                         :post   (fn [path body _] (update (ozon path body) :body json/write-json-str))}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            ctx         (assoc (context system) :scope scope)
            row         #(runtime/read-model ctx ::connections/connections {:connection-id c})
            completed   #(loop [n 60]
                           (when (and (pos? n) (not= "completed" (:run-state (row)))) (runtime/run-processors! system) (recur (dec n))))
            passes      #(sync/finished-passes ctx c "stock-by-warehouse")
            covered     #(:first-covered-at (get (runtime/coeffect ::sync/connection-positions ctx {:connection-id c}) "returns"))
            full        (juxt :external-warehouse-id :present :reserved :fulfilment-type)]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      {:connection-id        c :store-id                  "s"   :provider         "ozon"      :client-id "123"
                                        :secret-ref           (connector/store-secret! marketplace workspace "k")
                                        :verification-outcome "verified" :account-currency "RUB" :readable-domains all-domains}})
        (completed)
        (let [one   (:run-id (row))
              first (covered)]
          (is (some? first))
          (runtime/execute! ctx ::sync/start-marketplace-sync
                            {:command-id "now" :input {:attempt-id "now" :run-id "R2" :connection-id c :trigger "member"}})
          (is (= [one] (map :run-id (passes))) "the open run's pass is not finished")
          (completed)
          (is (= ["R2" one] (map :run-id (passes))) "newest first")
          (is (= [#{p} #{p}] (map :asked (passes))))
          (is (every? :observed-at (passes)))
          (is (= [] (sync/finished-passes ctx "other" "stock-by-warehouse")))
          (is (= first (covered)) "set once")
          (doseq [run-id [one "R2"]
                  :let   [rows (sync/stock-figures ctx c run-id)]]
            (is (= [[(str c ":fbo:77") 10 3 "fbo"] [(str c ":fbs:1") 4 0 "fbs"]] (sort (map full rows))))
            (is (= #{run-id} (set (map :run-id rows)))))
          (is (= [] (sync/stock-figures ctx "other" one)))
          (is (= [] (sync/stock-figures ctx c "R3")))
          (is (= {"warehouse-stock" 4 "warehouse-analytics" 1 "fulfilment-totals" 1}
                 (frequencies (map :source (records/all-records ctx ::sync/source-stock :stock-id))))
              "the other views keep the latest figures only"))))))


(deftest supplies-are-read-in-full
  (support/with-store
    (fn [repo _]
      (let [orders      (atom {1 "COMPLETED" 2 "IN_TRANSIT" 3 "CANCELLED"})
            stated      (atom #{1 2 3})
            updated     (atom (zipmap [1 2 3] (repeat "2026-01-01T00:00:00Z")))
            calls       (atom [])
            answer      (fn [path body]
                          (case path
                            "/v3/supply-order/list" {:order_ids (if (= "" (:last_id body)) (sort-by #(str (@updated %)) #(compare %2 %1) (keys @orders)) [])
                                                     :last_id   "end"}
                            "/v3/supply-order/get" {:orders (for [id (map parse-long (:order_ids body))]
                                                              {:order_id id :state (@orders id) :state_updated_date (@updated id)
                                                               :supplies (if (@stated id) [{:supply_id (* 10 id) :bundle_id (str "b" id)}] [])})}
                            "/v1/supply-order/bundle" {:items [{:sku 100 :quantity 5}] :has_next false}
                            "/v1/supply-order/act/product/get" {:supply_id (:supply_id body) :supply_acts []}
                            nil))
            marketplace {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {})
                         :post   (fn [path body _]
                                   (swap! calls conj [path body])
                                   (if-let [answer (answer path body)]
                                     {:status 200 :body (json/write-json-str answer)}
                                     (update (ozon path body) :body json/write-json-str)))}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            ctx         (assoc (context system) :scope scope)
            row         #(runtime/read-model ctx ::connections/connections {:connection-id c})
            complete!   (fn [n]
                          (loop [left 60]
                            (when (and (pos? left) (= "open" (:run-state (row))))
                              (runtime/run-processors! system) (recur (dec left))))
                          (reset! calls [])
                          (runtime/execute! ctx ::sync/start-marketplace-sync
                                            {:command-id (str "s" n) :input {:attempt-id n :run-id n :connection-id c :trigger "member"}})
                          (loop [left 60]
                            (when (and (pos? left) (= "open" (:run-state (row))))
                              (runtime/run-processors! system) (recur (dec left))))
                          (is (= ["completed" n] ((juxt :run-state :run-id) (row))) (pr-str (runtime/read-model ctx ::sync/sync-status-and-coverage {:connection-id c}))))
            detailed    #(sort (map (comp first :bundle_ids second) (filter (comp #{"/v1/supply-order/bundle"} first) @calls)))
            listed      #(count (filter (comp #{"/v3/supply-order/list"} first) @calls))
            position    #(get (runtime/coeffect ::sync/connection-positions ctx {:connection-id c}) "supplies")
            status      (fn [id] (some #(when (= ["supply" id] ((juxt :kind :source-id) %)) ((juxt :status :terminal) %))
                                       (records/all-records ctx ::sync/source-operations :source-operation-id)))]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      {:connection-id        c :store-id                  "s"   :provider         "ozon"                  :client-id "123"
                                        :secret-ref           (connector/store-secret! marketplace workspace "k")
                                        :verification-outcome "verified" :account-currency "RUB" :readable-domains ["products" "supplies"]}})
        (complete! "R1")
        (is (= ["b1" "b2" "b3"] (detailed)) "everything is new")
        (is (= ["2/20"] (json/read-json (:refetch-ids (position)))))
        (testing "the second run lists all and details the non-terminal one"
          (complete! "R2")
          (is (= [["b2"] 2] [(detailed) (listed)])))
        (testing "and the changed and the new one"
          (swap! updated assoc 1 (str (Instant/now)) 4 "2026-01-01T00:00:00Z")
          (swap! orders assoc 4 "COMPLETED")
          (swap! stated conj 4)
          (complete! "R3")
          (is (= [["b1" "b2" "b4"] 2] [(detailed) (listed)])))
        (testing "a supply of an order cancelled without supply facts is no longer open"
          (swap! orders assoc 2 "CANCELLED")
          (swap! stated disj 2)
          (complete! "R4")
          (is (= ["CANCELLED" true] (status "20")))
          (is (= "[]" (:refetch-ids (position)))))
        (is (= (:started-at (runtime/coeffect ::sync/run ctx {:run-id "R1"})) (:first-covered-at (position))))))))


(deftest a-failed-first-pass-continues-after-its-recorded-supplies
  (support/with-store
    (fn [repo _]
      (let [orders      (atom (sorted-map 1 "COMPLETED" 2 "COMPLETED" 3 "COMPLETED" 4 "COMPLETED"))
            fails       (atom true)
            calls       (atom [])
            answer      (fn [path body]
                          (case path
                            "/v3/supply-order/list" {:order_ids (if (= "" (:last_id body)) (reverse (keys @orders)) []) :last_id "end"}
                            "/v3/supply-order/get" {:orders (for [id (map parse-long (:order_ids body))]
                                                              {:order_id id :state (@orders id) :state_updated_date "2026-01-01T00:00:00Z"
                                                               :supplies [{:supply_id (* 10 id) :bundle_id (str "b" id)}]})}
                            "/v1/supply-order/bundle" {:items [{:sku 100 :quantity 5}] :has_next false}
                            "/v1/supply-order/act/product/get" {:supply_id (:supply_id body) :supply_acts []}
                            nil))
            marketplace {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {})
                         :post   (fn [path body _]
                                   (swap! calls conj [path body])
                                   (if (and @fails (= 20 (:supply_id body)))
                                     {:status 404 :body "{}"}
                                     (if-let [answer (answer path body)]
                                       {:status 200 :body (json/write-json-str answer)}
                                       (update (ozon path body) :body json/write-json-str))))}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            ctx         (assoc (context system) :scope scope)
            row         #(runtime/read-model ctx ::connections/connections {:connection-id c})
            finish!     #(loop [left 60]
                           (when (and (pos? left) (= "open" (:run-state (row))))
                             (runtime/run-processors! system) (recur (dec left))))
            read        (fn []
                          [(sort (map (comp first :bundle_ids second) (filter (comp #{"/v1/supply-order/bundle"} first) @calls)))
                           (sort (keep (comp :supply_id second) @calls))])
            status      (fn [id] (some #(when (= ["supply" id] ((juxt :kind :source-id) %)) (:status %))
                                       (records/all-records ctx ::sync/source-operations :source-operation-id)))]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      {:connection-id        c :store-id                  "s"   :provider         "ozon"                  :client-id "123"
                                        :secret-ref           (connector/store-secret! marketplace workspace "k")
                                        :verification-outcome "verified" :account-currency "RUB" :readable-domains ["products" "supplies"]}})
        (runtime/run-processors! system)
        (finish!)
        (is (= "failed" (:run-state (row))))
        (is (= [["b2" "b3" "b4"] [20 30 40]] (read)))
        (reset! fails false)
        (reset! calls [])
        (swap! orders assoc 3 "CANCELLED")
        (runtime/execute! ctx ::sync/start-marketplace-sync
                          {:command-id "s2" :input {:attempt-id "s2" :run-id "R2" :connection-id c :trigger "member"}})
        (finish!)
        (is (= ["R2" "completed"] ((juxt :run-id :run-state) (row))))
        (is (= [["b1" "b2" "b3"] [10 20]] (read)) "4 is recorded as completed; 3 has another status now")
        (is (= ["COMPLETED" "COMPLETED" "CANCELLED" "COMPLETED"] (map status ["10" "20" "30" "40"])))))))


(deftest only-an-absent-or-undecryptable-key-rejects-the-credentials
  (support/with-store
    (fn [repo _]
      (let [c2          "00000000-0000-0000-0000-00000000c002"
            marketplace {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {})
                         :post   (fn [path body _] (update (ozon path body) :body json/write-json-str))}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            with        #(assoc-in system [:resources :marketplace] %)
            ctx         (assoc (context system) :scope scope)
            row         (fn [id] (runtime/read-model ctx ::connections/connections {:connection-id id}))
            connect!    (fn [id client-id]
                          (runtime/execute! ctx ::connections/connect-marketplace-account
                                            {:command-id id
                                             :input      {:connection-id        id :store-id                 "s"   :provider         "ozon"       :client-id client-id
                                                          :secret-ref           (connector/store-secret! marketplace workspace client-id)
                                                          :verification-outcome "verified" :account-currency "RUB" :readable-domains ["products"]}}))
            start!      (fn [id run-id]
                          (runtime/execute! ctx ::sync/start-marketplace-sync
                                            {:command-id run-id :input {:attempt-id run-id :run-id run-id :connection-id id :trigger "member"}}))
            items       (fn [system]
                          (mapv (fn [{:keys [run env]}] [(:run-id run) (some? env)])
                                (sort-by (comp :run-id :run) (runtime/coeffect ::sync/runs-to-fetch {:bel/system system} {}))))
            states      #(mapv (juxt :run-state :credentials-state) [(row c) (row c2)])
            untouched   (fn [system]
                          (dotimes [_ 5] (runtime/run-processors! system))
                          (= [["open" "valid"] ["open" "valid"]] (states)))
            api-key     connector/api-key
            load-rows   @#'sync/load-rows]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (connect! c "123")
        (connect! c2 "456")
        (start! c "R1")
        (start! c2 "R2")
        (is (= [["R1" true] ["R2" true]] (items system)))
        (testing "a connector key that is not configured skips every run"
          (doseq [system [(with (dissoc marketplace :key)) (with nil)]]
            (is (= [] (items system)))
            (is (untouched system))))
        (testing "any read error skips its own connection only"
          (let [broken (:secret-ref (row c))]
            (with-redefs [connector/api-key (fn [m w secret]
                                              (if (= secret broken) (throw (RuntimeException. "store down")) (api-key m w secret)))]
              (is (= [["R2" true]] (items system)))))
          (with-redefs-fn {#'sync/load-rows (fn [ctx model ids]
                                              (if (= ["R1"] ids) (throw (RuntimeException. "store down")) (load-rows ctx model ids)))}
            #(is (= [["R2" true]] (items system))))
          (with-redefs [connector/api-key (fn [_ _ _] (throw (ex-info "Cannot read" {:type :conflict})))]
            (is (= [] (items system)))
            (is (untouched system)))
          (with-redefs [connector/api-key (fn [_ _ _] (throw (InterruptedException.)))]
            (is (thrown? InterruptedException (items system)) "a shutdown is not the connection's error")))
        (testing "an absent key"
          (connector/destroy-secret! marketplace workspace (:secret-ref (row c)))
          (is (= [["R1" false] ["R2" true]] (items system)))
          (runtime/run-processors! system)
          (is (= ["failed" "rejected"] (first (states)))))
        (testing "a key that cannot be decrypted"
          (let [system (with (assoc marketplace :key (byte-array (repeat 32 (byte 7)))))]
            (is (= [["R2" false]] (items system)))
            (runtime/run-processors! system)
            (is (= [["failed" "rejected"] ["failed" "rejected"]] (states)))))))))


(deftest parts-of-a-figure-add-up-through-the-store
  (support/with-store
    (fn [repo _]
      (let [marketplace {:verify (constantly nil) :repo repo :key key-bytes :post (constantly {:status 503})}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            ctx         (assoc (context system) :scope scope)
            connector   {:bel/system   system    :bel/service ::sync/marketplace-fetcher :user-id (sync/connector-actor c)
                         :workspace-id workspace}
            send        (fn [id command input] (runtime/execute! connector command {:command-id id :input input}))
            start       (fn [run-id]
                          (runtime/execute! ctx ::sync/start-marketplace-sync
                                            {:command-id (str "start-" run-id)
                                             :input      {:attempt-id run-id :run-id run-id :connection-id c :trigger "member"}}))
            batch       (fn [run-id domain key next observations]
                          {:run-id          run-id         :domain          domain :batch-key    key
                           :next-position   {:cursor next} :domain-finished false  :observations observations
                           :adapter-version "ozon-1"})
            one         (fn [run-id] (batch run-id "stock-by-warehouse" "" "{:p 1}" [(figure {:part "100" :present 10 :reserved 3})]))
            two         (fn [run-id] (batch run-id "stock-by-warehouse" "{:p 1}" "{:p 2}" [(figure {:part "101" :present 4})]))
            stated      (fn [run-id] (mapv (juxt :present :reserved) (sync/stock-figures ctx c run-id)))]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      {:connection-id        c :store-id                  "s"   :provider         "ozon"      :client-id "123"
                                        :secret-ref           (connector/store-secret! marketplace workspace "k")
                                        :verification-outcome "verified" :account-currency "RUB" :readable-domains all-domains}})
        (start "R1")
        (send "products" ::sync/record-source-observations (batch "R1" "products" "" "{:p 1}" [(product "A-1")]))
        (send "one" ::sync/record-source-observations (one "R1"))
        (is (= [[10 3]] (stated "R1")))
        (send "two" ::sync/record-source-observations (two "R1"))
        (is (= [[14 3]] (stated "R1")) "the part of the earlier page is loaded")
        (testing "replays change nothing"
          (send "two" ::sync/record-source-observations (two "R1"))
          (send "two-again" ::sync/record-source-observations (two "R1"))
          (send "one" ::sync/record-source-observations (one "R1"))
          (is (= :stale-position (rejection #(send "one-again" ::sync/record-source-observations (one "R1")))))
          (is (= [[14 3]] (stated "R1"))))
        (testing "a finishing pass knows the listings its earlier rows named"
          (let [named #(runtime/coeffect ::sync/named ctx (merge {:connection-id c :run-id "R1" :domain "stock-by-warehouse" :domain-finished true} %))]
            (is (= #{p} (named {})))
            (is (= #{} (named {:run-id "R2"}) (named {:domain-finished false}) (named {:domain "products"})))))
        (testing "the fetcher's lookup finds the recorded figures"
          (let [item (first (runtime/coeffect ::sync/runs-to-fetch {:bel/system system} {}))]
            (is (= [14] (map :present (vals ((:figures item) (:observations (one "R1")))))))))
        (testing "a later run does not add to it"
          (send "close" ::sync/complete-marketplace-sync {:run-id "R1" :outcome "failed" :failed-domain "returns" :reason "incomplete page"})
          (start "R2")
          (send "later" ::sync/record-source-observations (one "R2"))
          (is (= [[10 3]] (stated "R2")))
          (is (= [[14 3]] (stated "R1"))))))))


(deftest poison-batch-ends-the-run-through-the-store
  (support/with-store
    (fn [repo _]
      (let [marketplace {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {}) :last-look (atom nil)
                         :post   (fn [path body _]
                                   (update (if (= "/v3/product/info/list" path)
                                             ;; Longer than a stored name may be.
                                             (fake/ok {:items [{:id 9001 :name (apply str (repeat 3000 "x"))}]})
                                             (ozon path body))
                                           :body json/write-json-str))}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            ctx         (context system)
            row         #(runtime/read-model ctx ::connections/connections {:connection-id c})]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      {:connection-id        c :store-id                  "s"   :provider         "ozon"       :client-id "123"
                                        :secret-ref           (connector/store-secret! marketplace workspace "k")
                                        :verification-outcome "verified" :account-currency "RUB" :readable-domains ["products"]}})
        (loop [n 10]
          (when (and (pos? n) (not= "failed" (:run-state (row)))) (runtime/run-processors! system) (recur (dec n))))
        (is (= ["failed" 1] ((juxt :run-state :consecutive-failures) (row))))
        (is (= ["products" "unexpected provider response"]
               ((juxt :failed-domain :reason) (:last-failure (runtime/read-model ctx ::sync/sync-status-and-coverage {:connection-id c})))))))))


(deftest replaced-credentials-start-a-new-scheduled-run
  (support/with-store
    (fn [repo _]
      (let [used        (atom nil)
            marketplace {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {})
                         :post   (fn [path body headers]
                                   (reset! used (get headers "Api-Key"))
                                   (if (= "revoked" @used)
                                     {:status 401 :body "{}"}
                                     (update (ozon path body) :body json/write-json-str)))}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            ctx         (context system)
            row         #(runtime/read-model ctx ::connections/connections {:connection-id c})
            runs        #(count (records/all-records (assoc ctx :scope scope) ::sync/sync-runs :run-id))
            input       {:connection-id        c          :client-id        "123"       :account-currency "RUB"
                         :verification-outcome "verified" :readable-domains all-domains}]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      (assoc input :store-id "s" :provider "ozon"
                                              :secret-ref (connector/store-secret! marketplace workspace "revoked"))})
        (loop [n 5]
          (when (and (pos? n) (= "valid" (:credentials-state (row)))) (runtime/run-processors! system) (recur (dec n))))
        (let [rejected (row)]
          (is (= ["rejected" "failed" 1] ((juxt :credentials-state :run-state :consecutive-failures) rejected)))
          (runtime/run-processors! system)
          (is (= 1 (runs)) "nothing is due while credentials are rejected")
          (runtime/execute! ctx ::connections/replace-connection-credentials
                            {:command-id "replace-1"
                             :input      (assoc input :secret-ref (connector/store-secret! marketplace workspace "fresh"))})
          (let [items (sync/due-rows [(row)] (Instant/now))]
            (is (= [(:run-id rejected)] (map :after-run items)) "due again, at the same time as before the first run")
            (runtime/run-processors! system)
            (let [started (row)
                  request (sync/marketplace-sync-scheduler (first items))]
              (is (= "open" (:run-state started)))
              (is (not= (:run-id rejected) (:run-id started)))
              (is (= (get-in request [:input :run-id]) (:run-id started)))
              (runtime/execute! (merge (:scope request) {:bel/system system           :bel/service ::sync/marketplace-sync-scheduler
                                                         :user-id    (:actor request)})
                                (:id request)
                                {:command-id (runtime/processor-command-id ::sync/marketplace-sync-scheduler request)
                                 :input      (:input request)})
              (is (= 2 (runs)) "a retried tick recovers its receipt")
              (dotimes [_ 60] (runtime/run-processors! system))
              (is (= "completed" (:run-state (row))))
              (is (= "fresh" @used)))))))))


;; Dirty connections


(deftest marked-once-is-taken-once
  (let [marketplace {:dirty (atom {}) :swept (atom nil)}
        now         (t "2026-12-11T06:00:00Z")]
    (sync/take-dirty! marketplace :mapping-guard #{"a" "b"} now)
    (runtime/effect! ::sync/mark-dirty {:resources {:marketplace marketplace}} {:connection-id "a"})
    (is (= #{"a"} (sync/take-dirty! marketplace :mapping-guard #{"a" "b"} (.plusSeconds now 1))))
    (is (= #{} (sync/take-dirty! marketplace :mapping-guard #{"a" "b"} (.plusSeconds now 2))))))


(deftest everything-once-a-minute
  (let [marketplace {:dirty (atom {}) :swept (atom nil)}
        now         (t "2026-12-11T06:00:00Z")]
    (is (= #{"a" "b"} (sync/take-dirty! marketplace :mapping-guard #{"a" "b"} now)) "at start")
    (is (= #{} (sync/take-dirty! marketplace :mapping-guard #{"a" "b"} (.plusSeconds now 59))))
    (is (= #{"a" "b"} (sync/take-dirty! marketplace :mapping-guard #{"a" "b"} (.plusSeconds now 60))))
    (is (= #{} (sync/take-dirty! marketplace :mapping-guard #{"a" "b"} (.plusSeconds now 61))))))


(deftest each-consumer-sees-every-mark
  (let [marketplace {:dirty (atom {}) :swept (atom nil)}
        now         (t "2026-12-11T06:00:00Z")
        take!       #(sync/take-dirty! marketplace %1 #{"a" "b"} (.plusSeconds now %2))]
    (doseq [consumer sync/consumers] (take! consumer 0))
    (runtime/effect! ::sync/mark-dirty {:resources {:marketplace marketplace}} {:connection-id "a"})
    (is (= #{"a"} (take! :mapping-guard 1)) "the Mapping Guard drains its own marks")
    (is (= #{} (take! :mapping-guard 2)))
    (is (= #{"a"} (take! :marketplace-operation-intake 3)) "intake still sees the mark the guard took")
    (is (= #{"a"} (take! :automatic-acceptance 4)) "and so does automatic acceptance")
    (is (= #{} (take! :marketplace-operation-intake 5)))
    (testing "a consumer's first call sweeps everything, whatever the others swept"
      (let [fresh {:dirty (atom {}) :swept (atom nil)}]
        (is (= #{"a" "b"} (sync/take-dirty! fresh :mapping-guard #{"a" "b"} now)))
        (is (= #{"a" "b"} (sync/take-dirty! fresh :automatic-acceptance #{"a" "b"} now)))))))

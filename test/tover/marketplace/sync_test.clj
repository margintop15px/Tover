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
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.records :as records]
    [tover.catalogues.stores.model :as stores]
    [tover.inventory.actors :as actors]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.connector :as connector]
    [tover.marketplace.sync-ozon-test :as fake]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.support :as support])
  (:import
    [java.time Instant]
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
  (decide ::sync/start-marketplace-sync (merge {:connection connection :positions {}} facts)
          (merge {:attempt-id "a1" :run-id r :connection-id c :trigger "schedule"} input)))


(deftest scheduled-start
  (let [plan (start {:connection (assoc connection :last-completed-sync-at "2026-12-10T05:00:00Z" :run-state "completed")} {})]
    (is (= {:run-id             r :connection-id                  c      :trigger "schedule" :triggered-by (str "connector:" c)
            :domains            (connections/encode-domains all-domains)
            :starting-positions (json/write-json-str (into (sorted-map) (for [d ["fbs-postings" "fbo-postings" "returns" "rfbs-returns"
                                                                                 "supplies" "removals"]]
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
    (is (= {"fbs-postings" "2026-11-01T00:00:00Z" "fbo-postings" "2026-11-01T00:00:00Z" "returns"  "2026-11-01T00:00:00Z"
            "rfbs-returns" "2026-11-01T00:00:00Z" "supplies"     "2026-11-01T00:00:00Z" "removals" "2026-11-01T00:00:00Z"}
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


(def finished-before-analytics
  (connections/encode-domains (take-while #(not= "stock-analytics" %) all-domains)))


(deftest key-loses-a-role
  (let [request (sync/fetch-request (item (run {:finished-domains finished-before-analytics}) {} [{:status 403}]))]
    (is (= [::sync/record-fetch-setback {:run-id r :domain "stock-analytics" :setback "unavailable"}]
           ((juxt :id :input) request)))
    (is (= (parse-uuid c) (:actor request)))
    (let [unavailable (after (run {:finished-domains finished-before-analytics})
                             (decide ::sync/record-fetch-setback {:run (run {:finished-domains finished-before-analytics})} (:input request))
                             :run-id)]
      (is (= "fbs-postings" (first (:unfinished-domains (sync/coverage unavailable)))) "continues with fbs-postings"))))


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
        request  (sync/fetch-request (assoc (item (run {:finished-domains finished}) {} [])
                                            :env (:env (fake/env [{:status 429 :headers {"retry-after" "1200"}}]
                                                                 (.toEpochMilli (t "2026-12-10T06:00:00Z"))))))
        plan     (decide ::sync/record-fetch-setback {:run (run {})} (:input request))]
    (is (= "throttled" (get-in request [:input :setback])))
    (is (= [::sync/provider-throttled] (events plan)))
    (let [throttled (after (assoc connection :run-state "open" :started-at "2026-12-10T06:00:00Z") plan :connection-id)]
      (is (= "open" (:run-state throttled)) "R stays open")
      (is (= "2026-12-10T06:20:00Z" (:resume-not-before throttled)) "20 minutes ahead")
      (is (not (connections/due? throttled (t "2026-12-10T06:10:00Z"))))
      (is (connections/due? throttled (t "2026-12-10T06:20:00Z")) "continues after that time"))))


(deftest pause-between-batches
  (let [paused (assoc connection :sync-state "paused" :run-state "open")]
    (is (= :connection-paused
           (rejection #(decide ::sync/record-source-observations
                               {:run (run {}) :connection paused :position nil :existing {}}
                               {:run-id       r  :domain          "warehouses" :batch-key "" :next-position {:cursor "{}"} :domain-finished false
                                :observations [] :adapter-version "ozon-1"}))))))


;; Slice 4 · Record source observations


(defn record
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (decide ::sync/record-source-observations
          (merge {:run (run {}) :connection connection :position nil :existing {}} facts)
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


(deftest same-barcode-under-two-connections
  (let [one (event (record {} {:observations [(product "A-1")]}) ::sync/external-product-observed)
        two (event (record {:run (run {:connection-id "c2"})} {:observations [(product "A-1")]}) ::sync/external-product-observed)]
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


;; Slice 6 · Finish the sync run


(defn complete
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (decide ::sync/complete-marketplace-sync (merge {:run (run {}) :connection connection :positions {}} facts)
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
      "/v4/posting/fbs/list" {:result {:postings [{:posting_number "123-0001-1"}] :has_next false}}
      "/v3/posting/fbs/get" {:result {:posting_number "123-0001-1" :status "delivering" :delivery_method {:warehouse_id 1}
                                      :products       [{:sku 100 :offer_id "A-1" :quantity 2}]}}
      "/v3/posting/fbo/list" {:result []}
      "/v1/returns/list" {:returns [] :has_next false}
      "/v2/returns/rfbs/list" {:returns []}
      "/v3/supply-order/list" {:order_ids [] :last_id ""}
      ("/v1/removal/from-stock/list" "/v1/removal/from-supply/list") {:returns_summary_report_rows [] :last_id ""})))


(defn context
  {:malli/schema [:=> [:cat :map] :map]}
  [system]
  (runtime/prepare-context {:bel/system  system :workspace-id workspace :user-id member
                            :memberships [{:workspace-id workspace :role :owner}]}))


(deftest sync-through-the-store
  (support/with-store
    (fn [repo _]
      (let [marketplace {:verify (constantly nil) :repo repo :key key-bytes :pacer (atom {}) :last-look (atom nil)
                         :post   (fn [path body _headers] (update (ozon path body) :body json/write-json-str))}
            system      (support/bel-system {:repo repo :marketplace marketplace})
            ctx         (context system)
            secret      (connector/store-secret! marketplace workspace "api-key")
            read        #(runtime/read-model ctx ::sync/sync-status-and-coverage {:connection-id c})]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (runtime/execute! ctx ::connections/connect-marketplace-account
                          {:command-id "connect-1"
                           :input      {:connection-id        c          :store-id         "s"   :provider         "ozon"      :client-id "123" :secret-ref secret
                                        :verification-outcome "verified" :account-currency "RUB" :readable-domains all-domains}})

        (testing "never synced: the scheduler starts a run as the connector"
          (runtime/run-processors! system)
          (let [status (read)]
            (is (= "schedule" (get-in status [:current-run :trigger])))
            (is (= (str "connector:" c) (get-in status [:current-run :triggered-by])))))

        (testing "two members press Sync now during the run: nothing new starts"
          (let [run-id (get-in (read) [:current-run :run-id])]
            (runtime/execute! ctx ::sync/start-marketplace-sync
                              {:command-id "now-1" :input {:attempt-id "now-1" :run-id (str (random-uuid)) :connection-id c :trigger "member"}})
            (runtime/execute! ctx ::sync/start-marketplace-sync
                              {:command-id "now-2" :input {:attempt-id "now-2" :run-id (str (random-uuid)) :connection-id c :trigger "member"}})
            (is (= run-id (get-in (read) [:current-run :run-id])))))

        (testing "a pause stops the fetcher at the next checkpoint and Resume continues the same run"
          (runtime/run-processors! system)
          (runtime/execute! ctx ::connections/pause-marketplace-connection {:command-id "pause-1" :input {:connection-id c}})
          (let [before (read)]
            (dotimes [_ 3] (runtime/run-processors! system))
            (is (= (:current-run before) (:current-run (read))) "reads nothing more; the open run keeps its positions"))
          (runtime/execute! ctx ::connections/resume-marketplace-connection {:command-id "resume-1" :input {:connection-id c}}))

        (testing "the fetcher traverses every domain and completes the run"
          (dotimes [_ 60] (runtime/run-processors! system))
          (let [status (read)]
            (is (nil? (:current-run status)))
            (is (= "completed" (:last-outcome status)))
            (is (= {:listings 1 :warehouses 4 :operations 1} (:found-counts status)))
            (is (every? :covered-until (:covered-until status)) "every domain covered until the run start")
            (is (some? (:next-sync-at status)))))

        (testing "source records are evidence, stored per connection"
          (let [product (get (runtime/coeffect ::sync/existing (assoc ctx :scope scope)
                                               {:connection-id c :observations [{:kind "product" :source-id "9001"}]})
                             (str c ":9001"))]
            (is (= [4 "Tee" "350.5" "RUB"] ((juxt :revision :name :cost :cost-currency) product))
                "aliases, attributes and cost each added a revision")
            (is (some #(= {:type "barcode" :value "4600000000017"} (select-keys % [:type :value]))
                      (json/read-json (:identifiers product) :key-fn keyword)))))

        (testing "one row per warehouse identity"
          (let [rows    (records/all-records (assoc ctx :scope scope) ::sync/external-warehouses :external-warehouse-id)
                posts   (records/all-records (assoc ctx :scope scope) ::sync/source-operations :source-operation-id)
                posting {:kind           "operation"           :operation-kind "fbs-posting" :source-id "X"
                         :from-warehouse {:type "fbs" :id "1"}}]
            (is (= [["fbo:2" "fbo" nil true] ["fbo:77" "fbo" nil true] ["fbs:1" "fbs" "FBS 1" true] ["supply-origin:2" "supply-origin" "FBO" true]]
                   (sort-by first (map (juxt #(subs (:external-warehouse-id %) (inc (count c))) :warehouse-type :name :active) rows))))
            (is (= [(str c ":fbs:1")] (map :from-warehouse posts)))
            (is (contains? (runtime/coeffect ::sync/existing (assoc ctx :scope scope) {:connection-id c :observations [posting]})
                           (str c ":fbs:1"))
                "a posting's warehouse is loaded to tell FBS from rFBS")))

        (testing "history shows the connector principal as Ozon · account"
          (is (= "Ozon · 123" (:actor-label (first (actors/enrich ctx [{:actor (str "connector:" c)}]))))))))))


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


(deftest parts-of-a-figure-add-up-through-the-store
  (support/with-store
    (fn [repo _]
      (let [marketplace {:verify (constantly nil) :repo repo :key key-bytes}
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

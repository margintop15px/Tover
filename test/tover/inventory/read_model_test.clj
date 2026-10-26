(ns tover.inventory.read-model-test
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is]]
    [tover.inventory.fixture :as historical]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.read-query :as read-query]
    [tover.inventory.store :as store]
    [tover.inventory.store-test :as fixture]
    [tover.inventory.tables :as tables]
    [tover.shared.rdf :as rdf])
  (:import
    [org.eclipse.rdf4j.model.vocabulary RDF]))


(defn payloads
  {:malli/schema [:=> [:cat rdf/Connection :keyword] [:vector :map]]}
  [conn type]
  (let [query    {:select '[?payload]
                  :where  '[[:graph ?graph [[?group ?typePredicate ?type] [?group ?payloadPredicate ?payload]]]]}
        bindings {'?graph            (read-model/graph fixture/scope)
                  '?typePredicate    RDF/TYPE
                  '?type             (read-model/term (name type))
                  '?payloadPredicate (read-model/term "payload")}]
    (mapv (comp edn/read-string :payload) (rdf/select conn query bindings))))


(defn read-payloads
  {:malli/schema [:=> [:cat rdf/Repository :keyword] [:vector :map]]}
  [repo type]
  (store/read-snapshot repo fixture/scope (fn [conn _] (payloads conn type))))


(defn projection-statements
  {:malli/schema [:=> [:cat rdf/Repository] [:set :map]]}
  [repo]
  (store/read-snapshot
    repo fixture/scope
    (fn [conn _]
      (set (rdf/select conn {:select '[?s ?p ?o] :where '[[:graph ?graph [[?s ?p ?o]]]]}
                       {'?graph (read-model/graph fixture/scope)})))))


(defn revise-opening!
  {:malli/schema [:=> [:cat rdf/Repository :string :int] :map]}
  [repo id quantity]
  (let [entry {:kind        :revision
               :target-id   id
               :reason      "Count again"
               :replacement (fixture/opening quantity)}]
    (fixture/submit! repo [(fixture/save! repo entry)])))


(deftest stock-projection-preserves-draft-overlays-and-private-compositions
  (fixture/with-store
    (fn [repo _]
      (fixture/submit! repo [(fixture/save! repo (fixture/opening 10))])
      (let [accepted   (first (:rows (store/snapshot repo fixture/scope)))
            correction {:kind           :correction
                        :stock-row-id   (:id accepted)
                        :stock-count    ""
                        :stock-location "Other"
                        :lines          [(assoc (fixture/line "a" 0) :name "Renamed")]}]
        (fixture/save! repo correction)
        (fixture/save! repo {:kind :opening :lines [{:item-id "pending" :name "Pending" :quantity ""}]})
        (store/save-composition! repo fixture/alice-scope
                                 {:entry {:kind :opening :lines [(fixture/line "private" 9)]} :actor "alice"})
        (let [snapshot (store/snapshot repo fixture/scope)
              actual   (read-payloads repo :Stock)]
          (is (= (sort-by :id (read-model/stock-rows snapshot)) (sort-by :id actual)))
          (is (= #{"Renamed" "Pending"} (set (map :name actual))))
          (is (not-any? #(= "private" (:item-id %)) actual))
          (is (= "a" (:name (first (filter #(= "a" (:id %)) (read-payloads repo :Item)))))))))))


(deftest operations-upsert-latest-revision-and-retain-cancelled-groups-without-details
  (fixture/with-store
    (fn [repo _]
      (let [draft (fixture/save! repo (fixture/opening 10))]
        (fixture/submit! repo [draft])
        (revise-opening! repo (:id draft) 12)
        (fixture/submit! repo [(fixture/save! repo {:kind :cancellation :target-id (:id draft) :reason "Wrong opening"})])
        (let [rows (read-payloads repo :Operation)
              row  (first rows)]
          (is (= 1 (count rows)))
          (is (= (:id draft) (:id row)))
          (is (= 3 (:revision row)))
          (is (:cancelled? row))
          (is (= "Wrong opening" (:reason row)))
          (is (= "Opening stock · cancelled" (:operation row)))
          (is (not (contains? row :detail)))
          (is (empty? (read-payloads repo :Stock)))
          (store/read-snapshot
            repo fixture/scope
            (fn [conn _]
              (let [query    {:select '[?count]
                              :where  '[[:graph ?graph [[?group ?typePredicate ?type] [?group ?countPredicate ?count]]]]}
                    bindings {'?graph          (read-model/graph fixture/scope)
                              '?typePredicate  RDF/TYPE
                              '?type           (read-model/term "Operation")
                              '?countPredicate (read-model/term "rowCount")}
                    counts   (rdf/select conn query bindings)]
                (is (= (count (:rows (tables/operations rows))) (:count (first counts))))))))))))


(deftest cache-bootstrap-streams-latest-records-without-reading-full-history
  (fixture/with-store
    (fn [repo _]
      (let [draft (fixture/save! repo (fixture/opening 10))]
        (fixture/submit! repo [draft])
        (revise-opening! repo (:id draft) 12)
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (#'store/put-payload! conn (#'store/graph fixture/scope "state") (#'store/graph fixture/scope "workspace")
                                (dissoc (#'store/state conn fixture/scope) :projection-storage))
          (rdf/remove! conn (read-model/graph fixture/scope) nil nil nil))
        (let [decoded     (atom 0)
              read-record model/read-record]
          (with-redefs [model/read-history (fn [& _] (throw (ex-info "Full history must stay unread" {})))
                        model/read-record  (fn [& args] (swap! decoded inc) (apply read-record args))]
            (is (= 12 (:quantity (first (read-payloads repo :Stock)))))
            (is (= 2 (:revision (first (read-payloads repo :Operation)))))
            (is (= 1 @decoded))))))))


(deftest ready-cache-reads-lightweight-state-and-keeps-generation-stable
  (fixture/with-store
    (fn [repo _]
      (fixture/save! repo (fixture/opening 3))
      (let [read-state   #(store/read-snapshot repo fixture/scope (fn [_ state] state))
            before       (read-state)
            replacements {#'store/state        (fn [& _] (throw (ex-info "Canonical EDN must stay unread" {})))
                          #'model/read-history (fn [& _] (throw (ex-info "History must stay unread" {})))}]
        (is (not (contains? before :projection)))
        (is (not (contains? before :history)))
        (with-redefs-fn replacements #(is (= before (read-state))))
        (with-redefs [tables/stock (fn [& _] (throw (ex-info "Unchanged Stock must stay untouched" {})))]
          (fixture/save! repo {:kind :operation :type :purchase}))
        (let [saved (read-state)]
          (is (= (inc (:generation before)) (:generation saved)))
          (is (= (:epoch before) (:epoch saved)))
          (store/rebuild! repo fixture/scope)
          (let [rebuilt (read-state)]
            (is (= (inc (:generation saved)) (:generation rebuilt)))
            (is (not= (:epoch saved) (:epoch rebuilt)))))))))


(deftest read-projection-survives-reopen
  (fixture/with-store
    (fn [repo path]
      (fixture/submit! repo [(fixture/save! repo (fixture/opening 5))])
      (let [before (projection-statements repo)]
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (with-redefs [model/read-history (fn [& _] (throw (ex-info "History must stay unread" {})))]
              (is (= before (projection-statements reopened)))
              (is (= 5 (:quantity (first (read-payloads reopened :Stock))))))
            (finally (rdf/close! reopened))))))))


(deftest projection-failure-rolls-back-canonical-history-state-and-cache-together
  (fixture/with-store
    (fn [repo _]
      (let [draft      (fixture/save! repo (fixture/opening 8))
            before     (store/snapshot repo fixture/scope)
            projection (projection-statements repo)]
        (with-redefs [read-model/stage-records! (fn [& _] (throw (ex-info "Projection write failed" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Projection write failed" (fixture/submit! repo [draft]))))
        (is (= before (store/snapshot repo fixture/scope)))
        (is (= projection (projection-statements repo)))))))


(defn canonical-projection
  {:malli/schema [:=> [:cat rdf/Repository] :map]}
  [repo]
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (let [graph (rdf/iri (str "urn:tover:workspace:" (:workspace-id fixture/scope) ":inventory-v1:history"))]
      (#'store/derive-projection! conn graph (ledger/effective-entries (model/read-history conn graph))))))


(defn accepted-read
  {:malli/schema [:=> [:cat rdf/Repository [:? [:set :string]]] :map]}
  ([repo] (store/read-snapshot repo fixture/scope (fn [conn _] (read-model/read-projection conn fixture/scope))))
  ([repo ids] (store/read-snapshot repo fixture/scope (fn [conn _] (read-model/read-projection conn fixture/scope ids)))))


(defn item-statements
  {:malli/schema [:=> [:cat rdf/Repository :string] [:set :map]]}
  [repo id]
  (store/read-snapshot
    repo fixture/scope
    (fn [conn _]
      (set (#'read-model/select
             conn
             (str "SELECT DISTINCT ?node ?p ?o WHERE { GRAPH ?graph { "
                  "{ ?node (r:itemId|r:id) ?itemId. } UNION "
                  "{ ?group r:itemId ?itemId. ?node r:group ?group. } "
                  "?node ?p ?o. }}")
             {:graph (read-model/graph fixture/scope) :itemId id})))))


(deftest accepted-projection-reads-exclude-draft-overlays-and-match-semantic-rebuild
  (fixture/with-store
    (fn [repo _]
      (let [entries [{:kind  :opening :occurred-at "2026-01-01"
                      :lines [(fixture/line "a" 10) (fixture/line "x" 3)]}
                     {:kind    :operation :type :production :occurred-at "2026-01-02"
                      :inputs  [(fixture/line "a" 2) (fixture/line "x" 1)]
                      :outputs [(fixture/line "b" 1) (fixture/line "c" 1)]}
                     {:kind          :operation :type        :transfer :occurred-at "2026-01-03"
                      :from-location "Main"     :to-location "Other"   :lines       [(fixture/line "a" 2)]}]]
        (fixture/submit! repo (mapv #(fixture/save! repo %) entries)))
      (let [expected (canonical-projection repo)
            row      (first (filter #(= "a" (:item-id %)) (:rows expected)))]
        (fixture/save! repo (assoc {:kind :correction :stock-row-id (:id row) :stock-count 999 :stock-location "Elsewhere"}
                                   :lines [(assoc (fixture/line "a" 999) :name "Pending rename")]))
        (fixture/save! repo {:kind :opening :lines [(fixture/line "pending" 5)]})
        (is (= expected (accepted-read repo)))
        (let [subset (accepted-read repo #{"a"})]
          (is (= #{"a"} (set (keys (:items subset)))))
          (is (= (filterv #(= "a" (:item-id %)) (:rows expected)) (:rows subset)))
          (is (= (filterv #(= "a" (:item-id %)) (:records expected)) (:records subset)))
          (is (= (:lineage expected) (:lineage subset)))
          (is (= (:lineage-relationships expected) (:lineage-relationships subset))))
        (is (= {:items            {} :rows [] :balances {} :records [] :lineage [] :lineage-relationships []
                :semantic-version (:semantic-version expected)} (accepted-read repo #{})))))))


(deftest incremental-acceptance-keeps-unrelated-rdf-and-opening-draft-row-identities
  (fixture/with-store
    (fn [repo _]
      (fixture/submit! repo [(fixture/save! repo (assoc {:kind :opening :occurred-at "2026-01-01"}
                                                        :lines [(fixture/line "a" 10)]))
                             (fixture/save! repo (assoc {:kind :opening :occurred-at "2026-01-01"}
                                                        :lines [(fixture/line "z" 7)]))])
      (let [pending   (fixture/save! repo {:kind  :opening :occurred-at "2026-01-02"
                                           :lines [(fixture/line "pending-first" 2) (fixture/line "a" 1)]})
            unrelated (item-statements repo "z")
            sale      (fixture/save! repo (assoc {:kind :operation :type :sale :party "Customer" :occurred-at "2026-01-03"}
                                                 :lines [(fixture/line "a" 2)]))]
        (fixture/submit! repo [sale])
        (is (= unrelated (item-statements repo "z")))
        (is (= (canonical-projection repo) (accepted-read repo)))
        (let [rows (read-payloads repo :Stock)]
          (is (= "draft:" (subs (:id (first (filter #(= "pending-first" (:item-id %)) rows))) 0 6)))
          (is (= #{(str "draft:" (:id pending) ":0") (str "draft:" (:id pending) ":1")}
                 (set (map :id (filter :draft-kind rows))))))
        (is (= [pending] (:drafts (store/snapshot repo fixture/scope))))))))


(deftest lightweight-state-cache-loss-rebuilds-once-from-canonical-history
  (fixture/with-store
    (fn [repo _]
      (fixture/submit! repo [(fixture/save! repo (fixture/opening 4))])
      (let [expected     (accepted-read repo)
            reads        (atom 0)
            read-history model/read-history]
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (rdf/remove! conn (read-model/graph fixture/scope) nil nil nil))
        (with-redefs [model/read-history (fn [& args] (swap! reads inc) (apply read-history args))]
          (is (= expected (accepted-read repo)))
          (is (= 1 @reads))
          (is (= expected (accepted-read repo)))
          (is (= expected (accepted-read repo #{"a"})))
          (is (= 1 @reads)))))))


(deftest historical-purchase-goods-filter-matches-only-displayed-receipt-lines
  (fixture/with-store
    (fn [repo _]
      (let [entry        {:kind     :operation :type :purchase :party "Supplier" :occurred-at "2026-01-01"
                          :lines    [{:item-id "a" :name "Planned Alpha" :quantity 5}
                                     {:item-id "b" :name "Hidden Beta" :quantity 3}
                                     {:item-id "c" :name "Catalog Gamma" :quantity 2}]
                          :receipts [{:occurred-at "2026-01-02"
                                      :lines       [{:item-id "a" :name "Delivered Alpha" :quantity 1 :location "Main"}
                                                    {:item-id "c" :quantity 1 :location "Main"}]}]}
            accepted     (historical/accept-history! repo fixture/scope [entry])
            operation-id (get-in accepted [:operations 0 :id])
            matches      (fn [name]
                           (store/read-snapshot repo fixture/scope
                                                (fn [conn _]
                                                  (mapv :id (:rows (read-query/page conn fixture/scope
                                                                                    {:view    :operations
                                                                                     :filters {"name" [{:id "goods" :type "eq" :value name}]}}))))))]
        (is (= [operation-id] (matches "Delivered Alpha")))
        (is (= [operation-id] (matches "Catalog Gamma")))
        (is (empty? (matches "Planned Alpha")))
        (is (empty? (matches "Hidden Beta")))
        (let [displayed (first (read-payloads repo :Operation))]
          (is (= ["a" "c"] (mapv :item-id (:lines displayed))))
          (is (not (contains? displayed :receipts))))
        (is (= accepted (store/snapshot repo fixture/scope)))
        (is (= accepted (store/rebuild! repo fixture/scope)))
        (is (= [operation-id] (matches "Delivered Alpha")))
        (is (empty? (matches "Hidden Beta")))))))


(deftest previous-awaiting-cache-rebuilds-without-changing-accepted-history
  (fixture/with-store
    (fn [repo _]
      (fixture/submit! repo [(fixture/save! repo {:kind        :operation   :type  :purchase                :party "Supplier"
                                                  :occurred-at "2026-01-01" :lines [(fixture/line "new" 3)]})])
      (let [accepted  (store/snapshot repo fixture/scope)
            graph     (read-model/graph fixture/scope)
            state     (rdf/iri (str graph ":state"))
            synthetic (rdf/iri (str graph ":awaiting:obsolete"))]
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (rdf/remove! conn graph state (read-model/term "version") nil)
          (rdf/add! conn graph state (read-model/term "version") 6)
          (rdf/add! conn graph synthetic RDF/TYPE (read-model/term "Stock"))
          (rdf/add! conn graph synthetic (read-model/term "payload")
                    (pr-str {:id "awaiting:obsolete" :item-id "new" :awaiting? true :quantity 0})))
        (is (= [3] (mapv :quantity (read-payloads repo :Stock))))
        (is (not-any? :awaiting? (read-payloads repo :Stock)))
        (is (= accepted (store/snapshot repo fixture/scope)))
        (is (= accepted (store/rebuild! repo fixture/scope)))))))

(ns tover.inventory.scoped-acceptance-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.schema :as schema]
    [tover.inventory.store :as store]
    [tover.inventory.store-test :as fixture]
    [tover.shared.rdf :as rdf])
  (:import
    [java.util UUID]))


(defn- opening
  {:malli/schema [:=> [:cat :string :int :string] :map]}
  [item quantity date]
  {:kind :opening :occurred-at date :lines [(fixture/line item quantity)]})


(defn- sale
  {:malli/schema [:=> [:cat :string :int :string] :map]}
  [item quantity date]
  {:kind  :operation :type :sale :party "Customer" :occurred-at date
   :lines [(fixture/line item quantity)]})


(defn- production
  {:malli/schema [:=> [:cat :string [:vector :map] [:vector :map]] :map]}
  [date inputs outputs]
  {:kind :operation :type :production :occurred-at date :inputs inputs :outputs outputs})


(defn- save-all!
  {:malli/schema [:=> [:cat rdf/Repository [:vector :map]] [:vector :map]]}
  [repo entries]
  (mapv #(fixture/save! repo %) entries))


(defn- revise
  {:malli/schema [:=> [:cat :map :map] :map]}
  [draft replacement]
  {:kind :revision :target-id (:id draft) :reason "Correct recorded facts" :replacement replacement})


(defn- cancel
  {:malli/schema [:=> [:cat :map] :map]}
  [draft]
  {:kind :cancellation :target-id (:id draft) :reason "Entered in error"})


(defn- command
  {:malli/schema [:=> [:cat rdf/Repository [:vector :map]] schema/SubmitRequest]}
  [repo drafts]
  {:selected                 (mapv #(select-keys % [:id :revision]) drafts)
   :expected-ledger-revision (:ledger-revision (store/snapshot repo fixture/scope))
   :actor                    "reviewer" :command-id (str (UUID/randomUUID))})


(defn- balances
  {:malli/schema [:=> [:cat :map] :map]}
  [snapshot]
  (into {} (map (fn [row] [[(:item-id row) (:location row)] (:quantity row)])) (:rows snapshot)))


(defn- assert-rebuild!
  {:malli/schema [:=> [:cat rdf/Repository] :nil]}
  [repo]
  (let [before (store/snapshot repo fixture/scope)]
    (is (= before (store/rebuild! repo fixture/scope))))
  nil)


(defn- cache-payloads
  {:malli/schema [:=> [:cat rdf/Repository :string] [:set :string]]}
  [repo id]
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (into #{}
          (mapcat (fn [field]
                    (map :payload
                         (rdf/select conn {:select '[?payload]
                                           :where  '[[:graph ?graph [[?subject ?idPredicate ?id]
                                                                     [?subject ?payloadPredicate ?payload]]]]}
                                     {'?graph            (read-model/graph fixture/scope)
                                      '?idPredicate      (read-model/term field)          '?id id
                                      '?payloadPredicate (read-model/term "payload")}))))
          ["id" "itemId" "operationId"])))


(defn- cache-markers
  {:malli/schema [:=> [:cat rdf/Repository [:vector :string] :boolean] :int]}
  [repo ids mark?]
  (rdf/with-transaction [conn {:repo repo :level :serializable}]
    (reduce +
            (for [id                ids
                  {:keys [subject]} (rdf/select conn {:select '[?subject]
                                                      :where  '[[:graph ?graph [[?subject ?idPredicate ?id]]]]}
                                                {'?graph       (read-model/graph fixture/scope)
                                                 '?idPredicate (read-model/term "id")           '?id id})]
              (let [subject (rdf/iri (str subject))]
                (when mark? (rdf/add! conn (read-model/graph fixture/scope) subject (read-model/term "retainedTestMarker") "untouched"))
                (if (= "untouched" (rdf/value-at conn (read-model/graph fixture/scope) subject (read-model/term "retainedTestMarker"))) 1 0))))))


(deftest backdating-rejects-an-intermediate-negative-even-with-positive-final-stock
  (fixture/with-store
    (fn [repo _]
      (let [[initial restock] (save-all! repo [(opening "a" 2 "2026-01-01")
                                               (opening "a" 10 "2026-01-04")])]
        (fixture/submit! repo [initial restock])
        (let [backdated (fixture/save! repo (sale "a" 3 "2026-01-02"))
              before    (store/snapshot repo fixture/scope)
              preview   (store/preview repo fixture/scope (fixture/request repo [backdated]))]
          (is (false? (:valid? preview)))
          (is (= (:id backdated) (get-in preview [:errors 0 :operation-id])))
          (is (= :validation (:type (fixture/error-data #(fixture/submit! repo [backdated])))))
          (is (= before (store/snapshot repo fixture/scope))))))))


(deftest a-later-reset-cannot-mask-earlier-insufficient-stock
  (fixture/with-store
    (fn [repo _]
      (let [[initial reset] (save-all! repo [(opening "a" 2 "2026-01-01")
                                             {:kind  :correction             :occurred-at "2026-01-04" :reason "Physical count"
                                              :lines [(fixture/line "a" 20)]}])]
        (fixture/submit! repo [initial reset])
        (let [backdated (fixture/save! repo (sale "a" 3 "2026-01-02"))]
          (is (false? (:valid? (store/preview repo fixture/scope (fixture/request repo [backdated])))))
          (is (= :validation (:type (fixture/error-data #(fixture/submit! repo [backdated])))))
          (is (= 20 (fixture/quantity repo "a")))
          (assert-rebuild! repo))))))


(deftest related-corrections-follow-multi-input-output-production-and-repeated-transformations
  (fixture/with-store
    (fn [repo _]
      (let [[initial first-op second-op final-sale]
            (save-all! repo [{:kind  :opening :occurred-at "2026-01-01"
                              :lines [(fixture/line "a" 10) (fixture/line "x" 5)]}
                             (production "2026-01-02" [(fixture/line "a" 6) (fixture/line "x" 2)]
                                         [(fixture/line "b" 3) (fixture/line "c" 1)])
                             (production "2026-01-03" [(fixture/line "b" 2) (fixture/line "c" 1)]
                                         [(fixture/line "a" 4) (fixture/line "d" 1)])
                             (sale "a" 5 "2026-01-04")])]
        (fixture/submit! repo [initial first-op second-op final-sale])
        (let [[rev-initial rev-first] (save-all! repo
                                                 [(revise initial {:kind  :opening :occurred-at "2026-01-01"
                                                                   :lines [(fixture/line "a" 5) (fixture/line "x" 5)]})
                                                  (revise first-op (production "2026-01-02"
                                                                               [(fixture/line "a" 4) (fixture/line "x" 2)]
                                                                               [(fixture/line "b" 3) (fixture/line "c" 1)]))])]
          (is (false? (:valid? (store/preview repo fixture/scope (fixture/request repo [rev-initial])))))
          (is (true? (:valid? (store/preview repo fixture/scope (fixture/request repo [rev-initial rev-first])))))
          (fixture/submit! repo [rev-initial rev-first])
          (is (= {["a" "Main"] 0 ["x" "Main"] 3 ["b" "Main"] 1 ["c" "Main"] 0 ["d" "Main"] 1}
                 (balances (store/snapshot repo fixture/scope))))
          (is (= 2 (count (:lineage (store/item-history repo fixture/scope "a" {:include-ancestors? true})))))
          (assert-rebuild! repo))))))


(deftest moved-and-cancelled-revisions-recompute-old-and-new-item-locations
  (fixture/with-store
    (fn [repo _]
      (let [initial (fixture/save! repo (opening "a" 10 "2026-01-01"))]
        (fixture/submit! repo [initial])
        (fixture/submit! repo [(fixture/save! repo (revise initial
                                                           {:kind  :opening :occurred-at "2026-01-01"
                                                            :lines [(assoc (fixture/line "b" 10) :location "Shop")]}))])
        (let [unrelated (fixture/save! repo (opening "other" 3 "2026-01-01"))]
          (fixture/submit! repo [unrelated]))
        (is (= {["b" "Shop"] 10 ["other" "Main"] 3} (balances (store/snapshot repo fixture/scope))))
        (fixture/submit! repo [(fixture/save! repo (revise initial
                                                           {:kind  :opening :occurred-at "2026-01-01"
                                                            :lines [(assoc (fixture/line "a" 7) :location "Depot")]}))])
        (is (= {["a" "Depot"] 7 ["other" "Main"] 3} (balances (store/snapshot repo fixture/scope))))
        (fixture/submit! repo [(fixture/save! repo (cancel initial))])
        (is (= {["other" "Main"] 3} (balances (store/snapshot repo fixture/scope))))
        (is (= [:superseded :superseded :superseded :cancelled]
               (mapv :status (:audit-records (store/item-history repo fixture/scope "a" {:include-ancestors? true})))))
        (assert-rebuild! repo)))))


(deftest cancellation-and-type-changes-validate-incoming-financial-references
  (fixture/with-store
    (fn [repo _]
      (let [purchase (fixture/save! repo {:kind        :operation   :type  :purchase              :party "Supplier"
                                          :occurred-at "2026-01-01" :lines [(fixture/line "a" 5)]})]
        (fixture/submit! repo [purchase])
        (let [payment (fixture/save! repo {:kind        :operation   :type                  :payment       :party    "Supplier"
                                           :direction   :outgoing    :amount                "10.25"        :currency "EUR"
                                           :occurred-at "2026-01-02" :original-operation-id (:id purchase)})]
          (fixture/submit! repo [payment])
          (let [[cancel-purchase cancel-payment change-type]
                (save-all! repo [(cancel purchase) (cancel payment)
                                 (revise purchase (opening "a" 5 "2026-01-01"))])]
            (doseq [invalid [cancel-purchase change-type]]
              (let [preview (store/preview repo fixture/scope (fixture/request repo [invalid]))]
                (is (false? (:valid? preview)))
                (is (= (:id payment) (get-in preview [:errors 0 :operation-id])))))
            (is (true? (:valid? (store/preview repo fixture/scope (fixture/request repo [cancel-purchase cancel-payment])))))
            (fixture/submit! repo [cancel-purchase cancel-payment])
            (is (empty? (:rows (store/snapshot repo fixture/scope))))
            (is (= [change-type] (:drafts (store/snapshot repo fixture/scope))))
            (assert-rebuild! repo)))))))


(deftest metadata-only-acceptance-preserves-unaffected-cache-and-rebuilds-exactly
  (fixture/with-store
    (fn [repo _]
      (let [[selected unrelated] (save-all! repo [(opening "a" 5 "2026-01-01")
                                                  (opening "other" 7 "2026-01-01")])]
        (fixture/submit! repo [selected unrelated])
        (let [unrelated-cache-ids ["other" (ledger/row-id "other" "Main") (:id unrelated)]
              before-item         (cache-payloads repo "other")
              before-op           (cache-payloads repo (:id unrelated))
              old-history         (store/item-history repo fixture/scope "other" {:include-ancestors? true})
              correction          (fixture/save! repo {:kind        :correction :reason "Manual article correction"
                                                       :occurred-at "2026-01-02"
                                                       :lines       [(assoc (fixture/line "a" 5) :article "NEW")]})
              marker-count        (cache-markers repo unrelated-cache-ids true)]
          (is (>= marker-count 3))
          (is (seq before-item))
          (is (seq before-op))
          (is (true? (:valid? (store/preview repo fixture/scope (fixture/request repo [correction])))))
          (is (= marker-count (cache-markers repo unrelated-cache-ids false)))
          (fixture/submit! repo [correction])
          (is (= 5 (fixture/quantity repo "a")))
          (is (= "NEW" (get-in (store/snapshot repo fixture/scope) [:items "a" :article])))
          (is (= before-item (cache-payloads repo "other")))
          (is (= before-op (cache-payloads repo (:id unrelated))))
          (is (= marker-count (cache-markers repo unrelated-cache-ids false)) "Unrelated RDF cache groups are retained rather than recreated")
          (is (= (dissoc old-history :snapshot-at :ledger-revision)
                 (dissoc (store/item-history repo fixture/scope "other" {:include-ancestors? true}) :snapshot-at :ledger-revision)))
          (assert-rebuild! repo))))))


(deftest preview-and-submit-decode-and-replay-only-the-connected-component
  (fixture/with-store
    (fn [repo _]
      (let [unrelated               (save-all! repo (mapv #(opening (str "unrelated-" %) 3 "2026-01-01") (range 40)))
            [initial production-op] (save-all! repo [(opening "a" 8 "2026-01-01")
                                                     (production "2026-01-02" [(fixture/line "a" 4)] [(fixture/line "b" 2)])])]
        (fixture/submit! repo (into unrelated [initial production-op]))
        (let [draft              (fixture/save! repo (sale "b" 1 "2026-01-03"))
              request            (command repo [draft])
              allowed            #{(:id initial) (:id production-op) (:id draft)}
              decoded            (atom []) replayed       (atom [])
              projection-queries (atom [])
              read-record        model/read-record replay ledger/replay
              read-projection    read-model/read-projection]
          (with-redefs-fn
            {#'model/read-history         (fn [& _] (throw (ex-info "Whole history read is forbidden" {})))
             #'store/snapshot             (fn [& _] (throw (ex-info "Whole snapshot read is forbidden" {})))
             #'model/read-record          (fn [& args]
                                            (let [record (apply read-record args)]
                                              (swap! decoded conj (:entity-id record))
                                              record))
             #'ledger/replay              (fn [entries]
                                            (swap! replayed into (map :id entries))
                                            (replay entries))
             #'read-model/read-projection (fn [conn scope & [item-ids]]
                                            (swap! projection-queries conj item-ids)
                                            (read-projection conn scope item-ids))}
            (fn []
              (is (true? (:valid? (store/preview repo fixture/scope request))))
              (is (= 2 (:ledger-revision (store/submit! repo fixture/scope request))))))
          (is (seq @decoded))
          (is (seq @replayed))
          (is (seq @projection-queries))
          (is (every? #(= #{"a" "b"} %) @projection-queries) "Accepted projection queries are explicitly item-scoped")
          (is (every? allowed @decoded) (str "Decoded unrelated operations: " (remove allowed @decoded)))
          (is (every? allowed @replayed) (str "Replayed unrelated operations: " (remove allowed @replayed)))
          (is (<= (count @decoded) 8) "Read work is bounded by the two existing connected records")
          (is (= 1 (fixture/quantity repo "b")))
          (is (= 3 (fixture/quantity repo "unrelated-39")))
          (let [accepted (last (:history (store/snapshot repo fixture/scope)))]
            (is (= (:id draft) (:entity-id accepted)))
            (is (= 43 (:sequence accepted)))
            (is (= 43 (:business-order accepted))))
          (assert-rebuild! repo))))))


(deftest scoped-submit-keeps-unselected-drafts-and-retries-after-reopen
  (fixture/with-store
    (fn [repo path]
      (let [[initial unrelated] (save-all! repo [(opening "a" 5 "2026-01-01")
                                                 (opening "other" 9 "2026-01-01")])]
        (fixture/submit! repo [initial unrelated])
        (let [[selected pending] (save-all! repo [(sale "a" 2 "2026-01-02")
                                                  {:kind :operation :type :production :inputs []}])
              request            (command repo [selected])
              receipt            (store/submit! repo fixture/scope request)]
          (is (= [pending] (:drafts (store/snapshot repo fixture/scope))))
          (is (= receipt (store/submit! repo fixture/scope request)))
          (is (= {["a" "Main"] 3 ["other" "Main"] 9} (balances (store/snapshot repo fixture/scope))))
          (assert-rebuild! repo)
          (rdf/close! repo)
          (let [reopened (rdf/native-store path)]
            (try
              (is (= receipt (store/submit! reopened fixture/scope request)))
              (is (= [pending] (:drafts (store/snapshot reopened fixture/scope))))
              (is (= 3 (fixture/quantity reopened "a")))
              (finally (rdf/close! reopened)))))))))


(deftest scoped-concurrent-submits-recheck-the-ledger-and-leave-one-selection-pending
  (fixture/with-store
    (fn [repo _]
      (fixture/submit! repo (save-all! repo [(opening "a" 5 "2026-01-01")
                                             (opening "b" 5 "2026-01-01")]))
      (let [[a b] (save-all! repo [(sale "a" 1 "2026-01-02") (sale "b" 1 "2026-01-02")])
            qa    (command repo [a]) qb (command repo [b]) start (promise)
            run   (fn [request]
                    (future @start
                            (try (store/submit! repo fixture/scope request)
                                 (catch clojure.lang.ExceptionInfo error (ex-data error)))))
            fa    (run qa) fb           (run qb)]
        (deliver start true)
        (let [results [(deref fa 5000 :timeout) (deref fb 5000 :timeout)]
              saved   (store/snapshot repo fixture/scope)]
          (is (= 1 (count (filter :command-id results))))
          (is (= 1 (count (filter #(= :stale-ledger (:type %)) results))))
          (is (= 1 (count (:drafts saved))))
          (is (= 3 (count (:history saved))))
          (assert-rebuild! repo))))))


(deftest scoped-commit-failure-rolls-back-canonical-history-cache-and-draft-removal
  (fixture/with-store
    (fn [repo _]
      (let [[initial unrelated] (save-all! repo [(opening "a" 5 "2026-01-01")
                                                 (opening "other" 9 "2026-01-01")])]
        (fixture/submit! repo [initial unrelated])
        (let [selected     (fixture/save! repo (sale "a" 2 "2026-01-02"))
              request      (command repo [selected])
              before       (store/snapshot repo fixture/scope)
              cache-before (cache-payloads repo "a")
              add!         rdf/add!]
          (with-redefs [rdf/add! (fn [conn graph subject predicate value]
                                   (when (= "urn:tover:inventory-v1:payload" (str predicate))
                                     (when (.endsWith (str graph) ":receipts")
                                       (throw (ex-info "Simulated lost durable receipt" {}))))
                                   (add! conn graph subject predicate value))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Simulated lost durable receipt"
                                  (store/submit! repo fixture/scope request))))
          (is (= before (store/snapshot repo fixture/scope)))
          (is (= cache-before (cache-payloads repo "a")))
          (is (= 2 (:ledger-revision (store/submit! repo fixture/scope request))))
          (is (= 3 (fixture/quantity repo "a")))
          (assert-rebuild! repo))))))


(deftest purchase-corrections-revalidate-location-dependencies-without-duplicating-money
  (fixture/with-store
    (fn [repo _]
      (let [purchase-entry                 {:kind   :operation :type  :purchase :party "Supplier" :occurred-at "2026-01-01"
                                            :amount "12.50" :currency "EUR"
                                            :lines  [(fixture/line "a" 4)
                                                     (assoc (fixture/line "a" 3) :location "Shop")]}
            transfer-entry                 {:kind          :operation :type        :transfer :occurred-at "2026-01-03"
                                            :from-location "Main"     :to-location "Shop"    :lines       [(dissoc (fixture/line "a" 2) :location)]}
            [purchase transfer final-sale] (save-all! repo [purchase-entry transfer-entry
                                                            (assoc-in (sale "a" 4 "2026-01-05") [:lines 0 :location] "Shop")])]
        (fixture/submit! repo [purchase transfer final-sale])
        (let [revised-purchase            (-> purchase-entry
                                              (assoc-in [:lines 0 :quantity] 1)
                                              (assoc-in [:lines 1 :quantity] 6))
              [purchase-fix transfer-fix] (save-all! repo [(revise purchase revised-purchase)
                                                           (revise transfer (assoc-in transfer-entry [:lines 0 :quantity] 1))])
              invalid (store/preview repo fixture/scope (fixture/request repo [purchase-fix]))]
          (is (false? (:valid? invalid)))
          (is (= (:id transfer) (get-in invalid [:errors 0 :operation-id])))
          (is (true? (:valid? (store/preview repo fixture/scope (fixture/request repo [purchase-fix transfer-fix])))))
          (fixture/submit! repo [purchase-fix transfer-fix])
          (let [saved             (store/snapshot repo fixture/scope)
                accepted-purchase (first (filter #(= (:id purchase) (:id %)) (:operations saved)))]
            (is (= {["a" "Main"] 0 ["a" "Shop"] 3} (balances saved)))
            (is (= 12.50M (:amount accepted-purchase)))
            (is (= 2 (count (:lines accepted-purchase))))
            (is (not (contains? accepted-purchase :receipts)))
            (assert-rebuild! repo)))))))


(deftest scoped-acceptance-never-borrows-stock-or-production-from-another-workspace
  (fixture/with-store
    (fn [repo _]
      (let [local   (fixture/save! repo (opening "a" 5 "2026-01-01"))
            foreign (store/save-entry! repo fixture/other-scope
                                       {:entry (opening "a" 100 "2026-01-01") :actor "foreign-user"})]
        (fixture/submit! repo [local])
        (store/submit! repo fixture/other-scope
                       {:selected   [(select-keys foreign [:id :revision])] :expected-ledger-revision 0
                        :command-id "foreign-opening" :actor "foreign-user"})
        (let [draft          (fixture/save! repo (production "2026-01-02" [(fixture/line "a" 6)] [(fixture/line "b" 1)]))
              foreign-before (store/snapshot repo fixture/other-scope)
              preview        (store/preview repo fixture/scope (fixture/request repo [draft]))]
          (is (false? (:valid? preview)))
          (is (= (:id draft) (get-in preview [:errors 0 :operation-id])))
          (is (= :validation (:type (fixture/error-data #(fixture/submit! repo [draft])))))
          (is (= 5 (fixture/quantity repo "a")))
          (is (= foreign-before (store/snapshot repo fixture/other-scope))))))))

(ns tover.inventory.history-projection-test
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is]]
    [tover.inventory.fixture :as historical]
    [tover.inventory.model :as model]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.store :as store]
    [tover.inventory.store-test :as fixture]
    [tover.shared.rdf :as rdf])
  (:import
    [org.eclipse.rdf4j.model Literal]
    [org.eclipse.rdf4j.model.vocabulary XSD]
    [org.eclipse.rdf4j.repository RepositoryConnection]))


(defn cache-rows
  {:malli/schema [:=> [:cat rdf/Repository :keyword] [:vector :map]]}
  [repo type]
  (store/read-snapshot
    repo fixture/scope
    (fn [conn _]
      (let [query (.prepareTupleQuery ^RepositoryConnection conn
                                      "PREFIX r: <urn:tover:inventory-read-v1:>
                                      SELECT ?payload ?revision ?at ?lineIndex WHERE { GRAPH ?graph {
                                        ?node a ?type; r:payload ?payload; r:operationRevision ?revision; r:occurredAt ?at.
                                        OPTIONAL { ?node r:lineIndex ?lineIndex }
                                      } }")]
        (.setBinding query "graph" (read-model/graph fixture/scope))
        (.setBinding query "type" (read-model/term (name type)))
        (with-open [rows (.evaluate query)]
          (mapv (fn [row]
                  {:payload    (edn/read-string (.stringValue (.getValue row "payload")))
                   :revision   (.longValue ^Literal (.getValue row "revision"))
                   :date-type  (.getDatatype ^Literal (.getValue row "at"))
                   :line-index (some-> ^Literal (.getValue row "lineIndex") .longValue)})
                (iterator-seq rows)))))))


(defn payloads
  {:malli/schema [:=> [:cat rdf/Repository :keyword] [:vector :map]]}
  [repo type]
  (let [rows (cache-rows repo type)]
    (mapv :payload (sort-by (if (= :Effect type)
                              (fn [row] [(get-in row [:payload :occurred-at]) (get-in row [:payload :business-order])
                                         (get-in row [:payload :event-index] 0) (get-in row [:payload :event-id])
                                         (get-in row [:payload :phase] 0) (:line-index row)])
                              #(get-in % [:payload :operation-id])) rows))))


(defn history-statements
  {:malli/schema [:=> [:cat rdf/Repository] [:set :map]]}
  [repo]
  (store/read-snapshot
    repo fixture/scope
    (fn [conn _]
      (set (rdf/select conn
                       {:select '[?node ?predicate ?value]
                        :where  '[[:graph ?graph [[?node ?typePredicate ?type]
                                                  [?node ?predicate ?value]]]]}
                       {'?graph         (read-model/graph fixture/scope)
                        '?typePredicate (rdf/iri "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
                        '?type          (read-model/term "Effect")})))))


(defn accepted-projection
  {:malli/schema [:=> [:cat rdf/Repository] :map]}
  [repo]
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (:projection (#'store/state conn fixture/scope))))


(defn submit-history!
  {:malli/schema [:=> [:cat rdf/Repository] :map]}
  [repo]
  (let [entries [{:kind  :opening :occurred-at "2026-01-01"
                  :lines [(fixture/line "a" 10) (fixture/line "x" 4)]}
                 {:kind     :operation :type :purchase :party "Supplier" :occurred-at "2026-01-02"
                  :lines    [(fixture/line "a" 5)]
                  :receipts [{:id "first" :occurred-at "2026-01-03" :lines [(fixture/line "a" 2)]}
                             {:id "last" :occurred-at "2026-01-05" :lines [(fixture/line "a" 3)]}]}
                 {:kind    :operation :type :production :occurred-at "2026-01-04"
                  :inputs  [(fixture/line "a" 4) (fixture/line "x" 1)]
                  :outputs [(fixture/line "b" 2) (fixture/line "c" 1)]}
                 {:kind   :operation             :type    :production            :occurred-at "2026-01-06"
                  :inputs [(fixture/line "b" 1)] :outputs [(fixture/line "a" 1)]}
                 {:kind          :operation :type        :transfer :occurred-at "2026-01-07"
                  :from-location "Main"     :to-location "Other"   :lines       [(fixture/line "a" 2)]}]]
    (historical/accept-history! repo fixture/scope entries)))


(deftest persisted-effects-and-production-events-match-canonical-replay
  (fixture/with-store
    (fn [repo _]
      (submit-history! repo)
      (let [projection (accepted-projection repo)]
        (is (= (:records projection) (payloads repo :Effect)))
        (is (= (sort-by :operation-id (:lineage projection)) (payloads repo :Production)))
        (doseq [type [:Effect :Production]
                row  (cache-rows repo type)]
          (is (pos-int? (:revision row)))
          (is (= XSD/DATETIME (:date-type row))))))))


(deftest draft-and-private-composition-saves-preserve-history-projection
  (fixture/with-store
    (fn [repo _]
      (submit-history! repo)
      (let [before     (history-statements repo)
            production (payloads repo :Production)
            draft      (fixture/save! repo {:kind :operation :type :production})]
        (store/save-entry! repo fixture/scope
                           {:id    (:id draft) :expected-revision (:revision draft)
                            :entry {:kind :operation :type :production :reason "Still editing"} :actor "alice"})
        (store/save-composition! repo fixture/alice-scope
                                 {:entry {:kind :opening :lines [(fixture/line "private" 4)]} :actor "alice"})
        (is (= before (history-statements repo)))
        (is (= production (payloads repo :Production)))))))


(deftest revisions-and-cancellations-replace-effects-without-stale-production
  (fixture/with-store
    (fn [repo _]
      (let [opening    (fixture/save! repo (fixture/opening 10))
            production (fixture/save! repo
                                      {:kind   :operation             :type    :production            :occurred-at "2026-01-02"
                                       :inputs [(fixture/line "a" 2)] :outputs [(fixture/line "b" 1)]})]
        (fixture/submit! repo [opening production])
        (fixture/submit! repo
                         [(fixture/save! repo
                                         {:kind        :revision            :target-id (:id opening) :reason "Count again"
                                          :replacement (fixture/opening 12)})])
        (let [projection (accepted-projection repo)]
          (is (= (:records projection) (payloads repo :Effect)))
          (is (= 2 (:operation-revision (first (payloads repo :Effect))))))
        (fixture/submit! repo
                         [(fixture/save! repo
                                         {:kind :cancellation :target-id (:id production) :reason "Wrong production"})])
        (is (empty? (payloads repo :Production)))
        (is (= #{(:id opening)} (set (map :operation-id (payloads repo :Effect)))))))))


(deftest history-projection-bootstrap-and-reopen-avoid-reading-history
  (fixture/with-store
    (fn [repo path]
      (submit-history! repo)
      (let [effects    (payloads repo :Effect)
            production (payloads repo :Production)
            state      (rdf/iri (str (read-model/graph fixture/scope) ":state"))]
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (#'store/put-payload! conn (#'store/graph fixture/scope "state") (#'store/graph fixture/scope "workspace")
                                (dissoc (#'store/state conn fixture/scope) :projection-storage))
          (rdf/remove! conn (read-model/graph fixture/scope) state (read-model/term "version") nil)
          (rdf/add! conn (read-model/graph fixture/scope) state (read-model/term "version") 2))
        (with-redefs [model/read-history (fn [& _] (throw (ex-info "Full history must stay unread" {})))]
          (is (= effects (payloads repo :Effect)))
          (is (= production (payloads repo :Production))))
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (with-redefs [model/read-history (fn [& _] (throw (ex-info "Full history must stay unread" {})))]
              (is (= effects (payloads reopened :Effect)))
              (is (= production (payloads reopened :Production))))
            (finally (rdf/close! reopened))))))))


(deftest failed-history-projection-write-rolls-back-the-ledger-and-draft
  (fixture/with-store
    (fn [repo _]
      (let [draft   (fixture/save! repo (fixture/opening 8))
            before  (store/snapshot repo fixture/scope)
            effects (payloads repo :Effect)
            stage   read-model/stage-accepted!]
        (with-redefs [read-model/stage-accepted!
                      (fn [& args]
                        (apply stage args)
                        (throw (ex-info "History projection failed" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"History projection failed"
                                (fixture/submit! repo [draft]))))
        (is (= before (store/snapshot repo fixture/scope)))
        (is (= effects (payloads repo :Effect)))))))

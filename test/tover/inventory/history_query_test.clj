(ns tover.inventory.history-query-test
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is]]
    [tover.inventory.history-query :as history-query]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.store :as store]
    [tover.shared.rdf :as rdf])
  (:import
    [org.eclipse.rdf4j.repository.sail SailRepository]
    [org.eclipse.rdf4j.sail.memory MemoryStore]))


(defn- line
  {:malli/schema [:function [:=> [:cat :string :int] :map] [:=> [:cat :string :int :string] :map]]}
  ([item quantity] (line item quantity "Main"))
  ([item quantity location]
   {:item-id item :name item :quantity quantity :location location :unit "pcs"}))


(defn- record
  {:malli/schema [:=> [:cat :int :string :map] :map]}
  [index date entry]
  {:id          (str "record-" index) :entity-id (str index) :revision   1     :sequence index :business-order index
   :recorded-at "2026-10-01T12:00:00Z" :actor    "alice"     :cancelled? false
   :entry       (ledger/normalize-entry (merge {:kind :operation :occurred-at date} entry))})


(defn- opening
  {:malli/schema [:=> [:cat :int :string [:* :map]] :map]}
  [index date & lines]
  (record index date {:kind :opening :lines (vec lines)}))


(defn- graph
  {:malli/schema [:=> [:cat :map] rdf/IRI]}
  [scope]
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-v1:history")))


(defn- seed!
  {:malli/schema [:=> [:cat rdf/Connection :map [:vector :map]] :map]}
  [conn scope records]
  (let [projection (ledger/replay (ledger/effective-entries records))]
    (doseq [record records]
      (model/write-record! conn (graph scope) (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope))) record))
    (read-model/stage-state! conn scope {:ledger-revision (count records) :projection projection :drafts {}})
    projection))


(defn- with-store
  {:malli/schema [:=> [:cat [:=> [:cat rdf/Connection :map] :any]] :any]}
  [run]
  (let [repo  (doto (SailRepository. (MemoryStore.)) .init)
        scope {:workspace-id (random-uuid) :user-id (random-uuid)}]
    (try
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (run conn scope))
      (finally (.shutDown repo)))))


(defn- expected
  {:malli/schema [:=> [:cat :map [:vector :map] :string :boolean] :map]}
  [projection records item-id ancestors?]
  (let [audit (ledger/item-audit records item-id)
        item  (or (get-in projection [:items item-id]) (:item audit))]
    (assoc (ledger/item-history projection item-id ancestors?) :item item :audit-records (:audit-records audit))))


(defn- assert-parity
  {:malli/schema [:=> [:cat rdf/Connection :map :map [:vector :map] :string] :nil]}
  [conn scope projection records item-id]
  (doseq [ancestors? [false true]]
    (let [actual (history-query/history conn scope item-id ancestors?)
          legacy (expected projection records item-id ancestors?)]
      (is (= legacy (dissoc actual :items)) (str item-id " ancestors=" ancestors?))
      (is (= (:item actual) (get-in actual [:items item-id])))))
  nil)


(deftest scoped-reader-matches-effects-and-dated-repeated-transformations
  (with-store
    (fn [conn scope]
      (let [records    [(opening 1 "2026-01-01" (line "A" 20) (line "Z" 5))
                        (record 2 "2026-01-02T12:00:00Z" {:type :production :inputs [(line "A" 4)] :outputs [(line "B" 2) (line "Offcut" 1)]})
                        (record 3 "2026-01-02T12:00:00Z" {:type :production :inputs [(line "B" 1)] :outputs [(line "A" 2)]})
                        (record 4 "2026-01-02T12:00:00Z" {:type :production :inputs [(line "A" 1)] :outputs [(line "C" 1)]})
                        (record 5 "2026-01-03" {:type :production :inputs [(line "A" 1)] :outputs [(line "B" 1)]})
                        (record 6 "2026-01-04" {:type :production :inputs [(line "C" 1) (line "Z" 1)] :outputs [(line "D" 1)]})
                        (record 7 "2026-01-05" {:type :purchase :party "Future" :lines [(line "A" 100)]})]
            projection (seed! conn scope records)
            result     (history-query/history conn scope "C" true)]
        (doseq [item ["A" "B" "C" "D" "Offcut"]] (assert-parity conn scope projection records item))
        (is (= ["2" "3" "4" "6"] (mapv :operation-id (:lineage result))))
        (is (= #{"A" "B" "C"} (set (keys (:items result)))))
        (is (not-any? #{"5" "7"} (map :operation-id (:timeline-records result))))
        (is (not-any? #(= "Z" (:item-id %)) (:timeline-records result)))
        (is (= #{"2026-01-02T12:00:00Z"} (set (keep :timeline-until (:timeline-records result)))))
        (is (some #(and (= "4" (:operation-id %)) (= "A" (:item-id %)) (= 0 (:phase %))) (:timeline-records result)))))))


(deftest receipts-transfers-corrections-and-multiple-locations-retain-exact-records
  (with-store
    (fn [conn scope]
      (let [records    [(record 1 "2026-01-01" {:type     :purchase :party "Supplier" :amount "25.50" :currency "EUR"
                                                :lines    [(line "A" 10)]
                                                :receipts [{:occurred-at "2026-01-03" :lines [(line "A" 3 "Dock")]}
                                                           {:occurred-at "2026-01-06" :lines [(line "A" 2 "Shop")]}]})
                        (record 2 "2026-01-04" {:type :transfer :from-location "Dock" :to-location "Shop" :lines [(line "A" 1)]})
                        (record 3 "2026-01-05" {:type :return :direction :incoming :lines [(line "A" 1 "Dock")]})
                        (record 4 "2026-01-07" {:kind :correction :reason "Article correction" :lines [(assoc (line "A" 3 "Dock") :article "NEW")]})
                        (record 5 "2026-01-08" {:type :sale :party "Customer" :lines [(line "A" 1 "Shop")]})]
            projection (seed! conn scope records)
            result     (history-query/history conn scope "A" true)]
        (assert-parity conn scope projection records "A")
        (is (= #{"Dock" "Shop"} (set (map :location (:records result)))))
        (is (= "NEW" (get-in result [:item :article])))
        (is (= ["2026-01-03T00:00:00Z" "2026-01-06T00:00:00Z"]
               (mapv :occurred-at (filter #(= "1" (:operation-id %)) (:records result)))))))))


(deftest audit-includes-all-revisions-when-an-item-was-replaced-or-cancelled
  (with-store
    (fn [conn scope]
      (let [original     (opening 1 "2026-01-01" (assoc (line "A" 5) :name "Former item"))
            replacement  (assoc (opening 2 "2026-01-01" (line "B" 7)) :entity-id "1" :revision 2 :business-order 1)
            cancellation (assoc replacement :id "record-3" :revision 3 :sequence 3 :cancelled? true)
            records      [original replacement cancellation]
            projection   (seed! conn scope records)]
        (doseq [item ["A" "B"]] (assert-parity conn scope projection records item))
        (let [result (history-query/history conn scope "A" true)]
          (is (= [1 2 3] (mapv :revision (:audit-records result))))
          (is (= [true false false] (mapv :touches-item? (:audit-records result))))
          (is (= [:superseded :superseded :cancelled] (mapv :status (:audit-records result))))
          (is (= "Former item" (get-in result [:item :name])))
          (is (empty? (:records result))))))))


(deftest unarrived-purchases-have-history-and-pending-only-items-do-not
  (with-store
    (fn [conn scope]
      (let [records    [(record 1 "2026-01-01" {:type :purchase :party "Supplier" :lines [(line "A" 10)] :receipts []})]
            projection (seed! conn scope records)]
        (assert-parity conn scope projection records "A")
        (read-model/stage-state! conn scope
                                 {:ledger-revision 1 :projection projection
                                  :drafts          {"draft" {:id "draft" :saved-at "2026-01-01" :entry {:kind :opening :lines [(line "Pending" 2)]}}}})
        (is (= :not-found
               (try (history-query/history conn scope "Pending" true)
                    (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))))


(deftest graph-bindings-and-item-bindings-prevent-cross-workspace-or-query-injection
  (with-store
    (fn [conn scope]
      (let [other      (assoc scope :workspace-id (random-uuid))
            hostile    "A\") } UNION { ?a ?b ?c"
            records    [(opening 1 "2026-01-01" (line hostile 3))]
            projection (seed! conn scope records)]
        (seed! conn other [(opening 1 "2026-01-01" (assoc (line hostile 99) :name "Other workspace"))])
        (assert-parity conn scope projection records hostile)
        (is (= 3 (:after (first (:records (history-query/history conn scope hostile true))))))
        (is (= 99 (:after (first (:records (history-query/history conn other hostile true))))))
        (is (= :not-found
               (try (history-query/history conn scope "missing" true)
                    (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))))


(deftest unrelated-workspace-records-are-neither-read-nor-decoded
  (with-store
    (fn [conn scope]
      (let [records         (into [(opening 1 "2026-01-01" (line "Selected" 3))]
                                  (map #(opening % "2026-01-01" (line (str "Unrelated-" %) 9)) (range 2 302)))
            _               (seed! conn scope records)
            original-reader model/read-record
            original-edn    edn/read-string
            read-records    (atom [])
            decoded         (atom 0)
            fail-full-read  (fn [& _] (throw (ex-info "Whole-history read is forbidden" {})))
            result          (with-redefs [model/read-history fail-full-read store/snapshot fail-full-read ledger/replay fail-full-read
                                          model/read-record  (fn [conn graph subject]
                                                               (swap! read-records conj subject)
                                                               (original-reader conn graph subject))
                                          edn/read-string    (fn [& args] (swap! decoded inc) (apply original-edn args))]
                              (history-query/history conn scope "Selected" true))]
        (is (= 1 (count (:records result))))
        (is (= 1 (count (:audit-records result))))
        (is (= 1 (count @read-records)))
        (is (= 2 @decoded))
        (is (= #{"Selected"} (set (keys (:items result)))))))))


(deftest parent-cutoffs-exclude-future-and-unrelated-payloads-before-decoding
  (with-store
    (fn [conn scope]
      (let [records      (into [(opening 1 "2026-01-01" (line "A" 10) (line "Z" 100))
                                (record 2 "2026-01-02" {:type :production :inputs [(line "A" 2)] :outputs [(line "Kit" 1)]})]
                               (map #(record % "2026-01-03" {:type :production :inputs [(line "Z" 1)] :outputs [(line "A" 1)]})
                                    (range 3 83)))
            projection   (seed! conn scope records)
            original-edn edn/read-string
            decoded      (atom [])
            read-counted (fn [& args]
                           (let [value (apply original-edn args)]
                             (swap! decoded conj value)
                             value))
            result       (with-redefs [edn/read-string read-counted]
                           (history-query/history conn scope "Kit" true))]
        (assert-parity conn scope projection records "Kit")
        (is (= 6 (count @decoded)))
        (is (= ["1" "2" "2"] (mapv :operation-id (:timeline-records result))))
        (is (= ["2"] (mapv :operation-id (:lineage result))))
        (is (= #{"A" "Kit"} (set (keys (:items result)))))
        (reset! decoded [])
        (let [own (with-redefs [edn/read-string read-counted]
                    (history-query/history conn scope "Kit" false))]
          (is (= 3 (count @decoded)))
          (is (= #{"Kit"} (set (keys (:items own)))))
          (is (= (:records own) (:timeline-records own))))))))

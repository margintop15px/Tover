(ns tover.inventory.affected-query-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.inventory.affected-query :as affected-query]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.shared.rdf :as rdf])
  (:import
    [org.eclipse.rdf4j.repository.sail SailRepository]
    [org.eclipse.rdf4j.sail.memory MemoryStore]))


(defn- line
  {:malli/schema [:=> [:cat :string :int] :map]}
  [item quantity]
  {:item-id item :name item :quantity quantity :location "Main" :unit "pcs"})


(defn- record
  {:malli/schema [:=> [:cat :int :map] :map]}
  [sequence entry]
  {:id          (str "record-" sequence) :entity-id (str sequence) :revision   1     :sequence sequence :business-order sequence
   :recorded-at "2026-10-01T12:00:00Z" :actor       "alice"        :cancelled? false
   :entry       (ledger/normalize-entry (merge {:kind :operation :occurred-at "2026-01-01"} entry))})


(defn- opening
  {:malli/schema [:=> [:cat :int :string] :map]}
  [sequence item]
  (record sequence {:kind :opening :lines [(line item 10)]}))


(defn- seed!
  {:malli/schema [:=> [:cat rdf/Connection :map [:vector :map]] :nil]}
  [conn scope records]
  (let [workspace (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope)))
        graph     (rdf/iri (str workspace ":inventory-v1:history"))]
    (doseq [record records] (model/write-record! conn graph workspace record))))


(defn- with-store
  {:malli/schema [:=> [:cat [:=> [:cat rdf/Connection :map] :any]] :any]}
  [run]
  (let [repo  (doto (SailRepository. (MemoryStore.)) .init)
        scope {:workspace-id (random-uuid) :user-id (random-uuid)}]
    (try
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (run conn scope))
      (finally (.shutDown repo)))))


(deftest complete-production-item-and-reference-components-are-queried
  (with-store
    (fn [conn scope]
      (let [records [(opening 1 "A") (opening 2 "Z")
                     (record 3 {:type :production :inputs [(line "A" 2) (line "Z" 1)] :outputs [(line "B" 1) (line "C" 1)]})
                     (record 4 {:type :production :inputs [(line "B" 1)] :outputs [(line "A" 1)]})
                     (record 5 {:type :sale :party "Customer" :lines [(line "C" 1)]})
                     (record 6 {:type :return :direction :incoming :original-operation-id "5" :lines [(line "Z" 1)]})
                     (record 7 {:type :payment :direction :incoming :party "Customer" :amount "5" :currency "EUR" :original-operation-id "5"})
                     (opening 8 "Unrelated")]
            _       (seed! conn scope records)
            result  (affected-query/closure conn scope [(:entry (opening 99 "A"))] #{"new"})]
        (is (= #{"A" "Z" "B" "C"} (:item-ids result)))
        (is (= #{"1" "2" "3" "4" "5" "6" "7" "new"} (:entity-ids result)))
        (is (= (subvec records 0 7) (:records result)))
        (is (= 8 (:sequence result)))))))


(deftest latest-operations-determine-membership-and-selected-entities-retain-all-revisions
  (with-store
    (fn [conn scope]
      (let [original        (opening 1 "A")
            replacement     (assoc (opening 2 "B") :entity-id "1" :revision 2 :business-order 1)
            cancel-original (opening 3 "A")
            cancellation    (assoc cancel-original :id "record-4" :revision 2 :sequence 4 :cancelled? true)
            _               (seed! conn scope [original replacement cancel-original cancellation])
            for-a           (affected-query/closure conn scope [(:entry (opening 99 "A"))] #{"new"})
            for-b           (affected-query/closure conn scope [(:entry (opening 99 "B"))] #{"new"})
            change          (ledger/normalize-entry {:kind        :revision                 :target-id "1" :reason "Move to another item"
                                                     :replacement (:entry (opening 99 "C"))})
            for-change      (affected-query/closure conn scope [change] #{"1"})
            for-cancel      (affected-query/closure conn scope [] #{"3"})]
        (is (empty? (:records for-a)))
        (is (= #{"A"} (:item-ids for-a)))
        (is (= [original replacement] (:records for-b)))
        (is (= #{"B"} (:item-ids for-b)))
        (is (= [original replacement] (:records for-change)))
        (is (= #{"B" "C"} (:item-ids for-change)))
        (is (= [cancel-original cancellation] (:records for-cancel)))
        (is (= 4 (:sequence for-cancel)))))))


(deftest old-references-and-cancelled-referrers-do-not-expand-the-component
  (with-store
    (fn [conn scope]
      (let [purchase-a (record 1 {:type :purchase :party "Supplier A" :lines [(line "A" 10)]})
            purchase-b (record 2 {:type :purchase :party "Supplier B" :lines [(line "B" 10)]})
            payment-a  (record 3 {:type :payment :direction :outgoing :party "Supplier" :amount "4" :currency "EUR" :original-operation-id "1"})
            payment-b  (assoc payment-a :id "record-4" :revision 2 :sequence 4 :entry (assoc (:entry payment-a) :original-operation-id "2"))
            cancelled  (assoc payment-a :id "record-5" :entity-id "5" :sequence 5 :cancelled? true)
            _          (seed! conn scope [purchase-a purchase-b payment-a payment-b cancelled])
            a          (affected-query/closure conn scope [] #{"1"})
            b          (affected-query/closure conn scope [] #{"2"})]
        (is (= [purchase-a] (:records a)))
        (is (= #{"A"} (:item-ids a)))
        (is (= [purchase-b payment-a payment-b] (:records b)))
        (is (= #{"B"} (:item-ids b)))))))


(deftest target-and-forward-reference-seeds-cover-payment-collisions-and-missing-targets
  (with-store
    (fn [conn scope]
      (let [purchase (record 1 {:type :purchase :party "Supplier" :lines [(line "A" 10)] :receipts []})
            payment  (record 2 {:type :payment :direction :outgoing :party "Supplier" :amount "7" :currency "EUR" :original-operation-id "1"})
            _        (seed! conn scope [purchase payment])
            result   (affected-query/closure conn scope [(:entry payment)] #{"2" "missing"})]
        (is (= [purchase payment] (:records result)))
        (is (= #{"A"} (:item-ids result)))
        (is (= #{"1" "2" "missing"} (:entity-ids result)))
        (is (= 2 (:sequence result)))))))


(deftest receipts-and-production-lines-expand-as-whole-operations
  (with-store
    (fn [conn scope]
      (let [purchase   (record 1 {:type     :purchase :party "Supplier"
                                  :lines    [(line "A" 10) (line "B" 10)]
                                  :receipts [{:occurred-at "2026-01-03" :lines [(line "A" 2) (line "B" 3)]}]})
            production (record 2 {:type :production :inputs [(line "B" 1)] :outputs [(line "C" 1)]})
            _          (seed! conn scope [purchase production])
            result     (affected-query/closure conn scope [(:entry (opening 99 "A"))] #{"new"})]
        (is (= #{"A" "B" "C"} (:item-ids result)))
        (is (= [purchase production] (:records result)))))))


(deftest workspace-and-identifiers-are-bound-and-sequence-is-global
  (with-store
    (fn [conn scope]
      (let [other     (assoc scope :workspace-id (random-uuid))
            hostile   "A\") } UNION { ?a ?b ?c"
            selected  (opening 1 hostile)
            unrelated (opening 900 "Unrelated")
            _         (seed! conn scope [selected unrelated])
            _         (seed! conn other [(assoc (opening 999 hostile) :entity-id "1")])
            result    (affected-query/closure conn scope [(:entry (opening 99 hostile))] #{"new"})]
        (is (= [selected] (:records result)))
        (is (= 900 (:sequence result)))
        (is (= #{hostile} (:item-ids result)))
        (is (= 999 (:sequence (affected-query/closure conn other [] #{"1"}))))
        (is (= {:item-ids #{} :entity-ids #{} :records [] :sequence 900}
               (affected-query/closure conn scope [] #{})))))))


(deftest unrelated-history-is-neither-loaded-nor-decoded
  (with-store
    (fn [conn scope]
      (let [selected        (opening 1 "Selected")
            replacement     (assoc (opening 2 "Selected") :entity-id "1" :revision 2 :business-order 1)
            records         (into [selected replacement] (map #(opening % (str "Unrelated-" %)) (range 3 303)))
            _               (seed! conn scope records)
            original-reader model/read-record
            decoded         (atom [])
            forbidden       (fn [& _] (throw (ex-info "Whole workspace read is forbidden" {})))
            result          (with-redefs [model/read-history forbidden model/read-entity-history forbidden ledger/replay forbidden
                                          model/read-record  (fn [conn graph subject]
                                                               (let [record (original-reader conn graph subject)]
                                                                 (swap! decoded conj (:id record))
                                                                 record))]
                              (affected-query/closure conn scope [(:entry (opening 999 "Selected"))] #{"new"}))]
        (is (= [selected replacement] (:records result)))
        (is (= ["record-1" "record-2"] @decoded))
        (is (= 302 (:sequence result)))
        (is (= #{"Selected"} (:item-ids result)))))))


(deftest identifier-batches-preserve-global-record-order-and-empty-store-sequence
  (with-store
    (fn [conn scope]
      (is (= {:item-ids #{} :entity-ids #{} :records [] :sequence 0}
             (affected-query/closure conn scope [] #{})))
      (let [records (mapv #(record % {:type :payment :direction :incoming :party "Customer" :amount "1" :currency "EUR"})
                          (range 1 262))
            _       (seed! conn scope records)
            targets (set (map :entity-id records))
            result  (affected-query/closure conn scope [] targets)]
        (is (= records (:records result)))
        (is (= targets (:entity-ids result)))
        (is (empty? (:item-ids result)))
        (is (= 261 (:sequence result)))))))

(ns tover.inventory.derivation-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.inventory.derivation :as derivation]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.semantics :as semantics]
    [tover.shared.rdf :as rdf])
  (:import
    [java.io StringReader]
    [org.eclipse.rdf4j.model Resource]
    [org.eclipse.rdf4j.repository.sail SailRepository]
    [org.eclipse.rdf4j.rio RDFFormat]
    [org.eclipse.rdf4j.sail.memory MemoryStore]))


(def graph
  (rdf/iri "urn:test:inventory:history"))


(def workspace
  (rdf/iri "urn:test:inventory:workspace"))


(defn line
  {:malli/schema [:function [:=> [:cat :string :int] :map] [:=> [:cat :string :int :string] :map]]}
  ([item quantity] (line item quantity "Main"))
  ([item quantity location]
   {:item-id item :name item :location location :quantity quantity}))


(defn record
  {:malli/schema [:=> [:cat :int :string :map] :map]}
  [sequence occurred-at fields]
  {:id          (str "record-" sequence) :entity-id      (str "operation-" sequence) :revision   1
   :sequence    sequence :business-order                 sequence                    :actor      "tester"
   :recorded-at "2026-10-21T00:00:00Z" :semantic-version semantics/version           :cancelled? false
   :change-kind (:kind fields :operation)
   :entry       (ledger/normalize-entry (assoc fields :occurred-at occurred-at))})


(defn opening
  {:malli/schema [:=> [:cat :int :string [:* :map]] :map]}
  [sequence occurred-at & lines]
  (record sequence occurred-at {:kind :opening :lines (vec lines)}))


(defn revise
  {:malli/schema [:=> [:cat :int :map :map] :map]}
  [sequence previous replacement]
  (assoc previous :id (str "record-" sequence) :sequence sequence :revision (inc (:revision previous))
         :previous-revision (:revision previous) :change-kind :revision :entry (ledger/normalize-entry replacement)))


(defn cancel
  {:malli/schema [:=> [:cat :int :map] :map]}
  [sequence previous]
  (assoc previous :id (str "record-" sequence) :sequence sequence :revision (inc (:revision previous))
         :previous-revision (:revision previous) :change-kind :cancellation :cancelled? true))


(defn with-model
  {:malli/schema [:=> [:cat [:vector :map] [:=> [:cat rdf/Connection] :any]] :any]}
  [records f]
  (let [repo (SailRepository. (MemoryStore.))]
    (.init repo)
    (try
      (with-open [conn (.getConnection repo)]
        (.begin conn)
        (.add conn (StringReader. (slurp (io/resource "inventory/ontology.ttl"))) model/base RDFFormat/TURTLE
              (into-array Resource [(rdf/iri "urn:tover:inventory-v1:ontology")]))
        (doseq [record records] (model/write-record! conn graph workspace record))
        (.commit conn)
        (f conn))
      (finally (.shutDown repo)))))


(defn assert-equivalent
  {:malli/schema [:=> [:cat [:vector :map]] :any]}
  [records]
  (with-model records
    (fn [conn]
      (let [derived    (derivation/validate! conn graph)
            projection (ledger/replay (ledger/effective-entries records))]
        (is (= (:balances projection) (:balances derived)))
        (is (= (mapv :operation-id (:lineage projection)) (mapv :operation-id (:lineage derived))))
        derived))))


(deftest empty-graph-and-opening-balance
  (is (= {} (:balances (assert-equivalent []))))
  (let [derived (assert-equivalent [(opening 1 "2026-01-01" (line "a" 5))])]
    (is (= {["a" "Main"] 5} (:balances derived)))
    (is (= [:add] (mapv :mode (:effects derived))))))


(deftest all-operation-policies-match-replay-without-host-type-dispatch
  (let [records [(opening 1 "2026-01-01" (line "a" 50))
                 (record 2 "2026-01-02" {:type :purchase :party "S" :lines [(line "a" 10)]})
                 (record 3 "2026-01-03" {:type :return :direction :incoming :lines [(line "a" 2)]})
                 (record 4 "2026-01-04" {:type :return :direction :outgoing :lines [(line "a" 3)]})
                 (record 5 "2026-01-05" {:type :transfer :from-location "Main" :to-location "Shop" :lines [(line "a" 20)]})
                 (record 6 "2026-01-06" {:type :production :inputs [(line "a" 4)] :outputs [(line "b" 2)]})
                 (record 7 "2026-01-07" {:type :defect :reason "Damaged" :lines [(line "a" 1)]})
                 (record 8 "2026-01-08" {:type :sale :party "C" :lines [(line "a" 5)]})
                 (record 9 "2026-01-09" {:type :write-off :reason "Lost" :lines [(line "a" 2)]})
                 (record 10 "2026-01-10" {:type :payment :party "S" :direction :outgoing :amount "2.10" :currency "EUR"})]
        derived (assert-equivalent records)]
    (is (= {["a" "Main"] 27 ["a" "Shop"] 20 ["b" "Main"] 2} (:balances derived)))
    (is (= 11 (count (:effects derived))))))


(deftest dated-partial-arrivals-do-not-create-ordered-stock-or-duplicate-money
  (let [purchase (record 1 "2026-01-01" {:type     :purchase :party "S" :amount "10.10" :currency "EUR"
                                         :lines    [{:item-id "a" :quantity 10 :amount "10.10"}]
                                         :receipts [{:occurred-at "2026-01-03" :lines [(line "a" 3 "Dock")]}
                                                    {:occurred-at "2026-01-05" :lines [(line "a" 2 "Shop")]}]})
        derived  (assert-equivalent [purchase])]
    (is (= {["a" "Dock"] 3 ["a" "Shop"] 2} (:balances derived)))
    (is (= ["2026-01-03T00:00:00Z" "2026-01-05T00:00:00Z"] (mapv :occurred-at (:effects derived))))
    (is (= {} (:balances (assert-equivalent [(assoc-in purchase [:entry :receipts] [])]))))))


(deftest latest-revisions-and-cancellations-are-effective
  (let [original    (opening 1 "2026-01-01" (line "a" 10))
        sale        (record 2 "2026-01-02" {:type :sale :party "C" :lines [(line "a" 3)]})
        replacement (revise 3 original {:kind :opening :occurred-at "2026-01-01" :lines [(line "a" 12)]})
        derived     (assert-equivalent [original sale replacement])]
    (is (= {["a" "Main"] 9} (:balances derived)))
    (is (= [2 1] (mapv :operation-revision (:effects derived))))
    (is (= {["a" "Main"] 12} (:balances (assert-equivalent [original sale replacement (cancel 4 sale)]))))))


(deftest manual-correction-resets-only-its-own-item-and-location
  (let [records [(opening 1 "2026-01-01" (line "a" 10) (line "a" 4 "Shop") (line "b" 8))
                 (record 2 "2026-01-02" {:type :sale :party "C" :lines [(line "a" 3)]})
                 (record 3 "2026-01-03" {:kind :correction :reason "Count" :lines [(line "a" 5)]})
                 (record 4 "2026-01-04" {:type :purchase :party "S" :lines [(line "a" 2)]})
                 (record 5 "2026-01-05" {:kind :correction :reason "Recount" :lines [(line "a" 0)]})
                 (record 6 "2026-01-06" {:type :return :direction :incoming :lines [(line "a" 1)]})]
        derived (assert-equivalent records)]
    (is (= {["a" "Main"] 1 ["a" "Shop"] 4 ["b" "Main"] 8} (:balances derived)))))


(deftest backdated-sale-fails-even-if-final-stock-is-positive
  (with-model [(opening 1 "2026-01-01" (line "a" 2))
               (record 2 "2026-01-04" {:type :purchase :party "S" :lines [(line "a" 10)]})
               (record 3 "2026-01-02" {:type :sale :party "C" :lines [(line "a" 3)]})]
    (fn [conn]
      (let [derived (derivation/derive conn graph)]
        (is (= {["a" "Main"] 9} (:balances derived)))
        (is (= -1 (get-in derived [:violations 0 :after])))
        (is (= "operation-3" (get-in derived [:violations 0 :operation-id])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ordered inventory timeline" (derivation/validate! conn graph)))))))


(deftest later-count-does-not-erase-an-invalid-earlier-history
  (with-model [(opening 1 "2026-01-01" (line "a" 1))
               (record 2 "2026-01-02" {:type :sale :party "C" :lines [(line "a" 2)]})
               (record 3 "2026-01-03" {:kind :correction :reason "Count" :lines [(line "a" 10)]})]
    (fn [conn]
      (let [derived (derivation/derive conn graph)]
        (is (= {["a" "Main"] 10} (:balances derived)))
        (is (= [-1] (mapv :after (:violations derived))))))))


(deftest unavailable-production-inputs-cannot-self-fund-from-outputs
  (with-model [(record 1 "2026-01-01" {:type :production :inputs [(line "a" 2)] :outputs [(line "a" 3)]})]
    (fn [conn]
      (let [derived (derivation/derive conn graph)]
        (is (= [:remove :add] (mapv :mode (:effects derived))))
        (is (= -2 (get-in derived [:violations 0 :after])))))))


(deftest production-edges-remain-dated-through-repeat-transformations
  (let [records [(opening 1 "2026-01-01" (line "a" 10) (line "b" 4))
                 (record 2 "2026-01-02" {:type :production :inputs [(line "a" 4) (line "b" 2)] :outputs [(line "kit" 2) (line "offcut" 1)]})
                 (record 3 "2026-01-03" {:type :production :inputs [(line "kit" 1)] :outputs [(line "a" 2) (line "b" 1)]})
                 (record 4 "2026-01-04" {:type :production :inputs [(line "a" 1) (line "kit" 1)] :outputs [(line "bundle" 1)]})]
        derived (assert-equivalent records)]
    (is (= 8 (count (:lineage-relationships derived))))
    (is (= ["operation-2" "operation-3" "operation-4"] (mapv :operation-id (:lineage derived))))
    (is (= 7 (get-in derived [:balances ["a" "Main"]])))
    (is (every? #(and (:line-id (:input %)) (:line-id (:output %))) (:lineage-relationships derived)))
    (is (not-any? #(or (:origin-id %) (:unit-id %)) (:lineage-relationships derived)))
    (is (every? #(= :unknown (:allocation %)) (concat (:lineage derived) (:lineage-relationships derived))))))


(deftest equal-time-original-business-order-survives-later-revision
  (let [original (opening 1 "2026-01-01" (line "a" 5))
        sale     (record 2 "2026-01-01" {:type :sale :party "C" :lines [(line "a" 3)]})
        revision (revise 3 original {:kind :opening :occurred-at "2026-01-01" :lines [(line "a" 6)]})
        derived  (assert-equivalent [original sale revision])]
    (is (= ["operation-1" "operation-2"] (mapv :operation-id (:effects derived))))
    (is (= {["a" "Main"] 3} (:balances derived)))))


(deftest timeline-catches-supported-integer-overflow
  (with-model [(opening 1 "2026-01-01" (line "a" Long/MAX_VALUE))
               (record 2 "2026-01-02" {:type :purchase :party "S" :lines [(line "a" 1)]})]
    (fn [conn]
      (is (= (inc (bigint Long/MAX_VALUE)) (get-in (derivation/derive conn graph) [:violations 0 :after]))))))


(deftest typed-datetime-order-matches-replay-at-subsecond-boundaries
  (let [records [(opening 1 "2026-01-01T00:00:00Z" (line "a" 2))
                 (record 2 "2026-01-01T00:00:00.100Z" {:type :sale :party "C" :lines [(line "a" 1)]})
                 (record 3 "2026-01-01T00:00:01+00:00" {:type :purchase :party "S" :lines [(line "a" 2)]})]
        derived (assert-equivalent records)]
    (is (= ["operation-1" "operation-2" "operation-3"] (mapv :operation-id (:effects derived))))
    (is (= {["a" "Main"] 3} (:balances derived)))))


(deftest deterministic-production-trajectories-conserve-stock-without-counting-edges-twice
  (let [random  (java.util.Random. 52417)
        weights {"a" 1 "b" 1 "kit" 4 "scrap" 1 "bundle" 5}]
    (dotimes [_ 20]
      (let [packs            (+ 2 (.nextInt random 9))
            unpacked         (inc (.nextInt random (dec packs)))
            repacked         (- packs unpacked)
            surplus-a        (.nextInt random 5)
            surplus-b        (.nextInt random 5)
            transferred      (inc (.nextInt random (min 3 (+ surplus-a (* 2 unpacked)))))
            sold             (.nextInt random (inc repacked))
            records          (cond-> [(opening 1 "2026-01-01" (line "a" (+ (* 2 packs) surplus-a)) (line "b" (+ (* 3 packs) surplus-b)))
                                      (record 2 "2026-01-02" {:type :production :inputs [(line "a" (* 2 packs)) (line "b" (* 3 packs))] :outputs [(line "kit" packs) (line "scrap" packs)]})
                                      (record 3 "2026-01-03" {:type :production :inputs [(line "kit" unpacked)] :outputs [(line "a" (* 2 unpacked)) (line "b" (* 2 unpacked))]})
                                      (record 4 "2026-01-04" {:type :production :inputs [(line "kit" repacked) (line "scrap" repacked)] :outputs [(line "bundle" repacked)]})
                                      (record 5 "2026-01-05" {:type :transfer :from-location "Main" :to-location "Shop" :lines [(line "a" transferred)]})]
                               (pos? sold) (conj (record 6 "2026-01-06" {:type :sale :party "C" :lines [(line "bundle" sold)]})))
            derived          (assert-equivalent records)
            conserved        (reduce-kv (fn [total [item _] quantity] (+ total (* (weights item) quantity))) 0 (:balances derived))
            history-balances (reduce (fn [balances effect] (update balances [(:item-id effect) (:location effect)] (fnil + 0) (:delta effect))) {} (:effects derived))]
        (is (= (- (+ (* 5 packs) surplus-a surplus-b) (* 5 sold)) conserved))
        (is (= (:balances derived) history-balances))
        (is (= 8 (count (:lineage-relationships derived))))))))

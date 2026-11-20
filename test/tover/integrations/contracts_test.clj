(ns tover.integrations.contracts-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [malli.core :as m]
    [tover.integrations.fixture :as f]
    [tover.integrations.schema :as schema]
    [tover.integrations.semantics :as semantics]
    [tover.shared.rdf :as rdf])
  (:import
    [java.nio.file Files]
    [org.eclipse.rdf4j.model Model Resource]
    [org.eclipse.rdf4j.model.vocabulary RDF]
    [org.eclipse.rdf4j.repository Repository RepositoryConnection]))


(defn codes
  {:malli/schema [:=> [:cat :map] [:set :string]]}
  [result]
  (set (map :code (:findings result))))


(defn second-product
  {:malli/schema [:=> [:cat [:vector :map] :string] [:vector :map]]}
  [records goods]
  (into records
        [(f/record :product "p2" {:connection-id "c" :source-id "source-p2" :current-revision-id "p2-r1"})
         (f/revision "p2-r1" "p2" :product {:name "Shirt" :article "OTHER" :unit "piece"})
         (f/record :goods "goods2" {:unit "piece"})
         (f/mapping "m2" "p2" goods)]))


(defn collision
  {:malli/schema [:=> [:cat :string] [:vector :map]]}
  [goods]
  (into (second-product (f/baseline) goods)
        [(f/identifier-binding "b" "p" :barcode "000123") (f/identifier-binding "b2" "p2" :barcode "000123")]))


(deftest record-contracts-are-open-and-identifiers-are-lossless
  (doseq [record (remove #(#{:goods :location :store} (:kind %)) (f/baseline))]
    (is (m/validate schema/Record (assoc record :future-field "retained")) (pr-str record)))
  (doseq [{:keys [provider identifiers]} (:products (f/cases))]
    (is (every? #(m/validate schema/Identifier %) identifiers))
    (is (= (str (:id provider)) (:value (first identifiers))))
    (is (= (:offer_id provider) (:value (second identifiers))))
    (is (= (set (:barcodes provider)) (set (map :value (filter #(= :barcode (:type %)) identifiers))))))
  (let [ids (:identifiers (first (:products (f/cases))))]
    (is (= #{"100" "99"} (set (map :value (filter #(= :sku (:type %)) ids))))))
  (is (not (m/validate schema/Identifier {:type :sku :value 100})))
  (is (not (m/validate schema/Identifier {:type :sku :value ""})))
  (is (not (m/validate schema/Timestamp "2026-11-06")))
  (is (:valid? (f/check (f/baseline) :source [])))
  (is (:valid? (f/check (f/baseline) :acceptance ["m"]))))


(deftest article-revision-keeps-confirmed-identity-and-observation-history
  (let [original (f/baseline)
        renamed  (-> original
                     (f/change "p" #(assoc % :current-revision-id "p-r2"))
                     (conj (assoc (f/revision "p-r2" "p" :product {:name "Renamed shirt" :article "NEW" :unit "piece"})
                                  :revision 2 :observed-at "2026-11-07T08:00:00Z"))
                     (conj (assoc (f/identifier-binding "old-article" "p" :article "OLD") :active? false))
                     (conj (assoc (f/identifier-binding "new-article" "p" :article "NEW") :source-revision-id "p-r2")))]
    (is (:valid? (f/check renamed :acceptance ["m"])))
    (is (= (first (filter #(= "m" (:id %)) original)) (first (filter #(= "m" (:id %)) renamed))))
    (is (= 2 (count (filter #(= "p" (:entity-id %)) (filter #(= :source-revision (:kind %)) renamed)))))))


(deftest source-collisions-survive-but-acceptance-applies-the-selected-policy
  (let [different (collision "goods2")
        same      (collision "goods")
        strict    (f/change same "policy" #(assoc % :preset :barcode-per-listing))]
    (is (:valid? (f/check different :source [])))
    (is (= #{"barcode-goods"} (codes (f/check different :acceptance ["m"]))))
    (is (= #{:ambiguous} (set (map :category (:findings (f/check different :acceptance ["m"]))))))
    (is (:valid? (f/check same :acceptance ["m"])))
    (is (= #{"barcode-listing"} (codes (f/check strict :acceptance ["m"]))))
    (is (:valid? (f/check (f/change strict "b2" #(assoc % :active? false)) :acceptance ["m"])))
    (testing "competing listings need not be selected to block acceptance"
      (is (= #{"barcode-goods"} (codes (f/check different :acceptance ["m2"]))))))
  (let [records (-> (collision "goods2")
                    (conj (f/record :connection "c2" {:provider "ozon" :account-id "account-2" :store-id "store"
                                                      :enabled? true   :policy-id  "policy"})))
        records (reduce (fn [rs id] (f/change rs id #(assoc % :connection-id "c2"))) records ["p2" "p2-r1" "m2" "b2"])]
    (is (:valid? (f/check records :acceptance ["m" "m2"]))))
  (let [records (-> (collision "goods2")
                    (f/change "m" #(assoc % :state :proposed))
                    (f/change "m2" #(assoc % :state :proposed)))]
    (is (contains? (codes (f/check records :acceptance ["m" "m2"])) "barcode-goods"))))


(deftest required-fields-confirmation-and-counting-basis-are-acceptance-rules
  (let [records (f/change (f/baseline) "policy" #(assoc % :required-fields [:barcode]))]
    (is (:valid? (f/check records :source [])))
    (is (= #{"required-field"} (codes (f/check records :acceptance ["m"]))))
    (is (:valid? (f/check (conj records (f/identifier-binding "barcode" "p" :barcode "000123")) :acceptance ["m"]))))
  (is (= #{"mapping-confirmation"}
         (codes (f/check (f/change (f/baseline) "m" #(dissoc % :confirmed-at :confirmed-by)) :acceptance ["m"]))))
  (is (= #{"identity-changed"}
         (codes (f/check (f/change (f/baseline) "p-r1" #(assoc % :identity-basis "piece:size-58")) :acceptance ["m"]))))
  (is (= #{"counting-basis"}
         (codes (f/check (f/change (f/baseline) "p-r1" #(assoc-in % [:facts :unit] "pack-of-6")) :acceptance ["m"]))))
  (is (:valid? (f/check (second-product (f/baseline) "goods2") :acceptance ["m" "m2"])))
  (is (= #{"disabled-connection"}
         (codes (f/check (f/change (f/baseline) "c" #(assoc % :enabled? false)) :acceptance ["m"]))))
  (is (not (:valid? (f/check (f/baseline) :acceptance [])))))


(deftest sentinel-and-mistyped-identifiers-are-not-matching-keys
  (doseq [binding [(f/identifier-binding "zero" "p" :sku "0")
                   (f/identifier-binding "legacy-sku-in-product-id" "p" :product-id "100")]]
    (let [records (conj (f/baseline) binding)]
      (is (:valid? (f/check records :source [])))
      (is (= #{"unavailable-identifier"} (codes (f/check records :acceptance ["m"]))))))
  (let [records (conj (f/baseline) (f/identifier-binding "sku" "p" :sku "100") (f/identifier-binding "barcode" "p" :barcode "OZN999"))]
    (is (:valid? (f/check records :acceptance ["m"])))
    (is (= #{"100" "OZN999"} (set (map :value (filter #(= :identifier-binding (:kind %)) records)))))))


(deftest multiple-warehouses-and-products-can-point-to-the-same-goods
  (let [records (into (f/baseline)
                      [(f/record :warehouse "wh2" {:connection-id "c" :source-id "source-wh2" :current-revision-id "wh2-r1"})
                       (f/revision "wh2-r1" "wh2" :warehouse {:name "Second warehouse"})
                       (f/record :location "location2" {})
                       (f/mapping "wm2" "wh2" "location2")])]
    (is (:valid? (f/check records :acceptance ["m" "wm" "wm2"])))
    (is (= 1 (count (filter #(= :goods (:kind %)) records))))))


(deftest scope-and-reference-types-cannot-be-disabled-by-policy
  (doseq [preset [:barcode-per-goods :barcode-per-listing]]
    (let [records (f/change (f/baseline) "policy" #(assoc % :preset preset))
          foreign (f/change records "goods" #(assoc % :workspace-id "foreign"))]
      (is (contains? (codes (f/check foreign :source [])) "workspace"))
      (is (contains? (codes (f/check foreign :acceptance ["m"])) "relationship-scope"))
      (is (contains? (codes (f/check (f/change records "m" #(assoc % :target-id "location")) :source [])) "mapping-kind"))))
  (let [records (conj (f/baseline) (f/record :connection "c2" {:provider "ozon" :account-id "other" :store-id "store" :enabled? true :policy-id "policy"}))]
    (is (contains? (codes (f/check (f/change records "m" #(assoc % :connection-id "c2")) :source [])) "connection-scope")))
  (is (contains? (codes (f/check (f/change (f/baseline) "p-r1" #(assoc % :domain :return)) :source [])) "revision-domain"))
  (is (not (:valid? (f/check (f/change (f/baseline) "policy" #(assoc % :preset :anything-goes)) :source [])))))


(deftest event-contract-fixtures-preserve-all-lines-and-refuse-invented-effects
  (doseq [{:keys [case domain complete? candidate expected]} (:events (f/cases))]
    (testing (name case)
      (let [source   (f/record :source-revision "event-r1"
                               {:connection-id "c"          :domain          domain             :source-id (name case) :observed-at f/at
                                :payload-hash  f/hash-value :adapter-version "ozon-contract-v1" :complete? complete?   :facts       {}})
            proposal (f/record :proposal "proposal"
                               {:connection-id "c" :action      :operation :source-revision-ids ["event-r1"]
                                :dependencies  []  :mapping-ids ["m" "wm"] :entry               candidate    :findings []})
            records  (conj (f/baseline) source proposal)
            result   (f/check records :acceptance ["proposal"])]
        (is (m/validate schema/Record source))
        (is (m/validate schema/Record proposal))
        (is (:valid? (f/check records :source [])))
        (is (= expected (codes result)) (pr-str result))
        (is (= (empty? expected) (:valid? result)))
        (is (m/validate schema/ValidationResult result))
        (when (= case :multi-line-dispatch)
          (is (= 2 (count (:lines candidate))))
          (is (= 5 (reduce + (map :quantity (:lines candidate))))))))))


(deftest structured-attributes-retain-values-groups-and-dictionary-identities
  (let [attributes (:normalized (:attributes (f/cases)))
        records    (-> (f/baseline)
                       (f/change "p-r1" #(assoc % :attributes attributes))
                       (conj (f/record :field-definition "field" {:connection-id "c"    :category-id "200"  :type-id    "300"
                                                                  :attribute-id  "4295" :name        "Size" :value-type :string
                                                                  :multiple?     true   :complex?    false}))
                       (conj (f/record :view-preference "view" {:user-id "user-1" :table "goods" :optional-field-ids ["field"]})))
        graph      (f/graph records)]
    (is (every? #(m/validate schema/Attribute %) attributes))
    (is (= ["56" "58"] (mapv :value (:values (first attributes)))))
    (is (= [0 1] (mapv :group-index (rest attributes))))
    (is (= ["8" "9"] (mapv #(-> % :values first :dictionary-id) (rest attributes))))
    (is (= 4 (count (.filter ^Model graph nil (f/term :value) nil (make-array Resource 0)))))
    (is (:valid? (f/check records :source [])))
    (doseq [record (filter #(#{:field-definition :view-preference} (:kind %)) records)]
      (is (m/validate schema/Record record)))))


(deftest conflicting-decisions-and-false-physical-evidence-are-blocked
  (let [records (-> (f/baseline)
                    (f/change "m" #(assoc % :state :proposed))
                    (conj (f/record :goods "goods2" {:unit "piece"}))
                    (conj (assoc (f/mapping "m2" "p" "goods2") :state :proposed)))]
    (is (contains? (codes (f/check records :acceptance ["m" "m2"])) "mapping-target")))
  (let [graph (f/graph (f/baseline))]
    (.add ^Model graph (f/node "w" "goods") RDF/TYPE (f/term :location) (make-array Resource 0))
    (is (contains? (codes (semantics/validate (f/turtle graph) {:workspace-id "w" :phase :source :selected []})) "record-kind")))
  (let [candidate (:candidate (first (:events (f/cases))))
        source    (f/record :source-revision "event" {:connection-id "c"  :domain       :stock       :source-id       "snapshot"
                                                      :observed-at   f/at :payload-hash f/hash-value :adapter-version "v1"
                                                      :complete?     true :facts        {}})
        proposal  (f/record :proposal "proposal" {:connection-id "c" :action      :operation :source-revision-ids ["event"]
                                                  :dependencies  []  :mapping-ids ["m" "wm"] :entry               candidate :findings []})
        records   (conj (f/baseline) source proposal)]
    (is (:valid? (f/check records :source [])))
    (is (= #{"source-effect"} (codes (f/check records :acceptance ["proposal"]))))
    (is (contains? (codes (f/check (f/change records "proposal" #(assoc-in % [:entry :lines 1 :quantity] "1.5"))
                                   :acceptance ["proposal"])) "line-quantity"))
    (is (contains? (codes (f/check (f/change records "proposal" #(assoc-in % [:entry :lines 1 :product-id] "unknown"))
                                   :acceptance ["proposal"])) "line-mapping"))))


(deftest acceptance-provenance-and-catalogue-only-decisions-have-contracts
  (let [proposal   (f/record :proposal "catalogue" {:connection-id "c" :action      :catalogue :source-revision-ids ["p-r1"]
                                                    :dependencies  []  :mapping-ids []         :entry               {:name "Shirt" :unit "piece"} :findings []})
        provenance (f/record :acceptance-provenance "receipt" {:connection-id      "c"     :source-event-key   "posting:1:dispatch"
                                                               :source-revision-id "p-r1"  :accepted-entity-id "goods"
                                                               :accepted-revision  1       :receipt-id         "command-1"          :accepted-at f/at
                                                               :mapping-revisions  {"m" 1} :policy-revision    1})
        records    (conj (f/baseline) proposal provenance)]
    (is (m/validate schema/Record proposal))
    (is (m/validate schema/Record provenance))
    (is (:valid? (f/check records :acceptance ["catalogue"])))
    (is (= #{"required-field"}
           (codes (f/check (f/change records "policy" #(assoc % :required-fields [:barcode])) :acceptance ["catalogue"]))))
    (is (not (m/validate schema/Record (assoc provenance :accepted-revision 0))))))


(deftest incomplete-source-evidence-survives-and-dependencies-are-validated
  (let [source   (f/record :source-revision "unknown-event" {:connection-id "c"          :domain          :posting :observed-at f/at
                                                             :payload-hash  f/hash-value :adapter-version "v1"
                                                             :complete?     false        :facts           {}})
        proposal (f/record :proposal "operation" {:connection-id "c" :action      :operation :source-revision-ids ["unknown-event"]
                                                  :dependencies  []  :mapping-ids []         :entry               {:effect :sale}   :findings []})
        parent   (f/record :proposal "parent" {:connection-id "c"           :action      :catalogue :source-revision-ids ["p-r1"]
                                               :dependencies  ["operation"] :mapping-ids []         :entry               {}       :findings []})
        records  (conj (f/baseline) source proposal parent)]
    (is (m/validate schema/Record source))
    (is (:valid? (f/check records :source [])))
    (is (:valid? (f/check records :acceptance ["m"])))
    (is (= #{"source-response" "physical-event"} (codes (f/check records :acceptance ["parent"]))))))



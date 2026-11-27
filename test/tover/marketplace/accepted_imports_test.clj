(ns tover.marketplace.accepted-imports-test
  "Board chapter 45 slice 9 · Read accepted imported operations and provenance: an accepted record that came from a
  connection names its source operation and revision, who accepted it and how, the mapping each line used, later
  source revisions and their pending proposal. On the Operations page the source badge marks the record and its
  Revisions panel opens the provenance; manual records are unchanged and no buyer data reaches the page."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [dev.onionpancakes.chassis.core :as chassis]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.operations.web :as operations-web]
    [tover.marketplace.corrections-test :as ct]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.operations-test :as ot]
    [tover.marketplace.operations.model :as operations]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.shared.web.core]
    [tover.support :as support]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.inventory.references 'tover.marketplace.connections.model 'tover.marketplace.sync.model
                          'tover.marketplace.products.model 'tover.marketplace.products.guard 'tover.marketplace.warehouses.model
                          'tover.marketplace.cutover.model 'tover.marketplace.provenance 'tover.marketplace.operations.model
                          'tover.marketplace.operations.review 'tover.marketplace.operations.corrections
                          'tover.workspaces.currency.model 'tover.shared.web.core 'tover.inventory.operations.web])
    (f))
  guard-test/unit-authority)


(def c ot/c)
(def workspace ot/workspace)
(def member ot/member)
(def connector (sync/connector-actor c))
(def sale (str c ":fbs-posting:P1"))
(def record (provenance/import-entry-id sale))
(def ^{:malli/schema (:malli/schema (meta #'ct/setup!))} setup! @#'ct/setup!)
(def ^{:malli/schema (:malli/schema (meta #'ct/posting))} posting @#'ct/posting)
(def ^{:malli/schema (:malli/schema (meta #'ct/save!))} save! @#'ct/save!)
(def ^{:malli/schema (:malli/schema (meta #'ct/accept-all!))} accept-all! @#'ct/accept-all!)
(def ^{:malli/schema (:malli/schema (meta #'ot/operation))} operation @#'ot/operation)
(def ^{:malli/schema (:malli/schema (meta #'ot/seed!))} seed! @#'ot/seed!)


(defn- execute!
  {:malli/schema [:=> [:cat :map :string :map] :any]}
  [ctx command-id input]
  (runtime/execute! ctx ::entries/save-shared-inventory-entry {:command-id command-id :input input}))


(defn- provenance-of
  {:malli/schema [:=> [:cat :map [:* :any]] [:vector :map]]}
  [ctx & {:as input}]
  (:records (runtime/read-model ctx ::operations/accepted-imported-operations-and-provenance (or input {}))))


(defn- record-of
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [ctx record-id]
  (some #(when (= record-id (:record-id %)) %) (provenance-of ctx)))


(defn- table
  "The Operations page's table, as the page sends it."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ctx view]
  (charred/read-json (charred/write-json-str (:model (operations-web/query-page (assoc ctx :request {:tover/identity {:workspace-id workspace}}) view)))
                     :key-fn keyword))


(defn- column
  {:malli/schema [:=> [:cat :map :string :string] :any]}
  [model group key]
  (let [x (.indexOf ^java.util.List (mapv :key (:columns model)) key)
        c (get (:cells (some #(when (= group (:groupId %)) %) (:rows model))) x)]
    (or (:display c) (:value c))))


;; Board 45 slice 9 · Read accepted imported operations and provenance


(deftest automatic-acceptance
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic!]} (setup! repo)]
        (testing "Given P1 accepted automatically"
          (observe! (posting))
          (intake!)
          (is (= 1 (automatic!))))
        (testing "then its record names the source operation, revision, connector and the mapping per line"
          (let [row (record-of ctx record)]
            (is (= {:record-id       record :source-operation-id sale            :connection-id c    :connection-label "Ozon · Shop"
                    :revision        1      :accepted-by         (str connector) :automatic     true :edited           false
                    :later-revisions []}
                   (select-keys row [:record-id :source-operation-id :connection-id :connection-label :revision :accepted-by
                                     :automatic :edited :later-revisions])))
            (is (= [{:line-key "1" :external-product-id (str c ":100") :listing "Mug red" :mapping-revision 1 :stock-item-id "red"}]
                   (:mappings row)))
            (is (some? (:accepted-at row)))
            (is (nil? (:pending-proposal row)))))
        (testing "and another connection's filter shows none"
          (is (empty? (provenance-of ctx :connection-id "other")))
          (is (= [record] (map :record-id (provenance-of ctx :connection-id c)))))))))


(deftest edited-acceptance
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! entry-of]} (setup! repo)
            id (entry-of "P1")]
        (testing "Given a member edited P1 to 2 and accepted it"
          (observe! (posting))
          (intake!)
          (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                           :lines       [{:line-id (str sale "/1") :stock-item-id "red" :warehouse-id "home" :quantity 2}]})
          (accept-all! ctx "acc-edit" [[id 2]]))
        (testing "then the member accepted it, edited"
          (is (= [(str member) false true 1]
                 ((juxt :accepted-by :automatic :edited :revision) (record-of ctx record)))))))))


(deftest pending-amendment
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! corrections!]} (setup! repo)]
        (testing "Given P1 accepted automatically at revision 1"
          (observe! (posting))
          (intake!)
          (automatic!))
        (testing "and a later revision stating 2"
          (observe! (posting :quantity "2"))
          (is (= 1 (corrections!))))
        (testing "then the record lists the later revision and its pending amendment"
          (let [row (record-of ctx record)]
            (is (= 1 (:revision row)) "the record still reflects revision 1")
            (is (= [[2 "delivered" true]] (map (juxt :revision :status (comp some? :observed-at)) (:later-revisions row)))
                "a later revision says its number, status and when it was observed")
            (is (= {:entry-id (provenance/correction-entry-id sale 1) :kind "amendment" :revision 2 :source-conflict false}
                   (:pending-proposal row)))))
        (testing "and once the amendment is accepted the record reflects revision 2, with nothing pending"
          (accept-all! ctx "acc-fix" [[(provenance/correction-entry-id sale 1) 1]])
          (let [row (record-of ctx record)]
            (is (= [2 [] nil] ((juxt :revision :later-revisions :pending-proposal) row)))))))))


;; The Operations page


(deftest the-source-badge-marks-accepted-imported-records
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! corrections!]} (setup! repo)]
        (execute! ctx "m1" {:workspace-id workspace :entry-id "m1" :kind "sale" :occurred-at "2026-11-05T09:00:00Z"
                            :lines        [{:line-id "m1/1" :stock-item-id "red" :warehouse-id "home" :quantity 1}]})
        (accept-all! ctx "acc-m1" [["m1" 1]])
        (observe! (posting))
        (intake!)
        (automatic!)
        (observe! (posting :quantity "2"))
        (corrections!)
        (let [model (table ctx {})]
          (testing "the accepted imported record carries its connection; the manual one stays blank"
            (is (= "Ozon · Shop" (column model record "source")))
            (is (= "" (column model "m1" "source"))))
          (testing "its Revisions panel opens the provenance, read-only"
            (let [details (get-in model [:provenance (keyword record)])]
              (is (= "Ozon · Shop" (:source details)))
              (is (= "fbs-posting:P1" (:operation details)))
              (is (= 1 (:revision details)))
              (is (= "Accepted automatically" (:how details)))
              (is (= ["Mug red → Mug / red · mapping revision 1"] (:lines details)))
              (is (= [2] (map :revision (:later details))))
              (is (= "Amendment pending" (:pending details)))
              (is (= "Revisions and source" (some #(when (= "item-history" (:type %)) (:label %))
                                                  (mapcat :actions (mapcat :cells (filter #(= record (:groupId %)) (:rows model))))))))
            (is (nil? (get-in model [:provenance :m1])))
            (is (= "Revisions" (some #(when (= "item-history" (:type %)) (:label %))
                                     (mapcat :actions (mapcat :cells (filter #(= "m1" (:groupId %)) (:rows model))))))
                "a manual record keeps its Revisions action"))
          (testing "the pending amendment stays in place on the record"
            (is (some #(= (provenance/correction-entry-id sale 1) (:entry %)) (:rows model)))))))))


(deftest no-buyer-data-in-the-provenance
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx intake! automatic!]} (setup! repo)
            buyer (observations/write-json {:customer {:name "Ivan Petrov" :phone "+79990001122" :address "Lenina 1, Tver"}})]
        (seed! ctx ::sync/source-operation-observed (operation "P1" 1 :facts buyer))
        (intake!)
        (is (= 1 (automatic!)))
        (seed! ctx ::sync/source-operation-observed (operation "P1" 2 :facts buyer :lines (@#'ot/lines "100" "2")))
        (let [{:bel/keys [fn query]} (bel/get-page ::operations-web/page)
              html                   (chassis/html (:html (fn (query (assoc ctx :request {:tover/identity {:workspace-id workspace}}) {}))))]
          (is (str/includes? html "Accepted automatically") "the provenance is on the page")
          (doseq [text ["Ivan" "Petrov" "79990001122" "Lenina"]]
            (is (not (str/includes? html text)) text)))))))


(deftest manual-records-read-no-provenance
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx]} (setup! repo)]
        (execute! ctx "m1" {:workspace-id workspace :entry-id "m1" :kind "sale" :occurred-at "2026-11-05T09:00:00Z"
                            :lines        [{:line-id "m1/1" :stock-item-id "red" :warehouse-id "home" :quantity 1}]})
        (accept-all! ctx "acc-m1" [["m1" 1]])
        (is (empty? (provenance-of ctx)))
        (is (= 1 (count (runtime/read-model ctx ::acceptance/accepted-operations {}))))))))


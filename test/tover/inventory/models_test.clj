(ns tover.inventory.models-test
  "Inventory Bel models round-trip through the catalogue RDF store; draft line diffs are pure."
  (:require
    [bel.core :as bel :refer [defcommand]]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock-items.model :as items]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(def pending
  "Events for the next `emit`; command input coercion would strip their open maps."
  (atom []))


(defcommand emit
  {:input [:map] :policy ::records/workspace-member
   :emits #{::items/stock-item-registered ::items/stock-item-barcode-assigned ::entries/shared-inventory-entry-saved
            ::entries/inventory-entry-line-saved ::entries/inventory-entry-line-removed
            ::entries/inventory-entry-line-barcode-added ::entries/inventory-entry-line-barcode-removed
            ::acceptance/inventory-changes-accepted ::acceptance/inventory-record-accepted
            ::acceptance/inventory-record-line-accepted}}
  [_ _]
  {:events @pending})


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.suppliers.model
                          'tover.catalogues.stores.model 'tover.catalogues.categories.model
                          'tover.catalogues.products.model 'tover.inventory.stock-items.model
                          'tover.inventory.entries.model 'tover.inventory.acceptance.model 'tover.inventory.models-test])
    (f))
  (fn [f]
    ;; Membership guards run in the store integration suite; here the principal comes from the context.
    (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                           (read (assoc context :principal (select-keys context [:user-id :memberships]))))]
      (f))))


(def user #uuid "00000000-0000-0000-0000-000000000011")
(def at (Instant/parse "2026-11-20T10:00:00Z"))


(defn context
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo}) :repo repo :workspace-id (:workspace-id support/scope)
                            :user-id     user
                            :memberships [{:workspace-id (:workspace-id support/scope) :role :member}]}))


(def counter (atom 0))


(defn emit!
  {:malli/schema [:=> [:cat :map [:vector :map]] :map]}
  [ctx events]
  (reset! pending events)
  (runtime/execute! ctx ::emit {:command-id (str "emit-" (swap! counter inc)) :input {}}))


(defn fetch
  {:malli/schema [:=> [:cat :map :keyword :keyword :string] [:maybe :map]]}
  [ctx model id-key id]
  (runtime/read-model ctx model {id-key id}))


(def item {:stock-item-id "item-1" :product-id "mug" :variant-label "M" :condition "regular" :status "active" :revision 1})
(def barcode {:barcode-id "item-1|4600000000017" :stock-item-id "item-1" :barcode "4600000000017"})
(def entry {:entry-id "entry-1" :kind "opening" :occurred-at at :status "pending" :revision 1 :saved-by user})
(def line {:entry-line-id "entry-1/l1" :entry-id "entry-1" :line-id   "l1"     :position 0 :stock-item-id "item-1"
           :warehouse-id  "main"       :quantity 5         :unit-cost "100.00"})
(def line-barcode {:entry-line-barcode-id "entry-1/l1|4600000000024" :entry-line-id "entry-1/l1" :barcode "4600000000024"})
(def accepted {:acceptance-id "acc-1" :actor user :recorded-at at :record-count 1})
(def record {:record-revision-id "rec-1#1" :record-id "rec-1" :revision  1     :acceptance-id "acc-1" :kind "opening"
             :occurred-at        at        :sequence  0       :cancelled false})
(def record-line {:record-line-id "rec-1#1/0" :record-revision-id "rec-1#1" :position  0        :stock-item-id "item-1"
                  :warehouse-id   "main"      :quantity-change    5         :unit-cost "100.00"})


(deftest models-round-trip-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (emit! ctx [{:type ::items/stock-item-registered :data item}
                    {:type ::items/stock-item-barcode-assigned :data barcode}
                    {:type ::entries/shared-inventory-entry-saved :data entry}
                    {:type ::entries/inventory-entry-line-saved :data line}
                    {:type ::entries/inventory-entry-line-barcode-added :data line-barcode}
                    {:type ::acceptance/inventory-changes-accepted :data accepted}
                    {:type ::acceptance/inventory-record-accepted :data record}
                    {:type ::acceptance/inventory-record-line-accepted :data record-line}])
        (is (= item (fetch ctx ::items/stock-items :stock-item-id "item-1")))
        (is (= barcode (fetch ctx ::items/stock-item-barcodes :barcode-id (items/barcode-id "item-1" "4600000000017"))))
        (is (= entry (fetch ctx ::entries/entries :entry-id "entry-1")))
        (is (= line (fetch ctx ::entries/entry-lines :entry-line-id (entries/entry-line-id "entry-1" "l1"))))
        (is (= line-barcode (fetch ctx ::entries/entry-line-barcodes :entry-line-barcode-id "entry-1/l1|4600000000024")))
        (is (= accepted (fetch ctx ::acceptance/acceptances :acceptance-id "acc-1")))
        (is (= record (fetch ctx ::acceptance/records :record-revision-id (acceptance/record-revision-id "rec-1" 1))))
        (is (= record-line (fetch ctx ::acceptance/record-lines :record-line-id (acceptance/record-line-id "rec-1#1" 0))))
        (testing "saving a line without a cost clears it"
          (emit! ctx [{:type ::entries/inventory-entry-line-saved :data (dissoc line :unit-cost)}])
          (is (= (dissoc line :unit-cost) (fetch ctx ::entries/entry-lines :entry-line-id "entry-1/l1"))))
        (testing "a removed line and its barcode are gone"
          (emit! ctx (entries/line-events "entry-1" [{:line-id "l1" :barcodes ["4600000000024"]}] []))
          (is (nil? (fetch ctx ::entries/entry-lines :entry-line-id "entry-1/l1")))
          (is (nil? (fetch ctx ::entries/entry-line-barcodes :entry-line-barcode-id "entry-1/l1|4600000000024"))))))))


(deftest line-events-diff
  (let [events (entries/line-events "e" [{:line-id "l1" :barcodes ["a"]} {:line-id "l2" :barcodes ["b" "c"]}]
                                    [{:line-id "l2" :barcodes ["c" "d"] :quantity 2} {:line-id "l3"}])]
    (is (= [[::entries/inventory-entry-line-barcode-removed {:entry-line-barcode-id "e/l1|a"}]
            [::entries/inventory-entry-line-removed {:entry-line-id "e/l1"}]
            [::entries/inventory-entry-line-saved {:entry-line-id "e/l2" :entry-id "e" :line-id "l2" :position 0 :quantity 2}]
            [::entries/inventory-entry-line-barcode-removed {:entry-line-barcode-id "e/l2|b"}]
            [::entries/inventory-entry-line-barcode-added {:entry-line-barcode-id "e/l2|d" :entry-line-id "e/l2" :barcode "d"}]
            [::entries/inventory-entry-line-saved {:entry-line-id "e/l3" :entry-id "e" :line-id "l3" :position 1}]]
           (map (juxt :type :data) events)))))


(deftest assemble-restores-lines-in-order
  (is (= [{:line-id "l2" :quantity 1 :barcodes ["a" "b"]} {:line-id "l1"}]
         (entries/assemble [{:entry-line-id "e/l1" :entry-id "e" :line-id "l1" :position 1}
                            {:entry-line-id "e/l2" :entry-id "e" :line-id "l2" :position 0 :quantity 1}]
                           [{:entry-line-id "e/l2" :barcode "b"} {:entry-line-id "e/l2" :barcode "a"}]))))

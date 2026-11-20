(ns tover.inventory.entries.model
  "Board chapters 16, 14 and 32a · Shared pending inventory entries. Each draft line and each barcode typed for a
  new stock item is its own entity, so SHACL and SPARQL see them; removed lines are deleted."
  (:require
    [bel.core :refer [defevent defmodel]]
    [bel.validation :as validation]
    [tover.catalogues.records :as records :refer [Id]]))


(def Entry
  [:map
   [:entry-id Id]
   [:kind [:enum "opening"]]
   [:occurred-at {:optional true} validation/Instant]
   [:reason {:optional true} :string]
   [:status [:enum "pending" "accepted" "discarded"]]
   [:revision [:int {:min 1}]]
   [:saved-by :uuid]])


(def line-fields
  "A line names an existing stock item, or a new one by product id or product name plus its variant fields."
  [[:stock-item-id {:optional true :bel/ref :tover.inventory.stock-items.model/stock-items} Id]
   [:product-id {:optional true :bel/ref :tover.catalogues.products.model/products} Id]
   [:product-name {:optional true} :string]
   [:variant-label {:optional true} :string]
   [:article {:optional true} :string]
   [:unit {:optional true} :string]
   [:warehouse-id {:optional true :bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   [:warehouse-name {:optional true} :string]
   [:quantity {:optional true} :int]
   [:unit-cost {:optional true} :string]])


(def EntryLine
  "Id is `entry-id/line-id`."
  (into [:map [:entry-line-id Id] [:entry-id {:bel/ref ::entries} Id] [:line-id Id] [:position [:int {:min 0}]]]
        line-fields))


(def EntryLineBarcode
  "Id is `entry-line-id|barcode`."
  [:map [:entry-line-barcode-id Id] [:entry-line-id {:bel/ref ::entry-lines} Id] [:barcode Id]])


(def Line
  "A draft line as commands take it and read models return it; order is the vector order."
  (into [:map [:line-id Id] [:barcodes {:optional true} [:vector Id]]] line-fields))


(def ^:private entry-fields
  [:kind :occurred-at :reason :status :revision :saved-by])


(def ^:private line-keys
  (into [:entry-id :line-id :position] (map first) line-fields))


(defevent shared-inventory-entry-saved {:schema Entry})
(defevent inventory-draft-discarded {:schema [:map [:entry-id Id] [:status [:enum "discarded"]] [:revision [:int {:min 1}]]]})
(defevent shared-inventory-entry-accepted {:schema [:map [:entry-id Id] [:status [:enum "accepted"]] [:revision [:int {:min 1}]]]})
(defevent inventory-entry-line-saved {:schema EntryLine})
(defevent inventory-entry-line-removed {:schema [:map [:entry-line-id Id]]})
(defevent inventory-entry-line-barcode-added {:schema EntryLineBarcode})
(defevent inventory-entry-line-barcode-removed {:schema [:map [:entry-line-barcode-id Id]]})


(defmodel entries
  {:id     :entry-id
   :store  :catalogue
   :schema Entry
   :policy ::records/workspace-member
   ;; `apply-model!` writes a missing entity, so the first save needs no separate create.
   :events {::shared-inventory-entry-saved    {:set entry-fields}
            ::inventory-draft-discarded       {:set [:status :revision]}
            ::shared-inventory-entry-accepted {:set [:status :revision]}}})


(defmodel entry-lines
  {:id     :entry-line-id
   :store  :catalogue
   :schema EntryLine
   :policy ::records/workspace-member
   :events {::inventory-entry-line-saved   {:set line-keys}
            ::inventory-entry-line-removed {:delete true}}})


(defmodel entry-line-barcodes
  {:id     :entry-line-barcode-id
   :store  :catalogue
   :schema EntryLineBarcode
   :policy ::records/workspace-member
   :events {::inventory-entry-line-barcode-added   {:create [:entry-line-id :barcode]}
            ::inventory-entry-line-barcode-removed {:delete true}}})


(defn entry-line-id
  {:malli/schema [:=> [:cat :string :string] :string]}
  [entry-id line-id]
  (str entry-id "/" line-id))


(defn- barcode-id
  {:malli/schema [:=> [:cat :string :string] :string]}
  [entry-line-id barcode]
  (str entry-line-id "|" barcode))


(defn- removed
  {:malli/schema [:=> [:cat :string [:sequential :string]] [:vector :map]]}
  [entry-line-id barcodes]
  (mapv (fn [barcode] {:type ::inventory-entry-line-barcode-removed :data {:entry-line-barcode-id (barcode-id entry-line-id barcode)}})
        barcodes))


(defn line-events
  "Events that turn the stored lines `old` into `new`: every new line is saved at its vector position; lines and
  barcodes no longer present are removed."
  {:malli/schema [:=> [:cat :string [:sequential Line] [:sequential Line]] [:vector :map]]}
  [entry-id old new]
  (let [before (into {} (map (juxt :line-id identity)) old)
        kept   (set (map :line-id new))]
    (-> []
        (into (mapcat (fn [{:keys [line-id barcodes]}]
                        (let [id (entry-line-id entry-id line-id)]
                          (conj (removed id (or barcodes [])) {:type ::inventory-entry-line-removed :data {:entry-line-id id}}))))
              (remove (comp kept :line-id) old))
        (into (mapcat (fn [position {:keys [line-id barcodes] :as line}]
                        (let [id       (entry-line-id entry-id line-id)
                              previous (set (:barcodes (get before line-id)))
                              current  (set barcodes)]
                          (concat [{:type ::inventory-entry-line-saved
                                    :data (assoc (dissoc line :barcodes) :entry-line-id id :entry-id entry-id :position position)}]
                                  (removed id (sort (remove current previous)))
                                  (for [barcode (sort (remove previous current))]
                                    {:type ::inventory-entry-line-barcode-added
                                     :data {:entry-line-barcode-id (barcode-id id barcode) :entry-line-id id :barcode barcode}}))))
                      (range) new)))))


(defn assemble
  "Stored line and barcode rows of one entry as `Line`s in position order."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector Line]]}
  [lines barcodes]
  (let [by-line (group-by :entry-line-id barcodes)]
    (mapv (fn [{:keys [entry-line-id] :as line}]
            (let [codes (sort (map :barcode (get by-line entry-line-id)))]
              (cond-> (dissoc line :entry-line-id :entry-id :position)
                (seq codes) (assoc :barcodes (vec codes)))))
          (sort-by :position lines))))

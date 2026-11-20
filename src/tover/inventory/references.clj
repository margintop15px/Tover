(ns tover.inventory.references
  "Board chapter 32a · Draft line identities: suggestions while typing and resolution of an entered barcode or name.
  A typing aid only; nothing here persists. Active records only."
  (:refer-clojure :exclude [resolve])
  (:require
    [clojure.string :as str]
    [tover.catalogues.records :as records]))


(def Facts
  [:map [:products [:sequential :map]] [:stock-items [:sequential :map]] [:barcodes [:sequential :map]]
   [:warehouses {:optional true} [:sequential :map]]])


(defn- active
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [rows]
  (filter #(= "active" (:status %)) rows))


(defn- key-of
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [text]
  (records/name-key (str/replace (str/trim (or text "")) #"\s+" " ")))


(defn- items-of
  {:malli/schema [:=> [:cat Facts :string] [:vector :string]]}
  [{:keys [stock-items]} product-id]
  (vec (sort (map :stock-item-id (filter #(= product-id (:product-id %)) (active stock-items))))))


(defn- one-or-choice
  {:malli/schema [:=> [:cat [:vector :string] :string] :map]}
  [items product-id]
  (if (= 1 (count items)) {:stock-item-id (first items)} {:choose items :product-id product-id}))


(defn resolve
  "An exact barcode resolves to its owner; an exact product name to its only item or a choice; other text is the
  intent to create a product of that name. Blank text resolves to nothing."
  {:malli/schema [:=> [:cat :string Facts] [:maybe :map]]}
  [text {:keys [products stock-items barcodes] :as facts}]
  (let [value   (str/trim text)
        item    (into {} (map (juxt :stock-item-id identity)) (active stock-items))
        owners  (keep (comp item :stock-item-id) (filter #(= value (:barcode %)) barcodes))
        product (first (filter #(= (key-of value) (key-of (:name %))) (active products)))]
    (cond
      (str/blank? value) nil
      (seq owners) (one-or-choice (vec (sort (map :stock-item-id owners))) (:product-id (first owners)))
      product (one-or-choice (items-of facts (:product-id product)) (:product-id product))
      :else {:new-product value})))


(defn- warehouse-prefill
  "The product's default warehouse when it is active."
  {:malli/schema [:=> [:cat Facts :map] [:maybe :string]]}
  [{:keys [warehouses]} product]
  (let [id (:default-warehouse-id product)]
    (when (some #(and (= id (:warehouse-id %)) (= "active" (:status %))) warehouses) id)))


(defn suggest
  "Stock-item suggestions fill every known field of the item; product suggestions fill the name and the product's
  default warehouse. Matched on product name, variant, article or barcode, ignoring case and spacing; at most 20."
  {:malli/schema [:=> [:cat :string Facts] [:vector :map]]}
  [text {:keys [products stock-items barcodes] :as facts}]
  (let [query    (key-of text)
        hit?     #(str/includes? (key-of %) query)
        products (filter #(= "active" (:status %)) products)
        by-id    (into {} (map (juxt :product-id identity)) products)
        codes    (group-by :stock-item-id barcodes)
        items    (for [{:keys [stock-item-id product-id variant-label article unit]} (active stock-items)
                       :let [product (by-id product-id)
                             own (vec (sort (map :barcode (codes stock-item-id))))]
                       :when (and product (some hit? (concat [(:name product) variant-label article] own)))]
                   {:kind  "stock-item" :id stock-item-id
                    :label (str (:name product) (when variant-label (str " / " variant-label)) (when article (str " · " article)))
                    :fill  (cond-> {:stock-item-id stock-item-id :product-id product-id :product-name (:name product)}
                             variant-label (assoc :variant-label variant-label)
                             article (assoc :article article)
                             (seq own) (assoc :barcodes own)
                             unit (assoc :unit unit))})
        named    (for [product products :when (hit? (:name product))]
                   {:kind "product" :id (:product-id product) :label (:name product)
                    :fill (cond-> {:product-id (:product-id product) :product-name (:name product)}
                            (warehouse-prefill facts product) (assoc :warehouse-id (warehouse-prefill facts product)))})]
    (vec (take 20 (concat items named)))))

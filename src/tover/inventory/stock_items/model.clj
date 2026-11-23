(ns tover.inventory.stock-items.model
  "Board chapters 32a, 17 and 15 · Stock items and their barcodes. Both are registered and changed only by acceptance."
  (:require
    [bel.core :refer [defevent defmodel]]
    [tover.catalogues.records :as records :refer [Id]]))


(def units
  "The units a new stock item may have, in display order. Items registered earlier keep theirs."
  ["pcs" "kg" "g" "l" "ml" "m" "pack"])


(defn unit?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [unit]
  (boolean (some #{unit} units)))


(def StockItem
  [:map
   [:stock-item-id Id]
   [:product-id {:bel/ref :tover.catalogues.products.model/products} Id]
   [:variant-label {:optional true} :string]
   [:article {:optional true} :string]
   [:unit {:optional true} :string]
   [:condition [:enum "regular" "defective"]]
   [:regular-item-id {:optional true :bel/ref ::stock-items} Id]
   [:status [:enum "active" "archived"]]
   [:revision [:int {:min 1}]]])


(def Barcode
  "Id is `stock-item-id|barcode`, so one item never holds the same barcode twice."
  [:map [:barcode-id Id] [:stock-item-id {:bel/ref ::stock-items} Id] [:barcode Id]])


(def ^:private Reviewed
  [:map [:stock-item-id Id] [:status [:enum "active" "archived"]] [:revision [:int {:min 1}]]])


(defevent stock-item-registered {:schema StockItem})
(defevent stock-item-barcode-assigned {:schema Barcode})
;; Chapter 15 · applied only by accepting a reviewed correction. Updated and Moved carry the resulting values:
;; `:set` replaces every listed field, so an absent label or article is cleared.
(defevent stock-item-updated
  {:schema [:map [:stock-item-id Id] [:variant-label {:optional true} :string] [:article {:optional true} :string]
            [:unit {:optional true} :string] [:revision [:int {:min 1}]]]})
(defevent stock-item-moved
  {:schema [:map [:stock-item-id Id] [:previous-product-id Id] [:product-id Id] [:variant-label {:optional true} :string]
            [:revision [:int {:min 1}]]]})
(defevent stock-item-archived {:schema Reviewed})
(defevent stock-item-restored {:schema Reviewed})
(defevent stock-item-barcode-removed {:schema [:map [:barcode-id Id]]})


(defmodel stock-items
  {:id     :stock-item-id
   :store  :catalogue
   :schema StockItem
   :policy ::records/workspace-member
   :events {::stock-item-registered {:create [:product-id :variant-label :article :unit :condition :regular-item-id
                                              :status :revision]}
            ::stock-item-updated    {:set [:variant-label :article :unit :revision]}
            ::stock-item-moved      {:set [:product-id :variant-label :revision]}
            ::stock-item-archived   {:set [:status :revision]}
            ::stock-item-restored   {:set [:status :revision]}}})


(defmodel stock-item-barcodes
  {:id     :barcode-id
   :store  :catalogue
   :schema Barcode
   :policy ::records/workspace-member
   :events {::stock-item-barcode-assigned {:create [:stock-item-id :barcode]}
            ::stock-item-barcode-removed  {:delete true}}})


(defn barcode-id
  {:malli/schema [:=> [:cat :string :string] :string]}
  [stock-item-id barcode]
  (str stock-item-id "|" barcode))

(ns tover.stock.schema
  (:require [tover.shared.schema :as shared]))


(def Quantity
  [:int {:min 0 :max Long/MAX_VALUE}])


(def Delta
  [:int {:min Long/MIN_VALUE :max Long/MAX_VALUE}])


(def InputText
  [:maybe [:string {:max 200}]])


(def Row
  [:map [:id shared/Text] [:name shared/Text] [:article shared/Text] [:unit shared/Text]
   [:location shared/Text] [:quantity Quantity]])


(def Rows [:vector Row])


(def Receipt
  [:map [:id :uuid] [:row-id shared/Text] [:before Quantity] [:after Quantity] [:delta Delta]])


(def HistoryEntry
  [:map [:id :uuid] [:row-id shared/Text] [:before Quantity] [:after Quantity] [:delta Delta]
   [:reason shared/Text] [:actor {:optional true} shared/Text] [:recorded-at :string] [:effective-at :string]])


(def Snapshot
  [:map [:rows Rows] [:history [:vector HistoryEntry]]])


(def Command
  [:map [:row-id shared/Text] [:after Quantity] [:reason shared/Text] [:actor {:optional true} shared/Text]])


(def Proposal
  [:map [:row-id shared/Text] [:row Row] [:before Quantity] [:after Quantity]])


(def Editing
  [:map [:row Row]])


(def Interpretation
  [:multi {:dispatch :type}
   [:search [:map [:type [:= :search]] [:query :string] [:rows Rows]]]
   [:adjustment [:map [:type [:= :adjustment]] [:row Row] [:after Quantity]]]
   [:choose [:map [:type [:= :choose]] [:matches Rows] [:after Quantity]]]
   [:error [:map [:type [:= :error]] [:message :string]]]])


(def HistoryData
  [:map [:row [:maybe Row]] [:history [:vector HistoryEntry]]])


(def UIModel
  [:map [:rows Rows] [:evidence [:map-of shared/Text HistoryEntry]] [:history HistoryData]
   [:editing [:maybe Editing]] [:proposal [:maybe Proposal]] [:interpretation Interpretation]
   [:operations {:optional true} [:vector :map]]])


(def StockInput
  [:map [:query [:string {:max 200}]]])


(def EditInput
  [:map [:query [:string {:max 200}]] [:rowId shared/Text]])


(def PreviewQuantityInput
  [:map [:query [:string {:max 200}]] [:rowId shared/Text] [:quantity :string]])


(def SmartInput StockInput)


(def ChooseInput EditInput)


(def AcceptInput
  [:map [:query [:string {:max 200}]] [:rowId shared/Text]
   [:quantity :string] [:reason [:string {:max 500}]]])


(def HistoryInput
  [:map [:rowId shared/Text]])


(def DraftCell
  [:map [:rowId shared/Text]
   [:field [:enum "name" "quantity" "unit" "location" "article" "evidence"]]
   [:value :string]])


(def DraftRow
  [:map [:id shared/Text] [:name :string] [:quantity :string] [:unit :string]
   [:location :string] [:article :string] [:evidence :string]])


(def DraftPreviewInput
  [:map
   [:deletedRows {:optional true} [:vector shared/Text]]
   [:baseline {:optional true} [:vector DraftCell]]
   [:localRows {:optional true} [:vector DraftRow]]
   [:addedRows {:optional true} [:vector DraftRow]]
   [:cells [:vector DraftCell]]])

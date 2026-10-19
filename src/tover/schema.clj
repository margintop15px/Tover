(ns tover.schema
  (:require [clojure.string :as str]))


(def Quantity
  [:int {:min 0 :max Long/MAX_VALUE}])


(def Delta
  [:int {:min Long/MIN_VALUE :max Long/MAX_VALUE}])


(def Text
  [:and [:string {:min 1}]
   [:fn {:error/message "must not be blank"} (complement str/blank?)]])


(def InputText
  [:maybe [:string {:max 200}]])


(def Row
  [:map [:id Text] [:name Text] [:article Text] [:unit Text]
   [:location Text] [:quantity Quantity]])


(def Rows [:vector Row])


(def Receipt
  [:map [:id :uuid] [:row-id Text] [:before Quantity] [:after Quantity] [:delta Delta]])


(def HistoryEntry
  [:map [:id :uuid] [:row-id Text] [:before Quantity] [:after Quantity] [:delta Delta]
   [:reason Text] [:actor {:optional true} Text] [:recorded-at :string] [:effective-at :string]])


(def Snapshot
  [:map [:rows Rows] [:history [:vector HistoryEntry]]])


(def Command
  [:map [:row-id Text] [:after Quantity] [:reason Text] [:actor {:optional true} Text]])


(def Proposal
  [:map [:row-id Text] [:row Row] [:before Quantity] [:after Quantity]])


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
  [:map [:rows Rows] [:evidence [:map-of Text HistoryEntry]] [:history HistoryData]
   [:editing [:maybe Editing]] [:proposal [:maybe Proposal]] [:interpretation Interpretation]
   [:operations {:optional true} [:vector :map]]])


(def Hiccup
  [:and [:vector {:min 1} :any] [:fn #(keyword? (first %))]])


(def Headers
  [:map-of :string [:or :string [:sequential :string]]])


(def Request
  [:map [:request-method {:optional true} :keyword] [:headers {:optional true} Headers]])


(def Response
  [:map [:status [:int {:min 100 :max 599}]]
   [:headers {:optional true} Headers] [:body {:optional true} :any]])


(def Handler
  [:=> [:cat Request] [:maybe Response]])


(def StockInput
  [:map [:query [:string {:max 200}]]])


(def EditInput
  [:map [:query [:string {:max 200}]] [:rowId Text]])


(def PreviewQuantityInput
  [:map [:query [:string {:max 200}]] [:rowId Text] [:quantity :string]])


(def SmartInput StockInput)


(def ChooseInput EditInput)


(def AcceptInput
  [:map [:query [:string {:max 200}]] [:rowId Text]
   [:quantity :string] [:reason [:string {:max 500}]]])


(def HistoryInput
  [:map [:rowId Text]])


(def DraftCell
  [:map [:rowId Text]
   [:field [:enum "name" "quantity" "unit" "location" "article" "evidence"]]
   [:value :string]])


(def DraftRow
  [:map [:id Text] [:name :string] [:quantity :string] [:unit :string]
   [:location :string] [:article :string] [:evidence :string]])


(def DraftPreviewInput
  [:map
   [:deletedRows {:optional true} [:vector Text]]
   [:baseline {:optional true} [:vector DraftCell]]
   [:localRows {:optional true} [:vector DraftRow]]
   [:addedRows {:optional true} [:vector DraftRow]]
   [:cells [:vector DraftCell]]])

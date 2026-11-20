(ns tover.inventory.acceptance.model
  "Board chapters 17, 14 and 32a · Accepted inventory history: one acceptance, its record revisions and their lines."
  (:require
    [bel.core :refer [defevent defmodel]]
    [bel.validation :as validation]
    [tover.catalogues.records :as records :refer [Id]]))


(def Acceptance
  [:map [:acceptance-id Id] [:actor :uuid] [:recorded-at validation/Instant] [:record-count [:int {:min 1}]]])


(def Record
  "Id is `record-id#revision`; a later revision of the same record supersedes the earlier one."
  [:map
   [:record-revision-id Id]
   [:record-id Id]
   [:revision [:int {:min 1}]]
   [:acceptance-id {:bel/ref ::acceptances} Id]
   [:kind [:enum "opening"]]
   [:occurred-at validation/Instant]
   [:sequence [:int {:min 0}]]
   [:amends {:optional true} Id]
   [:cancelled :boolean]
   [:reason {:optional true} :string]])


(def RecordLine
  "Id is `record-revision-id/position`. Money is an exact decimal string in the workspace currency."
  [:map
   [:record-line-id Id]
   [:record-revision-id {:bel/ref ::records} Id]
   [:position [:int {:min 0}]]
   [:stock-item-id {:bel/ref :tover.inventory.stock-items.model/stock-items} Id]
   [:warehouse-id {:bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   [:quantity-change :int]
   [:unit-cost {:optional true} :string]])


(defevent inventory-changes-accepted {:schema Acceptance})
(defevent inventory-record-accepted {:schema Record})
(defevent inventory-record-line-accepted {:schema RecordLine})


(defmodel acceptances
  {:id     :acceptance-id
   :store  :catalogue
   :schema Acceptance
   :policy ::records/workspace-member
   :events {::inventory-changes-accepted {:create [:actor :recorded-at :record-count]}}})


(defmodel records
  {:id     :record-revision-id
   :store  :catalogue
   :schema Record
   :policy ::records/workspace-member
   :events {::inventory-record-accepted {:create [:record-id :revision :acceptance-id :kind :occurred-at :sequence :amends
                                                  :cancelled :reason]}}})


(defmodel record-lines
  {:id     :record-line-id
   :store  :catalogue
   :schema RecordLine
   :policy ::records/workspace-member
   :events {::inventory-record-line-accepted {:create [:record-revision-id :position :stock-item-id :warehouse-id
                                                       :quantity-change :unit-cost]}}})


(defn record-revision-id
  {:malli/schema [:=> [:cat :string [:int {:min 1}]] :string]}
  [record-id revision]
  (str record-id "#" revision))


(defn record-line-id
  {:malli/schema [:=> [:cat :string [:int {:min 0}]] :string]}
  [record-revision-id position]
  (str record-revision-id "/" position))

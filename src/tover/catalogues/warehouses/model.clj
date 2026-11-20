(ns tover.catalogues.warehouses.model
  "Board chapter 09 · Manage warehouses. The warehouse id is the stable inventory location key; the legacy
  stock page still resolves legacy warehouses until inventory runs on these records."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [tover.catalogues.records :as records :refer [Id Status Text]]))


(def Purpose
  [:enum "storage" "sales" "production"])


(def Warehouse
  [:map
   [:warehouse-id Id] [:name :string] [:description {:optional true} :string] [:purpose {:optional true} Purpose]
   [:status Status] [:revision [:int {:min 1}]]])


(def ^:private fields
  [:name :description :purpose :status :revision])


(def Transition
  [:map [:warehouse-id Id] [:status Status] [:revision [:int {:min 1}]]])


(defevent warehouse-registered {:schema Warehouse})
(defevent warehouse-updated {:schema Warehouse})
(defevent warehouse-archived {:schema Transition})
(defevent warehouse-restored {:schema Transition})


(defmodel warehouses
  {:id     :warehouse-id
   :store  :catalogue
   :schema Warehouse
   :policy ::records/workspace-member
   :events {::warehouse-registered {:create fields}
            ::warehouse-updated    {:set fields}
            ::warehouse-archived   {:set [:status :revision]}
            ::warehouse-restored   {:set [:status :revision]}}})


(def spec
  {:id-key   :warehouse-id
   :fields   [:description :purpose]
   :events   {:registered ::warehouse-registered :updated  ::warehouse-updated
              :archived   ::warehouse-archived   :restored ::warehouse-restored}
   :messages {:invalid-name     "Enter a warehouse name of 1 to 500 characters."
              :name-taken       "Another warehouse already uses this name."
              :not-found        "Choose an existing warehouse in this workspace."
              :stale            "This warehouse changed. Review it again."
              :already-archived "This warehouse is already archived."
              :not-archived     "This warehouse is not archived."}})


(defmethod runtime/coeffect ::all
  [_ context _]
  (records/all-records context ::warehouses :warehouse-id))


(defmethod runtime/coeffect ::usage
  [_ _ _]
  ;; ponytail: faked; no accepted balance or shared pending entry names a Bel warehouse until inventory runs on them.
  ;; Returns `{:stock? bool :pending-entry id}` for the warehouse; nothing blocks archiving yet.
  {:stock? false :pending-entry nil})


(def ^:private all
  [:records ::all (constantly {})])


(defn- by-id
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:warehouse-id]))


(def RegisterWarehouse
  [:map [:warehouse-id Id] [:name [:string {:max 1000}]] [:description {:optional true} Text] [:purpose {:optional true} [:maybe Purpose]]])


(defcommand register-warehouse
  {:input RegisterWarehouse :policy ::records/workspace-member :cofx [all] :emits #{::warehouse-registered}}
  [facts input]
  (records/register spec facts input))


(def UpdateWarehouse
  "An absent key keeps the field; a present nil clears it."
  [:map [:warehouse-id Id] [:revision [:int {:min 1}]] [:name {:optional true} [:string {:max 1000}]]
   [:description {:optional true} Text] [:purpose {:optional true} [:maybe Purpose]]])


(defcommand update-warehouse
  {:input UpdateWarehouse :policy ::records/workspace-member :cofx [[:record ::warehouses by-id] all] :emits #{::warehouse-updated}}
  [facts input]
  (records/update-record spec facts input))


(def Reviewed
  [:map [:warehouse-id Id] [:revision [:int {:min 1}]]])


(defcommand archive-warehouse
  {:input Reviewed :policy ::records/workspace-member
   :cofx  [[:record ::warehouses by-id] [:usage ::usage by-id]]
   :emits #{::warehouse-archived}}
  [{:keys [usage] :as facts} input]
  (when (:stock? usage)
    (records/fail! :warehouse-has-stock "Move or write off this warehouse's stock first." {}))
  (when-let [entry (:pending-entry usage)]
    (records/fail! :pending-reference "A shared pending inventory entry names this warehouse." {:entry entry}))
  (records/archive spec facts input))


(defcommand restore-warehouse
  {:input Reviewed :policy ::records/workspace-member :cofx [[:record ::warehouses by-id]] :emits #{::warehouse-restored}}
  [facts input]
  (records/restore spec facts input))


(defreadmodel warehouse-catalogue
  {:input  [:map [:archived? {:optional true} :boolean] [:id {:optional true} :string]]
   :output [:map [:records [:vector :map]] [:choices [:vector :map]]]
   :policy ::records/workspace-member
   :cofx   [all]}
  [{rows :records} input]
  {:records (records/catalogue spec rows {} input)
   :choices (records/choices rows :warehouse-id)})

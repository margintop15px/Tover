(ns tover.catalogues.suppliers.model
  "Board chapter 10 · Manage suppliers. Purchase history comes from purchase facts once purchases run on Bel."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [tover.catalogues.records :as records :refer [Id Status Text]]))


(def Supplier
  [:map
   [:supplier-id Id] [:name :string] [:address {:optional true} :string] [:contact {:optional true} :string]
   [:status Status] [:revision [:int {:min 1}]]])


(def ^:private fields
  [:name :address :contact :status :revision])


(def Transition
  [:map [:supplier-id Id] [:status Status] [:revision [:int {:min 1}]]])


(defevent supplier-registered {:schema Supplier})
(defevent supplier-updated {:schema Supplier})
(defevent supplier-archived {:schema Transition})
(defevent supplier-restored {:schema Transition})


(defmodel suppliers
  {:id     :supplier-id
   :store  :catalogue
   :schema Supplier
   :policy ::records/workspace-member
   :events {::supplier-registered {:create fields}
            ::supplier-updated    {:set fields}
            ::supplier-archived   {:set [:status :revision]}
            ::supplier-restored   {:set [:status :revision]}}})


(def spec
  {:id-key   :supplier-id
   :fields   [:address :contact]
   :events   {:registered ::supplier-registered :updated  ::supplier-updated
              :archived   ::supplier-archived   :restored ::supplier-restored}
   :messages {:invalid-name     "Enter a supplier name of 1 to 500 characters."
              :name-taken       "Another supplier already uses this name."
              :not-found        "Choose an existing supplier in this workspace."
              :stale            "This supplier changed. Review it again."
              :already-archived "This supplier is already archived."
              :not-archived     "This supplier is not archived."}})


(defmethod runtime/coeffect ::all
  [_ context _]
  (records/all-records context ::suppliers :supplier-id))


(def ^:private all
  [:records ::all (constantly {})])


(defn- by-id
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:supplier-id]))


(def RegisterSupplier
  [:map [:supplier-id Id] [:name [:string {:max 1000}]]
   [:address {:optional true} Text]
   [:contact {:optional true} Text]])


(defcommand register-supplier
  {:input RegisterSupplier :policy ::records/workspace-member :cofx [all] :emits #{::supplier-registered}}
  [facts input]
  (records/register spec facts input))


(def UpdateSupplier
  "An absent key keeps the field; a present nil clears it."
  [:map [:supplier-id Id] [:revision [:int {:min 1}]] [:name {:optional true} [:string {:max 1000}]]
   [:address {:optional true} Text]
   [:contact {:optional true} Text]])


(defcommand update-supplier
  {:input UpdateSupplier :policy ::records/workspace-member :cofx [[:record ::suppliers by-id] all] :emits #{::supplier-updated}}
  [facts input]
  (records/update-record spec facts input))


(def Reviewed
  [:map [:supplier-id Id] [:revision [:int {:min 1}]]])


(defcommand archive-supplier
  {:input Reviewed :policy ::records/workspace-member :cofx [[:record ::suppliers by-id]] :emits #{::supplier-archived}}
  [facts input]
  (records/archive spec facts input))


(defcommand restore-supplier
  {:input Reviewed :policy ::records/workspace-member :cofx [[:record ::suppliers by-id]] :emits #{::supplier-restored}}
  [facts input]
  (records/restore spec facts input))


(defreadmodel supplier-catalogue
  {:input  [:map [:archived? {:optional true} :boolean] [:id {:optional true} :string]]
   :output [:map [:records [:vector :map]] [:choices [:vector :map]]]
   :policy ::records/workspace-member
   :cofx   [all]}
  [{rows :records} input]
  {:records (records/catalogue spec rows {} input)
   :choices (records/choices rows :supplier-id)})

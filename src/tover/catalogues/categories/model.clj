(ns tover.catalogues.categories.model
  "Board chapter 11 · Manage categories: a flat list; a product holds at most one."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [tover.catalogues.records :as records :refer [Id Status]]))


(def Category
  [:map
   [:category-id Id] [:name :string]
   [:status Status] [:revision [:int {:min 1}]]])


(def ^:private fields
  [:name :status :revision])


(def Transition
  [:map [:category-id Id] [:status Status] [:revision [:int {:min 1}]]])


(defevent category-registered {:schema Category})
(defevent category-updated {:schema Category})
(defevent category-archived {:schema Transition})
(defevent category-restored {:schema Transition})


(defmodel categories
  {:id     :category-id
   :store  :catalogue
   :schema Category
   :policy ::records/workspace-member
   :events {::category-registered {:create fields}
            ::category-updated    {:set fields}
            ::category-archived   {:set [:status :revision]}
            ::category-restored   {:set [:status :revision]}}})


(def spec
  {:id-key   :category-id
   :fields   []
   :events   {:registered ::category-registered :updated  ::category-updated
              :archived   ::category-archived   :restored ::category-restored}
   :messages {:invalid-name     "Enter a category name of 1 to 500 characters."
              :name-taken       "Another category already uses this name."
              :not-found        "Choose an existing category in this workspace."
              :stale            "This category changed. Review it again."
              :already-archived "This category is already archived."
              :not-archived     "This category is not archived."}})


(defmethod runtime/coeffect ::all
  [_ context _]
  (records/all-records context ::categories :category-id))


(def ^:private all
  [:records ::all (constantly {})])


(defn- by-id
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:category-id]))


(def RegisterCategory
  [:map [:category-id Id] [:name [:string {:max 1000}]]])


(defcommand register-category
  {:input RegisterCategory :policy ::records/workspace-member :cofx [all] :emits #{::category-registered}}
  [facts input]
  (records/register spec facts input))


(def UpdateCategory
  "An absent key keeps the field; a present nil clears it."
  [:map [:category-id Id] [:revision [:int {:min 1}]] [:name {:optional true} [:string {:max 1000}]]])


(defcommand update-category
  {:input UpdateCategory :policy ::records/workspace-member :cofx [[:record ::categories by-id] all] :emits #{::category-updated}}
  [facts input]
  (records/update-record spec facts input))


(def Reviewed
  [:map [:category-id Id] [:revision [:int {:min 1}]]])


(defcommand archive-category
  {:input Reviewed :policy ::records/workspace-member :cofx [[:record ::categories by-id]] :emits #{::category-archived}}
  [facts input]
  (records/archive spec facts input))


(defcommand restore-category
  {:input Reviewed :policy ::records/workspace-member :cofx [[:record ::categories by-id]] :emits #{::category-restored}}
  [facts input]
  (records/restore spec facts input))


(defreadmodel category-catalogue
  {:input  [:map [:archived? {:optional true} :boolean] [:id {:optional true} :string]]
   :output [:map [:records [:vector :map]] [:choices [:vector :map]]]
   :policy ::records/workspace-member
   :cofx   [all]}
  [{rows :records} input]
  {:records (records/catalogue spec rows {} input)
   :choices (records/choices rows :category-id)})

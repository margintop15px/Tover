(ns tover.catalogues.stores.model
  "Board chapter 12 · Manage stores: sales channels with an optional default warehouse."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [tover.catalogues.records :as records :refer [Id Status]]
    [tover.catalogues.warehouses.model :as warehouses]))


(def Store
  [:map
   [:store-id Id] [:name :string] [:default-warehouse-id {:optional true} :string]
   [:status Status] [:revision [:int {:min 1}]]])


(def ^:private fields
  [:name :default-warehouse-id :status :revision])


(def Transition
  [:map [:store-id Id] [:status Status] [:revision [:int {:min 1}]]])


(defevent store-registered {:schema Store})
(defevent store-updated {:schema Store})
(defevent store-archived {:schema Transition})
(defevent store-restored {:schema Transition})


(defmodel stores
  {:id     :store-id
   :store  :catalogue
   :schema Store
   :policy ::records/workspace-member
   :events {::store-registered {:create fields}
            ::store-updated    {:set fields}
            ::store-archived   {:set [:status :revision]}
            ::store-restored   {:set [:status :revision]}}})


(def spec
  {:id-key     :store-id
   :fields     [:default-warehouse-id]
   :references {:default-warehouse-id [:warehouses :warehouse-unavailable]}
   :events     {:registered ::store-registered :updated  ::store-updated
                :archived   ::store-archived   :restored ::store-restored}
   :messages   {:invalid-name     "Enter a store name of 1 to 500 characters."
                :name-taken       "Another store already uses this name."
                :not-found        "Choose an existing store in this workspace."
                :stale            "This store changed. Review it again."
                :already-archived "This store is already archived."
                :not-archived     "This store is not archived."}})


(defmethod runtime/coeffect ::all
  [_ context _]
  (records/all-records context ::stores :store-id))


(def ^:private all
  [:records ::all (constantly {})])


(defmethod runtime/coeffect ::references
  [_ context _]
  {:warehouses (records/choices (records/all-records context ::warehouses/warehouses :warehouse-id) :warehouse-id)})


(def ^:private references
  [:references ::references (constantly {})])


(defn- by-id
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:store-id]))


(def RegisterStore
  [:map [:store-id Id] [:name [:string {:max 1000}]]
   [:default-warehouse-id {:optional true} [:maybe Id]]])


(defcommand register-store
  {:input RegisterStore :policy ::records/workspace-member :cofx [all references] :emits #{::store-registered}}
  [facts input]
  (records/register spec facts input))


(def UpdateStore
  "An absent key keeps the field; a present nil clears it."
  [:map [:store-id Id] [:revision [:int {:min 1}]] [:name {:optional true} [:string {:max 1000}]]
   [:default-warehouse-id {:optional true} [:maybe Id]]])


(defcommand update-store
  {:input UpdateStore :policy ::records/workspace-member :cofx [[:record ::stores by-id] all references] :emits #{::store-updated}}
  [facts input]
  (records/update-record spec facts input))


(def Reviewed
  [:map [:store-id Id] [:revision [:int {:min 1}]]])


(defmethod runtime/coeffect ::connections
  [_ context {:keys [store-id]}]
  ;; Chapter 40's model names stores, so it is reached through its coeffect and not required here.
  (filterv #(= store-id (:store-id %)) (runtime/coeffect :tover.marketplace.connections.model/all context {})))


;; The store's active marketplace connections are paused in the same commit; restoring resumes none of them.
(defcommand archive-store
  {:input Reviewed :policy ::records/workspace-member
   :cofx  [[:record ::stores by-id] [:connections ::connections by-id]]
   :emits #{::store-archived :tover.marketplace.connections.model/marketplace-connection-paused}}
  [{:keys [actor now connections] :as facts} input]
  (update (records/archive spec facts input) :events into
          (for [{:keys [connection-id sync-state]} connections
                :when (= "active" sync-state)]
            {:type :tover.marketplace.connections.model/marketplace-connection-paused
             :data {:connection-id connection-id :sync-state "paused"  :reason "store archived"
                    :paused-by     (str actor)   :paused-at  (str now)}})))


(defcommand restore-store
  {:input Reviewed :policy ::records/workspace-member :cofx [[:record ::stores by-id]] :emits #{::store-restored}}
  [facts input]
  (records/restore spec facts input))


(defreadmodel store-catalogue
  {:input  [:map [:archived? {:optional true} :boolean] [:id {:optional true} :string]]
   :output [:map [:records [:vector :map]] [:choices [:vector :map]]]
   :policy ::records/workspace-member
   :cofx   [all references]}
  [{rows :records :keys [references]} input]
  {:records (records/catalogue spec rows references input)
   :choices (records/choices rows :store-id)})

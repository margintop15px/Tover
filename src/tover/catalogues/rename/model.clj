(ns tover.catalogues.rename.model
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [tover.inventory.schema :as rules]))


(def GoodQuery
  [:map [:id [:string {:min 1 :max 500}]]])


(def RenameGood
  [:map
   [:id [:string {:min 1 :max 500}]]
   [:revision [:int {:min 1}]]
   [:name [:string {:min 1 :max 500}]]])


(def Good
  [:map
   [:id [:string {:min 1 :max 500}]]
   [:name :string]
   [:revision [:int {:min 1}]]
   [:article :string]
   [:barcode :string]
   [:unit :string]
   [:category-id {:optional true :bel/ref ::categories} :string]
   [:store-id {:optional true :bel/ref ::stores} :string]
   [:actor :string]
   [:updated-at :string]])


(defmethod runtime/policy ::workspace-member
  [_ {:keys [scope actor user-id workspace-id memberships]} _]
  (boolean (and (uuid? user-id) (= actor user-id)
                (= workspace-id (:workspace-id scope))
                (some #(= workspace-id (:workspace-id %)) memberships))))


(defevent good-renamed
  {:schema [:map [:id :string] [:name :string] [:revision [:int {:min 1}]]
            [:actor :string] [:updated-at :string]]})


(defmodel goods
  {:id     :id
   :store  :catalogue
   :schema Good
   :policy ::workspace-member
   :events {::good-renamed {:set [:name :revision :actor :updated-at]}}})


(defmodel categories
  {:id     :id
   :store  :catalogue
   :schema [:map [:id :string] [:name :string]]
   :policy ::workspace-member})


(defmodel stores
  {:id     :id
   :store  :catalogue
   :schema [:map [:id :string] [:name :string]]
   :policy ::workspace-member})


(defn by-id
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:id]))


(defcommand rename-good
  {:input RenameGood              :policy ::workspace-member
   :cofx  [[:good ::goods by-id]]
   :emits #{::good-renamed}}
  [{:keys [good actor now scope]} {:keys [id revision name]}]
  (when-not good
    (rules/fail! :not-found "Choose an existing good in this workspace." {}))
  (when-not (= revision (:revision good))
    (rules/fail! :stale-catalogue "This record changed. Choose which value to keep." {:current good}))
  (rules/text! name :name)
  {:events [{:type ::good-renamed
             :data {:id         id
                    :name       name
                    :revision   (inc revision)
                    ;; Preserve this isolated slice's existing RDF text contract until cutover.
                    :actor      (str actor)
                    :updated-at (str now)}}]
   :fx     [[:bel/publish {:topic    :tover.stock.web/changed
                           :scope-id (:workspace-id scope)
                           :data     {}}]]})


(defreadmodel good-row
  {:input  GoodQuery
   :output [:map [:id :string] [:name :string] [:revision :int] [:labels :map]]
   :policy ::workspace-member
   :cofx   [[:good ::goods by-id]
            [:category ::categories (fn [{:keys [good]} _] {:id (:category-id good)})]
            [:store ::stores (fn [{:keys [good]} _] {:id (:store-id good)})]]}
  [{:keys [good category store]} _]
  (when-not good (rules/fail! :not-found "Choose an existing good in this workspace." {}))
  (assoc good :labels {:category-id (or (:name category) "") :store-id (or (:name store) "")}))

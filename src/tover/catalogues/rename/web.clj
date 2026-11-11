(ns tover.catalogues.rename.web
  "Goods rename adoption boundary. Excluded from live discovery until the catalogue cutover."
  (:require
    [bel.core :refer [defaction defview]]
    [tover.catalogues.rename.model :as model]
    [tover.catalogues.schema :as schema]
    [tover.catalogues.ui :as ui]
    [tover.shared.i18n :as i18n]))


(defview row
  {:schema     (conj model/GoodQuery [:commandId [:string {:min 1 :max 100}]])
   :read-model ::model/good-row :signal :_catalogueSaveJson :route-data {:tover/access :workspace}}
  [{:keys [model input]}]
  {:commandId (:commandId input) :kind    "goods" :status "ok"
   :row       model              :gridRow (first (:rows (ui/grid :goods {:rows [model]})))})


(defview failed
  {:schema [:map [:commandId :string] [:error :map]] :signal :_catalogueSaveJson :route-data {:tover/access :workspace}}
  [{:keys [commandId error]}]
  {:commandId commandId                  :kind "goods" :status "failed" :current (:current error)
   :message   (i18n/error-message error)})


(defaction save
  {:schema     [:and schema/Save
                [:fn {:error/message "This action renames an existing good."}
                 #(and (= "goods" (:kind %)) (= "name" (:path %)) (not (:referenceName %)))]]
   :route-data {:tover/access :workspace}}
  [_ {:keys [id revision value commandId]}]
  {:type  :command :id ::model/rename-good :command-id commandId
   :input {:id id :revision revision :name value}
   :then  {:type :view :id ::row :input {:id id :commandId commandId}}
   :catch {:type :view :id ::failed :input {:commandId commandId}}})

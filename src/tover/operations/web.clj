(ns tover.operations.web
  (:require
    [bel.core :refer [defpage defstream]]
    [tover.inventory.workbench :as workbench]))


(defstream changed
  [_context _event]
  {:type :signals :data {:inventoryChanged (str (random-uuid))}})


(defpage page
  {:route      "/operations" :title "Operations · Tover" :parent :tover.shared.web.core/shell
   :route-data {:tover/access :workspace}
   :query      (fn [context input] (workbench/query-stock context (assoc input :view :operations)))}
  [props]
  {:type :elements :html (workbench/stock-page props)})

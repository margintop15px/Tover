(ns tover.operations.web
  (:require
    [bel.core :refer [defpage]]
    [tover.inventory.workbench :as workbench]))


(defpage page
  {:route      "/operations"
   :title      "Operations · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :workspace}
   :query      (fn [context _input] (workbench/query-stock context {:view :operations}))}
  [props]
  {:type :elements
   :html (workbench/stock-page props)})

(ns tover.inventory.workbench
  (:require
    [tover.operations.core :as operations]
    [tover.shared.schema :as shared]
    [tover.stock.schema :as schema]
    [tover.stock.ui :as ui]
    [tover.stock.view-model :as view-model]))


(defn query-stock
  {:malli/schema
   [:=> [:cat view-model/Context :map]
    [:map
     [:model schema/UIModel]
     [:view [:enum :stock :operations]]]]}
  [context {:keys [view] :or {view :stock}}]
  (let [model (view-model/query-stock context {:query ""})]
    (assoc (view-model/stock-props model (:operation-examples context)) :model model :view view)))


(defn stock-page
  "Render a stock read model with plain values and event properties."
  {:malli/schema [:=> [:cat [:map
                             [:model schema/UIModel]]]
                  shared/Hiccup]}
  [{:keys [model signals init effect on-history-close operations view] :as props}]
  (let [{:keys [interpretation history proposal]} model]
    [:div#inventory-evidence-workbench
     {:aria-label "Tover inventory workbench" :data-inventory "" :data-signals__ifmissing signals :data-init init :data-effect effect}
     [:div.main-area
      [:section#stock-view {:hidden (= view :operations) :aria-label "Stock"}
       (ui/smart-entry (assoc (:smart props) :interpretation interpretation))
       (ui/stock-sheet (:sheet props))
       (ui/stock-review {:proposal proposal :review (:review props)})
       (ui/history-panel (assoc history :on-click on-history-close))
       (ui/footer props)]
      [:section#operations-view {:hidden (not= view :operations) :aria-label "Operations"}
       (operations/operations-panel operations)]]]))

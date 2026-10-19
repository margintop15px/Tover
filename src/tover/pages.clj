(ns tover.pages
  (:require
    [bel.core :refer [defpage]]
    [bel.expressions :refer [->expr]]
    [tover.schema :as schema]
    [tover.ui :as ui]
    [tover.views :as views]))


(defn query-stock
  {:malli/schema
   [:=> [:cat views/Context :map]
    [:map
     [:model schema/UIModel]
     [:stock-props :map]]]}
  [context _input]
  (let [model (views/query-stock context {:query ""})]
    {:model       model
     :stock-props (views/stock-props model (:operation-examples context))}))


(defpage stock
  {:query query-stock}
  [{:keys [model stock-props]}]
  {:type :elements
   :html [:html {:lang "en"}
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:title "Inventory · Tover v2"]
           [:script {:src "/theme.js?v=1"}]
           [:link {:rel "stylesheet" :href "/stock.css?v=theme-1"}]
           [:link {:rel "stylesheet" :href "/stock-entry.css?v=add-rows-2"}]
           [:link {:rel "stylesheet" :href "/stock-operations.css?v=lineage-12"}]
           [:link {:rel "stylesheet" :href "/inline-history.css?v=lineage-12"}]
           [:link {:rel "stylesheet" :href "/operation-lineage.css?v=lineage-12"}]
           [:script {:type "module" :src "/stock-operations.js?v=lineage-12"}]
           [:script {:type "module" :src "/stock-entry.js?v=add-rows-2"}]
           [:script {:type "module" :src "/stock-grids.js?v=lineage-12"}]]
          [:body
           {:data-init (->expr (@get "/_bel/realtime/stream"
                                     {:payload        {:tabId  ~(str (random-uuid))
                                                       :topics ["tover.streams/stock"]}
                                      :openWhenHidden true}))}
           (ui/stock-page (assoc stock-props :model model))]]})

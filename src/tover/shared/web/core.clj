(ns tover.shared.web.core
  (:require
    [bel.core :refer [defpage]]
    [bel.expressions :refer [->expr]]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui]))


(defn document
  "The document and its workspace connection live for a complete browser visit."
  {:malli/schema [:=> [:cat :map [:* :any]] schema/Hiccup]}
  [{:keys [request title]} & children]
  [:html {:lang i18n/*locale*}
   [:head
    [:meta {:charset "utf-8"}]
    (i18n/catalog-meta)
    [:script {:src "/language.js"}]
    [:script {:type "module" :src "/i18n.js"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "csrf-token" :content (force (:anti-forgery-token request))}]
    [:meta {:name "tover-context" :content (str (get-in request [:tover/identity :auth-version]))}]
    [:title title]
    [:script {:src "/theme.js?v=2"}]
    [:script {:src "/identity.js?v=2" :defer true}]
    [:link {:rel "stylesheet" :href "/app.css"}]
    [:link {:rel "stylesheet" :href "/stock.css?v=workflow-2"}]
    [:link {:rel "stylesheet" :href "/inventory.css?v=workflow-2"}]
    [:link {:rel "stylesheet" :href "/stock-entry.css?v=workflow-2"}]
    [:link {:rel "stylesheet" :href "/stock-operations.css?v=workflow-2"}]
    [:link {:rel "stylesheet" :href "/vendor/jspreadsheet-ce/jspreadsheet.css" :data-inventory-asset "jspreadsheet.css"}]
    [:link {:rel "stylesheet" :href "/vendor/jspreadsheet-ce/jsuites.css" :data-inventory-asset "jsuites.css"}]
    [:link {:rel "stylesheet" :href "/inventory-grid.css?v=ce-1"}]
    [:link {:rel "stylesheet" :href "/inventory-timeline.css?v=ce-1"}]
    [:script {:type "module" :src "/realtime.js"}]
    [:script {:type "module" :src "/navigation.js"}]
    [:script {:type "module" :src "/dates.js"}]
    [:script {:type "module" :src "/inventory-grid.js"}]
    [:script {:type "module" :src "/stock-grids.js?v=workflow-2"}]
    [:script {:type "module" :src "/stock-entry.js?v=workflow-2"}]
    [:script {:type "module" :src "/stock-operations.js?v=workflow-2"}]]
   (into [:body
          (when (get-in request [:tover/identity :workspace-id])
            {:data-init (->expr (@realtime {:tabId  ~(str (random-uuid))
                                            :topics ["tover.stock.web/changed"
                                                     "tover.stock.web/draft-changed"
                                                     "tover.operations.web/changed"]}))})]
         children)])


(defn query-shell
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request]} _input]
  {:request  request
   :identity (:tover/identity request)
   :path     (:uri request)
   :error    (:tover/error request)})


(defpage shell
  {:document document
   :query    query-shell}
  [props & children]
  {:type :elements
   :html (apply ui/app-shell props children)})


(defn query-request
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request]} _input]
  request)

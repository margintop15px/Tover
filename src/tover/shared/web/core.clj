(ns tover.shared.web.core
  (:require
    [bel.core :refer [defpage]]
    [bel.expressions :refer [->expr]]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui]))


(defn document
  "The document and its workspace connection live for a complete browser visit."
  {:malli/schema [:=> [:cat :map [:* :any]] schema/Hiccup]}
  [{:keys [request title]} & children]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "csrf-token" :content (force (:anti-forgery-token request))}]
    [:meta {:name "tover-context" :content (str (get-in request [:tover/identity :auth-version]))}]
    [:title title]
    [:script {:src "/theme.js?v=2"}]
    [:script {:src "/identity.js?v=2" :defer true}]
    [:link {:rel "stylesheet" :href "/app.css"}]
    [:link {:rel "stylesheet" :href "/stock.css?v=theme-2"}]
    [:link {:rel "stylesheet" :href "/stock-entry.css?v=add-rows-2"}]
    [:link {:rel "stylesheet" :href "/stock-operations.css?v=lineage-12"}]
    [:link {:rel "stylesheet" :href "/inline-history.css?v=lineage-12"}]
    [:link {:rel "stylesheet" :href "/operation-lineage.css?v=lineage-12"}]
    [:script {:type "module" :src "/navigation.js"}]
    [:script {:type "module" :src "/stock-operations.js?v=lineage-12"}]
    [:script {:type "module" :src "/stock-entry.js?v=add-rows-2"}]
    [:script {:type "module" :src "/stock-grids.js?v=lineage-12"}]]
   (into [:body
          (when (get-in request [:tover/identity :workspace-id])
            {:data-init (->expr (@get "/_bel/realtime/stream"
                                      {:payload        {:tabId  ~(str (random-uuid))
                                                        :topics ["tover.stock.web/changed"]}
                                       :openWhenHidden true}))})]
         children)])


(defn query-shell
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request]} _input]
  {:identity (:tover/identity request)
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

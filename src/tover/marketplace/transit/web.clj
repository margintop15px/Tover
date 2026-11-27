(ns tover.marketplace.transit.web
  "Board chapter 46 screens: the Transit view of the Operations page, one per connection, and Shipment Details.
  Transit Overview is one read-only table of `Shipments in Transit`, filtered by state and connection, with the
  counted totals by state. Shipment Details is the read-only timeline of one shipment (`Shipment Status History`):
  its lines, every status with its actual time apart from Tover's observation time, and links to the accepted Sale
  and Customer return records it produced. Transit is never part of a warehouse balance.

  Everything is read for the selected workspace and the connection in the path; no buyer data is read or shown."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.nav :as nav]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.identity.access :as access]
    [tover.inventory.operations.web :as operations]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.cutover.web :as cutover-web]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.moves.web :as moves-web]
    [tover.marketplace.products.web :as products-web]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.transit.model :as transit]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]))


(def ^:private access
  {:tover/access :workspace})


(def State
  [:enum "all" "travelling" "delivered" "returning" "returned" "unknown" "awaiting facts"])


(defn transit-path
  "The Transit view of one connection; with a state other than `all`, filtered to it."
  {:malli/schema [:=> [:cat :string [:* :any]] :string]}
  [connection-id & [state]]
  (str (operations/marketplace-path connection-id) "/transit"
       (when (and state (not= "all" state)) (str "?state=" (codec/url-encode state)))))


(defn shipment-path
  {:malli/schema [:=> [:cat :string :string] :string]}
  [connection-id source-operation-id]
  (str (operations/marketplace-path connection-id) "/transit/" (codec/url-encode source-operation-id)))


(def ^:private state-labels
  {"all"            "All"            "travelling" "Travelling" "delivered" "Delivered" "returning" "Returning" "returned" "Returned"
   "unknown"        "Unknown"
   "awaiting facts" "Awaiting facts"})




(def ^:private columns
  [{:key :shipment :label "Shipment" :type :text :width 170}
   {:key :item :label "Stock item" :type :text :width 200}
   {:key :quantity :label "Quantity" :type :text :width 90}
   {:key :state :label "State" :type :text :width 130}
   {:key :counted :label "In totals" :type :text :width 90}
   {:key :actual-time :label "Actual time" :type :text :width 190}
   {:key :observed-at :label "Observed by Tover" :type :text :width 190}
   {:key :next-step :label "Next step" :type :text :width 190}])


(defn rows
  "One row per shipment line of `shipments`, opening the shipment's details."
  {:malli/schema [:=> [:cat ifn? [:sequential transit/Shipment]] [:vector grid/Row]]}
  [label shipments]
  (vec (for [{:keys [source-operation-id connection-id line-key stock-item-id quantity state counted actual-time observed-at missing next-step]}
             shipments]
         (cond-> {:id    (str source-operation-id "|" line-key) :revision 1 :label (label stock-item-id stock-item-id) :selectable? false
                  :href  (shipment-path connection-id source-operation-id)
                  :cells {:shipment  (provenance/shipment-label source-operation-id) :item        (label stock-item-id stock-item-id)
                          :quantity  (some-> quantity str) :state (i18n/t (state-labels state state))
                          :counted   (i18n/t (if counted "Yes" "No"))                :actual-time actual-time :observed-at observed-at
                          :next-step (operations/step-label next-step)}}
           (seq missing) (assoc :findings {:state (i18n/t "Missing: {facts}" {:facts (str/join ", " (map i18n/t missing))})})))))


(defn- labels
  {:malli/schema [:=> [:cat :map] ifn?]}
  [context]
  (cutover-web/names (runtime/read-model context :tover.inventory.references/draft-catalogue {})))


(defn table
  "The Transit table props of one connection in `state` (`all` for every state), or nil when they cannot be read."
  {:malli/schema [:=> [:cat :map :string :string] [:maybe :map]]}
  [context connection-id state]
  (products-web/read-or-nil
    #(let [{:keys [shipments totals]} (runtime/read-model context ::transit/shipments-in-transit {:connection-id connection-id})]
       ;; One id per state: a filter link morphs the page, and the browser keeps a grid whose id is unchanged.
       {:id   (str "marketplace-transit-" (str/replace state " " "-")) :label "Transit" :columns columns :totals totals
        :rows (rows (labels context) (if (= "all" state) shipments (filterv (comp #{state} :state) shipments)))})))


(def Query
  [:map [:connectionId connections/ConnectionId] [:state {:optional true} State] [:requestId [:string {:max 100}]]])


(defview table-view
  {:route-data access :schema Query :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [connectionId state requestId]} :input}]
  (products-web/connection! context connectionId)
  (grid/table-signal requestId (some-> (table context connectionId (or state "all")) grid/model)))


(defaction command
  ;; The table is read-only: it has no selection actions, so nothing is ever sent here.
  {:route-data access :schema [:map [:commandId [:string {:min 1 :max 100}]]]}
  [_ {:keys [commandId]}]
  (grid/rejected commandId (i18n/t "Shipments in transit are read-only.")))


(defn query-transit
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [connection-id state]}]
  (let [state (or state "all")]
    {:connection  (products-web/connection! context connection-id)
     :connections (operations/query-connections context)
     :state       state
     :table       (table context connection-id state)}))


(defn connection-filter
  "Board 46 Transit Overview · the connection filter: each connection's Transit view in the same state."
  {:malli/schema [:=> [:cat [:map [:connections [:sequential :map]] [:connection-id :string] [:state :string]]] schema/Hiccup]}
  [{:keys [connections connection-id state]}]
  [:nav.catalogue-kinds.transit-connections {:aria-label (i18n/t "Connection")}
   (for [{other :connection-id :keys [account-label]} connections]
     [:a (nav/link (transit-path other state) {:aria-current (when (= other connection-id) "page")}) (str "Ozon · " account-label)])])


(defn totals-line
  {:malli/schema [:=> [:cat [:map-of :string :int]] :string]}
  [totals]
  (if (seq totals)
    (str/join " · " (for [s transit/states :let [n (get totals s)] :when n] (str (i18n/t (state-labels s)) " " n)))
    (i18n/t "No counted units.")))


(defpage transit-view
  {:route      "/operations/marketplace/:connection-id/transit"
   :title      "Transit · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id connections/ConnectionId] [:state {:optional true} State]]
   :route-data access
   :query      query-transit}
  [{:keys [connection connections state table]}]
  (let [{:keys [connection-id account-label]} connection]
    {:type :elements
     :html [:section.marketplace-page {:aria-labelledby "transit-title" :data-connection-id connection-id}
            [:div.management-page
             (operations/operations-views {:current [:transit connection-id] :connections connections})
             (products-web/header {:id        "transit-title" :title      (i18n/t "Transit")            :subtitle (str "Ozon · " account-label)
                                   :back-href "/operations"   :back-label (i18n/t "Back to operations")})
             (connection-filter {:connections connections :connection-id connection-id :state state})
             [:p.management-secondary
              (when table (str (totals-line (:totals table)) " · "))
              (i18n/t "Units in transit left the warehouse with their accepted Sale; transit is never part of a warehouse balance.")]]
            (if table
              (grid/workbench (-> table (dissoc :totals)
                                  (assoc :query {:connectionId connection-id :state state} :query-view ::table-view :command-action ::command
                                         :filters (mapv (fn [s] {:label (state-labels s) :href (transit-path connection-id s) :current? (= s state)})
                                                        (rest State)))))
              [:p.management-empty {:role "alert"} (i18n/t "Shipments in transit could not be loaded.") " "
               [:a {:href (transit-path connection-id state)} (i18n/t "Retry")]])]}))


;; Shipment Details


(defn query-shipment
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [connection-id operation-id]}]
  (let [connection (products-web/connection! context connection-id)
        history    (runtime/read-model context ::transit/shipment-status-history {:source-operation-id operation-id})]
    (when-not (= connection-id (:connection-id history))
      (access/failure 404 :unknown-shipment "Unknown shipment"))
    {:connection connection :connections (operations/query-connections context) :history history :label (labels context)}))


(defn- table-section
  {:malli/schema [:=> [:cat :string :string [:sequential :string] [:sequential [:sequential :any]]] schema/Hiccup]}
  [id title headings rows]
  [:section.management-section {:aria-labelledby id}
   [:div.management-section-heading [:h2 {:id id} title]]
   [:div.management-table-wrap
    [:table.management-table
     [:thead (into [:tr] (for [h headings] [:th {:scope "col"} h]))]
     (into [:tbody] (for [cells rows] (into [:tr] (for [c cells] [:td c]))))]]])


(defpage shipment-view
  {:route      "/operations/marketplace/:connection-id/transit/:operation-id"
   :title      "Shipment details · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id connections/ConnectionId] [:operation-id [:string {:min 1 :max 300}]]]
   :route-data access
   :query      query-shipment}
  [{:keys [connection connections history label]}]
  (let [{:keys [connection-id account-label]}                connection
        {:keys [source-operation-id lines statuses records]} history]
    {:type :elements
     :html [:section.management-page {:aria-labelledby "shipment-title" :data-source-operation-id source-operation-id}
            (operations/operations-views {:current [:transit connection-id] :connections connections})
            (products-web/header {:id        "shipment-title"              :title      (provenance/shipment-label source-operation-id)
                                  :subtitle  (str "Ozon · " account-label)
                                  :back-href (transit-path connection-id)  :back-label (i18n/t "All shipments in transit")})
            [:div.management-content
             (table-section "shipment-lines" (i18n/t "Lines")
                            [(i18n/t "Stock item") (i18n/t "Quantity") (i18n/t "State") (i18n/t "In totals")]
                            (for [{:keys [stock-item-id quantity state counted]} lines]
                              [(label stock-item-id stock-item-id) (some-> quantity str) (i18n/t (state-labels state state)) (i18n/t (if counted "Yes" "No"))]))
             (table-section "shipment-timeline" (i18n/t "Status history")
                            [(i18n/t "Revision") (i18n/t "Status") (i18n/t "Actual time") (i18n/t "Observed by Tover")]
                            (for [{:keys [revision status actual-time observed-at]} statuses]
                              [revision (or status "—") (or actual-time "—") observed-at]))
             [:section.management-section {:aria-labelledby "shipment-records"}
              [:div.management-section-heading [:h2#shipment-records (i18n/t "Accepted records")]]
              (if (seq records)
                (into [:ul]
                      (for [{:keys [record-id cancelled] record-kind :kind} records]
                        [:li [:a {:href (moves-web/entry-path record-id)} (i18n/t (if (= "sale" record-kind) "Sale" "Customer return"))]
                         (when cancelled (str " " (i18n/t "(cancelled)")))]))
                [:p.management-empty (i18n/t "No accepted record yet.")])]]]}))

(ns tover.marketplace.moves.web
  "Board chapter 45 slices b and c screen: the Stock differences view of the Operations page, one per connection. One
  read-only table compares the newest finished Ozon snapshot with Tover, per compared stock item and FBO warehouse,
  with the item's state, its last agreeing snapshot and the pending Transfers a detected move produced. The Transfers
  are ordinary pending entries: members edit, discard or accept them on the Operations table, never here.

  Everything is read for the selected workspace and the connection in the path; no buyer data is read or shown."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.inventory.operations.web :as operations]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.cutover.web :as cutover-web]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.moves.model :as moves]
    [tover.marketplace.products.web :as products-web]
    [tover.shared.i18n :as i18n]))


(def ^:private access
  {:tover/access :workspace})


(defn differences-path
  {:malli/schema [:=> [:cat :string] :string]}
  [connection-id]
  (str (operations/marketplace-path connection-id) "/differences"))


(def ^:private state-labels
  {"equal" "Equal" "moved" "Moved" "unexplained" "Unexplained" "waiting for operations" "Waiting for operations"})


(def ^:private status-labels
  {"pending" "Pending" "accepted" "Accepted" "discarded" "Discarded"})


(def ^:private columns
  [{:key :item :label "Stock item" :type :text :width 200}
   {:key :warehouse :label "Warehouse" :type :text :width 150}
   {:key :ozon :label "Ozon quantity" :type :text :width 110}
   {:key :tover :label "Tover balance" :type :text :width 110}
   {:key :difference :label "Difference" :type :text :width 100}
   {:key :state :label "State" :type :text :width 170}
   {:key :last-agreeing :label "Last agreeing snapshot" :type :text :width 190}
   {:key :transfers :label "Proposed transfers" :type :lines :width 300}])


(defn- signed
  {:malli/schema [:=> [:cat :int] :string]}
  [n]
  (cond (pos? n) (str "+" n) (neg? n) (str "−" (abs n)) :else "0"))


(defn- transfer-line
  {:malli/schema [:=> [:cat ifn? moves/ProposedTransfer] :string]}
  [label {:keys [from to quantity status edited]}]
  (i18n/t "{quantity} from {from} to {to} · {status}"
          {:quantity quantity :from (label from from) :to (label to to)
           :status   (str (i18n/t (status-labels status status)) (when (and edited (= "pending" status)) (str ", " (i18n/t "edited"))))}))


(defn entry-path
  "The Operations page, opened at one pending entry."
  {:malli/schema [:=> [:cat :string] :string]}
  [entry-id]
  (str "/operations#entry=" (codec/url-encode entry-id)))


(def ^:private correction-path
  "Where a member records a correction (a count): the Stock page."
  "/")


(defn- item-transfers
  "The item's Transfers to show: every one of its current key's, then those still pending under older keys (a
  member's edit is kept when the key changes)."
  {:malli/schema [:=> [:cat [:sequential moves/Proposal] :string [:maybe :string]] [:vector moves/ProposedTransfer]]}
  [proposals stock-item-id current-key]
  (let [own (filter #(= stock-item-id (:stock-item-id %)) proposals)]
    (into (vec (mapcat :transfers (filter #(= current-key (:last-agreeing-run %)) own)))
          (comp (remove #(= current-key (:last-agreeing-run %))) (mapcat :transfers) (filter #(= "pending" (:status %))))
          own)))


(defn rows
  "One row per compared item and warehouse of `read` (`Stock Differences`); the item's state, last agreeing
  snapshot and Transfers (from `proposals`) are on its first row. A row links to each pending Transfer from or to its
  warehouse and, when no move explains its difference, to recording a correction."
  {:malli/schema [:=> [:cat ifn? moves/StockDifferences [:sequential moves/Proposal]] [:vector grid/Row]]}
  [label {:keys [items]} proposals]
  (vec (for [{:keys [stock-item-id state last-agreeing] :as item} items
             :let [transfers (item-transfers proposals stock-item-id (:run-id last-agreeing))]
             [index {:keys [warehouse-id ozon tover difference]}] (map-indexed vector (:rows item))
             :let [first? (zero? index)
                   links (cond-> (vec (for [{:keys [entry-id from to status]} transfers
                                            :when (and (= "pending" status) (#{from to} warehouse-id))]
                                        {:href (entry-path entry-id) :label (i18n/t "Open the pending transfer")}))
                           (and (= "unexplained" state) (not (zero? difference)))
                           (conj {:href correction-path :label (i18n/t "Record a correction on Stock")}))]]
         (cond-> {:id       (str stock-item-id "|" warehouse-id) :revision 1 :label (label stock-item-id stock-item-id) :selectable? false
                  :cells    {:item          (label stock-item-id stock-item-id) :warehouse (label warehouse-id warehouse-id)
                             :ozon          (str ozon) :tover (str tover) :difference (signed difference)
                             :state         (when first? (i18n/t (state-labels state state)))
                             :last-agreeing (when first? (:observed-at last-agreeing))
                             :transfers     (if first? (mapv #(transfer-line label %) transfers) [])}
                  :findings (when (not (zero? difference)) {:difference (i18n/t (state-labels state state))})}
           (seq links) (assoc :links {:difference links})))))


(defn table
  "The Stock differences table props, or nil when they cannot be read."
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [context connection-id]
  (products-web/read-or-nil
    #(let [read (runtime/read-model context ::moves/stock-differences {:connection-id connection-id})
           proposed (:proposals (runtime/read-model context ::moves/detected-move-proposals {:connection-id connection-id}))
           label (cutover-web/names (runtime/read-model context :tover.inventory.references/draft-catalogue {}))]
       {:id   "marketplace-differences"                 :label "Stock differences" :columns columns
        :read (select-keys read [:run-id :observed-at])
        :rows (rows label read proposed)})))


(def Query
  [:map [:connectionId connections/ConnectionId] [:requestId [:string {:max 100}]]])


(defview table-view
  {:route-data access :schema Query :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [connectionId requestId]} :input}]
  (products-web/connection! context connectionId)
  (grid/table-signal requestId (some-> (table context connectionId) grid/model)))


(defaction command
  ;; The table is read-only: it has no selection actions, so nothing is ever sent here.
  {:route-data access :schema [:map [:commandId [:string {:min 1 :max 100}]]]}
  [_ {:keys [commandId]}]
  (grid/rejected commandId (i18n/t "Accept or discard the proposed transfers on the Operations table.")))


(defn query-differences
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [connection-id]}]
  {:connection  (products-web/connection! context connection-id)
   :connections (operations/query-connections context)
   :table       (table context connection-id)})


(defpage differences-view
  {:route      "/operations/marketplace/:connection-id/differences"
   :title      "Stock differences · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id connections/ConnectionId]]
   :route-data access
   :query      query-differences}
  [{:keys [connection connections table]}]
  (let [{:keys [connection-id account-label]} connection
        observed (get-in table [:read :observed-at])]
    {:type :elements
     :html [:section.marketplace-page {:aria-labelledby "differences-title" :data-connection-id connection-id}
            [:div.management-page
             (operations/operations-views {:current [:differences connection-id] :connections connections})
             (products-web/header {:id        "differences-title" :title      (i18n/t "Stock differences")  :subtitle (str "Ozon · " account-label)
                                   :back-href "/operations"       :back-label (i18n/t "Back to operations")})
             [:p.management-secondary
              (str/join " · " [(if observed (i18n/t "Snapshot of {instant}" {:instant observed}) (i18n/t "No finished snapshot yet."))
                               (i18n/t "A move is proposed as pending transfers dated right after the last agreeing snapshot.")])
              " "
              [:a.differences-review {:href "/operations"} (i18n/t "Review and accept them on Operations")]]]
            (if table
              (grid/workbench (-> table (dissoc :read)
                                  (assoc :query {:connectionId connection-id} :query-view ::table-view :command-action ::command)))
              [:p.management-empty {:role "alert"} (i18n/t "Stock differences could not be loaded.") " "
               [:a {:href (differences-path connection-id)} (i18n/t "Retry")]])]}))

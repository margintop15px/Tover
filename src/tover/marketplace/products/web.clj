(ns tover.marketplace.products.web
  "Board chapter 42 screens under a connection's page. Product Matching: one editable table of the connection's
  listings, where members pick a stock item (Confirm), fill a new product (Register) or set listings aside (Defer)
  for the selected rows. The Mapped and Suspended filters are the same table with each mapped stock item: Change
  (another item picked in its cell), Remove and, for suspended rows, Reconfirm, one command per selected row.
  Mapping Details: one listing with its evidence, its mapping and history, read only, linked back to its table.

  Everything is read for the selected workspace and the connection in the path. Ozon text reaches the page only
  as escaped attribute or text content."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.identity.access :as access]
    [tover.inventory.actors :as actors]
    [tover.inventory.operations.web :as operations]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.invitations.team.web :as team]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.products.model :as products]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema])
  (:import
    [clojure.lang ExceptionInfo]))


(def ^:private access
  {:tover/access :workspace})


(defn connection-path
  {:malli/schema [:=> [:cat :string] :string]}
  [connection-id]
  (str "/settings/marketplace/" (codec/url-encode connection-id)))


(defn products-path
  {:malli/schema [:=> [:cat :string [:* :any]] :string]}
  [connection-id & [state]]
  (str (operations/marketplace-path connection-id) "/products" (when (and state (not= "unmatched" state)) (str "?state=" state))))


(defn listing-path
  {:malli/schema [:=> [:cat :string :string] :string]}
  [connection-id external-product-id]
  (str (operations/marketplace-path connection-id) "/products/" (codec/url-encode external-product-id)))


(defn connection!
  "The connection of the selected workspace, or the not-found page."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [context connection-id]
  (or (some #(when (= connection-id (:connection-id %)) %)
            (:connections (runtime/read-model context ::connections/marketplace-connections-and-requirements {})))
      (access/failure 404 :unknown-connection "Unknown connection")))


;; Labels


(def ^:private state-labels
  {"unmatched" "Unmatched" "deferred" "Deferred" "suspended" "Suspended" "confirmed" "Mapped"})


(def ^:private states
  ["unmatched" "deferred" "suspended" "confirmed"])


(def ^:private rule-labels
  {"barcode-owner" "{item} · same barcode" "article-match" "{item} · same article" "name-equal" "{item} · same name"})


(defn- item-label
  "`Product / variant`; the variant only when it has one."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :string]}
  [item product]
  (str (or (:name product) (:product-id item) (:stock-item-id item))
       (when-let [variant (some-> (:variant-label item) str/trim not-empty)] (str " / " variant))))


(defn- index
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [products stock-items]}]
  {:products (into {} (map (juxt :product-id identity)) products)
   :items    (into {} (map (juxt :stock-item-id identity)) stock-items)})


(defn- item-option
  {:malli/schema [:=> [:cat :map :map] grid/Option]}
  [{:keys [products]} item]
  {:value (:stock-item-id item) :label (item-label item (get products (:product-id item))) :revision (:revision item)})


(defn- choosable?
  "Active Regular items of live products: what Confirm and Change accept."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [{:keys [products]} item]
  (and (= "active" (:status item)) (= "regular" (:condition item "regular"))
       (= "active" (:status (get products (:product-id item))))))


(def ^:private identifier-types
  {"article" "Article" "barcode" "Barcode" "sku" "SKU" "product-id" "Ozon product ID"})


(defn- identifier-lines
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :string]]}
  [identifiers]
  (vec (for [{:keys [type value active?]} identifiers]
         (str (i18n/t (identifier-types type type))
              ": " value (when-not active? (str " " (i18n/t "(inactive)")))))))


(defn barcode-list
  "A cell's barcodes as the member typed them: any subset, separated by commas or spaces."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]}
  [text]
  (vec (distinct (re-seq #"[^,\s]+" (or text "")))))


(def ^:private units
  (mapv (fn [unit] {:value unit :label (i18n/unit-label unit)}) stock-items/units))


;; Product Matching table


(def matching-columns
  [{:key :listing :label "Listing" :type :lines :width 240}
   {:key :identifiers :label "Identifiers" :type :lines :width 220}
   {:key :waiting :label "Waiting operations" :type :text :width 110}
   {:key :stock-item :label "Stock item" :type :reference :editable? true :lookup "stock-items" :width 260}
   {:key :adopt :label "Adopt barcodes" :type :text :editable? true :width 170}
   {:key :product :label "New product" :type :reference :editable? true :lookup "products" :new-name? true :width 220}
   {:key :variant :label "Variant" :type :text :editable? true :width 130}
   {:key :article :label "Article" :type :text :editable? true :width 130}
   {:key :barcodes :label "Barcodes" :type :text :editable? true :width 170}
   {:key :unit :label "Unit" :type :select :editable? true :options units :width 100}
   {:key :status :label "Status" :type :lines :width 260}])


(def mapped-columns
  [{:key :listing :label "Listing" :type :lines :width 260}
   {:key :stock-item :label "Stock item" :type :reference :editable? true :lookup "stock-items" :width 260}
   {:key :waiting :label "Waiting operations" :type :text :width 110}
   {:key :status :label "Status" :type :lines :width 260}])


(def selection-actions
  "By filter. Change, Remove and Reconfirm are about one mapping each, so each selected row is its own command."
  {"unmatched" [{:command "confirm" :label "Confirm" :primary? true}
                {:command "register" :label "Register"}
                {:command "defer" :label "Defer" :reason? true}]
   "confirmed" [{:command "change" :label "Change mapping" :primary? true :each? true}
                {:command "remove" :label "Remove mapping" :each? true}]
   "suspended" [{:command "reconfirm" :label "Reconfirm" :primary? true :each? true}
                {:command "change" :label "Change mapping" :each? true}
                {:command "remove" :label "Remove mapping" :each? true}]})


(defn- listing-lines
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [{:keys [name earlier-names archived?]}]
  (cond-> [(or name (i18n/t "No name"))]
    (seq earlier-names) (conj (i18n/t "Earlier: {names}" {:names (str/join ", " earlier-names)}))
    archived? (conj (i18n/t "Archived at Ozon"))))


(defn- status-lines
  {:malli/schema [:=> [:cat :map :map] [:vector :string]]}
  [{:keys [products]} {:keys [state deferral conflicts candidates]}]
  (cond-> [(i18n/t (state-labels state))]
    (:reason deferral) (conj (:reason deferral))
    (empty? candidates) (conj (i18n/t "No fitting stock item yet"))
    (seq conflicts) (into (for [{:keys [barcode product-ids]} conflicts]
                            (i18n/t "Barcode {barcode} belongs to {products}"
                                    {:barcode  barcode
                                     :products (str/join ", " (map #(:name (get products %) %) product-ids))})))))


(defn- same-name-product
  "An active product named like the listing: registering under it avoids a name collision."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [{:keys [products]} name]
  (when name
    (some #(when (and (= "active" (:status %)) (= (str/lower-case (str/trim name)) (str/lower-case (str/trim (:name % "")))))
             %)
          (vals products))))


(defn matching-row
  "One unmatched or deferred listing: its evidence, the suggested stock item and what Register starts from."
  {:malli/schema [:=> [:cat :string :map :map] grid/Row]}
  [connection-id catalogue {:keys [external-product-id listing-revision evidence candidates waiting-operations proposed] :as row}]
  (let [options  (vec (for [{:keys [stock-item-id rule]} candidates
                            :let [item (get-in catalogue [:items stock-item-id])]
                            :when item]
                        (let [option (item-option catalogue item)]
                          (assoc option :label (i18n/t (rule-labels rule "{item}") {:item (:label option)})))))
        chosen   (some (fn [{:keys [stock-item-id preselected?]}] (when preselected? stock-item-id)) candidates)
        existing (same-name-product catalogue (:product-name proposed))]
    {:id      external-product-id :revision listing-revision :label (or (:name evidence) external-product-id)
     :href    (listing-path connection-id external-product-id)
     :options {:stock-item options}
     :cells   {:listing     (listing-lines evidence)
               :identifiers (identifier-lines (:identifiers evidence))
               :waiting     (str waiting-operations)
               :stock-item  (when-let [option (some #(when (= chosen (:value %)) %) options)] option)
               :adopt       ""
               :product     (if existing
                              {:value (:product-id existing) :label (:name existing) :revision (:revision existing)}
                              {:value "" :label (or (:product-name proposed) "")})
               :variant     ""
               :article     (or (:article proposed) "")
               ;; Register keeps the barcodes the member leaves here: the listing's active ones to start with.
               :barcodes    (str/join ", " (:barcodes proposed))
               :unit        (:unit proposed)
               :status      (status-lines catalogue row)}}))


(defn mapped-row
  "A mapped or suspended listing: its stock item is the picker's value, with the item revision Reconfirm sends; the
  row revision is the mapping's, and the reviewed listing revision goes along."
  {:malli/schema [:=> [:cat :string :map] grid/Row]}
  [connection-id {:keys [external-product-id name listing-revision mapping-revision archived? state waiting-operations stock-item reason]}]
  (let [chosen (when stock-item
                 (cond-> {:value (:stock-item-id stock-item)
                          :label (str (item-label stock-item {:name (:product-name stock-item)})
                                      (when (:archived? stock-item) (str " " (i18n/t "(archived)"))))}
                   (:revision stock-item) (assoc :revision (:revision stock-item))))]
    {:id      external-product-id :revision (or mapping-revision 0) :revisions {:listing listing-revision}
     :label   (or name external-product-id)
     :href    (listing-path connection-id external-product-id)
     :options {:stock-item (if chosen [chosen] [])}
     :cells   {:listing    (listing-lines {:name name :archived? archived?})
               :stock-item chosen
               :waiting    (str waiting-operations)
               :status     (cond-> [(i18n/t (state-labels state))] reason (conj reason))}}))


(defn read-or-nil
  "nil when the rows cannot be read: unavailable information is never shown as an empty table."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [read]
  (try (read)
       (catch ExceptionInfo error
         (if (:status (ex-data error)) (throw error) nil))))


(defn- table-of
  {:malli/schema [:=> [:cat :map :string :string] [:maybe :map]]}
  [context connection-id state]
  (read-or-nil
    #(let [editable? (contains? #{"unmatched" "deferred"} state)]
       {:id                (str "marketplace-products-" state)
        :label             "Product matching"
        :columns           (if editable? matching-columns mapped-columns)
        :selection-actions (get selection-actions (if editable? "unmatched" state) [])
        :rows              (if editable?
                             (let [catalogue (index (runtime/read-model context :tover.inventory.references/draft-catalogue {}))]
                               (mapv (partial matching-row connection-id catalogue)
                                     (:rows (runtime/read-model context ::products/unmatched-external-products-and-candidates
                                                                {:connection-id connection-id :state state}))))
                             (mapv (partial mapped-row connection-id)
                                   (:rows (runtime/read-model context ::products/mapped-and-remaining-listings
                                                              {:connection-id connection-id :state state}))))})))


(defn table
  "The workbench props of `state` for the connection, or nil when they cannot be read; with `listing`, only that
  listing's row (a group on the Operations page opens matching so)."
  {:malli/schema [:=> [:cat :map :string :string [:* :any]] [:maybe :map]]}
  [context connection-id state & [listing]]
  (some-> (table-of context connection-id state)
          (cond-> listing (update :rows #(filterv (comp #{listing} :id) %)))))


(def Query
  [:map [:connectionId connections/ConnectionId] [:state [:enum "unmatched" "deferred" "suspended" "confirmed"]]
   [:listing {:optional true} [:maybe products/ListingId]] [:requestId [:string {:max 100}]]])


(defview table-view
  {:route-data access :schema Query :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [connectionId state listing requestId]} :input}]
  (connection! context connectionId)
  (grid/table-signal requestId (some-> (table context connectionId state listing) grid/model)))


(defn choices
  "Stock items (active, Regular, of an active product) or active products whose label contains `text`."
  {:malli/schema [:=> [:cat :map [:enum "stock-items" "products"] :string] [:vector :map]]}
  [catalogue lookup text]
  (let [text    (str/lower-case (str/trim text))
        found?  #(str/includes? (str/lower-case (:label %)) text)
        options (if (= "stock-items" lookup)
                  (map #(item-option catalogue %) (filter #(choosable? catalogue %) (vals (:items catalogue))))
                  (for [product (vals (:products catalogue)) :when (= "active" (:status product))]
                    {:value (:product-id product) :label (:name product) :revision (:revision product)}))]
    (->> options (filter found?) (sort-by (comp str/lower-case :label)) (take 30) vec)))


(defview choices-view
  {:route-data access
   :schema     [:map [:connectionId connections/ConnectionId] [:lookup [:enum "stock-items" "products"]]
                [:text [:string {:max 500}]] [:requestId [:string {:max 200}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [lookup text requestId]} :input}]
  (grid/choices-signal requestId (read-or-nil #(choices (index (runtime/read-model context :tover.inventory.references/draft-catalogue {}))
                                                        lookup text))))


;; Selection actions: one command for the selected rows


(def CommandRow
  "`:revision` is the listing's for unmatched and deferred rows, the mapping's for mapped and suspended ones."
  [:map [:id products/ListingId] [:revision products/Revision]
   [:revisions {:optional true} [:map-of :any :int]]
   [:values {:optional true} [:map-of :any [:maybe [:string {:max 1000}]]]]])


(def Command
  [:map
   [:connectionId connections/ConnectionId] [:command [:enum "confirm" "register" "defer" "change" "remove" "reconfirm"]]
   [:commandId [:string {:min 1 :max 100}]] [:reason {:optional true} [:maybe [:string {:max 500}]]]
   [:rows [:vector {:min 1 :max 5000} CommandRow]]])


(defn- text
  {:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  [values key]
  (some-> (get values key) str/trim not-empty))


(defn- number
  {:malli/schema [:=> [:cat :map :string] [:maybe :int]]}
  [values key]
  (some-> (text values key) parse-long))


(def ^:private paths
  "Where each rejection code is shown on its row."
  {"confirm"   {:barcode-conflict "stock-item" :item-not-available "stock-item" :barcode-not-on-listing "adopt"}
   "register"  {:product-not-available "product" :invalid-product-name   "product"    :product-name-taken "product"
                :unknown-unit          "unit"    :barcode-not-on-listing "barcodes"   :duplicate-variant  "variant"
                :uniqueness-conflict   "article" :item-exists            "stock-item"}
   "defer"     {}
   "change"    {:item-not-available "stock-item" :barcode-conflict "stock-item"}
   "remove"    {}
   "reconfirm" {:item-archived "stock-item" :barcode-conflict "stock-item"}})


(defn- mapping-input
  "Change, Remove or Reconfirm of one mapped row, or the row's reason it cannot be sent. `mapped` is the row's
  current stock item id: Reconfirm keeps it."
  {:malli/schema [:=> [:cat :string :map [:maybe :string]] [:or [:tuple :qualified-keyword :map] [:map [:rows [:vector :map]]]]]}
  [command {:keys [id revision revisions values]} mapped]
  (let [item     (text values "stock-item")
        reviewed (get (update-keys (or revisions {}) name) "listing")
        input    {:external-product-id id :mapping-revision revision}
        refuse   (fn [path message] {:rows [{:id id :path path :message (i18n/t message)}]})]
    (case command
      "remove" [::products/change-product-mapping input]
      "change" (if (and item (number values "stock-item-revision"))
                 [::products/change-product-mapping (assoc input :stock-item-id item :item-revision (number values "stock-item-revision"))]
                 (refuse "stock-item" "Choose a stock item."))
      "reconfirm" (cond (not= item mapped)
                        (refuse "stock-item" "Reconfirm keeps the mapped stock item. Use Change mapping for another one.")
                        (not (and (number values "stock-item-revision") reviewed))
                        (refuse "stock-item" "The stock item is archived or defective. Change the mapping instead.")
                        :else
                        [::products/reconfirm-product-mapping
                         (assoc input :listing-revision reviewed :item-revision (number values "stock-item-revision"))]))))


(defn command-input
  "The command and its input for the selected rows, or the rows that cannot be sent with their reasons. `known`
  holds the connection's listing rows by id."
  {:malli/schema [:=> [:cat :map [:map-of :string :map]] [:or [:tuple :qualified-keyword :map] [:map [:rows [:vector :map]]]]]}
  [{:keys [command rows reason]} known]
  (let [rows (mapv #(update % :values (fn [values] (update-keys (or values {}) name))) rows)]
    (case command
      ("change" "remove" "reconfirm")
      (if (next rows)
        {:rows (mapv (fn [{:keys [id]}] {:id id :path "status" :message (i18n/t "Send one listing at a time.")}) rows)}
        (let [{:keys [id] :as row} (first rows)]
          (mapping-input command row (get-in known [id :stock-item :stock-item-id]))))
      "defer" [::products/defer-product-match
               (cond-> {:external-product-ids (mapv :id rows)} (some-> reason str/trim not-empty) (assoc :reason (str/trim reason)))]
      "confirm" (let [missing (filter #(not (and (text (:values %) "stock-item") (number (:values %) "stock-item-revision"))) rows)]
                  (if (seq missing)
                    {:rows (mapv (fn [{:keys [id]}] {:id id :path "stock-item" :message (i18n/t "Choose a stock item.")}) missing)}
                    [::products/confirm-product-mapping
                     {:rows (mapv (fn [{:keys [id revision values]}]
                                    (cond-> {:external-product-id id :listing-revision revision
                                             :stock-item-id       (text values "stock-item") :item-revision (number values "stock-item-revision")}
                                      (seq (barcode-list (get values "adopt"))) (assoc :adopt-barcodes (barcode-list (get values "adopt")))))
                                  rows)}]))
      "register" (let [missing (filter #(and (text (:values %) "product") (not (number (:values %) "product-revision"))) rows)]
                   (if (seq missing)
                     {:rows (mapv (fn [{:keys [id]}] {:id id :path "product" :message (i18n/t "Choose a product from the list.")}) missing)}
                     [::products/register-product-for-external-listing
                      {:rows (mapv (fn [{:keys [id revision values]}]
                                     (let [product (text values "product")]
                                       (cond-> {:external-product-id id :listing-revision revision :unit (or (text values "unit") "pcs")
                                                :barcodes            (barcode-list (get values "barcodes"))}
                                         product (assoc :product-id product :product-revision (number values "product-revision"))
                                         (not product) (assoc :product-name (or (text values "product-name") ""))
                                         (text values "variant") (assoc :variant-label (text values "variant"))
                                         (text values "article") (assoc :article (text values "article")))))
                                   rows)}])))))


(defn- listings!
  "The connection's listing rows by id; fails as unknown unless every listing is one of the connection's in the
  selected workspace."
  {:malli/schema [:=> [:cat :map :string [:sequential :string]] [:map-of :string :map]]}
  [context connection-id ids]
  (let [known (into {} (map (juxt :external-product-id identity))
                    (:rows (runtime/read-model context ::products/mapped-and-remaining-listings {:connection-id connection-id})))]
    (if (every? known ids)
      known
      (access/failure 404 :unknown-listing "Unknown listing"))))


(defaction command
  {:route-data access :schema Command}
  [context {:keys [connectionId commandId rows] :as input}]
  (connection! context connectionId)
  (let [result (command-input input (listings! context connectionId (mapv :id rows)))]
    (if (map? result)
      (grid/rejected commandId (i18n/t "Some rows are not ready.") :rows (:rows result))
      (let [[command-id command-input] result]
        {:type  :command :id command-id :command-id commandId :input command-input
         :then  {:type :view :id ::grid/committed :input {:commandId commandId}}
         :catch {:type  :view :id ::grid/failed
                 :input {:commandId commandId :idKey "external-product-id" :path "status" :paths (get paths (:command input))}}}))))


;; Pages


(defn- progress-line
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [{:keys [mapped unmatched deferred suspended]}]
  [:p.management-secondary {:role "status"}
   (i18n/t "Mapped {mapped} · Unmatched {unmatched} · Deferred {deferred} · Suspended {suspended}"
           {:mapped mapped :unmatched unmatched :deferred deferred :suspended suspended})])


(defn query-products
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [connection-id state listing]}]
  (let [connection (connection! context connection-id)
        state      (if (some #{state} states) state "unmatched")]
    {:connection  connection
     :state       state
     :listing     listing
     :connections (operations/query-connections context)
     :progress    (read-or-nil #(runtime/read-model context ::products/matching-progress {:connection-id connection-id}))
     :table       (table context connection-id state listing)}))


(defn header
  {:malli/schema [:=> [:cat [:map [:id :string] [:title :string] [:subtitle :string] [:back-href :string] [:back-label :string]]]
                  schema/Hiccup]}
  [{:keys [id title subtitle back-href back-label]}]
  [:header.management-header
   [:h1 {:id id :tabindex -1} title]
   [:p.management-subtitle subtitle]
   [:p [:a {:href back-href} back-label]]])


(defpage product-matching
  {:route      "/operations/marketplace/:connection-id/products"
   :title      "Product matching · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id connections/ConnectionId] [:state {:optional true} [:string {:max 20}]]
                [:listing {:optional true} products/ListingId]]
   :route-data access
   :query      query-products}
  [{:keys [connection connections state listing progress table]}]
  (let [{:keys [connection-id account-label]} connection]
    {:type :elements
     :html [:section.marketplace-page {:aria-labelledby "matching-title" :data-connection-id connection-id}
            [:div.management-page
             (operations/operations-views {:current [:products connection-id] :connections connections})
             (header {:id        "matching-title" :title      (i18n/t "Product matching")   :subtitle (str "Ozon · " account-label)
                      :back-href "/operations"    :back-label (i18n/t "Back to operations")})
             (when progress (progress-line progress))]
            (if table
              (grid/workbench (assoc table
                                     :query (cond-> {:connectionId connection-id :state state} listing (assoc :listing listing))
                                     :query-view ::table-view :command-action ::command :choices-view ::choices-view
                                     :filters (mapv (fn [s] {:label (state-labels s) :href (products-path connection-id s) :current? (= s state)})
                                                    states)))
              [:p.management-empty {:role "alert"} (i18n/t "Listings could not be loaded.") " "
               [:a {:href (products-path connection-id state)} (i18n/t "Retry")]])]}))


;; Mapping Details


(defn query-mapping
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [connection-id listing-id]}]
  (let [connection (connection! context connection-id)
        details    (try (runtime/read-model context ::products/mapping-details-and-source-evidence {:external-product-id listing-id})
                        (catch ExceptionInfo error
                          (if (= :not-found (:type (ex-data error)))
                            (access/failure 404 :unknown-listing "Unknown listing")
                            (throw error))))
        _          (when-not (= connection-id (get-in details [:listing :connection-id]))
                     (access/failure 404 :unknown-listing "Unknown listing"))
        catalogue  (index (runtime/read-model context :tover.inventory.references/draft-catalogue {}))
        ;; `enrich` also names the connector principal (`connector:<connection id>`) that suspends mappings.
        actors     (into {} (map (juxt :actor :actor-label))
                         (actors/enrich context (keep #(some->> % (hash-map :actor))
                                                      (concat [(get-in details [:mapping :confirmed-by]) (get-in details [:mapping :deferred-by])]
                                                              (map :changed-by (:history details))))))]
    {:connection connection :connections (operations/query-connections context) :details details :catalogue catalogue :actors actors}))


(def ^:private guard-rules
  {"listing-identity-changed" "Listing identifiers changed"
   "barcode-moved"            "Barcode moved to another product"
   "item-identity-changed"    "Stock item changed"
   "item-archived"            "Stock item archived"})


(def ^:private change-kinds
  {"mapped" "Confirmed" "changed" "Changed" "suspended" "Suspended" "reconfirmed" "Reconfirmed"})


(defn- reason-value
  "A suspension's before or after value: text as it is, anything else as JSON."
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (cond (nil? value) "—" (string? value) value :else (grid/json value)))


(defn- actor
  {:malli/schema [:=> [:cat :map [:maybe :string]] :string]}
  [actors id]
  (or (get actors id) (when id (i18n/t "Unavailable user")) ""))


(defpage mapping
  {:route      "/operations/marketplace/:connection-id/products/:listing-id"
   :title      "Mapping details · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id connections/ConnectionId] [:listing-id products/ListingId]]
   :route-data access
   :query      query-mapping}
  [{:keys [connection connections details catalogue actors]}]
  (let [{:keys [listing state mapping history stock-item operations other-listings]} details
        {:keys [evidence]} listing
        connection-id (:connection-id connection)]
    {:type :elements
     :html [:section.management-page {:aria-labelledby "mapping-title" :data-listing-id (:external-product-id listing)}
            (operations/operations-views {:current [:products connection-id] :connections connections})
            (header {:id        "mapping-title"               :title      (or (:name evidence) (:external-product-id listing))
                     :subtitle  (i18n/t (state-labels state))
                     :back-href (products-path connection-id) :back-label (i18n/t "All listings")})
            [:div.management-content
             [:dl.management-details
              [:dt (i18n/t "Stock item")]
              [:dd (if stock-item
                     (str (item-label (get-in catalogue [:items (:stock-item-id stock-item)] stock-item) {:name (:product-name stock-item)})
                          (when (:archived? stock-item) (str " " (i18n/t "(archived)"))))
                     (i18n/t "Not mapped"))]
              (when-let [at (:confirmed-at mapping)]
                (list [:dt (i18n/t "Confirmed")] [:dd (actor actors (:confirmed-by mapping)) " · " (team/moment at)]))
              (when-let [at (:deferred-at mapping)]
                (list [:dt (i18n/t "Deferred")] [:dd (actor actors (:deferred-by mapping)) " · " (team/moment at)
                                                 (when-let [reason (:reason mapping)] (list " · " reason))]))
              [:dt (i18n/t "Listing revision")]
              [:dd (:listing-revision listing)]
              (when-let [category (:category evidence)] (list [:dt (i18n/t "Category")] [:dd category]))
              [:dt (i18n/t "Operations")]
              [:dd (count operations)]]
             (when-let [reasons (seq (:reasons mapping))]
               [:section.management-section {:aria-labelledby "suspended-title"}
                [:div.management-section-heading [:h2#suspended-title (i18n/t "Suspended because")]]
                [:div.management-table-wrap
                 [:table.management-table.management-diff
                  [:thead [:tr [:th {:scope "col"} (i18n/t "Change")] [:th {:scope "col"} (i18n/t "Before")] [:th {:scope "col"} (i18n/t "After")]]]
                  (into [:tbody]
                        (for [{:keys [rule before after]} reasons]
                          [:tr [:td.management-primary (i18n/t (guard-rules rule rule))]
                           [:td.management-diff-before (reason-value before)] [:td.management-diff-after (reason-value after)]]))]]])
             [:p [:a {:href (products-path connection-id state)} (i18n/t "Open in the matching table")]]
             [:section.management-section {:aria-labelledby "identifiers-title"}
              [:div.management-section-heading [:h2#identifiers-title (i18n/t "Identifiers")]]
              [:div.management-table-wrap
               [:table.management-table
                [:thead [:tr [:th {:scope "col"} (i18n/t "Type")] [:th {:scope "col"} (i18n/t "Value")] [:th {:scope "col"} (i18n/t "Status")]]]
                (into [:tbody]
                      (for [{:keys [type value active?]} (:identifiers evidence)]
                        [:tr [:td (i18n/t (identifier-types type type))] [:td.management-primary value] [:td (i18n/t (if active? "Active" "Inactive"))]]))]]]
             [:section.management-section {:aria-labelledby "revisions-title"}
              [:div.management-section-heading [:h2#revisions-title (i18n/t "Listing revisions")]]
              [:div.management-table-wrap
               [:table.management-table
                [:thead [:tr [:th {:scope "col"} (i18n/t "Revision")] [:th {:scope "col"} (i18n/t "Observed")] [:th {:scope "col"} (i18n/t "Name")]]]
                (into [:tbody]
                      (for [{:keys [revision observed-at name]} (:revisions listing)]
                        [:tr [:td revision] [:td (team/moment observed-at)] [:td.management-primary name]]))]]]
             (when (seq history)
               [:section.management-section {:aria-labelledby "history-title"}
                [:div.management-section-heading [:h2#history-title (i18n/t "Mapping history")]]
                [:div.management-table-wrap
                 [:table.management-table
                  [:thead [:tr [:th {:scope "col"} (i18n/t "Revision")] [:th {:scope "col"} (i18n/t "Change")]
                           [:th {:scope "col"} (i18n/t "Stock item")] [:th {:scope "col"} (i18n/t "By")]]]
                  (into [:tbody]
                        (for [{:keys [mapping-revision change stock-item-id changed-by changed-at]} history
                              :let [item (get-in catalogue [:items stock-item-id])]]
                          [:tr [:td mapping-revision] [:td (i18n/t (change-kinds change change))]
                           [:td.management-primary (if item (item-label item (get-in catalogue [:products (:product-id item)])) (or stock-item-id "—"))]
                           [:td (actor actors changed-by) " · " (team/moment changed-at)]]))]]])
             (when (seq other-listings)
               [:section.management-section {:aria-labelledby "others-title"}
                [:div.management-section-heading [:h2#others-title (i18n/t "Other listings of this stock item")]]
                (into [:ul]
                      (for [{:keys [external-product-id name] other :connection-id} other-listings]
                        [:li [:a {:href (listing-path other external-product-id)} (or name external-product-id)]]))])]]}))

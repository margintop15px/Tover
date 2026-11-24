(ns tover.inventory.operations.web
  "Board chapters 18–25 · The operations page: every operation kind as one editable table, a group of rows per
  operation. A transfer or defect line shows as a red leaving row and a green entering row; production inputs are red
  and outputs green. Pending operations are shared drafts saved on each cell commit; Preview ticks the ones to submit, and
  Submit reviews and accepts them through the stock page's review and accept. Accepted operations are read-only
  until chapter 26. The browser side is `operations-workbench` in `operations.js`."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.expressions :refer [->expr]]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.movements :as movements]
    [tover.inventory.references :as references]
    [tover.inventory.stock.model :as stock]
    [tover.inventory.valuation :as valuation]
    [tover.inventory.web :as web]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui]
    [tover.workspaces.currency.model :as currency]))


(def ^:private access
  {:tover/access :workspace})


(defn- json
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (charred/write-json-str value))


(defn- decimal
  {:malli/schema [:=> [:cat :any] [:maybe decimal?]]}
  [value]
  (try (some-> value str str/trim not-empty bigdec) (catch NumberFormatException _ nil)))


(defn- by
  {:malli/schema [:=> [:cat :keyword [:maybe [:sequential :map]]] :map]}
  [k rows]
  (into {} (map (juxt k identity)) rows))


(defn- active?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [row]
  (= "active" (:status row)))


;; Saving one cell edit


(defn warehouse-prefill
  "The warehouse a chosen item fills in: its only warehouse with stock, then (sale, customer return) the store's
  default, then the product's default; each only when active."
  {:malli/schema [:=> [:cat :map :map :string [:maybe :string] :string] [:maybe :string]]}
  [{:keys [warehouses stock-items products stores]} stocked kind store-id item-id]
  (let [open?   (set (keep #(when (active? %) (:warehouse-id %)) warehouses))
        held    (filter open? (stocked item-id))
        store   (when (#{"sale" "customer-return"} kind) (:default-warehouse-id ((by :store-id stores) store-id)))
        product (:default-warehouse-id ((by :product-id products) (:product-id ((by :stock-item-id stock-items) item-id))))]
    (cond (= 1 (count held)) (first held)
          (open? store) store
          (open? product) product)))


(defn operation-input
  "The `Save Shared Inventory Entry` input after one edit of an operation: `values` name the `field`, its `value`,
  the `line-id` of a line field and, for a typed supplier, `referenceName`. The saved entry keeps the rest.
  Fields: create, occurred-at, reason, supplier, store, counterparty (`supplier:<id>` or `store:<id>`),
  from-warehouse, to-warehouse, direction, amount, add-line (`value` is a production side), remove, item, warehouse,
  quantity, price."
  {:malli/schema [:=> [:cat :map :map :uuid :string :int :map [:maybe :map]] :map]}
  [{:keys [stock-items suppliers] :as catalogue} stocked workspace-id entry-id revision values saved]
  (let [v       #(some-> (get values %) str/trim not-empty)
        kind    (or (:kind saved) (v "kind"))
        value   (v "value")
        line-id (v "line-id")
        put     (fn [m k x] (if x (assoc m k x) (dissoc m k)))
        mine?   #(= line-id (:line-id %))
        lines   (mapv #(cond-> % (:quantity %) (update :quantity str)) (:lines saved))
        line    (or (some #(when (mine? %) %) lines) {:line-id line-id})
        change  (fn [f] (let [l (f line)] (if (some mine? lines) (mapv #(if (mine? %) l %) lines) (conj lines l))))
        entry   (cond-> (merge {:workspace-id workspace-id :entry-id entry-id :kind kind}
                               (select-keys saved [:reason :supplier-id :supplier-name :store-id :from-warehouse-id :to-warehouse-id :direction :amount]))
                  (:occurred-at saved) (assoc :occurred-at (str (:occurred-at saved)))
                  (pos? revision) (assoc :expected-revision revision))
        item    (fn [l]
                  (let [id (or (:stock-item-id ((by :stock-item-id stock-items) value))
                               (when value (let [r (references/resolve value catalogue)] (or (:stock-item-id r) r))))
                        l  (dissoc l :stock-item-id :product-id :product-name :variant-label :article :barcodes :unit)]
                    (cond (string? id) (cond-> (assoc l :stock-item-id id)
                                         (and (not (:warehouse-id l)) (not= "transfer" kind))
                                         (put :warehouse-id (or (when (= "output" (:side l)) (some #(when (= "input" (:side %)) (:warehouse-id %)) lines))
                                                                (warehouse-prefill catalogue stocked kind (:store-id saved) id))))
                          (:new-product id) (assoc l :product-name (:new-product id))
                          (:product-id id) (assoc l :product-id (:product-id id))
                          :else l)))]
    (case (v "field")
      "create" (assoc entry :occurred-at value
                      :lines (case kind
                               "payment" []
                               "production" [{:line-id (str entry-id "-0") :side "input"} {:line-id (str entry-id "-1") :side "output"}]
                               [{:line-id (str entry-id "-0")}]))
      "occurred-at" (put (assoc entry :lines lines) :occurred-at value)
      "reason" (put (assoc entry :lines lines) :reason value)
      "supplier" (let [typed (v "referenceName")
                       known (when typed (some #(when (and (active? %) (= (records/name-key typed) (records/name-key (:name %)))) (:supplier-id %)) suppliers))]
                   (-> (assoc entry :lines lines)
                       (put :supplier-id (or known (when-not typed value)))
                       (put :supplier-name (when-not known typed))))
      "store" (put (assoc entry :lines lines) :store-id value)
      "counterparty" (let [[k id] (some-> value (str/split #":" 2))]
                       (-> (assoc entry :lines lines) (dissoc :supplier-id :supplier-name :store-id)
                           (put (if (= "store" k) :store-id :supplier-id) (not-empty id))))
      "from-warehouse" (put (assoc entry :lines lines) :from-warehouse-id value)
      "to-warehouse" (put (assoc entry :lines lines) :to-warehouse-id value)
      "direction" (put (assoc entry :lines lines) :direction value)
      "amount" (put (assoc entry :lines lines) :amount value)
      "add-line" (assoc entry :lines (conj lines (put {:line-id (str entry-id "-" (random-uuid))} :side (when (= "production" kind) (or value "input")))))
      "remove" (assoc entry :lines (vec (remove mine? lines)))
      "item" (assoc entry :lines (change item))
      "warehouse" (assoc entry :lines (change #(put % :warehouse-id value)))
      "quantity" (assoc entry :lines (change #(put % :quantity value)))
      "price" (assoc entry :lines (change #(put % (or (get-in entries/operation-rules [kind :price]) :unit-cost) value)))
      (assoc entry :lines lines))))


;; Operations table


(def ^:private group-columns
  ["select" "kind" "occurred-at" "party" "reason"])


(def ^:private line-columns
  ["item" "article" "unit" "warehouse" "quantity" "price" "amount" "total"])


(def ^:private finding-column
  {:supplier-id       "party"     :supplier-name   "party"     :store-id   "party" :occurred-at   "occurred-at" :lines        "kind"
   :stock-item-id     "item"      :product-name    "item"      :product-id "item"  :variant-label "item"        :warehouse-id "warehouse"
   :warehouse-name    "warehouse" :quantity        "quantity"  :unit-cost  "price" :unit-price    "price"       :reason       "reason"
   :from-warehouse-id "warehouse" :to-warehouse-id "warehouse" :direction  "kind"  :side          "kind"        :amount       "total"})


(def operation-kinds
  "Kinds the Add menu and the smart input offer, in menu order."
  ["purchase" "sale" "customer-return" "supplier-return" "transfer" "production" "defect" "write-off" "payment"])


(defn- money
  {:malli/schema [:=> [:cat [:maybe decimal?] :string] :string]}
  [value currency]
  (if value (valuation/display-amount value currency) ""))


(defn- names
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [products stock-items warehouses suppliers stores]}]
  (let [product (into {} (map (juxt :product-id :name)) products)]
    {:item      (into {} (map (fn [{:keys [stock-item-id product-id variant-label]}]
                                [stock-item-id (str (product product-id) (when (not-empty variant-label) (str " / " variant-label)))]))
                      stock-items)
     :product   product
     :warehouse (into {} (map (juxt :warehouse-id :name)) warehouses)
     :supplier  (into {} (map (juxt :supplier-id :name)) suppliers)
     :store     (into {} (map (juxt :store-id :name)) stores)}))


(defn- options
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] :keyword [:maybe :string]] [:vector :map]]}
  [rows k current]
  (into [{:value "" :label ""}]
        (for [r (sort-by (comp records/name-key :name) rows) :when (or (active? r) (= current (k r)))]
          {:value (k r) :label (cond-> (:name r) (not (active? r)) (str " " (i18n/t "(archived)")))})))


(defn- party-cell
  "The Supplier or store cell of a pending operation: a typed or chosen supplier (purchase), an active supplier
  (supplier return), a store (sale, customer return), either (payment); blank for the rest."
  {:malli/schema [:=> [:cat :map :map ifn?] :map]}
  [{:keys [catalogue label]} {:keys [kind supplier-id supplier-name store-id]} target]
  (case (get-in entries/operation-rules [kind :party])
    :supplier (if (= "purchase" kind)
                {:value  (or supplier-id "") :display (or (get-in label [:supplier supplier-id]) supplier-name "")
                 :editor {:type "catalogue-reference" :valueType "id" :options (if supplier-id [{:value supplier-id :label (get-in label [:supplier supplier-id] supplier-id)}] [])}
                 :target (target "supplier") :title   (i18n/t "Supplier")}
                {:value  (or supplier-id "") :display (get-in label [:supplier supplier-id] "")
                 :editor {:type "select" :allowEmpty true :options (options (:suppliers catalogue) :supplier-id supplier-id)} :target (target "supplier")})
    :store {:value  (or store-id "") :display (get-in label [:store store-id] "")
            :editor {:type "select" :allowEmpty true :options (options (:stores catalogue) :store-id store-id)} :target (target "store")}
    :either (let [current (cond supplier-id (str "supplier:" supplier-id) store-id (str "store:" store-id) :else "")]
              {:value  current :display (or (get-in label [:supplier supplier-id]) (get-in label [:store store-id]) "")
               :editor {:type    "select" :allowEmpty true
                        :options (into [{:value "" :label ""}]
                                       (concat (for [o (rest (options (:suppliers catalogue) :supplier-id supplier-id))] (update o :value #(str "supplier:" %)))
                                               (for [o (rest (options (:stores catalogue) :store-id store-id))] (update o :value #(str "store:" %)))))}
               :target (target "counterparty")})
    {:value "" :readOnly true}))


(defn- pending-group
  "Rows of one pending operation: shared cells on the first row, merged over its lines; every cell edits the draft.
  A transfer or defect line adds a read-only green row for its entering half."
  {:malli/schema [:=> [:cat :map :map :boolean] [:vector :map]]}
  [{:keys [catalogue currency label] :as ctx} {:keys [entry-id revision kind occurred-at reason direction amount from-warehouse-id to-warehouse-id lines blockers] :as entry} preview?]
  (let [item     (by :stock-item-id (:stock-items catalogue))
        target   (fn [field & [line]] (cond-> {:type  "operation-cell" :id       (str entry-id "|" (or (:line-id line) "")) :path field
                                               :entry entry-id         :revision revision :kind kind :field field}
                                        line (assoc :line (:line-id line))))
        price    (get-in entries/operation-rules [kind :price])
        payment? (= "payment" kind)
        lines    (if (seq lines) lines [nil])
        ro       {:value "" :readOnly true}
        wh       (fn [id field t] {:value  (or id "") :display (get-in label [:warehouse id] "")
                                   :editor {:type "select" :allowEmpty true :options (options (:warehouses catalogue) :warehouse-id id)} :target (t field)})
        shared   (fn [index]
                   (into (if preview? [(if (zero? index) {:selection {:id entry-id :label (i18n/t "Include in submit")}} {:value ""})] [])
                         [(if payment?
                            {:value   (or direction "") :display (web/kind-label kind direction) :className "operations-kind stock-cell-pending"
                             :editor  {:type "select" :allowEmpty true :options [{:value "" :label ""} {:value "in" :label (i18n/t "Payment received")}
                                                                                 {:value "out" :label (i18n/t "Payment made")}]}
                             :target  (target "direction")
                             :actions (if preview? [{:type "operation-discard" :entry entry-id :revision revision :label (i18n/t "Discard pending changes") :icon "×"}] [])}
                            {:value   (web/kind-label kind nil) :className "operations-kind stock-cell-pending"
                             :actions (cond-> (if (= "production" kind)
                                                [{:type "operation-add-line" :entry entry-id :revision revision :kind kind :side "input" :label (i18n/t "Add input") :icon "+"}
                                                 {:type "operation-add-line" :entry entry-id :revision revision :kind kind :side "output" :label (i18n/t "Add output") :icon "+"}]
                                                [{:type "operation-add-line" :entry entry-id :revision revision :kind kind :label (i18n/t "Add line") :icon "+"}])
                                        preview? (conj {:type "operation-discard" :entry entry-id :revision revision :label (i18n/t "Discard pending changes") :icon "×"}))})
                          {:value (or (some-> occurred-at str) "") :editor {:type "date"} :target (target "occurred-at")}
                          (party-cell ctx entry target)
                          {:value (or reason "") :editor {:type "text"} :target (target "reason")}]))
        row      (fn [id class index cells] {:id       id :groupId entry-id :className (str "operations-pending" (some->> class (str " "))) :cells (into (shared index) cells)
                                             :blockers (when (zero? index) blockers)})]
    (if payment?
      [(row (str entry-id "|") nil 0 (conj (vec (repeat (dec (count line-columns)) ro))
                                           {:value (or amount "") :editor {:type "decimal"} :target (target "amount") :className "inventory-cell-number"}))]
      (vec (apply concat
                  (for [[index line] (map-indexed vector lines)
                        :let         [i (item (:stock-item-id line))
                                      q (decimal (:quantity line))
                                      p (when price (decimal (get line price)))
                                      t #(assoc (target % line) :id (str entry-id "|" (:line-id line)))
                                      pair? (#{"transfer" "defect"} kind)
                                      out? (or pair? (= "input" (:side line)))
                                      n (count (filter some? lines))]]
                    (if-not line
                      [(row (str entry-id "|") nil index (repeat (count line-columns) ro))]
                      (let [item-cell   (cond-> {:value  (or (get-in label [:item (:stock-item-id line)]) (:product-name line) "")
                                                 :editor {:type "goods"} :target (t "item") :itemTarget (t "item") :itemValue (or (:stock-item-id line) "")}
                                          (< 1 n) (assoc :actions [(assoc (t "remove") :type "operation-cell" :label (i18n/t "Remove line") :icon "×" :value "")]))
                            leaving     [item-cell
                                         {:value (or (:article i) "") :readOnly true}
                                         {:value (or (i18n/unit-label (:unit i)) (:unit i) "") :readOnly true}
                                         (if (= "transfer" kind) (wh from-warehouse-id "from-warehouse" t) (wh (:warehouse-id line) "warehouse" t))
                                         {:value (some-> (:quantity line) str) :editor {:type "quantity"} :target (t "quantity") :className "inventory-cell-number"}
                                         (if price {:value (or (get line price) "") :editor {:type "decimal"} :target (t "price") :className "inventory-cell-number"} ro)
                                         {:value (money (when (and q p) (* q p)) currency) :readOnly true :className "inventory-cell-number"}
                                         ro]
                            counterpart (when (= "defect" kind) (movements/counterpart (:stock-items catalogue) (:stock-item-id line)))
                            entering    [{:value    (cond (nil? counterpart) (:value item-cell)
                                                          (:new-item? counterpart) (str (:value item-cell) " · " (i18n/t "defect") " " (i18n/t "(will be created)"))
                                                          :else (get-in label [:item (:stock-item-id counterpart)] ""))
                                          :readOnly true}
                                         {:value (or (:article i) "") :readOnly true}
                                         {:value (or (i18n/unit-label (:unit i)) (:unit i) "") :readOnly true}
                                         (if (= "transfer" kind)
                                           (wh to-warehouse-id "to-warehouse" t)
                                           {:value (get-in label [:warehouse (:warehouse-id line)] "") :readOnly true})
                                         {:value (some-> (:quantity line) str) :readOnly true :className "inventory-cell-number"}
                                         ro ro ro]]
                        (cond-> [(row (str entry-id "|" (:line-id line)) (when out? "operations-out") index leaving)]
                          pair? (conj (row (str entry-id "|" (:line-id line) "|in") "operations-in" (inc index) entering))
                          (= "output" (:side line)) (assoc-in [0 :className] "operations-pending operations-in"))))))))))


(defn- accepted-group
  {:malli/schema [:=> [:cat :map :map :boolean] [:vector :map]]}
  [{:keys [catalogue currency label]} {:keys [record-id kind direction occurred-at reason supplier-id store-id lines total partial?]} preview?]
  (let [item    (by :stock-item-id (:stock-items catalogue))
        ro      #(merge {:readOnly true} %)
        price   (get-in entries/operation-rules [kind :price])
        paired? (#{"transfer" "defect" "production"} kind)]
    (vec (for [[index line] (map-indexed vector (if (seq lines) lines [nil]))
               :let         [i (item (:stock-item-id line)) q (some-> (:quantity-change line) abs) p (when price (decimal (get line price)))]]
           {:id        (str record-id "|" (:position line)) :groupId record-id
            :className (when (and paired? line) (if (neg? (:quantity-change line)) "operations-out" "operations-in"))
            :cells     (-> (if preview? [(ro {:value ""})] [])
                           (into (map ro) [{:value (web/kind-label kind direction) :className "operations-kind"}
                                           {:value (str occurred-at) :date true}
                                           {:value (or (get-in label [:supplier supplier-id]) (get-in label [:store store-id]) "")}
                                           {:value (or reason "")}
                                           {:value (get-in label [:item (:stock-item-id line)] "")}
                                           {:value (or (:article i) "")}
                                           {:value (or (i18n/unit-label (:unit i)) (:unit i) "")}
                                           {:value (get-in label [:warehouse (:warehouse-id line)] "")}
                                           {:value (if q (str q) "") :className "inventory-cell-number"}
                                           {:value (money p currency) :className "inventory-cell-number"}
                                           {:value (money (some-> p (* q)) currency) :className "inventory-cell-number"}
                                           (cond-> {:value     (if (zero? index) (cond-> (money total currency) (and partial? total) (#(str "≥ " %))) "")
                                                    :className "inventory-cell-number"}
                                             (and (zero? index) partial?) (assoc :title (i18n/t "Some lines have no price.")))]))}))))


(defn operations-grid
  "Pending operations first (oldest first), then accepted ones newest first; in preview only pending ones, each with
  \"Include in submit\". Draft findings and blockers mark the cells they concern."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [catalogue pending findings accepted currency preview?]}]
  (let [ctx    {:catalogue catalogue :currency currency :label (names catalogue)}
        mine   (filter (comp (set operation-kinds) :kind) pending)
        groups (concat (for [e (sort-by (juxt (comp str :occurred-at) :entry-id) mine)] (pending-group ctx e preview?))
                       (when-not preview? (for [r accepted] (accepted-group ctx r preview?))))
        keys   (vec (concat (if preview? group-columns (rest group-columns)) line-columns))
        offset (if preview? 0 1)
        marks  (group-by (fn [{:keys [entry-id line-id field]}] [entry-id line-id (finding-column field "item")])
                         (concat (for [f findings] f)
                                 (for [{:keys [entry-id blockers]} mine b blockers
                                       :when (or (nil? (:line-id b)) (= :archived-reference (:code b)))]
                                   (assoc b :entry-id entry-id :level :error :blocker? true))))
        text   (fn [f] (if (:blocker? f) (web/problem-message f {}) (web/finding-text f {})))
        mark   (fn [row]
                 (let [[entry line] (str/split (:id row) #"\|" 2)]
                   (update row :cells
                           (fn [cells]
                             (mapv (fn [k cell]
                                     (let [fs (concat (get marks [entry (not-empty line) k]) (when (#{"kind" "occurred-at" "party" "reason" "warehouse" "total"} k) (get marks [entry nil k])))]
                                       (if (seq fs)
                                         (-> cell
                                             (update :className #(str/join " " (remove str/blank? [% "stock-finding" (str "stock-finding-" (name (or (some (comp #{:error} :level) fs) (:level (first fs)) :error)))])))
                                             (assoc :title (str/join "\n" (distinct (map text fs)))))
                                         cell)))
                                   keys cells)))))
        rows   (vec (map-indexed (fn [g rows] (map #(assoc % :groupIndex g) rows)) groups))
        flat   (vec (map mark (apply concat rows)))
        merges (second (reduce (fn [[start acc] group]
                                 [(+ start (count group))
                                  (into acc (when (< 1 (count group))
                                              (for [col (concat (range (- (count group-columns) offset)) [(dec (count keys))])]
                                                {:row start :col col :rowspan (count group)})))])
                               [0 []] groups))]
    {:id      "operations" :editable true :rowHeaders true :merges merges :editStates [] :showPrevious false
     :pending (vec (for [{:keys [entry-id revision]} mine] {:entry-id entry-id :revision revision :rowId entry-id}))
     :columns (mapv (fn [k] {:key   k :label (i18n/t ({"select"  ""          "kind"     "Kind"       "occurred-at" "Occurred at" "party" "Supplier or store"
                                                     "reason"    "Reason"    "item"     "Stock item" "article"     "Article"     "unit"  "Unit"
                                                     "warehouse" "Warehouse" "quantity" "Quantity"   "price"       "Unit price"
                                                     "amount"    "Amount"    "total"    "Total"} k))
                             :width ({"select" 40 "kind"      120 "occurred-at" 150 "party" 170 "reason" 150 "item"  220 "article" 100
                                      "unit"   70 "warehouse" 120 "quantity"    90  "price" 110 "amount" 110 "total" 110} k)})
                    keys)
     :rows    (mapv #(dissoc % :blockers) flat)
     ;; Choices for the smart input.
     :choices {:suppliers  (vec (for [r (rest (options (:suppliers catalogue) :supplier-id nil))] {:id (:value r) :name (:label r)}))
               :stores     (vec (for [r (rest (options (:stores catalogue) :store-id nil))] {:id (:value r) :name (:label r)}))
               :warehouses (vec (for [r (rest (options (:warehouses catalogue) :warehouse-id nil))] {:id (:value r) :name (:label r)}))
               :kinds      (vec (for [k operation-kinds :let [{:keys [party price reason?]} (entries/operation-rules k)]]
                                  {:kind k :label (web/kind-label k nil) :party (some-> party name) :price (boolean price) :reason (boolean reason?)}))}}))


;; Reads


(defn- stocked
  "Warehouses holding stock per item, from the stock table."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [table]
  (into {} (for [p (:products table) i (:items p)]
             [(:stock-item-id i) (vec (keep (fn [[w c]] (when (pos? (:quantity c 0)) w)) (:cells i)))])))


(defn- table-model
  {:malli/schema [:=> [:cat :map :boolean] [:maybe :map]]}
  [context preview?]
  (let [work      (web/read-or-nil context ::entries/pending-entries {})
        catalogue (web/read-or-nil context ::references/draft-catalogue {})
        accepted  (web/read-or-nil context ::acceptance/accepted-operations {})
        currency  (or (:currency (web/read-or-nil context ::currency/workspace-currency {})) "EUR")]
    (when (and work catalogue accepted)
      (operations-grid {:catalogue catalogue :pending  (:entries work) :findings (:findings work) :accepted accepted
                        :currency  currency  :preview? preview?}))))


(defview table
  {:route-data access :schema [:map [:requestId :string] [:preview :boolean]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId preview]} :input}]
  {:type :signals :data {:_operationsTableJson (json {:requestId requestId :model (table-model context preview)})}})


(defn item-options
  "Active stock items matching the typed text, for the goods editor."
  {:malli/schema [:=> [:cat [:maybe :map] :string] :map]}
  [catalogue text]
  (if-not catalogue
    {:status "failed" :options []}
    {:status  "ok"
     :options (vec (for [{:keys [kind id label]} (references/suggest (if (str/blank? text) "" text) catalogue) :when (= "stock-item" kind)]
                     {:value id :label label}))}))


(defview item-choices
  {:route-data access :schema [:map [:requestId :string] [:text [:string {:max 200}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId text]} :input}]
  {:type :signals
   :data {:_operationsItemsJson (json (assoc (item-options (web/read-or-nil context ::references/draft-catalogue {}) text) :requestId requestId))}})


(defview supplier-choices
  {:route-data access :schema [:map [:requestId :string] [:text [:string {:max 200}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId text]} :input}]
  (let [catalogue (web/read-or-nil context ::references/draft-catalogue {})
        query     (records/name-key (str/trim text))]
    {:type :signals
     :data {:_operationsSuppliersJson
            (json {:requestId requestId :status (if catalogue "ok" "failed")
                   :options   (vec (for [s     (sort-by (comp records/name-key :name) (:suppliers catalogue))
                                         :when (and (active? s) (str/includes? (records/name-key (:name s)) query))]
                                     {:value (:supplier-id s) :label (:name s)}))})}}))


;; Actions


(defaction save-operation
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]] [:revision [:int {:min 0}]]
                [:values [:map-of [:or :string :keyword] [:string {:max 1000}]]] [:commandId [:string {:min 1 :max 100}]]]}
  [context {:keys [workspaceId entryId revision values commandId]}]
  ;; Reads the saved entry to keep the rest of it, the catalogue to resolve names and the stock table for the
  ;; warehouse prefill; the command rechecks everything.
  (let [catalogue (runtime/read-model context ::references/draft-catalogue {})
        table     (runtime/read-model context ::stock/stock-table {:hide-empty? true})
        saved     (when (pos? revision)
                    (some #(when (= entryId (:entry-id %)) %) (:entries (runtime/read-model context ::entries/pending-entries {}))))]
    (web/descriptor ::entries/save-shared-inventory-entry commandId
                    (operation-input catalogue (stocked table) workspaceId entryId revision (update-keys values name) saved))))


;; Page


(defn query-page
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} _]
  {:workspace-id (get-in request [:tover/identity :workspace-id])
   :model        (table-model context false)})


(defn workbench
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [{:keys [workspace-id model]}]
  (let [options {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}]
    [:operations-workbench.operations-workbench.catalogue-workbench
     {:data-workspace-id            (str workspace-id)
      :data-model                   (json model)
      :data-on:operations-query     (->expr (.catch (@view ::table evt.detail ~options) (fn [_] (.requestFailed el "table"))))
      :data-on:operations-items     (->expr (.catch (@view ::item-choices evt.detail ~options) (fn [_] (.requestFailed el "items"))))
      :data-on:operations-suppliers (->expr (.catch (@view ::supplier-choices evt.detail ~options) (fn [_] (.requestFailed el "suppliers"))))
      :data-on:operations-add       (->expr (.catch (@action ::add-operation evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-on:operations-save      (->expr (.catch (@action ::save-operation evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-on:stock-review         (->expr (.catch (@view :tover.inventory.web/review evt.detail ~options) (fn [_] (.requestFailed el "review"))))
      :data-on:stock-describe       (->expr (.catch (@action :tover.inventory.web/describe-entry evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-on:stock-discard        (->expr (.catch (@action :tover.inventory.web/discard-entry evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-on:stock-accept         (->expr (.catch (@action :tover.inventory.web/accept evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-signals__ifmissing      (json {:_operationsTableJson "null" :_operationsItemsJson "null" :_operationsSuppliersJson "null"
                                           :_stockReviewJson     "null" :_stockSaveJson       "null" :inventoryChanged         ""})
      :data-effect                  "el.tableResponse=JSON.parse($_operationsTableJson); el.itemsResponse=JSON.parse($_operationsItemsJson); el.suppliersResponse=JSON.parse($_operationsSuppliersJson); el.reviewResponse=JSON.parse($_stockReviewJson); el.saveResponse=JSON.parse($_stockSaveJson); el.changed=$inventoryChanged"}
     [:div.smart-entry.stock-search
      [:div.smart-line
       [:input.catalogue-search.operations-smart-input {:type          "search" :autocomplete              "off"                    :role "combobox" :aria-expanded "false"
                                                        :aria-controls "operations-smart-list" :aria-label (i18n/t "Add operation")
                                                        :placeholder   (i18n/t "Type add to add an operation (Ctrl+K)")}]]
      [:ul#operations-smart-list.stock-suggestions.operations-smart-list {:role "listbox" :hidden true}]
      [:div.operations-smart-chips {:hidden true}]]
     [:div.sheet-toolbar
      [:div.sheet-change-status [:span.stock-save-status.quiet {:role "status"}]]
      [:div.sheet-actions
       (into [:select.operations-add {:aria-label (i18n/t "Add operation")} [:option {:value ""} (i18n/t "Add operation")]]
             (for [k operation-kinds] [:option {:value k} (web/kind-label k nil)]))
       [:button.sheet-icon-button.stock-pending-filter {:type "button" :aria-pressed "false" :title (i18n/t "Preview pending changes") :aria-label (i18n/t "Preview pending changes")} (ui/sheet-icon :preview)]
       [:button.sheet-icon-button.stock-submit {:type "button" :disabled true :title (i18n/t "Submit pending changes") :aria-label (i18n/t "Submit pending changes")} (ui/sheet-icon :submit)]]]
     [:div.catalogue-notice.stock-notice {:role "status"}]
     [:inventory-grid#operations-grid.stock-grid {:data-ignore-morph "" :aria-label (i18n/t "Operations")}]]))


(defpage page
  {:route "/operations" :title "Operations · Tover" :parent :tover.shared.web.core/shell :route-data access :query query-page}
  [{:keys [model] :as props}]
  {:type :elements
   :html (if model
           (workbench props)
           [:p.management-empty {:role "alert"} (i18n/t "Operations could not be loaded.") " " [:a {:href "/operations"} (i18n/t "Retry")]])})


(defaction add-operation
  "Board chapters 18–25 · Smart input Done: one new operation with its party or warehouses, time, reason, amount and
  first line, built edit by edit as the table would save it."
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]]
                [:values [:map-of [:or :string :keyword] [:string {:max 1000}]]] [:commandId [:string {:min 1 :max 100}]]]}
  [context {:keys [workspaceId entryId values commandId]}]
  (let [catalogue (runtime/read-model context ::references/draft-catalogue {})
        held      (stocked (runtime/read-model context ::stock/stock-table {:hide-empty? true}))
        v         (update-keys values name)
        line      (str entryId "-0")
        steps     (cond-> [{"field" "create" "kind" (v "kind") "value" (v "occurred-at")}]
                    (seq (v "supplier")) (conj (if (= "purchase" (v "kind")) {"field" "supplier" "referenceName" (v "supplier")} {"field" "supplier" "value" (v "supplier")}))
                    (seq (v "store")) (conj {"field" "store" "value" (v "store")})
                    (seq (v "counterparty")) (conj {"field" "counterparty" "value" (v "counterparty")})
                    (seq (v "from-warehouse")) (conj {"field" "from-warehouse" "value" (v "from-warehouse")})
                    (seq (v "to-warehouse")) (conj {"field" "to-warehouse" "value" (v "to-warehouse")})
                    (seq (v "reason")) (conj {"field" "reason" "value" (v "reason")})
                    (seq (v "direction")) (conj {"field" "direction" "value" (v "direction")})
                    (seq (v "amount")) (conj {"field" "amount" "value" (v "amount")})
                    (seq (v "item")) (conj {"field" "item" "line-id" line "value" (v "item")})
                    (seq (v "quantity")) (conj {"field" "quantity" "line-id" line "value" (v "quantity")})
                    (seq (v "price")) (conj {"field" "price" "line-id" line "value" (v "price")}))]
    (web/descriptor ::entries/save-shared-inventory-entry commandId
                    (reduce (fn [saved step] (operation-input catalogue held workspaceId entryId 0 step saved)) nil steps))))

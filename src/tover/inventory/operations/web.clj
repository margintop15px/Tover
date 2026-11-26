(ns tover.inventory.operations.web
  "Board chapters 18–25 · The operations page: every operation kind as one editable table, a group of rows per
  operation. A transfer or defect line shows as a red leaving row and a green entering row; production inputs are red
  and outputs green. Pending operations are shared drafts saved on each cell commit; Preview ticks the ones to submit, and
  Submit reviews and accepts them through the stock page's review and accept. Outside Preview the table is
  read-only. Chapters 26–28: an edit of an accepted operation saves a pending revision, Cancel a pending cancellation
  and Copy a new operation. The browser side is `operations-workbench` in `operations.js`."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.expressions :refer [->expr]]
    [bel.nav :as nav]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.actors :as actors]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.movements :as movements]
    [tover.inventory.references :as references]
    [tover.inventory.stock.model :as stock]
    [tover.inventory.valuation :as valuation]
    [tover.inventory.web :as web]
    [tover.marketplace.connections.model :as connections]
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
  quantity, price; cancel (of the accepted operation `saved` amends); copy (`value` is the time, `saved` the operation
  copied and `copies` its id). A `line-id` the entry does not have starts a line, on the given `side` of a production."
  {:malli/schema [:=> [:cat :map :map :uuid :string :int :map [:maybe :map]] :map]}
  [{:keys [stock-items suppliers] :as catalogue} stocked workspace-id entry-id revision values saved]
  (let [v       #(some-> (get values %) str/trim not-empty)
        kind    (or (:kind saved) (v "kind"))
        value   (v "value")
        line-id (v "line-id")
        put     (fn [m k x] (if x (assoc m k x) (dissoc m k)))
        mine?   #(= line-id (:line-id %))
        lines   (mapv #(cond-> % (:quantity %) (update :quantity str)) (:lines saved))
        line    (or (some #(when (mine? %) %) lines) (put {:line-id line-id} :side (v "side")))
        change  (fn [f] (let [l (f line)] (if (some mine? lines) (mapv #(if (mine? %) l %) lines) (conj lines l))))
        entry   (cond-> (merge {:workspace-id workspace-id :entry-id entry-id :kind kind}
                               (select-keys saved [:reason :supplier-id :supplier-name :store-id :from-warehouse-id :to-warehouse-id :direction :amount :amends :cancel]))
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
      "cancel" (assoc (select-keys entry [:workspace-id :entry-id :kind :amends :expected-revision]) :cancel true)
      "copy" (-> (dissoc entry :amends :cancel)
                 (assoc :occurred-at value :copies (v "copies") :lines (vec (map-indexed #(assoc %2 :line-id (str entry-id "-" %1)) lines))))
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
  ["select" "kind" "occurred-at" "supplier" "store" "reason"])


(def ^:private line-columns
  ["item" "article" "unit" "warehouse" "quantity" "price" "actions"])


(def ^:private finding-column
  {:supplier-id       "supplier"  :supplier-name   "supplier"  :store-id   "store" :occurred-at   "occurred-at" :lines        "kind"
   :stock-item-id     "item"      :product-name    "item"      :product-id "item"  :variant-label "item"        :warehouse-id "warehouse"
   :warehouse-name    "warehouse" :quantity        "quantity"  :unit-cost  "price" :unit-price    "price"       :reason       "reason"
   :from-warehouse-id "warehouse" :to-warehouse-id "warehouse" :direction  "kind"  :side          "kind"        :amount       "quantity"  :amends "kind"})


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


(defn- supplier-cell
  "A typed or chosen supplier (purchase), an active supplier (supplier return, payment); blank for the rest."
  {:malli/schema [:=> [:cat :map :map ifn?] :map]}
  [{:keys [catalogue label]} {:keys [kind supplier-id supplier-name]} target]
  (let [name (get-in label [:supplier supplier-id])]
    (case (get-in entries/operation-rules [kind :party])
      :supplier (if (= "purchase" kind)
                  {:value  (or supplier-id "") :display (or name supplier-name "")
                   :editor {:type "catalogue-reference" :valueType "id" :options (if supplier-id [{:value supplier-id :label (or name supplier-id)}] [])}
                   :target (target "supplier") :title   (i18n/t "Supplier")}
                  {:value  (or supplier-id "") :display (or name "")
                   :editor {:type "select" :allowEmpty true :options (options (:suppliers catalogue) :supplier-id supplier-id)} :target (target "supplier")})
      ;; A payment has one counterparty: choosing a supplier clears the store.
      :either {:value  (if supplier-id (str "supplier:" supplier-id) "") :display (or name "")
               :editor {:type    "select" :allowEmpty true
                        :options (mapv #(cond-> % (seq (:value %)) (update :value (partial str "supplier:"))) (options (:suppliers catalogue) :supplier-id supplier-id))}
               :target (assoc (target "counterparty") :path "supplier")}
      {:value "" :readOnly true})))


(defn- store-cell
  "The store of a sale, customer return or payment; blank for the rest."
  {:malli/schema [:=> [:cat :map :map ifn?] :map]}
  [{:keys [catalogue label]} {:keys [kind store-id]} target]
  (let [party (get-in entries/operation-rules [kind :party])
        name  (get-in label [:store store-id] "")]
    (case party
      :store {:value  (or store-id "") :display name
              :editor {:type "select" :allowEmpty true :options (options (:stores catalogue) :store-id store-id)} :target (target "store")}
      :either {:value  (if store-id (str "store:" store-id) "") :display name
               :editor {:type    "select" :allowEmpty true
                        :options (mapv #(cond-> % (seq (:value %)) (update :value (partial str "store:"))) (options (:stores catalogue) :store-id store-id))}
               :target (assoc (target "counterparty") :path "store")}
      {:value "" :readOnly true})))


(defn- payment-amount
  {:malli/schema [:=> [:cat [:maybe decimal?] :string] :string]}
  [amount currency]
  (if amount (str (money amount currency) " " currency) ""))


(defn- add-class
  {:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  [m class]
  (update m :className #(not-empty (str/join " " (remove str/blank? [% class])))))


(defn- group-rows
  "Rows of one operation's `facts`: shared cells on the first row, merged over its lines; every cell edits the draft in Preview.
  `source` says what an edit saves: the entry and its revision (0 before the first save), and the accepted revision it `:amends`.
  A transfer or defect line adds a read-only green row for its entering half. Once every line (of a production side)
  has an item, a blank row follows in Preview; choosing its item adds the line."
  {:malli/schema [:=> [:cat :map :string :map :map :boolean :boolean] [:vector :map]]}
  [{:keys [catalogue currency label] :as ctx} gid {:keys [kind occurred-at reason direction amount from-warehouse-id to-warehouse-id lines] :as facts}
   {:keys [entry revision] :as source} preview? money?]
  (let [item      (by :stock-item-id (:stock-items catalogue))
        target    (fn [field & [line]] (cond-> (merge source {:type "operation-cell" :id (str gid "|" (or (:line-id line) "")) :path field :kind kind :field field})
                                         line (assoc :line (:line-id line))
                                         (:side line) (assoc :side (:side line))))
        price     (get-in entries/operation-rules [kind :price])
        payment?  (= "payment" kind)
        pair?     (contains? #{"transfer" "defect"} kind)
        ro        {:value "" :readOnly true}
        wh        (fn [id field t] {:value  (or id "") :display (get-in label [:warehouse id] "")
                                    :editor {:type "select" :allowEmpty true :options (options (:warehouses catalogue) :warehouse-id id)} :target (t field)})
        shared    (into (if preview? [(if entry {:selection {:id entry :label (i18n/t "Include in submit")}} {:value ""})] [])
                        [(if payment?
                           {:value  (or direction "") :display (web/kind-label kind direction) :className "operations-kind"
                            :editor {:type "select" :allowEmpty true :options [{:value "" :label ""} {:value "in" :label (i18n/t "Payment received")}
                                                                               {:value "out" :label (i18n/t "Payment made")}]}
                            :target (target "direction")}
                           {:value (web/kind-label kind nil) :className "operations-kind"})
                         {:value (or (some-> occurred-at str) "") :editor {:type "date"} :target (target "occurred-at")}
                         (supplier-cell ctx facts target)
                         (store-cell ctx facts target)
                         {:value (or reason "") :editor {:type "text"} :target (target "reason")}])
        line-row  (fn [line class cells]
                    (-> {:id (str gid "|" line) :groupId gid :entry entry :line (not-empty line) :cells cells}
                        (add-class (when entry "operations-pending")) (add-class class)))
        item-row  (fn [line]
                    (let [i           (item (:stock-item-id line))
                          q           (decimal (:quantity line))
                          p           (when price (decimal (get line price)))
                          t           #(assoc (target % line) :id (str gid "|" (:line-id line)))
                          out?        (or pair? (= "input" (:side line)))
                          in?         (= "output" (:side line))
                          about       [{:value (or (:article i) "") :readOnly true}
                                       {:value (or (i18n/unit-label (:unit i)) (:unit i) "") :readOnly true}]
                          units       (fn [cell sign] (if money?
                                                        {:value (money (when (and q p) (* q p)) currency) :readOnly true :className "inventory-cell-number"}
                                                        (cond-> (merge {:value (some-> (:quantity line) str) :className "inventory-cell-number"} cell)
                                                          (and sign q) (assoc :prefix sign))))
                          name        (or (get-in label [:item (:stock-item-id line)]) (:product-name line) "")
                          leaving     (into [{:value       name :editor {:type "goods"} :target (t "item") :itemTarget (t "item") :itemValue (or (:stock-item-id line) "")
                                              :placeholder (i18n/t (case (:side line) "input" "Choose an input" "output" "Choose an output" "Choose a stock item"))}]
                                            (conj about
                                                  (if (= "transfer" kind) (wh from-warehouse-id "from-warehouse" t) (wh (:warehouse-id line) "warehouse" t))
                                                  (units {:editor {:type "quantity"} :target (t "quantity")} (cond out? "−" in? "+"))
                                                  (if price {:value (or (get line price) "") :editor {:type "decimal"} :target (t "price") :className "inventory-cell-number"} ro)
                                                  {:value   ""
                                                   :actions (if (< 1 (count lines)) [(assoc (t "remove") :type "operation-cell" :label (i18n/t "Remove line") :icon "×" :value "")] [])}))
                          counterpart (when (= "defect" kind) (movements/counterpart (:stock-items catalogue) (:stock-item-id line)))
                          entering    (into [{:value    (cond (nil? counterpart) name
                                                              (:new-item? counterpart) (str name " · " (i18n/t "defect") " " (i18n/t "(will be created)"))
                                                              :else (get-in label [:item (:stock-item-id counterpart)] ""))
                                              :readOnly true}]
                                            (conj about
                                                  (if (= "transfer" kind)
                                                    (wh to-warehouse-id "to-warehouse" t)
                                                    {:value (get-in label [:warehouse (:warehouse-id line)] "") :readOnly true})
                                                  (units {:readOnly true} "+")
                                                  ro ro))]
                      (cond-> [(line-row (:line-id line) (cond out? "operations-out" in? "operations-in") leaving)]
                        pair? (conj (line-row (str (:line-id line) "|in") "operations-in" entering)))))
        side-rows (fn [side]
                    (let [lines (filter #(= side (:side %)) lines)
                          blank {:line-id (str gid "-r" revision (some->> side (str "-"))) :side side}
                          t     #(assoc (target % blank) :id (str gid "|" (:line-id blank)))]
                      (cond-> (vec (mapcat item-row lines))
                        (and preview? (every? #(some % [:stock-item-id :product-id :product-name]) lines))
                        (conj (assoc (line-row (:line-id blank) (str "operations-new-line" (case side "input" " operations-out" "output" " operations-in" ""))
                                               (into [{:value       "" :editor {:type "goods"} :target (t "item") :itemTarget (t "item") :itemValue ""
                                                       :placeholder (str "+ " (i18n/t (case side "input" "Add input" "output" "Add output" "Add line")))}]
                                                     (repeat (dec (count line-columns)) ro)))
                                     :new-line? true)))))
        rows      (cond payment? [(line-row "" nil [ro ro ro ro
                                                    {:value  (or amount "")    :display (payment-amount (decimal amount) currency)
                                                     :editor {:type "decimal"} :target  (target "amount") :className "inventory-cell-number"}
                                                    ro ro])]
                        (= "production" kind) (into (side-rows "input") (side-rows "output"))
                        :else (side-rows nil))]
    (vec (map-indexed (fn [index row] (update row :cells #(into (if (and preview? (pos? index)) (assoc shared 0 {:value ""}) shared) %)))
                      (or (seq rows) [(line-row "" nil (repeat (count line-columns) ro))])))))


(defn- accepted-group
  {:malli/schema [:=> [:cat :map :map :boolean] [:vector :map]]}
  [{:keys [catalogue currency label]} {:keys [record-id kind direction occurred-at reason supplier-id store-id lines total partial?]} money?]
  (let [item    (by :stock-item-id (:stock-items catalogue))
        price   (get-in entries/operation-rules [kind :price])
        paired? (#{"transfer" "defect" "production"} kind)]
    (vec (for [[index line] (map-indexed vector (if (seq lines) lines [nil]))
               :let         [i (item (:stock-item-id line)) change (:quantity-change line) q (some-> change abs) p (when price (decimal (get line price)))]]
           {:id        (str record-id "|" (:position line)) :groupId record-id
            :className (when (and paired? line) (if (neg? change) "operations-out" "operations-in"))
            :cells     (mapv #(assoc % :readOnly true)
                             [{:value (web/kind-label kind direction) :className "operations-kind"}
                              {:value (str occurred-at) :date true}
                              {:value (get-in label [:supplier supplier-id] "")}
                              {:value (get-in label [:store store-id] "")}
                              {:value (or reason "")}
                              {:value (get-in label [:item (:stock-item-id line)] "")}
                              {:value (or (:article i) "")}
                              {:value (or (i18n/unit-label (:unit i)) (:unit i) "")}
                              {:value (get-in label [:warehouse (:warehouse-id line)] "")}
                              (cond-> {:className "inventory-cell-number"
                                       :value     (cond (= "payment" kind) (payment-amount total currency)
                                                        money? (money (some-> p (* q)) currency)
                                                        :else (str q))}
                                (and paired? q (not money?)) (assoc :prefix (if (neg? change) "−" "+"))
                                (and money? (zero? index) total (< 1 (count lines)))
                                (assoc :title (str (i18n/t "Total") ": " (money total currency) (when partial? (str ". " (i18n/t "Some lines have no price."))))))
                              {:value (money p currency) :className "inventory-cell-number"}
                              {:value ""}])}))))


(defn- lock
  "A cell without its editor and actions."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [editor] :as cell}]
  (cond-> (assoc (dissoc cell :editor :target :itemTarget :itemValue :placeholder :actions) :readOnly true)
    (= "date" (:type editor)) (assoc :date true)))


(defn- compared
  "Rows of a pending revision against the accepted `base` rows: a changed cell keeps its accepted text as `:before`, a
  new line is marked whole and a removed one stays, struck through. `shared` cells are the operation's, of which the
  first `fixed` are not compared."
  {:malli/schema [:=> [:cat [:vector :map] [:vector :map] :int :int] [:vector :map]]}
  [rows base shared fixed]
  (let [base  (vec (remove :new-line? base))
        was   (by :id base)
        last? #(= (dec (+ shared (count line-columns))) %)
        same? (fn [a b] (or (= a b) (let [x (decimal a) y (decimal b)] (boolean (and x y (zero? (compare x y)))))))
        gone  (remove (comp (set (map :id rows)) :id) base)]
    (-> (mapv (fn [{:keys [id new-line?] :as row}]
                (cond-> row
                  (not new-line?)
                  (update :cells
                          #(vec (map-indexed (fn [x cell]
                                               (let [old (some-> (get-in (if (< x shared) (first base) (was id)) [:cells x]) web/cell-text)]
                                                 (cond (or (< x fixed) (last? x)) cell
                                                       (nil? old) (add-class cell "stock-cell-pending")
                                                       (same? old (web/cell-text cell)) cell
                                                       :else (add-class (assoc cell :before old) "stock-cell-pending"))))
                                             %)))))
              rows)
        ;; ponytail: removed lines go to the end of the group.
        (into (map #(-> % (dissoc :entry :line) (update :id str "|removed") (add-class "operations-cancelled")
                        (update :cells (partial mapv (fn [cell] (dissoc (lock cell) :selection))))))
              gone))))


(defn- group
  "Rows of one operation, `{:gid :entry :op}`: a pending one, an accepted one, both (accepted → pending), a pending
  cancellation (struck through, only its reason editable) or a cancelled one. As on the stock table, cells edit in
  Preview only; outside it the first row offers Edit, Copy and Cancel."
  {:malli/schema [:=> [:cat :map :map :boolean :boolean] [:vector :map]]}
  [ctx {:keys [gid entry op]} preview? money?]
  (let [base       (when op (movements/collapse op (:lines op)))
        amends     (when op (or (:amends entry) (:record-revision-id op)))
        entry-id   (:entry-id entry)
        source     (cond-> {:entry entry-id :revision (:revision entry 0)} amends (assoc :amends amends))
        cancelled? (boolean (and (:cancelled op) (not entry)))
        struck?    (or cancelled? (boolean (:cancel entry)))
        kind       (:kind (or entry op))
        facts      (cond (:cancel entry) (assoc base :reason (:reason entry)) entry entry :else base)
        render     (fn [facts] (group-rows ctx gid facts source preview? money?))
        fixed      (if preview? 1 0)
        shared     (+ fixed (dec (count group-columns)))
        rows       (cond->> (if (or entry (and preview? (not struck?))) (render facts) (accepted-group ctx op money?))
                     struck? (into [] (remove :new-line?))
                     (and entry op (not struck?)) (#(compared % (render base) shared (cond-> fixed (not= "payment" kind) inc))))
        do-action  (fn [type label icon & [more]] (merge source {:type type :kind kind :group gid :label label :icon icon} more))
        actions    (cond-> []
                     op (conj {:type "item-history" :id gid :row (:id (peek rows)) :label (i18n/t "Revisions")})
                     (and preview? entry) (conj (do-action "operation-discard" (i18n/t "Discard pending changes") "🗑"))
                     (not (or preview? cancelled?)) (conj {:type "stock-edit" :id gid :label (i18n/t "Edit") :icon "✎"})
                     (not preview?) (conj (do-action "operation-copy" (i18n/t "Copy") "⧉" {:source (if (and entry (not struck?)) entry-id (:record-revision-id op))}))
                     (and op (not (or preview? struck?))) (conj (do-action "operation-cancel" (i18n/t "Cancel operation") "⊘")))
        suffix     (cond cancelled? (i18n/t "(cancelled)") struck? (i18n/t "(will be cancelled)"))
        open?      (fn [x] (and preview? (or (< x fixed) (not struck?) (= (dec shared) x))))]
    (vec (map-indexed
           (fn [index {:keys [cells] :as row}]
             (let [end (dec (count cells))]
               (cond-> (assoc row :cells (vec (map-indexed #(if (open? %1) %2 (lock %2)) cells)))
                 struck? (add-class "operations-cancelled")
                 (and entry op) (update-in [:cells fixed] add-class "stock-cell-pending")
                 suffix (update-in [:cells fixed] #(assoc % :display (str (web/cell-text %) " " suffix)))
                 (zero? index) (update-in [:cells end] #(-> % (assoc :actions (into (vec (:actions %)) actions)) (dissoc :readOnly))))))
           rows))))


(defn- groups
  "Operations in page order: new pending ones oldest first, then accepted ones newest first, each with the pending
  entry that amends it; in preview only pending ones and the accepted ones in `editing`. A copy in `below` follows its
  source, or comes first when the source is not there."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map] [:maybe [:sequential :map]] [:maybe [:sequential :string]] :boolean] [:vector :map]]}
  [pending accepted below editing preview?]
  (let [op-of    (into {} (for [op accepted r (cons op (:revisions op))] [(:record-revision-id r) (:record-id op)]))
        amending (into {} (for [e pending :let [id (op-of (:amends e))] :when id] [id e]))
        all      (into (vec (for [e (sort-by (juxt (comp str :occurred-at) :entry-id) pending) :when (not (op-of (:amends e)))] {:gid (:entry-id e) :entry e}))
                       (for [op    accepted
                             :let  [gid (:record-id op) entry (amending gid)]
                             :when (or (not preview?) entry (and (some #{gid} editing) (not (:cancelled op))))]
                         {:gid gid :op op :entry entry}))]
    (reduce (fn [gs {:keys [entry group]}]
              (let [copy (some #(when (= entry (:gid %)) %) gs)]
                (cond (or (nil? copy) (= entry group)) gs
                      (some #(= group (:gid %)) gs) (vec (mapcat #(condp = (:gid %) entry nil group [% copy] [%]) gs))
                      :else (into [copy] (remove #(= entry (:gid %))) gs))))
            all below)))


(defn- revisions
  "What each revision of an operation said, first to last."
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [{:keys [label actors currency]} {:keys [kind revisions]}]
  (vec (for [{:keys [revision recorded-at occurred-at actor reason cancelled supplier-id store-id amount lines]} revisions]
         {:revision revision :recordedAt (str recorded-at) :occurredAt (str occurred-at) :reason (or reason "") :cancelled (boolean cancelled)
          :actor    (get actors (str actor) (i18n/t "Unavailable user"))
          :facts    (into (vec (remove str/blank? [(or (get-in label [:supplier supplier-id]) (get-in label [:store store-id])) (money (decimal amount) currency)]))
                          (for [{:keys [stock-item-id warehouse-id quantity-change] :as line} lines
                                :let [price (get line (get-in entries/operation-rules [kind :price]))]]
                            (str (get-in label [:item stock-item-id] "") " · " (get-in label [:warehouse warehouse-id] "") " · "
                                 (if (pos? quantity-change) (str "+" quantity-change) quantity-change)
                                 (when price (str " × " (money (decimal price) currency))))))})))


(defn- arrange
  "Operations sorted and filtered by column. An operation stays whole: it is kept when one of its rows matches every
  condition. Quantity sums the operation's rows; other columns compare its first row."
  {:malli/schema [:=> [:cat [:sequential [:sequential :map]] [:sequential :string] :map] [:sequential [:sequential :map]]]}
  [groups column-keys {:keys [sort direction filters]}]
  (let [at     (into {} (map-indexed (fn [i k] [k i])) column-keys)
        text   (fn [row k] (web/cell-text (nth (:cells row) (at k))))
        number (fn [row k] (or (decimal (:value (nth (:cells row) (at k)))) 0M))
        active (for [[k conditions] filters :let [k (name k)] :when (and (at k) (seq conditions))] [k conditions])
        keep?  (fn [row] (every? (fn [[k conditions]] (web/matches? (text row k) conditions)) active))
        groups (cond->> groups (seq active) (filter (fn [rows] (some keep? (remove :new-line? rows)))))
        k      (some-> sort name)
        order  (if (= "desc" direction) #(compare %2 %1) compare)]
    (cond (not (at k)) groups
          (#{"quantity" "price"} k) (sort-by (fn [rows] (reduce + 0M (map #(number % k) rows))) order groups)
          :else (sort-by #(records/name-key (text (first %) k)) order groups))))


(def ^:private column-labels
  {"select"  ""        "actions" ""     "kind"      "Kind"      "occurred-at" "Occurred at" "supplier" "Supplier"   "store" "Store" "reason" "Reason" "item" "Stock item"
   "article" "Article" "unit"    "Unit" "warehouse" "Warehouse" "quantity"    "Quantity"    "price"    "Unit price"})


(def ^:private column-widths
  {"select"   40  "actions" 130 "kind" 150 "occurred-at" 180 "supplier" 150 "store" 140 "reason" 150 "item" 220 "article" 110 "unit" 90 "warehouse" 140
   "quantity" 130 "price"   140})


(defn operations-grid
  "New pending operations first (oldest first), then accepted ones newest first, with their pending revisions and
  cancellations in place; in preview only pending ones, each with \"Include in submit\", and the accepted ones in
  `:editing`. Quantities, or amounts in money mode (never in preview). Draft findings and blockers mark the
  cells they concern; `:sort`, `:direction` and `:filters` order and narrow the operations."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [catalogue pending findings accepted currency preview? mode below editing actors] :as view}]
  (let [ctx    {:catalogue catalogue :currency currency :label (names catalogue) :actors actors}
        money? (and (= "money" mode) (not preview?))
        mine   (filter (comp (set operation-kinds) :kind) pending)
        keys   (vec (concat (if preview? group-columns (rest group-columns)) line-columns))
        groups (arrange (for [g (groups mine accepted below editing (boolean preview?))] (group ctx g (boolean preview?) money?)) keys view)
        offset (if preview? 0 1)
        marks  (group-by (fn [{:keys [entry-id line-id field]}] [entry-id line-id (finding-column field "item")])
                         (concat findings
                                 (for [{:keys [entry-id blockers]} mine b blockers
                                       :when (or (nil? (:line-id b)) (= :archived-reference (:code b)))]
                                   (assoc b :entry-id entry-id :level :error :blocker? true))))
        text   (fn [f] (if (:blocker? f) (web/problem-message f {}) (web/finding-text f {})))
        mark   (fn [{:keys [entry line new-line?] :as row}]
                 (cond-> row
                   entry (update :cells
                                 (fn [cells]
                                   (mapv (fn [k cell]
                                           (let [fs (concat (get marks [entry line k])
                                                            (when ((cond-> #{"kind" "occurred-at" "supplier" "store" "reason"} (not new-line?) (conj "warehouse" "quantity")) k)
                                                              (get marks [entry nil k])))]
                                             (if (seq fs)
                                               (-> cell
                                                   (add-class (str "stock-finding stock-finding-" (name (or (some (comp #{:error} :level) fs) (:level (first fs)) :error))))
                                                   (assoc :title (str/join "\n" (distinct (map text fs)))))
                                               cell)))
                                         keys cells)))))
        rows   (vec (map mark (apply concat (map-indexed (fn [g rows] (map #(assoc % :groupIndex g) rows)) groups))))
        merges (second (reduce (fn [[start acc] group]
                                 [(+ start (count group))
                                  (into acc (when (< 1 (count group))
                                              (for [col (range (- (count group-columns) offset))] {:row start :col col :rowspan (count group)})))])
                               [0 []] groups))]
    {:id          "operations" :editable true :rowHeaders false :merges merges :editStates []
     :layoutScope "operations"
     :pending     (vec (for [{:keys [entry-id revision]} mine] {:entry-id entry-id :revision revision :rowId entry-id}))
     :revisions   (into {} (for [op accepted] [(:record-id op) (revisions ctx op)]))
     :columns     (mapv (fn [k] (cond-> {:key k :label (i18n/t (if (and money? (= "quantity" k)) "Amount" (column-labels k))) :width (column-widths k)}
                                  (not (#{"select" "actions"} k)) (assoc :sortKey k :filterKey k)
                                  (#{"kind" "item"} k) (assoc :required true)
                                  (= "kind" k) (assoc :fixed true)))
                        keys)
     :rows        (mapv #(dissoc % :new-line?) rows)
     ;; Choices for the smart input.
     :choices     {:suppliers  (vec (for [r (rest (options (:suppliers catalogue) :supplier-id nil))] {:id (:value r) :name (:label r)}))
                   :stores     (vec (for [r (rest (options (:stores catalogue) :store-id nil))] {:id (:value r) :name (:label r)}))
                   :warehouses (vec (for [r (rest (options (:warehouses catalogue) :warehouse-id nil))] {:id (:value r) :name (:label r)}))
                   :kinds      (vec (for [k operation-kinds :let [{:keys [party price reason? new-items]} (entries/operation-rules k)]]
                                      {:kind     k :label (web/kind-label k nil) :party (some-> party name) :price (boolean price) :reason (boolean reason?)
                                       :newItems (case new-items true "all" :outputs "outputs" nil)}))}}))


;; Reads


(defn- stocked
  "Warehouses holding stock per item, from the stock table."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [table]
  (into {} (for [p (:products table) i (:items p)]
             [(:stock-item-id i) (vec (keep (fn [[w c]] (when (pos? (:quantity c 0)) w)) (:cells i)))])))


(defn- table-model
  {:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  [context view]
  (let [work      (web/read-or-nil context ::entries/pending-entries {})
        catalogue (web/read-or-nil context ::references/draft-catalogue {})
        accepted  (web/read-or-nil context ::acceptance/accepted-operations {})
        currency  (or (:currency (web/read-or-nil context ::currency/workspace-currency {})) "EUR")]
    (when (and work catalogue accepted)
      (operations-grid (assoc view :catalogue catalogue :pending (:entries work) :findings (:findings work) :accepted accepted :currency currency
                              :actors (into {} (map (juxt (comp str :actor) :actor-label))
                                            (actors/enrich context (map #(hash-map :actor %) (distinct (keep :actor (mapcat :revisions accepted)))))))))))


(defview table
  {:route-data access :schema [:map [:requestId :string] [:preview :boolean] [:mode {:optional true} [:maybe [:enum "units" "money"]]]
                               [:sort {:optional true} [:maybe :string]] [:direction {:optional true} [:maybe [:enum "asc" "desc"]]]
                               [:filters {:optional true} [:maybe :map]] [:editing {:optional true} [:vector :string]]
                               [:below {:optional true} [:vector [:map [:entry :string] [:group :string]]]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId preview] :as input} :input}]
  {:type :signals
   :data {:_operationsTableJson (json {:requestId requestId
                                       :model     (table-model context (assoc (select-keys input [:mode :sort :direction :filters :editing :below]) :preview? preview))})}})


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
  ;; warehouse prefill; the first edit of an accepted operation starts from it, and a copy from its source. The
  ;; command rechecks everything.
  (let [catalogue               (runtime/read-model context ::references/draft-catalogue {})
        table                   (runtime/read-model context ::stock/stock-table {:hide-empty? true})
        values                  (update-keys values name)
        {:strs [amends copies]} (update-vals values not-empty)
        pending                 (fn [id] (some #(when (= id (:entry-id %)) %) (:entries (runtime/read-model context ::entries/pending-entries {}))))
        accepted                (fn [id] (some #(when (some (comp #{id} :record-revision-id) (cons % (:revisions %))) (movements/collapse % (:lines %)))
                                               (runtime/read-model context ::acceptance/accepted-operations {})))
        saved                   (cond copies (or (pending copies) (accepted copies))
                                      (pos? revision) (pending entryId)
                                      amends (some-> (accepted amends) (assoc :amends amends)))]
    (web/descriptor ::entries/save-shared-inventory-entry commandId
                    (operation-input catalogue (stocked table) workspaceId entryId revision values saved))))


;; Page


(defn marketplace-path
  "The matching and cutover views of one connection, under the operations page."
  {:malli/schema [:=> [:cat :string] :string]}
  [connection-id]
  (str "/operations/marketplace/" (codec/url-encode connection-id)))


(defn query-connections
  "The workspace's connections as the view switcher lists them. Read through the connections model, which requires
  no page namespace, so the matching pages can require this one without a cycle. A failed read gives no connections:
  the switcher is a convenience and must not take the page down."
  {:malli/schema [:=> [:cat :map] [:vector [:map [:connection-id :string] [:account-label :string]]]]}
  [context]
  (try (mapv #(select-keys % [:connection-id :account-label])
             (:connections (runtime/read-model context ::connections/marketplace-connections-and-requirements {})))
       (catch Exception _ [])))


(defn operations-views
  "The views of the operations page: the table of operations, then each connection's Product matching, Warehouse
  matching and Cutover. `current` is `:operations`, `[:products connection-id]`, `[:warehouses connection-id]` or
  `[:cutover connection-id]`. Without connections there is nothing to switch to."
  {:malli/schema [:=> [:cat [:map [:current [:or :keyword [:tuple :keyword :string]]] [:connections [:sequential :map]]]] [:maybe schema/Hiccup]]}
  [{:keys [current connections]}]
  (when (seq connections)
    [:nav.catalogue-kinds.operations-views {:aria-label (i18n/t "Views")}
     (for [[view href label] (cons [:operations "/operations" (i18n/t "Operations")]
                                   (for [{:keys [connection-id account-label]} connections
                                         [kind segment title]                  [[:products "products" "Product matching"] [:warehouses "warehouses" "Warehouse matching"]
                                                                                [:cutover "cutover" "Cutover"]]]
                                     [[kind connection-id] (str (marketplace-path connection-id) "/" segment) (str (i18n/t title) " · " account-label)]))]
       [:a (nav/link href {:aria-current (when (= current view) "page")}) label])]))


(defn query-page
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} _]
  {:workspace-id (get-in request [:tover/identity :workspace-id])
   :connections  (query-connections context)
   :model        (table-model context {:preview? false})})


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
     [:div.smart-entry
      [:smart-entry#operations-smart {:data-ignore-morph ""}]
      [:div.smart-filters {:hidden true}]]
     [:div.sheet-toolbar
      [:div.sheet-change-status [:span.stock-save-status.quiet {:role "status"}]]
      [:div.sheet-actions
       (into [:select.operations-add {:aria-label (i18n/t "Add operation")} [:option {:value ""} (i18n/t "Add operation")]]
             (for [k operation-kinds] [:option {:value k} (web/kind-label k nil)]))
       (web/mode-switch {})
       (web/column-picker {})
       [:button.sheet-icon-button.stock-pending-filter {:type "button" :aria-pressed "false" :title (i18n/t "Preview pending changes") :aria-label (i18n/t "Preview pending changes")} (ui/sheet-icon :preview)]
       [:button.sheet-icon-button.stock-submit {:type "button" :disabled true :title (i18n/t "Submit pending changes") :aria-label (i18n/t "Submit pending changes")} (ui/sheet-icon :submit)]]]
     [:div.catalogue-notice.stock-notice {:role "status"}]
     [:inventory-grid#operations-grid.stock-grid {:data-ignore-morph "" :aria-label (i18n/t "Operations")}]]))


(defpage page
  {:route "/operations" :title "Operations · Tover" :parent :tover.shared.web.core/shell :route-data access :query query-page}
  [{:keys [model connections] :as props}]
  {:type :elements
   :html [:section.operations-page
          (operations-views {:current :operations :connections connections})
          (if model
            (workbench props)
            [:p.management-empty {:role "alert"} (i18n/t "Operations could not be loaded.") " " [:a {:href "/operations"} (i18n/t "Retry")]])]})


(defn addition-input
  "The `Save Shared Inventory Entry` input of a smart-input addition: its header fields, then its lines, built edit by
  edit as the table would save them. Lines without an item are left out."
  {:malli/schema [:=> [:cat :map :map :uuid :string :map [:sequential :map]] :map]}
  [catalogue held workspace-id entry-id v lines]
  (let [lines  (vec (map-indexed (fn [i line] (assoc line "line-id" (str entry-id "-" i))) (filter #(seq (get % "item")) lines)))
        step   (fn [saved values] (operation-input catalogue held workspace-id entry-id 0 values saved))
        header (for [field ["supplier" "store" "counterparty" "from-warehouse" "to-warehouse" "reason" "direction" "amount"] :when (seq (v field))]
                 (if (= ["purchase" "supplier"] [(v "kind") field])
                   {"field" field "referenceName" (v field)}
                   {"field" field "value" (v field)}))
        cells  (for [line lines field ["item" "warehouse" "quantity" "price"] :when (seq (get line field))]
                 {"field" field "line-id" (get line "line-id") "side" (get line "side" "") "value" (get line field)})]
    (reduce step
            (assoc (step nil {"field" "create" "kind" (v "kind") "value" (v "occurred-at")}) :lines [])
            (concat header cells))))


(defaction add-operation
  "Board chapters 18–25 · Smart input Done: one new operation with its header fields and lines."
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]]
                [:values [:map-of [:or :string :keyword] [:string {:max 1000}]]]
                [:lines [:vector {:max 200} [:map-of [:or :string :keyword] [:string {:max 1000}]]]] [:commandId [:string {:min 1 :max 100}]]]}
  [context {:keys [workspaceId entryId values lines commandId]}]
  (let [catalogue (runtime/read-model context ::references/draft-catalogue {})
        held      (stocked (runtime/read-model context ::stock/stock-table {:hide-empty? true}))]
    (web/descriptor ::entries/save-shared-inventory-entry commandId
                    (addition-input catalogue held workspaceId entryId (update-keys values name) (mapv #(update-keys % name) lines)))))

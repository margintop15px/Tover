(ns tover.inventory.web
  "Board chapters 13, 14, 15, 16, 17 and 32a · The stock page: the stock table with Units / Money, shared opening and
  correction drafts saved on each cell commit, review of the selected drafts and acceptance, and an item's history below its row.
  The browser side is `stock-workbench` in `stock.js`; every action answers with a command descriptor."
  (:require
    [bel.core :refer [defaction defpage defstream defview]]
    [bel.expressions :refer [->expr]]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.references :as references]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.inventory.stock.model :as stock]
    [tover.inventory.valuation :as valuation]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui]
    [tover.workspaces.currency.model :as currency])
  (:import
    [clojure.lang ExceptionInfo]
    [java.time Duration Instant]))


(def ^:private access
  {:tover/access :workspace})


;; Stock table


(defn- amount
  {:malli/schema [:=> [:cat [:maybe decimal?] :string] :string]}
  [value currency]
  (if value (valuation/display-amount value currency) "—"))


;; Pending opening lines are drawn into the stock table: a typed quantity
;; saves a one-line opening for that item and warehouse; the unit cost and a new item's details apply to all its lines.


(defn- pending-lines
  "Every pending opening line with its entry's id, revision, time and reason, and how many lines the entry has."
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [entries]
  (for [{:keys [entry-id revision occurred-at reason lines]} entries
        line lines]
    (assoc line :entry-id entry-id :revision revision :occurred-at (some-> occurred-at str) :reason reason
           :line-count (count lines))))


(defn- line-values
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [line]
  (into {} (remove (comp nil? val))
        {"line-id"     (:line-id line)
         "quantity"    (some-> (:quantity line) str)                  "unit-cost" (some-> (:unit-cost line) str)
         "occurred-at" (:occurred-at line) "reason" (:reason line) "article" (:article line)
         "barcodes"    (some->> (:barcodes line) seq (str/join ", ")) "unit"      (:unit line)}))


(defn- decimal
  {:malli/schema [:=> [:cat :any] [:maybe decimal?]]}
  [value]
  (try (some-> value str str/trim not-empty bigdec) (catch NumberFormatException _ nil)))


(def ^:private item-fields
  [["article" "Article" 120] ["barcodes" "Barcodes" 160] ["unit" "Unit" 70]])


(def ^:private no-warehouse
  "The column of pending lines whose warehouse is missing or not a column yet."
  "no-warehouse")


(defn- blocked?
  "A line without a warehouse cannot be submitted."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [line]
  (not (or (:warehouse-id line) (:warehouse-name line))))


(defn- item-row
  "One stock item row: product, item, its details and unit cost, a cell per warehouse and Total (units or amount)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [mode currency warehouses preview?]}
   {:keys [id className product-cell label label-cell details details-editable? base locked? accepted lines actions item correction correction-id
           default-warehouse]}]
  (let [money?    (= "money" mode)
        archived? (:archived? item)
        ;; An existing row's counts and stock item changes go to its one Correction entry (chapter 15).
        counts    (into {} (map (juxt :warehouse-id identity)) (:lines correction))
        change    (first (:changes correction))
        ctarget   (fn [path field extra shared?]
                    {:type   "stock-cell" :id id :rowId id :path path :field field :entry correction-id :revision (:revision correction 0)
                     :shared (boolean shared?)
                     :values (merge {"kind" "correction" "stock-item-id" id "line-id" (str correction-id "/change")} extra)})
        count-q   (reduce + 0 (for [[w l] counts] (- (:quantity l) (get-in accepted [:cells w :quantity] 0))))
        money     #(amount % currency)
        column    (into {} (map (juxt :id :id)) warehouses)
        by-w      (group-by #(column (:warehouse-id %) no-warehouse) lines)
        entries   (vec (for [l lines] {:entry  (:entry-id l) :revision (:revision l)
                                       :values (merge base {"warehouse-id" (:warehouse-id l)} (line-values l))}))
        pend-q    (reduce + 0 (keep (comp decimal :quantity) lines))
        costs     (distinct (keep (comp decimal :unit-cost) lines))
        pend-v    (reduce + 0M (for [l lines :let [q (decimal (:quantity l)) c (decimal (:unit-cost l))] :when (and q c)] (* q c)))
        acc-q     (get-in accepted [:total :quantity] 0)
        acc-v     (get-in accepted [:total :value] 0M)
        val-q     (reduce + 0 (keep #(when (and (:valued? %) (:value %)) (:quantity %)) (vals (:cells accepted))))
        parts     (concat (for [c (vals (:cells accepted)) :when (pos? (:quantity c))] (boolean (and (:valued? c) (:value c))))
                          (for [l lines :when (some-> (:quantity l) decimal pos?)] (some? (:unit-cost l))))
        unvalued? (some false? parts)
        editor    (fn [path]
                    (if (= "unit" path)
                      {:type "select" :allowEmpty true :options (into [{:value "" :label ""}] (map #(hash-map :value % :label (i18n/unit-label %))) stock-items/units)}
                      {:type "text"}))
        pending   (fn [path]
                    (case path
                      "article" (:article change)
                      "unit" (:unit change)
                      "barcodes" (when (some #(contains? change %) [:barcodes-added :barcodes-removed])
                                   (str/join ", " (concat (remove (set (:barcodes-removed change)) (:barcodes item)) (:barcodes-added change))))))
        field     (fn [path value]
                    (cond-> {:value (or value "")}
                      item (merge (let [now (pending path)]
                                    (cond-> {:value (or now value "")}
                                      now (assoc :prefix (str (if (seq value) value "—") " → ") :className "stock-cell-pending")
                                      (and (= "unit" path) now) (assoc :display (or (i18n/unit-label now) now)))))
                      (and item (not archived?) (not (and (= "unit" path) (:unit-locked? item))))
                      (assoc :editor (editor path) :target (ctarget path path {} true))
                      (and item (= "unit" path) (:unit-locked? item))
                      (assoc :readOnly true :title (i18n/t "The unit is fixed once the item has stock history. Another unit needs another stock item and Production."))
                      (= "unit" path) (assoc :display (or (i18n/unit-label value) ""))
                      (= :blank details-editable?) (assoc :editor (editor path) :target {:type "stock-new-field" :id id :rowId id :path path})
                      (true? details-editable?) (assoc :editor (editor path)
                                                       :target {:type "stock-item-field" :id id :rowId id :path path :field path :entries entries})))
        cost      (cond-> {:value     (cond (= 1 (count costs)) (str (first costs))
                                            (and (empty? lines) (pos? val-q)) (money (with-precision 20 (/ acc-v val-q)))
                                            :else "")
                           :className (str "inventory-cell-number" (when (seq costs) " stock-cell-pending"))
                           :editor    {:type "decimal"}
                           :title     (if (seq lines) (i18n/t "Unit cost of the pending openings") (i18n/t "Enter a quantity first"))
                           :target    {:type "stock-item-field" :id id :rowId id :path "unit-cost" :field "unit-cost" :entries entries}}
                  ;; A new row keeps its cost until the first quantity saves it with the opening.
                    (= :blank details-editable?) (assoc :value (get details "unit-cost" "") :title (i18n/t "Unit cost")
                                                        :target {:type "stock-new-field" :id id :rowId id :path "unit-cost"})
                    (and (empty? lines) (not= :blank details-editable?)) (assoc :readOnly true))
        w-cell    (fn [{w :id gone? :archived?}]
                    (let [ls    (by-w w)
                          line  (first ls)
                          cell  (get-in accepted [:cells w])
                          shown (cond money? (if (and cell (pos? (:quantity cell))) (money (:value cell)) "") cell (str (:quantity cell)) :else "")
                          count (counts w)]
                      (if (and item (not money?) (:history? cell) (nil? line) (not gone?) (not archived?))
                        ;; Accepted history here: the cell takes a counted balance.
                        (cond-> {:value  shown :className "inventory-cell-number" :editor {:type "quantity"}
                                 :title  (i18n/t "Counted balance. Accepted now: {p0}" {:p0 shown})
                                 :target (ctarget w "quantity" {"warehouse-id" w "line-id" (or (:line-id count) (str correction-id "/" w))}
                                                  (or change (seq (dissoc counts w))))}
                          count (assoc :value (str (:quantity count)) :prefix (str shown " → ") :className "inventory-cell-number stock-cell-pending"))
                        (cond-> {:value shown :className (if (= w default-warehouse) "inventory-cell-number stock-default-warehouse" "inventory-cell-number")}
                          (and (not money?) (or (= no-warehouse w) (next ls))) (assoc :readOnly true)
                          (not (or money? (= no-warehouse w) (next ls)))
                          (assoc :editor {:type "quantity"}
                                 :target {:type   "stock-cell" :id           id :rowId id :path w :field "quantity"
                                          :entry  (:entry-id line) :revision (or (:revision line) 0) :shared (< 1 (:line-count line 1))
                                          :values (merge base {"warehouse-id" w} (line-values line))})
                          (and (not money?) (or locked? archived?)) (assoc :readOnly true)
                          (and (= w default-warehouse) (nil? line)) (assoc :title (i18n/t "This product's default warehouse"))
                          (and (not money?) (nil? line) gone?)
                          (assoc :readOnly true :title (i18n/t "This warehouse is archived. Restore it to add stock."))
                          line (assoc :value (if money?
                                               (if (every? (comp decimal :unit-cost) ls)
                                                 (money (reduce + 0M (for [l ls] (* (decimal (:unit-cost l)) (or (decimal (:quantity l)) 0M)))))
                                                 "—")
                                               (if (some :quantity ls) (str (reduce + 0 (keep (comp decimal :quantity) ls))) ""))
                                      :prefix "+" :className "inventory-cell-number stock-cell-pending"
                                      :title (cond (next ls) (i18n/t "Several pending openings. Only one can be accepted.")
                                                   (= no-warehouse w) (or (:warehouse-name line) (i18n/t "Warehouse is missing."))
                                                   :else (i18n/t "Pending opening. Accepted now: {p0}" {:p0 (if (seq shown) shown "0")})))))))]
    {:id    id :label label :className className
     :cells (-> (if preview?
                  ;; In preview each row with pending openings can be left for a later submit, or discarded.
                  [(if (or correction (some (complement blocked?) lines)) {:selection {:id id :label (i18n/t "Include in submit")}} {:value ""})]
                  [])
                (conj (or product-cell {:value ""}))
                (conj (or label-cell
                          (let [row-change (fn [label icon field value]
                                             (assoc (ctarget "item" field {} true) :type "stock-row-change" :label label :icon icon :value value))
                                marks      (cond-> []
                                             (:archive change) (conj (i18n/t "archive pending"))
                                             (:restore change) (conj (i18n/t "restore pending"))
                                             (:product-id change) (conj (i18n/t "move pending"))
                                             archived? (conj (i18n/t "(archived)")))
                                actions    (cond-> (vec actions)
                                             (and item archived?) (conj (if (:restore change)
                                                                          (row-change (i18n/t "Keep archived") "×" "restore" "")
                                                                          (row-change (i18n/t "Restore") "↺" "restore" "true")))
                                             (and item (not archived?)) (conj (row-change (i18n/t "Move to another product") "⇄" "move-to" nil)
                                                                              (if (:archive change)
                                                                                (row-change (i18n/t "Keep") "×" "archive" "")
                                                                                (row-change (i18n/t "Archive") "−" "archive" "true")))
                                             (and preview? (or (seq lines) correction))
                                             (conj {:type    "stock-discard-row" :id id :label (i18n/t "Discard pending changes")
                                                    :icon    "×"
                                                    :entries (concat
                                                               (for [[entry ls] (group-by :entry-id lines)
                                                                     l          (if (< (count ls) (:line-count (first ls))) ls (take 1 ls))]
                                                                 {:entry  entry :revision (:revision l) :line (:line-id l)
                                                                  :shared (< (count ls) (:line-count l)) :values (line-values l)})
                                                               (when correction
                                                                 [{:entry (:entry-id correction) :revision (:revision correction) :shared false :values {}}]))}))
                                variant    (:variant-label item)]
                            (cond-> {:value label}
                              (and item (not archived?)) (assoc :value (or variant "") :display label :editor {:type "text"}
                                                                :target (ctarget "item" "variant" {} true))
                              (contains? change :variant-label) (assoc :value (:variant-label change) :display (:variant-label change)
                                                                       :prefix (str (if (seq variant) variant "—") " → ") :className "stock-cell-pending")
                              (seq marks) (update :display #(str (or % label) " · " (str/join ", " marks)))
                              (seq actions) (assoc :actions actions)))))
                (into (map (fn [[path]] (field path (get details path)))) item-fields)
                (conj cost)
                (into (map w-cell) warehouses)
                (conj (cond-> {:value     (cond (not money?) (str (+ acc-q pend-q count-q))
                                                (and unvalued? (not-any? true? parts)) "—"
                                                :else (money (+ acc-v pend-v)))
                               :className (str "inventory-cell-number stock-total" (when (or (seq lines) (seq counts)) " stock-cell-pending"))}
                        (seq lines) (assoc :title (i18n/t "Includes pending openings"))
                        (and (not money?) (seq counts)) (assoc :title (i18n/t "Includes pending changes"))
                        (and money? unvalued? (some true? parts)) (assoc :partial true :prefix "≥ " :title (i18n/t "Includes unvalued stock")))))}))


(defn- finding-text
  "The member's message for one draft finding (chapter 16), naming related records where the rule gives them."
  {:malli/schema [:=> [:cat :map :map] :string]}
  [{:keys [code related]} {:keys [warehouse product]}]
  (case code
    :new-reference (i18n/t "Will be created on acceptance.")
    :similar-reference (i18n/t "Did you mean {p0}?" {:p0 (or (:name related) (i18n/t "the record proposed in another pending entry"))})
    :duplicate-variant (i18n/t "This variant already exists.")
    :insufficient-stock (i18n/t "Exceeds available stock.")
    :barcode-conflict (i18n/t "This barcode belongs to {p0}." {:p0 (or (:name related) (:owner-product related) (i18n/t "another product"))})
    :article-conflict (i18n/t "This article belongs to {p0}." {:p0 (or (:name related) (:owner-product related) (i18n/t "another product"))})
    :unit-locked (i18n/t "The unit is locked by stock history.")
    :archive-blocked (if-let [w (:warehouse-id related)]
                       (i18n/t "Cannot archive: stock in {p0}." {:p0 (warehouse w w)})
                       (i18n/t "Cannot archive: another pending entry names this item."))
    :restore-needs-product (i18n/t "Restore the product first.")
    :move-conflict (i18n/t "The move conflicts with {p0}."
                           {:p0 (or (:value related) (:variant-label related) (some-> (:product-id related) (#(product % %))) "")})
    :field-conflict (i18n/t "Another pending entry changes this field.")
    (i18n/t "Check this value.")))


(def ^:private finding-column
  "The column a finding's field shows in; `:warehouse` is the line's warehouse column."
  {:quantity      :warehouse  :warehouse-id :warehouse :warehouse-name :warehouse :product-name "product"  :product-id "item"
   :variant-label "item"      :status       "item"     :article        "article"  :barcodes     "barcodes" :unit       "unit"
   :unit-cost     "unit-cost"})


(def ^:private levels
  {:info 0 :warning 1 :error 2})


(defn- mark-findings
  "Every finding as a marker and message on its cell; the row's item cell carries the row's highest level."
  {:malli/schema [:=> [:cat [:vector :map] [:sequential :string] [:sequential :map] :map :map] [:vector :map]]}
  [rows column-keys findings row-of names]
  (let [known (set column-keys)
        place (fn [{:keys [entry-id line-id stock-item-id field]}]
                (let [{:keys [row warehouse]} (if line-id (row-of [entry-id line-id]) {:row stock-item-id})
                      column                  (finding-column field "item")]
                  (when row [row (if (= :warehouse column) (if (known warehouse) warehouse no-warehouse) column)])))
        found (group-by place findings)
        mark  (fn [cell fs extra]
                (let [level (name (apply max-key levels (map :level fs)))]
                  (-> cell
                      (update :className #(str/join " " (remove str/blank? [% "stock-finding" (str "stock-finding-" level) extra])))
                      (assoc :title (str/join "\n" (remove str/blank? (cons (:title cell) (distinct (map #(finding-text % names) fs)))))))))]
    (mapv (fn [{:keys [id cells] :as row}]
            (let [all (mapcat #(get found [id %]) column-keys)]
              (if (empty? all)
                row
                (let [top (name (apply max-key levels (map :level all)))]
                  (assoc row :cells (mapv (fn [k cell]
                                            (let [fs (get found [id k])]
                                              (cond (seq fs) (mark cell fs (when (= "item" k) (str "stock-row-" top)))
                                                    (= "item" k) (update cell :className #(str/trim (str % " stock-row-" top)))
                                                    :else cell)))
                                          column-keys cells))))))
          rows)))


(defn stock-grid
  "The stock table as one editable grid: a row per stock item under its product (merged), the item's details, a column
  per warehouse, Total, Unit cost and Amount. Pending openings and corrections show in their cells; new items get
  their own rows. Draft `findings` mark the cells they concern."
  {:malli/schema [:=> [:cat :map [:map [:mode [:enum "units" "money"]]]] :map]}
  [{:keys [warehouses products currency]} {:keys [mode scope entries findings]}]
  (let [{:keys [productId itemId productName newRow pending addTo]} scope
        lines (pending-lines (filter #(= "opening" (:kind % "opening")) entries))
        ;; ponytail: one Correction per row; a second member's correction of the same item only shows in Submit.
        corrections (filter #(= "correction" (:kind %)) entries)
        corrected (into {} (for [{:keys [lines changes] :as c} (reverse corrections)
                                 :let [item (or (:stock-item-id (first lines)) (:stock-item-id (first changes)))]
                                 :when item]
                             [item c]))
        known (set (map :id warehouses))
        warehouses (cond-> (vec warehouses)
                     (not-every? (comp known :warehouse-id) lines) (conj {:id no-warehouse :name (i18n/t "No warehouse")}))
        at-item (group-by :stock-item-id (filter :stock-item-id lines))
        key #(some-> % str/trim not-empty (str/replace #"\s+" " ") records/name-key)
        group-of #(or (:product-id %) (str "name:" (key (:product-name %))))
        new-lines (group-by group-of (remove :stock-item-id lines))
        ctx {:mode mode :currency currency :warehouses warehouses :preview? (boolean pending)}
        ;; `add` is a product id, or {:name …} for a product acceptance will register.
        product (fn [label add] (cond-> {:value label :className "stock-product-cell"}
                                  add (assoc :actions [(merge {:type "stock-add-item" :label (i18n/t "Add stock item") :icon "+"}
                                                              (if (map? add) {:id (str "name:" (:name add)) :name (:name add)} {:id add}))])))
        new-rows (fn [group base-of]
                   (for [[variant ls] (group-by #(or (some-> (:variant-label %) str/trim) "") (new-lines group))]
                     {:id      (str "new:" group ":" variant) :label             (if (seq variant) variant (i18n/t "New stock item"))
                      :details (line-values (first ls))       :details-editable? true :base (base-of variant) :lines ls}))
        blank-row (fn [group base taken]
                    (let [typed (when-not (contains? taken (key (:variant scope))) (not-empty (:variant scope)))
                          id    (str "new-row:" group)
                          ready (or typed (contains? base "product"))]
                      {:id                id :className             "stock-new-row"      :label (or typed "") :details (select-keys (update-keys scope name) ["article" "barcodes" "unit" "unit-cost"])
                       :details-editable? :blank :default-warehouse (:warehouseId scope)
                       :base              (merge base (select-keys (update-keys scope name) ["article" "barcodes" "unit" "unit-cost"]) {"variant" (or typed "")})
                       :locked?           (not ready)
                       :label-cell        {:value   (or typed "") :editor {:type "text"} :className (if typed "stock-variant-cell" "stock-variant-cell stock-variant-empty")
                                           :display (or typed (i18n/t "+ New stock item: type its variant"))
                                           :target  {:type "stock-new-field" :id id :rowId id :path "item"}}}))
        existing (for [{:keys [product-id name archived? items]} products
                       :when (or (nil? productId) (= productId product-id))
                       :let [items (cond->> items itemId (filter #(= itemId (:stock-item-id %))))
                             taken (into (set (map (comp key :variant-label) items)) (map (comp key :variant-label)) (new-lines product-id))
                             rows (concat (for [{:keys [stock-item-id variant-label article barcodes unit] :as item} items
                                                :let [correction (corrected stock-item-id)]]
                                            {:id            stock-item-id :label (or (not-empty variant-label) name)
                                             :details       {"article" article "barcodes" (str/join ", " barcodes) "unit" unit}
                                             :base          {"stock-item-id" stock-item-id} :accepted {:cells (:cells item) :total (:total item)}
                                             :lines         (at-item stock-item-id)
                                             :item          item :correction correction
                                             :correction-id (or (:entry-id correction) (str "correction-" (random-uuid)))
                                             :actions       [{:type "item-history" :id stock-item-id :label (i18n/t "History")}]})
                                          (when-not itemId (new-rows product-id #(hash-map "product-id" product-id "variant" %)))
                                          (when (and newRow (= (or productId addTo) product-id)) [(blank-row product-id {"product-id" product-id} taken)]))]
                       :when (or (seq rows) (not itemId))]
                   [(product (if archived? (str name " " (i18n/t "(archived)")) name) (when-not archived? product-id))
                    (if (seq rows) rows [{:id (str "product:" product-id) :label "" :base {} :locked? true}])])
        created (for [[group ls] new-lines
                      :when      (str/starts-with? group "name:")
                      :let       [label (:product-name (first ls))]
                      :when      (and (nil? productId) (nil? itemId))]
                  [(product label {:name label})
                   (concat (new-rows group #(hash-map "product" label "variant" %))
                           (when (and productName (= (key productName) (key label))) [(blank-row group {"product" label} #{})]))])
        fresh (when (and productName (not (some #(= (str "name:" (key productName)) %) (keys new-lines))))
                [[(product productName nil) [(blank-row "new" {"product" productName} #{})]]])
        ;; The product being created comes first; it does not filter the table.
        named? (fn [[cell]] (and productName (= (key productName) (key (:value cell)))))
        groups (cond->> (let [all (concat created fresh existing)] (concat (filter named? all) (remove named? all)))
                 pending (keep (fn [[cell rows]] (when-let [rows (seq (filter #(or (seq (:lines %)) (:correction %) (= "stock-new-row" (:className %))) rows))]
                                                   [cell rows]))))
        built (for [[cell rows] groups]
                (map-indexed #(item-row ctx (cond-> %2 (zero? %1) (assoc :product-cell cell))) rows))
        columns (-> (if pending [{:key "select" :label "" :width 40}] [])
                    (into [{:key "product" :label (i18n/t "Product") :width 200} {:key "item" :label (i18n/t "Stock item") :width 160}])
                    (into (map (fn [[k label width]] {:key k :label (i18n/t label) :width width})) item-fields)
                    (conj {:key "unit-cost" :label (i18n/t "Unit cost") :width 110})
                    (into (map #(hash-map :key (:id %) :label (:name %) :width 100)) warehouses)
                    (conj {:key "total" :label (i18n/t "Total") :width 110}))
        row-of (into {} (for [{:keys [entry-id kind] :as entry} entries
                              {:keys [line-id stock-item-id variant-label warehouse-id] :as line} (:lines entry)]
                          [[entry-id line-id]
                           {:row       (if (or stock-item-id (= "correction" kind))
                                         stock-item-id
                                         (str "new:" (group-of line) ":" (or (some-> variant-label str/trim) "")))
                            :warehouse warehouse-id}]))
        names {:warehouse (into {} (map (juxt :id :name)) warehouses) :product (into {} (map (juxt :product-id :name)) products)}
        rows (mark-findings (vec (map-indexed #(assoc %2 :rowIndex %1 :groupIndex %1 :groupId (:id %2)) (apply concat built)))
                            (mapv :key columns) (or findings []) row-of names)
        merges (second (reduce (fn [[start acc] group] [(+ start (count group)) (cond-> acc (< 1 (count group)) (conj {:row start :col (if pending 1 0) :rowspan (count group)}))])
                               [0 []] built))
        ;; Every pending opening with its row, shown or filtered out.
        all-pending (concat
                      (distinct (for [{:keys [entry-id revision stock-item-id variant-label] :as line} lines]
                                  (cond-> {:entry-id entry-id :revision revision
                                           :rowId    (or stock-item-id (str "new:" (group-of line) ":" (some-> variant-label str/trim)))}
                                    (blocked? line) (assoc :blocked true))))
                      (for [{:keys [entry-id revision lines changes]} corrections]
                        {:entry-id entry-id :revision revision :rowId (or (:stock-item-id (first lines)) (:stock-item-id (first changes)))}))]
    {:id      "stock"           :editable true :rowHeaders false :merges merges :editStates [] :showPrevious false
     :pending (vec all-pending)
     :columns columns
     :rows    rows}))


(def ^:private problem-text
  {:occurred-at-missing "Occurrence time is missing."              :quantity-missing  "Quantity is missing."
   :warehouse-missing   "Warehouse is missing."                    :item-missing      "Stock item is missing."
   :archived-reference  "Restore the archived record first."       :new-reference     "Will be created on acceptance."
   :similar-reference   "A record with a similar name exists."     :duplicate-variant "This product already has this variant."
   :insufficient-stock  "Not enough stock." :barcode-conflict "This barcode belongs to another product."
   :article-conflict    "This article belongs to another product."})


(defn- same-name?
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :boolean]}
  [a b]
  (let [k #(some-> % str/trim not-empty (str/replace #"\s+" " ") records/name-key)]
    (boolean (and (k a) (= (k a) (k b))))))


(defn entry-input
  "The `Save Shared Inventory Entry` input for one draft row. Typed names resolve to an active record of exactly that
  name; anything else is the intent to register it on acceptance. The entry's other `saved` lines are kept."
  {:malli/schema [:function [:=> [:cat :map :uuid :string :int :map] :map]
                  [:=> [:cat :map :uuid :string :int :map [:maybe [:sequential :map]]] :map]]}
  ([catalogue workspace-id entry-id revision values] (entry-input catalogue workspace-id entry-id revision values nil))
  ([{:keys [products stock-items warehouses]} workspace-id entry-id revision values saved]
   (let [v         #(some-> (get values %) str/trim not-empty)
         active    #(filter (comp #{"active"} :status) %)
         by-id     (fn [k rows] (some #(when (= (v k) (str (get % (keyword k)))) %) rows))
         product   (or (by-id "product-id" products) (some #(when (same-name? (v "product") (:name %)) %) (active products)))
         item      (or (by-id "stock-item-id" stock-items)
                       (when product
                         (some #(when (and (= (:product-id product) (:product-id %))
                                           (= (some-> (v "variant") records/name-key) (some-> (:variant-label %) str/trim not-empty records/name-key)))
                                  %)
                               (active stock-items))))
         warehouse (or (by-id "warehouse-id" warehouses) (some #(when (same-name? (v "warehouse") (:name %)) %) (active warehouses)))
         codes     (some->> (v "barcodes") (re-seq #"[^,\s]+") (remove #{"0"}) vec not-empty)
         line      (cond-> {:line-id (or (v "line-id") (str entry-id "-0"))}
                     item (assoc :stock-item-id (:stock-item-id item))
                     (and (not item) product) (assoc :product-id (:product-id product))
                     (and (not item) (not product) (v "product")) (assoc :product-name (v "product"))
                     (and (not item) (v "variant")) (assoc :variant-label (v "variant"))
                     (and (not item) (v "article")) (assoc :article (v "article"))
                     (and (not item) codes) (assoc :barcodes codes)
                     (and (not item) (v "unit")) (assoc :unit (v "unit"))
                     warehouse (assoc :warehouse-id (:warehouse-id warehouse))
                     (and (not warehouse) (v "warehouse")) (assoc :warehouse-name (v "warehouse"))
                     (v "quantity") (assoc :quantity (v "quantity"))
                     (v "unit-cost") (assoc :unit-cost (v "unit-cost")))
         same?     (comp #{(:line-id line)} :line-id)
         lines     (cond (v "remove") (vec (remove same? saved))
                         (some same? saved) (mapv #(if (same? %) line %) saved)
                         :else (conj (vec saved) line))]
     (cond-> {:workspace-id workspace-id :entry-id entry-id :kind "opening" :lines lines}
       (pos? revision) (assoc :expected-revision revision)
       (v "occurred-at") (assoc :occurred-at (v "occurred-at"))
       (v "reason") (assoc :reason (v "reason"))))))


;; One Correction entry per stock row: its counts and its stock item's changes (chapter 15).


(def ^:private change-keys
  [["variant" :variant-label] ["article" :article] ["unit" :unit]])


(defn correction-input
  "The `Save Shared Inventory Entry` input of a row's Correction. `values` carry one edit over the `saved` entry: a
  count for a warehouse (blank removes it), or stock item fields, barcodes, `move-to` (a product name), `archive` or
  `restore`. A value equal to the accepted one drops that change; nil when nothing is left to save."
  {:malli/schema [:=> [:cat :map :uuid :string :int :map [:maybe :map]] [:maybe :map]]}
  [{:keys [products stock-items barcodes]} workspace-id entry-id revision values saved]
  (let [v        #(some-> (get values %) str/trim)
        item-id  (v "stock-item-id")
        item     (some #(when (= item-id (:stock-item-id %)) %) stock-items)
        lines    (vec (:lines saved))
        lines    (if-let [w (not-empty (v "warehouse-id"))]
                   (let [line-id (or (not-empty (v "line-id")) (str entry-id "/" w))
                         same?   (comp #{line-id} :line-id)
                         line    {:line-id line-id :stock-item-id item-id :warehouse-id w :quantity (v "quantity")}]
                     (cond (or (v "remove") (str/blank? (v "quantity"))) (vec (remove same? lines))
                           (some same? lines) (mapv #(if (same? %) line %) lines)
                           :else (conj lines line)))
                   lines)
        accepted (set (keep #(when (= item-id (:stock-item-id %)) (:barcode %)) barcodes))
        typed    (when (contains? values "barcodes") (set (some->> (v "barcodes") (re-seq #"[^,\s]+") (remove #{"0"}))))
        product  (when-let [name (not-empty (v "move-to"))]
                   (some #(when (and (= "active" (:status %)) (same-name? name (:name %))) %) products))
        put      (fn [change k value unchanged?] (if unchanged? (dissoc change k) (assoc change k value)))
        change   (as-> (or (some #(when (= item-id (:stock-item-id %)) %) (:changes saved)) {:stock-item-id item-id}) change
                   (reduce (fn [change [key field]]
                             (cond-> change
                               (contains? values key) (put field (v key) (= (v key) (or (get item field) "")))))
                           change change-keys)
                   (cond-> change
                     typed (-> (put :barcodes-added (vec (sort (remove accepted typed))) (empty? (remove accepted typed)))
                               (put :barcodes-removed (vec (sort (remove typed accepted))) (empty? (remove typed accepted))))
                     (contains? values "move-to") (put :product-id (or (:product-id product) (v "move-to"))
                                                       (or (str/blank? (v "move-to")) (= (:product-id product) (:product-id item))))
                     (contains? values "archive") (put :archive true (str/blank? (v "archive")))
                     (contains? values "restore") (put :restore true (str/blank? (v "restore")))))
        changes  (into (vec (remove #(= item-id (:stock-item-id %)) (:changes saved))) (when (next change) [change]))]
    (when (or (seq lines) (seq changes))
      (cond-> {:workspace-id workspace-id :entry-id entry-id :kind "correction" :lines lines :changes changes}
        (pos? revision) (assoc :expected-revision revision)
        (not-empty (v "occurred-at")) (assoc :occurred-at (v "occurred-at"))
        (:reason saved) (assoc :reason (:reason saved))))))


;; Reads


(defn- read-or-nil
  "A read model's answer, or nil when it cannot be read: unavailable stock is never shown as empty."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [context id input]
  (try (runtime/read-model context id input)
       (catch ExceptionInfo error
         (if (or (:status (ex-data error)) (= :forbidden (:type (ex-data error))))
           (throw error)
           (do (mu/log ::read-failed :read-model id :error-type (:type (ex-data error))) nil)))))


(defn- json
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (charred/write-json-str value))


(defn- table-model
  "The stock grid, or nil when stock or pending work cannot be read."
  {:malli/schema [:function [:=> [:cat :map :string :boolean [:maybe :map]] [:maybe :map]]
                  [:=> [:cat :map :string :boolean [:maybe :map] :boolean] [:maybe :map]]]}
  ([context mode hide-empty? scope] (table-model context mode hide-empty? scope false))
  ([context mode hide-empty? scope show-archived?]
   (let [table (read-or-nil context ::stock/stock-table (cond-> {:hide-empty? hide-empty?} show-archived? (assoc :show-archived? true)))
         work  (read-or-nil context ::entries/pending-entries {})]
     (when (and table work)
       (stock-grid table {:mode mode :scope scope :entries (:entries work) :findings (:findings work)})))))


(defview table
  {:route-data access
   :schema     [:map [:requestId :string] [:mode [:enum "units" "money"]] [:hideEmpty :boolean] [:showArchived {:optional true} :boolean]
                [:filter {:optional true} [:maybe :map]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {scope :filter :keys [requestId mode hideEmpty showArchived]} :input}]
  {:type :signals
   :data {:_stockTableJson (json {:requestId requestId
                                  :model     (table-model context mode (and hideEmpty (not (:pending scope)) (empty? (select-keys scope [:productId :itemId])))
                                                          scope (boolean showArchived))})}})


(defn suggestions
  "The picker's answer: stock items and products from `references/suggest`, or `:failed` when the catalogue could not
  be read, so a lookup failure is never shown as no match."
  {:malli/schema [:=> [:cat [:maybe :map] :string] :map]}
  [catalogue text]
  (if-not catalogue
    {:text text :failed true :options []}
    {:text    text
     :exact   (boolean (some #(and (= "active" (:status %)) (same-name? text (:name %))) (:products catalogue)))
     :options (vec (for [{:keys [kind id label fill]} (take 12 (when-not (str/blank? text) (references/suggest text catalogue)))]
                     (if (= "product" kind)
                       (cond-> {:kind "product" :id id :label label}
                         (:warehouse-id fill) (assoc :warehouseId (:warehouse-id fill)))
                       ;; A barcode match is shown, so the member sees why the item was found.
                       (let [matched (filter #(str/includes? % (str/trim text)) (:barcodes fill))]
                         (cond-> {:kind "item" :id id :productId (:product-id fill) :label label}
                           (seq matched) (assoc :hint (str/join ", " matched)))))))}))


(defview suggest
  {:route-data access :schema [:map [:requestId :string] [:text [:string {:max 200}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId text]} :input}]
  {:type :signals
   :data {:_stockSuggestJson (json (assoc (suggestions (read-or-nil context ::references/draft-catalogue {}) text) :requestId requestId))}})


(defn- labels
  "Display names for the ids a review mentions: catalogue records and the registrations it proposes."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :map]}
  [{:keys [products stock-items warehouses]} registrations]
  (let [names (into {} (map (juxt :product-id :name)) products)]
    (merge (into {} (map (juxt :warehouse-id :name)) warehouses)
           (into {} (map (fn [{:keys [stock-item-id product-id variant-label]}]
                           [stock-item-id (str (names product-id) (when (not-empty variant-label) (str " / " variant-label)))]))
                 stock-items)
           (into {} (map (juxt :id :label)) registrations))))


(defview review
  {:route-data access
   :schema     [:map [:requestId :string] [:selection [:vector [:map [:entry-id :string] [:revision :int]]]] [:order [:vector :string]]
                [:ordered {:optional true} :boolean]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId selection order ordered]} :input}]
  (let [result    (read-or-nil context ::acceptance/inventory-review {:selection selection :order order :ordered? (boolean ordered)})
        catalogue (read-or-nil context ::references/draft-catalogue {})
        label     (when (and result catalogue) (labels catalogue (:registrations result)))
        currency  (or (:currency (read-or-nil context ::currency/workspace-currency {})) "EUR")]
    {:type :signals
     :data {:_stockReviewJson
            (json {:requestId requestId :selection selection :order order :ordered (boolean ordered)
                   :review    (when label
                                (-> result
                                    (update :effects (partial mapv #(assoc % :item (label (:stock-item-id %)) :warehouse (label (:warehouse-id %))
                                                                           :valueBefore (some-> (:value-before %) (amount currency))
                                                                           :valueAfter (some-> (:value-after %) (amount currency)))))
                                    (update :registrations (partial mapv #(update % :type (fn [t] (i18n/t (str/capitalize (str/replace (name t) "-" " ")))))))
                                    (update :problems (partial mapv #(assoc % :message
                                                                            (if (= :order-required (:code %))
                                                                              (i18n/t "{p0} in {p1}: changes at the same second need an order."
                                                                                      {:p0 (label (:stock-item-id %)) :p1 (label (:warehouse-id %))})
                                                                              (i18n/t (or (problem-text (:code %)) (second (acceptance/rejection (:code %))) "Check this value."))))))))})}}))


(defview history
  {:route-data access :schema [:map [:requestId :string] [:stockItemId :string] [:days [:maybe :int]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId stockItemId days]} :input}]
  (let [period    (cond-> {:stock-item-id stockItemId} days (assoc :from (.minus (Instant/now) (Duration/ofDays days))))
        rows      (read-or-nil context ::stock/item-history period)
        lineage   (read-or-nil context ::stock/item-lineage period)
        catalogue (read-or-nil context ::references/draft-catalogue {})
        name      (into {} (map (juxt :warehouse-id :name)) (:warehouses catalogue))]
    {:type :signals
     :data {:_stockHistoryJson (json {:requestId requestId :stockItemId stockItemId
                                      :status    (if (and rows lineage) "ok" "failed")
                                      :rows      (mapv #(-> % (update :occurred-at str) (update :recorded-at str)
                                                            (assoc :warehouse (name (:warehouse-id %) (:warehouse-id %))
                                                                   :kind-label (i18n/t (str/capitalize (:kind %)))))
                                                       (:rows rows))
                                      :lineage   (:status lineage)})}}))


(defview saved
  {:route-data access :schema [:map [:commandId [:string {:min 1 :max 100}]]] :signal :_stockSaveJson}
  [{:keys [commandId]}]
  {:commandId commandId :status "ok"})


(defview failed
  {:route-data access :schema [:map [:commandId :string] [:error :map]] :signal :_stockSaveJson}
  [{:keys [commandId error]}]
  {:commandId commandId :status "failed" :type (some-> (:type error) name) :message (i18n/error-message error)})


;; Actions


(defn- descriptor
  {:malli/schema [:=> [:cat :qualified-keyword :string :map] :map]}
  [command command-id input]
  {:type  :command :id command :command-id command-id :input input
   :then  {:type :view :id ::saved :input {:commandId command-id}}
   :catch {:type :view :id ::failed :input {:commandId command-id}}})


(def ^:private CommandId
  [:string {:min 1 :max 100}])


(defaction save-entry
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]] [:revision [:int {:min 0}]]
                [:values [:map-of [:or :string :keyword] [:string {:max 1000}]]] [:commandId CommandId]]}
  [context {:keys [workspaceId entryId revision values commandId]}]
  ;; Reads the catalogue to resolve typed names and the saved entry to keep its other lines; the command rechecks everything.
  (let [values    (update-keys values name)
        catalogue (runtime/read-model context ::references/draft-catalogue {})
        saved     (when (pos? revision)
                    (some #(when (= entryId (:entry-id %)) %) (:entries (runtime/read-model context ::entries/pending-entries {}))))]
    (if (= "correction" (get values "kind"))
      (if-let [input (correction-input catalogue workspaceId entryId revision values saved)]
        (descriptor ::entries/save-shared-inventory-entry commandId input)
        ;; Nothing left: a saved correction is discarded; an unsaved one needs nothing.
        (if (pos? revision)
          (descriptor ::entries/discard-inventory-draft commandId {:workspace-id workspaceId :entry-id entryId :revision revision})
          {:type :view :id ::saved :input {:commandId commandId}}))
      (descriptor ::entries/save-shared-inventory-entry commandId
                  (entry-input catalogue workspaceId entryId revision values (:lines saved))))))


(defaction describe-entry
  "Set the occurrence time and reason of a saved opening or correction; an unset one keeps the entry's own."
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]] [:revision [:int {:min 1}]]
                [:occurredAt [:string {:max 100}]] [:reason [:string {:max 1000}]] [:commandId CommandId]]}
  [context {:keys [workspaceId entryId revision occurredAt reason commandId]}]
  ;; Reads the entry to keep its lines; the command rejects it when the revision has moved on.
  (let [entry (some #(when (= entryId (:entry-id %)) %) (:entries (runtime/read-model context ::entries/pending-entries {})))
        at    (or (not-empty (str/trim occurredAt)) (some-> (:occurred-at entry) str))
        why   (or (not-empty (str/trim reason)) (:reason entry))]
    (descriptor ::entries/save-shared-inventory-entry commandId
                (cond-> {:workspace-id workspaceId :entry-id entryId :kind (:kind entry "opening") :expected-revision revision :lines (vec (:lines entry))}
                  (seq (:changes entry)) (assoc :changes (:changes entry))
                  at (assoc :occurred-at at)
                  why (assoc :reason why)))))


(defaction discard-entry
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]] [:revision [:int {:min 1}]] [:commandId CommandId]]}
  [_ {:keys [workspaceId entryId revision commandId]}]
  (descriptor ::entries/discard-inventory-draft commandId {:workspace-id workspaceId :entry-id entryId :revision revision}))


(defaction accept
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:selection [:vector [:map [:entry-id :string] [:revision :int]]]] [:order [:vector :string]]
                [:fingerprint :string] [:commandId CommandId] [:ordered {:optional true} :boolean]]}
  [_ {:keys [workspaceId selection order fingerprint commandId ordered]}]
  ;; The attempt id is the acceptance id, so a retried attempt recovers the same acceptance.
  (descriptor ::acceptance/accept-reviewed-inventory-changes commandId
              (cond-> {:workspace-id workspaceId :acceptance-id commandId :selection selection :order order :fingerprint fingerprint}
                ordered (assoc :ordered? true))))


(defstream changed
  {:resnapshot-on-open? true}
  [_context _event]
  {:type :signals :data {:inventoryChanged (str (random-uuid))}})


;; Page


(defn query-page
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} _]
  {:workspace-id (get-in request [:tover/identity :workspace-id])
   :model        (table-model context "units" true nil)})


(defn workbench
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [{:keys [workspace-id model]}]
  (let [options {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}]
    [:stock-workbench.stock-workbench.catalogue-workbench
     {:data-workspace-id       (str workspace-id)
      :data-model              (json model)
      :data-on:stock-query     (->expr (.catch (@view ::table evt.detail ~options) (fn [_] (.requestFailed el "table"))))
      :data-on:stock-suggest   (->expr (.catch (@view ::suggest evt.detail ~options) (fn [_] (.requestFailed el "suggest"))))
      :data-on:stock-review    (->expr (.catch (@view ::review evt.detail ~options) (fn [_] (.requestFailed el "review"))))
      :data-on:stock-history   (->expr (.catch (@view ::history evt.detail ~options) (fn [_] (.requestFailed el "history"))))
      :data-on:stock-save      (->expr (.catch (@action ::save-entry evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-on:stock-describe  (->expr (.catch (@action ::describe-entry evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-on:stock-discard   (->expr (.catch (@action ::discard-entry evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-on:stock-accept    (->expr (.catch (@action ::accept evt.detail ~options) (fn [_] (.requestFailed el "save"))))
      :data-signals__ifmissing (json {:_stockTableJson   "null" :_stockSuggestJson "null" :_stockReviewJson "null"
                                      :_stockHistoryJson "null" :_stockSaveJson    "null" :inventoryChanged ""})
      :data-effect             "el.tableResponse=JSON.parse($_stockTableJson); el.suggestResponse=JSON.parse($_stockSuggestJson); el.reviewResponse=JSON.parse($_stockReviewJson); el.historyResponse=JSON.parse($_stockHistoryJson); el.saveResponse=JSON.parse($_stockSaveJson); el.changed=$inventoryChanged"}
     [:div.smart-entry.stock-search
      [:div.smart-line
       [:input.catalogue-search.stock-search-input {:type          "search" :autocomplete          "off" :role "combobox" :aria-expanded "false"
                                                    :aria-controls "stock-suggestions" :aria-label (i18n/t "Find or add a product")
                                                    :placeholder   (i18n/t "Find or add a product, stock item, article or barcode")}]
       [:button.stock-filter-chip {:type "button" :hidden true :aria-label (i18n/t "Clear filter")}]]
      [:ul#stock-suggestions.stock-suggestions {:role "listbox" :hidden true}]]
     [:div.sheet-toolbar
      [:div.sheet-change-status [:span.stock-save-status.quiet {:role "status"}]]
      [:div.sheet-actions
       [:button.sheet-icon-button.stock-show-archived {:type "button" :aria-pressed "false" :title (i18n/t "Show archived items") :aria-label (i18n/t "Show archived items")} (ui/sheet-icon :archived)]
       [:button.sheet-icon-button.stock-hide-empty {:type "button" :aria-pressed "true" :title (i18n/t "Hide products without stock items") :aria-label (i18n/t "Hide products without stock items")} (ui/sheet-icon :hide-empty)]
       [:span.stock-mode {:role "group" :aria-label (i18n/t "Show")}
        [:button.sheet-icon-button.stock-mode-units {:type "button" :data-mode "units" :aria-pressed "true" :title (i18n/t "Units") :aria-label (i18n/t "Units")} (ui/sheet-icon :units)]
        [:button.sheet-icon-button.stock-mode-money {:type "button" :data-mode "money" :aria-pressed "false" :title (i18n/t "Money") :aria-label (i18n/t "Money")} (ui/sheet-icon :money)]]
       [:button.sheet-icon-button.stock-pending-filter {:type "button" :aria-pressed "false" :title (i18n/t "Preview pending changes") :aria-label (i18n/t "Preview pending changes")} (ui/sheet-icon :preview)]
       [:span.stock-submit-details {:hidden true}
        [:input.stock-submit-time {:type       "datetime-local" :step 1 :title (i18n/t "Occurrence time. Leave empty to keep each opening's own.")
                                   :aria-label (i18n/t "Occurrence time. Leave empty to keep each opening's own.")}]
        [:input.stock-submit-reason {:type       "text" :maxlength 1000 :placeholder (i18n/t "Reason") :title (i18n/t "Reason. Leave empty to keep each opening's own.")
                                     :aria-label (i18n/t "Reason. Leave empty to keep each opening's own.")}]]
       [:button.sheet-icon-button.stock-submit {:type "button" :disabled true :title (i18n/t "Submit pending changes") :aria-label (i18n/t "Submit pending changes")} (ui/sheet-icon :submit)]]]
     [:div.catalogue-notice.stock-notice {:role "status"}]
     [:inventory-grid#stock-grid.stock-grid {:data-ignore-morph "" :aria-label (i18n/t "Stock")}]]))


(defpage page
  {:route "/" :title "Stock · Tover" :parent :tover.shared.web.core/shell :route-data access :query query-page}
  [{:keys [model] :as props}]
  {:type :elements
   :html (if model
           (workbench props)
           [:p.management-empty {:role "alert"} (i18n/t "Stock could not be loaded.") " " [:a {:href "/"} (i18n/t "Retry")]])})

(ns tover.inventory.findings
  "Board chapter 16 · Read draft findings. Derived on read over every shared pending entry of the workspace at once,
  the catalogue and accepted balances; never stored and never acceptance evidence. Each rule is a code, a level and a
  check; adding one changes no other rule."
  (:refer-clojure :exclude [derive])
  (:require
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.changes :as changes])
  (:import
    [java.util.concurrent TimeUnit TimeoutException]))


(def Finding
  "A line finding names its line; a stock item change finding has no line and names the stock item."
  [:map [:entry-id :string] [:line-id [:maybe :string]] [:stock-item-id {:optional true} :string] [:field :keyword]
   [:code :keyword] [:level [:enum :info :warning :error]] [:related {:optional true} :map]])


(def ^:private direction
  "Incoming or outgoing per kind; a Correction's side depends on its count. ponytail: Transfer and Production sides
  arrive with chapters 21–22."
  {"opening" :in  "purchase" :in  "customer-return" :in
   "sale"    :out "defect"   :out "write-off"       :out "supplier-return" :out})


(defn- text-key
  "Comparison key: letter case and spacing ignored."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [text]
  (when-not (str/blank? text)
    (records/name-key (str/replace (str/trim text) #"\s+" " "))))


(defn- lines
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [entries]
  (for [{:keys [entry-id kind] :as entry} entries
        line (:lines entry)]
    (assoc line :entry-id entry-id :kind kind)))


(defn- new-item?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [line]
  (boolean (and (not (:stock-item-id line)) (or (:product-id line) (text-key (:product-name line))))))


(defn- product-key
  "The product a new item belongs to: an existing id, or a proposed name."
  {:malli/schema [:=> [:cat :map] [:maybe :any]]}
  [line]
  (or (some->> (:product-id line) (vector :id)) (some->> (text-key (:product-name line)) (vector :name))))


(defn- finding
  {:malli/schema [:=> [:cat :map :keyword :keyword :keyword [:maybe :map]] Finding]}
  [line field code level related]
  (cond-> {:entry-id (:entry-id line) :line-id (:line-id line) :field field :code code :level level}
    related (assoc :related related)))


(defn- proposals
  "Proposed new names per field: product names, warehouse names."
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [all]
  (concat
    (for [line all :when (and (not (:stock-item-id line)) (not (:product-id line)) (text-key (:product-name line)))]
      {:line line :field :product-name :key (text-key (:product-name line))})
    (for [line all :when (and (not (:warehouse-id line)) (text-key (:warehouse-name line)))]
      {:line line :field :warehouse-name :key (text-key (:warehouse-name line))})))


(defn- references
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:sequential Finding]]}
  [{:keys [products warehouses]} all]
  (let [existing {:product-name   (map #(hash-map :id (:product-id %) :name (:name %)) products)
                  :warehouse-name (map #(hash-map :id (:warehouse-id %) :name (:name %)) warehouses)}
        proposed (proposals all)]
    (concat
      (for [{:keys [line field]} proposed] (finding line field :new-reference :info nil))
      (for [line all :when (new-item? line)] (finding line :variant-label :new-reference :info nil))
      (for [{:keys [line field key]} proposed
            :let [record (first (filter #(= key (text-key (:name %))) (get existing field)))
                  other (first (filter #(and (= field (:field %)) (= key (:key %)) (not= line (:line %))) proposed))]
            :when                    (or record other)]
        (finding line field :similar-reference :warning
                 (or record (select-keys (:line other) [:entry-id :line-id])))))))


(defn- duplicate-variants
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:sequential Finding]]}
  [{:keys [stock-items]} all]
  (let [proposed (for [line all :when (and (new-item? line) (text-key (:variant-label line)))]
                   [line [(product-key line) (text-key (:variant-label line))]])
        existing (set (map #(vector [:id (:product-id %)] (text-key (:variant-label %))) stock-items))]
    (for [[[line key] index] (map vector proposed (range))
          :when              (or (existing key) (some #(= key (second %)) (take index proposed)))]
      (finding line :variant-label :duplicate-variant :error nil))))


(defn- stock
  "Per stock item and warehouse: error when one entry's outgoing exceeds accepted plus all pending incoming;
  otherwise a warning on every contributor when the pending outgoing total does. Dates are ignored."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:sequential Finding]]}
  [{:keys [balances]} all]
  (let [side   (fn [{:keys [kind quantity stock-item-id warehouse-id] :as line}]
                 (if (= "correction" kind)
                   (let [change (- quantity (get-in balances [[stock-item-id warehouse-id] :quantity] 0))]
                     (assoc line :side (if (neg? change) :out :in) :quantity (abs change)))
                   (assoc line :side (direction kind))))
        moving (filter #(and (:side %) (pos-int? (:quantity %)))
                       (map side (filter #(and (:stock-item-id %) (:warehouse-id %) (int? (:quantity %))) all)))]
    (mapcat (fn [[pair pair-lines]]
              (let [{in :in out :out} (group-by :side pair-lines)
                    available         (+ (get-in balances [pair :quantity] 0) (reduce + 0 (map :quantity in)))
                    by-entry          (group-by :entry-id out)
                    over              (set (keep (fn [[id ls]] (when (> (reduce + (map :quantity ls)) available) id)) by-entry))]
                (cond
                  (seq over) (for [line out :when (over (:entry-id line))] (finding line :quantity :insufficient-stock :error nil))
                  (> (reduce + 0 (map :quantity out)) available) (for [line out] (finding line :quantity :insufficient-stock :warning nil))
                  :else [])))
            (group-by (juxt :stock-item-id :warehouse-id) moving))))


(defn- ownership
  "A barcode or article belongs to one product: an owner of another product, or a pending claim for another product,
  conflicts. Sharing among items of one product is fine."
  {:malli/schema [:=> [:cat :map [:sequential :map] :keyword :keyword [:sequential [:tuple :string :string]]] [:sequential Finding]]}
  [{:keys [products]} all field code owned]
  (let [names   (into {} (map (juxt :product-id :name)) products)
        owners  (reduce (fn [m [value product-id]] (update m value (fnil conj #{}) [:id product-id])) {} owned)
        claims  (for [line  all :when (new-item? line)
                      value (if (= field :barcodes) (:barcodes line) (some-> (text-key (:article line)) vector))]
                  [line value])
        claimed (reduce (fn [m [line value]] (update m value (fnil conj #{}) (product-key line))) {} claims)]
    (distinct
      (for [[line value] claims
            :let         [mine (product-key line)
                          conflict (first (remove #{mine} (concat (get owners value) (get claimed value))))]
            :when        conflict]
        (finding line field code :error (when (= :id (first conflict)) {:id (second conflict) :name (get names (second conflict))}))))))


(defn- item-changes
  "Each entry's stock item changes checked as acceptance would, after that entry's own counts; field conflicts across
  all pending entries."
  {:malli/schema [:=> [:cat :map] [:sequential Finding]]}
  [{:keys [entries balances] :as facts}]
  (let [facts  (assoc facts :history {:lines (map (fn [[item warehouse]] {:stock-item-id item :warehouse-id warehouse}) (keys balances))})
        tagged (fn [{:keys [entry-id] :as entry}] (map #(assoc % :entry-id entry-id) (:changes entry)))
        found  (concat
                 (for [{:keys [lines] :as entry} entries
                       :when (seq (:changes entry))
                       :let [counted (into {} (for [l lines :when (int? (:quantity l))]
                                                [[(:stock-item-id l) (:warehouse-id l)] {:quantity (:quantity l)}]))]
                       problem                   (changes/problems facts [] (tagged entry) {:balances (merge balances counted)})
                       :when (not= :field-conflict (:code problem))]
                   problem)
                 (filter (comp #{:field-conflict} :code) (changes/problems facts [] (mapcat tagged entries) {:balances balances})))]
    (for [{:keys [code field] :as p} found]
      (cond-> {:entry-id (:entry-id p) :line-id nil :stock-item-id (:stock-item-id p) :field (or field :status)
               :code     (if (= :uniqueness-conflict code) (if (= :barcodes field) :barcode-conflict :article-conflict) code)
               :level    (if (= :field-conflict code) :warning :error)}
        (seq (dissoc p :entry-id :line-id :stock-item-id :field :code)) (assoc :related (dissoc p :entry-id :line-id :stock-item-id :field :code))))))


(defn derive
  "Findings for every line of every pending entry. `facts`: `:entries` (with `:lines`), `:products`, `:stock-items`,
  `:barcodes`, `:warehouses` rows and `:balances` keyed `[stock-item-id warehouse-id]`."
  {:malli/schema [:=> [:cat :map] [:vector Finding]]}
  [{:keys [entries stock-items barcodes] :as facts}]
  (let [all     (lines entries)
        product (into {} (map (juxt :stock-item-id :product-id)) stock-items)]
    (vec (concat (references facts all)
                 (duplicate-variants facts all)
                 (stock facts all)
                 (item-changes facts)
                 (ownership facts all :barcodes :barcode-conflict (map (juxt :barcode (comp product :stock-item-id)) barcodes))
                 (ownership facts all :article :article-conflict
                            (keep #(when-let [k (text-key (:article %))] [k (:product-id %)]) stock-items))))))


(defn derive-within-limit
  "Findings, or nil when the rules fail or take longer than 2 seconds; drafts then show without findings."
  {:malli/schema [:=> [:cat :map] [:maybe [:vector Finding]]]}
  [facts]
  (let [task (future (derive facts))]
    (try (.get ^java.util.concurrent.Future task 2000 TimeUnit/MILLISECONDS)
         (catch TimeoutException _
           (future-cancel task)
           (mu/log ::findings-unavailable :reason :timeout)
           nil)
         (catch Exception error
           (mu/log ::findings-unavailable :reason :error :message (ex-message error))
           nil))))

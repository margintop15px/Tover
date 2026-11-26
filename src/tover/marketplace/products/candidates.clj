(ns tover.marketplace.products.candidates
  "Board chapter 42 · Read unmatched listings and candidates: the rules that propose stock items for a listing.

  Pure. The caller reads products, stock items and their barcodes from the store and passes them in. Rules
  propose and never decide: a candidate is preselected only when the listing's barcode or article names one
  product and that product has exactly one item the listing can mean. `name-equal` is never preselected.

  Inputs are plain rows: products `{:product-id :name :status}`, stock items (see `stock-items.model`),
  barcodes `{:stock-item-id :barcode}` and a listing as an `ExternalProduct` row whose `:identifiers` (and
  `:attributes`) are already parsed."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [tover.marketplace.sync.observations :as observations]))


(def variant-attribute-names
  "Attribute definitions that separate variants although the provider does not flag them: size and colour."
  #{"Размер" "Российский размер" "Цвет товара" "Название цвета"})


(def default-unit
  "Ozon states no unit, so a listing is taken to count pieces."
  "pcs")


(def ^:private Row [:map])
(def ^:private Rows [:sequential Row])


(defn- trimmed
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [text]
  (str/trim (or text "")))


(defn- fold
  "Trimmed, lower-case text with repeated spaces as one."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [text]
  (str/lower-case (str/replace (trimmed text) #"\s+" " ")))


(defn- usable-barcode
  "A barcode as compared, or nil for a blank one or one made only of zeros."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [text]
  (let [barcode (trimmed text)]
    (when-not (or (str/blank? barcode) (re-matches #"0+" barcode)) barcode)))


(defn- listing-values
  "Distinct values of the listing's active identifiers of one type."
  {:malli/schema [:=> [:cat Row :string [:=> [:cat :string] [:maybe :string]]] [:vector :string]]}
  [listing type normalize]
  (->> (:identifiers listing)
       (filter #(and (= type (:type %)) (not= false (:active %))))
       (keep #(normalize (:value %)))
       distinct
       vec))


(defn- active-regular?
  {:malli/schema [:=> [:cat Row] :boolean]}
  [item]
  (and (contains? #{nil "active"} (:status item)) (contains? #{nil "regular"} (:condition item))))


(defn barcode-products
  "Barcode to the set of products whose items (not archived) hold it."
  {:malli/schema [:=> [:cat Rows Rows] [:map-of :string [:set :string]]]}
  [barcodes stock-items]
  (let [product-of (into {} (map (juxt :stock-item-id :product-id)) (remove #(= "archived" (:status %)) stock-items))]
    (reduce (fn [owners {:keys [stock-item-id barcode]}]
              (let [barcode (usable-barcode barcode)
                    product (product-of stock-item-id)]
                (if (and barcode product) (update owners barcode (fnil conj #{}) product) owners)))
            {}
            barcodes)))


(defn barcode-owners
  "Barcode to the product that owns it, among items not archived. A barcode held by items of several products
  has no single owner and is left out; `barcode-products` lists all of them."
  {:malli/schema [:=> [:cat Rows Rows] [:map-of :string :string]]}
  [barcodes stock-items]
  (into {}
        (keep (fn [[barcode owners]] (when (= 1 (count owners)) [barcode (first owners)])))
        (barcode-products barcodes stock-items)))


(defn- definition-index
  {:malli/schema [:=> [:cat Rows] [:map-of :string Row]]}
  [definitions]
  (reduce (fn [index d]
            (update index (:attribute-id d)
                    (fn [before]
                      (if before
                        (assoc before
                               :separates-variants (or (:separates-variants before) (:separates-variants d))
                               :name (or (:name before) (:name d)))
                        d))))
          {}
          definitions))


(defn- listing-attributes
  {:malli/schema [:=> [:cat Row] [:sequential Row]]}
  [listing]
  (let [attributes (:attributes listing)]
    (cond (string? attributes) (or (observations/read-json attributes) [])
          :else (or attributes []))))


(defn identity-basis
  "What a confirmed mapping stands on: the listing's variant-separating attributes (by attribute id), its pack,
  and the item's unit and variant label. Attributes separate variants when their definition says so or is
  named like size or colour; with no definition at all every attribute counts."
  {:malli/schema [:=> [:cat Row Rows [:maybe Row]]
                  [:map [:attributes [:map-of :string [:vector :string]]] [:pack [:maybe :string]]
                   [:unit [:maybe :string]] [:variant-label [:maybe :string]]]]}
  [listing definitions item]
  (let [index  (definition-index definitions)
        keep?  (fn [{:keys [attribute-id]}]
                 (or (empty? index)
                     (let [d (get index attribute-id)]
                       (and d (or (true? (:separates-variants d)) (contains? variant-attribute-names (:name d)))))))
        values (fn [attribute] (into [] (keep #(let [v (trimmed (:value %))] (when-not (str/blank? v) v))) (:values attribute)))]
    {:attributes    (into {}
                          (keep (fn [attribute]
                                  (let [vs (values attribute)]
                                    (when (and (keep? attribute) (seq vs)) [(str (:attribute-id attribute)) vs]))))
                          (listing-attributes listing))
     :pack          (:unit-and-pack listing)
     :unit          (:unit item)
     :variant-label (:variant-label item)}))


(defn- listing-variant-labels
  "Labels the listing's variant attributes can be written as: each value, or all values together."
  {:malli/schema [:=> [:cat Row Rows] [:set :string]]}
  [listing definitions]
  (let [values (->> (vals (:attributes (identity-basis listing definitions nil))) (apply concat) (map fold))]
    (cond-> (set values) (seq values) (conj (str/join " " values)))))


(defn- by-label
  {:malli/schema [:=> [:cat Rows] [:vector Row]]}
  [items]
  (vec (sort-by (juxt #(fold (:variant-label %)) :stock-item-id) items)))


(defn- candidate
  {:malli/schema [:=> [:cat Row :string :map] Row]}
  [item rule evidence]
  {:stock-item-id (:stock-item-id item) :item-revision (:revision item) :rule rule :evidence evidence :preselected? false})


(defn- preselectable
  "The one item of a named product that the listing can mean, if there is exactly one."
  {:malli/schema [:=> [:cat Rows Row Rows] [:maybe :string]]}
  [items listing definitions]
  (if (= 1 (count items))
    (:stock-item-id (first items))
    (let [labels (listing-variant-labels listing definitions)
          equal  (filter #(and (contains? labels (fold (:variant-label %)))
                               (= default-unit (or (:unit %) default-unit)))
                         items)]
      (when (= 1 (count equal)) (:stock-item-id (first equal))))))


(defn- barcode-candidates
  {:malli/schema [:=> [:cat [:set :string] [:map-of :string [:set :string]] [:map-of :string [:vector Row]]] [:vector Row]]}
  [named hit items-of]
  (vec (for [product (sort named)
             item    (by-label (items-of product))
             :let    [barcode (first (sort (keep (fn [[b ps]] (when (contains? ps product) b)) hit)))]]
         (candidate item "barcode-owner" {:barcode barcode :product-id product}))))


(defn- article-candidates
  {:malli/schema [:=> [:cat [:set :string] [:map-of :string [:vector Row]]] [:vector Row]]}
  [articles items-of]
  (vec (for [[product items] (sort-by key items-of)
             :let            [matching (filter #(contains? articles (fold (:article %))) items)]
             :when           (seq matching)
             item            (by-label items)]
         (candidate item "article-match" {:article (trimmed (:article (first matching))) :product-id product}))))


(defn- name-candidates
  {:malli/schema [:=> [:cat [:maybe :string] Rows [:map-of :string [:vector Row]]] [:vector Row]]}
  [listing-name products items-of]
  (let [wanted (fold listing-name)]
    (if (str/blank? wanted)
      []
      (vec (for [product (sort-by :product-id products)
                 item    (by-label (items-of (:product-id product)))
                 :let    [base (fold (:name product))]
                 :when   (or (= wanted base) (= wanted (fold (str base " " (:variant-label item)))))]
             (candidate item "name-equal" {:name (trimmed listing-name) :product-id (:product-id product)}))))))


(defn candidates
  "Candidate stock items for a listing, strongest rule first (barcode, article, name), items of one product by
  variant label, and the conflicts the evidence shows: barcodes of the listing that name different products
  give one conflict listing them all. Only active Regular items of active products are candidates."
  {:malli/schema [:=> [:cat [:map [:products Rows] [:stock-items Rows] [:barcodes Rows] [:definitions {:optional true} Rows]] Row]
                  [:map [:candidates [:vector Row]] [:conflicts [:vector Row]]]]}
  [{:keys [products stock-items barcodes definitions]} listing]
  (let [live         (filter #(contains? #{nil "active"} (:status %)) products)
        live?        (set (map :product-id live))
        items-of     (group-by :product-id (filter #(live? (:product-id %)) (filter active-regular? stock-items)))
        owners       (barcode-products barcodes stock-items)
        hit          (into {} (keep (fn [b] (when-let [ps (seq (filter live? (owners b)))] [b (set ps)])))
                           (listing-values listing "barcode" usable-barcode))
        by-barcode   (apply set/union #{} (vals hit))
        articles     (set (listing-values listing "article" #(let [a (fold %)] (when-not (str/blank? a) a))))
        article-hits (article-candidates articles items-of)
        by-article   (set (map (comp :product-id :evidence) article-hits))
        named        (if (seq by-barcode)
                       (when (and (= 1 (count by-barcode)) (or (empty? by-article) (= by-article by-barcode)))
                         (first by-barcode))
                       (when (= 1 (count by-article)) (first by-article)))
        chosen       (when named (preselectable (items-of named) listing (or definitions [])))
        all          (->> (concat (barcode-candidates by-barcode hit items-of)
                                  article-hits
                                  (name-candidates (:name listing) live items-of))
                          (reduce (fn [seen c] (if (contains? (:ids seen) (:stock-item-id c))
                                                 seen
                                                 (-> seen (update :ids conj (:stock-item-id c)) (update :rows conj c))))
                                  {:ids #{} :rows []})
                          :rows)]
    {:candidates (mapv #(assoc % :preselected? (and (some? chosen) (= chosen (:stock-item-id %)) (not= "name-equal" (:rule %)))) all)
     :conflicts  (if (> (count by-barcode) 1)
                   (let [barcodes (vec (sort (keys hit)))]
                     [{:barcode (first barcodes) :barcodes barcodes :product-ids (vec (sort by-barcode))}])
                   [])}))

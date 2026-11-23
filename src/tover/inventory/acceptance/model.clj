(ns tover.inventory.acceptance.model
  "Board chapters 17, 14 and 32a · Accepted inventory history: one acceptance, its record revisions and their lines."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [tover.catalogues.records :as records :refer [Id]]
    [tover.inventory.changes :as changes]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.movements :as movements]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.inventory.valuation :as valuation]
    [tover.workspaces.currency.model :as currency])
  (:import
    [java.security MessageDigest]))


(def Acceptance
  [:map [:acceptance-id Id] [:actor :uuid] [:recorded-at validation/Instant] [:record-count [:int {:min 0}]]])


(def Record
  "Id is `record-id#revision`; a later revision of the same record supersedes the earlier one."
  [:map
   [:record-revision-id Id]
   [:record-id Id]
   [:revision [:int {:min 1}]]
   [:acceptance-id {:bel/ref ::acceptances} Id]
   [:kind entries/kinds]
   [:occurred-at validation/Instant]
   [:sequence [:int {:min 0}]]
   [:amends {:optional true} Id]
   [:cancelled :boolean]
   [:reason {:optional true} :string]
   [:supplier-id {:optional true :bel/ref :tover.catalogues.suppliers.model/suppliers} Id]
   [:store-id {:optional true :bel/ref :tover.catalogues.stores.model/stores} Id]
   ;; Payment only.
   [:direction {:optional true} [:enum "in" "out"]]
   [:amount {:optional true} :string]])


(def RecordLine
  "Id is `record-revision-id/position`. Money is an exact decimal string in the workspace currency. A Correction line
  carries the counted balance instead of a change; replay derives its change."
  [:map
   [:record-line-id Id]
   [:record-revision-id {:bel/ref ::records} Id]
   [:position [:int {:min 0}]]
   [:stock-item-id {:bel/ref :tover.inventory.stock-items.model/stock-items} Id]
   [:warehouse-id {:bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   [:quantity-change {:optional true} :int]
   [:counted-quantity {:optional true} [:int {:min 0}]]
   [:unit-cost {:optional true} :string]
   [:unit-price {:optional true} :string]
   ;; The entering half of a transfer or defect: the position of the leaving half whose average cost it takes.
   [:carry-from {:optional true} [:int {:min 0}]]])


(defevent inventory-changes-accepted {:schema Acceptance})
(defevent inventory-record-accepted {:schema Record})
(defevent inventory-record-line-accepted {:schema RecordLine})


(defmodel acceptances
  {:id     :acceptance-id
   :store  :catalogue
   :schema Acceptance
   :policy ::records/workspace-member
   :events {::inventory-changes-accepted {:create [:actor :recorded-at :record-count]}}})


(defmodel records
  {:id     :record-revision-id
   :store  :catalogue
   :schema Record
   :policy ::records/workspace-member
   :events {::inventory-record-accepted {:create [:record-id :revision :acceptance-id :kind :occurred-at :sequence :amends
                                                  :cancelled :reason :supplier-id :store-id :direction :amount]}}})


(defmodel record-lines
  {:id     :record-line-id
   :store  :catalogue
   :schema RecordLine
   :policy ::records/workspace-member
   :events {::inventory-record-line-accepted {:create [:record-revision-id :position :stock-item-id :warehouse-id
                                                       :quantity-change :counted-quantity :unit-cost :unit-price :carry-from]}}})


(defn record-revision-id
  {:malli/schema [:=> [:cat :string [:int {:min 1}]] :string]}
  [record-id revision]
  (str record-id "#" revision))


(defn record-line-id
  {:malli/schema [:=> [:cat :string [:int {:min 0}]] :string]}
  [record-revision-id position]
  (str record-revision-id "/" position))


(defmethod runtime/coeffect ::history
  [_ context _]
  ;; Every accepted record revision with its acceptance time and actor, and every accepted line, for `valuation/replay`.
  ;; ponytail: full scan per read; add a per-pair index when history grows large.
  (let [accepted (into {} (map (juxt :acceptance-id #(select-keys % [:recorded-at :actor])))
                       (records/all-records context ::acceptances :acceptance-id))]
    {:records (mapv #(merge % (get accepted (:acceptance-id %))) (records/all-records context ::records :record-revision-id))
     :lines   (records/all-records context ::record-lines :record-line-id)}))


;; Review


(def Selection
  "`ordered?`: the member confirmed `order` for entries that share a stock item, warehouse and second."
  [:map [:selection [:vector [:map [:entry-id Id] [:revision [:int {:min 1}]]]]] [:order [:vector Id]]
   [:ordered? {:optional true} :boolean]])


(def Problem changes/Problem)


(defn- text-key
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [text]
  (when-not (str/blank? text) (records/name-key (str/replace (str/trim text) #"\s+" " "))))


(defn- by
  {:malli/schema [:=> [:cat :keyword [:sequential :map]] :map]}
  [k rows]
  (into {} (map (juxt k identity)) rows))


(defn- resolve-lines
  "Every selected line with resolved ids: existing records by id or exact name (an item by product and variant),
  otherwise deterministic new ids shared by the batch: `product:`/`warehouse:`/`item:` + the first line id proposing it."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [{:keys [products warehouses stock-items]} entries]
  (let [all       (for [{:keys [entry-id] :as entry} entries line (:lines entry)] (assoc line :entry-id entry-id :entry entry))
        named     (fn [rows id-key] (into {} (map (juxt (comp text-key :name) id-key)) rows))
        products  (named products :product-id)
        stores    (named warehouses :warehouse-id)
        variant   (fn [line] [(:product-id line) (text-key (:variant-label line))])
        items     (into {} (for [i stock-items :when (= "active" (:status i))] [(variant i) (:stock-item-id i)]))
        first-id  (fn [key-fn lines] (reduce (fn [m {:keys [line-id] :as line}]
                                               (let [k (key-fn line)]
                                                 (if (and k (not (contains? m k))) (assoc m k line-id) m)))
                                             {} (sort-by :line-id lines)))
        new-prods (first-id (comp text-key :product-name) all)
        new-whs   (first-id (comp text-key :warehouse-name) all)
        resolved  (mapv (fn [{:keys [stock-item-id product-id product-name warehouse-id warehouse-name] :as line}]
                          (let [pk (text-key product-name) wk (text-key warehouse-name)]
                            (cond-> line
                              (and (not warehouse-id) wk) (assoc :warehouse-id (or (stores wk) (str "warehouse:" (new-whs wk)))
                                                                 :new-warehouse? (not (stores wk)))
                              (and (not stock-item-id) (not product-id) pk) (assoc :product-id (or (products pk) (str "product:" (new-prods pk)))
                                                                                   :new-product? (not (products pk))))))
                        all)
        new-items (first-id #(when-not (:stock-item-id %) (variant %)) resolved)]
    (mapv (fn [{:keys [stock-item-id] :as line}]
            (cond
              stock-item-id line
              (items (variant line)) (assoc line :stock-item-id (items (variant line)))
              :else (assoc line :stock-item-id (str "item:" (new-items (variant line))) :new-item? true)))
          resolved)))


(defn- with-suppliers
  "A purchase naming a new supplier gets `supplier:` + the first entry id proposing that name, shared by the batch."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [entries]
  (let [new-key  #(when-not (:supplier-id %) (text-key (:supplier-name %)))
        first-id (reduce (fn [m e] (let [k (new-key e)] (if (and k (not (contains? m k))) (assoc m k (:entry-id e)) m)))
                         {} (sort-by :entry-id entries))]
    (mapv #(if-let [k (new-key %)] (assoc % :supplier-id (str "supplier:" (first-id k)) :new-supplier? true) %) entries)))


(defn- new-suppliers
  "One proposing entry per new supplier id, the first by entry id."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [lines]
  (->> (keep #(when (:new-supplier? (:entry %)) (:entry %)) lines)
       (sort-by :entry-id)
       (reduce (fn [m e] (if (contains? m (:supplier-id e)) m (assoc m (:supplier-id e) e))) (sorted-map))
       vals
       vec))


(defn- registrations
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [{:keys [products]} lines]
  (let [names (merge (into {} (map (juxt :product-id :name)) products)
                     (reduce #(if (and (:new-product? %2) (not (contains? %1 (:product-id %2))))
                                (assoc %1 (:product-id %2) (str/trim (:product-name %2)))
                                %1)
                             {} lines))]
    (->> (concat
           (for [e (new-suppliers lines)] {:type :supplier :id (:supplier-id e) :label (str/trim (:supplier-name e))})
           (for [l lines :when (:new-warehouse? l)] {:type :warehouse :id (:warehouse-id l) :label (str/trim (:warehouse-name l))})
           (for [l lines :when (:new-product? l)] {:type :product :id (:product-id l) :label (names (:product-id l))})
           (for [l lines :when (:new-item? l)]
             {:type  :stock-item :id (:stock-item-id l)
              :label (str (names (:product-id l)) (when-let [v (some-> (:variant-label l) str/trim not-empty)] (str " / " v)))})
           (for [l lines :when (:new-item? l) code (:barcodes l)]
             {:type :barcode :id (str (:stock-item-id l) "|" code) :label code}))
         (reduce (fn [[seen out] r] (if (seen [(:type r) (:id r)]) [seen out] [(conj seen [(:type r) (:id r)]) (conj out r)]))
                 [#{} []])
         second)))


(defn- problem
  {:malli/schema [:=> [:cat :map :keyword [:? :map]] Problem]}
  ([line code] (problem line code {}))
  ([line code data] (merge {:entry-id (:entry-id line) :line-id (:line-id line) :code code} data)))


(defn- present
  {:malli/schema [:=> [:cat :map] :map]}
  [m]
  (into {} (remove (comp nil? val)) m))


(defn- distinct-by-item
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [lines]
  (vec (vals (into (sorted-map) (map (juxt :stock-item-id identity)) (reverse lines)))))


(defn- uniqueness
  "New items: variant unique within the product; barcode and article owned by at most one product. A new supplier's
  name is unique among all suppliers, archived included."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:sequential Problem]]}
  [{:keys [products stock-items barcodes suppliers]} lines]
  (let [names    (into {} (map (juxt :product-id :name)) products)
        taken    (into {} (map (juxt (comp text-key :name) :name)) suppliers)
        item     (by :stock-item-id stock-items)
        variants (set (map (juxt :product-id (comp text-key :variant-label)) stock-items))
        article  (reduce (fn [m i] (if-let [k (text-key (:article i))] (update m k (fnil conj #{}) (:product-id i)) m)) {} stock-items)
        owner    (reduce (fn [m b] (update m (:barcode b) (fnil conj #{}) (:product-id (item (:stock-item-id b))))) {} barcodes)
        fresh    (filter :new-item? lines)
        conflict (fn [line owners]
                   (when-let [other (first (remove #{(:product-id line)} owners))]
                     (problem line :uniqueness-conflict {:owner-product (or (names other) other)})))]
    (concat
      (for [e     (distinct (keep #(when (:new-supplier? (:entry %)) (:entry %)) lines))
            :let  [owner (taken (text-key (:supplier-name e)))]
            :when owner]
        {:entry-id (:entry-id e) :line-id nil :code :supplier-name-taken :field :supplier-id :owner-supplier owner})
      ;; Lines naming one new product and variant share one item, so only an existing (archived) item can collide.
      (for [line  (distinct-by-item fresh)
            :when (variants [(:product-id line) (text-key (:variant-label line))])]
        (problem line :duplicate-variant))
      (keep #(conflict % (get article (text-key (:article %)))) fresh)
      (for [line fresh code (:barcodes line) :let [found (conflict line (owner code))] :when found] found)
      (for [[code claims] (group-by second (for [l fresh c (:barcodes l)] [l c]))
            :when         (< 1 (count (distinct (map (comp :product-id first) claims))))
            [line]        claims]
        (problem line :uniqueness-conflict {:owner-product code})))))


(defn- unknown-units
  "New items whose unit is not in the list; a draft saved before the list may hold one."
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential Problem]]}
  [lines]
  (for [{:keys [new-item? unit] :as line} lines
        :let [unit (some-> unit str/trim not-empty)]
        :when (and new-item? unit (not (:new-counterpart? line)) (not (stock-items/unit? unit)))]
    (problem line :unknown-unit)))


(defn- tentative
  "Selected entries as record revisions, and their signed lines (`movements/expand`) as record lines, in reviewed
  order, recorded `now`. A payment is a record without lines."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map] [:sequential :string] :any] :map]}
  [entries lines order now]
  (let [position (zipmap order (range))
        grouped  (group-by :entry-id lines)]
    {:records (vec (for [{:keys [entry-id kind occurred-at supplier-id store-id direction amount]} entries
                         :when (or (= "payment" kind) (grouped entry-id))]
                     (present {:record-revision-id (record-revision-id entry-id 1) :record-id entry-id :revision  1     :kind        kind
                               :occurred-at        occurred-at :sequence (position entry-id 0)         :cancelled false :recorded-at now
                               :supplier-id        supplier-id :store-id store-id :direction direction                  :amount      amount})))
     :lines   (vec (for [[entry-id ls] grouped
                         [index line]  (map vector (range) ls)]
                     (present {:record-line-id     (record-line-id (record-revision-id entry-id 1) index)
                               :record-revision-id (record-revision-id entry-id 1) :position index
                               :stock-item-id      (:stock-item-id line) :warehouse-id       (:warehouse-id line)
                               (if (= "correction" (:kind (:entry line))) :counted-quantity :quantity-change)
                               (if (= "correction" (:kind (:entry line))) (:quantity line) (:change line))
                               :unit-cost          (:unit-cost line) :unit-price             (:unit-price line)   :carry-from (:carry-from line)})))}))


(defn- openings
  "At most one effective opening per pair, first in its timeline."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:sequential Problem]]}
  [history lines]
  (let [effective (valuation/business-order (:records history))
        pairs     (fn [{:keys [records lines]} pred]
                    (let [keep? (set (map :record-revision-id (filter pred records)))
                          at    (into {} (map (juxt :record-revision-id :occurred-at)) records)]
                      (for [l lines :when (keep? (:record-revision-id l))] [[(:stock-item-id l) (:warehouse-id l)] (at (:record-revision-id l))])))
        opening?  #(= "opening" (:kind %))
        live      {:records effective :lines (:lines history)}
        opened    (into {} (pairs live opening?))
        earliest  (reduce (fn [m [pair at]] (update m pair #(if (and % (neg? (compare % at))) % at))) {} (pairs live (complement opening?)))
        pair-of   #(vector (:stock-item-id %) (:warehouse-id %))
        mine      (group-by pair-of (filter (comp opening? :entry) lines))]
    (concat
      (for [[pair ls] mine line ls
            :let      [at (get-in line [:entry :occurred-at])]
            code      [(when (or (contains? opened pair) (< 1 (count ls))) :second-opening)
                       (when (and (earliest pair) at (pos? (compare at (earliest pair)))) :opening-not-first)]
            :when     code]
        (problem line code))
      ;; A selected record dated before the pair's accepted opening.
      (for [line  (remove (comp opening? :entry) lines)
            :let  [at (get-in line [:entry :occurred-at]) opening (opened (pair-of line))]
            :when (and at opening (neg? (compare at opening)))]
        (problem line :opening-not-first)))))


(defn- counts-without-history
  "A count needs accepted history for its pair, or an opening in the same selection; the first count is an opening."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:sequential Problem]]}
  [history lines]
  (let [known (into (set (map (juxt :stock-item-id :warehouse-id) (:lines history)))
                    (comp (filter #(= "opening" (:kind (:entry %)))) (map (juxt :stock-item-id :warehouse-id)))
                    lines)]
    (for [line  lines
          :when (and (= "correction" (:kind (:entry line))) (not (known [(:stock-item-id line) (:warehouse-id line)])))]
      (problem line :count-without-history))))


(defn- units-required
  "Board `Stock Item Updated`: a quantity-bearing line needs its item's unit, set before or in the same selection."
  {:malli/schema [:=> [:cat :map [:sequential :map] [:sequential :map]] [:sequential Problem]]}
  [facts lines changes]
  (let [{:keys [items]} (changes/produce facts lines changes)]
    (for [line lines :when (str/blank? (:unit (items (:stock-item-id line))))]
      (problem line :unit-required))))


(defn- ties
  "Board chapter 17: entries other than openings on one stock item and warehouse at the same second need the member's
  explicit order. An opening always comes first in its timeline, so it ties with nothing."
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential Problem]]}
  [lines]
  (for [[[item warehouse at] ls] (group-by (juxt :stock-item-id :warehouse-id (comp :occurred-at :entry))
                                           (remove #(= "opening" (:kind (:entry %))) lines))
        :when                    (and at (< 1 (count (distinct (map :entry-id ls)))))
        entry-id                 (distinct (map :entry-id ls))]
    {:entry-id entry-id :line-id nil :code :order-required :stock-item-id item :warehouse-id warehouse
     :tied     (vec (distinct (map :entry-id ls)))}))


(defn- picked-changes
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [picked]
  (vec (for [{:keys [entry-id changes]} picked change changes] (assoc change :entry-id entry-id))))


(defn- item-events
  "Catalogue facts turning the current stock items into the produced ones; each fact takes the next revision."
  {:malli/schema [:=> [:cat :map [:sequential :map] [:sequential :map]] [:vector :map]]}
  [{:keys [stock-items barcodes] :as facts} lines changes]
  (let [{:keys [items codes]} (changes/produce facts lines changes)
        current               (by :stock-item-id stock-items)
        before                (set (map (juxt :stock-item-id :barcode) barcodes))
        value                 #(some-> % str/trim not-empty)]
    (vec (mapcat
           (fn [item]
             (let [was    (current item)
                   now    (items item)
                   fields (present {:variant-label (value (:variant-label now)) :article (value (:article now)) :unit (value (:unit now))})
                   facts  (concat
                            (when (not= fields (present (select-keys was [:variant-label :article :unit])))
                              [[::stock-items/stock-item-updated (assoc fields :stock-item-id item)]])
                            (when (not= (:product-id now) (:product-id was))
                              [[::stock-items/stock-item-moved (present {:stock-item-id item              :previous-product-id (:product-id was)
                                                                         :product-id    (:product-id now) :variant-label       (:variant-label fields)})]])
                            (when (not= (:status now) (:status was))
                              [[(if (= "archived" (:status now)) ::stock-items/stock-item-archived ::stock-items/stock-item-restored)
                                {:stock-item-id item :status (:status now)}]]))
                   pairs  (fn [s] (sort (filter #(= item (first %)) s)))]
               (concat
                 (map-indexed (fn [i [type data]] {:type type :data (assoc data :revision (+ (:revision was) 1 i))}) facts)
                 (for [[_ code] (pairs (remove codes before))]
                   {:type ::stock-items/stock-item-barcode-removed :data {:barcode-id (stock-items/barcode-id item code)}})
                 (for [[_ code] (pairs (remove before codes))]
                   {:type ::stock-items/stock-item-barcode-assigned
                    :data {:barcode-id (stock-items/barcode-id item code) :stock-item-id item :barcode code}}))))
           (distinct (map :stock-item-id changes))))))


(defn- sha256
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8")))))


(defn fingerprint
  "What the review saw: the selection and order, the selected entries, referenced and same-named catalogue records
  (suppliers and stores included), owners of the barcodes and articles used, accepted record ids on the affected pairs, and the currency."
  {:malli/schema [:=> [:cat :map Selection [:sequential :map] [:sequential :map]] :string]}
  [{:keys [products warehouses stock-items barcodes history currency entries suppliers] :as facts} selection lines changes]
  (let [ids      (fn [k] (set (concat (keep k lines) (keep k changes))))
        names    (set (keep (comp text-key :product-name) lines))
        stores   (set (keep (comp text-key :warehouse-name) lines))
        codes    (set (concat (mapcat :barcodes lines) (mapcat :barcodes-added changes)))
        articles (set (keep (comp text-key :article) lines))
        pairs    (set (map (juxt :stock-item-id :warehouse-id) lines))
        picked   (set (map :entry-id (:selection selection)))
        chosen   (filter (comp picked :entry-id) entries)
        sellers  (set (keep :supplier-id chosen))
        named    (set (keep (comp text-key :supplier-name) chosen))
        shops    (set (keep :store-id chosen))]
    ;; Canonical text: `pr-str` of equal maps differs with key order, and of an Instant with its identity hash.
    (sha256 (pr-str (walk/postwalk #(cond (map? %) (into (sorted-map) %) (inst? %) (str %) :else %)
                                   [(:selection selection) (:order selection) (boolean (:ordered? selection))
                                    (sort-by :entry-id (filter (comp picked :entry-id) entries))
                                    (sort-by :product-id (filter #(or ((ids :product-id) (:product-id %)) (names (text-key (:name %)))) products))
                                    (sort-by :warehouse-id (filter #(or ((ids :warehouse-id) (:warehouse-id %)) (stores (text-key (:name %)))) warehouses))
                                    (sort-by :stock-item-id (filter #(or ((ids :stock-item-id) (:stock-item-id %)) (articles (text-key (:article %)))) stock-items))
                                    (sort-by :barcode-id (filter #(or (codes (:barcode %)) ((ids :stock-item-id) (:stock-item-id %))) barcodes))
                                    (sort (keep #(when (pairs [(:stock-item-id %) (:warehouse-id %)]) (:record-line-id %)) (:lines history)))
                                    currency
                                    (sort-by :supplier-id (filter #(or (sellers (:supplier-id %)) (named (text-key (:name %)))) suppliers))
                                    (sort-by :store-id (filter (comp shops :store-id) (:stores facts)))])))))


(defn review
  "The exact effects of accepting `selection` in `order` now: before/after per affected pair, required registrations,
  blocking problems and the fingerprint acceptance must match. Nothing here is stored."
  {:malli/schema [:=> [:cat :map Selection] :map]}
  [{:keys [entries history now] :as facts} {:keys [selection order ordered?] :as input}]
  (let [compact  (fn [m] (into {} (remove (comp nil? val)) m))
        pending  (by :entry-id (map #(update (compact %) :lines (partial mapv compact)) entries))
        changed  (for [{:keys [entry-id revision]} selection
                       :when (not= revision (:revision (pending entry-id)))]
                   {:entry-id entry-id :line-id nil :code :entry-changed})
        picked   (keep #(when (= (:revision %) (:revision (pending (:entry-id %)))) (pending (:entry-id %))) selection)
        status   (entries/status-index facts)
        blocked  (for [entry picked {:keys [code line-id field]} (entries/blockers entry (:lines entry) status)]
                   {:entry-id (:entry-id entry) :line-id line-id :field field
                    :code     (if (#{:quantity-missing :warehouse-missing :item-missing :occurred-at-missing} code) :entry-incomplete code)})
        future   (for [{:keys [entry-id occurred-at]} picked :when (and occurred-at (pos? (compare occurred-at now)))]
                   {:entry-id entry-id :line-id nil :code :future-occurrence})
        sellers  (with-suppliers picked)
        lines    (resolve-lines facts (map #(assoc % :lines (movements/expand % (partial movements/counterpart (:stock-items facts)))) sellers))
        usable   (if (seq blocked) [] lines)
        changes  (if (seq blocked) [] (picked-changes picked))
        proposed (tentative (if (seq blocked) [] sellers) usable order now)
        before   (valuation/replay (:records history) (:lines history))
        after    (valuation/replay (into (:records history) (:records proposed)) (into (:lines history) (:lines proposed)))
        pairs    (distinct (map (juxt :stock-item-id :warehouse-id) usable))
        problems (vec (distinct (concat changed blocked future
                                        (when (seq usable) (openings history usable))
                                        (uniqueness facts usable)
                                        (unknown-units usable)
                                        (counts-without-history history usable)
                                        (units-required facts usable changes)
                                        (when-not ordered? (ties usable))
                                        (changes/problems facts usable changes after)
                                        (when-let [{:keys [stock-item-id warehouse-id occurred-at]} (:error after)]
                                          [{:entry-id     (first order) :code        :negative-balance :stock-item-id stock-item-id
                                            :warehouse-id warehouse-id  :occurred-at occurred-at}]))))]
    {:valid?        (and (empty? problems) (seq selection) (= (set order) (set (map :entry-id selection))))
     :effects       (vec (for [[item warehouse :as pair] pairs
                               :let [b (get-in before [:balances pair]) a (get-in after [:balances pair])]]
                           {:stock-item-id   item :warehouse-id warehouse
                            :quantity-before (:quantity b 0)               :quantity-after (:quantity a 0)
                            :value-before    (if b (valuation/value b) 0M) :value-after    (valuation/value a)}))
     :registrations (registrations facts usable)
     :problems      problems
     :lines         usable
     :records       proposed
     :item-events   (item-events facts usable changes)
     :fingerprint   (fingerprint facts input lines (picked-changes picked))}))


(defmethod runtime/coeffect ::now
  [_ _ _]
  (java.time.Instant/now))


(def review-facts
  [[:entries :tover.inventory.entries.model/pending (constantly {})]
   [:catalogue :tover.inventory.entries.model/catalogue (constantly {})]
   [:history ::history (constantly {})]
   currency/row-fact])


(defn review-input
  "Facts as `review` takes them, from the coeffects in `review-facts`."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [entries catalogue history currency-row]}]
  (merge catalogue {:entries entries :history history :currency (currency/current currency-row)}))


(defreadmodel inventory-review
  {:input  Selection
   :output :map
   :policy ::records/workspace-member
   :cofx   (conj review-facts [:clock ::now (constantly {})])}
  [{:keys [clock] :as facts} input]
  (dissoc (review (assoc (review-input facts) :now clock) input) :lines :records :item-events))


;; Accept


(def Accept
  [:map [:workspace-id :uuid] [:acceptance-id Id] [:fingerprint [:string {:min 1}]]
   [:selection [:vector [:map [:entry-id Id] [:revision [:int {:min 1}]]]]] [:order [:vector Id]] [:ordered? {:optional true} :boolean]])


(def rejection
  "Review problem code to the `Accept Reviewed Inventory Changes` rejection it raises."
  {:entry-changed         [:review-outdated "The selection changed since the review. Review it again."]
   :entry-incomplete      [:review-outdated "A selected entry is incomplete. Review it again."]
   :future-occurrence     [:future-occurrence "An occurrence time is later than now."]
   :negative-balance      [:negative-balance "Stock would go below zero."]
   :second-opening        [:second-opening "This stock item already has an opening in this warehouse."]
   :opening-not-first     [:second-opening "An opening must come first in its stock timeline."]
   :uniqueness-conflict   [:uniqueness-conflict "A barcode or article belongs to another product."]
   :duplicate-variant     [:uniqueness-conflict "This product already has a stock item with this variant."]
   :unknown-unit          [:validation "Choose a unit from the list."]
   :supplier-missing      [:validation "Choose a supplier."]
   :lines-missing         [:validation "Add at least one line."]
   :reason-missing        [:validation "Enter a reason."]
   :inputs-missing        [:validation "Add at least one input."]
   :outputs-missing       [:validation "Add at least one output."]
   :side-missing          [:validation "Choose input or output."]
   :direction-missing     [:validation "Choose received or paid."]
   :counterparty-missing  [:validation "Choose a supplier or store."]
   :amount-missing        [:validation "Enter an amount above 0."]
   :supplier-name-taken   [:supplier-name-taken "A supplier with this name already exists."]
   :archived-reference    [:archived-reference "Restore the archived record first."]
   :count-without-history [:validation "This stock has no history here. Declare opening stock first."]
   :unit-required         [:validation "Set the stock item's unit first."]
   :order-required        [:order-required "Entries for the same stock at the same second need an order."]
   :unit-locked           [:unit-locked "The unit is fixed once the item has stock history."]
   :field-conflict        [:field-conflict "Two selected entries change the same stock item field."]
   :move-conflict         [:move-conflict "The move would make a value belong to two products."]
   :restore-needs-product [:restore-needs-product "Restore the product first."]
   :archive-blocked       [:archive-blocked "The item has stock or other pending work."]})


(defn- registration-events
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [lines]
  (let [trim  #(some-> % str/trim not-empty)
        first (fn [k pred] (vals (reduce #(if (and (pred %2) (not (contains? %1 (k %2)))) (assoc %1 (k %2) %2) %1) {} lines)))]
    (vec (concat
           (for [e (new-suppliers lines)]
             {:type :tover.catalogues.suppliers.model/supplier-registered
              :data {:supplier-id (:supplier-id e) :name (trim (:supplier-name e)) :status "active" :revision 1}})
           (for [l (sort-by :line-id (first :warehouse-id :new-warehouse?))]
             {:type :tover.catalogues.warehouses.model/warehouse-registered
              :data {:warehouse-id (:warehouse-id l) :name (trim (:warehouse-name l)) :status "active" :revision 1}})
           (for [l (sort-by :line-id (first :product-id :new-product?))]
             {:type :tover.catalogues.products.model/product-registered
              :data {:product-id (:product-id l) :name (trim (:product-name l)) :status "active" :revision 1}})
           ;; Lines naming one new product and variant share one item: register it, and each of its barcodes, once.
           (for [l (sort-by :line-id (first :stock-item-id :new-item?))]
             {:type ::stock-items/stock-item-registered
              :data (present {:stock-item-id   (:stock-item-id l)   :product-id (:product-id l)  :variant-label (trim (:variant-label l))
                              :article         (trim (:article l))  :unit       (trim (:unit l)) :condition     (:condition l "regular")  :status "active"
                              :regular-item-id (:regular-item-id l) :revision   1})})
           (for [[item code] (distinct (for [l lines :when (:new-item? l) code (:barcodes l)] [(:stock-item-id l) code]))]
             {:type ::stock-items/stock-item-barcode-assigned
              :data {:barcode-id (stock-items/barcode-id item code) :stock-item-id item :barcode code}})))))


(defcommand accept-reviewed-inventory-changes
  {:input  Accept
   :policy ::records/workspace-member
   :cofx   review-facts
   :emits  #{::inventory-changes-accepted ::inventory-record-accepted ::inventory-record-line-accepted
             ::stock-items/stock-item-registered ::stock-items/stock-item-barcode-assigned ::stock-items/stock-item-updated
             ::stock-items/stock-item-moved ::stock-items/stock-item-archived ::stock-items/stock-item-restored
             ::stock-items/stock-item-barcode-removed
             :tover.catalogues.products.model/product-registered :tover.catalogues.warehouses.model/warehouse-registered
             :tover.catalogues.suppliers.model/supplier-registered
             :tover.inventory.entries.model/shared-inventory-entry-accepted}}
  [{:keys [scope actor now] :as facts} {:keys [acceptance-id fingerprint selection order] :as input}]
  (entries/check-workspace! scope input)
  (let [{:keys [problems lines records item-events] :as result} (review (assoc (review-input facts) :now now) (select-keys input [:selection :order :ordered?]))
        reasons (into {} (map (juxt :entry-id :reason)) (:entries facts))]
    (when (not= fingerprint (:fingerprint result))
      (records/fail! :review-outdated "Something the review saw changed. Review it again." {}))
    (when-let [{:keys [code] :as problem} (first problems)]
      (let [[type message] (rejection code [:review-outdated "Review it again."])]
        (records/fail! type message {:problem problem :problems problems})))
    (when-not (:valid? result)
      (records/fail! :review-outdated "The selection and order must match. Review it again." {}))
    {:events (vec (concat
                    [{:type ::inventory-changes-accepted
                      :data {:acceptance-id acceptance-id :actor actor :recorded-at now :record-count (count (:records records))}}]
                    (registration-events lines)
                    item-events
                    (for [r (sort-by :sequence (:records records))]
                      {:type ::inventory-record-accepted
                       :data (present (-> r (dissoc :recorded-at) (assoc :acceptance-id acceptance-id :reason (reasons (:record-id r)))))})
                    (for [l (:lines records)] {:type ::inventory-record-line-accepted :data l})
                    (for [entry-id order
                          :let     [revision (some #(when (= entry-id (:entry-id %)) (:revision %)) selection)]]
                      {:type :tover.inventory.entries.model/shared-inventory-entry-accepted
                       :data {:entry-id entry-id :status "accepted" :revision (inc revision)}})))
     :fx     (records/publish scope)}))


(defreadmodel acceptance-result
  {:input  [:map [:acceptance-id Id]]
   :output :map
   :policy ::records/workspace-member
   :cofx   [[:history ::history (constantly {})] [:pending :tover.inventory.entries.model/pending (constantly {})]]}
  [{:keys [history pending]} {:keys [acceptance-id]}]
  (let [records (sort-by :sequence (filter #(= acceptance-id (:acceptance-id %)) (:records history)))
        mine    (set (map :record-revision-id records))
        pairs   (distinct (keep #(when (mine (:record-revision-id %)) [(:stock-item-id %) (:warehouse-id %)]) (:lines history)))
        after   (:balances (valuation/replay (:records history) (:lines history)))]
    {:records       (vec records)
     :balances      (vec (for [[item warehouse :as pair] pairs :let [b (after pair)]]
                           {:stock-item-id item :warehouse-id warehouse :quantity (:quantity b 0) :value (if b (valuation/value b) 0M)}))
     :pending-count (count pending)}))


(defn operations
  "Effective operation records (every kind but opening and correction), newest first, each with its lines and derived
  total: a payment's amount, else the sum of priced line amounts (quantity × the kind's price field), `partial?` when
  some line of a priced kind has no price. Kinds without a price have no total."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:keys [records lines]}]
  (let [by-record (group-by :record-revision-id lines)]
    (->> (valuation/effective records)
         (remove (comp #{"opening" "correction"} :kind))
         (sort-by (juxt :occurred-at :recorded-at :sequence) #(compare %2 %1))
         (mapv (fn [{:keys [kind record-revision-id amount] :as r}]
                 (let [ls     (vec (sort-by :position (by-record record-revision-id)))
                       price  (get-in entries/operation-rules [kind :price])
                       priced (when price (filter price ls))]
                   (assoc r :lines ls
                          :total (cond amount (bigdec amount)
                                       (seq priced) (reduce + 0M (map #(* (abs (:quantity-change %)) (bigdec (price %))) priced)))
                          :partial? (boolean (and price (< (count priced) (count ls)))))))))))


(defreadmodel accepted-operations
  {:input [:map] :output [:vector :map] :policy ::records/workspace-member :cofx [[:history ::history (constantly {})]]}
  [{:keys [history]} _]
  (operations history))

(ns tover.inventory.acceptance.model
  "Board chapters 17, 14 and 32a · Accepted inventory history: one acceptance, its record revisions and their lines."
  (:require
    [bel.core :refer [defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.catalogues.records :as records :refer [Id]]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.valuation :as valuation])
  (:import
    [java.security MessageDigest]))


(def Acceptance
  [:map [:acceptance-id Id] [:actor :uuid] [:recorded-at validation/Instant] [:record-count [:int {:min 1}]]])


(def Record
  "Id is `record-id#revision`; a later revision of the same record supersedes the earlier one."
  [:map
   [:record-revision-id Id]
   [:record-id Id]
   [:revision [:int {:min 1}]]
   [:acceptance-id {:bel/ref ::acceptances} Id]
   [:kind [:enum "opening"]]
   [:occurred-at validation/Instant]
   [:sequence [:int {:min 0}]]
   [:amends {:optional true} Id]
   [:cancelled :boolean]
   [:reason {:optional true} :string]])


(def RecordLine
  "Id is `record-revision-id/position`. Money is an exact decimal string in the workspace currency."
  [:map
   [:record-line-id Id]
   [:record-revision-id {:bel/ref ::records} Id]
   [:position [:int {:min 0}]]
   [:stock-item-id {:bel/ref :tover.inventory.stock-items.model/stock-items} Id]
   [:warehouse-id {:bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   [:quantity-change :int]
   [:unit-cost {:optional true} :string]])


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
                                                  :cancelled :reason]}}})


(defmodel record-lines
  {:id     :record-line-id
   :store  :catalogue
   :schema RecordLine
   :policy ::records/workspace-member
   :events {::inventory-record-line-accepted {:create [:record-revision-id :position :stock-item-id :warehouse-id
                                                       :quantity-change :unit-cost]}}})


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
  ;; Every accepted record revision with its acceptance time, and every accepted line, for `valuation/replay`.
  ;; ponytail: full scan per read; add a per-pair index when history grows large.
  (let [recorded (into {} (map (juxt :acceptance-id :recorded-at)) (records/all-records context ::acceptances :acceptance-id))]
    {:records (mapv #(assoc % :recorded-at (get recorded (:acceptance-id %))) (records/all-records context ::records :record-revision-id))
     :lines   (records/all-records context ::record-lines :record-line-id)}))


;; Review


(def Selection
  [:map [:selection [:vector [:map [:entry-id Id] [:revision [:int {:min 1}]]]]] [:order [:vector Id]]])


(def Problem
  [:map [:entry-id :string] [:line-id {:optional true} [:maybe :string]] [:code :keyword]])


(defn- text-key
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [text]
  (when-not (str/blank? text) (records/name-key (str/replace (str/trim text) #"\s+" " "))))


(defn- by
  {:malli/schema [:=> [:cat :keyword [:sequential :map]] :map]}
  [k rows]
  (into {} (map (juxt k identity)) rows))


(defn- resolve-lines
  "Every selected line with resolved ids: existing records by id or exact name, otherwise deterministic new ids
  (`item:<line-id>`, `product:`/`warehouse:` + the first line id proposing that name)."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [{:keys [products warehouses]} entries]
  (let [all       (for [{:keys [entry-id] :as entry} entries line (:lines entry)] (assoc line :entry-id entry-id :entry entry))
        named     (fn [rows id-key] (into {} (map (juxt (comp text-key :name) id-key)) rows))
        products  (named products :product-id)
        stores    (named warehouses :warehouse-id)
        first-id  (fn [field] (reduce (fn [m {:keys [line-id] :as line}]
                                        (let [k (text-key (get line field))]
                                          (if (and k (not (contains? m k))) (assoc m k line-id) m)))
                                      {} (sort-by :line-id all)))
        new-prods (first-id :product-name)
        new-whs   (first-id :warehouse-name)]
    (mapv (fn [{:keys [stock-item-id product-id product-name warehouse-id warehouse-name line-id] :as line}]
            (let [pk (text-key product-name) wk (text-key warehouse-name)]
              (cond-> line
                (and (not warehouse-id) wk) (assoc :warehouse-id (or (stores wk) (str "warehouse:" (new-whs wk)))
                                                   :new-warehouse? (not (stores wk)))
                (and (not stock-item-id) (not product-id) pk) (assoc :product-id (or (products pk) (str "product:" (new-prods pk)))
                                                                     :new-product? (not (products pk)))
                (not stock-item-id) (assoc :stock-item-id (str "item:" line-id) :new-item? true))))
          all)))


(defn- registrations
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [{:keys [products]} lines]
  (let [names (merge (into {} (map (juxt :product-id :name)) products)
                     (reduce #(if (and (:new-product? %2) (not (contains? %1 (:product-id %2))))
                                (assoc %1 (:product-id %2) (str/trim (:product-name %2)))
                                %1)
                             {} lines))]
    (->> (concat
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


(defn- uniqueness
  "New items: variant unique within the product; barcode and article owned by at most one product."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:sequential Problem]]}
  [{:keys [products stock-items barcodes]} lines]
  (let [names    (into {} (map (juxt :product-id :name)) products)
        item     (by :stock-item-id stock-items)
        variants (set (map (juxt :product-id (comp text-key :variant-label)) stock-items))
        article  (reduce (fn [m i] (if-let [k (text-key (:article i))] (update m k (fnil conj #{}) (:product-id i)) m)) {} stock-items)
        owner    (reduce (fn [m b] (update m (:barcode b) (fnil conj #{}) (:product-id (item (:stock-item-id b))))) {} barcodes)
        fresh    (filter :new-item? lines)
        conflict (fn [line owners]
                   (when-let [other (first (remove #{(:product-id line)} owners))]
                     (problem line :uniqueness-conflict {:owner-product (or (names other) other)})))]
    (concat
      (for [[line index] (map vector fresh (range))
            :let         [key [(:product-id line) (text-key (:variant-label line))]]
            :when        (or (variants key) (some #(= key [(:product-id %) (text-key (:variant-label %))]) (take index fresh)))]
        (problem line :duplicate-variant))
      (keep #(conflict % (get article (text-key (:article %)))) fresh)
      (for [line fresh code (:barcodes line) :let [found (conflict line (owner code))] :when found] found)
      (for [[code claims] (group-by second (for [l fresh c (:barcodes l)] [l c]))
            :when         (< 1 (count (distinct (map (comp :product-id first) claims))))
            [line]        claims]
        (problem line :uniqueness-conflict {:owner-product code})))))


(defn- tentative
  "Selected entries as record revisions and lines, in reviewed order, recorded `now`."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :string] :any] :map]}
  [lines order now]
  (let [position (zipmap order (range))
        grouped  (group-by :entry-id lines)]
    {:records (vec (for [[entry-id ls] grouped
                         :let          [{:keys [kind occurred-at]} (:entry (first ls))]]
                     {:record-revision-id (record-revision-id entry-id 1) :record-id entry-id :revision  1     :kind        kind
                      :occurred-at        occurred-at :sequence (position entry-id 0)         :cancelled false :recorded-at now}))
     :lines   (vec (for [[entry-id ls] grouped
                         [index line]  (map vector (range) ls)]
                     (cond-> {:record-line-id     (record-line-id (record-revision-id entry-id 1) index)
                              :record-revision-id (record-revision-id entry-id 1) :position index
                              :stock-item-id      (:stock-item-id line) :warehouse-id       (:warehouse-id line)
                              :quantity-change    (:quantity line)}
                       (:unit-cost line) (assoc :unit-cost (:unit-cost line)))))}))


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
        accepted  (set (map first (pairs live opening?)))
        earliest  (reduce (fn [m [pair at]] (update m pair #(if (and % (neg? (compare % at))) % at))) {} (pairs live (complement opening?)))
        mine      (group-by #(vector (:stock-item-id %) (:warehouse-id %)) (filter (comp opening? :entry) lines))]
    (for [[pair ls] mine line ls
          :let      [at (get-in line [:entry :occurred-at])]
          code      [(when (or (accepted pair) (< 1 (count ls))) :second-opening)
                     (when (and (earliest pair) at (pos? (compare at (earliest pair)))) :opening-not-first)]
          :when     code]
      (problem line code))))


(defn- sha256
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8")))))


(defn fingerprint
  "What the review saw: the selection and order, the selected entries, referenced and same-named catalogue records,
  owners of the barcodes and articles used, accepted record ids on the affected pairs, and the currency."
  {:malli/schema [:=> [:cat :map Selection [:sequential :map]] :string]}
  [{:keys [products warehouses stock-items barcodes history currency entries]} selection lines]
  (let [ids      (fn [k] (set (keep k lines)))
        names    (set (keep (comp text-key :product-name) lines))
        stores   (set (keep (comp text-key :warehouse-name) lines))
        codes    (set (mapcat :barcodes lines))
        articles (set (keep (comp text-key :article) lines))
        pairs    (set (map (juxt :stock-item-id :warehouse-id) lines))
        picked   (set (map :entry-id (:selection selection)))]
    (sha256 (pr-str [(:selection selection) (:order selection)
                     (sort-by :entry-id (filter (comp picked :entry-id) entries))
                     (sort-by :product-id (filter #(or ((ids :product-id) (:product-id %)) (names (text-key (:name %)))) products))
                     (sort-by :warehouse-id (filter #(or ((ids :warehouse-id) (:warehouse-id %)) (stores (text-key (:name %)))) warehouses))
                     (sort-by :stock-item-id (filter #(or ((ids :stock-item-id) (:stock-item-id %)) (articles (text-key (:article %)))) stock-items))
                     (sort-by :barcode-id (filter (comp codes :barcode) barcodes))
                     (sort (keep #(when (pairs [(:stock-item-id %) (:warehouse-id %)]) (:record-line-id %)) (:lines history)))
                     currency]))))


(defn review
  "The exact effects of accepting `selection` in `order` now: before/after per affected pair, required registrations,
  blocking problems and the fingerprint acceptance must match. Nothing here is stored."
  {:malli/schema [:=> [:cat :map Selection] :map]}
  [{:keys [entries history now] :as facts} {:keys [selection order] :as input}]
  (let [compact  (fn [m] (into {} (remove (comp nil? val)) m))
        pending  (by :entry-id (map #(update (compact %) :lines (partial mapv compact)) entries))
        changed  (for [{:keys [entry-id revision]} selection
                       :when (not= revision (:revision (pending entry-id)))]
                   {:entry-id entry-id :line-id nil :code :entry-changed})
        picked   (keep #(when (= (:revision %) (:revision (pending (:entry-id %)))) (pending (:entry-id %))) selection)
        status   (entries/status-index facts)
        blocked  (for [entry picked {:keys [code line-id]} (entries/blockers entry (:lines entry) status)]
                   {:entry-id (:entry-id entry) :line-id line-id :code (if (= :archived-reference code) :archived-reference :entry-incomplete)})
        future   (for [{:keys [entry-id occurred-at]} picked :when (and occurred-at (pos? (compare occurred-at now)))]
                   {:entry-id entry-id :line-id nil :code :future-occurrence})
        lines    (resolve-lines facts picked)
        usable   (if (seq blocked) [] lines)
        proposed (tentative usable order now)
        before   (valuation/replay (:records history) (:lines history))
        after    (valuation/replay (into (:records history) (:records proposed)) (into (:lines history) (:lines proposed)))
        pairs    (distinct (map (juxt :stock-item-id :warehouse-id) usable))
        problems (vec (distinct (concat changed blocked future
                                        (when (seq usable) (openings history usable))
                                        (uniqueness facts usable)
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
     :fingerprint   (fingerprint facts input lines)}))


(defmethod runtime/coeffect ::now
  [_ _ _]
  (java.time.Instant/now))


(def review-facts
  [[:entries :tover.inventory.entries.model/pending (constantly {})]
   [:catalogue :tover.inventory.entries.model/catalogue (constantly {})]
   [:history ::history (constantly {})]
   [:currency-row :tover.workspaces.currency.model/workspace-currencies (fn [_ _] {:currency-key "currency"})]])


(defn- review-input
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [entries catalogue history currency-row]}]
  (merge catalogue {:entries entries :history history :currency (or (:currency currency-row) "EUR")}))


(defreadmodel inventory-review
  {:input  Selection
   :output :map
   :policy ::records/workspace-member
   :cofx   (conj review-facts [:clock ::now (constantly {})])}
  [{:keys [clock] :as facts} input]
  (dissoc (review (assoc (review-input facts) :now clock) input) :lines :records))

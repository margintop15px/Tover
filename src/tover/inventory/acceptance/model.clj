(ns tover.inventory.acceptance.model
  "Board chapters 17, 14 and 32a · Accepted inventory history: one acceptance, its record revisions and their lines."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [tover.catalogues.records :as records :refer [Id]]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.inventory.valuation :as valuation]
    [tover.workspaces.currency.model :as currency])
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
  ;; Every accepted record revision with its acceptance time and actor, and every accepted line, for `valuation/replay`.
  ;; ponytail: full scan per read; add a per-pair index when history grows large.
  (let [accepted (into {} (map (juxt :acceptance-id #(select-keys % [:recorded-at :actor])))
                       (records/all-records context ::acceptances :acceptance-id))]
    {:records (mapv #(merge % (get accepted (:acceptance-id %))) (records/all-records context ::records :record-revision-id))
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


(defn- distinct-by-item
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [lines]
  (vec (vals (into (sorted-map) (map (juxt :stock-item-id identity)) (reverse lines)))))


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
    ;; Canonical text: `pr-str` of equal maps differs with key order, and of an Instant with its identity hash.
    (sha256 (pr-str (walk/postwalk #(cond (map? %) (into (sorted-map) %) (inst? %) (str %) :else %)
                                   [(:selection selection) (:order selection)
                                    (sort-by :entry-id (filter (comp picked :entry-id) entries))
                                    (sort-by :product-id (filter #(or ((ids :product-id) (:product-id %)) (names (text-key (:name %)))) products))
                                    (sort-by :warehouse-id (filter #(or ((ids :warehouse-id) (:warehouse-id %)) (stores (text-key (:name %)))) warehouses))
                                    (sort-by :stock-item-id (filter #(or ((ids :stock-item-id) (:stock-item-id %)) (articles (text-key (:article %)))) stock-items))
                                    (sort-by :barcode-id (filter (comp codes :barcode) barcodes))
                                    (sort (keep #(when (pairs [(:stock-item-id %) (:warehouse-id %)]) (:record-line-id %)) (:lines history)))
                                    currency])))))


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
  (dissoc (review (assoc (review-input facts) :now clock) input) :lines :records))


;; Accept


(def Accept
  [:map [:workspace-id :uuid] [:acceptance-id Id] [:fingerprint [:string {:min 1}]]
   [:selection [:vector [:map [:entry-id Id] [:revision [:int {:min 1}]]]]] [:order [:vector Id]]])


(def rejection
  "Review problem code to the `Accept Reviewed Inventory Changes` rejection it raises."
  {:entry-changed       [:review-outdated "The selection changed since the review. Review it again."]
   :entry-incomplete    [:review-outdated "A selected entry is incomplete. Review it again."]
   :future-occurrence   [:future-occurrence "An occurrence time is later than now."]
   :negative-balance    [:negative-balance "Stock would go below zero."]
   :second-opening      [:second-opening "This stock item already has an opening in this warehouse."]
   :opening-not-first   [:second-opening "An opening must come first in its stock timeline."]
   :uniqueness-conflict [:uniqueness-conflict "A barcode or article belongs to another product."]
   :duplicate-variant   [:uniqueness-conflict "This product already has a stock item with this variant."]
   :archived-reference  [:archived-reference "Restore the archived product or warehouse first."]})


(defn- present
  {:malli/schema [:=> [:cat :map] :map]}
  [m]
  (into {} (remove (comp nil? val)) m))


(defn- registration-events
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [lines]
  (let [trim  #(some-> % str/trim not-empty)
        first (fn [k pred] (vals (reduce #(if (and (pred %2) (not (contains? %1 (k %2)))) (assoc %1 (k %2) %2) %1) {} lines)))]
    (vec (concat
           (for [l (sort-by :line-id (first :warehouse-id :new-warehouse?))]
             {:type :tover.catalogues.warehouses.model/warehouse-registered
              :data {:warehouse-id (:warehouse-id l) :name (trim (:warehouse-name l)) :status "active" :revision 1}})
           (for [l (sort-by :line-id (first :product-id :new-product?))]
             {:type :tover.catalogues.products.model/product-registered
              :data {:product-id (:product-id l) :name (trim (:product-name l)) :status "active" :revision 1}})
           (for [l lines :when (:new-item? l)]
             {:type ::stock-items/stock-item-registered
              :data (present {:stock-item-id (:stock-item-id l)  :product-id (:product-id l)  :variant-label (trim (:variant-label l))
                              :article       (trim (:article l)) :unit       (trim (:unit l)) :condition     "regular"                 :status "active"
                              :revision      1})})
           (for [l lines :when (:new-item? l) code (:barcodes l)]
             {:type ::stock-items/stock-item-barcode-assigned
              :data {:barcode-id (stock-items/barcode-id (:stock-item-id l) code) :stock-item-id (:stock-item-id l) :barcode code}})))))


(defcommand accept-reviewed-inventory-changes
  {:input  Accept
   :policy ::records/workspace-member
   :cofx   review-facts
   :emits  #{::inventory-changes-accepted ::inventory-record-accepted ::inventory-record-line-accepted
             ::stock-items/stock-item-registered ::stock-items/stock-item-barcode-assigned
             :tover.catalogues.products.model/product-registered :tover.catalogues.warehouses.model/warehouse-registered
             :tover.inventory.entries.model/shared-inventory-entry-accepted}}
  [{:keys [scope actor now] :as facts} {:keys [acceptance-id fingerprint selection order] :as input}]
  (entries/check-workspace! scope input)
  (let [{:keys [problems lines records] :as result} (review (assoc (review-input facts) :now now) (select-keys input [:selection :order]))
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

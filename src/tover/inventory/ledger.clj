(ns tover.inventory.ledger
  (:require
    [clojure.string :as str]
    [tover.inventory.schema :as schema]
    [tover.inventory.semantics :as semantics])
  (:import
    [java.time Instant]))


(defn- require-lines!
  {:malli/schema [:=> [:cat :any :boolean :boolean] [:vector :map]]}
  [lines zero? location?]
  (when-not (and (vector? lines) (seq lines) (<= (count lines) 500))
    (schema/fail! :validation "Add between one and 500 item lines." {:field :lines}))
  (let [normalized
        (mapv (fn [index line]
                (when-not (map? line)
                  (schema/fail! :validation "Each item line must be a map." {:field :lines}))
                (doseq [field [:name :article :barcode :unit]]
                  (when (contains? line field)
                    (when-not (and (string? (get line field)) (<= (count (get line field)) 500))
                      (schema/fail! :validation "Item descriptions must be text." {:field field}))))
                (cond-> (assoc line
                               :id (schema/text! (or (:id line) (str "line-" index)) :line-id)
                               :item-id (schema/text! (:item-id line) :item-id)
                               :quantity (schema/quantity! (:quantity line) zero?))
                  location? (assoc :location (schema/text! (:location line) :location))
                  (and (not location?) (string? (:location line)) (str/blank? (:location line))) (dissoc :location)
                  (contains? line :amount) (assoc :amount (schema/money! (:amount line)))))
              (range) lines)]
    (when-not (= (count normalized) (count (set (map :id normalized))))
      (schema/fail! :validation "Item line identifiers must be unique within an operation." {:field :lines}))
    normalized))


(defn- financial-fields!
  {:malli/schema [:=> [:cat :map] :map]}
  [entry]
  (cond-> entry
    (contains? entry :amount) (assoc :amount (schema/money! (:amount entry)))
    (contains? entry :currency)
    (assoc :currency (let [currency (:currency entry)]
                       (when-not (and (string? currency) (re-matches #"[A-Z]{3}" currency))
                         (schema/fail! :validation "Use a three-letter uppercase currency code." {:field :currency}))
                       currency))))


(defn normalize-entry
  "Validate a complete proposal. Incomplete autosaved drafts bypass this boundary until preview."
  {:malli/schema [:=> [:cat :any] :map]}
  [entry]
  (when-not (map? entry)
    (schema/fail! :validation "An inventory entry must be a map." {}))
  (let [kind (:kind entry :operation)]
    (case kind
      (:revision :cancellation)
      (let [entry (assoc entry :kind kind
                         :target-id (schema/text! (:target-id entry) :target-id)
                         :reason (schema/text! (:reason entry) :reason))]
        (if (= kind :revision)
          (let [replacement (normalize-entry (:replacement entry))]
            (when (#{:revision :cancellation} (:kind replacement))
              (schema/fail! :validation "A replacement must be an operation or stock correction." {}))
            (assoc entry :replacement replacement))
          entry))
      (:opening :correction)
      (-> entry
          (assoc :kind kind
                 :occurred-at (schema/timestamp! (:occurred-at entry))
                 :reason (if (= kind :opening)
                           (or (:reason entry) "Opening balance")
                           (schema/text! (:reason entry) :reason))
                 :lines (require-lines! (:lines entry) true true))
          financial-fields!)
      :operation
      (let [type  (:type entry)
            entry (-> entry
                      (assoc :kind kind :occurred-at (schema/timestamp! (:occurred-at entry)))
                      financial-fields!)]
        (when-not (schema/OperationTypes type)
          (schema/fail! :validation "Choose one of the eight operation types." {:field :type}))
        (when (#{:purchase :sale :payment} type)
          (schema/text! (:party entry) :party))
        (when (#{:return :payment} type)
          (when-not (#{:incoming :outgoing} (:direction entry))
            (schema/fail! :validation "Choose incoming or outgoing." {:field :direction})))
        (when (#{:defect :write-off} type)
          (schema/text! (:reason entry) :reason))
        (case type
          :payment
          (let [amount (schema/money! (:amount entry))]
            (when-not (pos? amount)
              (schema/fail! :validation "A payment amount must be positive." {:field :amount}))
            (when-not (:currency entry)
              (schema/fail! :validation "A payment currency is required." {:field :currency}))
            (assoc entry :amount amount))
          :production
          (assoc entry :inputs (require-lines! (:inputs entry) false true)
                 :outputs (require-lines! (:outputs entry) false true))
          :transfer
          (let [from (schema/text! (:from-location entry) :from-location)
                to   (schema/text! (:to-location entry) :to-location)]
            (when (= from to)
              (schema/fail! :validation "Choose different transfer locations." {:field :to-location}))
            (assoc entry :lines (require-lines! (:lines entry) false false)))
          :purchase
          (let [receipts? (contains? entry :receipts)
                lines     (require-lines! (:lines entry) false (not receipts?))
                entry     (assoc entry :lines lines)]
            (if-not receipts?
              entry
              (do
                (when-not (and (vector? (:receipts entry)) (<= (count (:receipts entry)) 500))
                  (schema/fail! :validation "Purchase arrivals must be a list." {:field :receipts}))
                (let [receipts (mapv (fn [index receipt]
                                       (assoc receipt :id (or (:id receipt) (str "receipt-" index))
                                              :occurred-at (schema/timestamp! (:occurred-at receipt))
                                              :lines (require-lines! (:lines receipt) false true)))
                                     (range) (:receipts entry))
                      ordered  (reduce (fn [m line] (update m (:item-id line) (fnil +' 0) (:quantity line))) {} lines)
                      received (reduce (fn [m line] (update m (:item-id line) (fnil +' 0) (:quantity line))) {} (mapcat :lines receipts))]
                  (doseq [[item-id quantity] received]
                    (when (> quantity (get ordered item-id 0))
                      (schema/fail! :validation "Arrivals exceed the purchase's ordered quantity."
                                    {:field :receipts :item-id item-id :ordered (get ordered item-id 0) :received quantity})))
                  (when-not (= (count receipts) (count (set (map :id receipts))))
                    (schema/fail! :validation "Arrival identifiers must be unique." {:field :receipts}))
                  (assoc entry :receipts receipts)))))
          (assoc entry :lines (require-lines! (:lines entry) false true))))
      (schema/fail! :validation "Unknown inventory entry kind." {:field :kind}))))


(defn effective-entries
  "Resolve immutable entity revisions, retaining the original order for equal occurrence times."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [history]
  (->> history
       (reduce (fn [entities record]
                 (assoc entities (:entity-id record) record)) {})
       vals
       (remove :cancelled?)
       (map (fn [{:keys [entity-id revision sequence business-order actor recorded-at entry]}]
              (assoc entry :id entity-id :revision revision :business-order business-order
                     :accepted-order sequence :actor actor :recorded-at recorded-at)))
       (sort-by (juxt #(Instant/parse (:occurred-at %)) :business-order :id))
       vec))


(defn- events
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
  [entry]
  (if (and (= :purchase (:type entry)) (contains? entry :receipts))
    (map-indexed (fn [index receipt]
                   (assoc entry :event-id (str (:id entry) "/" (:id receipt))
                          :occurred-at (:occurred-at receipt) :lines (:lines receipt)
                          :receipt-id (:id receipt) :event-index index))
                 (:receipts entry))
    [(assoc entry :event-id (:id entry) :event-index 0)]))


(defn- update-item
  {:malli/schema [:=> [:cat :map :map] :map]}
  [items line]
  (let [id (:item-id line)]
    (update items id #(merge {:id id :name id :article "" :barcode "" :unit "pcs"}
                             % (select-keys line [:name :article :barcode :unit])))))


(defn- entry-lines
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
  [entry]
  (concat (:lines entry) (:inputs entry) (:outputs entry) (mapcat :lines (:receipts entry))))


(defn- catalog
  "Ordinary lines retain document snapshots; explicit stock corrections control current item metadata."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [entries]
  (let [initial     (reduce update-item {} (mapcat entry-lines entries))
        corrections (sort-by (juxt #(or (:accepted-order %) (:business-order %)) :id)
                             (filter #(= :correction (:kind %)) entries))]
    (reduce update-item initial (mapcat entry-lines corrections))))


(defn row-id
  {:malli/schema [:=> [:cat :string :string] :string]}
  [item-id location]
  (str (java.net.URLEncoder/encode item-id java.nio.charset.StandardCharsets/UTF_8)
       "@" (java.net.URLEncoder/encode location java.nio.charset.StandardCharsets/UTF_8)))


(defn- apply-line
  {:malli/schema [:=> [:cat :map :map :map [:enum :set :add :remove]] :map]}
  [projection event line mode]
  (let [key      [(:item-id line) (:location line)]
        before   (get-in projection [:balances key] 0)
        quantity (:quantity line)
        after    (case mode :set quantity :add (+' before quantity) :remove (-' before quantity))
        record   {:operation-id   (:id event)             :operation-revision (:revision event)    :event-id    (:event-id event)
                  :type           (:type event)           :kind               (:kind event)        :occurred-at (:occurred-at event)
                  :business-order (:business-order event) :event-index        (:event-index event) :phase       (:phase event 0)
                  :recorded-at    (:recorded-at event)    :actor              (:actor event)       :reason      (:reason event)
                  :party          (:party event)          :line-id            (:id line)           :item-id     (:item-id line)      :location (:location line)
                  :quantity       quantity                :before             before               :after       after                :delta    (-' after before)}]
    (when (or (neg? after) (> after Long/MAX_VALUE))
      (schema/fail! :validation "This timeline leaves unavailable stock or an unsupported balance."
                    {:errors [(assoc record :message "Insufficient stock at this occurrence time.")]}))
    (-> projection
        (assoc-in [:balances key] after)
        (update :records conj record))))

(defn- apply-lines
  {:malli/schema [:=> [:cat :map :map [:sequential :map] [:enum :set :add :remove]] :map]}
  [projection event lines mode]
  (reduce #(apply-line %1 event %2 mode) projection lines))


(defn- apply-event
  {:malli/schema [:=> [:cat :map :map] :map]}
  [projection event]
  (let [{:keys [kind type lines]} event]
    (case (semantics/stock-effect kind type)
      :none projection
      :add (apply-lines projection event lines :add)
      :set (apply-lines projection event lines :set)
      :remove (apply-lines projection event lines :remove)
      :directional (apply-lines projection event lines (if (= :incoming (:direction event)) :add :remove))
      :transfer (-> projection
                    (apply-lines (assoc event :phase 0) (mapv #(assoc % :location (:from-location event)) lines) :remove)
                    (apply-lines (assoc event :phase 1) (mapv #(assoc % :location (:to-location event)) lines) :add))
      :production (-> projection
                      (apply-lines (assoc event :phase 0) (:inputs event) :remove)
                      (apply-lines (assoc event :phase 1) (:outputs event) :add)
                      (update :lineage conj {:operation-id   (:id event) :occurred-at            (:occurred-at event)
                                             :business-order (:business-order event) :allocation :unknown
                                             :inputs         (mapv #(assoc % :line-id (:id %)) (:inputs event))
                                             :outputs        (mapv #(assoc % :line-id (:id %)) (:outputs event))})))))


(defn replay
  "Derive counted stock and dated production relationships; never allocate receipts or units."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [entries]
  (let [catalog    (catalog entries)
        projection (reduce apply-event {:balances {} :items catalog :records [] :lineage []}
                           (sort-by (juxt #(Instant/parse (:occurred-at %)) :business-order :event-index :event-id)
                                    (mapcat events entries)))
        rows       (->> (:balances projection)
                        (map (fn [[[item-id location] quantity]]
                               (merge (get-in projection [:items item-id])
                                      {:id       (row-id item-id location) :item-id  item-id
                                       :location location                  :quantity quantity})))
                        (sort-by (juxt #(str/lower-case (:name %)) :location :item-id))
                        vec)]
    (assoc projection :rows rows :semantic-version semantics/version)))


(defn- event-order
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [event]
  [(Instant/parse (:occurred-at event)) (:business-order event) (:event-index event 0) (:phase event 0)])


(defn item-history
  {:malli/schema [:=> [:cat :map :string [:maybe :boolean]] :map]}
  [projection item-id include-ancestors?]
  (let [touches?    (fn [lines] (some #(= item-id (:item-id %)) lines))
        own-records (filterv #(= item-id (:item-id %)) (:records projection))
        own-events  (filterv #(touches? (concat (:inputs %) (:outputs %))) (:lineage projection))
        origins     (filterv #(touches? (:outputs %)) own-events)
        ancestry    (if-not include-ancestors?
                      {}
                      (loop [pending origins found {}]
                        (if-let [event (peek pending)]
                          (if (contains? found (:operation-id event))
                            (recur (pop pending) found)
                            (let [parents (set (map :item-id (:inputs event)))
                                  earlier (filter #(and (neg? (compare (event-order %) (event-order event)))
                                                        (some (fn [line] (parents (:item-id line))) (:outputs %)))
                                                  (:lineage projection))]
                              (recur (into (pop pending) earlier) (assoc found (:operation-id event) event))))
                          found)))
        lineage     (vec (sort-by (juxt event-order :operation-id)
                                  (vals (merge (into {} (map (juxt :operation-id identity)) own-events) ancestry))))
        cutoffs     (reduce (fn [cutoffs event]
                              (reduce (fn [cutoffs line]
                                        (update cutoffs (:item-id line)
                                                (fn [previous]
                                                  (let [cutoff (event-order event)]
                                                    (if (or (nil? previous) (pos? (compare cutoff previous))) cutoff previous)))))
                                      cutoffs (:inputs event)))
                            {} (vals ancestry))
        timeline    (->> (:records projection)
                         (keep (fn [record]
                                 (if (= item-id (:item-id record))
                                   record
                                   (when-let [cutoff (get cutoffs (:item-id record))]
                                     (when-not (pos? (compare (event-order record) cutoff))
                                       (assoc record :timeline-until (str (first cutoff))))))))
                         vec)]
    {:item (get-in projection [:items item-id]) :records own-records :lineage lineage :timeline-records timeline}))


(defn item-audit
  "Keep every immutable revision of entities that ever mentioned the selected item."
  {:malli/schema [:=> [:cat [:vector :map] :string] :map]}
  [history item-id]
  (let [matching-lines (fn [record] (filterv #(= item-id (:item-id %)) (entry-lines (:entry record))))
        touched        (set (keep #(when (seq (matching-lines %)) (:entity-id %)) history))
        latest         (into {} (map (juxt :entity-id :revision)) history)
        records        (filterv #(touched (:entity-id %)) history)
        fallback       (get (reduce update-item {} (mapcat matching-lines records)) item-id)]
    {:item          fallback
     :audit-records (mapv (fn [record]
                            (let [lines   (matching-lines record)
                                  latest? (= (:revision record) (get latest (:entity-id record)))]
                              (assoc record :operation-id (:entity-id record)
                                     :occurred-at (get-in record [:entry :occurred-at])
                                     :status (cond (not latest?) :superseded (:cancelled? record) :cancelled :else :effective)
                                     :touches-item? (boolean (seq lines)) :item-lines lines))) records)}))

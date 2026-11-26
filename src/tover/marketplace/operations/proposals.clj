(ns tover.marketplace.operations.proposals
  "Board chapter 45 slice 2 · Propose the imported operation: from one synced source operation and the facts at
  hand, the pending inventory entry it becomes, or nil when it becomes none yet.

  Pure. `operation` is a `::sync/source-operations` row (lines and missing facts as JSON text). An operation is
  proposed only when its evidence names an inventory effect (the board's kind table), every line resolves to a
  confirmed product mapping, every warehouse it states has a confirmed mapping and at least one line is after its
  baseline. Missing facts (time, quantity, warehouse) are listed; they never block the proposal, and a line is
  never dropped, split or merged."
  (:require
    [clojure.string :as str]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.sync.ozon :as ozon])
  (:import
    [java.time Instant]))


(def Facts
  "`products` the connection's listings (identifiers parsed or JSON text), `mappings` product mappings and
  `warehouse-mappings` warehouse mappings by external id, `baselines` by baseline id, `operations` source operations
  by id (a return's original posting), `currency` the Workspace Currency."
  [:map
   [:products {:optional true} [:sequential :map]]
   [:mappings {:optional true} [:map-of :string :map]]
   [:warehouse-mappings {:optional true} [:map-of :string :map]]
   [:baselines {:optional true} [:map-of :string :map]]
   [:operations {:optional true} [:map-of :string :map]]
   [:currency {:optional true} [:maybe :string]]])


(def Operation
  [:map [:source-operation-id :string] [:connection-id :string] [:kind :string] [:status {:optional true} [:maybe :string]]])


(def ResolvedLine
  "`after-baseline`: the line counts from its baseline on; without an actual time it does until a member enters
  one (acceptance checks it again)."
  [:map
   [:line-key :string]
   [:external-product-id {:optional true} :string]
   [:mapping {:optional true} :map]
   [:stock-item-id {:optional true} :string]
   [:quantity {:optional true} :int]
   [:warehouse-id {:optional true} :string]
   [:unit-price {:optional true} :string]
   [:baseline {:optional true} :map]
   [:after-baseline :boolean]])


(def EntryLine
  [:map [:line-id :string] [:stock-item-id :string] [:quantity {:optional true} :int] [:warehouse-id {:optional true} :string]
   [:unit-price {:optional true} :string]])


(def Proposal
  [:map
   [:entry [:map [:kind [:enum "sale" "customer-return" "transfer" "write-off"]] [:occurred-at {:optional true} :string]
            [:from-warehouse-id {:optional true} :string] [:to-warehouse-id {:optional true} :string]
            [:reason {:optional true} :string] [:lines [:vector EntryLine]]]]
   [:missing [:vector :string]]
   [:findings [:vector :string]]])


(def disposed-reason "Disposed by Ozon")


(def compensated-finding "Compensated by Ozon")


(defn- json
  "Stored JSON text, or the value itself when already read."
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (if (string? value) (observations/read-json value) value))


(defn- status
  {:malli/schema [:=> [:cat :map] :string]}
  [operation]
  (str/lower-case (or (:status operation) "")))


(def ^:private received-statuses
  "Return statuses stating a receipt, by Ozon or by the seller."
  #{"returned" "returnedtoozon" "receivedbyseller"})


(defn- received-by-seller?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [operation]
  (= "receivedbyseller" (status operation)))


(def ^:private no-effect-statuses
  "Return statuses that never put stock back; every cancelled status is one too."
  #{"rejected" "utilized"})


(defn- return-counts?
  "Not cancelled, rejected or utilized, and received by its status, compensated (proposed with that finding) or
  finished otherwise, its missing facts then entered by a member. Initiation adds no stock."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:keys [terminal compensated] :as operation}]
  (let [s (status operation)]
    (boolean
      (and (not (contains? no-effect-statuses s))
           (not (str/includes? s "cancel"))
           (or (contains? received-statuses s) compensated terminal)))))


(defn entry-kind
  "Board 45 slice 2 · the kind table: what the operation's evidence becomes, or nil for none."
  {:malli/schema [:=> [:cat Operation] [:maybe :string]]}
  [{:keys [kind occurred-at handed-over-at disposed] :as operation}]
  (case kind
    ("fbs-posting" "fbo-posting") (when (or occurred-at handed-over-at (contains? ozon/after-handover (status operation))) "sale")
    ("return" "rfbs-return") (when (return-counts? operation) "customer-return")
    "supply" (when (= "completed" (status operation)) "transfer")
    "removal" (when (= "completed" (status operation)) (if disposed "write-off" "transfer"))
    nil))


(defn- confirmed-warehouse
  "The Tover warehouse an external warehouse is confirmed as, or nil."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :string]]}
  [facts external-id]
  (let [mapping (get-in facts [:warehouse-mappings external-id])]
    (when (= "confirmed" (:state mapping)) (:warehouse-id mapping))))


(defn- seller-warehouse
  "The connection's only confirmed seller warehouse (FBS or rFBS), else nil."
  {:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  [facts connection-id]
  (let [ids (distinct (keep #(when (and (= connection-id (:connection-id %)) (= "confirmed" (:state %))
                                        (#{"fbs" "rfbs"} (:warehouse-type %)))
                               (:warehouse-id %))
                            (vals (:warehouse-mappings facts))))]
    (when (= 1 (count ids)) (first ids))))


(defn- return-warehouse
  "Board kind table: an FBO return received by the seller takes the connection's only seller warehouse; an FBO or
  FBS return otherwise the original posting's warehouse. An rFBS return is not prefilled."
  {:malli/schema [:=> [:cat :map :map] [:maybe :string]]}
  [facts {:keys [kind return-schema related-operation connection-id] :as operation}]
  (when (= "return" kind)
    (if (and (= "fbo" return-schema) (received-by-seller? operation))
      (seller-warehouse facts connection-id)
      (some->> (get-in facts [:operations related-operation :from-warehouse]) (confirmed-warehouse facts)))))


(defn- stated-warehouses
  "External warehouses the operation itself states."
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [operation]
  (vec (distinct (filter some? (concat [(:from-warehouse operation) (:to-warehouse operation)]
                                       (map :warehouse (json (:lines operation))))))))


(defn- line-warehouse
  "The Tover warehouse a line of a sale, return or write-off affects; a transfer's lines take the entry's."
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :string]]}
  [facts operation line]
  (case (:kind operation)
    ("return" "rfbs-return") (return-warehouse facts operation)
    "supply" nil
    "removal" nil
    (confirmed-warehouse facts (or (:warehouse line) (:from-warehouse operation)))))


(defn- listing-index
  "Active identifiers of the connection's listings: {[type value] #{external-product-id}}."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [facts connection-id]
  (reduce (fn [index {:keys [external-product-id identifiers]}]
            (reduce (fn [index {:keys [type value active]}]
                      (cond-> index (not (false? active)) (update [type value] (fnil conj #{}) external-product-id)))
                    index (json identifiers)))
          {} (filter #(= connection-id (:connection-id %)) (:products facts))))


(defn- listing
  "By SKU, then product id, then article; an identifier two listings hold resolves to none."
  {:malli/schema [:=> [:cat :map [:maybe [:sequential :map]]] [:maybe :string]]}
  [index identifiers]
  (reduce (fn [_ type]
            (let [ids (into #{} (comp (filter #(= type (:type %))) (mapcat #(get index [type (:value %)]))) identifiers)]
              (case (count ids)
                0 nil
                1 (reduced (first ids))
                (reduced nil))))
          nil ["sku" "product-id" "article"]))


(defn whole-quantity
  "A line quantity as stated text: plain digits, at most nine as entries take them, above 0; else nil. Never
  truncated: a sign, a decimal point or an exponent is no whole quantity."
  {:malli/schema [:=> [:cat :any] [:maybe :int]]}
  [text]
  (when-let [digits (some->> text str str/trim (re-matches #"\d{1,9}"))]
    (let [n (parse-long digits)] (when (pos? n) n))))


(defn- unit-price
  "The stated price, only in the Workspace Currency."
  {:malli/schema [:=> [:cat :map :map] [:maybe :string]]}
  [facts {:keys [price price-currency]}]
  (when (and price (some? (:currency facts)) (= (:currency facts) price-currency))
    (try (.toPlainString (bigdec price)) (catch NumberFormatException _ nil))))


(defn- instant
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [text]
  (when (string? text) (try (Instant/parse text) (catch Exception _ nil))))


(defn- line-baseline
  "The latest baseline among the line's scopes: its warehouse, or a transfer's two, each of which needs one; without
  a warehouse, every scope of the item on the connection."
  {:malli/schema [:=> [:cat :map :string :string [:sequential [:maybe :string]]] [:maybe :map]]}
  [facts connection-id item warehouses]
  (let [named (remove nil? warehouses)
        found (if (seq named)
                (let [found (map #(provenance/baseline facts connection-id item %) named)]
                  (when (every? some? found) found))
                (filter #(and (= connection-id (:connection-id %)) (= item (:stock-item-id %))) (vals (:baselines facts))))]
    (last (sort-by (comp instant :instant) found))))


(defn- after?
  "Whether a line at `at` counts from `baseline`: strictly after its instant, or without an actual time yet."
  {:malli/schema [:=> [:cat [:maybe inst?] [:maybe :map]] :boolean]}
  [at baseline]
  (let [from (instant (:instant baseline))]
    (boolean (and from (or (nil? at) (.isAfter ^Instant at ^Instant from))))))


(defn resolve-lines
  "Every line of the operation, in order, with what resolves through confirmed mappings and baselines."
  {:malli/schema [:=> [:cat Facts Operation] [:vector ResolvedLine]]}
  [facts {:keys [connection-id occurred-at] :as operation}]
  (let [index (listing-index facts connection-id)
        at    (instant occurred-at)
        kind  (entry-kind operation)]
    (mapv (fn [{:keys [line-key identifiers quantity] :as line}]
            (let [listing-id (listing index identifiers)
                  mapping    (get-in facts [:mappings listing-id])
                  item       (when (= "confirmed" (:state mapping)) (:stock-item-id mapping))
                  warehouse  (line-warehouse facts operation line)
                  scopes     (if (#{"supply" "removal"} (:kind operation))
                               (map #(confirmed-warehouse facts %) [(:from-warehouse operation) (:to-warehouse operation)])
                               [warehouse])
                  baseline   (when item (line-baseline facts connection-id item scopes))
                  price      (when (#{"sale" "customer-return"} kind) (unit-price facts line))]
              (cond-> {:line-key line-key :after-baseline (after? at baseline)}
                listing-id (assoc :external-product-id listing-id)
                mapping (assoc :mapping mapping)
                item (assoc :stock-item-id item)
                (whole-quantity quantity) (assoc :quantity (whole-quantity quantity))
                warehouse (assoc :warehouse-id warehouse)
                price (assoc :unit-price price)
                baseline (assoc :baseline baseline))))
          (json (:lines operation)))))


(defn- missing-facts
  "The observation's missing facts, with a quantity that is not whole and a receiving warehouse the prefill gave."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :string]]}
  [operation lines]
  (let [stated   (vec (json (:missing operation)))
        returned (#{"return" "rfbs-return"} (:kind operation))]
    (vec (distinct (cond->> (concat stated (when (some (comp nil? :quantity) lines) ["line quantity"]))
                     (and returned (every? :warehouse-id lines)) (remove #{"receiving warehouse"})
                     (and returned (some (comp nil? :warehouse-id) lines) (not (some #{"receiving warehouse"} stated)))
                     (#(concat % ["receiving warehouse"])))))))


(defn proposal
  "Board `Propose Imported Operation` § entry: the pending entry the operation becomes with the facts still
  missing and its findings, or nil when it becomes none (no inventory effect, a missing match or no line after its
  baseline)."
  {:malli/schema [:=> [:cat Facts Operation] [:maybe Proposal]]}
  [facts {:keys [source-operation-id occurred-at from-warehouse to-warehouse] :as operation}]
  (let [kind  (entry-kind operation)
        lines (resolve-lines facts operation)]
    (when (and kind
               (seq lines)
               (every? :stock-item-id lines)
               (every? #(confirmed-warehouse facts %) (stated-warehouses operation))
               (some :after-baseline lines))
      (let [transfer? (= "transfer" kind)
            from      (when transfer? (confirmed-warehouse facts from-warehouse))
            to        (when transfer? (confirmed-warehouse facts to-warehouse))]
        {:entry    (cond-> {:kind  kind
                            :lines (mapv #(-> (select-keys % [:stock-item-id :quantity :warehouse-id :unit-price])
                                              (assoc :line-id (str source-operation-id "/" (:line-key %))))
                                         lines)}
                     occurred-at (assoc :occurred-at occurred-at)
                     from (assoc :from-warehouse-id from)
                     to (assoc :to-warehouse-id to)
                     (= "write-off" kind) (assoc :reason disposed-reason))
         :missing  (missing-facts operation lines)
         :findings (if (and (= "customer-return" kind) (:compensated operation)) [compensated-finding] [])}))))

(ns tover.marketplace.cutover.model
  "Board chapter 44 · Establish the marketplace cutover: cutover readiness and observed balances (slice 1) and
  baseline coverage (slice 5).

  A scope is one stock item in one warehouse of one connection, reached by a confirmed product mapping and a
  confirmed warehouse mapping. The scopes come from the figures of the connection's last finished
  `stock-by-warehouse` pass, plus every scope with a pending cutover entry or a baseline. A listing or external
  warehouse with stock but without a confirmed mapping is a row of its own that names the missing match.

  The observation is evidence: it is shown on every scope it speaks for, and prefills the quantity only on a
  ready scope of an FBO warehouse, and only when the instant is the pass's observation time. Tover's balance
  and the item's average are read at the instant from accepted history; a pair with any accepted record gets a
  correction, otherwise an opening."
  (:require
    [bel.core :refer [defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.workspaces.currency.model :as currency])
  (:import
    [java.math RoundingMode]
    [java.time Instant OffsetDateTime]
    [java.time.format DateTimeParseException]
    [java.time.temporal ChronoUnit]))


(def Ref connections/Ref)
(def At connections/At)


(def operation-domains
  "Sync domains that carry operations: the cutover is never before every one of them is covered."
  ["fbs-postings" "fbo-postings" "returns" "rfbs-returns" "supplies" "removals"])


(def Readiness
  [:enum "ready" "needs product match" "needs warehouse match" "pending" "has baseline"])


(def CostSource
  [:enum "Tover average" "Ozon"])


(def Scope
  "Board `Cutover Readiness and Observed Balances`, one row. A scope row has the stock item and warehouse; a
  `needs … match` row has the listing or external warehouse instead. Suggestions, `result` and the mapping
  revisions the proposal checks appear on ready rows only."
  [:map
   [:stock-item-id {:optional true} Ref] [:warehouse-id {:optional true} Ref]
   [:external-product-id {:optional true} :string] [:external-warehouse-id {:optional true} :string]
   [:readiness Readiness]
   [:observed-quantity {:optional true} :int] [:observed-at {:optional true} At]
   [:suggested-quantity {:optional true} :int]
   [:balance {:optional true} :int]
   [:result {:optional true} [:enum "opening" "correction"]]
   [:suggested-cost {:optional true} :string] [:cost-source {:optional true} CostSource]
   [:mapping-revisions {:optional true} [:map [:products [:map-of :string :int]] [:warehouses [:map-of :string :int]]]]])


(def Facts
  "What the reads need of one connection. `passes` are the finished stock-by-warehouse passes, newest first;
  `figures` the figures of the newest one (others are ignored); `history` the accepted records and lines."
  [:map
   [:currency :string]
   [:passes [:sequential :map]]
   [:figures [:sequential :map]]
   [:listings [:sequential :map]]
   [:product-mappings [:map-of :string :map]]
   [:warehouse-mappings [:map-of :string :map]]
   [:history {:optional true} [:map [:records [:sequential :map]] [:lines [:sequential :map]]]]
   [:provenance [:maybe :map]]])


;; Instants


(defn- instant
  "Instant or ISO text as a whole-second instant; nil when blank or unreadable."
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [value]
  (cond (inst? value) (.truncatedTo ^Instant value ChronoUnit/SECONDS)
        (and (string? value) (not (str/blank? value)))
        (try (.truncatedTo (.toInstant (OffsetDateTime/parse (str/trim value))) ChronoUnit/SECONDS)
             (catch DateTimeParseException _ nil))))


(defn default-instant
  "Where the instant starts: the observation time of the last finished `stock-by-warehouse` pass; nil without one."
  {:malli/schema [:=> [:cat [:sequential :map]] [:maybe :string]]}
  [passes]
  (:observed-at (first passes)))


(defn earliest
  "The earliest instant a cutover may have: the latest first covered instant of the operation domains (of the
  readable ones, when given). Nil while any of them lacks it, and then nothing can be proposed."
  {:malli/schema [:function [:=> [:cat [:maybe :map]] [:maybe :string]]
                  [:=> [:cat [:maybe :map] [:maybe [:set :string]]] [:maybe :string]]]}
  ([positions] (earliest positions nil))
  ([positions readable]
   (let [domains (cond->> operation-domains readable (filter readable))
         firsts  (map #(get-in positions [% :first-covered-at]) domains)]
     (when (and (seq domains) (every? instant firsts))
       (apply max-key (comp #(.getEpochSecond ^Instant %) instant) firsts)))))


;; Cost


(defn- positive-decimal
  {:malli/schema [:=> [:cat :any] [:maybe decimal?]]}
  [text]
  (when (and (string? text) (not (str/blank? text)))
    (try (let [n (bigdec (str/trim text))] (when (pos? n) n))
         (catch NumberFormatException _ nil))))


(defn- plain
  {:malli/schema [:=> [:cat decimal?] :string]}
  [n]
  (.toPlainString (.stripTrailingZeros ^BigDecimal n)))


(defn cost
  "Suggested cost and its source for an opening: Tover's average of the item; else the cost the seller entered at
  Ozon when every mapped listing that has one (above 0) states the same value in the workspace currency; else
  blank."
  {:malli/schema [:=> [:cat [:map [:average {:optional true} [:maybe decimal?]] [:listings {:optional true} [:sequential :map]]
                             [:currency :string]]]
                  [:tuple [:maybe :string] [:maybe CostSource]]]}
  [{:keys [average listings currency]}]
  (let [entered (keep #(when-let [n (positive-decimal (:cost %))] [n (:cost-currency %)]) listings)
        values  (distinct (map (comp #(.stripTrailingZeros ^BigDecimal %) first) entered))]
    (cond
      average [(plain average) "Tover average"]
      (and (seq entered) (every? #(= currency (second %)) entered) (= 1 (count values))) [(plain (first values)) "Ozon"]
      :else [nil nil])))


;; Accepted history at the instant


(defn- history-at
  "Balances at `at` (all accepted history without one) and the pairs with any accepted record."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe inst?]] [:map [:balances :map] [:recorded [:set :any]]]]}
  [{:keys [records lines] :or {records [] lines []}} at]
  (let [effective (valuation/effective records)
        current   (set (map :record-revision-id effective))
        kept      (set (keep #(when (or (nil? at) (not (.isAfter ^Instant (instant (:occurred-at %)) at))) (:record-id %)) effective))
        before    (filterv (comp kept :record-id) records)]
    {:balances (:balances (valuation/replay before lines))
     :recorded (set (keep #(when (current (:record-revision-id %)) [(:stock-item-id %) (:warehouse-id %)]) lines))}))


(defn- item-average
  "The item's average over its valued pairs with stock, weighted by quantity, to 4 places."
  {:malli/schema [:=> [:cat :map :string] [:maybe decimal?]]}
  [balances item]
  (let [held  (keep (fn [[[i _] {:keys [quantity average valued?]}]]
                      (when (and (= item i) valued? average (pos? quantity)) [quantity average]))
                    balances)
        units (reduce + 0 (map first held))]
    (when (pos? units)
      (.divide ^BigDecimal (reduce + 0M (map (fn [[q a]] (* (bigdec q) a)) held)) (bigdec units) 4 RoundingMode/HALF_EVEN))))


;; Scopes


(defn- confirmed
  "Confirmed mappings of the connection as {external id mapping}."
  {:malli/schema [:=> [:cat [:maybe :map] :string] :map]}
  [mappings connection-id]
  (into {} (filter (fn [[_ m]] (and (= "confirmed" (:state m)) (= connection-id (:connection-id m))))) mappings))


(defn- classify
  "Scopes {[item warehouse] {:listings {id mapping} :warehouses {id mapping}}} and the unmatched listings and
  external warehouses the last pass found stock for."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [passes figures product-mappings warehouse-mappings provenance]} connection-id]
  (let [run      (:run-id (first passes))
        figures  (filter #(and run (= run (:run-id %)) (:external-warehouse-id %)) figures)
        products (confirmed product-mappings connection-id)
        places   (confirmed warehouse-mappings connection-id)
        own      #(= connection-id (:connection-id %))
        pairs    (into (sorted-set)
                       (concat (for [{:keys [external-product-id external-warehouse-id]} figures
                                     :let [p (get products external-product-id) w (get places external-warehouse-id)]
                                     :when (and p w)]
                                 [(:stock-item-id p) (:warehouse-id w)])
                               (map (juxt :stock-item-id :warehouse-id) (filter own (vals (:cutover-entries provenance))))
                               (map (juxt :stock-item-id :warehouse-id) (filter own (vals (:baselines provenance))))))]
    {:scopes               (into (sorted-map)
                                 (for [[item warehouse :as k] pairs]
                                   [k {:listings   (into (sorted-map) (filter #(= item (:stock-item-id (val %)))) products)
                                       :warehouses (into (sorted-map) (filter #(= warehouse (:warehouse-id (val %)))) places)}]))
     :unmatched-products   (into (sorted-set) (remove products) (map :external-product-id figures))
     :unmatched-warehouses (into (sorted-set) (remove places) (map :external-warehouse-id figures))}))


(defn- readiness
  {:malli/schema [:=> [:cat :map :string :string :string] Readiness]}
  [provenance connection-id item warehouse]
  (cond (provenance/baseline provenance connection-id item warehouse) "has baseline"
        (some #(= [connection-id item warehouse] ((juxt :connection-id :stock-item-id :warehouse-id) %))
              (vals (:cutover-entries provenance)))
        "pending"
        :else "ready"))


(defn- observed
  "What the pass states of a scope: the figures of every mapped listing it speaks for in every mapped external
  warehouse, summed; nil when it speaks for none."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map] :map] [:maybe :int]]}
  [pass figures {:keys [listings warehouses]}]
  (let [stated (for [l (keys listings) w (keys warehouses)] (sync/stated-present pass figures l w))]
    (when (some some? stated) (reduce + 0 (remove nil? stated)))))


(defn scopes
  "Board `Cutover Readiness and Observed Balances` for one connection at `instant`: scopes by stock item and
  warehouse, then listings and external warehouses that need a match."
  {:malli/schema [:=> [:cat Facts [:map [:connection-id Ref] [:instant {:optional true} [:maybe [:or inst? :string]]]]]
                  [:vector Scope]]}
  [{:keys [passes figures listings provenance currency history] :as facts} {:keys [connection-id] :as input}]
  (let [at (instant (:instant input))
        pass (first passes)
        snapshot (and at pass (= at (instant (:observed-at pass))))
        {:keys [balances recorded]} (history-at history at)
        costs (into {} (map (juxt :external-product-id identity)) listings)
        {:keys [unmatched-products unmatched-warehouses] :as classified} (classify facts connection-id)]
    (into (vec (for [[[item warehouse] contributes] (:scopes classified)
                     :let [state (readiness provenance connection-id item warehouse)
                           quantity (observed pass figures contributes)
                           balance (get-in balances [[item warehouse] :quantity])
                           result (if (contains? recorded [item warehouse]) "correction" "opening")
                           fbo? (and (seq (:warehouses contributes)) (every? (comp warehouses/fbo? :warehouse-type) (vals (:warehouses contributes))))
                           [c from] (when (and (= "ready" state) (= "opening" result))
                                      (cost {:average  (item-average balances item)                :currency currency
                                             :listings (keep costs (keys (:listings contributes)))}))]]
                 (cond-> {:stock-item-id item :warehouse-id warehouse :readiness state}
                   quantity (assoc :observed-quantity quantity :observed-at (:observed-at pass))
                   balance (assoc :balance balance)
                   (= "ready" state) (assoc :result result
                                            :mapping-revisions {:products   (update-vals (:listings contributes) :mapping-revision)
                                                                :warehouses (update-vals (:warehouses contributes) :mapping-revision)})
                   (and (= "ready" state) snapshot fbo? quantity) (assoc :suggested-quantity quantity)
                   c (assoc :suggested-cost c :cost-source from))))
          (concat (map #(hash-map :external-product-id % :readiness "needs product match") unmatched-products)
                  (map #(hash-map :external-warehouse-id % :readiness "needs warehouse match") unmatched-warehouses)))))


;; Coverage


(defmulti waiting-operations
  "Imported operations each uncovered scope keeps in Review: {[stock-item-id warehouse-id] n}. Chapter 45 adds
  the `:imported-operations` method; until then nothing waits."
  (fn [_context _connection-id] :imported-operations))


(defmethod waiting-operations :default [_ _] {})


(def Coverage
  [:map
   [:connection-id Ref]
   [:baselines [:vector [:map [:stock-item-id Ref] [:warehouse-id Ref] [:instant At] [:record-id :string]]]]
   [:without-baseline [:vector [:map [:stock-item-id Ref] [:warehouse-id Ref] [:readiness [:enum "ready" "pending"]]
                                [:waiting-operations :int]]]]])


(defn coverage
  "Board `Baseline Coverage by Stock Item and Warehouse`: the connection's baselines with their instants, and its
  ready or pending scopes still without one, with the imported operations each keeps in Review."
  {:malli/schema [:=> [:cat :map :string [:maybe :map]] Coverage]}
  [{:keys [provenance] :as facts} connection-id waiting]
  {:connection-id    connection-id
   :baselines        (->> (vals (:baselines provenance))
                          (filter #(= connection-id (:connection-id %)))
                          (sort-by (juxt :stock-item-id :warehouse-id))
                          (mapv #(select-keys % [:stock-item-id :warehouse-id :instant :record-id])))
   :without-baseline (vec (for [[[item warehouse] _] (:scopes (classify facts connection-id))
                                :let                 [state (readiness provenance connection-id item warehouse)]
                                :when                (not= "has baseline" state)]
                            {:stock-item-id      item :warehouse-id warehouse :readiness state
                             :waiting-operations (get waiting [item warehouse] 0)}))})


;; Facts and reads


(defn- load-facts
  {:malli/schema [:=> [:cat :map :string :boolean] :map]}
  [context connection-id with-history?]
  (let [passes (sync/finished-passes context connection-id "stock-by-warehouse")]
    (cond-> {:currency           (currency/current (first (records/all-records context ::currency/workspace-currencies :currency-key)))
             :passes             passes
             :figures            (if-let [run (:run-id (first passes))] (sync/stock-figures context connection-id run) [])
             :listings           (runtime/coeffect ::products/listings context {:connection-id connection-id})
             :product-mappings   (runtime/coeffect ::products/mappings context {:connection-id connection-id})
             :warehouse-mappings (runtime/coeffect ::warehouses/mappings context {:connection-id connection-id})
             :provenance         (runtime/coeffect ::provenance/facts context {})}
      with-history? (assoc :history (runtime/coeffect :tover.inventory.acceptance.model/history context {})
                           :positions (runtime/coeffect ::sync/connection-positions context {:connection-id connection-id})
                           :readable (some-> (runtime/coeffect ::sync/connection context {:connection-id connection-id})
                                             :readable-domains connections/decode-domains set)))))


(defmethod runtime/coeffect ::facts
  [_ context {:keys [connection-id]}]
  (load-facts context connection-id true))


(defmethod runtime/coeffect ::waiting
  [_ context {:keys [connection-id]}]
  (waiting-operations context connection-id))


(def ReadinessAndBalances
  [:map [:connection-id Ref] [:instant {:optional true} At] [:default-instant {:optional true} At]
   [:earliest {:optional true} At] [:scopes [:vector Scope]]])


(defreadmodel cutover-readiness-and-observed-balances
  {:input  [:map [:connection-id Ref] [:instant {:optional true} At]]
   :output ReadinessAndBalances
   :policy ::records/workspace-member
   :cofx   [[:facts ::facts (fn [_ input] (select-keys input [:connection-id]))]]}
  [{:keys [facts]} {:keys [connection-id] :as input}]
  (let [start    (default-instant (:passes facts))
        at       (or (:instant input) start)
        first-at (earliest (:positions facts) (:readable facts))]
    (cond-> {:connection-id connection-id
             :scopes        (scopes facts {:connection-id connection-id :instant at})}
      at (assoc :instant at)
      start (assoc :default-instant start)
      first-at (assoc :earliest first-at))))


(defreadmodel baseline-coverage-by-stock-item-and-warehouse
  {:input  [:map [:connection-id Ref]]
   :output Coverage
   :policy ::records/workspace-member
   :cofx   [[:facts ::facts (fn [_ input] (select-keys input [:connection-id]))]
            [:waiting ::waiting (fn [_ input] (select-keys input [:connection-id]))]]}
  [{:keys [facts waiting]} {:keys [connection-id]}]
  (coverage facts connection-id waiting))


(defmethod connections/count-part :scopes-without-baseline
  ;; Board chapter 40: ready or pending scopes of the connection without a baseline.
  [_ context connection-id]
  (count (:without-baseline (coverage (load-facts context connection-id false) connection-id {}))))


(defmethod connections/count-part :baselines
  [_ context connection-id]
  (count (filter #(= connection-id (:connection-id %)) (vals (:baselines (runtime/coeffect ::provenance/facts context {}))))))

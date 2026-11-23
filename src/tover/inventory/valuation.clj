(ns tover.inventory.valuation
  "Board chapter 13 · Accepted Stock by Product and Warehouse, Valuation. Pure replay of effective accepted lines in
  business order: a moving weighted average per stock item and warehouse, kept to 4 decimals half-even.
  ponytail: generic line rule only; Transfer, Production and Defect record-level costing arrive with chapters 21–23."
  (:import
    [java.math RoundingMode]
    [java.util Currency]))


(def Balance
  [:map [:quantity :int] [:average [:maybe decimal?]] [:valued? :boolean]])


(defn- effective
  "Latest revision per record id; a cancelled latest revision removes the record."
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [records]
  (->> (vals (group-by :record-id records))
       (map #(apply max-key :revision %))
       (remove :cancelled)))


(defn business-order
  "Effective records sorted by occurrence; at equal instants openings first, then acceptance time and sequence."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [records]
  (vec (sort-by (juxt :occurred-at #(if (= "opening" (:kind %)) 0 1) :recorded-at :sequence) (effective records))))


(defn- average
  {:malli/schema [:=> [:cat decimal?] decimal?]}
  [x]
  (.setScale ^BigDecimal x 4 RoundingMode/HALF_EVEN))


(defn- apply-line
  "Generic line rule: a costed incoming line blends; an uncosted one enters at the average or, with none, makes the
  pair unvalued; an outgoing line leaves at the average. An unvalued pair becomes valued again only from zero."
  {:malli/schema [:=> [:cat [:maybe Balance] :map] Balance]}
  [{:keys [quantity valued?] avg :average :or {quantity 0 valued? true}} {:keys [quantity-change unit-cost]}]
  (let [after (+ quantity quantity-change)
        cost  (some-> unit-cost bigdec)]
    (cond
      (neg? quantity-change) {:quantity after :average avg :valued? valued?}
      (and cost (or valued? (zero? quantity)))
      {:quantity after :valued? true
       :average  (if (and avg (pos? quantity) valued?)
                   (.divide ^BigDecimal (+ (* (bigdec quantity) avg) (* (bigdec quantity-change) cost)) (bigdec after) 4 RoundingMode/HALF_EVEN)
                   (average cost))}
      (and valued? avg) {:quantity after :average avg :valued? true}
      :else {:quantity after :average avg :valued? false})))


(defn replay
  "Balances after every effective line, the steps that produced them, and the first negative balance if any."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] :map]}
  [records lines]
  (let [by-record (group-by :record-revision-id lines)]
    (reduce (fn [{:keys [balances] :as state} [{:keys [occurred-at]} {:keys [stock-item-id warehouse-id] :as line}]]
              (let [pair    [stock-item-id warehouse-id]
                    ;; A count (chapter 15) sets the balance: its change is derived here, never stored.
                    line    (if-some [counted (:counted-quantity line)]
                              (assoc line :quantity-change (- counted (get-in balances [pair :quantity] 0)))
                              line)
                    balance (apply-line (get balances pair) line)]
                (cond-> (-> state
                            (assoc-in [:balances pair] balance)
                            (update :steps conj (assoc (select-keys line [:record-revision-id :position :stock-item-id :warehouse-id
                                                                          :quantity-change :counted-quantity :unit-cost])
                                                       :balance-after (:quantity balance))))
                  (and (neg? (:quantity balance)) (not (:error state)))
                  (assoc :error {:type :negative-balance :stock-item-id stock-item-id :warehouse-id warehouse-id :occurred-at occurred-at}))))
            {:balances {} :steps [] :error nil}
            (for [record (business-order records)
                  line   (sort-by :position (get by-record (:record-revision-id record)))]
              [record line]))))


(defn value
  "Quantity × average when the pair is valued."
  {:malli/schema [:=> [:cat [:maybe Balance]] [:maybe decimal?]]}
  [{:keys [quantity average valued?]}]
  (when (and valued? average) (* (bigdec quantity) average)))


(defn display-amount
  "Amount at the currency's minor units, half-even."
  {:malli/schema [:=> [:cat decimal? :string] :string]}
  [amount currency-code]
  (.toPlainString (.setScale ^BigDecimal amount (.getDefaultFractionDigits (Currency/getInstance ^String currency-code))
                             RoundingMode/HALF_EVEN)))

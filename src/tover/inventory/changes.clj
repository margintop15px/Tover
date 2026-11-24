(ns tover.inventory.changes
  "Board chapter 15 · Stock item changes of Correction entries checked against the state they produce. Acceptance
  rejects on these problems; draft findings show them early."
  (:require
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.inventory.stock-items.model :as stock-items]))


(def Problem
  [:map [:entry-id :string] [:line-id {:optional true} [:maybe :string]] [:code :keyword]])


(defn text-key
  "Comparison key: letter case and spacing ignored."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [text]
  (when-not (str/blank? text) (records/name-key (str/replace (str/trim text) #"\s+" " "))))


(defn- by
  {:malli/schema [:=> [:cat :keyword [:sequential :map]] :map]}
  [k rows]
  (into {} (map (juxt k identity)) rows))


(def ^:private change-field
  "Stock item field each change key writes."
  {:variant-label  :variant-label :article          :article  :unit :unit :store-id :store-id :product-id :product-id :archive :status :restore :status
   :barcodes-added :barcodes      :barcodes-removed :barcodes})


(defn produce
  "Stock items and barcode carriers (`[item barcode]` pairs) as the selection leaves them: new items registered,
  then every change applied."
  {:malli/schema [:=> [:cat :map [:sequential :map] [:sequential :map]] :map]}
  [{:keys [stock-items barcodes]} lines changes]
  (let [fresh (for [l lines :when (:new-item? l)] (select-keys l [:stock-item-id :product-id :variant-label :article :unit :store-id]))
        items (reduce (fn [m {:keys [stock-item-id archive restore] :as c}]
                        (update m stock-item-id merge (select-keys c [:variant-label :article :unit :store-id :product-id])
                                (cond archive {:status "archived"} restore {:status "active"})))
                      (into (by :stock-item-id stock-items) (map (juxt :stock-item-id #(assoc % :status "active"))) fresh)
                      changes)
        codes (reduce (fn [s {:keys [stock-item-id barcodes-added barcodes-removed]}]
                        (-> (apply disj s (map #(vector stock-item-id %) barcodes-removed))
                            (into (map #(vector stock-item-id %)) barcodes-added)))
                      (into (set (map (juxt :stock-item-id :barcode) barcodes))
                            (for [l lines :when (:new-item? l) code (:barcodes l)] [(:stock-item-id l) code]))
                      changes)]
    {:items items :codes codes}))


(defn problems
  "Stock item changes checked against the state the acceptance produces, after the selection's counts apply."
  {:malli/schema [:=> [:cat :map [:sequential :map] [:sequential :map] :map] [:sequential Problem]]}
  [{:keys [products stock-items history entries] :as facts} lines changes after]
  (let [{:keys [items codes]} (produce facts lines changes)
        current               (by :stock-item-id stock-items)
        product               (by :product-id products)
        proposed              (into {} (keep #(when (:product-name %) [(:product-id %) (:product-name %)])) lines)
        names                 #(or (:name (product %)) (proposed %) %)
        locked                (set (map :stock-item-id (:lines history)))
        carriers              (reduce (fn [m [item code]] (update m code (fnil conj #{}) item)) {} codes)
        flag                  (fn [c code data] (merge {:entry-id (:entry-id c) :line-id nil :code code :stock-item-id (:stock-item-id c)} data))
        ;; Barcodes are written one value at a time: two entries conflict only on the same barcode.
        writes                (for [c     changes k (keys c) :let [field (change-field k)] :when field
                                    value (if (= :barcodes field) (get c k) [nil])]
                                [[(:stock-item-id c) field value] (:entry-id c)])]
    (concat
      (for [[[item field value] ws] (group-by first writes)
            :when                   (< 1 (count (distinct (map second ws))))
            entry-id                (distinct (map second ws))]
        (cond-> {:entry-id entry-id :line-id nil :code :field-conflict :stock-item-id item :field field}
          value (assoc :value value)))
      (mapcat
        (fn [{:keys [stock-item-id unit archive restore] :as c}]
          (let [was    (current stock-item-id)
                now    (items stock-item-id)
                moved? (contains? c :product-id)
                clash  (if moved? :move-conflict :uniqueness-conflict)
                others (fn [pred] (filter #(and (not= stock-item-id (:stock-item-id %)) (pred %)) (vals items)))
                owners (fn [ids] (disj (set (map (comp :product-id items) ids)) (:product-id now)))]
            (concat
              (when (and unit (not= unit (:unit was)))
                (cond (not (stock-items/unit? unit)) [(flag c :unknown-unit {:field :unit})]
                      ;; An item accepted before units were required takes its first unit; a set unit never changes.
                      (and (locked stock-item-id) (not (str/blank? (:unit was)))) [(flag c :unit-locked {:field :unit :unit unit})]))
              (when moved?
                (when (or (= (:product-id c) (:product-id was)) (not= "active" (:status (product (:product-id c)))))
                  [(flag c :move-conflict {:field :product-id :product-id (:product-id c)})]))
              (when (or moved? (contains? c :variant-label))
                (when (seq (others #(= [(:product-id %) (text-key (:variant-label %))] [(:product-id now) (text-key (:variant-label now))])))
                  [(flag c (if moved? :move-conflict :duplicate-variant) {:field :variant-label :variant-label (:variant-label now)})]))
              (when-let [k (and (or moved? (contains? c :article)) (text-key (:article now)))]
                (for [other (others #(and (= k (text-key (:article %))) (not= (:product-id now) (:product-id %))))]
                  (flag c clash {:field :article :value (:article now) :owner-product (names (:product-id other)) :other-item (:stock-item-id other)})))
              ;; One problem per value and per item of another product that keeps it.
              (distinct
                (for [code  (if moved? (keep #(when (= stock-item-id (first %)) (second %)) codes) (:barcodes-added c))
                      other (disj (carriers code) stock-item-id)
                      :let  [owner (:product-id (items other))]
                      :when (contains? (owners [other]) owner)]
                  (flag c clash (cond-> {:field :barcodes :value code :owner-product (names owner)}
                                  moved? (assoc :other-item other)))))
              (when (and restore (not= "active" (:status (product (:product-id now)))))
                [(flag c :restore-needs-product {:field :status :product-id (:product-id now)})])
              (when archive
                (concat
                  (for [[[item warehouse] balance] (:balances after)
                        :when (and (= item stock-item-id) (not (zero? (:quantity balance))))]
                    (flag c :archive-blocked {:field :status :warehouse-id warehouse}))
                  (for [{:keys [entry-id] :as other} entries
                        :when (and (not= entry-id (:entry-id c))
                                   (or (some #(= stock-item-id (:stock-item-id %)) (:lines other))
                                       (some #(= stock-item-id (:stock-item-id %)) (:changes other))))]
                    (flag c :archive-blocked {:field :status :other-entry-id entry-id})))))))
        changes))))

(ns tover.inventory.completion
  (:require
    [tover.inventory.ledger :as ledger]
    [tover.inventory.schema :as schema]
    [tover.shared.i18n :as i18n]))


(defn- problem
  {:malli/schema [:=> [:cat [:vector :any] [:=> [:cat] :any]] [:maybe :map]]}
  [path validate]
  (try
    (validate)
    nil
    (catch clojure.lang.ExceptionInfo error
      {:path           (if (and (empty? path) (:field (ex-data error))) [(:field (ex-data error))] path)
       :message-key    (or (:message-key (ex-data error)) (.getMessage error))
       :message-params (:message-params (ex-data error) {})
       :message        (i18n/error-message error)})))


(defn- line-errors
  {:malli/schema [:=> [:cat :any [:vector :any] :boolean :boolean [:set :string]] [:vector :map]]}
  [lines path zero? location? known-item-ids]
  (if-not (and (vector? lines) (seq lines) (<= (count lines) 500))
    [{:path path :message-key "Add between one and 500 item lines." :message (i18n/t "Add between one and 500 item lines.")}]
    (vec
      (mapcat
        (fn [[index line]]
          (let [at (conj path index)]
            (if-not (map? line)
              [{:path at :message-key "Each item line must be a map." :message (i18n/t "Each item line must be a map.")}]
              (remove nil?
                      [(problem (conj at :item-id) #(schema/text! (:item-id line) :item-id))
                       (when-not (contains? known-item-ids (:item-id line))
                         (problem (conj at :name) #(schema/text! (:name line) :name)))
                       (problem (conj at :quantity) #(schema/quantity! (:quantity line) zero?))
                       (when location?
                         (problem (conj at :location) #(schema/text! (:location line) :location)))]))))
        (map-indexed vector lines)))))


(defn check
  "Validate one complete entry without replaying stock or production availability."
  {:malli/schema [:function
                  [:=> [:cat :map] [:map [:valid? :boolean] [:errors [:vector :map]]]]
                  [:=> [:cat :map [:set :string]] [:map [:valid? :boolean] [:errors [:vector :map]]]]]}
  ([entry]
   (check entry #{}))

  ([entry known-item-ids]
   (let [entry     (if (= :purchase (:type entry)) (dissoc entry :receipts) entry)
         kind      (:kind entry :operation)
         type      (:type entry)
         required  (concat
                     (when-not (#{:revision :cancellation} kind) [:occurred-at])
                     (when (or (#{:revision :cancellation :correction} kind)
                               (#{:defect :write-off} type)) [:reason])
                     (when (#{:revision :cancellation} kind) [:target-id])
                     (when (#{:purchase :sale :payment} type) [:party])
                     (when (= :transfer type) [:from-location :to-location]))
         errors    (vec
                     (concat
                       (keep (fn [field]
                               (problem [field] #(if (= :occurred-at field)
                                                   (schema/timestamp! (get entry field))
                                                   (schema/text! (get entry field) field)))) required)
                       (when (and (= :operation kind) (not (schema/OperationTypes type)))
                         [{:path [:type] :message-key "Choose one of the eight operation types." :message (i18n/t "Choose one of the eight operation types.")}])
                       (when (and (#{:return :payment} type) (not (#{:incoming :outgoing} (:direction entry))))
                         [{:path [:direction] :message-key "Choose incoming or outgoing." :message (i18n/t "Choose incoming or outgoing.")}])
                       (when (= :payment type)
                         (remove nil?
                                 [(problem [:amount] #(when-not (pos? (schema/money! (:amount entry)))
                                                        (schema/fail! :validation "A payment amount must be positive." {})))
                                  (when-not (and (string? (:currency entry)) (re-matches #"[A-Z]{3}" (:currency entry)))
                                    {:path [:currency] :message-key "Use a three-letter uppercase currency code." :message (i18n/t "Use a three-letter uppercase currency code.")})]))
                       (cond
                         (= :revision kind) (map #(update % :path (partial into [:replacement]))
                                                 (:errors (check (or (:replacement entry) {}) known-item-ids)))
                         (= :cancellation kind) []
                         (= :production type) (concat (line-errors (:inputs entry) [:inputs] false true known-item-ids)
                                                      (line-errors (:outputs entry) [:outputs] false true known-item-ids))
                         (= :payment type) []
                         :else (line-errors (:lines entry) [:lines] (boolean (#{:opening :correction} kind))
                                            (not= :transfer type) known-item-ids))))
         remaining (when (empty? errors)
                     (problem [] #(ledger/normalize-entry entry)))
         errors    (cond-> errors remaining (conj remaining))]
     {:valid? (empty? errors) :errors errors})))

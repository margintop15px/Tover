(ns tover.inventory.schema
  (:require
    [clojure.string :as str]
    [tover.shared.i18n :as i18n])
  (:import
    [java.math BigDecimal]
    [java.time Instant LocalDate ZoneOffset]))


(def OperationTypes
  #{:purchase :return :transfer :production :defect :sale :write-off :payment})


(def Entry
  [:map
   [:kind {:optional true} [:enum :operation :opening :correction :revision :cancellation]]
   [:type {:optional true} (into [:enum] (sort OperationTypes))]])


(defn fail!
  {:malli/schema [:=> [:cat :keyword :string :map] :any]}
  [type message data]
  (throw (ex-info message (assoc data :type type))))


(defn text!
  {:malli/schema [:=> [:cat :any :keyword] :string]}
  [value field]
  (when-not (and (string? value) (not (str/blank? value)) (<= (count value) 500))
    (fail! :validation (str (name field) " is required (maximum 500 characters).") {:field          field :message-key "{field} is required (maximum 500 characters)."
                                                                                    :message-params {:field (if (= "en" i18n/*locale*) (name field)
                                                                                                                (i18n/t (str/capitalize (str/replace (name field) "-" " "))))}}))
  value)


(defn quantity!
  {:malli/schema [:=> [:cat :any :boolean] :int]}
  [value zero?]
  (let [quantity (if (and (string? value) (re-matches #"\d+" value))
                   (try (Long/parseLong value) (catch NumberFormatException _ nil))
                   value)]
    (when-not (and (integer? quantity) (<= (if zero? 0 1) quantity Long/MAX_VALUE))
      (fail! :validation "Enter a whole-unit quantity in the supported range." {:field :quantity :value value}))
    quantity))


(defn timestamp!
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (try
    (str (if (and (string? value) (re-matches #"\d{4}-\d{2}-\d{2}" value))
           (.toInstant (.atStartOfDay (LocalDate/parse value) ZoneOffset/UTC))
           (Instant/parse value)))
    (catch Exception _
      (fail! :validation "Enter an occurrence date or an ISO timestamp." {:field :occurred-at :value value}))))


(defn money!
  {:malli/schema [:=> [:cat :any] [:fn decimal?]]}
  [value]
  (let [amount (cond
                 (instance? BigDecimal value) value
                 (integer? value) (bigdec value)
                 (and (string? value) (<= (count value) 100)
                      (re-matches #"\d+(?:\.\d+)?" value)) (bigdec value)
                 :else nil)]
    (when-not (and amount (not (neg? amount)) (<= (.precision ^BigDecimal amount) 38)
                   (<= (.scale ^BigDecimal amount) 12))
      (fail! :validation "Enter an exact nonnegative decimal amount." {:field :amount :value value}))
    amount))


(def Id
  [:and :string [:fn #(and (not (str/blank? %)) (<= (count %) 500))]])


(def Selection
  [:vector [:map [:id Id] [:revision :int]]])


(def ReviewRequest
  [:map [:selected Selection] [:expected-ledger-revision :int]])


(def SubmitRequest
  [:map [:selected Selection] [:expected-ledger-revision :int] [:actor Id] [:command-id Id]])


(def SaveRequest
  [:map [:entry :map] [:actor Id] [:id {:optional true} Id] [:expected-revision {:optional true} :int]])


(def DiscardRequest
  [:map [:id Id] [:expected-revision :int]])


(def CompleteRequest
  [:map
   [:id Id]
   [:expected-revision :int]
   [:actor Id]
   [:command-id Id]
   [:entry {:optional true} :map]])


(def Draft
  [:map
   [:id Id]
   [:revision :int]
   [:entry :map]
   [:actor Id]
   [:saved-at :string]
   [:phase {:optional true} [:enum :composing :pending]]
   [:owner-id {:optional true} Id]])


(def Snapshot
  [:map
   [:ledger-revision :int]
   [:drafts [:vector Draft]]
   [:compositions [:vector Draft]]
   [:items [:map-of :string :map]]
   [:rows [:vector :map]]
   [:operations [:vector :map]]
   [:history [:vector :map]]])


(def Preview
  [:map [:valid? :boolean] [:errors [:vector :map]] [:selected Selection] [:expected-ledger-revision :int]])


(def Receipt
  [:map [:command-id Id] [:ledger-revision :int] [:accepted [:vector :map]]
   [:submitted-ids [:vector Id]] [:recorded-at :string] [:actor Id]])

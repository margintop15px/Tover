(ns tover.workspaces.currency.model
  "Board chapter 04a · Workspace currency. One currency per workspace, EUR until an owner changes it. Every inventory
  money amount is in it; a change relabels existing amounts and converts nothing. Kept in the catalogue store so
  review and acceptance read it in the same transaction as the records."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.catalogues.records :as records])
  (:import
    [java.util Currency]))


(def default-currency "EUR")
(def currency-key "currency")


(def Code
  "ISO 4217 code; the command checks it against the JVM's list."
  [:string {:min 3 :max 3}])


(def CurrencyChanged
  [:map
   [:currency-key :string]
   [:currency Code]
   [:previous-currency Code]
   [:changed-by :uuid]
   [:changed-at validation/Instant]])


(defevent workspace-currency-changed {:schema CurrencyChanged})


(defmodel workspace-currencies
  {:id     :currency-key
   :store  :catalogue
   :schema CurrencyChanged
   :policy ::records/workspace-member
   :events {::workspace-currency-changed {:set [:currency :previous-currency :changed-by :changed-at]}}})


(defn current
  {:malli/schema [:=> [:cat [:maybe :map]] :string]}
  [row]
  (or (:currency row) default-currency))


(def codes
  "ISO 4217 codes the JVM knows, sorted."
  (delay (vec (sort (map #(.getCurrencyCode ^Currency %) (Currency/getAvailableCurrencies))))))


(defn- owner?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{{:keys [memberships]} :principal scope :scope}]
  (boolean (some #(and (= (:workspace-id scope) (:workspace-id %)) (= :owner (keyword (:role %)))) memberships)))


(defmethod runtime/policy ::owner
  [_ context _]
  (owner? context))


(defmethod runtime/coeffect ::can-change?
  [_ context _]
  (owner? context))


(defn- row-key
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ _]
  {:currency-key currency-key})


(def row-fact
  "Coeffect for commands and read models that need the workspace currency row."
  [:currency-row ::workspace-currencies row-key])


(defn fail!
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [type message data]
  (throw (ex-info message (assoc data :type type))))


(def ChangeCurrency
  [:map [:workspace-id :uuid] [:currency [:string {:max 10}]]])


(defcommand change-workspace-currency
  {:input ChangeCurrency :policy ::owner :cofx [row-fact] :emits #{::workspace-currency-changed}}
  [{:keys [currency-row scope actor now]} {:keys [workspace-id currency]}]
  (when-not (= workspace-id (:workspace-id scope))
    (fail! :not-permitted "This workspace is no longer selected." {}))
  (let [code     (str/upper-case (str/trim currency))
        previous (current currency-row)]
    (when-not (some #{code} @codes)
      (fail! :unknown-currency "Choose a currency from the list." {:attempted currency}))
    (if (= code previous)
      {}
      {:events [{:type ::workspace-currency-changed
                 :data {:currency-key currency-key :currency   code :previous-currency previous
                        :changed-by   actor        :changed-at now}}]})))


(defreadmodel workspace-currency
  {:input  [:map]
   :output [:map [:currency :string] [:can-change? :boolean]]
   :policy ::records/workspace-member
   :cofx   [row-fact [:can-change? ::can-change? (constantly {})]]}
  [{:keys [currency-row can-change?]} _]
  {:currency (current currency-row) :can-change? can-change?})

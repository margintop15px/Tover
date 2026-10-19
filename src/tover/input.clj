(ns tover.input
  (:require
    [bel.validation :as validation]
    [clojure.string :as str]
    [malli.core :as m]
    [malli.transform :as mt]
    [tover.schema :as schema])
  (:import [clojure.lang ExceptionInfo]))


(def ^:private QuantityText
  [:re #"^[0-9]+\z"])


(def ^:private SetIntent
  [:re #"(?i)^set(?:\s|$)"])


(def ^:private command-pattern
  #"(?i)^set\s+(\S(?:.*?\S)?)\s+to\s+(\S+)\z")


(def ^:private CommandText
  [:re command-pattern])


(def ^:private Command
  [:tuple {:decode/string
           (fn [text]
             (when-not (= ::m/invalid (m/parse CommandText text))
               (vec (rest (re-matches command-pattern text)))))}
   :string :string])


(defn quantity
  "Decode a whole nonnegative quantity from text through Malli."
  {:malli/schema [:=> [:cat :any] schema/Quantity]}
  [text]
  (validation/check! QuantityText text)
  (validation/decode! schema/Quantity text (mt/string-transformer)))


(defn- normalized
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (str/lower-case (str/trim (str value))))


(defn- adjustment
  {:malli/schema [:=> [:cat schema/Rows :string :string] schema/Interpretation]}
  [rows selector text]
  (try
    (let [after (quantity text)
          selector (normalized selector)
          exact (filterv (fn [row]
                           (some #(= selector (normalized (get row %)))
                                 [:id :article :name]))
                         rows)
          matches (if (seq exact)
                    exact
                    (filterv (fn [row]
                               (some #(str/includes? (normalized (get row %)) selector)
                                     [:article :name]))
                             rows))]
      (case (count matches)
        0 {:type :error :message (str "No goods match \"" selector "\".")}
        1 {:type :adjustment :row (first matches) :after after}
        {:type :choose :matches matches :after after}))
    (catch ExceptionInfo error
      (if (= :validation (:type (ex-data error)))
        {:type    :error
         :message "Quantity must be a whole number between 0 and 9223372036854775807."}
        (throw error)))))


(defn interpret
  "Search stock, or interpret `set <article or goods name> to <integer>`."
  {:malli/schema [:=> [:cat schema/Rows :any] schema/Interpretation]}
  [rows text]
  (try
    (validation/check! schema/InputText text)
    (let [text (str/trim (or text ""))]
      (if (m/validate SetIntent text)
        (let [[selector quantity] (try
                                    (validation/decode! Command text (mt/string-transformer))
                                    (catch ExceptionInfo error
                                      (when-not (= :validation (:type (ex-data error)))
                                        (throw error))))]
          (if selector
            (adjustment rows selector quantity)
            {:type :error :message "Use: set <article or goods name> to <integer>."}))
        (let [query (normalized text)]
          {:type  :search
           :query text
           :rows  (if (str/blank? query)
                    (vec rows)
                    (filterv (fn [row]
                               (some #(str/includes? (normalized (get row %)) query)
                                     [:name :article :location]))
                             rows))})))
    (catch ExceptionInfo error
      (if (= :validation (:type (ex-data error)))
        {:type    :error
         :message (if (m/validate [:maybe :string] text)
                    "Use at most 200 characters."
                    "Input must be text.")}
        (throw error)))))

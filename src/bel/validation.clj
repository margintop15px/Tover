(ns bel.validation
  (:require
    [charred.api :as charred]
    [clojure.string :as str]
    [malli.core :as m]
    [malli.error :as error]
    [malli.transform :as mt]))


(def input-transformer
  (mt/transformer
    (mt/string-transformer)
    (mt/default-value-transformer)))


(def signal-transformer
  (mt/transformer
    (mt/json-transformer)
    {:name :wire :encoders {:int str}}))


(defn check!
  "Return valid data or throw a validation error with Malli explanations."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [schema data]
  (if-let [explanation (m/explain schema data)]
    (let [errors   (error/humanize explanation)
          messages (if (map? errors)
                     (map (fn [[field messages]]
                            (str (if (ident? field) (name field) field) ": " (str/join ", " messages)))
                          errors)
                     errors)]
      (throw (ex-info (str/join "; " messages)
                      {:type :validation :errors errors :explanation explanation})))
    data))


(defn decode!
  "Decode through a Malli transformer and validate the decoded value."
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [schema data transformer]
  (check! schema (m/decode schema data transformer)))


(defn encode!
  "Validate data before encoding through a Malli transformer."
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [schema data transformer]
  (m/encode schema (check! schema data) transformer))


(defn decode-input
  {:malli/schema [:=> [:cat :any :any] :any]}
  [schema data]
  (decode! schema data input-transformer))


(defn encode-data
  "Validate data and encode typed integers as decimal strings for browser payloads."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [schema data]
  (encode! schema data signal-transformer))


(defn signal-json
  {:malli/schema [:=> [:cat :any :map] :string]}
  [schema data]
  (charred/write-json-str (encode-data schema data)))

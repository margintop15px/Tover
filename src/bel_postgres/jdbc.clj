(ns bel-postgres.jdbc
  "Native JDBC values and HoneySQL execution shared by PostgreSQL adapters."
  (:require
    [charred.api :as json]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.date-time :as date-time]
    [next.jdbc.prepare :as p]
    [next.jdbc.result-set :as rs])
  (:import [org.postgresql.util PGobject]))


(date-time/read-as-instant)


(defn jsonb
  "Explicit JSONB parameter; ordinary Clojure maps retain normal JDBC behavior."
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (reify p/SettableParameter
    (set-parameter [_ statement index]
      (.setObject statement index
                  (doto (PGobject.)
                    (.setType "jsonb")
                    (.setValue (json/write-json-str value)))))))


(defn- read-pg-object
  {:malli/schema [:=> [:cat [:fn #(instance? PGobject %)]] :any]}
  [^PGobject value]
  (if (contains? #{"json" "jsonb"} (.getType value))
    (some-> (.getValue value) (json/read-json :key-fn keyword))
    value))


(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [value _] (read-pg-object value))
  (read-column-by-index [value _ _] (read-pg-object value)))


(defn execute-one!
  {:malli/schema [:=> [:cat :any :map] [:maybe :map]]}
  [tx query]
  (jdbc/execute-one! tx (sql/format query {:quoted true}) {:builder-fn rs/as-unqualified-lower-maps}))


(defn selection
  {:malli/schema [:=> [:cat :map] [:vector [:tuple :keyword :keyword]]]}
  [columns]
  (mapv (fn [[field column]] [column field]) columns))

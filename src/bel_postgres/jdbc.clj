(ns bel-postgres.jdbc
  "Native JDBC values and HoneySQL execution shared by PostgreSQL adapters."
  (:require
    [charred.api :as json]
    [hikari-cp.core :as hikari]
    [honey.sql :as sql]
    [integrant.core :as ig]
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


(defn execute!
  {:malli/schema [:=> [:cat :any :map] [:vector :map]]}
  [tx query]
  (jdbc/execute! tx (sql/format query {:quoted true}) {:builder-fn rs/as-unqualified-lower-maps}))


(defn selection
  {:malli/schema [:=> [:cat :map] [:vector [:tuple :keyword :keyword]]]}
  [columns]
  (mapv (fn [[field column]] [column field]) columns))


(defn datasource
  "One application-owned pool; callers borrow connections for bounded operations."
  {:malli/schema [:=> [:cat [:map [:jdbc-url :string] [:user :string] [:password :string]]] :any]}
  [{:keys [jdbc-url user password pool-name] :or {pool-name "bel"}}]
  (hikari/make-datasource
    {:jdbc-url           jdbc-url  :username           user :password          password
     :pool-name          pool-name :minimum-idle       2    :maximum-pool-size 8
     :connection-timeout 2000      :validation-timeout 1000
     :connect-timeout    15        :socket-timeout     15}))


(defn close!
  {:malli/schema [:=> [:cat :any] :nil]}
  [datasource]
  (hikari/close-datasource datasource))


(defmethod ig/init-key ::datasource [_ options] (datasource options))
(defmethod ig/halt-key! ::datasource [_ pool] (close! pool))

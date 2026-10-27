(ns tover.shared.jdbc
  (:require
    [com.brunobonacci.mulog :as mu]
    [hikari-cp.core :as hikari]
    [next.jdbc :as jdbc]))


(defn datasource
  "One application-owned pool; callers borrow connections for bounded operations."
  {:malli/schema [:=> [:cat :map] :any]}
  [{:keys [jdbc-url user password]}]
  (hikari/make-datasource
    {:jdbc-url           jdbc-url         :username           user :password          password
     :pool-name          "tover-identity" :minimum-idle       2    :maximum-pool-size 8
     :connection-timeout 2000             :validation-timeout 1000
     :connect-timeout    15               :socket-timeout     15}))


(defn close!
  {:malli/schema [:=> [:cat :any] :nil]}
  [datasource]
  (hikari/close-datasource datasource))


(defn connection
  {:malli/schema [:=> [:cat :any] :any]}
  [datasource]
  (mu/trace ::connection {:pairs []}
    (jdbc/get-connection datasource)))

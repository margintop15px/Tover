(ns tover.shared.jdbc
  (:require
    [com.brunobonacci.mulog :as mu]
    [next.jdbc :as jdbc]))


(defn connection
  {:malli/schema [:=> [:cat :any] :any]}
  [datasource]
  (mu/trace ::connection {:pairs []}
    (jdbc/get-connection datasource)))

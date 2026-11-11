(ns bel.journal
  "Shared journal operations, independent of the owning domain store."
  (:require [bel.store :as store]))


(defmulti read-receipt (fn [context _command-id] (get-in context [:bel/runtime :journal :adapter])))
(defmulti write-batch! (fn [context _delivery] (get-in context [:bel/runtime :journal :adapter])))
(defmulti read-history (fn [context] (get-in context [:bel/runtime :journal :adapter])))


(defn receipt
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [context command-id]
  (store/transact context (get-in context [:bel/runtime :journal]) :read-committed
                  #(read-receipt % command-id)))


(defn deliver!
  "Commit the complete recorded batch, or join the domain's existing journal transaction."
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [context delivery]
  (store/transact (assoc context :scope (get-in delivery [:request :scope]))
                  (get-in context [:bel/runtime :journal]) :read-committed
                  #(write-batch! % delivery))
  nil)


(defn history
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [context]
  (store/transact context (get-in context [:bel/runtime :journal]) :read-committed #(read-history %)))

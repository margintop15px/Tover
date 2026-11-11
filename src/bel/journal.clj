(ns bel.journal
  "Shared journal operations on the system's journal store, independent of the owning domain store."
  (:require [bel.store :as store]))


(defn- journal-call
  {:malli/schema [:=> [:cat :map :map [:fn fn?]] :any]}
  [context scope f]
  (let [key (get-in context [:bel/system :journal])]
    (store/with-store context key :read-committed
      #(f (store/store % key) (:tx %) (store/store-scope scope)))))


(defn receipt
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [context command-id]
  (journal-call context (:scope context) #(store/-receipt %1 %2 %3 command-id)))


(defn deliver!
  "Commit the complete recorded batch, or join the domain's existing journal transaction."
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [context delivery]
  (journal-call context (get-in delivery [:request :scope]) #(store/-append! %1 %2 %3 delivery))
  nil)


(defn history
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [context]
  (vec (journal-call context (:scope context) store/-history)))

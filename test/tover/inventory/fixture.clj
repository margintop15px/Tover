(ns tover.inventory.fixture
  (:require
    [tover.inventory.ledger :as ledger]
    [tover.inventory.semantics :as semantics]
    [tover.inventory.store :as store]
    [tover.shared.rdf :as rdf]))


(defn accept-history!
  "Seed immutable historical facts independently of current draft submission rules."
  {:malli/schema [:=> [:cat rdf/Repository :map [:vector :map]] :map]}
  [repo scope entries]
  (rdf/with-transaction [conn {:repo repo :level :serializable}]
    (let [current  (#'store/state conn scope)
          sequence (reduce max 0 (map :sequence (:history (store/snapshot repo scope))))]
      (doseq [[index entry] (map-indexed vector entries)
              :let          [position (+ sequence index 1)
                             entry (ledger/normalize-entry entry)]]
        (store/write-record! conn scope
                             {:id               (str (random-uuid))           :entity-id (str (random-uuid)) :revision       1
                              :semantic-version semantics/version             :sequence  position            :business-order position
                              :recorded-at      (str (java.time.Instant/now)) :actor     "historical-owner"  :proposed-by    "historical-owner"
                              :created-by       "historical-owner"            :reason    (:reason entry)
                              :cancelled?       false :change-kind (:kind entry) :entry entry}))
      (#'store/save-light-state! conn scope (update current :ledger-revision inc))))
  (store/rebuild! repo scope))

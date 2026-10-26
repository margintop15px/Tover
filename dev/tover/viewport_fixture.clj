(ns tover.viewport-fixture
  "Deterministic large read fixtures for isolated viewport browser tests only."
  (:require
    [tover.inventory.store :as store]
    [tover.shared.rdf :as rdf])
  (:import
    [java.util UUID]))


(def workspace-id
  #uuid "00000000-0000-4000-8000-000000000101")


(def other-workspace-id
  #uuid "00000000-0000-4000-8000-000000000102")


(def actor
  "00000000-0000-4000-8000-000000000001")


(defn- accept!
  {:malli/schema [:=> [:cat rdf/Repository :uuid [:vector :map]] :map]}
  [repo workspace entries]
  (let [scope  {:workspace-id workspace}
        _      (assert (zero? (:ledger-revision (store/snapshot repo scope)))
                       "Viewport fixtures require a new isolated inventory store")
        drafts (mapv #(store/save-entry! repo scope {:id (:fixture-id %) :entry (dissoc % :fixture-id) :actor actor}) entries)]
    (store/submit! repo scope {:selected                 (mapv #(select-keys % [:id :revision]) drafts)
                               :expected-ledger-revision 0 :actor actor
                               :command-id               (str (UUID/randomUUID))})))


(defn seed!
  "Seed through real durable acceptance; never reset or replace an existing store."
  {:malli/schema [:=> [:cat rdf/Repository] :map]}
  [repo]
  (let [opening    {:fixture-id "viewport-opening" :kind :opening :occurred-at "2026-10-01T12:00:00Z"
                    :reason     "Viewport browser fixture opening inventory"
                    :lines      (mapv (fn [index]
                                        {:item-id (format "viewport-item-%03d" index)
                                         :name    (format "Viewport goods %03d" index)
                                         :article (format "ARTICLE-%03d" index)        :barcode (format "BARCODE-%03d" index)
                                         :unit    "pcs" :location (if (even? index) "Shelf A" "Shelf B") :quantity 20})
                                      (range 260))}
        production {:fixture-id  "viewport-production" :kind    :operation                   :type :production
                    :occurred-at "2026-10-02T12:00:00Z" :reason "Viewport merged production"
                    :inputs      [{:item-id "viewport-item-000" :name "Viewport goods 000" :location "Shelf A" :quantity 2}
                                  {:item-id "viewport-item-001" :name "Viewport goods 001" :location "Shelf B" :quantity 1}]
                    :outputs     [{:item-id "viewport-produced" :name "Viewport produced" :unit "pcs" :location "Shelf A" :quantity 1}]}
        payments   (mapv (fn [index]
                           {:fixture-id  (format "viewport-payment-%03d" index) :kind :operation :type   :payment
                            :occurred-at (format "2026-10-%02dT12:%02d:00Z" (+ 3 (quot index 60)) (mod index 60))
                            :direction   :incoming :party (format "Viewport party %03d" index)   :amount "10.25"  :currency "EUR"
                            :reason      "Viewport payment fixture"})
                         (range 230))]
    {:workspace       (accept! repo workspace-id (into [opening production] payments))
     :other-workspace (accept! repo other-workspace-id
                               [{:fixture-id "viewport-other-opening" :kind :opening :occurred-at "2026-10-01"
                                 :reason     "Other tenant fixture"
                                 :lines      [{:item-id "private-item" :name "Other tenant private goods" :location "Private shelf" :quantity 7}]}])}))

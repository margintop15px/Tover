(ns tover.marketplace.transit-test
  "Board chapter 46 slice 1 · Read shipments in transit: postings and returns after their cutover, each line with
  its state, whether it counts in the totals, and its actual and observation times. Transit never holds stock: a
  Sale deducts once, delivery deducts nothing more, an accepted Customer return restores once."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [are deftest is testing use-fixtures]]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.marketplace.corrections-test :as corrections-test]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.operations.model :as operations]
    [tover.marketplace.products.guard :as guard]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.transit.model :as transit]
    [tover.support :as support])
  (:import
    [java.time Duration Instant]))


(def now
  "The instant every transit test runs at: five days after the observations, well inside `stale-after`."
  (Instant/parse "2026-11-25T10:00:00Z"))


(defn pinned-clock
  "Fixture: `::acceptance/now` answers `now`, so a test's states never depend on the day it runs."
  {:malli/schema [:=> [:cat fn?] :any]}
  [f]
  (let [previous (get-method runtime/coeffect ::acceptance/now)]
    (.addMethod ^clojure.lang.MultiFn runtime/coeffect ::acceptance/now (fn [& _] now))
    (try (f) (finally (.addMethod ^clojure.lang.MultiFn runtime/coeffect ::acceptance/now previous)))))


(use-fixtures :each pinned-clock bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                          'tover.marketplace.products.guard 'tover.marketplace.warehouses.model 'tover.marketplace.provenance
                          'tover.marketplace.operations.model 'tover.marketplace.operations.corrections
                          'tover.marketplace.transit.model 'tover.workspaces.currency.model])
    (f))
  guard-test/unit-authority)


(def workspace (:workspace-id support/scope))
(def c corrections-test/c)
(def sale corrections-test/sale)
(def return (str c ":return:R2"))
(def observed "2026-11-20T10:00:00Z")

(def setup! #'corrections-test/setup!)
(def posting #'corrections-test/posting)
(def return-of #'corrections-test/return-of)
(def accept-all! #'corrections-test/accept-all!)
(def save! #'corrections-test/save!)


(defn- read-transit
  {:malli/schema [:=> [:cat :map] :map]}
  [ctx]
  (runtime/read-model ctx ::transit/shipments-in-transit {:connection-id c}))


(defn- rows
  "Shipments as [operation line item quantity state counted]."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [read]
  (mapv (juxt :source-operation-id :line-key :stock-item-id :quantity :state :counted) (:shipments read)))


(defn- accepted-sale!
  "P1 observed with `fields`, proposed and accepted automatically."
  {:malli/schema [:=> [:cat :map [:* :any]] :nil]}
  [{:keys [observe! intake! automatic! accepted]} & fields]
  (observe! (apply posting fields))
  (intake!)
  (is (= 1 (automatic!)))
  (is (some? (accepted sale)))
  nil)


(deftest posting-awaiting-dispatch-time
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! balance]} (setup! repo)]
        (testing "Given posting P with 2 units whose pending Sale misses \"actual time\", W showing 10"
          (observe! (posting :status "delivering" :terminal false :occurred-at nil :quantity "2" :complete false :missing ["actual time"]))
          (is (= 1 (intake!)))
          (let [read (read-transit ctx)]
            (testing "then P is listed as awaiting facts, out of the totals"
              (is (= [[sale "1" "red" 2 "awaiting facts" false]] (rows read)))
              (is (= ["actual time"] (:missing (first (:shipments read)))))
              (is (= "waiting for Ozon" (:next-step (first (:shipments read)))))
              (is (= {} (:totals read))))
            (testing "and W shows 10"
              (is (= 10 (balance "red" "home"))))))))))


(deftest facts-arrive
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! balance] :as s} (setup! repo)]
        (observe! (posting :status "delivering" :terminal false :occurred-at nil :quantity "2" :complete false :missing ["actual time"]))
        (intake!)
        (testing "When Ozon states the dispatch time and the Sale is accepted"
          (accepted-sale! s :status "delivering" :terminal false :quantity "2")
          (let [read (read-transit ctx) row (first (:shipments read))]
            (testing "then P is travelling with 2 units, counted"
              (is (= [[sale "1" "red" 2 "travelling" true]] (rows read)))
              (is (= {"travelling" 2} (:totals read)))
              (is (= ["2026-11-10T09:00:00Z" observed] ((juxt :actual-time :observed-at) row)) "actual and observed time apart")
              (is (nil? (:missing row))))
            (testing "and W shows 8"
              (is (= 8 (balance "red" "home"))))
            (testing "and its units block archiving red"
              (is (= {"red" [sale]} (transit/blocking-items (:shipments read))))))
          (is (= 0 (intake!))))))))


(deftest delivery-deducts-nothing-more
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! balance] :as s} (setup! repo)]
        (accepted-sale! s :status "delivering" :terminal false :quantity "2")
        (testing "When Ozon reports P delivered"
          (observe! (posting :quantity "2"))
          (let [read (read-transit ctx)]
            (testing "then transit closes as delivered without another deduction"
              (is (= [[sale "1" "red" 2 "delivered" true]] (rows read)))
              (is (= {"delivered" 2} (:totals read)))
              (is (= 8 (balance "red" "home")))
              (is (= {} (transit/blocking-items (:shipments read))) "delivered units block nothing"))))))))


(deftest return-restores-once
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! balance pending] :as s} (setup! repo)
            id (provenance/import-entry-id return)]
        (accepted-sale! s)
        (is (= 7 (balance "red" "home")))
        (testing "Given a return of 1 red mug received by Ozon"
          (observe! (return-of))
          (is (= 1 (intake!)))
          (testing "then it is returning until its Customer return is accepted, and the posting keeps the rest"
            (let [read (read-transit ctx)]
              (is (= [[sale "1" "red" 2 "delivered" true] [return "1" "red" 1 "returning" true]] (rows read)))
              (is (= {"delivered" 2 "returning" 1} (:totals read)))
              (is (= "confirm return warehouse" (:next-step (second (:shipments read)))))
              (is (= {"red" [return]} (transit/blocking-items (:shipments read))))
              (is (= 7 (balance "red" "home")))))
          (testing "When a member accepts the Customer return"
            (is (some? (pending id)))
            (accept-all! ctx "acc-r" [[id 1]])
            (let [read (read-transit ctx)]
              (testing "then it is returned and stock is restored once"
                (is (= [[sale "1" "red" 2 "delivered" true] [return "1" "red" 1 "returned" true]] (rows read)))
                (is (= 8 (balance "red" "home")))
                (is (= {} (transit/blocking-items (:shipments read))))))))))))


(defn- state-after
  "The state of a travelling accepted P1 `days` after Tover last observed it."
  {:malli/schema [:=> [:cat :map :int] :string]}
  [ctx days]
  (:state (first (transit/shipments (transit/load-facts ctx c) (.plus (Instant/parse observed) (Duration/ofDays days))))))


(deftest thirty-days
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)]
        (accepted-sale! s :status "delivering" :terminal false)
        (testing "measured from the operation's own last observation: sync re-reads unfinished shipments every run"
          (are [days state] (= state (state-after ctx days))
            29 "travelling"
            30 "unknown"))
        (testing "unknown blocks no archiving"
          (is (= {} (transit/blocking-items (transit/shipments (transit/load-facts ctx c)
                                                               (.plus (Instant/parse observed) (Duration/ofDays 30)))))))))))


(deftest period-bounds
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)
            read                #(count (:shipments (runtime/read-model ctx ::transit/shipments-in-transit (merge {:connection-id c} %))))]
        (accepted-sale! s :status "delivering" :terminal false)
        (testing "the actual time bounds the period, both ends included"
          (is (= 1 (read {:from "2026-11-10T09:00:00Z" :to "2026-11-10T09:00:00Z"})))
          (is (= 0 (read {:from "2026-11-11T00:00:00Z"}))))
        (testing "a bound that does not parse bounds nothing instead of failing"
          (is (= 1 (read {:from "not an instant at all" :to "2026-13-45T99:99:99Z"}))))))))


(deftest before-cutover-and-aside-are-left-out
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake!]} (setup! repo)
            p2 (str c ":fbs-posting:P2")]
        (testing "Given a posting before the cutover and another one set aside"
          (observe! (posting :occurred-at "2026-10-20T09:00:00Z"))
          (observe! (posting :source-id "P2" :status "delivering" :terminal false))
          (intake!)
          (runtime/execute! ctx ::operations/set-source-operations-aside {:command-id "aside"
                                                                          :input      {:operations [{:source-operation-id p2 :revision 1}] :aside true}})
          (testing "then neither is shown"
            (is (= {:shipments [] :totals {}} (read-transit ctx)))))))))


(deftest lines-keep-their-own-state
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake!] :as s} (setup! repo)]
        (testing "Given an accepted posting of 2 red and 1 blue, the blue one returned"
          (accepted-sale! s :lines [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "2"}
                                    {:line-key "2" :identifiers [{:type "sku" :value "200"}] :quantity "1"}])
          (observe! (return-of :lines [{:line-key "1" :identifiers [{:type "sku" :value "200"}] :quantity "1"}]))
          (intake!)
          (testing "then red stays delivered and blue is returning, never both"
            (is (= [[sale "1" "red" 2 "delivered" true] [return "1" "blue" 1 "returning" true]] (rows (read-transit ctx))))))))))


(deftest return-without-a-posting
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake!]} (setup! repo)]
        (testing "Given a return whose posting Tover never read"
          (observe! (return-of :related-operation "fbs-posting:P9"))
          (intake!)
          (let [read (read-transit ctx)]
            (testing "then it is shown as returning and its units are counted once"
              (is (= [[return "1" "red" 1 "returning" true]] (rows read)))
              (is (= {"returning" 1} (:totals read))))))))))


(defn- state
  {:malli/schema [:=> [:cat :string] :string]}
  [status]
  (transit/status-state "fbs-posting" status))


(deftest unrecognised-status
  (is (= "unknown" (state "brand_new_status")))
  (are [status expected] (= expected (state status))
    "awaiting_deliver" "travelling" "delivering" "travelling" "delivered" "delivered" "not_accepted" "returning")
  (is (= "unknown" (transit/status-state "return" "brand_new_status")))
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)]
        (accepted-sale! s :status "brand_new_status" :terminal false)
        (let [read (read-transit ctx)]
          (is (= [[sale "1" "red" 3 "unknown" true]] (rows read)))
          (is (= {} (transit/blocking-items (:shipments read)))))))))


(deftest cancelled-sale-is-not-counted
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx balance] :as s} (setup! repo)]
        (accepted-sale! s :status "delivering" :terminal false)
        (testing "When a member cancels the wrong accepted Sale"
          (runtime/execute! ctx ::entries/save-shared-inventory-entry {:command-id "m-cancel"
                                                                       :input      {:workspace-id workspace :entry-id "m-cancel" :kind "sale"
                                                                                    :amends       (acceptance/record-revision-id corrections-test/record 1)
                                                                                    :cancel       true :reason        "Wrong"}})
          (accept-all! ctx "acc-cancel" [["m-cancel" 1]])
          (let [read (read-transit ctx)]
            (testing "then it leaves the transit totals and blocks nothing"
              (is (= [[sale "1" "red" 3 "travelling" false]] (rows read)))
              (is (= {} (:totals read)))
              (is (= {} (transit/blocking-items (:shipments read))))
              (is (= 10 (balance "red" "home"))))))))))


;; Board 46 slice 2 · Read shipment status history


(deftest status-history
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake!] :as s} (setup! repo)
            id (provenance/import-entry-id return)]
        (accepted-sale! s :status "delivering" :terminal false)
        (observe! (posting))
        (observe! (return-of))
        (intake!)
        (accept-all! ctx "acc-r" [[id 1]])
        (let [history (runtime/read-model ctx ::transit/shipment-status-history {:source-operation-id sale})]
          (testing "one shipment: its lines, every status with actual and observed time, and the records it produced"
            (is (= [{:line-key "1" :stock-item-id "red" :quantity 2 :state "delivered" :counted true}] (:lines history)))
            (is (= [[1 "delivering" "2026-11-10T09:00:00Z" observed] [2 "delivered" "2026-11-10T09:00:00Z" observed]]
                   (mapv (juxt :revision :status :actual-time :observed-at) (:statuses history))))
            (is (= [[corrections-test/record "sale" sale false] [id "customer-return" return false]]
                   (mapv (juxt :record-id :kind :source-operation-id :cancelled) (:records history)))))
          (testing "and no buyer data"
            (is (not-any? #{:buyer :customer :addressee :address} (mapcat keys (filter map? (tree-seq coll? seq history)))))))
        (is (nil? (runtime/read-model ctx ::transit/shipment-status-history {:source-operation-id "nope"})))))))


(deftest cancelled-after-the-sale-is-returning
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe!] :as s} (setup! repo)]
        (accepted-sale! s :status "delivering" :terminal false :quantity "2")
        (testing "When Ozon cancels P after its Sale was accepted"
          (observe! (posting :status "cancelled" :quantity "2"))
          (let [read (read-transit ctx)]
            (testing "then its units are returning, still counted, and block archiving"
              (is (= [[sale "1" "red" 2 "returning" true]] (rows read)))
              (is (= {"red" [sale]} (transit/blocking-items (:shipments read)))))))))))


;; Board 46 · Units in transit block archiving their stock item


(defn- archive-blockers
  "What blocks archiving red when a member counts it to 0 everywhere and archives it: each `:archive-blocked` problem
  of the review names a source operation, a warehouse or another pending entry."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [ctx]
  (runtime/execute! ctx ::entries/save-shared-inventory-entry
                    {:command-id "archive-red"
                     :input      {:workspace-id workspace :entry-id "archive-red" :kind "correction" :occurred-at "2026-11-21T09:00:00Z"
                                  :lines        [{:line-id "archive-red/1" :stock-item-id "red" :warehouse-id "home" :quantity 0}
                                                 {:line-id "archive-red/2" :stock-item-id "red" :warehouse-id "ozon-7" :quantity 0}]
                                  :changes      [{:stock-item-id "red" :archive true}]}})
  (let [review (runtime/read-model ctx ::acceptance/inventory-review {:selection [{:entry-id "archive-red" :revision 1}] :order ["archive-red"]})]
    (into [] (comp (filter #(= :archive-blocked (:code %))) (map #(or (:source-operation-id %) (:warehouse-id %) (:other-entry-id %))))
          (:problems review))))


(deftest travelling-units-block-archiving
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)]
        (accepted-sale! s :status "delivering" :terminal false :quantity "2")
        (testing "Given 2 red mugs travelling, when a member counts red to 0 and archives it, then the review names P"
          (is (= [sale] (archive-blockers ctx)))
          (testing "and the draft shows it on the item before Submit"
            (is (= [{:code :archive-blocked :related {:source-operation-id sale}}]
                   (mapv #(select-keys % [:code :related]) (:findings (runtime/read-model ctx ::entries/pending-entries {}))))))
          (testing "and Accept rejects the archive"
            (let [selection {:selection [{:entry-id "archive-red" :revision 1}] :order ["archive-red"]}
                  review    (runtime/read-model ctx ::acceptance/inventory-review selection)
                  failure   (try (runtime/execute! ctx ::acceptance/accept-reviewed-inventory-changes
                                                   {:command-id "acc-archive"
                                                    :input      (merge selection {:workspace-id workspace             :acceptance-id "acc-archive"
                                                                                  :fingerprint  (:fingerprint review)})})
                                 nil
                                 (catch clojure.lang.ExceptionInfo error (ex-data error)))]
              (is (= :archive-blocked (:type failure)))
              (is (= sale (get-in failure [:problem :source-operation-id])))
              (is (= "active" (:status (some #(when (= "red" (:stock-item-id %)) %)
                                             (:stock-items (runtime/coeffect ::entries/catalogue ctx {})))))
                  "red stays active"))))))))


(deftest returning-units-block-archiving
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake!] :as s} (setup! repo)
            id (provenance/import-entry-id return)]
        (accepted-sale! s)
        (observe! (return-of))
        (intake!)
        (testing "Given 1 red mug returning, then the review names the return, besides its pending Customer return"
          (is (= #{return id} (set (archive-blockers ctx)))))))))


(deftest unknown-does-not-block
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)]
        (accepted-sale! s :status "brand_new_status" :terminal false :quantity "2")
        (testing "Given P in a status Tover does not recognise, then nothing blocks archiving red"
          (is (= [] (archive-blockers ctx))))))))


(deftest delivered-does-not-block
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)]
        (accepted-sale! s :quantity "2")
        (testing "Given P delivered, then nothing blocks archiving red"
          (is (= [] (archive-blockers ctx))))))))


;; Transit follows the accepted record, never today's mapping


(deftest suspended-mapping-keeps-the-accepted-sale
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx as-connector] :as s} (setup! repo)]
        (accepted-sale! s :status "delivering" :terminal false :quantity "2")
        (testing "Given the Mapping Guard suspended listing 100 after the Sale was accepted"
          (runtime/execute! as-connector ::guard/suspend-product-mapping
                            {:command-id "suspend"
                             :input      {:external-product-id (str c ":100") :mapping-revision 1
                                          :reasons             [{:rule "listing-identity-changed" :before "1" :after "2"}]}})
          (let [read (read-transit ctx)]
            (testing "then the posting is still travelling with the accepted item"
              (is (= [[sale "1" "red" 2 "travelling" true]] (rows read)))
              (is (= {"red" [sale]} (transit/blocking-items (:shipments read)))))
            (testing "and it still blocks archiving red"
              (is (= [sale] (archive-blockers ctx))))))))))


(deftest remapped-listing-keeps-the-accepted-item
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)]
        (accepted-sale! s :status "delivering" :terminal false :quantity "2")
        (testing "Given a member moved listing 100 from red to blue after acceptance"
          (runtime/execute! ctx ::products/change-product-mapping
                            {:command-id "remap"
                             :input      {:external-product-id (str c ":100") :mapping-revision 1 :stock-item-id "blue" :item-revision 1}})
          (let [read (read-transit ctx)]
            (testing "then transit shows and blocks red, whose stock the Sale deducted, not blue"
              (is (= [[sale "1" "red" 2 "travelling" true]] (rows read)))
              (is (= {"red" [sale]} (transit/blocking-items (:shipments read))))
              (is (= [sale] (archive-blockers ctx))))))))))


(deftest edited-quantity-is-the-accepted-one
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! balance]} (setup! repo)
            id (provenance/import-entry-id sale)]
        (testing "Given P of 3 red mugs whose Sale a member edited to 2 before accepting it"
          (observe! (posting :status "delivering" :terminal false))
          (intake!)
          (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                           :lines       [{:line-id (str sale "/1") :stock-item-id "red" :warehouse-id "home" :quantity 2}]})
          (accept-all! ctx "acc-edited" [[id 2]])
          (is (= 8 (balance "red" "home")))
          (testing "then transit shows the 2 accepted units"
            (is (= [[sale "1" "red" 2 "travelling" true]] (rows (read-transit ctx))))))))))

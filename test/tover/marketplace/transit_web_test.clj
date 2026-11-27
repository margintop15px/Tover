(ns tover.marketplace.transit-web-test
  "Board chapter 46 screens: the Transit view of the Operations page is one read-only table of shipments in transit,
  filtered by state and connection; Shipment Details is one shipment's read-only timeline, its actual times apart
  from Tover's observation times, with links to the accepted records it produced. Neither shows buyer data."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [ring.util.codec :as codec]
    [tover.marketplace.connections-web-test :as forms]
    [tover.marketplace.corrections-test :as corrections-test]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.transit-test :as transit-test]
    [tover.marketplace.transit.web :as web]
    [tover.shared.i18n :as i18n]
    [tover.support :as support]))


(use-fixtures :each transit-test/pinned-clock bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.inventory.references
                          'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                          'tover.marketplace.products.guard 'tover.marketplace.warehouses.model 'tover.marketplace.provenance
                          'tover.marketplace.operations.model 'tover.marketplace.operations.corrections
                          'tover.marketplace.transit.model 'tover.workspaces.currency.model
                          'tover.shared.web.core 'tover.marketplace.grid 'tover.marketplace.products.web
                          'tover.marketplace.cutover.web 'tover.marketplace.transit.web])
    (f))
  guard-test/unit-authority)


(def ^:private c corrections-test/c)
(def ^:private sale corrections-test/sale)
(def ^:private return transit-test/return)
(def ^:private setup! #'corrections-test/setup!)
(def ^:private posting #'corrections-test/posting)
(def ^:private return-of #'corrections-test/return-of)
(def ^:private accept-all! #'corrections-test/accept-all!)

(def ^:private buyer
  {:name "Ivan Buyer" :phone "+79990001122" :address "Tverskaya 1"})


(defn- given-a-return!
  "P1's Sale of 2 red mugs accepted while delivering, P1 delivered since, and a return of 1 received by Ozon; every
  observation's facts name the buyer."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [observe! intake! automatic!]}]
  (observe! (posting :status "delivering" :terminal false :quantity "2" :buyer buyer))
  (intake!)
  (is (= 1 (automatic!)))
  (observe! (posting :quantity "2" :buyer buyer))
  (observe! (return-of :buyer buyer))
  (intake!)
  nil)


(defn- cells
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [table]
  (mapv (comp (juxt :shipment :item :quantity :state :counted) :cells) (:rows table)))


(deftest one-read-only-table-of-the-operations-page
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)]
        (given-a-return! s)
        (let [html (forms/page ctx ::web/transit-view {:connection-id c})]
          (is (= 1 (count (re-seq #"<inventory-grid" html))) "one table")
          (is (= 0 (count (re-seq #"<form" html))) "no per-row forms")
          (is (not (str/includes? html "data-command=")) "read-only")
          (is (str/includes? html "href=\"/operations\">Back to operations<"))
          (is (re-find (re-pattern (str "aria-current=\"page\" href=\"/operations/marketplace/" c "/transit\"[^>]*>Transit · ")) html)
              "this view is listed and current in the Operations views")
          (is (str/includes? html "Delivered 1 · Returning 1 · ") "the counted totals by state; the return takes its unit from the posting")
          (is (str/includes? html "transit is never part of a warehouse balance"))
          (is (not-any? #(str/includes? html %) ["Ivan Buyer" "+79990001122" "Tverskaya"]) "no buyer data"))
        (testing "the rows: one per shipment line, each opening its shipment"
          (let [table (web/table ctx c "all")]
            (is (= [["Posting P1" "Mug / red" "1" "Delivered" "Yes"] ["Return R2" "Mug / red" "1" "Returning" "Yes"]] (cells table)))
            (is (= [(str "/operations/marketplace/" c "/transit/" (codec/url-encode sale))
                    (str "/operations/marketplace/" c "/transit/" (codec/url-encode return))]
                   (mapv :href (:rows table))))
            (is (every? #(false? (:selectable? %)) (:rows table)))
            (is (= ["2026-11-10T09:00:00Z" transit-test/observed] ((juxt :actual-time :observed-at) (:cells (first (:rows table)))))
                "actual and observed time apart")))
        (testing "the state filter"
          (is (= [["Return R2" "Mug / red" "1" "Returning" "Yes"]] (cells (web/table ctx c "returning"))))
          (is (= [] (cells (web/table ctx c "travelling"))))
          (let [html (forms/page ctx ::web/transit-view {:connection-id c :state "returning"})]
            (is (re-find (re-pattern (str "aria-current=\"page\" href=\"/operations/marketplace/" c "/transit\\?state=returning\"[^>]*>Returning<")) html))
            (is (re-find (re-pattern (str "href=\"/operations/marketplace/" c "/transit\"[^>]*>All<")) html))))
        (testing "the connection filter lists each connection's Transit view in the same state"
          (let [html (forms/page ctx ::web/transit-view {:connection-id c :state "returning"})]
            (is (re-find (re-pattern (str "aria-label=\"Connection\".*aria-current=\"page\" href=\"/operations/marketplace/" c
                                          "/transit\\?state=returning\"[^>]*>Ozon · "))
                         html))))
        (testing "in Russian"
          (let [html (binding [i18n/*locale* "ru"] (forms/page ctx ::web/transit-view {:connection-id c}))]
            (is (str/includes? html ">В пути<"))
            (is (str/includes? html ">К операциям<")))
          (is (= ["Отправление P1" "Mug / red" "1" "Доставлено" "Да"] (first (cells (binding [i18n/*locale* "ru"] (web/table ctx c "all")))))))))))


(deftest shipment-details
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx] :as s} (setup! repo)
            id                  (provenance/import-entry-id return)]
        (given-a-return! s)
        (accept-all! ctx "acc-r" [[id 1]])
        (let [html (forms/page ctx ::web/shipment-view {:connection-id c :operation-id sale})]
          (testing "the timeline of one shipment, read-only"
            (is (str/includes? html ">Posting P1<"))
            (is (= 0 (count (re-seq #"<form|<input|<button" html))) "read-only")
            (is (re-find #"<td>1</td><td>delivering</td><td>2026-11-10T09:00:00Z</td><td>2026-11-20T10:00:00Z</td>" html)
                "each status with its actual time apart from Tover's observation time")
            (is (re-find #"<td>2</td><td>delivered</td>" html))
            (is (re-find #"<td>Mug / red</td><td>1</td><td>Delivered</td><td>Yes</td>" html) "its lines"))
          (testing "with links to the accepted records it produced"
            (is (str/includes? html (str "href=\"/operations#entry=" (codec/url-encode corrections-test/record) "\">Sale<")))
            (is (str/includes? html (str "href=\"/operations#entry=" (codec/url-encode id) "\">Customer return<"))))
          (is (str/includes? html (str "href=\"/operations/marketplace/" c "/transit\">All shipments in transit<")))
          (is (not-any? #(str/includes? html %) ["Ivan Buyer" "+79990001122" "Tverskaya"]) "no buyer data"))
        (testing "an unknown shipment, or one of another connection, is not found"
          (is (= 404 (:status (ex-data (try (forms/page ctx ::web/shipment-view {:connection-id c :operation-id "nope"}) (catch Exception e e))))))
          (is (= 404 (:status (ex-data (try (forms/page ctx ::web/shipment-view {:connection-id "other" :operation-id sale}) (catch Exception e e)))))))))))

(ns tover.marketplace.differences-web-test
  "Board chapter 45 slices b and c screen: the Stock differences view of the Operations page is one read-only table
  of the newest snapshot's differences, with each moved item's pending Transfers; it never shows buyer data."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.marketplace.connections-web-test :as forms]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.moves-test :as moves-test]
    [tover.marketplace.moves.web :as web]
    [tover.support :as support]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.inventory.references
                          'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                          'tover.marketplace.products.guard 'tover.marketplace.warehouses.model 'tover.marketplace.provenance
                          'tover.marketplace.operations.model 'tover.marketplace.operations.corrections
                          'tover.marketplace.moves.model 'tover.workspaces.currency.model
                          'tover.shared.web.core 'tover.marketplace.grid 'tover.marketplace.products.web
                          'tover.marketplace.cutover.web 'tover.marketplace.moves.web])
    (f))
  guard-test/unit-authority)


(def ^:private setup! #'moves-test/setup!)
(def ^:private detection #'moves-test/detection)
(def ^:private c moves-test/c)


(deftest one-read-only-table-of-the-operations-page
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect!]} (detection (setup! repo))]
        (pass! "D1" "2026-11-21T06:00:00Z" moves-test/equal)
        (pass! "D2" "2026-11-22T06:00:00Z" moves-test/moved-5)
        (is (= 1 (detect!)))
        (let [html (forms/page ctx ::web/differences-view {:connection-id c})]
          (is (= 1 (count (re-seq #"<inventory-grid" html))) "one table")
          (is (= 0 (count (re-seq #"<form" html))) "no per-row forms")
          (is (not (str/includes? html "data-command=")) "read-only: members accept on Operations")
          (is (str/includes? html "href=\"/operations\">Back to operations<"))
          (is (re-find (re-pattern (str "aria-current=\"page\" href=\"/operations/marketplace/" c "/differences\"[^>]*>Stock differences · "))
                       html)
              "this view is listed and current in the Operations views")
          (is (str/includes? html "Snapshot of 2026-11-22T06:00:00Z")))
        (testing "the rows: one per item and warehouse, the move's pending Transfer on the item's first row"
          (let [rows (:rows (web/table ctx c))]
            (is (= [["Mug / red" "Ozon 7" "5" "10" "−5" "Moved" "2026-11-21T06:00:00Z" ["5 from Ozon 7 to Ozon 8 · Pending"]]
                    ["Mug / red" "Ozon 8" "15" "10" "+5" nil nil []]]
                   (mapv (comp (juxt :item :warehouse :ozon :tover :difference :state :last-agreeing :transfers) :cells) rows)))
            (is (every? #(false? (:selectable? %)) rows))))))))

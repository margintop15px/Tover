(ns tover.marketplace.differences-web-test
  "Board chapter 45 slices b and c screen: the Stock differences view of the Operations page is one read-only table
  of the newest snapshot's differences, with each moved item's pending Transfers; it never shows buyer data."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [ring.util.codec :as codec]
    [tover.marketplace.connections-web-test :as forms]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.moves-test :as moves-test]
    [tover.marketplace.moves.web :as web]
    [tover.marketplace.operations-test :as operations-test]
    [tover.marketplace.sync.observations :as observations]
    [tover.shared.i18n :as i18n]
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
(def ^:private accept-all! #'operations-test/accept-all!)
(def ^:private operation #'operations-test/operation)


(deftest one-read-only-table-of-the-operations-page
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect! observe!]} (detection (setup! repo))]
        ;; A posting before the cutover (history, decided) whose facts name its buyer.
        (observe! (operation "P0" 1 :occurred-at "2026-10-20T09:00:00Z"
                             :facts (observations/write-json {:buyer {:name "Ivan Buyer" :phone "+79990001122" :address "Tverskaya 1"}})))
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
          (is (str/includes? html "Snapshot of 2026-11-22T06:00:00Z"))
          (is (not-any? #(str/includes? html %) ["Ivan Buyer" "+79990001122" "Tverskaya"]) "no buyer data"))
        (testing "in Russian"
          (let [html (binding [i18n/*locale* "ru"] (forms/page ctx ::web/differences-view {:connection-id c}))]
            (is (str/includes? html ">Расхождения остатков<"))
            (is (str/includes? html ">К операциям<")))
          (let [cells (:cells (first (:rows (binding [i18n/*locale* "ru"] (web/table ctx c)))))]
            (is (= ["Перемещено" ["5 из Ozon 7 в Ozon 8 · Ожидает"]] ((juxt :state :transfers) cells))
                "the rows' state and Transfers are translated too")))
        (testing "the rows: one per item and warehouse, the move's pending Transfer on the item's first row"
          (let [rows (:rows (web/table ctx c))]
            (is (= [["Mug / red" "Ozon 7" "5" "10" "−5" "Moved" "2026-11-21T06:00:00Z" ["5 from Ozon 7 to Ozon 8 · Pending"]]
                    ["Mug / red" "Ozon 8" "15" "10" "+5" nil nil []]]
                   (mapv (comp (juxt :item :warehouse :ozon :tover :difference :state :last-agreeing :transfers) :cells) rows)))
            (is (every? #(false? (:selectable? %)) rows))))
        (testing "the Operations table reads each pending Transfer's evidence: the snapshots it was found between"
          (is (= [{:entry-id         (str "move:" c "|red|D1|ozon-7|ozon-8") :connection-id c :last-agreeing-run "D1" :current-run "D2"
                   :last-agreeing-at "2026-11-21T06:00:00Z"                  :current-at    "2026-11-22T06:00:00Z"}]
                 (:moves (runtime/read-model ctx :tover.marketplace.moves.model/pending-move-evidence {})))))
        (testing "a moved row links to its pending Transfer on Operations"
          (let [id (str "move:" c "|red|D1|ozon-7|ozon-8")]
            (is (= [[{:href (str "/operations#entry=" (codec/url-encode id)) :label "Open the pending transfer"}]
                    [{:href (str "/operations#entry=" (codec/url-encode id)) :label "Open the pending transfer"}]]
                   (mapv #(get-in % [:links :difference]) (:rows (web/table ctx c)))))
            (is (= [{:type "marketplace-open" :href (str "/operations#entry=" (codec/url-encode id)) :label "Open the pending transfer"}]
                   (mapv #(select-keys % [:type :href :label])
                         (get-in (grid/model (web/table ctx c)) [:rows 0 :cells 4 :actions])))
                "the link is on the row's Difference cell")))
        (testing "an accepted Transfer shows what was accepted"
          (let [id (str "move:" c "|red|D1|ozon-7|ozon-8")]
            (runtime/execute! ctx :tover.inventory.entries.model/save-shared-inventory-entry
                              {:command-id "edit-move"
                               :input      {:workspace-id (:workspace-id support/scope) :entry-id   id       :expected-revision 1        :kind "transfer"
                                            :occurred-at  "2026-11-21T06:00:01Z" :from-warehouse-id "ozon-7" :to-warehouse-id   "ozon-8"
                                            :lines        [{:line-id "1" :stock-item-id "red" :quantity 3}]}})
            (accept-all! ctx "acc-3" [[id 2]])
            (is (= 0 (detect!)))
            (is (= ["3 from Ozon 7 to Ozon 8 · Accepted"] (get-in (first (:rows (web/table ctx c))) [:cells :transfers])))))))))


(deftest older-transfers-and-unexplained-rows-are-linked
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect!]} (detection (setup! repo))
            old (str "move:" c "|red|D1|ozon-7|ozon-8")
            new (str "move:" c "|red|D3|ozon-7|ozon-8")
            open #(hash-map :href (str "/operations#entry=" (codec/url-encode %)) :label "Open the pending transfer")]
        (pass! "D1" "2026-11-21T06:00:00Z" moves-test/equal)
        (pass! "D2" "2026-11-22T06:00:00Z" moves-test/moved-5)
        (is (= 1 (detect!)))
        (testing "Given a member edited the first Transfer, then a newer agreeing snapshot gives the move a new key"
          (runtime/execute! ctx :tover.inventory.entries.model/save-shared-inventory-entry
                            {:command-id "edit-move"
                             :input      {:workspace-id (:workspace-id support/scope) :entry-id   old      :expected-revision 1        :kind "transfer"
                                          :occurred-at  "2026-11-21T06:00:01Z" :from-warehouse-id "ozon-7" :to-warehouse-id   "ozon-8"
                                          :lines        [{:line-id "1" :stock-item-id "red" :quantity 4}]}})
          (pass! "D3" "2026-11-23T06:00:00Z" moves-test/equal)
          (pass! "D4" "2026-11-24T06:00:00Z" moves-test/moved-5)
          (is (= 1 (detect!)))
          (testing "then the Transfer kept under the older key is still listed and linked"
            (let [rows (:rows (web/table ctx c))]
              (is (= ["5 from Ozon 7 to Ozon 8 · Pending" "4 from Ozon 7 to Ozon 8 · Pending, edited"] (get-in (first rows) [:cells :transfers])))
              (is (= [(open new) (open old)] (get-in (first rows) [:links :difference]))))))
        (testing "Given the next snapshot shows a difference no move explains"
          (pass! "D5" "2026-11-25T06:00:00Z" {["100" moves-test/hub-7] 5 ["100" moves-test/hub-8] 13})
          (detect!)
          (testing "then each differing row links to recording a correction, beside the pending Transfer it touches"
            (let [rows (:rows (web/table ctx c))]
              (is (= "Unexplained" (get-in (first rows) [:cells :state])))
              (is (= [[(open old) {:href "/" :label "Record a correction on Stock"}]
                      [(open old) {:href "/" :label "Record a correction on Stock"}]]
                     (mapv #(get-in % [:links :difference]) rows))))))))))

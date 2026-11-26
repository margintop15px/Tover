(ns tover.marketplace.products-web-test
  "Board chapter 42 screens: Product Matching is one table with selection actions that answer with command
  descriptors; rejections land on their rows; Ozon text is escaped; Mapping Details reads one listing."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.handler :as handler]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [dev.onionpancakes.chassis.core :as chassis]
    [tover.catalogues.categories.model]
    [tover.catalogues.products.model]
    [tover.catalogues.suppliers.model]
    [tover.fixtures :as fixtures]
    [tover.inventory.entries.model]
    [tover.inventory.references]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.marketplace.connections-routes-test :as routes]
    [tover.marketplace.connections-test :as scenarios]
    [tover.marketplace.connections-web-test :as forms]
    [tover.marketplace.connections.web :as connections-web]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.products-test :as listings]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.products.web :as web]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.support :as support]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.stores.model 'tover.catalogues.products.model
                        'tover.inventory.stock-items.model 'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.inventory.entries.model 'tover.inventory.references
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model 'tover.marketplace.warehouses.model
                        'tover.shared.web.core 'tover.marketplace.connections.web 'tover.marketplace.grid
                        'tover.marketplace.products.web 'tover.workspaces.currency.model])
  (f))


(use-fixtures :each bel-test/isolated-registry register scenarios/unit-authority)


(def c "c-1")
(def mug (str c ":1"))
(def plate-listing (str c ":2"))
(def odd (str c ":3"))


(defn seed!
  "Connection c-1; products Mug (two items, barcode 4600000000017) and Plate (barcode plate-code); listings Mug,
  a listing that also carries Plate's barcode, and one named with markup."
  {:malli/schema [:=> [:cat :map] :nil]}
  [ctx]
  (forms/accept! ctx (forms/act ctx ::connections-web/connect forms/connect-form))
  (doseq [[product name] [["mug" "Mug"] ["plate" "Plate"]]]
    (listings/seed! ctx :tover.catalogues.products.model/product-registered {:product-id product :name name :status "active" :revision 1}))
  (doseq [[item product variant] [["mug-1" "mug" "blue"] ["mug-2" "mug" "red"] ["plate-1" "plate" nil]]]
    (listings/seed! ctx ::stock-items/stock-item-registered
                    (cond-> {:stock-item-id item :product-id product :condition "regular" :status "active" :revision 1}
                      variant (assoc :variant-label variant))))
  (doseq [[item code] [["mug-1" "4600000000017"] ["plate-1" "plate-code"]]]
    (listings/seed! ctx ::stock-items/stock-item-barcode-assigned {:barcode-id (stock-items/barcode-id item code) :stock-item-id item :barcode code}))
  (listings/seed! ctx ::sync/external-product-observed (listings/observed "1" "Mug" :barcodes ["4600000000017"] :connection-id c))
  (listings/seed! ctx ::sync/external-product-observed (listings/observed "2" "Mug set" :barcodes ["plate-code"] :connection-id c))
  (listings/seed! ctx ::sync/external-product-observed (listings/observed "3" "<script>alert(1)</script>" :connection-id c)))


(defn with-listings
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (forms/with-context (fn [ctx _] (seed! ctx) (f ctx))))


(defn page
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :string]}
  [ctx id input]
  (forms/page ctx id input))


(defn act
  "The action's answer for `input`, the descriptor or a signal."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ctx input]
  ((:bel/fn (bel/get-action ::web/command)) ctx (merge {:connectionId c :commandId "attempt-1"} input)))


(deftest one-table-and-no-form
  (with-listings
    (fn [ctx]
      (let [html (page ctx ::web/product-matching {:connection-id c})]
        (is (= 1 (count (re-seq #"<inventory-grid" html))))
        (is (= 1 (count (re-seq #"<marketplace-workbench" html))))
        (is (not (str/includes? html "<form")))
        (is (str/includes? html "href=\"/settings/marketplace/c-1\"") "a link back to the connection")
        (is (str/includes? html "management-header"))
        (is (str/includes? html "Mapped 0 · Unmatched 3 · Deferred 0 · Suspended 0"))
        (doseq [command ["confirm" "register" "defer"]]
          (is (str/includes? html (str "data-command=\"" command "\""))))))))


(deftest the-connection-page-links-to-the-table
  (with-listings
    (fn [ctx]
      (is (str/includes? (page ctx ::connections-web/connection {:connection-id c}) "href=\"/settings/marketplace/c-1/products\"")))))


(deftest ozon-text-is-escaped
  (with-listings
    (fn [ctx]
      (let [html (page ctx ::web/product-matching {:connection-id c})]
        (is (not (str/includes? html "<script>alert")))
        (is (str/includes? html "&lt;script&gt;alert(1)&lt;")))
      (let [html (page ctx ::web/mapping {:connection-id c :listing-id odd})]
        (is (not (str/includes? html "<script>alert")))
        (is (str/includes? html "&lt;script&gt;alert(1)&lt;"))))))


(defn rows
  "The table's rows by id."
  {:malli/schema [:=> [:cat :map [:* :any]] :map]}
  [ctx & [state]]
  (into {} (map (juxt :id identity)) (:rows (web/table ctx c (or state "unmatched")))))


(deftest matching-rows
  (with-listings
    (fn [ctx]
      (let [{mug-row mug set-row plate-listing} (rows ctx)]
        (is (nil? (get-in mug-row [:cells :stock-item])) "two items of Mug: nothing is preselected")
        (is (= [{:value "mug-1" :label "Mug / blue · same barcode" :revision 1} {:value "mug-2" :label "Mug / red · same barcode" :revision 1}]
               (get-in mug-row [:options :stock-item])))
        (is (= {:value "plate-1" :label "Plate · same barcode" :revision 1} (get-in set-row [:cells :stock-item]))
            "Plate's only item is the suggested stock item")
        (is (= "" (get-in mug-row [:cells :adopt])) "no barcode is adopted until the member types it")
        (is (= {:value "mug" :label "Mug" :revision 1} (get-in mug-row [:cells :product])) "an existing product of the same name")
        (is (= "4600000000017" (get-in mug-row [:cells :barcodes])))
        (is (= "pcs" (get-in mug-row [:cells :unit])))
        (is (= "/settings/marketplace/c-1/products/c-1%3A1" (:href mug-row)))
        (is (= {:value "" :label "Mug set"} (get-in set-row [:cells :product])) "a new product named like the listing")))))


(deftest confirm-returns-a-command-descriptor
  (with-listings
    (fn [ctx]
      (let [descriptor (act ctx {:command "confirm"
                                 :rows    [{:id     mug :revision 1
                                            :values {:stock-item "mug-1" :stock-item-revision "1" :adopt "4600000000017"}}]})]
        (is (= {:type  :command :id ::products/confirm-product-mapping :command-id "attempt-1"
                :input {:rows [{:external-product-id mug               :listing-revision 1 :stock-item-id "mug-1" :item-revision 1
                                :adopt-barcodes      ["4600000000017"]}]}
                :then  {:type :view :id ::grid/committed :input {:commandId "attempt-1"}}}
               (dissoc descriptor :catch)))
        (is (= ::grid/failed (get-in descriptor [:catch :id])))
        (forms/accept! ctx descriptor)
        (is (not (contains? (rows ctx) mug)) "the confirmed listing leaves the unmatched table")
        (is (contains? (rows ctx "confirmed") mug))))))


(deftest register-and-defer-descriptors
  (with-listings
    (fn [ctx]
      (is (= [::products/register-product-for-external-listing
              {:rows [{:external-product-id plate-listing :listing-revision 1     :unit "pcs" :barcodes ["plate-code"]
                       :product-name        "Mug set"     :variant-label    "big"}]}]
             ((juxt :id :input) (act ctx {:command "register"
                                          :rows    [{:id     plate-listing :revision 1
                                                     :values {:product "" :product-name "Mug set" :variant "big" :barcodes "plate-code" :unit "pcs"}}]}))))
      (is (= [::products/register-product-for-external-listing
              {:rows [{:external-product-id mug :listing-revision 1 :unit "kg" :barcodes [] :product-id "mug" :product-revision 1}]}]
             ((juxt :id :input) (act ctx {:command "register"
                                          :rows    [{:id mug :revision 1 :values {:product "mug" :product-revision "1" :unit "kg"}}]}))))
      (is (= [::products/defer-product-match {:external-product-ids [mug odd] :reason "not tracked"}]
             ((juxt :id :input) (act ctx {:command "defer" :reason " not tracked " :rows [{:id mug :revision 1} {:id odd :revision 1}]})))))))


(deftest a-row-without-a-stock-item-is-not-sent
  (with-listings
    (fn [ctx]
      (let [answer (act ctx {:command "confirm" :rows [{:id odd :revision 1 :values {:stock-item ""}}]})]
        (is (= :signals (:type answer)))
        (is (str/includes? (get-in answer [:data :_marketplaceCommandJson]) "\"path\":\"stock-item\""))))))


(deftest barcode-conflict-renders-on-its-row
  (with-listings
    (fn [ctx]
      (let [input  {:connectionId c :commandId "attempt-2" :command "confirm"
                    :rows         [{:id mug :revision 1 :values {:stock-item "mug-1" :stock-item-revision "1"}}
                                   {:id plate-listing :revision 1 :values {:stock-item "mug-2" :stock-item-revision "1"}}]}
            caught (handler/execute-action ctx ::web/command input)
            answer ((:bel/fn (bel/get-view ::grid/failed)) (:input caught))]
        (is (= ::grid/failed (:id caught)))
        (is (= {:commandId "attempt-2" :status "failed"
                :rows      [{:id plate-listing :path "stock-item" :message "Barcode plate-code belongs to Plate."}]}
               (dissoc answer :message)))
        (testing "nothing was accepted"
          (is (contains? (rows ctx) mug)))))))


(deftest the-filter-shows-each-state
  (with-listings
    (fn [ctx]
      (forms/accept! ctx (act ctx {:command "defer" :reason "later" :rows [{:id odd :revision 1}]}))
      (is (= #{mug plate-listing} (set (keys (rows ctx)))))
      (is (= [odd] (keys (rows ctx "deferred"))))
      (is (= ["Deferred" "later"] (take 2 (get-in (rows ctx "deferred") [odd :cells :status]))))
      (let [html (page ctx ::web/product-matching {:connection-id c :state "deferred"})]
        (is (str/includes? html "aria-current=\"page\" href=\"/settings/marketplace/c-1/products?state=deferred\"")
            "the current filter is marked")))))


(deftest mapping-details
  (with-listings
    (fn [ctx]
      (forms/accept! ctx (act ctx {:command "confirm" :rows [{:id mug :revision 1 :values {:stock-item "mug-1" :stock-item-revision "1"}}]}))
      (let [html (page ctx ::web/mapping {:connection-id c :listing-id mug})]
        (is (str/includes? html "Mug / blue"))
        (is (str/includes? html "4600000000017"))
        (is (not (str/includes? html "<form")) "read-only evidence and history")
        (is (not (str/includes? html "<select")))
        (is (str/includes? html "href=\"/settings/marketplace/c-1/products?state=confirmed\"") "a link back to its table"))
      (testing "a listing of another connection is not found here"
        (is (= 404 (try (page ctx ::web/mapping {:connection-id "c-2" :listing-id mug}) nil
                        (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))))))


(defn- suspend!
  "Mug confirmed to mug-1, then suspended by the guard at mapping revision 2."
  {:malli/schema [:=> [:cat :map] :nil]}
  [ctx]
  (forms/accept! ctx (act ctx {:command "confirm" :rows [{:id mug :revision 1 :values {:stock-item "mug-1" :stock-item-revision "1"}}]}))
  (listings/seed! ctx ::products/product-mapping-suspended
                  {:external-product-id mug :mapping-revision-id   (products/revision-id mug 2) :connection-id c
                   :change              "suspended" :stock-item-id "mug-1" :listing-revision                   1           :item-revision 1           :identity-basis "{}"
                   :reasons             (observations/write-json [{:rule "barcode-moved" :before "4600000000017" :after "none"}])
                   :mapping-revision    2 :state                   "suspended"                  :changed-by    "connector" :changed-at    listings/at}))


(deftest mapped-rows-are-actionable-in-the-table
  (with-listings
    (fn [ctx]
      (forms/accept! ctx (act ctx {:command "confirm" :rows [{:id mug :revision 1 :values {:stock-item "mug-1" :stock-item-revision "1"}}]}))
      (let [row  (get (rows ctx "confirmed") mug)
            html (page ctx ::web/product-matching {:connection-id c :state "confirmed"})]
        (is (= {:value "mug-1" :label "Mug / blue" :revision 1} (get-in row [:cells :stock-item])) "the picker starts at the mapped item")
        (is (= [1 {:listing 1}] ((juxt :revision :revisions) row)) "the mapping revision, and the reviewed listing revision")
        (is (not (str/includes? html "<form")))
        (doseq [command ["change" "remove"]]
          (is (re-find (re-pattern (str "data-command=\"" command "\" disabled data-each=\"true\"")) html) "one command per selected row"))
        (is (not (str/includes? html "data-command=\"reconfirm\"")) "Reconfirm only for suspended rows"))
      (testing "Change sends Change Product Mapping with the picked item"
        (let [descriptor (act ctx {:command "change" :commandId "change-1" :rows [{:id     mug :revision 1 :revisions {:listing 1}
                                                                                   :values {:stock-item "mug-2" :stock-item-revision "1"}}]})]
          (is (= [::products/change-product-mapping {:external-product-id mug :mapping-revision 1 :stock-item-id "mug-2" :item-revision 1}
                  ::grid/committed]
                 [(:id descriptor) (:input descriptor) (get-in descriptor [:then :id])]))
          (is (= ::grid/failed (get-in descriptor [:catch :id])))
          (forms/accept! ctx descriptor)
          (is (= "mug-2" (get-in (rows ctx "confirmed") [mug :cells :stock-item :value])))))
      (testing "a blank picker is not sent as Change"
        (let [answer (act ctx {:command "change" :rows [{:id mug :revision 2 :values {:stock-item ""}}]})]
          (is (= :signals (:type answer)))
          (is (str/includes? (get-in answer [:data :_marketplaceCommandJson]) "Choose a stock item."))))
      (testing "Remove sends Change Product Mapping without an item"
        (let [descriptor (act ctx {:command "remove" :commandId "remove-1" :rows [{:id mug :revision 2 :values {:stock-item "mug-2" :stock-item-revision "1"}}]})]
          (is (= {:external-product-id mug :mapping-revision 2} (:input descriptor)))
          (forms/accept! ctx descriptor)
          (is (contains? (rows ctx) mug) "back among the unmatched")))
      (testing "one row per command"
        (let [answer (act ctx {:command "remove" :rows [{:id mug :revision 3} {:id odd :revision 1}]})]
          (is (str/includes? (get-in answer [:data :_marketplaceCommandJson]) "Send one listing at a time.")))))))


(deftest reconfirm-a-suspended-row-in-the-table
  (with-listings
    (fn [ctx]
      (suspend! ctx)
      (let [row  (get (rows ctx "suspended") mug)
            html (page ctx ::web/product-matching {:connection-id c :state "suspended"})]
        (is (= [2 {:listing 1}] ((juxt :revision :revisions) row)))
        (doseq [command ["reconfirm" "change" "remove"]]
          (is (str/includes? html (str "data-command=\"" command "\""))))
        (testing "an edited picker is not reconfirmed"
          (let [answer (act ctx {:command "reconfirm" :rows [{:id     mug :revision 2 :revisions {:listing 1}
                                                              :values {:stock-item "mug-2" :stock-item-revision "1"}}]})]
            (is (str/includes? (get-in answer [:data :_marketplaceCommandJson]) "Reconfirm keeps the mapped stock item."))))
        (let [descriptor (act ctx {:command "reconfirm" :commandId "reconfirm-1" :rows [{:id     mug :revision 2 :revisions {:listing 1}
                                                                                         :values {:stock-item "mug-1" :stock-item-revision "1"}}]})]
          (is (= [::products/reconfirm-product-mapping {:external-product-id mug :mapping-revision 2 :listing-revision 1 :item-revision 1}]
                 ((juxt :id :input) descriptor)))
          (forms/accept! ctx descriptor)
          (is (empty? (rows ctx "suspended")))
          (is (= ["Mapped"] (take 1 (get-in (rows ctx "confirmed") [mug :cells :status])))))))))


(deftest a-stale-reconfirm-renders-on-its-row
  (with-listings
    (fn [ctx]
      (suspend! ctx)
      (let [input  {:connectionId c :commandId "attempt-3" :command "reconfirm"
                    :rows         [{:id mug :revision 1 :revisions {:listing 1} :values {:stock-item "mug-1" :stock-item-revision "1"}}]}
            caught (handler/execute-action ctx ::web/command input)
            answer ((:bel/fn (bel/get-view ::grid/failed)) (:input caught))]
        (is (= ::grid/failed (:id caught)))
        (is (= [{:id mug :path "status" :message "The listing, its mapping or the stock item changed since review. Review it again."}]
               (:rows answer)))
        (is (contains? (rows ctx "suspended") mug) "nothing was accepted")))))


(deftest the-pages-need-the-selected-workspace
  (routes/with-connection
    (fn [{:keys [handler] :as services} _ _]
      (let [member  (support/browser handler)
            outside (support/browser handler)]
        (testing "a member of the workspace reads the table"
          (support/sign-in! services member "member@example.test" {:workspace-id fixtures/workspace-id})
          (is (= 200 (:status ((:request! member) :get "/settings/marketplace/c-1/products")))))
        (testing "a member of another workspace does not see the connection"
          (support/sign-in! services outside "other@example.test" {:workspace-id fixtures/other-workspace-id})
          (is (= 404 (:status ((:request! outside) :get "/settings/marketplace/c-1/products")))))
        (testing "without a selected workspace the existing access page answers"
          (let [anonymous ((:request! (support/browser handler)) :get "/settings/marketplace/c-1/products")]
            (is (= 303 (:status anonymous)))
            (is (str/starts-with? (get-in anonymous [:headers "location"]) "/auth/login"))))))))


(defn- cell
  "Column `key` of the row as the grid draws it."
  {:malli/schema [:=> [:cat :map :string :keyword] :map]}
  [ctx id key]
  (let [table (web/table ctx c "unmatched")
        model (grid/model (assoc table :id "t"))]
    (some #(when (= key (keyword (get-in % [:target :path] ""))) %)
          (:cells (some #(when (= id (:id %)) %) (:rows model))))))


(deftest an-edited-product-keeps-its-revision
  (with-listings
    (fn [ctx]
      (listings/seed! ctx :tover.catalogues.products.model/product-registered {:product-id "bowl" :name "Bowl" :status "active" :revision 3})
      (listings/seed! ctx ::sync/external-product-observed (listings/observed "4" "Bowl" :connection-id c))
      (let [bowl (str c ":4")
            cell (cell ctx bowl :product)]
        (is (= "bowl" (:value cell)))
        (is (some #(= {:value "bowl" :label "Bowl" :revision 3} %) (get-in cell [:editor :options]))
            "the preselected product is among the picker's options with its revision, so the browser sends it")
        (testing "a product without its revision is not sent"
          (let [answer (act ctx {:command "register" :rows [{:id bowl :revision 1 :values {:product "bowl" :unit "pcs"}}]})]
            (is (= :signals (:type answer)))
            (is (str/includes? (get-in answer [:data :_marketplaceCommandJson]) "Choose a product from the list."))))
        (let [descriptor (act ctx {:command "register" :rows [{:id bowl :revision 1 :values {:product "bowl" :product-revision "3" :unit "pcs"}}]})]
          (is (= 3 (get-in descriptor [:input :rows 0 :product-revision])))
          (forms/accept! ctx descriptor)
          (is (contains? (rows ctx "confirmed") bowl) "Register is accepted, not stale"))))))


(deftest register-keeps-the-barcodes-the-member-left
  (with-listings
    (fn [ctx]
      (listings/seed! ctx ::sync/external-product-observed (listings/observed "5" "Jug" :barcodes ["jug-1" "jug-2"] :connection-id c))
      (let [jug  (str c ":5")
            text (:value (cell ctx jug :barcodes))]
        (is (= "jug-1, jug-2" text) "the listing's barcodes, shown as sent")
        (is (= ["jug-1" "jug-2"] (web/barcode-list text)))
        (forms/accept! ctx (act ctx {:command "register"
                                     :rows    [{:id jug :revision 1 :values {:product "" :product-name "Jug" :barcodes "jug-2" :unit "pcs"}}]}))
        (is (contains? (rows ctx "confirmed") jug))
        (let [{:keys [barcodes]} (runtime/read-model ctx :tover.inventory.references/draft-catalogue {})]
          (is (= ["jug-2"] (filterv #{"jug-1" "jug-2"} (map :barcode barcodes))) "only the kept barcode is assigned"))))))


(deftest actions-reject-listings-of-other-connections
  (with-listings
    (fn [ctx]
      (listings/seed! ctx ::sync/external-product-observed (listings/observed "7" "Elsewhere" :connection-id "c-2"))
      (let [status #(try (%) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))]
        (doseq [id ["c-2:7" "c-1:999"]]
          (is (= 404 (status #(act ctx {:command "defer" :rows [{:id mug :revision 1} {:id id :revision 1}]})))))
        (doseq [command ["change" "remove" "reconfirm"]]
          (is (= 404 (status #(act ctx {:command command :rows [{:id     "c-2:7" :revision 1 :revisions {:listing 1}
                                                                 :values {:stock-item "mug-1" :stock-item-revision "1"}}]})))))
        (is (= #{mug plate-listing odd} (set (keys (rows ctx)))) "nothing was executed")))))


(deftest suspended-details-highlight-the-change
  (with-listings
    (fn [ctx]
      (forms/accept! ctx (act ctx {:command "confirm" :rows [{:id mug :revision 1 :values {:stock-item "mug-1" :stock-item-revision "1"}}]}))
      (listings/seed! ctx ::products/product-mapping-suspended
                      {:external-product-id mug :mapping-revision-id   (products/revision-id mug 2) :connection-id c
                       :change              "suspended" :stock-item-id "mug-1" :listing-revision                   1           :item-revision 1           :identity-basis "{}"
                       :reasons             (observations/write-json [{:rule "barcode-moved" :before "4600000000017" :after "<b>none</b>"}])
                       :mapping-revision    2 :state                   "suspended"                  :changed-by    "connector" :changed-at    listings/at})
      (let [html (page ctx ::web/mapping {:connection-id c :listing-id mug})]
        (is (str/includes? html "Barcode moved to another product") "the rule is named")
        (is (re-find #"management-diff-before\">4600000000017<" html))
        (is (re-find #"management-diff-after\">&lt;b&gt;none&lt;/b&gt;<" html))
        (is (str/includes? html "<td>Barcode</td>") "identifier types are labels")
        (is (str/includes? html "<td>Suspended</td>") "history changes are labels")))))

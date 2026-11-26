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
    [tover.support :as support]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.stores.model 'tover.catalogues.products.model
                        'tover.inventory.stock-items.model 'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.inventory.entries.model 'tover.inventory.references
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
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
        (is (= [{:value "" :label "None"} {:value "4600000000017" :label "4600000000017"}] (get-in mug-row [:options :adopt])))
        (is (= {:value "mug" :label "Mug"} (get-in mug-row [:cells :product])) "an existing product of the same name")
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
        (is (str/includes? html (bel/action-url ::web/change-mapping))))
      (testing "Change and Remove send Change Product Mapping and reopen the details"
        (let [form   {:connection-id c :listing-id mug :mapping-revision 1}
              change (forms/act ctx ::web/change-mapping (assoc form :command-id "change-1" :stock-item "mug-2@1"))]
          (is (= [::products/change-product-mapping {:mapping-revision 1 :stock-item-id "mug-2" :item-revision 1 :external-product-id mug}
                  {:type :page :id ::web/mapping :path-params {:connection-id c :listing-id mug}}]
                 ((juxt :id :input :then) change)))
          (is (= {:mapping-revision 1 :external-product-id mug}
                 (:input (forms/act ctx ::web/change-mapping (assoc form :stock-item "")))))))
      (testing "a listing of another connection is not found here"
        (is (= 404 (try (page ctx ::web/mapping {:connection-id "c-2" :listing-id mug}) nil
                        (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))))))


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

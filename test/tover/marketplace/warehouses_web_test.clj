(ns tover.marketplace.warehouses-web-test
  "Board chapter 43 screen: Warehouse Matching is one table with selection actions that answer with command
  descriptors; rejections land on their rows; Ozon text is escaped; actions accept only the path's connection."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.handler :as handler]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.categories.model]
    [tover.catalogues.products.model]
    [tover.catalogues.suppliers.model]
    [tover.inventory.entries.model]
    [tover.inventory.references]
    [tover.marketplace.connections-test :as scenarios]
    [tover.marketplace.connections-web-test :as forms]
    [tover.marketplace.connections.web :as connections-web]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.products-test :as listings]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.warehouses-test :as model-test]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.marketplace.warehouses.web :as web]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.stores.model 'tover.catalogues.products.model
                        'tover.inventory.stock-items.model 'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.inventory.entries.model 'tover.marketplace.provenance 'tover.inventory.references
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                        'tover.marketplace.warehouses.model
                        'tover.shared.web.core 'tover.marketplace.connections.web 'tover.marketplace.grid
                        'tover.marketplace.products.web 'tover.marketplace.warehouses.web 'tover.workspaces.currency.model])
  (f))


(use-fixtures :each bel-test/isolated-registry register scenarios/unit-authority)


(def c "c-1")
(def hub (str c ":fbo:1"))
(def seller (str c ":fbs:2"))
(def odd (str c ":fbo:3"))


(defn seed!
  "Connection c-1; warehouse Main; Ozon warehouses Hub (FBO), Main (FBS) and one named with markup."
  {:malli/schema [:=> [:cat :map] :nil]}
  [ctx]
  (forms/accept! ctx (forms/act ctx ::connections-web/connect forms/connect-form))
  (listings/seed! ctx :tover.catalogues.warehouses.model/warehouse-registered (model-test/warehouse "main" "Main"))
  (listings/seed! ctx ::sync/external-warehouse-observed (model-test/observed "fbo" "1" "Hub"))
  (listings/seed! ctx ::sync/external-warehouse-observed (model-test/observed "fbs" "2" "Main"))
  (listings/seed! ctx ::sync/external-warehouse-observed (model-test/observed "fbo" "3" "<script>alert(1)</script>")))


(defn with-warehouses
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (forms/with-context (fn [ctx _] (seed! ctx) (f ctx))))


(defn act
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ctx input]
  ((:bel/fn (bel/get-action ::web/command)) ctx (merge {:connectionId c :commandId "attempt-1"} input)))


(defn rows
  {:malli/schema [:=> [:cat :map [:* :any]] :map]}
  [ctx & [state]]
  (into {} (map (juxt :id identity)) (:rows (web/table ctx c (or state "unmatched")))))


(deftest one-table-and-no-form
  (with-warehouses
    (fn [ctx]
      (let [html (forms/page ctx ::web/warehouse-matching {:connection-id c})]
        (is (= 1 (count (re-seq #"<inventory-grid" html))))
        (is (= 1 (count (re-seq #"<marketplace-workbench" html))))
        (is (not (str/includes? html "<form")))
        (is (str/includes? html "management-header"))
        (is (str/includes? html "href=\"/operations\">Back to operations<") "a link back to the operations page")
        (is (re-find #"aria-current=\"page\" href=\"/operations/marketplace/c-1/warehouses\"[^>]*>Warehouse matching · " html) "this view is current")
        (is (str/includes? html "data-command=\"confirm\""))))))


(deftest the-connection-page-links-to-the-table
  (with-warehouses
    (fn [ctx]
      (is (str/includes? (forms/page ctx ::connections-web/connection {:connection-id c})
                         "href=\"/operations/marketplace/c-1/warehouses\"")))))


(deftest ozon-text-is-escaped
  (with-warehouses
    (fn [ctx]
      (let [html (forms/page ctx ::web/warehouse-matching {:connection-id c})]
        (is (not (str/includes? html "<script>alert")))
        (is (str/includes? html "&lt;script&gt;alert(1)&lt;"))))))


(deftest rows-start-with-their-suggestion
  (with-warehouses
    (fn [ctx]
      (let [{hub-row hub seller-row seller} (rows ctx)]
        (is (= {:value "" :label "Hub"} (get-in hub-row [:cells :warehouse])) "FBO: a new warehouse under the provider's name")
        (is (= ["Hub" "FBO · 1"] (get-in hub-row [:cells :ozon])))
        (is (= {:value "main" :label "Main"} (get-in seller-row [:cells :warehouse])) "a seller warehouse: the one of its name")))))


(deftest confirm-several-rows-returns-one-descriptor
  (with-warehouses
    (fn [ctx]
      (let [descriptor (act ctx {:command "confirm"
                                 :rows    [{:id hub :revision 1 :values {:warehouse "" :warehouse-name " Hub "}}
                                           {:id seller :revision 1 :values {:warehouse "main"}}]})]
        (is (= {:type  :command :id ::warehouses/confirm-warehouse-mapping :command-id "attempt-1"
                :input {:rows [{:external-warehouse-id hub :revision 1 :warehouse-name "Hub"}
                               {:external-warehouse-id seller :revision 1 :warehouse-id "main"}]}
                :then  {:type :view :id ::grid/committed :input {:commandId "attempt-1"}}}
               (dissoc descriptor :catch)))
        (forms/accept! ctx descriptor)
        (is (= #{odd} (set (keys (rows ctx)))))
        (is (= #{hub seller} (set (keys (rows ctx "confirmed")))))
        (testing "the mapped table shows the warehouse and a Change action"
          (let [html (forms/page ctx ::web/warehouse-matching {:connection-id c :state "confirmed"})]
            (is (str/includes? html "data-command=\"change\""))
            (is (= {:value "main" :label "Main"} (get-in (rows ctx "confirmed") [seller :cells :warehouse])))))
        (testing "Change with a blank warehouse removes the mapping"
          (is (= [::warehouses/change-warehouse-mapping {:external-warehouse-id seller :mapping-revision 1}]
                 ((juxt :id :input) (act ctx {:command "change" :rows [{:id seller :revision 1 :values {:warehouse ""}}]}))))
          (forms/accept! ctx (act ctx {:command "change" :commandId "attempt-3" :rows [{:id seller :revision 1 :values {:warehouse ""}}]}))
          (is (contains? (rows ctx) seller)))))))


(deftest a-row-without-a-warehouse-is-not-sent
  (with-warehouses
    (fn [ctx]
      (let [answer (act ctx {:command "confirm" :rows [{:id odd :revision 1 :values {:warehouse "" :warehouse-name " "}}]})]
        (is (= :signals (:type answer)))
        (is (str/includes? (get-in answer [:data :_marketplaceCommandJson]) "\"path\":\"warehouse\""))))))


(deftest two-fbo-warehouses-render-on-their-rows
  (with-warehouses
    (fn [ctx]
      (let [input  {:connectionId c :commandId "attempt-2" :command "confirm"
                    :rows         [{:id hub :revision 1 :values {:warehouse "main"}}
                                   {:id odd :revision 1 :values {:warehouse "main"}}]}
            caught (handler/execute-action ctx ::web/command input)
            answer ((:bel/fn (bel/get-view ::grid/failed)) (:input caught))]
        (is (= ::grid/failed (:id caught)))
        (is (= [{:id hub :path "warehouse" :message "Already used for another Ozon warehouse: <script>alert(1)</script>."}
                {:id odd :path "warehouse" :message "Already used for another Ozon warehouse: Hub."}]
               (:rows answer)))
        (is (= #{hub seller odd} (set (keys (rows ctx)))) "nothing was accepted")))))


(deftest actions-reject-warehouses-of-other-connections
  (with-warehouses
    (fn [ctx]
      (listings/seed! ctx ::sync/external-warehouse-observed (model-test/observed "fbs" "7" "Elsewhere" :connection-id "c-2"))
      (let [status #(try (%) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))]
        (doseq [id ["c-2:fbs:7" "c-1:fbo:999"]]
          (is (= 404 (status #(act ctx {:command "confirm" :rows [{:id hub :revision 1 :values {:warehouse "main"}}
                                                                  {:id id :revision 1 :values {:warehouse "main"}}]})))))
        (is (= #{hub seller odd} (set (keys (rows ctx)))) "nothing was executed")))))

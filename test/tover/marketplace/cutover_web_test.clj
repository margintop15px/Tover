(ns tover.marketplace.cutover-web-test
  "Board chapter 44 screen: the Cutover view of the Operations page is one table with an instant picker above and
  the coverage table below; Propose answers with one command descriptor; rejections land on their rows; the pending
  entries show their observation in the Stock page's Preview."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.handler :as handler]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.categories.model]
    [tover.catalogues.products.model]
    [tover.catalogues.suppliers.model]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.references]
    [tover.inventory.stock.model]
    [tover.inventory.web :as inventory-web]
    [tover.marketplace.connections-test :as scenarios]
    [tover.marketplace.connections-web-test :as forms]
    [tover.marketplace.connections.web :as connections-web]
    [tover.marketplace.cutover-propose-test :as propose]
    [tover.marketplace.cutover.model :as cutover]
    [tover.marketplace.cutover.web :as web]
    [tover.marketplace.grid :as grid]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.stores.model 'tover.catalogues.products.model
                        'tover.inventory.stock-items.model 'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.inventory.entries.model 'tover.inventory.acceptance.model 'tover.marketplace.provenance
                        'tover.inventory.references 'tover.inventory.stock.model
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                        'tover.marketplace.warehouses.model 'tover.marketplace.cutover.model
                        'tover.shared.web.core 'tover.marketplace.connections.web 'tover.marketplace.grid
                        'tover.marketplace.products.web 'tover.marketplace.warehouses.web 'tover.marketplace.cutover.web
                        'tover.inventory.web
                        'tover.workspaces.currency.model])
  (f))


(use-fixtures :each bel-test/isolated-registry register scenarios/unit-authority)


(def c "c-1")
(def hub (str c ":fbo:1"))


(defn with-cutover
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (forms/with-context
    (fn [ctx _]
      (forms/accept! ctx (forms/act ctx ::connections-web/connect forms/connect-form))
      (propose/seed-cutover! ctx c false)
      (f ctx))))


(defn act
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ctx input]
  ((:bel/fn (bel/get-action ::web/command)) ctx (merge {:connectionId c :commandId "attempt-1" :command "propose"} input)))


(defn rows
  {:malli/schema [:=> [:cat :map] :map]}
  [ctx]
  (into {} (map (juxt :id identity)) (:rows (web/table ctx c nil))))


(def red-row
  {:id "red|main" :revision 1 :values {:quantity "120" :unit-cost "350,5"} :revisions {(keyword (str "p|" c ":p1")) 1 (keyword (str "w|" hub)) 1}})


(def blue-row
  {:id "blue|main" :revision 1 :values {:quantity "8" :unit-cost ""} :revisions {(keyword (str "p|" c ":p2")) 1 (keyword (str "w|" hub)) 1}})


(deftest one-table-view-of-the-operations-page
  (with-cutover
    (fn [ctx]
      (let [html (forms/page ctx ::web/cutover-view {:connection-id c})]
        (is (= 2 (count (re-seq #"<inventory-grid" html))) "the setup table and the coverage table")
        (is (= 1 (count (re-seq #"<form" html))) "the instant picker only")
        (is (str/includes? html "method=\"get\""))
        (is (str/includes? html "value=\"2026-11-20T10:00:00Z\"") "the instant starts at the last snapshot")
        (is (str/includes? html "Earliest allowed: 2026-11-01T00:00:00Z"))
        (is (str/includes? html "href=\"/operations\">Back to operations<"))
        (is (re-find #"aria-current=\"page\" href=\"/operations/marketplace/c-1/cutover\"[^>]*>Cutover · " html) "this view is current")
        (is (str/includes? html "data-command=\"propose\""))
        (is (str/includes? html "href=\"/?cutover=c-1\">Review and accept the proposed entries<"))
        (is (str/includes? html "An instant with its UTC offset"))))))


(deftest the-connection-page-links-to-the-view
  (with-cutover
    (fn [ctx]
      (is (str/includes? (forms/page ctx ::connections-web/connection {:connection-id c})
                         "href=\"/operations/marketplace/c-1/cutover\"")))))


(deftest rows-are-prefilled-from-the-snapshot
  (with-cutover
    (fn [ctx]
      (let [{red "red|main" blue "blue|main"} (rows ctx)]
        (is (= ["Mug / red" "Main" ["Ready"] ["120" "2026-11-20T10:00:00Z"] "Opening" "120"]
               ((juxt :item :warehouse :readiness :observed :result :quantity) (:cells red))))
        (is (= {(keyword (str "p|" c ":p1")) 1 (keyword (str "w|" hub)) 1} (:revisions red)))
        (is (= ["Opening" "Opening"] [(get-in red [:cells :result]) (get-in blue [:cells :result])]) "neither has history yet")
        (is (:selectable? red))))))


(deftest propose-returns-one-descriptor
  (with-cutover
    (fn [ctx]
      (let [descriptor (act ctx {:rows [red-row (assoc-in blue-row [:values :unit-cost] "100")]})]
        (is (= {:type  :command :id ::cutover/propose-marketplace-cutover :command-id "attempt-1"
                :input {:connection-id c :instant "2026-11-20T10:00:00Z"
                        :scopes        [{:stock-item-id             "red"             :warehouse-id                "main"  :quantity "120" :unit-cost "350,5"
                                         :product-mapping-revisions {(str c ":p1") 1} :warehouse-mapping-revisions {hub 1}}
                                        {:stock-item-id             "blue"            :warehouse-id                "main"  :quantity "8" :unit-cost "100"
                                         :product-mapping-revisions {(str c ":p2") 1} :warehouse-mapping-revisions {hub 1}}]}
                :then  {:type :view :id ::grid/committed :input {:commandId "attempt-1"}}}
               (dissoc descriptor :catch)))
        (forms/accept! ctx descriptor)
        (testing "the scopes are pending with what was proposed"
          (let [{red "red|main"} (rows ctx)]
            (is (= ["Pending" "Proposed 120, difference +120"] (get-in red [:cells :readiness])))
            (is (not (:selectable? red)))))
        (testing "the Stock page's Preview shows the observation on the pending cell"
          (let [model  (#'inventory-web/table-model ctx "units" false {:pending true})
                titles (keep :title (mapcat :cells (:rows model)))]
            (is (some #(str/includes? % "Cutover: observed 120, proposed 120, difference +120 from Tover's 0.") titles))))))))


(deftest cutover-review-is-the-shared-draft-preview-filtered-to-the-connection
  (with-cutover
    (fn [ctx]
      (forms/accept! ctx (act ctx {:rows [red-row]}))
      (runtime/execute! ctx ::entries/save-shared-inventory-entry
                        {:command-id "other" :input {:workspace-id scenarios/workspace :entry-id "other" :kind "opening"
                                                     :occurred-at  "2026-11-02T00:00:00Z"
                                                     :lines        [{:line-id   "l" :stock-item-id "blue" :warehouse-id "main" :quantity "3"
                                                                     :unit-cost "1"}]}})
      (let [{:keys [scope model]} (inventory-web/query-page ctx {:cutover c})
            html                  (forms/page ctx ::inventory-web/page {:cutover c})
            red-id                (str "cutover:" c "|red|main#1")]
        (testing "the Stock page opens in Preview narrowed to the connection's cutover entries"
          (is (= {:pending true :cutover c} scope))
          (is (str/includes? html "data-scope=\"{&quot;pending&quot;:true,&quot;cutover&quot;:&quot;c-1&quot;}\""))
          (is (str/includes? html "data-scope-label=\"Cutover entries\""))
          (is (= [red-id] (mapv :entry-id (:pending model))) "the other pending entry is left out")
          (is (= 2 (count (:pending (#'inventory-web/table-model ctx "units" false {:pending true})))) "without the scope both are pending"))
        (testing "Accept takes that selection through the shared-draft review; the other entry stays pending"
          (let [selection (mapv #(select-keys % [:entry-id :revision]) (:pending model))
                review    (runtime/read-model ctx ::acceptance/inventory-review {:selection selection :order [red-id]})]
            (is (:valid? review) (pr-str (:problems review)))
            (is (= :accepted (:status (forms/accept! ctx ((:bel/fn (bel/get-action ::inventory-web/accept)) ctx
                                                                                                            {:workspaceId scenarios/workspace   :selection selection :order [red-id]
                                                                                                             :fingerprint (:fingerprint review) :commandId "acc-1"})))))
            (is (= [["red" "main" "2026-11-20T10:00:00Z"]]
                   (mapv (juxt :stock-item-id :warehouse-id :instant)
                         (:baselines (runtime/read-model ctx ::cutover/baseline-coverage-by-stock-item-and-warehouse {:connection-id c})))))
            (is (= ["other"] (mapv :entry-id (:entries (runtime/read-model ctx ::entries/pending-entries {})))))))))))


(deftest rejections-land-on-their-rows
  (with-cutover
    (fn [ctx]
      (let [input  {:connectionId c :commandId "attempt-2" :command "propose" :rows [(assoc-in red-row [:values :unit-cost] " ") blue-row]}
            caught (handler/execute-action ctx ::web/command input)
            answer ((:bel/fn (bel/get-view ::grid/failed)) (:input caught))]
        (is (= ::grid/failed (:id caught)))
        (is (= [{:id "red|main" :path "unit-cost" :message "Enter a unit cost for this opening."}
                {:id "blue|main" :path "unit-cost" :message "Enter a unit cost for this opening."}]
               (:rows answer)))
        (is (= ["Ready" "Ready"] (map #(first (get-in % [:cells :readiness])) (vals (select-keys (rows ctx) ["red|main" "blue|main"]))))
            "nothing was proposed")))))


(deftest an-instant-out-of-range-names-the-earliest
  (with-cutover
    (fn [ctx]
      (let [caught (handler/execute-action ctx ::web/command {:connectionId c :commandId                 "attempt-3" :command "propose"
                                                              :instant      "2026-10-01T00:00:00Z" :rows [blue-row]})
            answer ((:bel/fn (bel/get-view ::grid/failed)) (:input caught))]
        (is (= "Choose an instant from 2026-11-01T00:00:00Z until now." (:message answer)))))))


(deftest a-member-cannot-propose
  (with-cutover
    (fn [ctx]
      (let [member (assoc ctx :memberships [{:workspace-id scenarios/workspace :role :member}])
            caught (handler/execute-action member ::web/command {:connectionId c :commandId "attempt-4" :command "propose" :rows [blue-row]})]
        (is (= "Only an Owner or Admin proposes the cutover." (:message ((:bel/fn (bel/get-view ::grid/failed)) (:input caught)))))))))

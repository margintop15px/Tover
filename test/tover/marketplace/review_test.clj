(ns tover.marketplace.review-test
  "Board chapter 45 slice 5 · Read imported operations needing review, through a store: every downloaded operation
  with its one next step, grouped by the decision that unblocks it, on the Operations page; set aside and bring back
  from a selection; the proposed correction accepted with its Sale; the waiting counts of the matching and cutover
  views; and no buyer data on the page."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [dev.onionpancakes.chassis.core :as chassis]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.operations.web :as operations-web]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.cutover.model :as cutover]
    [tover.marketplace.cutover.web :as cutover-web]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.operations-test :as ot]
    [tover.marketplace.operations.review :as review]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.products.web :as products-web]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.shared.web.core]
    [tover.support :as support]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.inventory.references 'tover.marketplace.connections.model 'tover.marketplace.sync.model
                          'tover.marketplace.products.model 'tover.marketplace.products.guard 'tover.marketplace.warehouses.model
                          'tover.marketplace.cutover.model 'tover.marketplace.provenance 'tover.marketplace.operations.model
                          'tover.marketplace.operations.review 'tover.workspaces.currency.model 'tover.shared.web.core
                          'tover.inventory.operations.web])
    (f))
  guard-test/unit-authority)


(def c ot/c)
(def workspace ot/workspace)
(def ^{:malli/schema (:malli/schema (meta #'ot/operation))} operation @#'ot/operation)
(def ^{:malli/schema (:malli/schema (meta #'ot/lines))} lines @#'ot/lines)
(def ^{:malli/schema (:malli/schema (meta #'ot/listing))} listing @#'ot/listing)
(def ^{:malli/schema (:malli/schema (meta #'ot/opening))} opening @#'ot/opening)
(def ^{:malli/schema (:malli/schema (meta #'ot/seed!))} seed! @#'ot/seed!)
(def ^{:malli/schema (:malli/schema (meta #'ot/seed-all!))} seed-all! @#'ot/seed-all!)
(def ^{:malli/schema (:malli/schema (meta #'ot/execute!))} execute! @#'ot/execute!)
(def ^{:malli/schema (:malli/schema (meta #'ot/accept-all!))} accept-all! @#'ot/accept-all!)
(def ^{:malli/schema (:malli/schema (meta #'ot/failure))} failure @#'ot/failure)


(defn- needing-review
  {:malli/schema [:=> [:cat :map [:* :any]] :map]}
  [ctx & {:as input}]
  (runtime/read-model ctx ::review/imported-operations-needing-review (or input {})))


(defn- groups
  "Each group as [label count]."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [result]
  (mapv (juxt :label :count) (:groups result)))


(defn- row-of
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [result source-operation-id]
  (some #(when (= source-operation-id (:source-operation-id %)) %) (mapcat :operations (:groups result))))


(defn- model
  "The Operations page's table, as the page sends it, for `view`."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ctx view]
  (charred/read-json (charred/write-json-str (:model (operations-web/query-page (assoc ctx :request {:tover/identity {:workspace-id workspace}}) view)))
                     :key-fn keyword))


(defn- act
  "The descriptor an Operations page action returns, executed as the member."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [ctx id input]
  (let [{command :id command-id :command-id input :input} ((:bel/fn (bel/get-action id)) ctx (assoc input :commandId (str (random-uuid))))]
    (execute! ctx command command-id input)))


;; Board examples


(deftest first-sync
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! source-id]} (ot/setup! repo)]
        (testing "Given 340 postings over 12 unmatched listings"
          (seed-all! ctx (concat [[:tover.inventory.stock-items.model/stock-item-registered
                                   {:stock-item-id "green" :product-id "mug" :variant-label "green" :unit "pcs" :condition "regular" :status "active" :revision 1}]]
                                 (opening "green" "home")
                                 (for [n (range 12)] [::sync/external-product-observed (listing (str (+ 300 n)) (str "Item " n))])))
          (doseq [n (range 340)] (observe! (operation (str "F" n) 1 :lines (lines (str (+ 300 (mod n 12))) "1"))))
          (let [result (needing-review ctx)]
            (testing "Then all are visible in 12 groups, most operations first"
              (is (= 12 (count (:groups result))))
              (is (= 340 (reduce + (map :count (:groups result)))))
              (is (= [29 29 29 29] (take 4 (map :count (:groups result)))))
              (is (= ["Match Item 0" 29] (first (groups result))))
              (is (every? #(= "match product" (:decision %)) (:groups result))))))
        (testing "and matching one listing moves its operations to their next step"
          (execute! ctx ::products/confirm-product-mapping "confirm-green"
                    {:rows [{:external-product-id (str c ":300") :listing-revision 1 :stock-item-id "green" :item-revision 1}]})
          (let [result (needing-review ctx)]
            (is (= 11 (count (filter #(= "match product" (:decision %)) (:groups result)))))
            (is (not-any? #(= "Match Item 0" (:label %)) (:groups result)))
            (is (= "automatic" (:next-step (row-of result (source-id "F0")))))))))))


(deftest cutover-accepted
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! source-id]} (ot/setup! repo)]
        (testing "Given 300 operations before and 40 after the new baseline"
          (doseq [n (range 300)] (observe! (operation (str "B" n) 1 :occurred-at "2026-10-20T09:00:00Z" :lines (lines "100" "1"))))
          (doseq [n (range 40)] (observe! (operation (str "A" n) 1 :lines (lines "100" "1")))))
        (let [result (needing-review ctx)]
          (testing "Then 300 become history and 40 continue"
            (is (= 300 (:count (some #(when (= "history" (:decision %)) %) (:groups result)))))
            (is (= 40 (count (remove #(= "history" (:next-step %)) (mapcat :operations (:groups result))))))
            (is (= "history" (:next-step (row-of result (source-id "B0")))))))
        (testing "history is hidden on the page until the next step is filtered"
          (let [shown   (model ctx {:source "imported"})
                step    (.indexOf ^java.util.List (mapv :key (:columns shown)) "next-step")
                steps   (set (map #(get-in % [:cells step :value]) (remove :header (:rows shown))))
                history (model ctx {:source "imported" :filters {"next-step" [{:type "eq" :value "History"}]}})]
            (is (not (contains? steps "History")))
            (is (= 300 (count (filter #(= "History" (get-in % [:cells step :value])) (:rows history)))))))))))


(deftest ozon-counted-differently
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! entry-of balance source-id]} (ot/setup! repo)]
        (testing "Given balance 2"
          (execute! ctx ::entries/save-shared-inventory-entry "m8"
                    {:workspace-id workspace :entry-id "m8" :kind "sale" :occurred-at "2026-11-05T09:00:00Z"
                     :lines        [{:line-id "m8/1" :stock-item-id "red" :warehouse-id "home" :quantity 8}]})
          (accept-all! ctx "acc-m8" [["m8" 1]])
          (is (= 2 (balance "red" "home"))))
        (testing "and an imported Sale of 3"
          (observe! (operation "P1" 1))
          (is (= 1 (intake!))))
        (let [row (row-of (needing-review ctx) (source-id "P1"))]
          (is (= "fix shortage" (:next-step row)))
          (is (= {:stock-item-id "red" :warehouse-id "home" :instant "2026-11-10T08:59:59Z" :count 3} (:suggested-correction row)))
          (testing "the page highlights the proposed correction before its sale"
            (let [shown (model ctx {:source "imported"})
                  ids   (mapv :id (remove :header (:rows shown)))
                  fix   (some #(when (str/includes? (or (:className %) "") "operations-suggested") %) (:rows shown))]
              (is (some? fix))
              (is (< (.indexOf ^java.util.List ids (:id fix)) (.indexOf ^java.util.List ids (str (entry-of "P1") "|" (source-id "P1") "/1"))))))
          (testing "When the member takes the proposed correction and accepts both"
            (act ctx ::operations-web/add-correction
                 (merge {:workspaceId workspace :entryId (entry-of "P1")}
                        (update-keys (:suggested-correction row) {:stock-item-id :stockItemId :warehouse-id :warehouseId :instant :instant :count :count})))
            (let [fix (operations-web/correction-entry-id (entry-of "P1"))]
              (is (= ["correction" 3] ((juxt :kind (comp :quantity first :lines)) (some #(when (= fix (:entry-id %)) %) (runtime/coeffect ::entries/pending ctx {})))))
              (accept-all! ctx "acc-both" [[fix 1] [(entry-of "P1") 1]])))
          (testing "Then the count becomes 3 just before the sale and 0 after it"
            (let [{:keys [records lines]} (runtime/coeffect ::acceptance/history ctx {})
                  steps                   (filter #(= ["red" "home"] [(:stock-item-id %) (:warehouse-id %)]) (:steps (valuation/replay records lines)))]
              (is (= [3 0] (map :balance-after (take-last 2 steps))))
              (is (= 0 (balance "red" "home"))))))))))


;; Brief


(deftest set-aside-and-bring-back-on-a-selection
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! entry-of source-id pending]} (ot/setup! repo)
            id (entry-of "P1")]
        (observe! (operation "P1" 1))
        (observe! (operation "P2" 1 :lines (lines "999" "1")))
        (is (= 1 (intake!)))
        (let [shown  (model ctx {:source c})
              chosen (:imported shown)]
          (testing "the page names every imported row's revisions, with the entry's revision when it shows one"
            (is (= {:sourceOperationId (source-id "P1") :revision 1 :entryRevision 1 :aside false} (get chosen (keyword id))))
            (is (= {:sourceOperationId (source-id "P2") :revision 1 :aside false}
                   (get chosen (keyword (str "import:" (source-id "P2"))))))
            (is (every? #(some :selection (:cells %)) (filter :entry (:rows shown))) "imported rows are selectable outside Preview"))
          (testing "a member's edit after the page read is never discarded silently"
            (execute! ctx ::entries/save-shared-inventory-entry "edit"
                      {:workspace-id workspace :entry-id id :kind "sale" :occurred-at "2026-11-10T09:00:00Z" :expected-revision 1
                       :lines        [{:line-id (str (source-id "P1") "/1") :stock-item-id "red" :warehouse-id "home" :quantity 2}]})
            (is (= :stale (failure #(act ctx ::operations-web/set-aside-operations
                                         {:workspaceId workspace :aside true
                                          :operations  [{:sourceOperationId (source-id "P1") :revision 1 :entryRevision 1}]}))))
            (is (= 2 (:revision (pending id))) "the edit stays")
            (is (= :stale (failure #(act ctx ::operations-web/set-aside-operations
                                         {:workspaceId workspace :aside true :operations [{:sourceOperationId (source-id "P1") :revision 1}]})))
                "a row sent without its entry revision says it saw no entry")))
        (testing "set aside both selected rows, then bring them back"
          (act ctx ::operations-web/set-aside-operations
               {:workspaceId workspace :aside true :reason "Not tracked"
                :operations  [{:sourceOperationId (source-id "P1") :revision 1 :entryRevision 2} {:sourceOperationId (source-id "P2") :revision 1}]})
          (is (nil? (pending id)))
          (let [result (needing-review ctx)]
            (is (= ["set aside" "set aside"] (mapv #(:next-step (row-of result (source-id %))) ["P1" "P2"]))))
          (let [chosen (:imported (model ctx {:source c :filters {"next-step" [{:type "eq" :value "Set aside"}]}}))]
            (is (= [true true] (mapv #(:aside (get chosen (keyword (str "import:" (source-id %))))) ["P1" "P2"]))))
          (act ctx ::operations-web/set-aside-operations
               {:workspaceId workspace :aside false
                :operations  [{:sourceOperationId (source-id "P1") :revision 1} {:sourceOperationId (source-id "P2") :revision 1}]})
          (is (= 1 (intake!)))
          (is (= "match product" (:next-step (row-of (needing-review ctx) (source-id "P2"))))))))))


(deftest imported-entries-among-the-other-pending-entries
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! entry-of]} (ot/setup! repo)]
        (execute! ctx ::entries/save-shared-inventory-entry "manual"
                  {:workspace-id workspace :entry-id "manual" :kind "sale" :occurred-at "2026-11-11T09:00:00Z"
                   :lines        [{:line-id "manual/1" :stock-item-id "blue" :warehouse-id "home" :quantity 1}]})
        (observe! (operation "P1" 1 :occurred-at nil :terminal false :missing (observations/write-json ["actual time"])))
        (observe! (operation "P2" 1 :lines (lines "999" "1")))
        (is (= 1 (intake!)))
        (let [shown (model ctx {})
              at    #(.indexOf ^java.util.List (mapv :key (:columns shown)) %)
              row   (fn [group] (some #(when (= group (:groupId %)) %) (:rows shown)))]
          (is (= #{"manual" (entry-of "P1")} (set (keep :groupId (:rows shown)))) "an operation without an entry is shown only under the filter")
          (is (= ["Ozon · Shop" "Waiting for Ozon"] (mapv #(get-in (row (entry-of "P1")) [:cells (at %) :value]) ["source" "next-step"])))
          (is (= ["" ""] (mapv #(get-in (row "manual") [:cells (at %) :value]) ["source" "next-step"]))))))))


(deftest a-group-opens-matching-with-its-listing
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe!]} (ot/setup! repo)]
        (observe! (operation "P2" 1 :lines (lines "999" "1")))
        (seed! ctx ::sync/external-product-observed (listing "999" "Spoon"))
        (let [header (some #(when (:header %) %) (:rows (model ctx {:source "imported"})))]
          (is (= "Match Spoon – 1 operation" (get-in header [:cells 0 :value])))
          (is (= (str "/operations/marketplace/" c "/products?listing=" (java.net.URLEncoder/encode (str c ":999") "UTF-8"))
                 (get-in header [:cells 0 :actions 0 :href]))))))))


(deftest waiting-operations-of-listings-and-scopes
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake!]} (ot/setup! repo)]
        (seed! ctx ::sync/external-product-observed (listing "999" "Spoon"))
        (observe! (operation "P1" 1 :lines (lines "100" "12")))
        (observe! (operation "P2" 1 :lines (lines "999" "1")))
        (observe! (operation "P3" 1 :lines (lines "999" "2")))
        (observe! (operation "P4" 1 :occurred-at "2026-10-20T09:00:00Z"))
        (intake!)
        (testing "a listing's waiting operations are those its line keeps in Review"
          (is (= {(str c ":999") 2 (str c ":100") 1} (products/waiting-operations ctx c))))
        (testing "a baselined scope shows the imported operations still waiting on it"
          (is (= {["red" "home"] 1} (cutover/waiting-operations ctx c)))
          (is (= "1" (some #(when (= (cutover/scope-id "red" "home") (:id %)) (get-in % [:cells :waiting]))
                           (:rows (cutover-web/coverage-table ctx c))))))
        (testing "the connection counts its operations by next step"
          (is (= {"fix shortage" 1 "match product" 2 "history" 1} (connections/count-part :next-steps ctx c))))
        (testing "counts that cannot be read are unknown: nil, shown as —, never zero"
          (with-redefs [tover.marketplace.operations.model/load-facts (fn [& _] (throw (ex-info "down" {})))]
            (is (nil? (products/waiting-operations ctx c)))
            (is (nil? (cutover/waiting-operations ctx c)))
            (is (nil? (connections/count-part :next-steps ctx c)))
            (is (= "—" (some #(when (= (cutover/scope-id "red" "home") (:id %)) (get-in % [:cells :waiting]))
                             (:rows (cutover-web/coverage-table ctx c)))))
            (is (= "—" (some #(when (= (str c ":999") (:id %)) (get-in % [:cells :waiting])) (:rows (products-web/table ctx c "unmatched")))))))))))


(deftest no-buyer-data-on-the-page
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake!]} (ot/setup! repo)
            buyer (observations/write-json {:customer {:name "Ivan Petrov" :phone "+79990001122" :address "Lenina 1, Tver"}})]
        (observe! (operation "P1" 1 :facts buyer))
        (observe! (operation "P2" 1 :facts buyer :lines (lines "999" "1")))
        (intake!)
        (let [{:bel/keys [fn query]} (bel/get-page ::operations-web/page)
              html                   (chassis/html (:html (fn (query (assoc ctx :request {:tover/identity {:workspace-id workspace}}) {:source "imported"}))))]
          (is (str/includes? html "Ozon · Shop") "the imported rows are on the page")
          (doseq [text ["Ivan" "Petrov" "79990001122" "Lenina"]]
            (is (not (str/includes? html text)) text)))))))

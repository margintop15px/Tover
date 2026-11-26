(ns tover.marketplace.operations-test
  "Board chapter 45 slices 1 and 2 · Notice a new source operation and Propose the imported operation: intake
  through a store, from a downloaded operation to a pending shared entry the connector saved, its replacement,
  conflict and withdrawal, and the refusals of `Propose Imported Operation`."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.categories.model]
    [tover.catalogues.products.model]
    [tover.catalogues.records :as records]
    [tover.catalogues.stores.model]
    [tover.catalogues.suppliers.model]
    [tover.catalogues.warehouses.model]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.actors :as actors]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock-items.model]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.operations.model :as operations]
    [tover.marketplace.operations.steps :as steps]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.support :as support]
    [tover.workspaces.currency.model]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                          'tover.marketplace.products.guard 'tover.marketplace.warehouses.model 'tover.marketplace.provenance
                          'tover.marketplace.operations.model 'tover.workspaces.currency.model])
    (f))
  guard-test/unit-authority)


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-0000000000b1")
(def c "00000000-0000-0000-0000-00000000c045")
(def connector (sync/connector-actor c))
(def at "2026-11-20T10:00:00Z")
(def seller (str c ":fbs:3"))
(def hub (str c ":fbo:7"))


(defn- seed-all!
  "Applies the events in one transaction."
  {:malli/schema [:=> [:cat :map [:sequential [:tuple :qualified-keyword :map]]] :nil]}
  [ctx events]
  (store/with-store (assoc ctx :bel/definitions (bel/operations)) :catalogue :serializable
    (fn [tx] (doseq [[type data] events] (runtime/apply-event! tx {:type type :data data}))))
  nil)


(defn- seed!
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :nil]}
  [ctx type data]
  (seed-all! ctx [[type data]]))


(defn- listing
  {:malli/schema [:=> [:cat :string :string] :map]}
  [sku name]
  (let [id (str c ":" sku)]
    {:external-product-id id :entity-id id :connection-id c :source-id sku :revision 1
     :payload-hash        (apply str (repeat 64 "a")) :adapter-version "test" :observed-at at :last-observed-at at
     :source-revision-id  (str id "#1") :name name :archived false
     :identifiers         (observations/write-json [{:type "sku" :value sku :active true}])
     :facts               (observations/write-json {:base {:name name}})}))


(defn- lines
  {:malli/schema [:=> [:cat [:* :any]] :string]}
  [& sku-quantities]
  (observations/write-json (vec (map-indexed (fn [i [sku quantity]] {:line-key (str (inc i)) :identifiers [{:type "sku" :value sku}] :quantity quantity})
                                             (partition 2 sku-quantities)))))


(defn- operation
  "A delivered FBS posting of 3 red mugs from the seller warehouse on the 10th, at `revision`."
  {:malli/schema [:=> [:cat :string :int [:* :any]] :map]}
  [source-id revision & {:as fields}]
  (let [id (str c ":fbs-posting:" source-id)]
    (into {} (remove (comp nil? val))
          (merge {:source-operation-id id :entity-id id :connection-id c :source-id source-id :revision revision
                  :payload-hash        (apply str (repeat 64 (str revision))) :adapter-version "test" :observed-at at :last-observed-at at
                  :source-revision-id  (str id "#" revision)                  :facts           (observations/write-json {})
                  :kind                "fbs-posting" :status "delivered" :terminal true :occurred-at "2026-11-10T09:00:00Z"
                  :from-warehouse      seller :lines (lines "100" "3") :complete true}
                 fields))))


(defn- warehouse-mapped
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [external type warehouse]
  {:external-warehouse-id external :mapping-revision-id (warehouses/revision-id external 1) :connection-id c :warehouse-type type
   :mapping-revision      1        :changed-by          (str member) :changed-at at :change "mapped" :warehouse-id warehouse :state "confirmed"})


(defn- opening
  "Accepted opening of 10 `item` in `warehouse` on the 1st and its cutover baseline."
  {:malli/schema [:=> [:cat :string :string] [:vector [:tuple :qualified-keyword :map]]]}
  [item warehouse]
  (let [record (str "opening-" item "-" warehouse)
        rid    (acceptance/record-revision-id record 1)
        day    (java.time.Instant/parse "2026-11-01T00:00:00Z")]
    [[::acceptance/inventory-changes-accepted {:acceptance-id (str "a-" rid) :actor member :recorded-at day :record-count 1}]
     [::acceptance/inventory-record-accepted {:record-revision-id rid       :record-id   record :revision 1 :acceptance-id (str "a-" rid)
                                              :kind               "opening" :occurred-at day    :sequence 0 :cancelled     false}]
     [::acceptance/inventory-record-line-accepted {:record-line-id (acceptance/record-line-id rid 0) :record-revision-id rid :position 0
                                                   :stock-item-id  item :warehouse-id warehouse :quantity-change 10 :unit-cost "350.00"}]
     [::provenance/cutover-baseline-established {:baseline-id    (provenance/baseline-id c item warehouse) :connection-id c :stock-item-id item
                                                 :warehouse-id   warehouse :instant "2026-11-01T00:00:00Z"                  :record-id     record
                                                 :established-at "2026-11-02T00:00:00Z"}]]))


(defn- execute!
  {:malli/schema [:=> [:cat :map :qualified-keyword :string :map] :any]}
  [ctx id command-id input]
  (runtime/execute! ctx id {:command-id command-id :input input}))


(defn- failure
  {:malli/schema [:=> [:cat fn?] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn- records-of
  {:malli/schema [:=> [:cat :map :qualified-keyword :keyword] [:vector :map]]}
  [ctx model id-key]
  (records/all-records ctx model id-key))


(defn setup!
  "A connected workspace: listings 100 (red) and 200 (blue) confirmed, the seller warehouse confirmed as Home and
  the FBO hub as Ozon 7, each item with 10 in each warehouse from the 1st, its cutover."
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (let [marketplace  {:dirty (atom {}) :swept (atom nil)}
        system       (support/bel-system {:repo repo :marketplace marketplace})
        ctx          (runtime/prepare-context {:bel/system  system :repo repo :workspace-id workspace :user-id member
                                               :memberships [{:workspace-id workspace :role :member}]})
        as-connector {:workspace-id workspace :bel/system system :bel/service ::operations/marketplace-operation-intake :user-id connector}
        source-id    #(str c ":fbs-posting:" %)
        entry-of     #(provenance/import-entry-id (source-id %))]
    (seed-all! ctx (concat
                     [[::connections/marketplace-account-connected
                       {:connection-id     c        :workspace-id     (str workspace) :provider         "ozon"     :provider-account-id "123"    :account-label "Shop"
                        :secret-ref        "secret" :account-currency "RUB"           :readable-domains "products" :sync-state          "active"
                        :credentials-state "valid"  :connected-by     (str member)    :connected-at     at}]
                      [:tover.catalogues.products.model/product-registered {:product-id "mug" :name "Mug" :status "active" :revision 1}]
                      [:tover.inventory.stock-items.model/stock-item-registered
                       {:stock-item-id "red" :product-id "mug" :variant-label "red" :unit "pcs" :condition "regular" :status "active" :revision 1}]
                      [:tover.inventory.stock-items.model/stock-item-registered
                       {:stock-item-id "blue" :product-id "mug" :variant-label "blue" :unit "pcs" :condition "regular" :status "active" :revision 1}]
                      [:tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "home" :name "Home" :status "active" :revision 1}]
                      [:tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "ozon-7" :name "Ozon 7" :status "active" :revision 1}]
                      [::sync/external-product-observed (listing "100" "Mug red")]
                      [::sync/external-product-observed (listing "200" "Mug blue")]
                      [::sync/external-warehouse-observed
                       {:external-warehouse-id seller :entity-id seller :connection-id c :source-id "3" :revision 1
                        :payload-hash          (apply str (repeat 64 "b"))  :adapter-version "test"     :observed-at    at    :last-observed-at at
                        :source-revision-id    (str seller "#1")            :name            "Seller 3" :warehouse-type "fbs" :active           true
                        :facts                 (observations/write-json {})}]
                      [::warehouses/external-warehouse-mapped (warehouse-mapped seller "fbs" "home")]
                      [::warehouses/external-warehouse-mapped (warehouse-mapped hub "fbo" "ozon-7")]]
                     (opening "red" "home") (opening "blue" "home") (opening "red" "ozon-7")))
    (execute! ctx ::products/confirm-product-mapping "confirm"
              {:rows [{:external-product-id (str c ":100") :listing-revision 1 :stock-item-id "red" :item-revision 1}
                      {:external-product-id (str c ":200") :listing-revision 1 :stock-item-id "blue" :item-revision 1}]})
    {:ctx          ctx
     :system       system
     :as-connector as-connector
     :source-id    source-id
     :entry-of     entry-of
     :observe!     (fn [op] (seed! ctx ::sync/source-operation-observed op))
     :intake!      (fn []
                     ;; As `runtime/run-processors!` runs this processor, with a full sweep each time.
                     (reset! (:swept marketplace) nil)
                     (let [found (runtime/coeffect ::operations/operations-to-propose {:bel/system system} {})]
                       (doseq [request (map operations/marketplace-operation-intake found)]
                         (runtime/execute! (merge (:scope request) {:bel/system system           :bel/service ::operations/marketplace-operation-intake
                                                                    :user-id    (:actor request)})
                                           (:id request)
                                           {:command-id (runtime/processor-command-id ::operations/marketplace-operation-intake request)
                                            :input      (:input request)}))
                       (count found)))
     :propose!     (fn [command-id input]
                     (execute! as-connector ::operations/propose-imported-operation command-id input))
     :entry        (fn [id] (some #(when (= id (:entry-id %)) %) (records-of ctx ::entries/entries :entry-id)))
     :pending      (fn [id] (some #(when (= id (:entry-id %)) %) (runtime/coeffect ::entries/pending ctx {})))
     :import       (fn [id] (some #(when (= id (:source-operation-id %)) %) (records-of ctx ::provenance/imports :source-operation-id)))
     :balance      (fn [item warehouse]
                     (let [{:keys [records lines]} (runtime/coeffect ::acceptance/history ctx {})]
                       (get-in (valuation/replay records lines) [:balances [item warehouse] :quantity] 0)))
     :classify     (fn [& ids] (let [facts (operations/load-facts ctx c)
                                     found (mapv #(:next-step (steps/classify facts (get-in facts [:operations %]))) ids)]
                                 (if (next ids) found (first found))))}))


(defn- save!
  "A member's save of the import entry."
  {:malli/schema [:=> [:cat :map :string :int :map] :any]}
  [ctx entry-id revision fields]
  (execute! ctx ::entries/save-shared-inventory-entry (str "save-" entry-id "-" revision)
            (merge {:workspace-id workspace :entry-id entry-id :expected-revision revision :kind "sale"} fields)))


(defn- accept!
  {:malli/schema [:=> [:cat :map :string :int] :any]}
  [ctx entry-id revision]
  (let [selection {:selection [{:entry-id entry-id :revision revision}] :order [entry-id]}
        review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
    (is (:valid? review) (pr-str (:problems review)))
    (execute! ctx ::acceptance/accept-reviewed-inventory-changes "accept"
              (merge selection {:workspace-id workspace :acceptance-id "acc-1" :fingerprint (:fingerprint review)}))))


;; Board 45 slice 2 · examples


(deftest posting-without-handover-time
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! intake! pending import balance source-id entry-of]} (setup! repo)]
        (testing "Given a delivered posting without a handover date, mapped and baselined"
          (observe! (operation "P1" 1 :occurred-at nil :complete false :missing (observations/write-json ["actual time"])))
          (is (= 1 (intake!)))
          (testing "then a pending Sale missing \"actual time\" is listed"
            (let [entry (pending (entry-of "P1"))]
              (is (= ["sale" "pending" 1 connector nil] ((juxt :kind :status :revision :saved-by :occurred-at) entry)))
              (is (= [{:line-id (str (source-id "P1") "/1") :stock-item-id "red" :quantity 3 :warehouse-id "home"}] (:lines entry))))
            (is (= [1 (entry-of "P1") 1 false ["actual time"]]
                   ((juxt :revision :entry-id :entry-revision :source-conflict (comp observations/read-json :missing)) (import (source-id "P1"))))))
          (testing "and stock is unchanged"
            (is (= 10 (balance "red" "home")))))))))


(deftest member-fills-the-gap
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending balance source-id entry-of]} (setup! repo)
            id (entry-of "P1")]
        (observe! (operation "P1" 1 :occurred-at nil :complete false :missing (observations/write-json ["actual time"])))
        (intake!)
        (testing "When a member enters the time and accepts"
          (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                           :lines       [{:line-id (str (source-id "P1") "/1") :stock-item-id "red" :warehouse-id "home" :quantity 3}]})
          (is (= 0 (intake!)) "intake keeps the member's entry")
          (is (= member (:saved-by (pending id))))
          (accept! ctx id 2)
          (testing "then the Sale is accepted and stock drops"
            (is (nil? (pending id)))
            (is (= 7 (balance "red" "home")))
            (is (= 0 (intake!)) "an accepted entry is never proposed again")))))))


(deftest old-operation
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! intake! entry source-id entry-of classify]} (setup! repo)]
        (testing "Given a posting before the cutover"
          (observe! (operation "P0" 1 :occurred-at "2026-10-20T09:00:00Z"))
          (is (= 0 (intake!)))
          (testing "then it has no entry and is listed under History"
            (is (nil? (entry (entry-of "P0"))))
            (is (= "history" (classify (source-id "P0"))))))))))


(deftest nothing-matched-yet
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx intake! source-id classify]} (setup! repo)
            skus (map #(str (+ 900 %)) (range 12))]
        (testing "Given 340 postings naming 12 unmatched listings"
          (seed-all! ctx (concat (for [sku skus] [::sync/external-product-observed (listing sku (str "Listing " sku))])
                                 (for [n (range 340)]
                                   [::sync/source-operation-observed (operation (str "U" n) 1 :lines (lines (nth skus (mod n 12)) "1"))])))
          (is (= 0 (intake!)))
          (testing "then all 340 are visible and no entry exists"
            (is (empty? (filter (comp provenance/import-entry-id? :entry-id) (records-of ctx ::entries/entries :entry-id))))
            (is (= {"match product" 340} (frequencies (apply classify (map #(source-id (str "U" %)) (range 340))))))))))))


(deftest fbo-return-received-by-ozon
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending classify]} (setup! repo)
            sale (str c ":fbo-posting:F0")
            return (str c ":return:R1")]
        (testing "Given an FBO return received by Ozon whose sale left warehouse W"
          (observe! (operation "F0" 1 :source-operation-id sale :entity-id sale :source-revision-id (str sale "#1") :kind "fbo-posting"
                               :occurred-at "2026-11-05T09:00:00Z" :from-warehouse hub :lines (lines "100" "1")))
          (seed! ctx ::provenance/source-operation-accepted
                 {:source-operation-id sale  :connection-id c    :revision    1  :record-id "sale-f0" :accepted-by (str connector)
                  :edited              false :mappings-used "{}" :accepted-at at})
          (observe! (operation "R1" 1 :source-operation-id return :entity-id return :source-revision-id (str return "#1") :kind "return"
                               :return-schema "fbo" :status "ReturnedToOzon" :occurred-at "2026-11-12T09:00:00Z" :from-warehouse nil
                               :related-operation sale :lines (lines "100" "1")))
          (is (= 1 (intake!)) "the accepted sale is not proposed again")
          (testing "then a pending Customer return is prefilled with W and waits for a member"
            (let [entry (pending (provenance/import-entry-id return))]
              (is (= "customer-return" (:kind entry)))
              (is (= ["ozon-7"] (map :warehouse-id (:lines entry)))))
            (is (= "confirm return warehouse" (classify return)))))))))


;; Propose Imported Operation · rules and rejections


(deftest repeat-emits-nothing
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! intake! propose! entry import source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (is (= 1 (intake!)))
        (let [before [(entry (entry-of "P1")) (import (source-id "P1"))]]
          (is (= 0 (intake!)) "the next round finds nothing to do")
          (is (= [] (:event-ids (propose! "again" {:source-operation-id (source-id "P1") :revision 1})))
              "a repeat for the same revision emits nothing")
          (is (= before [(entry (entry-of "P1")) (import (source-id "P1"))])))))))


(deftest newer-revision-replaces-unedited
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! intake! pending import source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (observe! (operation "P1" 2 :lines (lines "100" "2")))
        (is (= 1 (intake!)))
        (let [entry (pending (entry-of "P1"))]
          (is (= [2 connector [2]] [(:revision entry) (:saved-by entry) (mapv :quantity (:lines entry))])))
        (is (= [2 2 false] ((juxt :revision :entry-revision :source-conflict) (import (source-id "P1")))))))))


(deftest newer-revision-over-an-edit-is-a-conflict
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! propose! pending import source-id entry-of classify]} (setup! repo)
            id (entry-of "P1")]
        (observe! (operation "P1" 1))
        (intake!)
        (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                         :lines       [{:line-id (str (source-id "P1") "/1") :stock-item-id "red" :warehouse-id "home" :quantity 1}]})
        (observe! (operation "P1" 2 :lines (lines "100" "2")))
        (is (= :stale (failure #(propose! "raced" {:source-operation-id (source-id "P1") :revision 2 :entry-revision 1})))
            "a member's save after intake read the entry is stale")
        (is (= 1 (intake!)))
        (testing "the member's entry is kept"
          (let [entry (pending id)]
            (is (= [2 member [1]] [(:revision entry) (:saved-by entry) (mapv :quantity (:lines entry))]))))
        (testing "and a source conflict is recorded"
          (is (= [2 1 true] ((juxt :revision :entry-revision :source-conflict) (import (source-id "P1")))))
          (is (= "resolve conflict" (classify (source-id "P1")))))
        (is (= 0 (intake!)) "recorded once")))))


(deftest set-aside-is-refused
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! propose! entry source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (seed! ctx ::provenance/source-operation-set-aside
               {:source-operation-id (source-id "P1") :connection-id c :revision 1 :aside true :set-by (str member) :set-at at})
        (is (= :set-aside (failure #(propose! "p" {:source-operation-id (source-id "P1") :revision 1}))))
        (is (= 0 (intake!)))
        (is (nil? (entry (entry-of "P1"))))))))


(deftest already-accepted-is-refused
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! propose! entry source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (seed! ctx ::provenance/source-operation-accepted
               {:source-operation-id (source-id "P1") :connection-id c    :revision    1  :record-id "sale-1" :accepted-by (str member)
                :edited              false            :mappings-used "{}" :accepted-at at})
        (is (= :already-accepted (failure #(propose! "p" {:source-operation-id (source-id "P1") :revision 1}))))
        (is (= 0 (intake!)))
        (is (nil? (entry (entry-of "P1"))))))))


(deftest mapping-changed-since-read-is-stale
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! propose! entry source-id entry-of]} (setup! repo)
            input {:source-operation-id (source-id "P1") :revision 1 :product-mapping-revisions {(str c ":100") 1}}]
        (observe! (operation "P1" 1))
        (execute! ctx ::products/change-product-mapping "remap"
                  {:external-product-id (str c ":100") :mapping-revision 1 :stock-item-id "blue" :item-revision 1})
        (is (= :stale (failure #(propose! "p" input))) "the mapping intake read is no longer current")
        (is (= :stale (failure #(propose! "p2" {:source-operation-id (source-id "P1") :revision 2}))) "nor is the revision")
        (is (nil? (entry (entry-of "P1"))))))))


(deftest paused-connection
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! propose! entry source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (seed! ctx ::connections/marketplace-connection-paused
               {:connection-id c :sync-state "paused" :reason "manager" :paused-by (str member) :paused-at at})
        (is (= 0 (intake!)) "a paused connection proposes nothing")
        (is (= :connection-paused (failure #(propose! "p" {:source-operation-id (source-id "P1") :revision 1}))))
        (is (nil? (entry (entry-of "P1"))))))))


(deftest remapped-unedited-entry-follows-the-mapping
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending import source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (execute! ctx ::products/change-product-mapping "remap"
                  {:external-product-id (str c ":100") :mapping-revision 1 :stock-item-id "blue" :item-revision 1})
        (is (= 1 (intake!)))
        (let [entry (pending (entry-of "P1"))]
          (is (= [2 connector ["blue"]] [(:revision entry) (:saved-by entry) (mapv :stock-item-id (:lines entry))])))
        (is (= [1 2] ((juxt :revision :entry-revision) (import (source-id "P1")))) "the same source revision")))))


(deftest no-longer-resolvable-unedited-entry-is-withdrawn
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! entry pending import source-id entry-of classify]} (setup! repo)]
        (observe! (operation "P1" 1))
        (observe! (operation "P2" 1 :lines (lines "200" "1")))
        (intake!)
        (save! ctx (entry-of "P2") 1 {:occurred-at "2026-11-10T09:00:00Z"
                                      :lines       [{:line-id (str (source-id "P2") "/1") :stock-item-id "blue" :warehouse-id "home" :quantity 2}]})
        (execute! ctx ::warehouses/change-warehouse-mapping "unmap" {:external-warehouse-id seller :mapping-revision 1})
        (is (= 1 (intake!)) "only the unedited entry is withdrawn")
        (testing "the unedited entry is withdrawn and the operation waits for its match"
          (is (= "discarded" (:status (entry (entry-of "P1")))))
          (is (nil? (pending (entry-of "P1"))))
          (is (nil? (import (source-id "P1"))))
          (is (= "match warehouse" (classify (source-id "P1")))))
        (testing "a member's edit is kept"
          (is (= member (:saved-by (pending (entry-of "P2"))))))
        (testing "matched again, it is proposed again"
          (execute! ctx ::warehouses/change-warehouse-mapping "remap" {:external-warehouse-id seller :mapping-revision 2 :warehouse-id "home"})
          (is (= 1 (intake!)))
          (is (= ["pending" connector 3] ((juxt :status :saved-by :revision) (pending (entry-of "P1"))))))))))


(deftest history-shows-ozon-account
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (testing "the connector's actor id, a bare UUID, is labelled with its Ozon account"
          (is (= "Ozon · Shop" (:actor-label (first (actors/enrich ctx [{:actor (str (:saved-by (pending (entry-of "P1"))))}])))))
          (is (= "Ozon · Shop" (:actor-label (first (actors/enrich ctx [{:actor (str connector)}]))))))))))

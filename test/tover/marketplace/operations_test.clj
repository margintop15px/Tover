(ns tover.marketplace.operations-test
  "Board chapter 45 slices 1, 2, a and 6 · Notice a new source operation, Propose the imported operation, Set an
  operation aside and Accept reviewed imported changes: intake through a store, from a downloaded operation to a
  pending shared entry the connector saved, its replacement, conflict and withdrawal, the refusals of `Propose
  Imported Operation`, setting aside and bringing back, discarding, and a member's acceptance."
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


(defn- listing-of
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [conn sku name]
  (let [id (str conn ":" sku)]
    {:external-product-id id :entity-id id :connection-id conn :source-id sku :revision 1
     :payload-hash        (apply str (repeat 64 "a")) :adapter-version "test" :observed-at at :last-observed-at at
     :source-revision-id  (str id "#1") :name name :archived false
     :identifiers         (observations/write-json [{:type "sku" :value sku :active true}])
     :facts               (observations/write-json {:base {:name name}})}))


(defn- listing
  {:malli/schema [:=> [:cat :string :string] :map]}
  [sku name]
  (listing-of c sku name))


(defn- lines
  {:malli/schema [:=> [:cat [:* :any]] :string]}
  [& sku-quantities]
  (observations/write-json (vec (map-indexed (fn [i [sku quantity]] {:line-key (str (inc i)) :identifiers [{:type "sku" :value sku}] :quantity quantity})
                                             (partition 2 sku-quantities)))))


(defn- operation-of
  "A delivered FBS posting of connection `conn`: 3 red mugs from its seller warehouse on the 10th, at `revision`."
  {:malli/schema [:=> [:cat :string :string :int [:* :any]] :map]}
  [conn source-id revision & {:as fields}]
  (let [id (str conn ":fbs-posting:" source-id)]
    (into {} (remove (comp nil? val))
          (merge {:source-operation-id id :entity-id id :connection-id conn :source-id source-id :revision revision
                  :payload-hash        (apply str (repeat 64 (str revision))) :adapter-version "test" :observed-at                    at   :last-observed-at at
                  :source-revision-id  (str id "#" revision)                  :facts           (observations/write-json {})
                  :kind                "fbs-posting" :status "delivered" :terminal true :occurred-at "2026-11-10T09:00:00Z"
                  :from-warehouse      (str conn ":fbs:3")                    :lines           (lines "100" "3")            :complete true}
                 fields))))


(defn- operation
  "A delivered FBS posting of 3 red mugs from the seller warehouse on the 10th, at `revision`."
  {:malli/schema [:=> [:cat :string :int [:* :any]] :map]}
  [source-id revision & {:as fields}]
  (apply operation-of c source-id revision (mapcat identity fields)))


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
     :marketplace  marketplace
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
     :automatic!   (fn []
                     ;; As `runtime/run-processors!` runs Automatic Acceptance, with a full sweep each time; a refused
                     ;; request is counted, never thrown. The number of acceptances it sent.
                     (reset! (:swept marketplace) nil)
                     (let [found (runtime/coeffect ::operations/operations-to-accept {:bel/system system} {})]
                       (doseq [request (map operations/automatic-acceptance found)]
                         (try (runtime/execute! (merge (:scope request) {:bel/system system           :bel/service ::operations/automatic-acceptance
                                                                         :user-id    (:actor request)})
                                                (:id request)
                                                {:command-id (runtime/processor-command-id ::operations/automatic-acceptance request)
                                                 :input      (:input request)})
                              (catch clojure.lang.ExceptionInfo _ nil)))
                       (count found)))
     :accept-auto! (fn [command-id input]
                     (execute! (assoc as-connector :bel/service ::operations/automatic-acceptance)
                               ::operations/accept-imported-operation-automatically command-id input))
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


;; Board 45 slice a · Set an operation aside


(defn- set-aside!
  {:malli/schema [:=> [:cat :map :string [:sequential :map] :boolean [:* :any]] :any]}
  [ctx command-id operations aside & {:as more}]
  (execute! ctx ::operations/set-source-operations-aside command-id (merge {:operations operations :aside aside} more)))


(defn- aside-row
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [ctx id]
  (some #(when (= id (:source-operation-id %)) %) (records-of ctx ::provenance/asides :source-operation-id)))


(defn- acceptance-row
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [ctx id]
  (some #(when (= id (:source-operation-id %)) %) (records-of ctx ::provenance/source-acceptances :source-operation-id)))


(defn- marked?
  "Whether intake's dirty set comes to hold the connection: effects run on a thread of their own after commit."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [marketplace]
  (loop [tries 200]
    (cond (contains? (get @(:dirty marketplace) :marketplace-operation-intake #{}) c) true
          (zero? tries) false
          :else (do (Thread/sleep 10) (recur (dec tries))))))


(deftest not-tracked-stock
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx marketplace intake! entry pending import balance source-id entry-of classify]} (setup! repo)
            ids (mapv #(str "B" %) (range 20))
            rows (fn [] (mapv #(hash-map :source-operation-id (source-id %) :revision 1) ids))]
        (testing "Given 20 operations of a listing the business does not track"
          (seed-all! ctx (for [id ids] [::sync/source-operation-observed (operation id 1 :lines (lines "200" "1"))]))
          (is (= 20 (intake!)))
          (testing "When a member sets them aside"
            (reset! (:dirty marketplace) {})
            (set-aside! ctx "aside" (rows) true :reason "Not tracked")
            (is (marked? marketplace))
            (testing "then they leave the list: their entries are removed in the same commit"
              (is (every? #(nil? (pending (entry-of %))) ids))
              (is (every? #(= "discarded" (:status (entry (entry-of %)))) ids))
              (is (every? #(nil? (import (source-id %))) ids))
              (is (= [true "Not tracked" (str member) 1 (entry-of "B0")]
                     ((juxt :aside :reason :set-by :revision :removed-entry-id) (aside-row ctx (source-id "B0")))))
              (is (= {"set aside" 20} (frequencies (apply classify (map source-id ids)))))
              (is (= 0 (intake!))))
            (testing "stock is unchanged"
              (is (= 10 (balance "blue" "home"))))
            (testing "and they can be brought back"
              (set-aside! ctx "back" (rows) false)
              (is (false? (:aside (aside-row ctx (source-id "B0")))))
              (is (= 20 (intake!)))
              (is (every? #(= ["pending" connector] ((juxt :status :saved-by) (pending (entry-of %)))) ids)))))))))


(deftest new-revision-stays-aside
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending source-id entry-of classify]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (set-aside! ctx "aside" [{:source-operation-id (source-id "P1") :revision 1}] true)
        (observe! (operation "P1" 2 :lines (lines "100" "2")))
        (is (= 0 (intake!)) "a new source revision keeps the operation aside")
        (is (nil? (pending (entry-of "P1"))))
        (is (= "set aside" (classify (source-id "P1"))))
        (is (= :stale (failure #(set-aside! ctx "late" [{:source-operation-id (source-id "P1") :revision 1}] false)))
            "a row reviewed at the old revision is stale")
        (set-aside! ctx "back" [{:source-operation-id (source-id "P1") :revision 2}] false)
        (is (= 1 (intake!)))
        (is (= [2] (mapv :quantity (:lines (pending (entry-of "P1"))))))))))


(deftest discarding-sets-aside
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx marketplace observe! intake! pending import source-id entry-of classify]} (setup! repo)
            id (entry-of "P1")]
        (testing "Given a pending imported entry"
          (observe! (operation "P1" 1))
          (intake!)
          (testing "When a member discards it"
            (reset! (:dirty marketplace) {})
            (execute! ctx ::entries/discard-inventory-draft "discard" {:workspace-id workspace :entry-id id :revision 1})
            (is (marked? marketplace))
            (testing "then its operation is set aside in the same commit"
              (is (= [true (str member) id] ((juxt :aside :set-by :removed-entry-id) (aside-row ctx (source-id "P1")))))
              (is (nil? (import (source-id "P1"))))
              (is (= "set aside" (classify (source-id "P1")))))
            (testing "and is not proposed again until brought back"
              (is (= 0 (intake!)))
              (is (nil? (pending id)))
              (set-aside! ctx "back" [{:source-operation-id (source-id "P1") :revision 1}] false)
              (is (= 1 (intake!)))
              (is (= ["pending" 3] ((juxt :status :revision) (pending id)))))))))))


(deftest already-accepted-cannot-be-set-aside
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (accept! ctx (entry-of "P1") 1)
        (is (= :already-accepted (failure #(set-aside! ctx "aside" [{:source-operation-id (source-id "P1") :revision 1}] true))))
        (is (= :not-found (failure #(set-aside! ctx "unknown" [{:source-operation-id (source-id "nope") :revision 1}] true))))))))


;; Board 45 slice 6 · Accept reviewed imported changes


(defn- accept-all!
  "Reviews and accepts `selection` in its order."
  {:malli/schema [:=> [:cat :map :string [:sequential [:tuple :string :int]]] :any]}
  [ctx acceptance-id selection]
  (let [input  {:selection (mapv (fn [[id revision]] {:entry-id id :revision revision}) selection) :order (mapv first selection)}
        review (runtime/read-model ctx ::acceptance/inventory-review input)]
    (is (:valid? review) (pr-str (:problems review)))
    (execute! ctx ::acceptance/accept-reviewed-inventory-changes acceptance-id
              (merge input {:workspace-id workspace :acceptance-id acceptance-id :fingerprint (:fingerprint review)}))))


(defn- rejection
  "The rejection's type, message and the problem it names."
  {:malli/schema [:=> [:cat fn?] [:maybe :any]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error [(:type (ex-data error)) (ex-message error) (:problem (ex-data error))])))


(deftest return-warehouse
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending balance]} (setup! repo)
            sale (str c ":fbs-posting:P1")
            return (str c ":return:R2")
            id (provenance/import-entry-id return)]
        (seed! ctx :tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "v" :name "V" :status "active" :revision 1})
        (observe! (operation "P1" 1))
        (seed! ctx ::provenance/source-operation-accepted
               {:source-operation-id sale  :connection-id c    :revision    1  :record-id "sale-p1" :accepted-by (str member)
                :edited              false :mappings-used "[]" :accepted-at at})
        (testing "Given an FBS return prefilled with warehouse W from its posting"
          (observe! (operation "R2" 1 :source-operation-id return :entity-id return :source-revision-id (str return "#1") :kind "return"
                               :return-schema "fbs" :status "ReturnedToOzon" :occurred-at "2026-11-12T09:00:00Z" :from-warehouse nil
                               :related-operation sale :lines (lines "100" "1")))
          (is (= 1 (intake!)))
          (is (= ["customer-return" ["home"]] ((juxt :kind #(mapv :warehouse-id (:lines %))) (pending id))))
          (testing "When the member changes it to V and accepts"
            (save! ctx id 1 {:kind  "customer-return" :occurred-at "2026-11-12T09:00:00Z"
                             :lines [{:line-id (str return "/1") :stock-item-id "red" :warehouse-id "v" :quantity 1}]})
            (accept-all! ctx "acc-r" [[id 2]])
            (testing "then the Customer return enters V"
              (is (= 1 (balance "red" "v")))
              (is (= 10 (balance "red" "home")))
              (is (= [1 id true] ((juxt :revision :record-id :edited) (acceptance-row ctx return))))
              (is (= 0 (intake!))))))))))


(deftest edited
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx marketplace observe! intake! balance source-id entry-of]} (setup! repo)
            id (entry-of "P1")]
        (observe! (operation "P1" 1))
        (intake!)
        (testing "Given a member changed the quantity"
          (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                           :lines       [{:line-id (str (source-id "P1") "/1") :stock-item-id "red" :warehouse-id "home" :quantity 2}]})
          (testing "When they accept"
            (reset! (:dirty marketplace) {})
            (accept-all! ctx "acc-e" [[id 2]])
            (is (marked? marketplace))
            (testing "then Source Operation Accepted says edited"
              (let [row (acceptance-row ctx (source-id "P1"))]
                (is (= [c 1 id (str member) true] ((juxt :connection-id :revision :record-id :accepted-by :edited) row)))
                (is (= [{:line-key "1" :external-product-id (str c ":100") :mapping-revision 1}]
                       (observations/read-json (:mappings-used row))))))
            (is (= 8 (balance "red" "home")))))))))


(deftest with-manual-entries
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending balance source-id entry-of]} (setup! repo)
            id (entry-of "P1")]
        (observe! (operation "P1" 1))
        (intake!)
        (execute! ctx ::entries/save-shared-inventory-entry "manual"
                  {:workspace-id workspace :entry-id "m1" :kind "sale" :occurred-at "2026-11-11T09:00:00Z"
                   :lines        [{:line-id "m1/1" :stock-item-id "red" :warehouse-id "home" :quantity 1}]})
        (accept-all! ctx "acc-m" [[id 1] ["m1" 1]])
        (is (= 6 (balance "red" "home")))
        (is (nil? (pending id)))
        (is (nil? (pending "m1")))
        (is (= [(str member) false] ((juxt :accepted-by :edited) (acceptance-row ctx (source-id "P1")))) "accepted unedited")
        (is (= 1 (count (records-of ctx ::provenance/source-acceptances :source-operation-id))) "the manual entry has no source")))))


(deftest line-before-cutover-names-the-line
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending source-id entry-of classify]} (setup! repo)
            id (entry-of "P1")
            rid (acceptance/record-revision-id "count-green" 1)
            day (java.time.Instant/parse "2026-11-15T00:00:00Z")
            problem {:entry-id id :line-id (str (source-id "P1") "/2") :code :before-cutover}]
        (testing "Given green, whose cutover at Home is on the 15th, and a posting on the 10th of red and green"
          (seed-all! ctx (concat
                           [[:tover.inventory.stock-items.model/stock-item-registered
                             {:stock-item-id "green" :product-id "mug" :variant-label "green" :unit "pcs" :condition "regular" :status "active" :revision 1}]
                            [::sync/external-product-observed (listing "300" "Mug green")]]
                           (butlast (opening "green" "home"))
                           [[::acceptance/inventory-changes-accepted {:acceptance-id "a-count" :actor member :recorded-at day :record-count 1}]
                            [::acceptance/inventory-record-accepted {:record-revision-id rid          :record-id   "count-green" :revision 1 :acceptance-id "a-count"
                                                                     :kind               "correction" :occurred-at day           :sequence 0 :cancelled     false}]
                            [::acceptance/inventory-record-line-accepted {:record-line-id (acceptance/record-line-id rid 0) :record-revision-id rid :position 0
                                                                          :stock-item-id  "green" :warehouse-id "home" :counted-quantity 10}]
                            [::provenance/cutover-baseline-established {:baseline-id    (provenance/baseline-id c "green" "home") :connection-id c :stock-item-id "green"
                                                                        :warehouse-id   "home" :instant "2026-11-15T00:00:00Z" :record-id "count-green"
                                                                        :established-at "2026-11-16T00:00:00Z"}]]))
          (execute! ctx ::products/confirm-product-mapping "confirm-green"
                    {:rows [{:external-product-id (str c ":300") :listing-revision 1 :stock-item-id "green" :item-revision 1}]})
          (observe! (operation "P1" 1 :lines (lines "100" "1" "300" "1")))
          (is (= 1 (intake!)))
          (is (= "remove old lines" (classify (source-id "P1"))))
          (let [selection {:selection [{:entry-id id :revision 1}] :order [id]}
                review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
            (is (some #{problem} (:problems review)))
            (testing "then acceptance is rejected Before cutover, naming the line"
              (is (= [:before-cutover "Before cutover" problem]
                     (rejection #(execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-b"
                                           (merge selection {:workspace-id workspace :acceptance-id "acc-b" :fingerprint (:fingerprint review)})))))
              (is (some? (pending id))))))))))


(deftest correction-in-the-same-selection-covers-the-shortage
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! balance entry-of]} (setup! repo)
            id (entry-of "P1")]
        (testing "Given a Sale of 12 red from Home, which holds 10"
          (observe! (operation "P1" 1 :lines (lines "100" "12")))
          (is (= 1 (intake!)))
          (let [selection {:selection [{:entry-id id :revision 1}] :order [id]}
                review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
            (is (= :negative-balance
                   (first (rejection #(execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-short"
                                                (merge selection {:workspace-id workspace :acceptance-id "acc-short" :fingerprint (:fingerprint review)})))))
                "alone, the shortage is never accepted as a negative balance"))
          (testing "a count before it in the same selection covers it"
            (execute! ctx ::entries/save-shared-inventory-entry "count"
                      {:workspace-id workspace :entry-id "count" :kind "correction" :occurred-at "2026-11-09T09:00:00Z"
                       :lines        [{:line-id "count/1" :stock-item-id "red" :warehouse-id "home" :quantity 15}]})
            (accept-all! ctx "acc-c" [["count" 1] [id 1]])
            (is (= 3 (balance "red" "home")))))))))


(deftest manual-acceptance-while-paused
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! balance source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (seed! ctx ::connections/marketplace-connection-paused
               {:connection-id c :sync-state "paused" :reason "manager" :paused-by (str member) :paused-at at})
        (accept-all! ctx "acc-p" [[(entry-of "P1") 1]])
        (is (= 7 (balance "red" "home")))
        (is (= [(str member) false] ((juxt :accepted-by :edited) (acceptance-row ctx (source-id "P1")))))))))


(deftest accepted-at-most-once
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending source-id entry-of]} (setup! repo)
            id (entry-of "P1")]
        (observe! (operation "P1" 1))
        (intake!)
        (seed! ctx ::provenance/source-operation-accepted
               {:source-operation-id (source-id "P1") :connection-id c    :revision    1  :record-id "elsewhere" :accepted-by (str connector)
                :edited              false            :mappings-used "[]" :accepted-at at})
        (let [selection {:selection [{:entry-id id :revision 1}] :order [id]}
              review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
          (is (= [:already-accepted "This operation is already accepted." {:entry-id id :line-id nil :code :already-accepted}]
                 (rejection #(execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-twice"
                                       (merge selection {:workspace-id workspace :acceptance-id "acc-twice" :fingerprint (:fingerprint review)})))))
          (is (some? (pending id))))))))


;; Task 4 review fixes


(deftest setting-aside-clears-stock-item-changes
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending source-id entry-of]} (setup! repo)
            id (entry-of "P1")]
        (observe! (operation "P1" 1))
        (intake!)
        (testing "Given a member turned the import entry into a count with a stock item change"
          (save! ctx id 1 {:kind    "correction" :occurred-at "2026-11-10T09:00:00Z"
                           :lines   [{:line-id (str (source-id "P1") "/1") :stock-item-id "red" :warehouse-id "home" :quantity 10}]
                           :changes [{:stock-item-id "red" :article "R-1"}]})
          (is (= ["red"] (mapv :stock-item-id (:changes (pending id)))))
          (testing "when it is set aside and brought back"
            (set-aside! ctx "aside" [{:source-operation-id (source-id "P1") :revision 1}] true)
            (is (empty? (records-of ctx ::entries/entry-changes :entry-change-id)) "its changes are removed with it")
            (set-aside! ctx "back" [{:source-operation-id (source-id "P1") :revision 1}] false)
            (is (= 1 (intake!)))
            (testing "then the proposed Sale carries no old change"
              (is (= ["sale" connector nil] ((juxt :kind :saved-by :changes) (pending id)))))))))))


(deftest import-entries-cannot-amend-or-cancel
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! pending source-id entry-of]} (setup! repo)
            id (entry-of "P1")
            line {:line-id (str (source-id "P1") "/1") :stock-item-id "red" :warehouse-id "home" :quantity 3}
            message "An imported operation cannot amend or cancel an accepted record."]
        (observe! (operation "P1" 1))
        (intake!)
        (execute! ctx ::entries/save-shared-inventory-entry "manual"
                  {:workspace-id workspace :entry-id "m1" :kind "sale" :occurred-at "2026-11-11T09:00:00Z"
                   :lines        [{:line-id "m1/1" :stock-item-id "red" :warehouse-id "home" :quantity 1}]})
        (accept-all! ctx "acc-m" [["m1" 1]])
        (is (= [:validation message]
               (take 2 (rejection #(save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z" :lines [line] :amends "m1#1"})))))
        (is (= [:validation message]
               (take 2 (rejection #(save! ctx id 1 {:amends "m1#1" :cancel true :reason "wrong"})))))
        (is (= [1 connector] ((juxt :revision :saved-by) (pending id))) "the entry is unchanged")))))


;; Board 45 slices 3 and 4 · Check automatic-acceptance eligibility, and Accept the imported operation automatically


(defn- auto-input
  {:malli/schema [:=> [:cat :string [:* :any]] :map]}
  [source-operation-id & {:keys [revision entry-revision] :or {revision 1 entry-revision 1}}]
  {:source-operation-id source-operation-id :revision revision
   :entry-id            (provenance/import-entry-id source-operation-id) :entry-revision entry-revision})


(defn- accepted-sources
  {:malli/schema [:=> [:cat :map] [:set :string]]}
  [ctx]
  (set (map :source-operation-id (records-of ctx ::provenance/source-acceptances :source-operation-id))))


(defn- eligibility
  {:malli/schema [:=> [:cat :map :string] [:vector :map]]}
  [ctx connection-id]
  (runtime/read-model ctx ::operations/imported-proposals-and-acceptance-eligibility {:connection-id connection-id}))


(deftest short-stock
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! pending balance classify source-id entry-of]} (setup! repo)]
        (testing "Given balance 2"
          (observe! (operation "P0" 1 :occurred-at "2026-11-05T09:00:00Z" :lines (lines "100" "8")))
          (intake!)
          (automatic!)
          (is (= 2 (balance "red" "home"))))
        (testing "and an imported Sale of 3"
          (observe! (operation "P1" 1))
          (intake!)
          (is (= [{:entry-id (entry-of "P1") :source-operation-id (source-id "P1") :occurred-at (java.time.Instant/parse "2026-11-10T09:00:00Z")
                   :eligible false           :failing-conditions  ["validation"]}]
                 (eligibility ctx c)))
          (automatic!)
          (testing "then it is not accepted and its next step is `fix shortage`"
            (is (= 2 (balance "red" "home")))
            (is (some? (pending (entry-of "P1"))))
            (is (not (contains? (accepted-sources ctx) (source-id "P1"))))
            (is (= "fix shortage" (classify (source-id "P1"))))))))))


(deftest supply
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! pending balance classify]} (setup! repo)
            id (str c ":supply:S1")]
        (testing "Given a supply with every fact entered"
          (observe! (operation "S1" 1 :source-operation-id id :entity-id id :source-revision-id (str id "#1") :kind "supply"
                               :status "completed" :from-warehouse seller :to-warehouse hub))
          (is (= 1 (intake!)))
          (is (= "transfer" (:kind (pending (provenance/import-entry-id id)))))
          (testing "then it still waits for a member"
            (is (= 0 (automatic!)))
            (is (= [false ["kind"]] ((juxt :eligible :failing-conditions) (first (eligibility ctx c)))))
            (is (= "accept" (classify id)))
            (is (= [10 10] [(balance "red" "home") (balance "red" "ozon-7")]))
            (is (empty? (accepted-sources ctx)))))))))


(deftest retry
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! accept-auto! balance source-id]} (setup! repo)
            input (auto-input (source-id "P1"))
            rows #(count (records-of ctx ::provenance/source-acceptances :source-operation-id))]
        (observe! (operation "P1" 1))
        (intake!)
        (testing "Given a lost response"
          (let [first-result (accept-auto! "auto-1" input)]
            (testing "when the same attempt is sent again, then the first result is returned"
              (is (= first-result (accept-auto! "auto-1" input)))
              (is (= 7 (balance "red" "home")) "no further effects")
              (is (= 1 (rows))))))
        (testing "another attempt at the same source revision is refused: the acceptance id is fixed by it"
          (is (some? (failure #(accept-auto! "auto-2" input))))
          (is (= 7 (balance "red" "home")))
          (is (= 1 (rows))))))))


(deftest race-with-a-member
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! accept-auto! balance source-id entry-of]} (setup! repo)
            id (entry-of "P1")
            input (auto-input (source-id "P1"))
            selection {:selection [{:entry-id id :revision 1}] :order [id]}]
        (observe! (operation "P1" 1))
        (intake!)
        (let [review (runtime/read-model ctx ::acceptance/inventory-review selection)
              ready  (java.util.concurrent.CountDownLatch. 2)
              plan   acceptance/plan]
          (testing "Given a member accepts the same entry at the same moment"
            ;; Both decisions read their facts before either commits.
            (with-redefs [acceptance/plan (fn [& args]
                                            (.countDown ready)
                                            (.await ready 10 java.util.concurrent.TimeUnit/SECONDS)
                                            (apply plan args))]
              (let [jobs    [(future (try (accept-auto! "auto" input) (catch Exception error error)))
                             (future (try (execute! ctx ::acceptance/accept-reviewed-inventory-changes "by-member"
                                                    (merge selection {:workspace-id workspace :acceptance-id "acc-m" :fingerprint (:fingerprint review)}))
                                          (catch Exception error error)))]
                    results (mapv #(deref % 20000 ::timeout) jobs)]
                (is (not-any? #{::timeout} results))
                ;; The other is refused; a member's attempt gets a retryable conflict.
                (is (= 1 (count (filter map? results))) (pr-str (map #(if (map? %) % (ex-data %)) results)))))
            (testing "then exactly one acceptance wins"
              (is (= 1 (count (records-of ctx ::provenance/source-acceptances :source-operation-id))))
              (is (= 1 (count (filter #(= id (:record-id %)) (:records (runtime/coeffect ::acceptance/history ctx {}))))))
              (is (= 7 (balance "red" "home"))))))))))


(deftest paused-just-before
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! accept-auto! balance source-id]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (testing "Given the connection was paused"
          (seed! ctx ::connections/marketplace-connection-paused
                 {:connection-id c :sync-state "paused" :reason "manager" :paused-by (str member) :paused-at at})
          (testing "then nothing is accepted"
            (is (= :not-eligible (failure #(accept-auto! "auto" (auto-input (source-id "P1"))))))
            (is (= [:not-eligible ["connection state"]]
                   (try (accept-auto! "auto-b" (auto-input (source-id "P1"))) nil
                        (catch clojure.lang.ExceptionInfo error ((juxt :type :conditions) (ex-data error))))))
            (is (= 0 (automatic!)))
            (is (= 10 (balance "red" "home")))
            (is (empty? (accepted-sources ctx)))))))))


(deftest one-failure-does-not-block-the-next
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! accept-auto! balance classify source-id]} (setup! repo)]
        (testing "Given a short sale first and an independent sale after it"
          (observe! (operation "P1" 1 :lines (lines "100" "11")))
          (observe! (operation "P2" 1 :occurred-at "2026-11-11T09:00:00Z" :lines (lines "200" "2")))
          (is (= 2 (intake!)))
          (is (= :not-eligible (failure #(accept-auto! "auto-p1" (auto-input (source-id "P1"))))))
          (automatic!)
          (testing "then the later one is accepted and the short one waits"
            (is (= #{(source-id "P2")} (accepted-sources ctx)))
            (is (= [10 8] [(balance "red" "home") (balance "blue" "home")]))
            (is (= "fix shortage" (classify (source-id "P1"))))))))))


(def c2 "00000000-0000-0000-0000-00000000c046")


(defn- connect-second!
  "A second Ozon account in the workspace, its listing 100 confirmed as red and its seller warehouse as Home, with its
  own baseline on red at Home."
  {:malli/schema [:=> [:cat :map] :nil]}
  [ctx]
  (let [seller-2 (str c2 ":fbs:3")]
    (seed-all! ctx [[::connections/marketplace-account-connected
                     {:connection-id     c2       :workspace-id     (str workspace) :provider         "ozon"     :provider-account-id "456"    :account-label "Second"
                      :secret-ref        "secret" :account-currency "RUB"           :readable-domains "products" :sync-state          "active"
                      :credentials-state "valid"  :connected-by     (str member)    :connected-at     at}]
                    [::sync/external-product-observed (listing-of c2 "100" "Mug red")]
                    [::sync/external-warehouse-observed
                     {:external-warehouse-id seller-2 :entity-id seller-2 :connection-id c2 :source-id "3" :revision 1
                      :payload-hash          (apply str (repeat 64 "b"))  :adapter-version "test"     :observed-at    at    :last-observed-at at
                      :source-revision-id    (str seller-2 "#1")          :name            "Seller 3" :warehouse-type "fbs" :active           true
                      :facts                 (observations/write-json {})}]
                    [::warehouses/external-warehouse-mapped (assoc (warehouse-mapped seller-2 "fbs" "home") :connection-id c2)]
                    [::provenance/cutover-baseline-established
                     {:baseline-id    (provenance/baseline-id c2 "red" "home") :connection-id c2                 :stock-item-id "red" :warehouse-id "home"
                      :instant        "2026-11-01T00:00:00Z"                   :record-id     "opening-red-home"
                      :established-at "2026-11-02T00:00:00Z"}]])
    (execute! ctx ::products/confirm-product-mapping "confirm-2"
              {:rows [{:external-product-id (str c2 ":100") :listing-revision 1 :stock-item-id "red" :item-revision 1}]})
    nil))


(deftest two-accounts-short-of-one-item
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! balance source-id]} (setup! repo)
            other (str c2 ":fbs-posting:Q1")]
        (connect-second! ctx)
        (testing "Given balance 3 of red at Home"
          (observe! (operation "P0" 1 :occurred-at "2026-11-05T09:00:00Z" :lines (lines "100" "7")))
          (intake!)
          (automatic!)
          (is (= 3 (balance "red" "home"))))
        (testing "and a sale of 2 by each account, both proposed and eligible"
          (observe! (operation "P1" 1 :lines (lines "100" "2")))
          (observe! (operation-of c2 "Q1" 1 :occurred-at "2026-11-11T09:00:00Z" :lines (lines "100" "2")))
          (is (= 2 (intake!)))
          (is (= [true true] (map :eligible (concat (eligibility ctx c) (eligibility ctx c2))))))
        (automatic!)
        (testing "then one is accepted and the other's next step is `fix shortage`"
          (let [accepted (disj (accepted-sources ctx) (source-id "P0"))
                step     (fn [connection-id id]
                           (let [facts (operations/load-facts ctx connection-id)]
                             (:next-step (steps/classify facts (get-in facts [:operations id])))))]
            (is (= 1 (count accepted)))
            (is (= 1 (balance "red" "home")))
            (is (= "fix shortage" (if (contains? accepted other) (step c (source-id "P1")) (step c2 other))))))))))


(deftest never-registers
  (testing "the command declares no catalogue registration, item change or mapping confirmation"
    (let [emits (get-in (bel/operations) [:commands ::operations/accept-imported-operation-automatically :emits])]
      (is (seq emits))
      (is (not-any? operations/registering emits))
      (is (not-any? #(= "tover.marketplace.products.model" (namespace %)) emits))))
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! accept-auto! balance source-id]} (setup! repo)
            plan acceptance/plan]
        (observe! (operation "P1" 1))
        (intake!)
        (testing "an acceptance that would register a record is refused and commits nothing"
          (with-redefs [acceptance/plan (fn [& args]
                                          (update (apply plan args) :events conj
                                                  {:type :tover.catalogues.warehouses.model/warehouse-registered
                                                   :data {:warehouse-id "new" :name "New" :status "active" :revision 1}}))]
            (is (= :not-eligible (failure #(accept-auto! "auto" (auto-input (source-id "P1")))))))
          (is (= 10 (balance "red" "home")))
          (is (empty? (accepted-sources ctx))))))))


(deftest actor-is-the-connector
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! pending balance source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (is (= [true []] ((juxt :eligible :failing-conditions) (first (eligibility ctx c)))))
        (is (= 1 (automatic!)))
        (testing "the Sale is accepted without a member, as the connector"
          (is (= 7 (balance "red" "home")))
          (is (nil? (pending (entry-of "P1"))))
          (let [row        (some #(when (= (source-id "P1") (:source-operation-id %)) %)
                                 (records-of ctx ::provenance/source-acceptances :source-operation-id))
                acceptance (some #(when (= (str "auto/" (source-id "P1") "#1") (:acceptance-id %)) %)
                                 (records-of ctx ::acceptance/acceptances :acceptance-id))]
            (is (= [(str connector) false (entry-of "P1")] ((juxt :accepted-by :edited :record-id) row)))
            (is (= connector (:actor acceptance)))
            (is (= "Ozon · Shop" (:actor-label (first (actors/enrich ctx [{:actor (str (:actor acceptance))}])))))))
        (testing "and nothing is left to accept"
          (is (= [] (eligibility ctx c)))
          (is (= 0 (automatic!))))))))


(deftest remapped-product-makes-the-entry-stale
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! accept-auto! pending balance source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (intake!)
        (testing "Given a member moves listing 100 from red to blue before intake replaces the entry"
          (execute! ctx ::products/change-product-mapping "remap"
                    {:external-product-id (str c ":100") :mapping-revision 1 :stock-item-id "blue" :item-revision 1})
          (is (= ["red"] (map :stock-item-id (:lines (pending (entry-of "P1"))))))
          (testing "then the entry on red is stale, and automatic acceptance accepts nothing"
            (is (= :stale (failure #(accept-auto! "auto" (auto-input (source-id "P1"))))))
            (automatic!)
            (is (empty? (accepted-sources ctx)))
            (is (= [10 10] [(balance "red" "home") (balance "blue" "home")])))
          (testing "and once intake proposes it again on blue, blue is sold"
            (is (= 1 (intake!)))
            (is (= 1 (automatic!)))
            (is (= [10 7] [(balance "red" "home") (balance "blue" "home")]))))))))


(deftest remapped-warehouse-makes-the-entry-stale
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! automatic! accept-auto! pending balance source-id entry-of]} (setup! repo)]
        (observe! (operation "P1" 1))
        (seed-all! ctx (into [[:tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "back" :name "Back room" :status "active" :revision 1}]]
                             (opening "red" "back")))
        (intake!)
        (testing "Given a member moves the seller warehouse from Home to Back room before intake replaces the entry"
          (execute! ctx ::warehouses/change-warehouse-mapping "remap" {:external-warehouse-id seller :mapping-revision 1 :warehouse-id "back"})
          (is (= ["home"] (map :warehouse-id (:lines (pending (entry-of "P1"))))))
          (testing "then the entry at Home is stale, and automatic acceptance accepts nothing"
            (is (= :stale (failure #(accept-auto! "auto" (auto-input (source-id "P1"))))))
            (automatic!)
            (is (empty? (accepted-sources ctx)))
            (is (= [10 10] [(balance "red" "home") (balance "red" "back")])))
          (testing "and once intake proposes it again in Back room, Back room is sold from"
            (is (= 1 (intake!)))
            (is (= 1 (automatic!)))
            (is (= [10 7] [(balance "red" "home") (balance "red" "back")]))))))))


(defn- sync-to-an-empty-queue!
  "12 listings matched to items with a cutover of 100 at Home and `n` postings of 1, round-robin over them; intake
  and automatic acceptance run until both send nothing. Returns `[balance-of-the-first-item rounds]`."
  {:malli/schema [:=> [:cat :any :int] [:tuple :int :int]]}
  [repo n]
  (let [{:keys [ctx intake! automatic! balance]} (setup! repo)
        skus (mapv #(str (+ 900 %)) (range 12))
        item #(str "item-" %)
        quiet (fn [] (loop [rounds 1] (if (and (zero? (intake!)) (zero? (automatic!))) rounds (recur (inc rounds)))))]
    (seed-all! ctx (concat (for [sku skus]
                             [:tover.inventory.stock-items.model/stock-item-registered
                              {:stock-item-id (item sku) :product-id "mug" :variant-label sku :unit "pcs" :condition "regular"
                               :status        "active"   :revision   1}])
                           (for [sku skus] [::sync/external-product-observed (listing sku (str "Listing " sku))])
                           (mapcat #(update-in (opening (item %) "home") [2 1] assoc :quantity-change 100) skus)))
    (execute! ctx ::products/confirm-product-mapping "match"
              {:rows (mapv #(hash-map :external-product-id (str c ":" %) :listing-revision 1 :stock-item-id (item %) :item-revision 1) skus)})
    (seed-all! ctx (for [i (range n)]
                     [::sync/source-operation-observed
                      (operation (str "U" i) 1 :lines (lines (nth skus (mod i 12)) "1")
                                 :occurred-at (str (.plusSeconds (java.time.Instant/parse "2026-11-10T00:00:00Z") (* 60 i))))]))
    (let [rounds (quiet)]
      (is (= n (count (records-of ctx ::provenance/source-acceptances :source-operation-id))) "every sale is accepted once")
      (is (empty? (filter (comp provenance/import-entry-id? :entry-id) (runtime/coeffect ::entries/pending ctx {}))))
      [(balance (item (first skus)) "home") rounds])))


(deftest first-sync-to-an-empty-queue
  (support/with-store
    (fn [repo _]
      (testing "36 postings over 12 matched listings with a cutover: all accepted as the connector, in three rounds"
        (is (= [(- 100 3) 3] (sync-to-an-empty-queue! repo 36)))))))


(deftest ^:perf first-sync-of-340-operations
  ;; Plan 4 Task 5 Step 5's timing run; skipped by the default suite (`tests.edn`), run with `--focus-meta :perf`.
  (support/with-store
    (fn [repo _]
      (is (= [(- 100 29) 3] (sync-to-an-empty-queue! repo 340)) "29 of the 340 sell listing 900"))))

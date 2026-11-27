(ns tover.marketplace.guard-test
  "Board chapter 42 · the Mapping Guard (slices 9, a, b): the rules over given facts, Suspend Product Mapping
  decisions, and the processor through a store from confirmation to suspension, reconfirmation and remapping."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.products.model]
    [tover.catalogues.stores.model]
    [tover.catalogues.warehouses.model]
    [tover.inventory.stock-items.model]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.products.candidates :as candidates]
    [tover.marketplace.products.guard :as guard]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.support :as support]
    [tover.workspaces.currency.model])
  (:import
    [java.time Instant]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                        'tover.inventory.stock-items.model 'tover.marketplace.connections.model
                        'tover.marketplace.sync.model 'tover.marketplace.products.model 'tover.marketplace.products.guard
                        'tover.workspaces.currency.model])
  (f))


(defn unit-authority
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                         (read (assoc context :principal (if-let [service (:bel/service context)]
                                                                           {:service service}
                                                                           {:user-id     (:user-id context)
                                                                            :memberships (:memberships context)}))))]
    (f)))


(use-fixtures :each bel-test/isolated-registry register unit-authority)


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-0000000000b1")
(def c "00000000-0000-0000-0000-00000000c001")
(def at "2026-11-20T10:00:00Z")
(def id (str c ":1"))


;; Given facts


(def definitions
  [{:connection-id c :attribute-id "10096" :name "Цвет товара" :separates-variants false}
   {:connection-id c :attribute-id "4191" :name "Аннотация" :separates-variants false}])


(def listing
  {:external-product-id id :connection-id c     :source-id "1" :revision 1 :name "Mug" :archived false
   :identifiers         [{:type "barcode" :value "4600000000017" :active true} {:type "article" :value "MUG-1" :active true}]
   :attributes          [{:attribute-id "10096" :values [{:value "Red"}]} {:attribute-id "4191" :values [{:value "A mug"}]}]
   :unit-and-pack       "1" :cost         "100"})


(def mug-1
  {:stock-item-id "mug-1" :product-id "mug" :variant-label "Red" :unit "pcs" :condition "regular" :status "active" :revision 1})


(def catalogue
  {:products    [{:product-id "mug" :name "Mug" :status "active" :revision 1}
                 {:product-id "plate" :name "Plate" :status "active" :revision 1}]
   :stock-items [mug-1
                 {:stock-item-id "mug-2" :product-id "mug" :variant-label "Blue" :unit "pcs" :condition "regular" :status "active" :revision 1}
                 {:stock-item-id "plate-1" :product-id "plate" :unit "pcs" :condition "regular" :status "active" :revision 1}]
   :barcodes    [{:stock-item-id "mug-1" :barcode "4600000000017"} {:stock-item-id "plate-1" :barcode "plate-code"}]
   :definitions definitions})


(def mapping
  "Confirmed at revision 1 against `listing` and `mug-1`."
  {:external-product-id id :connection-id c           :stock-item-id "mug-1"      :listing-revision 1  :item-revision 1
   :identity-basis      (observations/write-json (candidates/identity-basis listing definitions mug-1))
   :mapping-revision    1 :state          "confirmed" :changed-by    (str member) :changed-at       at})


(defn reasons
  "What the guard finds once `f` changed the listing and `g` the catalogue."
  {:malli/schema [:=> [:cat [:fn fn?] [:fn fn?]] [:vector :map]]}
  [f g]
  (guard/reasons {:listing (f listing) :catalogue (g catalogue)} mapping))


(defn items
  "The catalogue with `f` applied to the stock item `item-id`."
  {:malli/schema [:=> [:cat :map :string [:fn fn?]] :map]}
  [catalogue item-id f]
  (update catalogue :stock-items (partial mapv #(cond-> % (= item-id (:stock-item-id %)) f))))


;; Slice 9 · Notice an identity-relevant change


(deftest renamed
  (is (= [] (reasons #(assoc % :name "Big mug" :revision 2) identity))))


(deftest pack-changed
  (is (= [{:rule "listing-identity-changed" :before "1" :after "2"}]
         (reasons #(assoc % :unit-and-pack "2" :revision 2) identity))))


(deftest article-price-cost-change-pass
  (is (= [] (reasons #(-> %
                          (assoc :cost "250" :revision 2)
                          (assoc-in [:identifiers 1 :value] "MUG-NEW")
                          (assoc-in [:attributes 1 :values] [{:value "A bigger mug"}]))
                     identity))
      "article, cost and a descriptive attribute"))


(deftest variant-attribute-changed
  (is (= [{:rule "listing-identity-changed" :before "Цвет товара: Red" :after "Цвет товара: Blue"}]
         (reasons #(assoc-in % [:attributes 0 :values] [{:value "Blue"}]) identity))))


(deftest attribute-without-definition-separates
  (is (= [{:rule "listing-identity-changed" :before nil :after "777: XL"}]
         (reasons #(update % :attributes conj {:attribute-id "777" :values [{:value "XL"}]}) identity))
      "an unknown definition never lets a change pass"))


(deftest barcode-moved-to-another-product
  (is (= [{:rule "barcode-moved" :before "4600000000017 · Mug" :after "4600000000017 · Plate"}]
         (reasons identity #(assoc % :barcodes [{:stock-item-id "plate-1" :barcode "4600000000017"}
                                                {:stock-item-id "plate-1" :barcode "plate-code"}])))))


(deftest item-moved-with-its-barcodes-passes
  (is (= [] (reasons identity #(items % "mug-1" (fn [i] (assoc i :product-id "plate" :revision 2)))))))


(deftest unit-or-label-changed
  (is (= [{:rule "item-identity-changed" :before "pcs" :after "kg"}
          {:rule "item-identity-changed" :before "Red" :after "Crimson"}]
         (reasons identity #(items % "mug-1" (fn [i] (assoc i :unit "kg" :variant-label "Crimson" :revision 2)))))))


(deftest archived
  (is (= [{:rule "item-archived" :before "active" :after "archived"}]
         (reasons identity #(items % "mug-1" (fn [i] (assoc i :status "archived" :revision 2))))))
  (is (= [{:rule "item-archived" :before "active" :after "missing"}]
         (reasons identity #(assoc % :stock-items []))))
  (is (= [{:rule "item-archived" :before "active" :after "archived"}]
         (reasons identity #(update % :products (partial mapv (fn [p] (cond-> p (= "mug" (:product-id p)) (assoc :status "archived")))))))
      "an archived product archives its items"))


;; Slice a · Suspend the mapping


(def connector (sync/connector-actor c))
(def pack {:rule "listing-identity-changed" :before "1" :after "2"})
(def moved {:rule "barcode-moved" :before "4600000000017 · Mug" :after "4600000000017 · Plate"})


(defn suspend
  {:malli/schema [:=> [:cat :map [:* :any]] :map]}
  [input & {:as facts}]
  (runtime/decide ::guard/suspend-product-mapping
                  (merge {:scope support/scope :actor connector :now (Instant/parse at) :mappings {id mapping}} facts)
                  (merge {:external-product-id id :mapping-revision 1 :reasons [pack]} input)))


(defn suspended-data
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [plan]
  (some #(when (= ::products/product-mapping-suspended (:type %)) (:data %)) (:events plan)))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(deftest suspend-keeps-the-basis-and-names-the-reasons
  (let [data (suspended-data (suspend {}))]
    (is (= {:mapping-revision 2       :mapping-revision-id (str id "#2")        :state "suspended" :change "suspended"
            :stock-item-id    "mug-1" :changed-by          (str "connector:" c)}
           (select-keys data [:mapping-revision :mapping-revision-id :state :change :stock-item-id :changed-by])))
    (is (= (:identity-basis mapping) (:identity-basis data)))
    (is (= [pack] (observations/read-json (:reasons data))))))


(deftest suspend-rejections
  (is (= :stale (rejection #(suspend {:mapping-revision 2}))) "the mapping changed since it was compared")
  (is (= :stale (rejection #(suspend {} :mappings {}))))
  (is (= :stale (rejection #(suspend {} :mappings {id (assoc mapping :state "removed")}))))
  (is (= :not-permitted (rejection #(suspend {} :actor (sync/connector-actor "00000000-0000-0000-0000-00000000c002"))))
      "the connector acts for its own connection only"))


(deftest already-suspended-gains-the-new-reason
  (let [suspended (assoc mapping :state "suspended" :mapping-revision 2 :reasons (observations/write-json [moved]))]
    (is (= [moved pack]
           (observations/read-json (:reasons (suspended-data (suspend {:mapping-revision 2 :reasons [moved pack]}
                                                                      :mappings {id suspended}))))))
    (is (empty? (:events (suspend {:mapping-revision 2 :reasons [moved]} :mappings {id suspended})))
        "a reason it already names adds nothing")))


;; Through a store: processor, reconfirmation, remapping and the suspended read


(defn seed!
  {:malli/schema [:=> [:cat :map :keyword :map] :nil]}
  [ctx type data]
  (store/with-store (assoc ctx :bel/definitions (bel/operations)) :catalogue :serializable
    #(runtime/apply-event! % {:type type :data data})))


(defn observed
  {:malli/schema [:=> [:cat :int :string] :map]}
  [revision pack]
  {:external-product-id id :entity-id id :connection-id c :source-id "1" :revision revision
   :payload-hash        (apply str (repeat 64 (str revision))) :adapter-version "test" :observed-at at :last-observed-at at
   :source-revision-id  (str id "#" revision) :name "Mug" :archived false :unit-and-pack pack
   :identifiers         (observations/write-json [{:type "barcode" :value "4600000000017" :active true}])
   :attributes          (observations/write-json [])
   :facts               (observations/write-json {:base {:name "Mug"}})})


(defn setup!
  "A connected workspace with Mug (mug-1, mug-2) and listing 1 confirmed to mug-1. Returns the tools."
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (let [marketplace {:dirty (atom {}) :swept (atom nil)}
        system      (support/bel-system {:repo repo :marketplace marketplace})
        ctx         (runtime/prepare-context {:bel/system  system :repo repo :workspace-id workspace :user-id member
                                              :memberships [{:workspace-id workspace :role :member}]})]
    (seed! ctx ::connections/marketplace-account-connected
           {:connection-id     c        :workspace-id     (str workspace) :provider         "ozon"     :provider-account-id "123"    :account-label "Shop"
            :secret-ref        "secret" :account-currency "RUB"           :readable-domains "products" :sync-state          "active"
            :credentials-state "valid"  :connected-by     (str member)    :connected-at     at})
    (seed! ctx :tover.catalogues.products.model/product-registered {:product-id "mug" :name "Mug" :status "active" :revision 1})
    (doseq [item ["mug-1" "mug-2"]]
      (seed! ctx :tover.inventory.stock-items.model/stock-item-registered
             {:stock-item-id item :product-id "mug" :unit "pcs" :condition "regular" :status "active" :revision 1}))
    (seed! ctx :tover.inventory.stock-items.model/stock-item-barcode-assigned
           {:barcode-id "b1" :stock-item-id "mug-1" :barcode "4600000000017"})
    (seed! ctx ::sync/external-product-observed (observed 1 "1"))
    (runtime/execute! ctx ::products/confirm-product-mapping
                      {:command-id "confirm-1" :input {:rows [{:external-product-id id :listing-revision 1 :stock-item-id "mug-1" :item-revision 1}]}})
    {:ctx         ctx
     :marketplace marketplace
     :guard!      (fn []
                    ;; As `runtime/run-processors!` runs this processor, with a full sweep each time.
                    (reset! (:swept marketplace) nil)
                    (let [found (runtime/coeffect ::guard/mappings-to-suspend {:bel/system system} {})]
                      (doseq [request (map guard/mapping-guard found)]
                        (runtime/execute! (merge (:scope request) {:bel/system system :bel/service ::guard/mapping-guard :user-id (:actor request)})
                                          (:id request)
                                          {:command-id (runtime/processor-command-id ::guard/mapping-guard request) :input (:input request)}))
                      (count found)))
     :details     #(runtime/read-model ctx ::products/mapping-details-and-source-evidence {:external-product-id id})
     :suspended   #(:rows (runtime/read-model ctx ::guard/suspended-mappings-awaiting-review {:connection-id c}))}))


(deftest running-twice-emits-once
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx guard! details suspended]} (setup! repo)]
        (is (= 0 (guard!)) "a fresh confirmation matches no rule")
        (seed! ctx ::sync/external-product-observed (observed 2 "2"))
        (is (= 1 (guard!)))
        (is (= 0 (guard!)) "the second run finds nothing new")
        (is (= [["mapped" 1] ["suspended" 2]] (mapv (juxt :change :mapping-revision) (:history (details)))))
        (is (= "suspended" (:state (details))))
        (testing "Slice b · the suspended read names the change and the item"
          (is (= [{:external-product-id id :name                   "Mug" :listing-revision 2 :mapping-revision 2
                   :stock-item          {:stock-item-id "mug-1" :product-name "Mug"}
                   :reasons             [pack] :waiting-operations nil}]
                 (mapv #(dissoc % :suspended-at) (suspended)))
              "this store has no imported operations' reads: the waiting count is unknown, never 0"))))))


(deftest reconfirm-makes-current-values-the-basis
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx guard! details suspended]} (setup! repo)]
        (seed! ctx ::sync/external-product-observed (observed 2 "2"))
        (guard!)
        (runtime/execute! ctx ::products/reconfirm-product-mapping
                          {:command-id "reconfirm-1"
                           :input      {:external-product-id id :mapping-revision 2 :listing-revision 2 :item-revision 1}})
        (is (= 0 (guard!)) "pack 2 is the basis now")
        (is (= {:state "confirmed" :pack "2"}
               (let [m (:mapping (details))] {:state (:state m) :pack (get-in m [:identity-basis :pack])})))
        (is (= [] (suspended)))))))


(deftest remap-clears-reasons
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx guard! details suspended]} (setup! repo)]
        (seed! ctx ::sync/external-product-observed (observed 2 "2"))
        (guard!)
        (runtime/execute! ctx ::products/change-product-mapping
                          {:command-id "change-1" :input {:external-product-id id :mapping-revision 2 :stock-item-id "mug-2" :item-revision 1}})
        (is (= {:state "confirmed" :stock-item-id "mug-2"} (select-keys (:mapping (details)) [:state :stock-item-id :reasons])))
        (is (= 0 (guard!)))
        (is (= [] (suspended)))))))


;; Fails closed on long values and unreadable bases


(deftest long-values-and-many-attributes-still-suspend
  (let [long-value (apply str (repeat 2500 "x"))
        attributes (into [{:attribute-id "10096" :values [{:value long-value}]}]
                         (for [n (range 250)] {:attribute-id (str "9" n) :values [{:value "v"}]}))
        found      (guard/fit (reasons #(assoc % :attributes attributes :revision 2) identity))
        plan       (suspend {:reasons found})]
    (is (= guard/max-reasons (count found)))
    (is (= "+152 more" (:after (peek found))) "251 attribute changes: 99 kept, the rest merged")
    (is (every? #(<= (count (or (:after %) "")) guard/max-text) found))
    (is (= \… (last (:after (first found)))) "a long value is cut and marked")
    (is (= "suspended" (:state (suspended-data plan))))
    (is (= found (observations/read-json (:reasons (suspended-data plan)))))
    (is (nil? (guard/suspended-reasons (assoc mapping :state "suspended" :reasons (:reasons (suspended-data plan))) found))
        "the fitted reasons are not new the next time")))


(deftest unreadable-basis-suspends-and-never-stops-the-others
  (let [other   (assoc listing :external-product-id (str c ":2") :unit-and-pack "2")
        corrupt (assoc mapping :identity-basis "{not json" :reasons "[also not")
        fine    (assoc mapping :external-product-id (str c ":2"))
        found   (guard/pending {:listings [listing other] :mappings {id corrupt (str c ":2") fine} :catalogue catalogue}
                               (str workspace) c)]
    (is (= [id (str c ":2")] (mapv :external-product-id found)))
    (is (some #{{:rule "listing-identity-changed" :before nil :after "1"}} (:reasons (first found)))
        "an unreadable basis is empty, so the listing differs from it")
    (is (= "suspended" (:state (suspended-data (suspend {:reasons (:reasons (first found))} :mappings {id corrupt}))))
        "unreadable stored reasons count as none")))

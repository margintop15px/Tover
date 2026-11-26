(ns tover.marketplace.products-test
  "Board chapter 42 · the matching reads (slices 1, 3, 5, 7): read-model handlers over given facts, then the
  models, coeffects and connection count through a store. Candidate rules are in `candidates-test`."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                        'tover.inventory.stock-items.model 'tover.marketplace.connections.model
                        'tover.marketplace.sync.model 'tover.marketplace.products.model 'tover.workspaces.currency.model])
  (f))


(defn unit-authority
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                         (read (assoc context :principal {:user-id (:user-id context) :memberships (:memberships context)})))]
    (f)))


(use-fixtures :each bel-test/isolated-registry register unit-authority)


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-0000000000b1")
(def c "00000000-0000-0000-0000-00000000c001")
(def other-c "00000000-0000-0000-0000-00000000c002")
(def at "2026-11-20T10:00:00Z")


(defn listing
  "A listing as the `::listings` coeffect returns it: identifiers and attributes parsed."
  {:malli/schema [:=> [:cat :string [:* :any]] :map]}
  [source-id & {:keys [name barcodes archived revision connection-id] :or {revision 1 connection-id c}}]
  {:external-product-id (str connection-id ":" source-id) :connection-id connection-id :source-id source-id
   :revision            revision :name name :archived (boolean archived)
   :identifiers         (mapv (fn [b] {:type "barcode" :value b :active true}) barcodes)
   :attributes          []})


(defn mapping
  {:malli/schema [:=> [:cat :map :string :string] :map]}
  [listing item state]
  {:external-product-id (:external-product-id listing) :connection-id (:connection-id listing) :stock-item-id item
   :listing-revision    1 :item-revision 1 :identity-basis "{}" :mapping-revision 1 :state state
   :changed-by          (str member)                   :changed-at    at})


(def catalogue
  {:products    [{:product-id "mug" :name "Mug" :status "active" :revision 1}
                 {:product-id "plate" :name "Plate" :status "active" :revision 1}]
   :stock-items [{:stock-item-id "mug-1" :product-id "mug" :condition "regular" :status "active" :revision 1}
                 {:stock-item-id "plate-1" :product-id "plate" :condition "regular" :status "active" :revision 1}]
   :barcodes    [{:stock-item-id "mug-1" :barcode "4600000000017"} {:stock-item-id "plate-1" :barcode "plate-code"}]
   :definitions []})


(def mug (listing "1" :name "Mug" :barcodes ["4600000000017"]))
(def plate (listing "2" :name "Plate" :barcodes ["plate-code"]))
(def cup (listing "3" :name "Cup"))
(def bowl (listing "4" :name "Bowl"))
(def old (listing "5" :name "Old mug" :archived true))
(def both (listing "6" :name "Set" :barcodes ["4600000000017" "plate-code"]))


(def facts
  "Mug mapped, Plate suspended, Bowl deferred; Cup and the archived listing unmatched."
  {:listings  [mug plate cup bowl old]
   :mappings  {(:external-product-id mug)   (mapping mug "mug-1" "confirmed")
               (:external-product-id plate) (mapping plate "plate-1" "suspended")}
   :deferrals {(:external-product-id bowl) {:external-product-id (:external-product-id bowl) :reason      "later"
                                            :deferred-by         (str member)                :deferred-at at}}
   :catalogue catalogue
   :earlier   {}
   :waiting   {}})


(defn read-handler
  {:malli/schema [:=> [:cat :keyword :map :map] :any]}
  [id facts input]
  ((:handler (get-in (bel/operations) [:read-models id])) facts input))


(defn ids
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :string]]}
  [rows]
  (mapv :external-product-id rows))


;; Slice 1 · Unmatched External Products and Candidates (field rules)


(deftest rows-sorted-by-waiting-operations
  (let [rows (:rows (read-handler ::products/unmatched-external-products-and-candidates
                                  (assoc facts :waiting {(:external-product-id cup) 1 (:external-product-id old) 4})
                                  {:connection-id c}))]
    (is (= [(:external-product-id old) (:external-product-id cup)] (ids rows)))
    (is (= [4 1] (mapv :waiting-operations rows)))))


(deftest deferred-rows-only-with-the-deferred-filter
  (let [read #(:rows (read-handler ::products/unmatched-external-products-and-candidates facts (merge {:connection-id c} %)))]
    (is (not-any? #{(:external-product-id bowl)} (ids (read {}))))
    (is (= [{:external-product-id (:external-product-id bowl) :state "deferred"
             :deferral            {:reason "later" :deferred-by (str member) :deferred-at (Instant/parse at)}}]
           (mapv #(select-keys % [:external-product-id :state :deferral]) (read {:state "deferred"}))))))


(deftest archived-listings-are-listed
  (let [row (first (filter #(= (:external-product-id old) (:external-product-id %))
                           (:rows (read-handler ::products/unmatched-external-products-and-candidates facts {:connection-id c}))))]
    (is (= "unmatched" (:state row)))
    (is (true? (get-in row [:evidence :archived?])))))


(deftest unmatched-rows-carry-evidence-candidates-and-conflicts
  (let [revision {:entity-id (:external-product-id both) :revision 1
                  :facts     (observations/write-json {:base {:name "Set of two"}})}
        rows     (:rows (read-handler ::products/unmatched-external-products-and-candidates
                                      (assoc facts :listings [(assoc both :revision 2)] :earlier {(:external-product-id both) [revision]})
                                      {:connection-id c}))
        row      (first rows)]
    (is (= 2 (:listing-revision row)))
    (is (= {:name        "Set" :earlier-names ["Set of two"] :archived? false
            :identifiers [{:type "barcode" :value "4600000000017" :active? true} {:type "barcode" :value "plate-code" :active? true}]
            :attributes  []}
           (:evidence row)))
    (is (= [{:barcode "4600000000017" :product-ids ["mug"]} {:barcode "plate-code" :product-ids ["plate"]}] (:conflicts row)))
    (is (= #{"mug-1" "plate-1"} (set (map :stock-item-id (:candidates row)))))
    (is (not-any? :preselected? (:candidates row)))))


;; Slice 3 · Matching Progress


(deftest progress-counts
  (let [progress (read-handler ::products/matching-progress facts {:connection-id c})]
    (is (= {:mapped 1 :unmatched 2 :deferred 1 :suspended 1} (dissoc progress :without-candidates)))
    (is (= [{:external-product-id (:external-product-id cup) :name "Cup"} {:external-product-id (:external-product-id old) :name "Old mug"}]
           (:without-candidates progress)))))


;; Slice 5 · Mapped and Remaining Listings


(deftest every-listing-by-state
  (let [read #(read-handler ::products/mapped-and-remaining-listings facts (merge {:connection-id c} %))
        all  (read {})]
    (is (= [["suspended" (:external-product-id plate)] ["unmatched" (:external-product-id cup)] ["unmatched" (:external-product-id old)]
            ["deferred" (:external-product-id bowl)] ["confirmed" (:external-product-id mug)]]
           (mapv (juxt :state :external-product-id) (:rows all))))
    (is (= {"confirmed" 1 "unmatched" 2 "deferred" 1 "suspended" 1} (:counts all)))
    (is (= {:stock-item-id "mug-1" :product-id "mug" :product-name "Mug" :archived? false}
           (:stock-item (last (:rows all)))))
    (is (= "later" (:reason (first (:rows (read {:state "deferred"}))))))
    (is (= [(:external-product-id mug)] (ids (:rows (read {:state "confirmed"})))))))


;; Slice 7 · Mapping Details and Source Evidence


(deftest mapping-details-name-who-confirmed-and-other-listings
  (let [second-shop (listing "9" :name "Mug 2" :connection-id other-c)
        removed     (assoc (mapping cup "mug-1" "removed") :mapping-revision 2)
        history     [(assoc (mapping mug "mug-1" "confirmed") :change "mapped")
                     (assoc (mapping mug "mug-1" "suspended") :change "suspended" :mapping-revision 2
                            :changed-by "connector" :reasons (observations/write-json [{:rule "item-archived"}]))
                     (assoc (mapping mug "mug-1" "confirmed") :change "reconfirmed" :mapping-revision 3
                            :changed-at "2026-11-21T10:00:00Z")]
        details     (read-handler ::products/mapping-details-and-source-evidence
                                  {:listing    mug
                                   :mappings   (assoc (:mappings facts)
                                                      (:external-product-id second-shop) (mapping second-shop "mug-1" "confirmed")
                                                      (:external-product-id cup) removed)
                                   :history    history
                                   :revisions  [{:revision 1 :observed-at at :facts (observations/write-json {:base {:name "Mug"}})}]
                                   :catalogue  catalogue
                                   :operations []
                                   :others     {(:external-product-id second-shop) second-shop}}
                                  {:external-product-id (:external-product-id mug)})]
    (is (= "confirmed" (:state details)))
    (is (= [{:revision 1 :observed-at (Instant/parse at) :name "Mug"}] (get-in details [:listing :revisions])))
    (is (= ["mapped" "suspended" "reconfirmed"] (mapv :change (:history details))))
    (is (= [{:rule "item-archived"}] (:reasons (second (:history details)))))
    (is (= [(str member) (Instant/parse "2026-11-21T10:00:00Z")]
           ((juxt :confirmed-by :confirmed-at) (:mapping details))))
    (is (= "Mug" (get-in details [:stock-item :product-name])))
    (is (= [{:external-product-id (:external-product-id second-shop) :connection-id other-c :state "confirmed" :name "Mug 2"}]
           (:other-listings details)))))


(deftest unknown-listing-has-no-details
  (is (= :not-found (try (read-handler ::products/mapping-details-and-source-evidence {:mappings {} :history [] :revisions []}
                                       {:external-product-id "x"})
                         nil
                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))


;; Through a store: event mappings, coeffects and the connection's unmatched count


(defn context
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})         :repo repo :workspace-id workspace :user-id member
                            :memberships [{:workspace-id workspace :role :member}]}))


(defn seed!
  {:malli/schema [:=> [:cat :map :keyword :map] :nil]}
  [ctx type data]
  (store/with-store (assoc ctx :bel/definitions (bel/operations)) :catalogue :serializable
    #(runtime/apply-event! % {:type type :data data})))


(defn observed
  {:malli/schema [:=> [:cat :string :string [:* :any]] :map]}
  [source-id name & {:keys [barcodes revision connection-id] :or {revision 1 connection-id c}}]
  (let [id (str connection-id ":" source-id)]
    {:external-product-id id :entity-id id :connection-id connection-id :source-id source-id :revision revision
     :payload-hash        (apply str (repeat 64 "a")) :adapter-version "test" :observed-at at    :last-observed-at at
     :source-revision-id  (str id "#" revision) :name                  name   :archived    false
     :identifiers         (observations/write-json (mapv (fn [b] {:type "barcode" :value b :active true}) barcodes))
     :facts               (observations/write-json {:base {:name name}})}))


(defn mapped
  {:malli/schema [:=> [:cat :string :string :int [:* :any]] :map]}
  [listing-id item revision & {:keys [connection-id] :or {connection-id c}}]
  {:external-product-id listing-id   :mapping-revision-id (products/revision-id listing-id revision) :connection-id connection-id
   :change              "mapped"     :stock-item-id       item :listing-revision 1 :item-revision 1
   :identity-basis      "{}"         :mapping-revision    revision :state "confirmed"
   :changed-by          (str member) :changed-at          at})


(deftest reads-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx  (context repo)
            read #(runtime/read-model ctx %1 %2)
            mug  (str c ":1")
            cup  (str c ":3")]
        (seed! ctx :tover.catalogues.products.model/product-registered {:product-id "mug" :name "Mug" :status "active" :revision 1})
        (seed! ctx :tover.inventory.stock-items.model/stock-item-registered
               {:stock-item-id "mug-1" :product-id "mug" :condition "regular" :status "active" :revision 1})
        (seed! ctx :tover.inventory.stock-items.model/stock-item-barcode-assigned
               {:barcode-id "mug-1|4600000000017" :stock-item-id "mug-1" :barcode "4600000000017"})
        (seed! ctx ::sync/external-product-observed (observed "1" "Mug" :barcodes ["4600000000017"]))
        (seed! ctx ::sync/external-product-observed (observed "3" "Cup"))

        (testing "slice 1 · the unmatched listing with its preselected candidate"
          (let [rows (:rows (read ::products/unmatched-external-products-and-candidates {:connection-id c}))]
            (is (= [cup mug] (ids rows)))
            (is (= [["mug-1" true]] (mapv (juxt :stock-item-id :preselected?) (:candidates (second rows)))))
            (is (= 2 (connections/count-part :unmatched-listings ctx c)))))

        (testing "a deferral leaves the queue; mapping removes it"
          (seed! ctx ::products/product-match-deferred {:external-product-id mug :deferred-by (str member) :deferred-at at})
          (is (= {:mapped 0 :unmatched 1 :deferred 1 :suspended 0}
                 (dissoc (read ::products/matching-progress {:connection-id c}) :without-candidates)))
          (is (= 2 (connections/count-part :unmatched-listings ctx c)) "a deferred listing has no confirmed mapping")
          (seed! ctx ::products/external-product-mapped (mapped mug "mug-1" 1))
          (is (= {:mapped 1 :unmatched 1 :deferred 0 :suspended 0}
                 (dissoc (read ::products/matching-progress {:connection-id c}) :without-candidates)))
          (is (= 1 (connections/count-part :unmatched-listings ctx c))))

        (testing "removing returns the listing to unmatched; mapping again writes in place"
          (seed! ctx ::products/product-mapping-changed
                 {:external-product-id    mug          :mapping-revision-id (products/revision-id mug 2) :connection-id c :change "changed"
                  :previous-stock-item-id "mug-1"      :listing-revision    1 :mapping-revision 2 :state "removed"
                  :changed-by             (str member) :changed-at          at})
          (is (= "unmatched" (:state (first (:rows (read ::products/mapped-and-remaining-listings {:connection-id c :state "unmatched"}))))))
          (seed! ctx ::products/external-product-mapped (mapped mug "mug-1" 3))
          (let [details (read ::products/mapping-details-and-source-evidence {:external-product-id mug})]
            (is (= ["mapped" "changed" "mapped"] (mapv :change (:history details))))
            (is (= [nil "mug-1" nil] (mapv :previous-stock-item-id (:history details))))
            (is (= "confirmed" (:state details)))
            (is (= [{:revision 1 :observed-at (Instant/parse at) :name "Mug"}] (get-in details [:listing :revisions])))
            (is (= [{:type "barcode" :value "4600000000017" :active? true}] (get-in details [:listing :evidence :identifiers])))))

        (testing "a change takes the listing revision it was decided against"
          (seed! ctx ::sync/external-product-observed (observed "1" "Mug" :barcodes ["4600000000017"] :revision 2))
          (seed! ctx ::products/product-mapping-changed
                 {:external-product-id    mug          :mapping-revision-id (products/revision-id mug 4) :connection-id c :change "changed"
                  :previous-stock-item-id "mug-1"      :stock-item-id       "mug-1" :item-revision 1 :identity-basis              "{}"
                  :listing-revision       2            :mapping-revision    4 :state "confirmed"
                  :changed-by             (str member) :changed-at          at})
          (is (= 2 (get-in (read ::products/mapping-details-and-source-evidence {:external-product-id mug}) [:mapping :listing-revision]))))

        (testing "status counts unmatched listings"
          (is (= 1 (get (runtime/coeffect ::connections/counts ctx {:connection-id c}) :unmatched-listings))))))))


(defn seed-four!
  "Listings 1-4 of a connection: unmatched, deferred, suspended and confirmed (to `item`)."
  {:malli/schema [:=> [:cat :map :string :string] :nil]}
  [ctx connection-id item]
  (let [id #(str connection-id ":" %)]
    (doseq [n ["1" "2" "3" "4"]]
      (seed! ctx ::sync/external-product-observed (observed n (str "Listing " n) :connection-id connection-id)))
    (seed! ctx ::products/product-match-deferred {:external-product-id (id "2") :reason "later" :deferred-by (str member) :deferred-at at})
    (seed! ctx ::products/external-product-mapped (mapped (id "3") item 1 :connection-id connection-id))
    (seed! ctx ::products/product-mapping-suspended
           {:external-product-id (id "3") :mapping-revision-id (products/revision-id (id "3") 2)         :connection-id connection-id
            :change              "suspended" :stock-item-id item :listing-revision 1 :item-revision 1 :identity-basis "{}"
            :reasons             (observations/write-json [{:rule "item-archived"}]) :mapping-revision 2 :state         "suspended"
            :changed-by          "connector" :changed-at at})
    (seed! ctx ::products/external-product-mapped (mapped (id "4") item 1 :connection-id connection-id))
    nil))


(deftest unmatched-count-is-every-listing-without-a-confirmed-mapping
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (seed-four! ctx c "mug-1")
        (is (= 3 (connections/count-part :unmatched-listings ctx c)) "unmatched, deferred and suspended count; confirmed does not")
        (is (= {:mapped 1 :unmatched 1 :deferred 1 :suspended 1}
               (dissoc (runtime/read-model ctx ::products/matching-progress {:connection-id c}) :without-candidates)))))))


(deftest another-connection-stays-apart
  (support/with-store
    (fn [repo _]
      (let [ctx  (context repo)
            read #(runtime/read-model ctx %1 %2)
            mine (str c ":9")]
        (seed-four! ctx other-c "other-1")
        (seed! ctx ::sync/external-product-observed (observed "9" "Mine"))
        (is (= [mine] (ids (:rows (read ::products/unmatched-external-products-and-candidates {:connection-id c})))))
        (is (= [] (:rows (read ::products/unmatched-external-products-and-candidates {:connection-id c :state "deferred"}))))
        (is (= {:mapped 0 :unmatched 1 :deferred 0 :suspended 0 :without-candidates [{:external-product-id mine :name "Mine"}]}
               (read ::products/matching-progress {:connection-id c})))
        (is (= [["unmatched" mine]] (mapv (juxt :state :external-product-id)
                                          (:rows (read ::products/mapped-and-remaining-listings {:connection-id c})))))
        (is (= {"confirmed" 0 "unmatched" 1 "deferred" 0 "suspended" 0}
               (:counts (read ::products/mapped-and-remaining-listings {:connection-id c}))))
        (let [details (read ::products/mapping-details-and-source-evidence {:external-product-id mine})]
          (is (= "unmatched" (:state details)))
          (is (= [] (:history details)))
          (is (= [] (:other-listings details))))
        (is (= 1 (connections/count-part :unmatched-listings ctx c)))
        (is (= 3 (connections/count-part :unmatched-listings ctx other-c)))))))


;; Slices 2, 6, 8 and c · the member's commands over given facts


(def blue
  "Listing c:9001 states the Mug's barcode, so Mug's only item is preselected."
  (listing "9001" :name "Mug blue" :barcodes ["4600000000017"] :connection-id "c"))


(def command-facts
  {:listings  {(:external-product-id blue) blue}
   :mappings  {}
   :catalogue (update catalogue :stock-items conj
                      {:stock-item-id "mug-2" :product-id "mug" :variant-label "red" :condition "regular" :status "active" :revision 1})})


(defn decide
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [command facts input]
  (runtime/decide command (merge {:scope support/scope :actor member :now (Instant/parse at)} command-facts facts) input))


(defn with-listing
  "Command facts in which c:9001 is `listing`."
  {:malli/schema [:=> [:cat :map] :map]}
  [listing]
  {:listings {(:external-product-id listing) listing}})


(def row
  {:external-product-id "c:9001" :listing-revision 1 :stock-item-id "mug-1" :item-revision 1})


(defn confirm
  {:malli/schema [:=> [:cat [:sequential :map] [:* :any]] :map]}
  [rows & {:as facts}]
  (decide ::products/confirm-product-mapping (or facts {}) {:rows rows}))


(defn event
  "The data of the plan's first event of `type`."
  {:malli/schema [:=> [:cat :map :keyword] [:maybe :map]]}
  [plan type]
  (some #(when (= type (:type %)) (:data %)) (:events plan)))


(defn offending
  "The rejection's rows as [listing code owner-product], or nil when accepted."
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe [:vector [:vector :any]]]]}
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (mapv #(vec (remove nil? ((juxt :external-product-id :code :owner-product) %))) (:rows (ex-data e))))))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(def accepted-fx
  [[:bel/publish {:topic :tover.inventory.web/changed :scope-id workspace :data {}}]
   [::sync/mark-dirty {:connection-id "c"}]])


;; Slice 2 · Confirm a mapping to an existing stock item


(deftest one-click
  (let [plan (confirm [row])]
    (is (= {:external-product-id "c:9001" :stock-item-id "mug-1" :mapping-revision 1 :state "confirmed"}
           (select-keys (event plan ::products/external-product-mapped) [:external-product-id :stock-item-id :mapping-revision :state])))
    (is (= {:mapping-revision-id "c:9001#1"   :connection-id "c" :change "mapped" :listing-revision 1 :item-revision 1
            :changed-by          (str member) :changed-at    at}
           (select-keys (event plan ::products/external-product-mapped)
                        [:mapping-revision-id :connection-id :change :listing-revision :item-revision :changed-by :changed-at])))
    (is (= {:attributes {} :pack nil :unit nil :variant-label nil}
           (observations/read-json (:identity-basis (event plan ::products/external-product-mapped)))))
    (is (= accepted-fx (:fx plan)) "the listing's waiting operations are evaluated again")))


(deftest foreign-barcode
  (let [row-with-plate-code (with-listing (listing "9001" :barcodes ["4600000000017" "plate-code"] :connection-id "c"))]
    (is (= [["c:9001" :barcode-conflict "Plate"]] (offending #(confirm [row] row-with-plate-code))))
    (is (= :barcode-conflict (rejection #(confirm [row] row-with-plate-code))))
    (testing "an archived item keeps its barcode reserved to its product"
      (is (= [["c:9001" :barcode-conflict "Plate"]]
             (offending #(confirm [row] (merge row-with-plate-code
                                               {:catalogue (update catalogue :stock-items (partial mapv (fn [i] (cond-> i (= "plate-1" (:stock-item-id i)) (assoc :status "archived")))))}))))))))


(deftest adopt-a-barcode
  (let [facts (with-listing (listing "9001" :barcodes ["4600000000017" "4600000000024"] :connection-id "c"))
        plan  (confirm [(assoc row :adopt-barcodes ["4600000000024" " 4600000000017 "])] facts)]
    (is (= [::products/external-product-mapped :tover.inventory.stock-items.model/stock-item-barcode-assigned] (mapv :type (:events plan)))
        "the item's own barcode changes nothing")
    (is (= {:barcode-id "mug-1|4600000000024" :stock-item-id "mug-1" :barcode "4600000000024"}
           (event plan :tover.inventory.stock-items.model/stock-item-barcode-assigned)))
    (testing "nothing ticked, nothing assigned"
      (is (= [::products/external-product-mapped] (mapv :type (:events (confirm [row] facts))))))))


(deftest adopted-barcode-must-be-active-on-the-listing
  (let [facts (with-listing (update (listing "9001" :barcodes ["4600000000024"] :connection-id "c")
                                    :identifiers conj {:type "barcode" :value "4600000000031" :active false}))]
    (is (= [["c:9001" :barcode-not-on-listing]] (offending #(confirm [(assoc row :adopt-barcodes ["4600000000031"])] facts))))
    (is (= :validation (rejection #(confirm [(assoc row :adopt-barcodes ["4600000000048"])] facts))))))


(deftest two-members
  (let [mapped {:mappings {"c:9001" (mapping blue "mug-1" "confirmed")}}]
    (is (= [["c:9001" :already-mapped]] (offending #(confirm [(assoc row :stock-item-id "mug-2")] mapped))))
    (is (= {:events [] :fx []} (confirm [row] mapped)) "the same decision twice changes nothing")
    (is (= [["c:9001" :already-mapped]]
           (offending #(confirm [row] {:mappings {"c:9001" (mapping blue "mug-1" "suspended")}})))
        "a suspended mapping is reconfirmed, not confirmed")
    (testing "a removed mapping is mapped again with the next revision"
      (is (= 3 (:mapping-revision (event (confirm [row] {:mappings {"c:9001" (assoc (mapping blue "mug-1" "removed") :mapping-revision 2)}})
                                         ::products/external-product-mapped)))))))


(deftest same-listing-twice-in-a-batch
  (is (= [["c:9001" :listed-twice] ["c:9001" :listed-twice]]
         (offending #(confirm [row (assoc row :stock-item-id "mug-2")]))))
  (is (= :already-mapped (rejection #(confirm [row (assoc row :stock-item-id "mug-2")]))))
  (is (= 1 (count (:events (confirm [row row])))) "the same row twice is one decision"))


(deftest one-bad-row-accepts-nothing
  (let [cup   (listing "9002" :name "Cup" :connection-id "c")
        facts {:listings {"c:9001" blue "c:9002" cup}}
        rows  [row {:external-product-id "c:9002" :listing-revision 1 :stock-item-id "mug-2" :item-revision 1}]]
    (is (= 2 (count (:events (confirm rows facts)))))
    (is (= [["c:9002" :stale]] (offending #(confirm (assoc-in rows [1 :listing-revision] 2) facts))))
    (is (= [["c:9002" :stale]] (offending #(confirm (assoc-in rows [1 :item-revision] 2) facts))))
    (is (= [["c:9002" :item-not-available]] (offending #(confirm (assoc-in rows [1 :stock-item-id] "gone") facts))))
    (testing "every bad row is named"
      (is (= [["c:9001" :stale] ["c:9002" :stale]]
             (offending #(confirm (mapv (fn [r] (assoc r :listing-revision 5)) rows) facts)))))
    (testing "a barcode adopted by one row is not free for another product in the same batch"
      (let [shared {:listings {"c:9001" (listing "9001" :barcodes ["4600000000024"] :connection-id "c")
                               "c:9002" (listing "9002" :barcodes ["4600000000024"] :connection-id "c")}}]
        (is (= [["c:9002" :barcode-conflict "Mug"]]
               (offending #(confirm [(assoc row :adopt-barcodes ["4600000000024"])
                                     {:external-product-id "c:9002" :listing-revision 1 :stock-item-id "plate-1" :item-revision 1}]
                                    shared))))))))


(deftest archived-and-defective-items-are-not-available
  (let [with-item #(update-in command-facts [:catalogue :stock-items] conj %)]
    (is (= [["c:9001" :item-not-available]]
           (offending #(confirm [(assoc row :stock-item-id "mug-3")]
                                (with-item {:stock-item-id "mug-3" :product-id "mug" :condition "defective" :status "active" :revision 1})))))
    (is (= [["c:9001" :item-not-available]]
           (offending #(confirm [(assoc row :stock-item-id "mug-3")]
                                (with-item {:stock-item-id "mug-3" :product-id "mug" :condition "regular" :status "archived" :revision 1})))))
    (testing "an archived listing may be mapped: its old operations still need it"
      (is (some? (event (confirm [row] (with-listing (assoc blue :archived true))) ::products/external-product-mapped))))))


;; Slice 6 · Defer a listing


(deftest defer-sets-a-listing-aside
  (let [cup  (listing "9002" :name "Cup" :connection-id "c")
        plan (decide ::products/defer-product-match {:listings {"c:9001" blue "c:9002" cup}}
                     {:external-product-ids ["c:9001" "c:9002" "c:9001"] :reason "  not sold from stock "})]
    (is (= [{:external-product-id "c:9001" :reason "not sold from stock" :deferred-by (str member) :deferred-at at}
            {:external-product-id "c:9002" :reason "not sold from stock" :deferred-by (str member) :deferred-at at}]
           (mapv :data (:events plan))))
    (is (= accepted-fx (:fx plan)))
    (is (nil? (:reason (:data (first (:events (decide ::products/defer-product-match {} {:external-product-ids ["c:9001"] :reason " "})))))))))


(deftest defer-rejects-a-mapped-listing
  (is (= [["c:9001" :already-mapped]]
         (offending #(decide ::products/defer-product-match {:mappings {"c:9001" (mapping blue "mug-1" "confirmed")}}
                             {:external-product-ids ["c:9001"]}))))
  (is (= [["c:9404" :stale]] (offending #(decide ::products/defer-product-match {} {:external-product-ids ["c:9404"]}))))
  (is (= 1 (count (:events (decide ::products/defer-product-match {:mappings {"c:9001" (mapping blue "mug-1" "removed")}}
                                   {:external-product-ids ["c:9001"]}))))
      "a removed mapping is unmatched again"))


;; Slice 8 · Change the mapping


(def change-facts
  {:mappings {"c:9001" (mapping blue "mug-1" "confirmed")}})


(defn change
  {:malli/schema [:=> [:cat :map [:* :any]] :map]}
  [input & {:as facts}]
  (decide ::products/change-product-mapping (merge change-facts facts) (merge {:external-product-id "c:9001" :mapping-revision 1} input)))


(deftest change-points-at-another-item
  (let [data (event (change {:stock-item-id "mug-2" :item-revision 1}) ::products/product-mapping-changed)]
    (is (= {:external-product-id    "c:9001" :mapping-revision-id "c:9001#2" :mapping-revision 2 :change "changed"
            :previous-stock-item-id "mug-1"  :stock-item-id       "mug-2"    :item-revision    1 :state  "confirmed" :listing-revision 1}
           (dissoc data :identity-basis :connection-id :changed-by :changed-at)))
    (is (= "red" (:variant-label (observations/read-json (:identity-basis data)))))
    (is (= accepted-fx (:fx (change {:stock-item-id "mug-2" :item-revision 1}))))))


(deftest change-checks-like-confirm
  (is (= [["c:9001" :stale]] (offending #(change {:mapping-revision 2 :stock-item-id "mug-2" :item-revision 1}))))
  (is (= [["c:9001" :stale]] (offending #(change {:stock-item-id "mug-2" :item-revision 3}))))
  (is (= [["c:9001" :item-not-available]] (offending #(change {:stock-item-id "gone" :item-revision 1}))))
  (is (= [["c:9001" :barcode-conflict "Mug"]] (offending #(change {:stock-item-id "plate-1" :item-revision 1}))))
  (is (= {:events [] :fx []} (change {:stock-item-id "mug-1" :item-revision 1})) "the same item changes nothing"))


;; Slice c · Reconfirm the mapping


(def suspended-facts
  {:listings {"c:9001" (assoc blue :revision 2)}
   :mappings {"c:9001" (assoc (mapping blue "mug-1" "suspended") :mapping-revision 2 :reasons "[]")}})


(defn reconfirm
  {:malli/schema [:=> [:cat :map [:* :any]] :map]}
  [input & {:as facts}]
  (decide ::products/reconfirm-product-mapping (merge suspended-facts facts)
          (merge {:external-product-id "c:9001" :mapping-revision 2 :listing-revision 2 :item-revision 1} input)))


(deftest reconfirm-a-suspended-mapping
  (let [plan (reconfirm {})]
    (is (= {:external-product-id "c:9001" :mapping-revision-id "c:9001#3" :mapping-revision 3 :change "reconfirmed"
            :stock-item-id       "mug-1"  :listing-revision    2          :item-revision    1 :state  "confirmed"   :connection-id "c"}
           (dissoc (event plan ::products/product-mapping-reconfirmed) :identity-basis :changed-by :changed-at)))
    (is (= accepted-fx (:fx plan)))))


(deftest reconfirm-rejections
  (is (= [["c:9001" :not-suspended]] (offending #(reconfirm {} :mappings {"c:9001" (assoc (mapping blue "mug-1" "confirmed") :mapping-revision 2)}))))
  (is (= [["c:9001" :not-suspended]] (offending #(reconfirm {} :mappings {}))))
  (is (= [["c:9001" :stale]] (offending #(reconfirm {:mapping-revision 1}))))
  (is (= [["c:9001" :stale]] (offending #(reconfirm {:listing-revision 1}))))
  (is (= [["c:9001" :stale]] (offending #(reconfirm {:item-revision 2}))))
  (is (= [["c:9001" :item-archived]]
         (offending #(reconfirm {} :catalogue (update catalogue :stock-items (partial mapv (fn [i] (cond-> i (= "mug-1" (:stock-item-id i)) (assoc :status "archived")))))))))
  (is (= [["c:9001" :barcode-conflict "Plate"]]
         (offending #(reconfirm {} :listings {"c:9001" (listing "9001" :barcodes ["plate-code"] :revision 2 :connection-id "c")})))))


;; Through a store: one commit, history and workspace isolation


(defn execute!
  {:malli/schema [:=> [:cat :map :keyword :string :map] :map]}
  [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input input}))


(defn seed-catalogue!
  {:malli/schema [:=> [:cat :map] :nil]}
  [ctx]
  (seed! ctx :tover.catalogues.products.model/product-registered {:product-id "mug" :name "Mug" :status "active" :revision 1})
  (doseq [item ["mug-1" "mug-2"]]
    (seed! ctx :tover.inventory.stock-items.model/stock-item-registered
           {:stock-item-id item :product-id "mug" :condition "regular" :status "active" :revision 1}))
  (seed! ctx ::sync/external-product-observed (observed "1" "Mug" :barcodes ["4600000000024"])))


(deftest adopt-a-barcode-in-one-commit
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)
            id  (str c ":1")]
        (seed-catalogue! ctx)
        (execute! ctx ::products/confirm-product-mapping "confirm-1"
                  {:rows [{:external-product-id id                :listing-revision 1 :stock-item-id "mug-1" :item-revision 1
                           :adopt-barcodes      ["4600000000024"]}]})
        (is (= "confirmed" (:state (runtime/read-model ctx ::products/mapping-details-and-source-evidence {:external-product-id id}))))
        (is (= [["mug-1" "4600000000024"]]
               (mapv (juxt :stock-item-id :barcode) (:barcodes (runtime/coeffect ::products/catalogue ctx {})))))))))


(deftest two-members-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx   (context repo)
            other (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})            :repo repo :workspace-id workspace
                                            :user-id     #uuid "00000000-0000-0000-0000-0000000000b2"
                                            :memberships [{:workspace-id workspace :role :member}]})
            input #(hash-map :rows [{:external-product-id (str c ":1") :listing-revision 1 :stock-item-id % :item-revision 1}])]
        (seed-catalogue! ctx)
        (execute! ctx ::products/confirm-product-mapping "first" (input "mug-1"))
        (is (= :already-mapped (rejection #(execute! other ::products/confirm-product-mapping "second" (input "mug-2")))))
        (is (map? (execute! other ::products/confirm-product-mapping "third" (input "mug-1"))))
        (is (= ["mapped"] (mapv :change (:history (runtime/read-model ctx ::products/mapping-details-and-source-evidence
                                                                      {:external-product-id (str c ":1")})))))))))


(deftest remap
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)
            id  (str c ":1")]
        (seed-catalogue! ctx)
        (execute! ctx ::products/confirm-product-mapping "confirm-1"
                  {:rows [{:external-product-id id :listing-revision 1 :stock-item-id "mug-1" :item-revision 1}]})
        (execute! ctx ::products/change-product-mapping "change-1"
                  {:external-product-id id :mapping-revision 1 :stock-item-id "mug-2" :item-revision 1})
        (let [details (runtime/read-model ctx ::products/mapping-details-and-source-evidence {:external-product-id id})]
          (is (= [[1 "mapped" "mug-1" nil] [2 "changed" "mug-2" "mug-1"]]
                 (mapv (juxt :mapping-revision :change :stock-item-id :previous-stock-item-id) (:history details)))
              "the previous stock item stays in revision 1; revision 2 names B")
          (is (= "mug-2" (get-in details [:stock-item :stock-item-id]))))))))


(deftest removing-returns-to-unmatched
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)
            id  (str c ":1")]
        (seed-catalogue! ctx)
        (execute! ctx ::products/confirm-product-mapping "confirm-1"
                  {:rows [{:external-product-id id :listing-revision 1 :stock-item-id "mug-1" :item-revision 1}]})
        (execute! ctx ::products/change-product-mapping "remove-1" {:external-product-id id :mapping-revision 1})
        (is (= [["unmatched" id]] (mapv (juxt :state :external-product-id)
                                        (:rows (runtime/read-model ctx ::products/mapped-and-remaining-listings {:connection-id c})))))
        (is (= {:state "removed" :previous-stock-item-id "mug-1"}
               (select-keys (last (:history (runtime/read-model ctx ::products/mapping-details-and-source-evidence {:external-product-id id})))
                            [:state :previous-stock-item-id :stock-item-id])))
        (testing "confirming again maps it in place with the next revision"
          (execute! ctx ::products/confirm-product-mapping "confirm-2"
                    {:rows [{:external-product-id id :listing-revision 1 :stock-item-id "mug-2" :item-revision 1}]})
          (is (= 3 (get-in (runtime/read-model ctx ::products/mapping-details-and-source-evidence {:external-product-id id})
                           [:mapping :mapping-revision]))))))))


(deftest another-workspaces-item-is-not-available
  (support/with-store
    (fn [repo _]
      (let [ctx       (context repo)
            elsewhere #uuid "00000000-0000-0000-0000-000000000002"
            theirs    (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})         :repo repo :workspace-id elsewhere
                                                :user-id     member
                                                :memberships [{:workspace-id elsewhere :role :member}]})]
        (seed! theirs :tover.catalogues.products.model/product-registered {:product-id "theirs" :name "Theirs" :status "active" :revision 1})
        (seed! theirs :tover.inventory.stock-items.model/stock-item-registered
               {:stock-item-id "their-1" :product-id "theirs" :condition "regular" :status "active" :revision 1})
        (seed! ctx ::sync/external-product-observed (observed "1" "Mug"))
        (is (= :item-not-available
               (rejection #(execute! ctx ::products/confirm-product-mapping "confirm-1"
                                     {:rows [{:external-product-id (str c ":1") :listing-revision 1 :stock-item-id "their-1" :item-revision 1}]}))))))))

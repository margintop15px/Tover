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
  [source-id name & {:keys [barcodes revision] :or {revision 1}}]
  (let [id (str c ":" source-id)]
    {:external-product-id id :entity-id id :connection-id c :source-id source-id :revision revision
     :payload-hash        (apply str (repeat 64 "a")) :adapter-version "test" :observed-at at    :last-observed-at at
     :source-revision-id  (str id "#" revision) :name                  name   :archived    false
     :identifiers         (observations/write-json (mapv (fn [b] {:type "barcode" :value b :active true}) barcodes))
     :facts               (observations/write-json {:base {:name name}})}))


(defn mapped
  {:malli/schema [:=> [:cat :string :string :int] :map]}
  [listing-id item revision]
  {:external-product-id listing-id   :mapping-revision-id (products/revision-id listing-id revision) :connection-id c
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

        (testing "status counts unmatched listings"
          (is (= 1 (get (runtime/coeffect ::connections/counts ctx {:connection-id c}) :unmatched-listings))))))))

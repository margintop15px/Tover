(ns tover.marketplace.products.model
  "Board chapter 42 · Match marketplace products to stock items: product mappings, deferrals, the matching
  reads (slices 1, 3, 5, 7) and the member's confirm, register, defer, change and reconfirm commands (slices 2,
  4, 6, 8, c).

  A listing is an `External Product Observed` row of one connection. A mapping links it to one stock item;
  several listings may map to the same item. States: `confirmed` (permits automatic acceptance), `suspended`
  (the Mapping Guard found a change), `removed` (changed to no item: unmatched again). A deferral takes an
  unmatched listing out of the queue until it is mapped.

  Store: every model lives in `:catalogue`. The RDF adapter stores scalars only, so the identity basis and
  suspension reasons are JSON text and instants ISO text. Bel maps event fields by name, so the board's
  `confirmed by/at`, `changed by/at`, `reconfirmed by/at` and `suspended at` are `changed-by`/`changed-at` in
  every event. `External Product Mapped` writes with `:set`: a removed mapping is mapped again in place. Each
  of the four mapping events also creates a revision `<external-product-id>#<mapping-revision>`."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.inventory.changes :as changes]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.products.candidates :as candidates]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]))


;; Data model (element details on the board)


(def Ref connections/Ref)
(def At connections/At)


(def ListingId
  [:string {:min 1 :max 600}])


(def Json
  [:string {:max 200000}])


(def Revision
  [:int {:min 1}])


(def MappingState
  [:enum "confirmed" "suspended" "removed"])


(def ProductMapping
  [:map
   [:external-product-id ListingId]
   [:connection-id Ref]
   [:stock-item-id {:optional true} Ref]
   [:listing-revision Revision]
   [:item-revision {:optional true} Revision]
   [:identity-basis {:optional true} Json]
   [:mapping-revision Revision]
   [:state MappingState]
   [:reasons {:optional true} Json]
   [:changed-by Ref]
   [:changed-at At]])


(def Change
  [:enum "mapped" "changed" "suspended" "reconfirmed"])


(def ProductMappingRevision
  "One mapping revision as its event left it; never updated."
  (into ProductMapping
        [[:mapping-revision-id [:string {:min 1 :max 700}]]
         [:change Change]
         [:previous-stock-item-id {:optional true} Ref]]))


(def ProductDeferral
  [:map [:external-product-id ListingId] [:reason {:optional true} [:string {:max 1000}]] [:deferred-by Ref] [:deferred-at At]])


(defn revision-id
  {:malli/schema [:=> [:cat :string :int] :string]}
  [external-product-id mapping-revision]
  (str external-product-id "#" mapping-revision))


(def ^:private Common
  [[:external-product-id ListingId] [:mapping-revision-id :string] [:connection-id Ref]
   [:mapping-revision Revision] [:changed-by Ref] [:changed-at At]])


(def ^:private Unsuspended
  "Absent fields the mapping and revision models list: written as cleared."
  [[:previous-stock-item-id {:optional true} Ref] [:reasons {:optional true} Json]])


(def ExternalProductMapped
  (into [:map] (concat Common Unsuspended [[:change [:= "mapped"]] [:stock-item-id Ref] [:listing-revision Revision]
                                           [:item-revision Revision] [:identity-basis Json] [:state [:= "confirmed"]]])))


(def ProductMappingChanged
  "A blank new item means unmatched: state `removed`, no item, revision or basis."
  (into [:map] (concat Common Unsuspended [[:change [:= "changed"]]
                                           [:stock-item-id {:optional true} Ref] [:listing-revision Revision]
                                           [:item-revision {:optional true} Revision] [:identity-basis {:optional true} Json]
                                           [:state [:enum "confirmed" "removed"]]])))


(def ProductMappingSuspended
  (into [:map] (concat Common [[:previous-stock-item-id {:optional true} Ref] [:change [:= "suspended"]] [:stock-item-id Ref] [:listing-revision Revision]
                               [:item-revision Revision] [:identity-basis Json]
                               [:reasons Json] [:state [:= "suspended"]]])))


(def ProductMappingReconfirmed
  (into [:map] (concat Common Unsuspended [[:change [:= "reconfirmed"]] [:stock-item-id Ref] [:listing-revision Revision]
                                           [:item-revision Revision] [:identity-basis Json] [:state [:= "confirmed"]]])))


(def ProductMatchDeferred
  ProductDeferral)


(defevent external-product-mapped {:schema ExternalProductMapped})
(defevent product-match-deferred {:schema ProductMatchDeferred})
(defevent product-mapping-changed {:schema ProductMappingChanged})
(defevent product-mapping-suspended {:schema ProductMappingSuspended})
(defevent product-mapping-reconfirmed {:schema ProductMappingReconfirmed})


(def ^:private mapping-fields
  [:connection-id :stock-item-id :listing-revision :item-revision :identity-basis :mapping-revision :state :reasons
   :changed-by :changed-at])


(defmodel product-mappings
  {:id     :external-product-id :store :catalogue :schema ProductMapping :policy ::records/workspace-member
   ;; `:set` replaces every listed field: a change or reconfirmation clears the reasons.
   :events {::external-product-mapped     {:set mapping-fields}
            ::product-mapping-changed     {:set (vec (remove #{:connection-id} mapping-fields))}
            ::product-mapping-suspended   {:set [:mapping-revision :state :reasons :changed-by :changed-at]}
            ::product-mapping-reconfirmed {:set (vec (remove #{:connection-id} mapping-fields))}}})


(def ^:private revision-fields
  (into [:external-product-id :change :previous-stock-item-id] mapping-fields))


(defmodel product-mapping-revisions
  {:id     :mapping-revision-id :store :catalogue :schema ProductMappingRevision :policy ::records/workspace-member
   :events (into {} (for [event [::external-product-mapped ::product-mapping-changed ::product-mapping-suspended
                                 ::product-mapping-reconfirmed]]
                      [event {:create revision-fields}]))})


(defmodel product-deferrals
  {:id     :external-product-id :store :catalogue :schema ProductDeferral :policy ::records/workspace-member
   :events {::product-match-deferred  {:set [:reason :deferred-by :deferred-at]}
            ::external-product-mapped {:delete true}
            ::product-mapping-changed {:delete true}}})


;; Facts


(defn- listing-row
  "A listing with its identifiers and attributes parsed."
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (assoc row
         :identifiers (vec (observations/read-json (:identifiers row)))
         :attributes (vec (observations/read-json (:attributes row)))))


(defn- by-id
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] :map]}
  [rows k]
  (into {} (map (juxt k identity)) rows))


(defmethod runtime/coeffect ::mappings
  [_ context {:keys [connection-id]}]
  (by-id (cond->> (records/all-records context ::product-mappings :external-product-id)
           connection-id (filter #(= connection-id (:connection-id %))))
         :external-product-id))


(defmethod runtime/coeffect ::deferrals
  [_ context _]
  (by-id (records/all-records context ::product-deferrals :external-product-id) :external-product-id))


(defmethod runtime/coeffect ::listings
  [_ context {:keys [connection-id]}]
  ;; ponytail: scans the workspace's listings; a per-connection query when several large connections exist.
  (if connection-id
    (into [] (comp (filter #(= connection-id (:connection-id %))) (map listing-row))
          (records/all-records context ::sync/external-products :external-product-id))
    []))


(defmethod runtime/coeffect ::listing
  [_ context {:keys [external-product-id]}]
  (some-> (get (sync/load-rows context ::sync/external-products [external-product-id]) external-product-id) listing-row))


(defmethod runtime/coeffect ::catalogue
  [_ context {:keys [connection-id]}]
  {:products    (records/all-records context :tover.catalogues.products.model/products :product-id)
   :stock-items (records/all-records context :tover.inventory.stock-items.model/stock-items :stock-item-id)
   :barcodes    (records/all-records context :tover.inventory.stock-items.model/stock-item-barcodes :barcode-id)
   :definitions (cond->> (records/all-records context ::sync/field-definitions :field-definition-id)
                  connection-id (filterv #(= connection-id (:connection-id %))))})


(defn- earlier-revisions
  "Source revisions before the current one, oldest first."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:map-of :string [:vector :map]]]}
  [context listings]
  (let [ids  (for [{:keys [external-product-id revision]} listings, n (range 1 (or revision 1))]
               (str external-product-id "#" n))
        rows (vals (sync/load-rows context ::sync/source-revisions ids))]
    (update-vals (group-by :entity-id rows) #(vec (sort-by :revision %)))))


(defmethod runtime/coeffect ::earlier
  [_ context {:keys [listings]}]
  (earlier-revisions context listings))


(defmethod runtime/coeffect ::mapping-revisions
  [_ context {:keys [external-product-id mapping-revision]}]
  (if (and external-product-id mapping-revision)
    (->> (sync/load-rows context ::product-mapping-revisions (map #(revision-id external-product-id %) (range 1 (inc mapping-revision))))
         vals (sort-by :mapping-revision) vec)
    []))


(defmulti waiting-operations
  "Imported operations naming each listing of a connection: {external-product-id n}. Chapter 45 adds the
  `:imported-operations` method; until then nothing waits."
  (fn [_context _connection-id] :imported-operations))


(defmethod waiting-operations :default [_ _] {})


(defmulti listing-operations
  "Pending and accepted operations that used a listing's mapping, for Mapping Details. Chapter 45 adds the
  `:imported-operations` method; until then there are none."
  (fn [_context _external-product-id] :imported-operations))


(defmethod listing-operations :default [_ _] [])


(defmethod runtime/coeffect ::waiting
  [_ context {:keys [connection-id]}]
  (waiting-operations context connection-id))


(defmethod runtime/coeffect ::operations
  [_ context {:keys [external-product-id]}]
  (listing-operations context external-product-id))


;; Reads


(def ListingState
  [:enum "unmatched" "deferred" "confirmed" "suspended"])


(defn listing-state
  "A listing's state for the matching reads: its mapping's, unless removed or absent; then deferred or unmatched."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] ListingState]}
  [mapping deferral]
  (cond (#{"confirmed" "suspended"} (:state mapping)) (:state mapping)
        deferral "deferred"
        :else "unmatched"))


(defn- fold
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [text]
  (str/lower-case (str/trim (or text ""))))


(defn- earlier-names
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :string]]}
  [listing revisions]
  (->> revisions
       (keep #(get-in (observations/read-json (:facts %)) [:base :name]))
       (remove #{(:name listing)})
       distinct
       vec))


(def Evidence
  [:map
   [:name {:optional true} :string]
   [:earlier-names [:vector :string]]
   [:category {:optional true} :string]
   [:archived? :boolean]
   [:identifiers [:vector [:map [:type :string] [:value :string] [:active? :boolean]]]]
   [:attributes [:vector :map]]])


(defn evidence
  "What the listing states, current and earlier: identifiers keep their type and whether still active."
  {:malli/schema [:=> [:cat :map [:sequential :map]] Evidence]}
  [listing revisions]
  (cond-> {:earlier-names (earlier-names listing revisions)
           :archived?     (boolean (:archived listing))
           :identifiers   (->> (:identifiers listing)
                               (mapv #(cond-> {:type (:type %) :value (:value %) :active? (not (false? (:active %)))}
                                        (:role %) (assoc :role (:role %))))
                               (sort-by (juxt (comp not :active?) :type :value))
                               vec)
           :attributes    (:attributes listing)}
    (:name listing) (assoc :name (:name listing))
    (:category listing) (assoc :category (:category listing))))


(def Proposed
  "What registering a product for the listing starts with (slice 4); every value is editable in the table."
  [:map
   [:product-name {:optional true} :string]
   [:article {:optional true} :string]
   [:barcodes [:vector :string]]
   [:unit :string]])


(defn- listing-barcodes
  "The listing's active barcodes."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set :string]]}
  [listing]
  (into #{} (keep #(when (and (= "barcode" (:type %)) (not (false? (:active %)))) (candidates/usable-barcode (:value %))))
        (:identifiers listing)))


(defn proposed
  "The listing's name, first active article and active barcodes; the unit starts as `pcs`."
  {:malli/schema [:=> [:cat :map] Proposed]}
  [listing]
  (let [article (some #(when (and (= "article" (:type %)) (not (false? (:active %))) (not (str/blank? (:value %))))
                         (str/trim (:value %)))
                      (:identifiers listing))]
    (cond-> {:barcodes (vec (sort (listing-barcodes listing))) :unit candidates/default-unit}
      (not (str/blank? (:name listing))) (assoc :product-name (str/trim (:name listing)))
      article (assoc :article article))))


(def UnmatchedRow
  [:map
   [:external-product-id :string]
   [:listing-revision :int]
   [:state [:enum "unmatched" "deferred"]]
   [:evidence Evidence]
   [:candidates [:vector :map]]
   [:conflicts [:vector [:map [:barcode :string] [:product-ids [:vector :string]]]]]
   [:waiting-operations :int]
   [:proposed Proposed]
   [:deferral {:optional true} [:map [:reason {:optional true} :string] [:deferred-by :string] [:deferred-at :any]]]])


(defn- by-waiting
  "Most waiting operations first, then by name and id."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [rows]
  (vec (sort-by (juxt (comp - :waiting-operations) (comp fold :name :evidence) :external-product-id) rows)))


(defn unmatched-rows
  "Listings without a confirmed or suspended mapping in `state` (`unmatched` unless asked for `deferred`), with
  their candidates. Archived listings are listed: their operations may still wait."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:vector UnmatchedRow]]}
  [{:keys [listings mappings deferrals catalogue earlier waiting]} state]
  (let [state (or state "unmatched")
        index (candidates/catalogue-index catalogue)]
    (by-waiting
      (for [{:keys [external-product-id] :as listing} listings
            :let [deferral (get deferrals external-product-id)]
            :when (= state (listing-state (get mappings external-product-id) deferral))]
        (cond-> (merge {:external-product-id external-product-id
                        :listing-revision    (:revision listing)
                        :state               state
                        :evidence            (evidence listing (get earlier external-product-id []))
                        :waiting-operations  (get waiting external-product-id 0)
                        :proposed            (proposed listing)}
                       (candidates/indexed-candidates index listing))
          (= "deferred" state) (assoc :deferral (-> (select-keys deferral [:reason :deferred-by])
                                                    (assoc :deferred-at (connections/at (:deferred-at deferral))))))))))


(defn- by-connection
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:connection-id]))


(def ^:private matching-cofx
  [[:listings ::listings by-connection]
   [:mappings ::mappings by-connection]
   [:deferrals ::deferrals (constantly {})]
   [:waiting ::waiting by-connection]])


(def ^:private unmatched-cofx
  (into matching-cofx
        [[:catalogue ::catalogue by-connection]
         [:earlier ::earlier (fn [facts _] {:listings (:listings facts)})]]))


(defreadmodel unmatched-external-products-and-candidates
  {:input  [:map [:connection-id Ref] [:state {:optional true} [:enum "unmatched" "deferred"]]]
   :output [:map [:rows [:vector UnmatchedRow]]]
   :policy ::records/workspace-member
   :cofx   unmatched-cofx}
  [facts {:keys [state]}]
  {:rows (unmatched-rows facts state)})


(def MatchingProgress
  [:map
   [:mapped :int] [:unmatched :int] [:deferred :int] [:suspended :int]
   [:without-candidates [:vector [:map [:external-product-id :string] [:name {:optional true} :string]]]]])


(defn- state-counts
  {:malli/schema [:=> [:cat :map] [:map-of ListingState :int]]}
  [{:keys [listings mappings deferrals]}]
  (merge {"confirmed" 0 "unmatched" 0 "deferred" 0 "suspended" 0}
         (frequencies (map #(listing-state (get mappings (:external-product-id %)) (get deferrals (:external-product-id %)))
                           listings))))


(defreadmodel matching-progress
  {:input  [:map [:connection-id Ref]]
   :output MatchingProgress
   :policy ::records/workspace-member
   :cofx   unmatched-cofx}
  [facts _]
  (let [counts (state-counts facts)]
    {:mapped             (counts "confirmed")
     :unmatched          (counts "unmatched")
     :deferred           (counts "deferred")
     :suspended          (counts "suspended")
     :without-candidates (->> (unmatched-rows facts "unmatched")
                              (filter (comp empty? :candidates))
                              (mapv #(cond-> {:external-product-id (:external-product-id %)}
                                       (get-in % [:evidence :name]) (assoc :name (get-in % [:evidence :name])))))}))


(defmethod connections/count-part :unmatched-listings
  ;; Board chapter 40: external products without a confirmed mapping, so deferred and suspended ones count.
  [_ context connection-id]
  (let [mappings (runtime/coeffect ::mappings context {:connection-id connection-id})]
    (count (remove #(= "confirmed" (:state (get mappings (:external-product-id %))))
                   (runtime/coeffect ::listings context {:connection-id connection-id})))))


(def ListingRow
  [:map
   [:external-product-id :string] [:name {:optional true} :string] [:listing-revision :int] [:archived? :boolean]
   [:state ListingState] [:waiting-operations :int]
   [:stock-item {:optional true} [:map [:stock-item-id :string] [:product-id {:optional true} :string]
                                  [:product-name {:optional true} :string] [:variant-label {:optional true} :string]
                                  [:archived? :boolean]]]
   [:mapping-revision {:optional true} :int]
   [:reason {:optional true} :string]])


(def ^:private state-order
  {"suspended" 0 "unmatched" 1 "deferred" 2 "confirmed" 3})


(defn- stock-item-label
  {:malli/schema [:=> [:cat :map :map :string] :map]}
  [items products stock-item-id]
  (let [item    (get items stock-item-id)
        product (get products (:product-id item))]
    (cond-> {:stock-item-id stock-item-id
             :archived?     (boolean (or (nil? item) (= "archived" (:status item))
                                         (contains? #{"archived" "deleted"} (:status product))))}
      (:product-id item) (assoc :product-id (:product-id item))
      (:name product) (assoc :product-name (:name product))
      (:variant-label item) (assoc :variant-label (:variant-label item)))))


(defn listing-rows
  "Every listing of the connection with its state, the stock item it maps to and why it waits."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:vector ListingRow]]}
  [{:keys [listings mappings deferrals waiting catalogue]} state]
  (let [items    (by-id (:stock-items catalogue) :stock-item-id)
        products (by-id (:products catalogue) :product-id)]
    (->> (for [{:keys [external-product-id] :as listing} listings
               :let [mapping (get mappings external-product-id)
                     deferral (get deferrals external-product-id)
                     current (listing-state mapping deferral)]
               :when (or (nil? state) (= state current))]
           (cond-> {:external-product-id external-product-id
                    :listing-revision    (:revision listing)
                    :archived?           (boolean (:archived listing))
                    :state               current
                    :waiting-operations  (get waiting external-product-id 0)}
             (:name listing) (assoc :name (:name listing))
             (and (#{"confirmed" "suspended"} current) (:stock-item-id mapping))
             (assoc :stock-item (stock-item-label items products (:stock-item-id mapping))
                    :mapping-revision (:mapping-revision mapping))
             (and (= "deferred" current) (:reason deferral)) (assoc :reason (:reason deferral))))
         (sort-by (juxt (comp state-order :state) (comp - :waiting-operations) (comp fold :name) :external-product-id))
         vec)))


(defreadmodel mapped-and-remaining-listings
  {:input  [:map [:connection-id Ref] [:state {:optional true} ListingState]]
   :output [:map [:rows [:vector ListingRow]] [:counts [:map-of ListingState :int]]]
   :policy ::records/workspace-member
   :cofx   (conj matching-cofx [:catalogue ::catalogue by-connection])}
  [facts {:keys [state]}]
  {:rows (listing-rows facts state) :counts (state-counts facts)})


(def MappingDetails
  [:map
   [:listing [:map [:external-product-id :string] [:connection-id :string] [:listing-revision :int]
              [:evidence Evidence]
              [:revisions [:vector [:map [:revision :int] [:observed-at :any] [:name {:optional true} :string]]]]]]
   [:state ListingState]
   [:mapping {:optional true} :map]
   [:history [:vector :map]]
   [:stock-item {:optional true} :map]
   [:operations [:vector :map]]
   [:other-listings [:vector [:map [:external-product-id :string] [:connection-id :string] [:name {:optional true} :string]
                              [:state ListingState]]]]])


(defn- revision-view
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (cond-> (-> (select-keys row [:mapping-revision :change :state :stock-item-id :previous-stock-item-id
                                :listing-revision :item-revision :changed-by])
              (assoc :changed-at (connections/at (:changed-at row))))
    (:identity-basis row) (assoc :identity-basis (observations/read-json (:identity-basis row)))
    (:reasons row) (assoc :reasons (vec (observations/read-json (:reasons row))))))


(defn- sharing-item
  "Other listings mapped (confirmed or suspended) to the stock item this listing maps to."
  {:malli/schema [:=> [:cat [:map-of :string :map] :string] [:vector :string]]}
  [mappings external-product-id]
  (let [mapping (get mappings external-product-id)
        item    (when (#{"confirmed" "suspended"} (:state mapping)) (:stock-item-id mapping))]
    (vec (sort (for [[id m] mappings
                     :when  (and item (not= id external-product-id) (= item (:stock-item-id m))
                                 (#{"confirmed" "suspended"} (:state m)))]
                 id)))))


(defn details
  "Mapping Details and Source Evidence of one listing."
  {:malli/schema [:=> [:cat :map] MappingDetails]}
  [{:keys [listing mapping deferral history revisions operations mappings others catalogue]}]
  (let [{:keys [external-product-id]} listing
        current (listing-state mapping deferral)
        mapped? (#{"confirmed" "suspended"} current)
        ;; The latest member decision: a change to another item confirms it too.
        confirmed (first (filter #(= "confirmed" (:state %)) (reverse history)))
        items (by-id (:stock-items catalogue) :stock-item-id)
        products (by-id (:products catalogue) :product-id)
        item-id (:stock-item-id mapping)]
    (cond-> {:listing        {:external-product-id external-product-id
                              :connection-id       (:connection-id listing)
                              :listing-revision    (:revision listing)
                              :evidence            (evidence listing (vec (butlast revisions)))
                              :revisions           (mapv (fn [r] (let [name (get-in (observations/read-json (:facts r)) [:base :name])]
                                                                   (cond-> {:revision (:revision r) :observed-at (connections/at (:observed-at r))}
                                                                     name (assoc :name name))))
                                                         revisions)}
             :state          current
             :history        (mapv revision-view history)
             :operations     (vec operations)
             :other-listings (->> (sharing-item mappings external-product-id)
                                  (mapv (fn [id]
                                          (let [m    (get mappings id)
                                                name (get-in others [id :name])]
                                            (cond-> {:external-product-id id :connection-id (:connection-id m) :state (:state m)}
                                              name (assoc :name name))))))}
      mapped? (assoc :mapping (cond-> (revision-view mapping)
                                confirmed (assoc :confirmed-by (:changed-by confirmed)
                                                 :confirmed-at (connections/at (:changed-at confirmed))))
                     :stock-item (stock-item-label items products item-id))
      (= "deferred" current) (assoc :mapping (cond-> {:deferred-by (:deferred-by deferral)
                                                      :deferred-at (connections/at (:deferred-at deferral))}
                                               (:reason deferral) (assoc :reason (:reason deferral)))))))


(defn- by-listing
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:external-product-id]))


(defmethod runtime/coeffect ::source-revisions
  [_ context {:keys [external-product-id revision]}]
  (if (and external-product-id revision)
    (->> (sync/load-rows context ::sync/source-revisions (map #(str external-product-id "#" %) (range 1 (inc revision))))
         vals (sort-by :revision) vec)
    []))


(defmethod runtime/coeffect ::deferral
  [_ context {:keys [external-product-id]}]
  (get (sync/load-rows context ::product-deferrals [external-product-id]) external-product-id))


(defmethod runtime/coeffect ::listings-by-id
  [_ context {:keys [ids]}]
  (sync/load-rows context ::sync/external-products (vec ids)))


(defreadmodel mapping-details-and-source-evidence
  {:input  [:map [:external-product-id ListingId]]
   :output MappingDetails
   :policy ::records/workspace-member
   :cofx   [[:listing ::listing by-listing]
            [:mappings ::mappings (constantly {})]
            [:deferral ::deferral by-listing]
            [:history ::mapping-revisions (fn [facts input] (assoc (by-listing facts input) :mapping-revision (get-in facts [:mappings (:external-product-id input) :mapping-revision])))]
            [:revisions ::source-revisions (fn [facts input] (assoc (by-listing facts input) :revision (get-in facts [:listing :revision])))]
            [:catalogue ::catalogue (constantly {})]
            [:operations ::operations by-listing]
            [:others ::listings-by-id (fn [facts input] {:ids (sharing-item (:mappings facts) (:external-product-id input))})]]}
  [{:keys [listing mappings] :as facts} {:keys [external-product-id]}]
  (when-not listing
    (records/fail! :not-found "Unknown listing" {}))
  (details (assoc facts :mapping (get mappings external-product-id))))


;; Commands (slices 2, 6, 8 and c). Every row is checked and every problem returned; one bad row accepts nothing.


(def ^:private messages
  {:stale                  "The listing, its mapping or the stock item changed since review. Review it again."
   :barcode-conflict       "A barcode of the listing belongs to another product."
   :item-not-available     "Choose an active, regular stock item of this workspace."
   :already-mapped         "The listing is already mapped. Change its mapping instead."
   :listed-twice           "The listing is in the batch twice with different stock items."
   :not-suspended          "The mapping is not suspended; there is nothing to reconfirm."
   :item-archived          "The stock item is archived or defective. Change the mapping instead."
   :barcode-not-on-listing "Only an active barcode of the listing can be adopted."
   :item-exists            "The listing already registered a stock item. Confirm a mapping to it instead."
   :product-not-available  "Choose an active product of this workspace."
   :invalid-product-name   "Enter a product name of 1 to 500 characters."
   :product-name-taken     "A product with this name already exists."
   :unknown-unit           "Choose a unit from the list."
   :duplicate-variant      "This product already has a stock item with this variant."
   :uniqueness-conflict    "A barcode or article belongs to another product."})


(def ^:private rejection-type
  "Row codes that reject with another type than their own."
  {:listed-twice         :already-mapped :barcode-not-on-listing :validation :item-exists        :already-mapped
   :invalid-product-name :validation     :unknown-unit           :validation :product-name-taken :collision
   :duplicate-variant    :collision      :uniqueness-conflict    :collision})


(defn- problem
  {:malli/schema [:=> [:cat :string :keyword [:* :any]] :map]}
  [external-product-id code & {:as extra}]
  (merge {:external-product-id external-product-id :code code :message (messages code)} extra))


(defn- reject!
  "Rejects with the first problem's type, naming every problem row."
  {:malli/schema [:=> [:cat [:sequential :map]] :nil]}
  [problems]
  (when-let [{:keys [code message]} (first problems)]
    (records/fail! (rejection-type code code) message {:rows (vec problems)})))


(defn- catalogue-index
  "Items and products by id, and barcode owners as the unmatched read counts them (archived items included)."
  {:malli/schema [:=> [:cat :map] :map]}
  [catalogue]
  {:items    (by-id (:stock-items catalogue) :stock-item-id)
   :products (by-id (:products catalogue) :product-id)
   :owners   (candidates/barcode-products (:barcodes catalogue) (:stock-items catalogue))
   :held     (set (map (juxt :stock-item-id :barcode) (:barcodes catalogue)))})


(defn- item-code
  "Nil when the item is in this workspace, at the reviewed revision, active, regular and of a live product; else the
  problem code. Stale is checked before availability; an item of another workspace is not found here."
  {:malli/schema [:=> [:cat :map [:maybe :string] [:maybe :int] :keyword] [:maybe :keyword]]}
  [{:keys [items products]} stock-item-id item-revision unavailable]
  (let [item (get items stock-item-id)]
    (cond (nil? item) unavailable
          (not= item-revision (:revision item)) :stale
          (or (not= "active" (:status item)) (not= "regular" (:condition item "regular"))
              (contains? #{"archived" "deleted"} (:status (get products (:product-id item))))) unavailable)))


(defn- conflicts
  "One problem per listing barcode held by a product other than `product-id`, naming that product."
  {:malli/schema [:=> [:cat :map :string [:set :string] [:maybe :string]] [:vector :map]]}
  [{:keys [owners products]} external-product-id barcodes product-id]
  (vec (for [barcode (sort barcodes)
             owner   (sort (disj (get owners barcode #{}) product-id))
             :let    [name (:name (get products owner) owner)]]
         (problem external-product-id :barcode-conflict :barcode barcode :owner-product name
                  :message (str "Barcode " barcode " belongs to " name ".")))))


(defn- basis
  {:malli/schema [:=> [:cat :map :map :map] :string]}
  [catalogue listing item]
  (observations/write-json
    (candidates/identity-basis listing (filterv #(= (:connection-id listing) (:connection-id %)) (:definitions catalogue)) item)))


(defn- decided
  "What every member decision writes: the listing, the new mapping revision, who and when."
  {:malli/schema [:=> [:cat :map :map :string :string :int] :map]}
  [{:keys [actor now]} listing connection-id change mapping-revision]
  {:external-product-id (:external-product-id listing)
   :mapping-revision-id (revision-id (:external-product-id listing) mapping-revision)
   :connection-id       connection-id
   :change              change
   :listing-revision    (:revision listing)
   :mapping-revision    mapping-revision
   :changed-by          (str actor)
   :changed-at          (str now)})


(defn- target
  "The confirmed item and the values the Mapping Guard compares with."
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [catalogue listing item]
  {:stock-item-id  (:stock-item-id item)
   :item-revision  (:revision item)
   :identity-basis (basis catalogue listing item)
   :state          "confirmed"})


(defn- accepted-fx
  "Publish, and evaluate again the waiting operations of each connection touched."
  {:malli/schema [:=> [:cat :map [:sequential [:maybe :string]]] [:vector :any]]}
  [scope connection-ids]
  (into (records/publish scope) (for [c (sort (set (remove nil? connection-ids)))] [::sync/mark-dirty {:connection-id c}])))


(defn- mapped?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [mapping]
  (contains? #{"confirmed" "suspended"} (:state mapping)))


(defmethod runtime/coeffect ::reviewed-listings
  [_ context {:keys [ids]}]
  (update-vals (sync/load-rows context ::sync/external-products (vec ids)) listing-row))


(defmethod runtime/coeffect ::mappings-by-id
  [_ context {:keys [ids]}]
  (sync/load-rows context ::product-mappings (vec ids)))


(defn- row-ids
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ {:keys [rows external-product-ids external-product-id]}]
  {:ids (vec (or external-product-ids (some->> rows (map :external-product-id)) [external-product-id]))})


(def ^:private command-cofx
  [[:listings ::reviewed-listings row-ids]
   [:mappings ::mappings-by-id row-ids]
   [:catalogue ::catalogue (constantly {})]])


(def ConfirmRow
  [:map
   [:external-product-id ListingId] [:listing-revision Revision]
   [:stock-item-id Ref] [:item-revision Revision]
   [:adopt-barcodes {:optional true} [:vector [:string {:max 200}]]]])


(def ConfirmProductMapping
  [:map [:rows [:vector {:min 1 :max 5000} ConfirmRow]]])


(defn- with-adoptions
  "Barcode owners once every row of the batch has adopted its barcodes, whatever the row order."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :map]}
  [index rows]
  (reduce (fn [owners {:keys [stock-item-id adopt-barcodes]}]
            (if-let [product (get-in index [:items stock-item-id :product-id])]
              (reduce #(update %1 %2 (fnil conj #{}) product) owners (keep candidates/usable-barcode adopt-barcodes))
              owners))
          (:owners index)
          rows))


(defn- confirm-row
  "Adds the row's problems, or the accepted row, to `acc`. A listing already confirmed to the same item is skipped;
  one confirmed to another item is stale: another member decided since review."
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [{:keys [listings mappings index twice]} acc
   {:keys [external-product-id listing-revision stock-item-id item-revision adopt-barcodes]}]
  (let [listing  (get listings external-product-id)
        mapping  (get mappings external-product-id)
        item     (get-in index [:items stock-item-id])
        barcodes (listing-barcodes listing)
        adopt    (set (keep candidates/usable-barcode adopt-barcodes))
        code     (cond (twice external-product-id) :listed-twice
                       (or (nil? listing) (not= listing-revision (:revision listing))) :stale
                       (and (= "confirmed" (:state mapping)) (= stock-item-id (:stock-item-id mapping))) :same
                       (= "confirmed" (:state mapping)) :stale
                       (mapped? mapping) :already-mapped
                       :else (item-code index stock-item-id item-revision :item-not-available))
        problems (cond (= :same code) []
                       code [(problem external-product-id code)]
                       :else (into (vec (for [b (sort adopt) :when (not (barcodes b))]
                                          (problem external-product-id :barcode-not-on-listing :barcode b)))
                                   (conflicts index external-product-id barcodes (:product-id item))))]
    (cond (= :same code) acc
          (seq problems) (update acc :problems into problems)
          :else (update acc :accepted conj {:listing listing :mapping mapping :item item :adopt adopt}))))


(defcommand confirm-product-mapping
  {:input ConfirmProductMapping :policy ::records/workspace-member
   :cofx  command-cofx
   :emits #{::external-product-mapped :tover.inventory.stock-items.model/stock-item-barcode-assigned}}
  [{:keys [scope listings mappings catalogue] :as facts} {:keys [rows]}]
  (let [rows  (distinct rows)
        twice (set (keep (fn [[id n]] (when (> n 1) id)) (frequencies (map :external-product-id rows))))
        index (catalogue-index catalogue)
        {:keys [problems accepted]}
        ;; Every adoption of the batch counts for every row: one row may not give a barcode to another product.
        (reduce (partial confirm-row {:listings listings :mappings mappings :twice twice
                                      :index    (assoc index :owners (with-adoptions index rows))})
                {:problems [] :accepted []}
                rows)]
    (reject! problems)
    (if (empty? accepted)
      {}
      {:events (vec (concat
                      (for [{:keys [listing mapping item]} accepted]
                        {:type ::external-product-mapped
                         :data (merge (decided facts listing (:connection-id listing) "mapped" (inc (:mapping-revision mapping 0)))
                                      (target catalogue listing item))})
                      ;; Stock Item Barcode Assigned, as acceptance emits it; a barcode the item has changes nothing.
                      (for [[item code] (distinct (for [{:keys [item adopt]} accepted, code (sort adopt)] [(:stock-item-id item) code]))
                            :when       (not ((:held index) [item code]))]
                        {:type :tover.inventory.stock-items.model/stock-item-barcode-assigned
                         :data {:barcode-id (stock-items/barcode-id item code) :stock-item-id item :barcode code}})))
       :fx     (accepted-fx scope (map (comp :connection-id :listing) accepted))})))


(def DeferProductMatch
  [:map [:external-product-ids [:vector {:min 1 :max 5000} ListingId]] [:reason {:optional true} [:string {:max 500}]]])


(defcommand defer-product-match
  {:input DeferProductMatch :policy ::records/workspace-member :cofx (pop command-cofx) :emits #{::product-match-deferred}}
  [{:keys [actor now scope listings mappings]} {:keys [external-product-ids reason]}]
  (let [ids    (distinct external-product-ids)
        reason (some-> reason str/trim not-empty)]
    (reject! (for [id    ids
                   :let  [code (cond (nil? (get listings id)) :stale (mapped? (get mappings id)) :already-mapped)]
                   :when code]
               (problem id code)))
    {:events (mapv (fn [id] {:type ::product-match-deferred
                             :data (cond-> {:external-product-id id :deferred-by (str actor) :deferred-at (str now)}
                                     reason (assoc :reason reason))})
                   ids)
     :fx     (accepted-fx scope (map #(:connection-id (get listings %)) ids))}))


(def ChangeProductMapping
  "A blank stock item removes the mapping."
  [:map [:external-product-id ListingId] [:mapping-revision Revision]
   [:stock-item-id {:optional true} Ref] [:item-revision {:optional true} Revision]])


(defcommand change-product-mapping
  {:input ChangeProductMapping :policy ::records/workspace-member :cofx command-cofx :emits #{::product-mapping-changed}}
  [{:keys [scope listings mappings catalogue] :as facts} {:keys [external-product-id mapping-revision stock-item-id item-revision]}]
  (let [listing (get listings external-product-id)
        mapping (get mappings external-product-id)
        index   (catalogue-index catalogue)
        item    (get-in index [:items stock-item-id])
        same?   (if stock-item-id
                  (and (= "confirmed" (:state mapping)) (= stock-item-id (:stock-item-id mapping)))
                  (not (mapped? mapping)))]
    (reject! (cond (or (nil? listing) (nil? mapping) (not= mapping-revision (:mapping-revision mapping)))
                   [(problem external-product-id :stale)]
                   same? []
                   stock-item-id (if-let [code (item-code index stock-item-id item-revision :item-not-available)]
                                   [(problem external-product-id code)]
                                   (conflicts index external-product-id (listing-barcodes listing) (:product-id item)))
                   :else []))
    (if same?
      {}
      {:events [{:type ::product-mapping-changed
                 :data (cond-> (decided facts listing (:connection-id mapping) "changed" (inc mapping-revision))
                         (:stock-item-id mapping) (assoc :previous-stock-item-id (:stock-item-id mapping))
                         item (merge (target catalogue listing item))
                         (nil? item) (assoc :state "removed"))}]
       :fx     (accepted-fx scope [(:connection-id mapping)])})))


(def ReconfirmProductMapping
  [:map [:external-product-id ListingId] [:mapping-revision Revision] [:listing-revision Revision] [:item-revision Revision]])


(defcommand reconfirm-product-mapping
  {:input ReconfirmProductMapping :policy ::records/workspace-member :cofx command-cofx :emits #{::product-mapping-reconfirmed}}
  [{:keys [scope listings mappings catalogue] :as facts} {:keys [external-product-id mapping-revision listing-revision item-revision]}]
  (let [listing (get listings external-product-id)
        mapping (get mappings external-product-id)
        index   (catalogue-index catalogue)
        item    (get-in index [:items (:stock-item-id mapping)])
        code    (cond (nil? mapping) :not-suspended
                      (not= mapping-revision (:mapping-revision mapping)) :stale
                      (not= "suspended" (:state mapping)) :not-suspended
                      (or (nil? listing) (not= listing-revision (:revision listing))) :stale
                      :else (item-code index (:stock-item-id mapping) item-revision :item-archived))]
    (reject! (if code
               [(problem external-product-id code)]
               (conflicts index external-product-id (listing-barcodes listing) (:product-id item))))
    {:events [{:type ::product-mapping-reconfirmed
               :data (merge (decided facts listing (:connection-id mapping) "reconfirmed" (inc mapping-revision))
                            (target catalogue listing item))}]
     :fx     (accepted-fx scope [(:connection-id mapping)])}))


;; Slice 4 · Register a product or stock item for the listing


(defn item-id
  "The stock item a listing registers. Derived from the listing, so one listing never registers two items."
  {:malli/schema [:=> [:cat :string] :string]}
  [external-product-id]
  (str "listing:" external-product-id))


(defn new-product-id
  "A new product of the batch takes its id from the first row (by order) naming it."
  {:malli/schema [:=> [:cat :string] :string]}
  [external-product-id]
  (str "listing-product:" external-product-id))


(def RegisterRow
  "The product is an existing `:product-id` at `:product-revision`, or a new `:product-name`. Rows naming the same
  new name (trimmed, case-insensitive) form one product."
  [:map
   [:external-product-id ListingId] [:listing-revision Revision]
   [:product-id {:optional true} Ref] [:product-revision {:optional true} Revision]
   [:product-name {:optional true} [:string {:max 1000}]]
   [:variant-label {:optional true} [:string {:max 500}]]
   [:article {:optional true} [:string {:max 500}]]
   [:barcodes {:optional true} [:vector [:string {:max 200}]]]
   [:unit [:string {:max 50}]]])


(def RegisterProductForExternalListing
  [:map [:rows [:vector {:min 1 :max 5000} RegisterRow]]])


(defn- value
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [text]
  (some-> text str/trim not-empty))


(defn- register-code
  "The row's first problem before uniqueness, or nil."
  {:malli/schema [:=> [:cat :map :map] [:maybe :keyword]]}
  [{:keys [listings mappings index taken twice]}
   {:keys [external-product-id listing-revision product-id product-revision product-name unit]}]
  (let [listing (get listings external-product-id)
        product (get-in index [:products product-id])
        name    (value product-name)]
    (or (cond (twice external-product-id) :listed-twice
              (or (nil? listing) (not= listing-revision (:revision listing))) :stale
              (mapped? (get mappings external-product-id)) :already-mapped
              (get-in index [:items (item-id external-product-id)]) :item-exists)
        (if product-id
          (cond (nil? product) :product-not-available
                (not= product-revision (:revision product)) :stale
                (not= "active" (:status product)) :product-not-available)
          (cond (not (and name (<= (count name) 500))) :invalid-product-name
                (taken (changes/text-key name)) :product-name-taken))
        (when-not (stock-items/unit? (value unit)) :unknown-unit))))


(defn- register-lines
  "Rows as acceptance lines of new items: `:entry-id` is the listing, the item id derives from it and a new name
  takes the id of the first row naming it."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [listings rows]
  (let [first-row (reduce (fn [m {:keys [external-product-id product-id product-name]}]
                            (let [k (when-not product-id (changes/text-key product-name))]
                              (if (and k (not (contains? m k))) (assoc m k external-product-id) m)))
                          {} rows)]
    (mapv (fn [{:keys [external-product-id product-id product-name variant-label article barcodes unit]}]
            (let [new-key (changes/text-key product-name)]
              (cond-> {:entry-id      external-product-id :line-id   nil             :new-item? true
                       :stock-item-id (item-id external-product-id)
                       :product-id    (or product-id (when new-key (new-product-id (first-row new-key))))
                       :variant-label (value variant-label) :article (value article) :unit      (value unit)
                       :barcodes      (vec (distinct (keep candidates/usable-barcode barcodes)))
                       :listing       (get listings external-product-id)}
                (not product-id) (assoc :new-product? true :product-name (value product-name)))))
          rows)))


(defn- collisions
  "Uniqueness as acceptance checks it (`changes/uniqueness`), plus what two new items of the batch may break: a
  variant twice under one product, an article under two products. An in-batch barcode problem names the other
  product of the batch instead of the barcode."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [catalogue lines]
  (let [names   (merge (into {} (map (juxt :product-id :name)) (:products catalogue))
                       (into {} (keep #(when (:new-product? %) [(:product-id %) (:product-name %)])) lines))
        by-line (by-id lines :entry-id)
        claims  (reduce (fn [m l] (reduce #(update %1 %2 (fnil conj #{}) (:product-id l)) m (:barcodes l))) {} lines)
        other   (fn [line owners] (names (first (sort (disj owners (:product-id line))))))
        found   (changes/uniqueness (assoc (select-keys catalogue [:products :stock-items :barcodes]) :suppliers []) lines)]
    (vec (distinct
           (concat
             (for [{:keys [entry-id code owner-product]} found
                   :let [line (by-line entry-id)
                         batch (when (some #{owner-product} (:barcodes line)) (claims owner-product))]]
               (if (< 1 (count batch))
                 (problem entry-id code :barcode owner-product :owner-product (other line batch))
                 (cond-> (problem entry-id code) owner-product (assoc :owner-product owner-product))))
             (for [[_ ls] (group-by (juxt :product-id (comp changes/text-key :variant-label)) lines)
                   :when  (< 1 (count ls))
                   line   ls]
               (problem (:entry-id line) :duplicate-variant))
             (for [[article ls] (group-by (comp changes/text-key :article) lines)
                   :let         [owners (set (map :product-id ls))]
                   :when        (and article (< 1 (count owners)))
                   line         ls]
               (problem (:entry-id line) :uniqueness-conflict :article (:article line) :owner-product (other line owners))))))))


(defn- registration-events
  "Product Registered once per new product, Stock Item Registered and its barcodes once per row, then the mapping."
  {:malli/schema [:=> [:cat :map :map [:sequential :map]] [:vector :map]]}
  [facts catalogue lines]
  (let [present #(into {} (remove (comp nil? val)) %)]
    (vec (concat
           (for [l (vals (into (sorted-map) (map (juxt :product-id identity)) (reverse (filter :new-product? lines))))]
             {:type :tover.catalogues.products.model/product-registered
              :data {:product-id (:product-id l) :name (:product-name l) :status "active" :revision 1}})
           (for [l lines]
             {:type ::stock-items/stock-item-registered
              :data (present {:stock-item-id (:stock-item-id l) :product-id (:product-id l) :variant-label (:variant-label l)
                              :article       (:article l)       :unit       (:unit l)       :condition     "regular"          :status "active"
                              :revision      1})})
           (for [l lines code (:barcodes l)]
             {:type ::stock-items/stock-item-barcode-assigned
              :data {:barcode-id (stock-items/barcode-id (:stock-item-id l) code) :stock-item-id (:stock-item-id l) :barcode code}})
           (for [{:keys [listing] :as l} lines
                 :let                    [item (-> (select-keys l [:stock-item-id :variant-label :unit]) (assoc :revision 1))]]
             {:type ::external-product-mapped
              :data (merge (decided facts listing (:connection-id listing) "mapped"
                                    (inc (:mapping-revision (get (:mappings facts) (:entry-id l)) 0)))
                           (target catalogue listing item))})))))


(defcommand register-product-for-external-listing
  {:input RegisterProductForExternalListing :policy ::records/workspace-member
   :cofx  command-cofx
   :emits #{:tover.catalogues.products.model/product-registered ::stock-items/stock-item-registered
            ::stock-items/stock-item-barcode-assigned ::external-product-mapped}}
  [{:keys [scope listings mappings catalogue] :as facts} {:keys [rows]}]
  (let [rows    (distinct rows)
        twice   (set (keep (fn [[id n]] (when (> n 1) id)) (frequencies (map :external-product-id rows))))
        taken   (into #{} (keep #(when (not= "deleted" (:status %)) (changes/text-key (:name %)))) (:products catalogue))
        checked {:listings listings :mappings mappings :index (catalogue-index catalogue) :taken taken :twice twice}
        lines   (register-lines listings rows)]
    (reject! (for [[row line] (map vector rows lines)
                   p          (if-let [code (register-code checked row)]
                                [(problem (:external-product-id row) code)]
                                (let [on-listing (listing-barcodes (:listing line))]
                                  (for [b (:barcodes line) :when (not (on-listing b))]
                                    (problem (:external-product-id row) :barcode-not-on-listing :barcode b))))]
               p))
    ;; Uniqueness once every row stands: all rows are accepted together or none.
    (reject! (collisions catalogue lines))
    {:events (registration-events facts catalogue lines)
     :fx     (accepted-fx scope (map (comp :connection-id :listing) lines))}))

(ns tover.marketplace.products.model
  "Board chapter 42 · Match marketplace products to stock items: product mappings, deferrals and the matching
  reads (slices 1, 3, 5, 7). The commands that emit these events come with slices 2, 4, 6, 8 and c.

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
    [bel.core :refer [defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
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
            ::product-mapping-changed     {:set (vec (remove #{:connection-id :listing-revision} mapping-fields))}
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


(def UnmatchedRow
  [:map
   [:external-product-id :string]
   [:listing-revision :int]
   [:state [:enum "unmatched" "deferred"]]
   [:evidence Evidence]
   [:candidates [:vector :map]]
   [:conflicts [:vector [:map [:barcode :string] [:product-ids [:vector :string]]]]]
   [:waiting-operations :int]
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
                        :waiting-operations  (get waiting external-product-id 0)}
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
  [_ context connection-id]
  (let [facts {:listings  (runtime/coeffect ::listings context {:connection-id connection-id})
               :mappings  (runtime/coeffect ::mappings context {:connection-id connection-id})
               :deferrals (runtime/coeffect ::deferrals context {})}]
    ((state-counts facts) "unmatched")))


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

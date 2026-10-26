(ns tover.inventory.smart-web
  (:require
    [bel.core :refer [defaction]]
    [charred.api :as charred]
    [clojure.string :as str]
    [tover.inventory.completion :as completion]
    [tover.inventory.filters :as filters]
    [tover.inventory.item-selection :as item-selection]
    [tover.inventory.read-query :as read-query]
    [tover.inventory.smart :as smart]
    [tover.inventory.store :as store]
    [tover.inventory.workbench :as workbench]
    [tover.shared.i18n :as i18n]
    [tover.stock.web :as stock])
  (:import
    [clojure.lang ExceptionInfo]))


(def Input
  [:map
   [:state [:map
            [:mode {:optional true} [:enum "filter" "types" "add" "resumes" "unavailable"]]
            [:id {:optional true} [:maybe :string]]
            [:revision {:optional true} [:maybe :int]]
            [:field {:optional true} [:maybe :string]]
            [:replacing {:optional true} [:maybe :string]]
            [:showErrors {:optional true} :boolean]
            [:conflict {:optional true} [:maybe :map]]]]
   [:text [:string {:max 2000}]]
   [:filters {:optional true} :map]
   [:filterRequest {:optional true} :int]
   [:view {:optional true} [:enum "stock" "operations"]]
   [:sort {:optional true} :string]
   [:direction {:optional true} :string]
   [:request :int]
   [:option {:optional true} [:maybe :map]]])


(defn snapshot
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  (let [snapshot (store/snapshot (:repo context) context)]
    (assoc snapshot :items (workbench/item-catalog snapshot))))


(defn model-response
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [state text filters request failed? view errors closeMenu]}]
  (let [current  (workbench/draft-snapshot context)
        field    (when-not (str/blank? (:field state)) (keyword (last (str/split (:field state) #"/"))))
        current  (cond
                   (= :original-operation-id field)
                   (assoc-in current [:field-options (:field state)]
                             (mapv (fn [{:keys [value label]}]
                                     {:kind "value" :key (:field state) :value value :label label :description (i18n/t "Existing operation")})
                                   (store/read-snapshot (:repo context) context
                                                        (fn [conn _] (read-query/suggest-operations conn context (or text "") 30)))))
                   (= :name field)
                   (update current :items merge
                           (store/read-snapshot (:repo context) context
                                                (fn [conn _] (read-query/suggest-items conn context (or text "") 30))))
                   (#{:unit :location :article :barcode :party :currency :from-location :to-location} field)
                   (assoc current :rows
                          (mapv #(hash-map field %)
                                (store/read-snapshot (:repo context) context
                                                     (fn [conn _] (read-query/values conn context (name field) (or text "") 30)))))
                   :else current)
        base     (smart/model current (assoc (or state {}) :view (or view "stock")) (or text "") (or filters {}) (or request 0))
        conflict (:conflict state)
        model    (cond-> (assoc base :closeMenu (boolean closeMenu))
                   failed? (assoc :failed true)
                   errors (assoc :errors errors)
                   conflict (assoc :conflict conflict
                                   :options [{:kind "use-saved" :label (i18n/t "Use saved") :description (str (i18n/t "Saved value: ") (:saved conflict))}
                                             {:kind "apply-mine" :label (i18n/t "Apply mine") :description (str (i18n/t "Your value: ") (:value conflict))}]))]
    {:type :signals :data {:_smartModelJson (charred/write-json-str model)}}))


(defn current-composition
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [id revision]}]
  (let [current (smart/composition (workbench/draft-snapshot context) id)]
    (when-not current (throw (ex-info "This unfinished operation is no longer available." {:type :unavailable-composition})))
    (when-not (= revision (:revision current))
      (throw (ex-info "This entry changed. Choose which value to keep." {:type :stale-draft :current current})))
    current))


(defn checked-field
  {:malli/schema [:=> [:cat :map :map :string] :map]}
  [context state key]
  (let [draft  (when (= "add" (:mode state)) (current-composition context state))
        fields (if draft (smart/entry-fields (:entry draft)) (filters/columns (or (:view state) "stock")))
        key    (if (str/blank? key) "name" key)]
    (or (some #(when (= key (:key %)) %) fields)
        (throw (ex-info "Choose an available field." {:type :validation})))))


(defn save-composition!
  {:malli/schema [:=> [:cat :map [:maybe :map] :map] :map]}
  [context current entry]
  (store/save-composition! (:repo context) context
                           (cond-> {:entry entry :actor (str (:user-id context))}
                             current (assoc :id (:id current) :expected-revision (:revision current)))))


(defn with-value
  {:malli/schema [:=> [:cat :map :map :string :string :map] :map]}
  [context entry key value option]
  (let [path     (stock/path-parts key)
        snapshot (snapshot context)
        items    (:items snapshot)
        item-id  (when (= :name (last path))
                   (or (:itemId option)
                       (let [matches (filter #(= value (get-in % [1 :name])) items)]
                         (when (= 1 (count matches)) (ffirst matches)))))]
    (if item-id
      (item-selection/select-item snapshot entry (pop path) item-id)
      (let [entry        (assoc-in entry path (if (str/blank? value) "" (stock/field-value path value)))
            item-path    (conj (pop path) :item-id)
            current-item (get items (get-in entry item-path))
            entry        (if (and (= :name (last path))
                                  (or (str/blank? value) (and current-item (not= value (:name current-item)))))
                           (assoc-in entry item-path (str (random-uuid))) entry)]
        (if (and (str/blank? value) (#{:original-operation-id :amount :currency} (last path)))
          (if (seq (pop path)) (update-in entry (pop path) dissoc (last path)) (dissoc entry (last path))) entry)))))


(defn complete*
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [state text option view] :as input}]
  (let [state        (assoc (merge {:mode "filter"} state) :view (or view "stock"))
        filter-state (filters/check! (or view "stock") (or (:filters input) {}))
        option       (or option (if (and (= "filter" (:mode state)) (#{"add" "ass"} (str/lower-case (str/trim text))))
                                  {:kind "add"}
                                  {:kind "value" :key (or (:field state) "name") :value text}))
        kind         (:kind option)
        key          (:key option)
        clean        (fn [next-state] (assoc input :state next-state :text "" :filters filter-state))]
    (when (and (:conflict state) (not (#{"use-saved" "apply-mine" "cancel" "restart"} kind)))
      (throw (ex-info "Choose Use saved or Apply mine before continuing." {:type :conflict-resolution})))
    (case kind
      "add" (clean {:mode "types"})
      "restart" (clean {:mode "types"})
      "resumes" (clean {:mode "resumes"})
      "resume" (let [draft (smart/composition (snapshot context) (:id option))]
                 (when-not draft (throw (ex-info "This unfinished operation is no longer available." {:type :unavailable-composition})))
                 (clean (smart/resumed-state draft)))
      "clear-field" (clean (assoc state :field nil :conflict nil))
      "composition" (clean (assoc state :field nil))
      "cancel" (do (when (and (not (str/blank? (:id state))) (smart/composition (snapshot context) (:id state)))
                     (current-composition context state)
                     (store/discard-composition! (:repo context) context {:id (:id state) :expected-revision (:revision state)}))
                   (assoc (clean {:mode "filter"}) :private-saved? true :closeMenu true))
      "done" (let [draft (smart/composition (snapshot context) (:id state))
                   entry (when draft
                           (let [active? (and (:field state) (not (str/blank? text)))]
                             (if active?
                               (do (checked-field context state (:field state))
                                   (with-value context (:entry draft) (:field state) text {}))
                               (:entry draft))))
                   check (when entry (completion/check entry (set (keys (:items (snapshot context))))))]
               (when (and check (not (:valid? check)))
                 (throw (ex-info "Complete the highlighted fields before Done."
                                 {:type :incomplete-composition :errors (:errors check)})))
               (when (str/blank? (:commandId option))
                 (throw (ex-info "Retry Done with its completion identifier." {:type :validation})))
               (let [pending (store/complete-composition! (:repo context) context
                                                          (cond-> {:id    (:id state)              :expected-revision (:revision state)
                                                                   :actor (str (:user-id context)) :command-id        (:commandId option)}
                                                            entry (assoc :entry entry)))]
                 (assoc (clean {:mode "filter"}) :changed? true :completed pending :closeMenu true)))
      "type" (let [type (keyword (:type option))]
               (when-not (some #(= type (first %)) smart/operation-types)
                 (throw (ex-info "Choose an operation type." {:type :validation})))
               (when (= "add" (:mode state)) (throw (ex-info "Finish or cancel the current operation first." {:type :validation})))
               (let [draft (save-composition! context nil (stock/new-entry :operation type))]
                 (assoc (clean (smart/resumed-state draft)) :private-saved? true)))
      "field" (do (checked-field context state key) (clean (assoc state :field key)))
      "filter" (do (checked-field context state key)
                   (when (filters/complex? filter-state key)
                     (throw (ex-info "Edit complex conditions in the column filter." {:type :validation})))
                   (assoc (clean (assoc state :mode "filter" :field key))
                          :text (or (get-in filter-state [key 0 :value]) "")))
      "edit" (let [draft (current-composition context state)
                   _     (checked-field context state key)
                   value (get-in (:entry draft) (smart/path key))]
               (assoc (clean (assoc state :field key)) :text (if (keyword? value) (name value) (str value))))
      "use-saved" (let [current (smart/composition (snapshot context) (:id state))]
                    (when-not current (throw (ex-info "This unfinished operation is no longer available." {:type :unavailable-composition})))
                    (clean (assoc state :revision (:revision current) :field nil :conflict nil)))
      "apply-mine" (let [conflict (:conflict state)]
                     (when-not conflict (throw (ex-info "There is no conflicting value to apply." {:type :validation})))
                     (complete* context (assoc input :state (assoc state :conflict nil)
                                               :option (or (:option conflict) {:kind "value" :key (:key conflict) :value (:value conflict)})
                                               :text (:value conflict))))
      "remove" (if (= "add" (:mode state))
                 (complete* context (assoc input :option {:kind "value" :key key :value ""}))
                 (throw (ex-info "Remove completed filters from the filter strip." {:type :validation})))
      "line" (let [draft   (current-composition context state)
                   group   (keyword key)
                   type    (get-in draft [:entry :type])
                   allowed (if (= :production type) #{:inputs :outputs} (if (= :payment type) #{} #{:lines}))]
               (when-not (contains? allowed group) (throw (ex-info "Choose an available item group." {:type :validation})))
               (let [index (count (get-in draft [:entry group]))
                     saved (save-composition! context draft (update (:entry draft) group (fnil conj []) (stock/line)))]
                 (assoc (clean (assoc state :revision (:revision saved) :field (str key "/" index "/name"))) :private-saved? true)))
      "remove-line" (let [draft (current-composition context state)
                          [group index :as line-path] (stock/path-parts key)
                          allowed (if (= :production (get-in draft [:entry :type])) #{:inputs :outputs} #{:lines})
                          lines (get-in draft [:entry group])]
                      (when-not (and (= 2 (count line-path)) (contains? allowed group) (integer? index)
                                     (pos? index) (vector? lines) (< index (count lines)))
                        (throw (ex-info "Choose an extra item line to remove." {:type :validation})))
                      (let [saved (save-composition! context draft
                                                     (assoc (:entry draft) group (into (subvec lines 0 index) (subvec lines (inc index)))))]
                        (assoc (clean (assoc state :revision (:revision saved) :field nil)) :private-saved? true)))
      ("value" "replace-filter")
      (let [value (str (or (:value option) text ""))
            key   (if (str/blank? key) "name" key)
            _     (checked-field context state key)]
        (if (= "add" (:mode state))
          (let [draft (current-composition context state)
                saved (save-composition! context draft (with-value context (:entry draft) key value option))]
            (assoc (clean (assoc state :field nil :revision (:revision saved))) :private-saved? true))
          (assoc (clean (assoc state :field nil)) :filter-changed? true :closeMenu true
                 :filters (filters/smart-add (or view "stock") filter-state key value (= "replace-filter" kind)))))
      (throw (ex-info "Choose a smart input suggestion." {:type :validation})))))


(defaction suggest
  {:route-data {:tover/access :workspace} :schema Input}
  [context input]
  [(model-response context input)])


(defaction complete
  {:route-data {:tover/access :workspace} :schema Input}
  [context input]
  (try
    (let [result   (complete* context input)
          messages (cond (:changed? result) (stock/saved context result)
                         (:filter-changed? result) (stock/response context result)
                         :else [(stock/snapshot-response context result)])]
      (into messages [{:type :signals :data {:saveStatus "Saved"}}
                      (stock/notice "" false)
                      (model-response context result)]))
    (catch ExceptionInfo error
      (let [key (or (get-in input [:option :key]) (get-in input [:state :field]))
            value                    (str (or (get-in input [:option :value]) (:text input) ""))
            {:keys [current errors]} (ex-data error)
            field                    (when (and current (string? key))
                                       (some #(when (= key (:key %)) %) (smart/entry-fields (:entry current))))
            conflict                 (when current
                                       {:key   key :value value :option (or (:option input) {:kind "value" :key key :value value})
                                        :saved (if field (str (get-in (:entry current) (smart/path key))) (i18n/t "Updated unfinished operation"))})
            input                    (cond-> (assoc input :failed? true :text value)
                                       errors (assoc :errors errors)
                                       conflict (assoc :state (assoc (:state input) :revision (:revision current) :field key :conflict conflict)))]
        (into (if (try (filters/check! (or (:view input) "stock") (or (:filters input) {})) (catch ExceptionInfo _ nil))
                [(stock/snapshot-response context input)] [])
              [{:type :signals :data {:saveStatus "Failed"}}
               (stock/notice (i18n/error-message error) true)
               (model-response context input)])))))

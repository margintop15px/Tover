(ns tover.operations.core
  (:require
    [bel.validation :as validation]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as schema]
    [tover.stock.store :as store]))


(def Operation
  [:map
   [:id :string]
   [:rowId :string]
   [:name :string]
   [:article :string]
   [:unit :string]
   [:location :string]
   [:type :string]
   [:before :string]
   [:after :string]
   [:delta :string]
   [:reason :string]
   [:evidence :string]
   [:recordedAt :string]
   [:effectiveAt :string]])


(def ^:private history-query
  '{:prefixes {:t   "<urn:tover:stock-v0:>"
               :xsd "<http://www.w3.org/2001/XMLSchema#>"}
    :select   [?effect ?id ?kind ?rowId ?name ?article ?unit ?location
               ?before ?after ?delta ?reason ?recordedAt ?effectiveAt]
    :where    [[:graph ?history
                [[?effect :t/decision "accepted"] [?effect :t/kind ?kind]
                 [?effect :t/row ?row] [?effect :t/before ?before]
                 [?effect :t/after ?after] [?effect :t/delta ?delta]
                 [?effect :t/reason ?reason]
                 [:optional [[?effect :t/id ?id]]]
                 [:optional [[?effect :t/recorded-at ?recordedAt]]]
                 [:optional [[?effect :t/effective-at ?effectiveAt]]]]]
               [:graph ?catalog
                [[?row :t/id ?rowId] [?row :t/name ?name] [?row :t/article ?article]
                 [?row :t/unit ?unit] [?row :t/location ?location]]]
               [:filter (or (= ?kind "opening") (= ?kind "inline-edit"))]]
    :order-by [(desc (:xsd/dateTime ?recordedAt)) (desc ?effect)]})


(defn- operation
  {:malli/schema [:=> [:cat :map] Operation]}
  [{:keys [effect id kind before after delta reason recordedAt effectiveAt] :as entry}]
  (merge (select-keys entry [:rowId :name :article :unit :location])
         {:id          (str (or id effect))
          :type        (if (= kind "opening") "Opening balance" "Manual adjustment")
          :before      (str before)
          :after       (str after)
          :delta       (str (when (pos? delta) "+") delta)
          :reason      (str reason)
          :evidence    (if (= kind "opening") "Opening declaration" "Manual edit")
          :recordedAt  (str recordedAt)
          :effectiveAt (str effectiveAt)}))


(defn query
  "Read accepted openings and adjustments; browser drafts are separate from history."
  {:malli/schema [:=> [:cat [:map [:repo rdf/Repository] [:workspace-id :uuid]]] [:vector Operation]]}
  [{:keys [repo] :as context}]
  (validation/check! schema/Scope context)
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (validation/check! [:vector Operation] (mapv operation (rdf/select conn history-query (store/scope-bindings context))))))


(defn operations-panel
  {:malli/schema [:=> [:cat [:maybe :map]] schema/Hiccup]}
  [operations]
  [:stock-operations#operations-history
   (cond-> {:data-ignore-morph "" :data-effect (:source operations)}
     (:examples? operations) (assoc :data-examples "" :data-on:examples-toggle (:on-examples-toggle operations)))])

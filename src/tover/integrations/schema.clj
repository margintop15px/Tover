(ns tover.integrations.schema
  "External evidence contracts. These records do not own inventory quantities."
  (:require
    [clojure.string :as str])
  (:import
    [java.time Instant]))


(def Id
  [:and [:string {:min 1 :max 500}] [:fn #(not (str/blank? %))]])

(def Timestamp
  [:and :string [:fn #(try (Instant/parse %) true (catch Exception _ false))]])

(def Revision
  [:int {:min 1}])

(def Hash
  [:re #"[a-f0-9]{64}"])

(def IdentifierType
  [:enum :product-id :article :barcode :sku :warehouse-id :posting-number :return-id :supply-id])

(def Identifier
  [:map
   [:type IdentifierType]
   [:value Id]
   [:role {:optional true} [:enum :primary :source :historical]]])

(def AttributeValue
  [:map
   [:value :string]
   [:dictionary-id {:optional true} Id]])

(def Attribute
  [:map
   [:attribute-id Id]
   [:group-id {:optional true} Id]
   [:group-index {:optional true} [:int {:min 0}]]
   [:values [:vector AttributeValue]]])

(def Line
  [:map
   [:id Id]
   [:identifiers [:vector Identifier]]
   [:product-id {:optional true} Id]
   [:quantity {:optional true} [:or :string :int]]
   [:unit {:optional true} Id]
   [:warehouse-id {:optional true} Id]])

(def Candidate
  [:map
   [:effect {:optional true} [:enum :sale :return :transfer :write-off :opening :correction :none]]
   [:evidence {:optional true} [:enum :dispatch :receipt :transfer-completed :disposal]]
   [:occurred-at {:optional true} Timestamp]
   [:from-location {:optional true} Id]
   [:to-location {:optional true} Id]
   [:lines {:optional true} [:vector Line]]])


(defn- record
  {:malli/schema [:=> [:cat :keyword [:vector :any]] [:vector :any]]}
  [kind fields]
  (into [:map
         [:kind [:= kind]]
         [:id Id]
         [:workspace-id Id]
         [:revision Revision]]
        fields))


(def Connection
  (record :connection
          [[:provider Id]
           [:account-id Id]
           [:store-id Id]
           [:enabled? :boolean]]))

(def Product
  (record :product
          [[:connection-id Id]
           [:source-id Id]
           [:current-revision-id Id]]))

(def Warehouse
  (record :warehouse
          [[:connection-id Id]
           [:source-id Id]
           [:current-revision-id Id]]))

(def SourceRevision
  (record :source-revision
          [[:connection-id Id]
           [:domain [:enum :product :warehouse :stock :posting :return :supply :removal]]
           [:source-id {:optional true} Id]
           [:entity-id {:optional true} Id]
           [:observed-at Timestamp]
           [:provider-updated-at {:optional true} Timestamp]
           [:occurred-at {:optional true} Timestamp]
           [:payload-hash Hash]
           [:adapter-version Id]
           [:complete? :boolean]
           [:facts :map]
           [:identifiers {:optional true} [:vector Identifier]]
           [:attributes {:optional true} [:vector Attribute]]
           [:lines {:optional true} [:vector Line]]
           [:identity-basis {:optional true} Id]]))

(def IdentifierBinding
  (record :identifier-binding
          [[:connection-id Id]
           [:product-id Id]
           [:source-revision-id Id]
           [:type [:enum :product-id :article :barcode :sku]]
           [:value Id]
           [:role {:optional true} [:enum :primary :source :historical]]
           [:active? :boolean]
           [:observed-at Timestamp]
           [:last-observed-at {:optional true} Timestamp]]))

(def Mapping
  (record :mapping
          [[:connection-id Id]
           [:entity-id Id]
           [:target-id Id]
           [:source-revision-id Id]
           [:state [:enum :proposed :confirmed :suspended :retired]]
           [:confirmed-by {:optional true} Id]
           [:confirmed-at {:optional true} Timestamp]
           [:identity-basis {:optional true} Id]]))

(def Proposal
  (record :proposal
          [[:connection-id Id]
           [:action [:enum :catalogue :mapping :opening :correction :operation :cancellation]]
           [:source-revision-ids [:vector {:min 1} Id]]
           [:dependencies [:vector Id]]
           [:mapping-ids [:vector Id]]
           [:entry Candidate]
           [:findings [:vector :map]]]))

(def AcceptanceProvenance
  (record :acceptance-provenance
          [[:connection-id Id]
           [:source-event-key Id]
           [:source-revision-id Id]
           [:accepted-entity-id Id]
           [:accepted-revision Revision]
           [:receipt-id Id]
           [:accepted-at Timestamp]
           [:mapping-revisions [:map-of Id Revision]]
           [:policy-revision Revision]]))

(def Policy
  (record :policy
          [[:preset [:enum :barcode-per-goods :barcode-per-listing]]
           [:required-fields [:vector [:enum :name :article :barcode :unit]]]]))

(def FieldDefinition
  (record :field-definition
          [[:connection-id Id]
           [:category-id Id]
           [:type-id Id]
           [:attribute-id Id]
           [:name Id]
           [:value-type [:enum :string :integer :decimal :boolean :date :dictionary :unknown]]
           [:multiple? :boolean]
           [:complex? :boolean]
           [:unit {:optional true} Id]
           [:dictionary-id {:optional true} Id]]))

(def ViewPreference
  (record :view-preference
          [[:user-id Id]
           [:table Id]
           [:optional-field-ids [:vector Id]]]))


(def Record
  [:multi {:dispatch :kind}
   [:connection Connection]
   [:product Product]
   [:warehouse Warehouse]
   [:source-revision SourceRevision]
   [:identifier-binding IdentifierBinding]
   [:mapping Mapping]
   [:proposal Proposal]
   [:acceptance-provenance AcceptanceProvenance]
   [:policy Policy]
   [:field-definition FieldDefinition]
   [:view-preference ViewPreference]])

(def ValidationContext
  [:map
   [:workspace-id Id]
   [:phase [:enum :source :acceptance]]
   [:selected [:vector :string]]])

(def Finding
  [:map
   [:category [:enum :ambiguous :incomplete :contradictory]]
   [:code :string]
   [:focus-node [:maybe :string]]
   [:path [:maybe :string]]
   [:shape [:maybe :string]]])

(def ValidationResult
  [:map
   [:valid? :boolean]
   [:semantic-version :string]
   [:findings [:vector Finding]]])

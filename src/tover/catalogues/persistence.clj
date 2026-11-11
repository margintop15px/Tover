(ns tover.catalogues.persistence
  "The isolated Goods slice's graph identities and explicit legacy cutover guard."
  (:require
    [bel-rdf.adapter :as adapter]
    [bel-rdf.core :as rdf]
    [bel.runtime :as runtime]
    [tover.shared.schema :as schema])
  (:import [org.eclipse.rdf4j.model Resource]
           [org.eclipse.rdf4j.repository RepositoryConnection]))


(defn graph
  {:malli/schema [:=> [:cat schema/Scope [:enum :domain :delivery :events :receipts]] rdf/IRI]}
  [scope kind]
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":bel-" (name kind))))


(defmethod adapter/graphs ::workspace
  [{:keys [scope]}]
  {:domain (graph scope :domain) :delivery (graph scope :delivery)})


(defmethod runtime/check-source! ::goods
  [_ {:keys [tx scope]}]
  (when (.hasStatement ^RepositoryConnection tx nil nil nil false
                       (into-array Resource [(graph scope :events) (graph scope :receipts)]))
    (throw (ex-info "Existing RDF history requires an explicit journal cutover" {:type :migration-required}))))

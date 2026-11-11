(ns tover.catalogues.persistence
  "The isolated Goods slice's graph identities and explicit legacy cutover guard."
  (:require
    [bel-rdf.core :as rdf]
    [tover.shared.schema :as schema])
  (:import [org.eclipse.rdf4j.model Resource]
           [org.eclipse.rdf4j.repository RepositoryConnection]))


(defn graph
  {:malli/schema [:=> [:cat schema/Scope [:enum :domain :delivery :events :receipts]] rdf/IRI]}
  [scope kind]
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":bel-" (name kind))))


(defn graphs
  "Store graph allocation for a Bel workspace scope."
  {:malli/schema [:=> [:cat [:map [:kind [:= :workspace]] [:id :uuid]]] :map]}
  [{:keys [id]}]
  {:domain (graph {:workspace-id id} :domain) :delivery (graph {:workspace-id id} :delivery)})


(defn legacy-guard
  "Refuse acceptance where pre-journal RDF history still exists."
  {:malli/schema [:=> [:cat :any [:map [:kind :keyword] [:id :any]]] :nil]}
  [tx {:keys [id]}]
  (when (.hasStatement ^RepositoryConnection tx nil nil nil false
                       (into-array Resource [(graph {:workspace-id id} :events) (graph {:workspace-id id} :receipts)]))
    (throw (ex-info "Existing RDF history requires an explicit journal cutover" {:type :migration-required}))))

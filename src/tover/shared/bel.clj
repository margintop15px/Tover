(ns tover.shared.bel
  "Bel system composition for the isolated slices. Live routes remain separate."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-rdf.adapter :as rdf]
    [tover.auth.bel :as auth]
    [tover.catalogues.persistence :as catalogues]
    [tover.workspaces.persistence :as workspaces]))


(defn system
  "Directory on PostgreSQL, catalogue on RDF; either is omitted when its resource is absent."
  {:malli/schema [:=> [:cat [:map [:repo {:optional true} :any] [:datasource {:optional true} :any]
                             [:realtime {:optional true} :any]]] :map]}
  [{:keys [repo datasource realtime]}]
  {:stores    (cond-> {}
                datasource (assoc :directory (postgres/store {:datasource datasource :tables workspaces/tables}))
                repo (assoc :catalogue (rdf/store {:repo repo :graphs catalogues/graphs :guard catalogues/legacy-guard})))
   :journal   :directory
   :scope     :workspace
   :authority {:store :directory :impl (auth/->CurrentSession)}
   :resources {:realtime realtime}})


(defn delivery-system
  "Use the private journal writer resource; accepted work does not depend on a user session."
  {:malli/schema [:=> [:cat :any :any] :map]}
  [repo datasource]
  {:stores  {:catalogue      (rdf/store {:repo repo :graphs catalogues/graphs})
             :journal-writer (postgres/store {:datasource datasource})}
   :journal :journal-writer})

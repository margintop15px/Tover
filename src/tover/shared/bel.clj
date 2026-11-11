(ns tover.shared.bel
  "Bel system composition for the isolated slices. Live routes remain separate."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-rdf.adapter :as rdf]
    [tover.accounts.persistence :as accounts]
    [tover.auth.bel :as authority]
    [tover.catalogues.persistence :as catalogues]
    [tover.workspaces.persistence :as workspaces]))


(defn system
  "Directory on PostgreSQL, catalogue on RDF; either is omitted when its resource is absent."
  {:malli/schema [:=> [:cat [:map [:repo {:optional true} :any] [:datasource {:optional true} :any]
                             [:realtime {:optional true} :any] [:auth {:optional true} :any]
                             [:session-store {:optional true} :any]]] :map]}
  [{:keys [repo datasource realtime auth session-store]}]
  {:stores    (cond-> {}
                datasource (assoc :directory (postgres/store {:datasource datasource :tables (merge workspaces/tables accounts/tables)}))
                repo (assoc :catalogue (rdf/store {:repo repo :graphs catalogues/graphs :guard catalogues/legacy-guard})))
   :journal   :directory
   :scope     :workspace
   :authority {:store :directory :impl (authority/->CurrentSession)}
   :resources {:realtime realtime :auth auth :session-store session-store}})


(defn delivery-system
  "Use the private journal writer resource; accepted work does not depend on a user session."
  {:malli/schema [:=> [:cat [:map [:repo :any] [:datasource :any]]] :map]}
  [{:keys [repo datasource]}]
  {:stores  {:catalogue      (rdf/store {:repo repo :graphs catalogues/graphs})
             :journal-writer (postgres/store {:datasource datasource})}
   :journal :journal-writer})

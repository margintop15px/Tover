(ns tover.shared.bel
  "Bel system composition for the isolated slices. Live routes remain separate."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-rdf.adapter :as rdf]
    [tover.accounts.persistence :as accounts]
    [tover.auth.bel :as authority]
    [tover.catalogues.persistence :as catalogues]
    [tover.invitations.persistence :as invitations]
    [tover.workspaces.persistence :as workspaces]))


(def ^:private directory-tables
  (merge workspaces/tables accounts/tables invitations/tables))


(defn system
  "Directory on PostgreSQL, catalogue on RDF; either is omitted when its resource is absent."
  {:malli/schema [:=> [:cat [:map [:repo {:optional true} :any] [:datasource {:optional true} :any]
                             [:realtime {:optional true} :any] [:auth {:optional true} :any]
                             [:session-store {:optional true} :any]]] :map]}
  [{:keys [repo datasource realtime auth session-store]}]
  {:stores    (cond-> {}
                datasource (assoc :directory (postgres/store {:datasource datasource :tables directory-tables}))
                repo (assoc :catalogue (rdf/store {:repo repo :graphs catalogues/graphs :guard catalogues/legacy-guard})))
   :journal   :directory
   :scope     :workspace
   :authority {:store :directory :impl (authority/->CurrentSession)}
   :resources {:realtime realtime :auth auth :session-store session-store}})


(defn delivery-system
  "The worker's private login: journal delivery and processors, which act as a service, not a user session.
  `mailer` and `origin` are effect resources for processors that send email."
  {:malli/schema [:=> [:cat [:map [:repo {:optional true} :any] [:datasource :any] [:mailer {:optional true} :any]
                             [:origin {:optional true} :string]]] :map]}
  [{:keys [repo datasource mailer origin]}]
  {:stores    (cond-> {:directory (postgres/store {:datasource datasource :tables directory-tables})}
                repo (assoc :catalogue (rdf/store {:repo repo :graphs catalogues/graphs})))
   :journal   :directory
   :scope     :workspace
   :resources {:mailer mailer :origin origin}})

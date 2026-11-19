(ns tover.shared.bel
  "Bel system composition: the directory on PostgreSQL, the catalogue on RDF."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-rdf.adapter :as rdf]
    [tover.catalogues.persistence :as catalogues]
    [tover.identity.session :as session]
    ;; Directory coeffects that commands name by keyword.
    [tover.invitations.persistence]
    [tover.workspaces.persistence]))


(defn system
  "Directory on PostgreSQL, catalogue on RDF; either is omitted when its resource is absent.
  Effect resources: :realtime, :session-store, :provider (tover.identity.provider), :origin."
  {:malli/schema [:=> [:cat [:map [:repo {:optional true} :any] [:datasource {:optional true} :any]
                             [:realtime {:optional true} :any] [:provider {:optional true} :any]
                             [:session-store {:optional true} :any] [:origin {:optional true} :string]]] :map]}
  [{:keys [repo datasource realtime provider session-store origin]}]
  {:stores    (cond-> {}
                datasource (assoc :directory (postgres/store {:datasource datasource}))
                repo (assoc :catalogue (rdf/store {:repo repo :graphs catalogues/graphs :guard catalogues/legacy-guard})))
   :journal   :directory
   :scope     :workspace
   :authority {:store :directory :impl (session/->CurrentSession)}
   :resources {:realtime realtime :provider provider :session-store session-store :origin origin}})


(defn delivery-system
  "The worker's private login: journal delivery and processors, which act as a service, not a user session.
  `mailer` and `origin` are effect resources for processors that send email."
  {:malli/schema [:=> [:cat [:map [:repo {:optional true} :any] [:datasource :any] [:mailer {:optional true} :any]
                             [:origin {:optional true} :string]]] :map]}
  [{:keys [repo datasource mailer origin]}]
  {:stores    (cond-> {:directory (postgres/store {:datasource datasource})}
                repo (assoc :catalogue (rdf/store {:repo repo :graphs catalogues/graphs})))
   :journal   :directory
   :scope     :workspace
   :resources {:mailer mailer :origin origin}})

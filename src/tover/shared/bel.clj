(ns tover.shared.bel
  "Resource and policy wiring for the isolated Bel slices. Live routes remain separate."
  (:require
    [bel-postgres.adapter]
    [bel-postgres.journal]
    [bel-rdf.adapter]
    [tover.auth.bel :as auth]
    [tover.catalogues.persistence :as catalogues]
    [tover.workspaces.persistence]))


(defn runtime-config
  {:malli/schema [:=> [:cat [:enum :rdf :postgres]] :map]}
  [adapter]
  (cond-> {:adapter   adapter :resource (if (= adapter :rdf) :repo :datasource)
           :scope     [(if (= adapter :rdf) :workspace-id :user-id)]                   :actor :user-id
           :resources [:repo :datasource :realtime]
           :authority {:adapter :postgres :resource :datasource :check ::auth/current}
           :journal   {:adapter :postgres :resource :datasource}}
    (= adapter :rdf) (assoc :graphs ::catalogues/workspace :check-source ::catalogues/goods)))


(defn delivery-context
  "Use the private journal writer resource; accepted work does not depend on a user session."
  {:malli/schema [:=> [:cat :any :any] :map]}
  [repo datasource]
  {:bel/runtime (dissoc (runtime-config :rdf) :authority :check-source)
   :resources   {:repo repo :datasource datasource}})

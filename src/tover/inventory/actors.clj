(ns tover.inventory.actors
  (:require
    [tover.auth.security :as security]
    [tover.shared.i18n :as i18n]
    [tover.workspaces.core :as workspaces]))


(def actor-fields
  {:actor :actor-label :created-by :created-by-label :proposed-by :proposed-by-label})


(defn labels
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:map-of :string :string]]}
  [{:keys [workspaces request workspace-id] :as context} records]
  (let [ids (vec (distinct (keep #(some-> % str parse-uuid) (mapcat #(keep % (keys actor-fields)) records))))]
    (if (and workspaces request (seq ids))
      (try
        (workspaces/actor-labels workspaces (security/access-token context request) workspace-id ids)
        (catch clojure.lang.ExceptionInfo _ {}))
      {})))


(defn enrich
  "Resolve presentation labels without changing immutable actor identifiers."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [context records]
  (let [names (labels context records)]
    (mapv (fn [record]
            (reduce-kv (fn [record field label-field]
                         (if-let [id (get record field)]
                           (assoc record label-field (get names (str id) (i18n/t "Unavailable user")))
                           record)) record actor-fields)) records)))

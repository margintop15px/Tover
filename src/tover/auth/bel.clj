(ns tover.auth.bel
  "Current Tover session and workspace authority, checked in Bel's enclosing transaction."
  (:require
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]
    [clojure.edn :as edn])
  (:import [java.time Instant]))


(defn current
  {:malli/schema [:=> [:cat :any :map] :map]}
  [tx context]
  (let [{:keys [actor user-id scope auth-session-id session-id]} context]
    (when-not (and (or (= scope {:user-id user-id})
                       (and (= #{:workspace-id} (set (keys scope))) (uuid? (:workspace-id scope))))
                   (uuid? user-id) (= actor user-id) (string? auth-session-id) (uuid? session-id))
      (throw (ex-info "A current user session is required" {:type :forbidden :status 403})))

    (let [row  (db/execute-one! tx {:select [:user_id :data :expires_at] :from [:app_private.sessions]
                                    :where  [:= :id auth-session-id]     :for  :share})
          data (some->> (:data row) (edn/read-string {:readers {}}))]
      (when-not (and (= user-id (:user_id row))
                     (= session-id (:bel/session-id data))
                     (.isAfter ^Instant (:expires_at row) (Instant/now)))
        (throw (ex-info "This session has ended or changed" {:type :forbidden :status 403})))

      (db/execute-one! tx {:select [[[:set_config "request.jwt.claim.sub" [:cast actor :text] true]]
                                    [[:set_config "tover.session_key" auth-session-id true]]]})

      (let [context (assoc context
                           :tx tx
                           :confirmed? (:confirmed (db/execute-one! tx {:select [[[:app_private.bel_confirmed_user] :confirmed]]}))
                           :password-required? (boolean (:identity/password-required? data)))]
        (if-let [workspace-id (:workspace-id scope)]
          (let [membership (db/execute-one! tx {:select [:role_id :status]
                                                :from   [[[:app_private.bel_workspace_membership workspace-id] :membership]]})]
            (when-not (and (:confirmed? context) (not (:password-required? context)) (= "active" (:status membership)))
              (throw (ex-info "Current workspace access is required" {:type :forbidden :status 403})))
            (assoc context
                   :workspace-id workspace-id
                   :memberships [{:workspace-id workspace-id :role (keyword (:role_id membership))}]))
          context)))))


(defrecord CurrentSession []
  runtime/Authority
  (-principal [_ tx context] (current tx context)))


(alter-meta! #'->CurrentSession assoc :malli/schema [:=> [:cat] :map])
(alter-meta! #'map->CurrentSession assoc :malli/schema [:=> [:cat :map] :map])

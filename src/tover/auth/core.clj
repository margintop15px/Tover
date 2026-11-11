(ns tover.auth.core
  (:require
    [bel.validation :as validation]
    [tover.workspaces.core :as workspaces]))


(def User
  [:map
   [:user-id :uuid]
   [:email :string]
   [:email-confirmed? :boolean]
   [:locale {:optional true} [:enum "en" "ru"]]])


(def Tokens
  [:map
   [:access-token :string]
   [:refresh-token :string]
   [:expires-at :int]
   [:user User]])


(def Identity
  [:map
   [:user User]
   [:profile-complete? :boolean]
   [:memberships [:vector workspaces/Membership]]])


(defprotocol Auth
  "Application identity operations. Credentials and provider payloads stay here."
  (^{:malli/schema [:=> [:cat :any :map] :map]} sign-up! [provider input])
  (^{:malli/schema [:=> [:cat :any :map] :map]} sign-in! [provider input])
  (^{:malli/schema [:=> [:cat :any :string] :map]} token-identity [provider access-token])
  (^{:malli/schema [:=> [:cat :any :string] :map]} refresh! [provider refresh-token])
  (^{:malli/schema [:=> [:cat :any :string] :nil]} sign-out! [provider access-token])
  (^{:malli/schema [:=> [:cat :any :map] :map]} verify-email! [provider input])
  (^{:malli/schema [:=> [:cat :any :map] :nil]} recover! [provider input])
  (^{:malli/schema [:=> [:cat :any :string :string] :map]} set-password! [provider access-token password])
  (^{:malli/schema [:=> [:cat :any :map] :nil]} invite! [provider input])
  (^{:malli/schema [:=> [:cat :any :map] :nil]} magic-link! [provider input]))


(defn fail!
  "Throw only application error codes and safe metadata, never provider bodies."
  {:malli/schema
   [:function
    [:=> [:cat :keyword :int :boolean] :any]
    [:=> [:cat :keyword :int :boolean :map] :any]]}
  ([code status uncertain?]
   (fail! code status uncertain? {}))

  ([code status uncertain? details]
   (throw (ex-info "Identity request failed."
                   (merge {:type :identity :code code :status status :uncertain? uncertain?}
                          (select-keys details [:type :retry-after :invite-id]))))))


(defn check-input!
  "Use the shared validator without attaching submitted credentials to errors."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [schema value]
  (try (validation/check! schema value)
       (catch clojure.lang.ExceptionInfo _
         (fail! :invalid-input 400 false))))


(defn claims-identity
  "Map access-token claims written by the Auth token hook. Without them the hook is not installed."
  {:malli/schema [:=> [:cat :map] Identity]}
  [{:keys [sub email language profile_complete email_confirmed memberships] :as claims}]
  (when-not (every? #(some? (get claims %)) [:memberships :profile_complete :email_confirmed])
    (fail! :identity-claims-missing 503 false {:type :configuration}))
  (try
    (check-input! Identity
                  {:user              (cond-> {:user-id (parse-uuid sub) :email email :email-confirmed? email_confirmed}
                                        (contains? #{"en" "ru"} language) (assoc :locale language))
                   :profile-complete? profile_complete
                   :memberships       (mapv (fn [membership]
                                              {:workspace-id (parse-uuid (:workspace_id membership))
                                               :name         (:name membership)
                                               :role         (keyword (:role membership))
                                               :created-at   (:created_at membership)})
                                            memberships)})
    (catch Exception _
      (fail! :invalid-response 502 false))))

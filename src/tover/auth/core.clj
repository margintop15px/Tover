(ns tover.auth.core
  (:require
    [bel.validation :as validation]))


(def User
  [:map
   [:user-id :uuid]
   [:email :string]
   [:email-confirmed? :boolean]])


(def Tokens
  [:map
   [:access-token :string]
   [:refresh-token :string]
   [:expires-at :int]
   [:user User]])


(defprotocol Auth
  "Application identity operations. Credentials and provider payloads stay here."
  (^{:malli/schema [:=> [:cat :any :map] :map]} sign-up! [provider input])
  (^{:malli/schema [:=> [:cat :any :map] :map]} sign-in! [provider input])
  (^{:malli/schema [:=> [:cat :any :string] :map]} current-user [provider access-token])
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
                          (select-keys details [:retry-after :invite-id]))))))


(defn check-input!
  "Use the shared validator without attaching submitted credentials to errors."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [schema value]
  (try (validation/check! schema value)
       (catch clojure.lang.ExceptionInfo _
         (fail! :invalid-input 400 false))))

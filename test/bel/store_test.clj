(ns bel.store-test
  (:require
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.test :refer [deftest is]]))


(defrecord TestStore [name seen]
  store/Transactional
  (-transact [_ isolation f]
    (let [connection (Object.)]
      (swap! seen conj [:begin name isolation])
      (try
        (let [result (f connection)]
          (swap! seen conj [:commit name])
          result)
        (catch Exception error
          (swap! seen conj [:rollback name])
          (throw error))))))


(defrecord TestAuthority [seen]
  runtime/Authority
  (-principal [_ tx context]
    (swap! seen conj :authorized)
    (assoc context :authority-connection tx)))


(defn context [shared? seen]
  {:bel/system {:stores    {:domain (->TestStore :domain seen) :identity (->TestStore :identity seen)}
                :authority {:store (if shared? :domain :identity) :impl (->TestAuthority seen)}}})


(defn error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest colocated-authority-and-domain-share-one-transaction
  (let [seen (atom []) ctx (context true seen)]
    (runtime/with-authority ctx :domain :serializable
      (fn [ctx]
        (store/with-store ctx :domain :serializable
          (fn [ctx]
            (is (identical? (:authority-connection ctx) (:tx ctx)))
            (store/with-store ctx :domain :read-committed
              (fn [journal]
                (is (identical? (:tx ctx) (:tx journal)))))))))
    (is (= [[:begin :domain :serializable] :authorized [:commit :domain]] @seen))))


(deftest separate-authority-stays-open-through-source-commit-or-rollback
  (doseq [fail? [false true]]
    (let [seen (atom []) ctx (context false seen)]
      (try
        (runtime/with-authority ctx :domain :serializable
          (fn [ctx]
            (store/with-store ctx :domain :serializable
              (fn [ctx]
                (is (not (identical? (:authority-connection ctx) (:tx ctx))))
                (swap! seen conj :mapped)
                (when fail? (throw (ex-info "Source failed" {})))))))
        (catch clojure.lang.ExceptionInfo _
          (is fail?)))
      (let [outcome (if fail? :rollback :commit)]
        (is (= [[:begin :identity :read-committed] :authorized
                [:begin :domain :serializable] :mapped
                [outcome :domain] [outcome :identity]] @seen))))))


(deftest without-authority-the-callback-runs-directly
  (let [seen (atom []) ctx (update (context true seen) :bel/system dissoc :authority)]
    (is (= :done (runtime/with-authority ctx :domain :serializable (constantly :done))))
    (is (= [] @seen))))


(deftest unknown-store-key-is-a-configuration-error
  (let [ctx (context true (atom []))]
    (is (= {:type :configuration :store :missing}
           (select-keys (error-data #(store/with-store ctx :missing :serializable identity)) [:type :store])))))


(deftest store-scope-converts-trusted-keys
  (let [w (random-uuid) u (random-uuid)]
    (is (= {:kind :workspace :id w} (store/store-scope {:workspace-id w})))
    (is (= {:kind :user :id u} (store/store-scope {:user-id u})))
    (is (= {:kind :session :id u} (store/store-scope {:session-id u})))
    (is (= :configuration (:type (error-data #(store/store-scope {:user-id u :workspace-id w})))))))

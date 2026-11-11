(ns bel.store-test
  (:require
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.test :refer [deftest is]]))


(defmethod store/open-transaction ::database
  [_ {:keys [name seen]} isolation f]
  (let [connection (Object.)]
    (swap! seen conj [:begin name isolation])
    (try
      (let [result (f connection)]
        (swap! seen conj [:commit name])
        result)
      (catch Exception error
        (swap! seen conj [:rollback name])
        (throw error)))))


(defmethod runtime/authority ::current
  [_ context]
  (swap! (get-in context [:resources :seen]) conj :authorized)
  (assoc context :authority-connection (:tx context)))


(defn context [shared? seen]
  {:bel/runtime {:adapter   ::database :resource :domain
                 :authority {:adapter ::database :resource (if shared? :domain :identity) :check ::current}}
   :resources   {:seen seen :domain {:name :domain :seen seen} :identity {:name :identity :seen seen}}})


(deftest colocated-authority-and-domain-share-one-transaction
  (let [seen (atom []) ctx (context true seen)]
    (runtime/with-authority ctx :serializable
      #(store/transact % (store/domain ctx) :serializable
                       (fn [ctx]
                         (is (identical? (:authority-connection ctx) (:tx ctx)))
                         (store/transact ctx {:adapter ::database :resource :domain} :read-committed
                                         (fn [journal]
                                           (is (identical? (:tx ctx) (:tx journal))))))))
    (is (= [[:begin :domain :serializable] :authorized [:commit :domain]] @seen))))


(deftest separate-authority-stays-open-through-source-commit-or-rollback
  (doseq [fail? [false true]]
    (let [seen (atom []) ctx (context false seen)]
      (try
        (runtime/with-authority ctx :serializable
          #(store/transact % (store/domain ctx) :serializable
                           (fn [ctx]
                             (is (not (identical? (:authority-connection ctx) (:tx ctx))))
                             (swap! seen conj :mapped)
                             (when fail? (throw (ex-info "Source failed" {}))))))
        (catch clojure.lang.ExceptionInfo _
          (is fail?)))
      (let [outcome (if fail? :rollback :commit)]
        (is (= [[:begin :identity :read-committed] :authorized
                [:begin :domain :serializable] :mapped
                [outcome :domain] [outcome :identity]] @seen))))))


(deftest a-named-resource-cannot-change-its-adapter-inside-a-transaction
  (let [ctx (context true (atom []))]
    (is (= :configuration
           (try
             (store/transact ctx (store/domain ctx) :serializable
                             #(store/transact % {:adapter ::different :resource :domain} :serializable identity))
             (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))

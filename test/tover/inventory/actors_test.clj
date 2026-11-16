(ns tover.inventory.actors-test
  (:require
    [bel-postgres.jdbc :as db]
    [bel.store :as store]
    [clojure.test :refer [deftest is]]
    [tover.inventory.actors :as actors])
  (:import
    [org.postgresql.util PGobject]))


(defn- context
  {:malli/schema [:=> [:cat] :map]}
  []
  {:user-id    #uuid "00000000-0000-4000-8000-000000000001" :workspace-id #uuid "00000000-0000-4000-8000-000000000101"
   :bel/system {:stores {:directory (reify store/Transactional (-transact [_ _ f] (f ::tx)))}}})


(defn- labels
  {:malli/schema [:=> [:cat :string] :any]}
  [json]
  (doto (PGobject.) (.setType "jsonb") (.setValue json)))


(deftest attribution-reads-the-selected-workspace-as-the-signed-in-user
  (let [{:keys [user-id workspace-id] :as context} (context)
        proposed (random-uuid)
        calls (atom [])
        record {:actor (str user-id) :created-by user-id :proposed-by (str proposed) :reason "Count"}]
    (with-redefs [db/execute-one! (fn [tx query]
                                    (swap! calls conj [tx query])
                                    {:labels (labels (str "{\"" user-id "\":\"Owner Name\",\"" proposed "\":\"editor@example.test\"}"))})]
      (let [actual (first (actors/enrich context [record]))]
        (is (= record (dissoc actual :actor-label :created-by-label :proposed-by-label)))
        (is (= ["Owner Name" "Owner Name" "editor@example.test"]
               ((juxt :actor-label :created-by-label :proposed-by-label) actual)))))
    (is (= [::tx ::tx] (map first @calls)))
    (is (= [[:set_config "request.jwt.claim.sub" [:cast user-id :text] true]]
           (get-in (first @calls) [1 :select 0])))
    (let [[_ workspace [_ [_ ids]]] (get-in (second @calls) [1 :select 0 0])]
      (is (= workspace-id workspace))
      (is (= #{user-id proposed} (set ids)))
      (is (= 2 (count ids))))))


(deftest unavailable-labels-and-non-identity-actors-have-safe-presentation
  (let [records  [{:actor (str (random-uuid)) :proposed-by "old-actor"} {:reason "No actor"}]
        expected [(assoc (first records) :actor-label "Unavailable user" :proposed-by-label "Unavailable user")
                  (second records)]]
    (with-redefs [db/execute-one! (fn [& _] (throw (ex-info "private upstream details" {:status 503})))]
      (is (= expected (actors/enrich (context) records)))
      (is (= [{:actor "system" :actor-label "Unavailable user"}] (actors/enrich (context) [{:actor "system"}]))))
    (is (= expected (actors/enrich {} records)))))

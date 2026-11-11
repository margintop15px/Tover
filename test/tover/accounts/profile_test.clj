(ns tover.accounts.profile-test
  "Board scenarios for display name and account language, decided without a store."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.accounts.profile.model :as model]))


(def alice #uuid "00000000-0000-0000-0000-0000000000a1")
(def bob #uuid "00000000-0000-0000-0000-0000000000b0")


(def principal
  {:user-id alice :confirmed? true :password-required? false})


(def refresh
  [[:tover.auth/refresh-claims {}]])


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.accounts.profile.model])
  (f))


(use-fixtures :each fixture/isolated-registry register)


(defn error-data
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn profile
  {:malli/schema [:=> [:cat :map] :map]}
  [fields]
  (merge {:user-id alice :name-revision 0 :language-revision 0} fields))


(defn decide
  {:malli/schema [:=> [:cat :qualified-keyword :map :map] :map]}
  [command saved input]
  (runtime/decide command {:profile (profile saved) :actor alice} (assoc input :account alice)))


(defn authorize
  "Policies run in the runtime's authorization step, not in decide."
  {:malli/schema [:=> [:cat :qualified-keyword :map :map] :nil]}
  [command principal input]
  (runtime/authorize! (get-in (bel/operations) [:commands command]) {:principal principal :scope {:user-id alice}} input))


(deftest first-name-is-accepted
  (let [{:keys [events fx]} (decide ::model/set-display-name {} {:display-name "  Алиса  " :name-revision 0})]
    (is (= [{:type ::model/display-name-changed :data {:user-id alice :display-name "Алиса" :name-revision 1}}]
           (mapv #(select-keys % [:type :data]) events)))
    (is (= refresh fx))))


(deftest unchanged-name-emits-nothing
  (let [plan (decide ::model/set-display-name {:display-name "Alice" :name-revision 3}
                     {:display-name "Alice" :name-revision 3})]
    (is (= [] (:events plan)))
    (is (= refresh (:fx plan)))))


(deftest blank-name-is-invalid
  (is (= :validation (:type (error-data #(decide ::model/set-display-name {} {:display-name "   " :name-revision 0}))))))


(deftest stale-name-is-rejected-with-latest
  (let [data (error-data #(decide ::model/set-display-name {:display-name "Bob" :name-revision 2}
                                  {:display-name "Rob" :name-revision 1}))]
    (is (= :stale-profile (:type data)))
    (is (= {:display-name "Bob" :name-revision 2} (:latest data)))))


(deftest language-change-ignores-name-revision
  (let [{:keys [events fx]} (decide ::model/change-account-language {:display-name "Alice" :name-revision 5}
                                    {:language "ru" :language-revision 0})]
    (is (= [{:type ::model/account-language-changed :data {:user-id alice :language "ru" :language-revision 1}}]
           (mapv #(select-keys % [:type :data]) events)))
    (is (= refresh fx))))


(deftest form-for-another-account-is-forbidden
  (doseq [[command input] [[::model/set-display-name {:account bob :display-name "Alice" :name-revision 0}]
                           [::model/change-account-language {:account bob :language "ru" :language-revision 0}]]]
    (is (= :forbidden (:type (error-data #(authorize command principal input)))))
    (is (nil? (authorize command principal (assoc input :account alice))))))


(deftest recovery-session-is-forbidden
  (is (= :forbidden (:type (error-data #(authorize ::model/set-display-name (assoc principal :password-required? true)
                                                   {:account alice :display-name "Alice" :name-revision 0})))))
  (is (= :forbidden (:type (error-data #(authorize ::model/change-account-language (assoc principal :confirmed? false)
                                                   {:account alice :language "ru" :language-revision 0}))))))


(deftest name-longer-than-100-is-invalid
  (is (= :validation (:type (error-data #(decide ::model/set-display-name {}
                                                 {:display-name (apply str (repeat 101 "a")) :name-revision 0})))))
  (is (= 1 (get-in (decide ::model/set-display-name {} {:display-name (apply str (repeat 100 "a")) :name-revision 0})
                   [:events 0 :data :name-revision]))))

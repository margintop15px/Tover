(ns tover.identity.limits-test
  "The request limits of board chapters 01, 01a, 02 and 02a, over the fixture store."
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.fixtures :as fixtures]
    [tover.identity.limits :as limits]))


(def alice (limits/email-key "alice@example.test"))
(def bob (limits/email-key "bob@example.test"))


(defn- store
  {:malli/schema [:=> [:cat] :map]}
  []
  (fixtures/system))


(defn- wait!
  {:malli/schema [:=> [:cat :map :int] :any]}
  [{:keys [state]} seconds]
  (swap! state update :elapsed (fnil + 0) seconds))


(defn- attempt!
  {:malli/schema [:=> [:cat :map :string :string :keyword] :map]}
  [{:keys [limits]} email address outcome]
  (let [{:keys [attempt-id] :as started} (limits/-start-attempt! limits email address)]
    (when attempt-id (limits/-settle-attempt! limits attempt-id outcome))
    started))


(defn- fail!
  {:malli/schema [:=> [:cat :map :string :string :int] :any]}
  [services email address n]
  (dotimes [_ n] (attempt! services email address :failed)))


(defn- refused?
  {:malli/schema [:=> [:cat :map :string :string] :boolean]}
  [services email address]
  (some? (:retry-after (attempt! services email address :discarded))))


(deftest case-and-spaces-share-a-limit
  (is (= alice (limits/email-key "  ALICE@Example.test ")))
  (is (not= alice bob))
  (is (not (re-find #"alice" alice))))


(deftest an-email-may-ask-again-after-sixty-seconds
  (let [{:keys [limits] :as services} (store)]
    (is (nil? (limits/-start-email! limits :confirmation alice)))
    (is (= 60 (limits/-start-email! limits :confirmation alice)))
    (testing "another email and another purpose are counted apart"
      (is (nil? (limits/-start-email! limits :confirmation bob)))
      (is (nil? (limits/-start-email! limits :recovery alice))))
    (wait! services 59)
    (testing "a refused request does not restart the wait"
      (is (= 1 (limits/-start-email! limits :confirmation alice))))
    (wait! services 1)
    (is (nil? (limits/-start-email! limits :confirmation alice)))))


(deftest the-eleventh-attempt-from-one-place-waits
  (let [services (store)]
    (fail! services alice "a" 10)
    (is (= 900 (:retry-after (attempt! services alice "a" :failed))))
    (is (not (refused? services alice "b")))
    (is (not (refused? services bob "a")))
    (wait! services 600)
    (testing "a refused attempt does not extend the wait"
      (is (= 300 (:retry-after (attempt! services alice "a" :failed)))))
    (wait! services 301)
    (is (not (refused? services alice "a")))))


(deftest the-hundred-and-first-attempt-from-one-address-waits
  (let [services (store)]
    (doseq [n (range 100)] (fail! services (str "email-" n) "a" 1))
    (is (refused? services alice "a"))
    (is (not (refused? services alice "b")))))


(deftest one-email-from-many-addresses-waits
  (let [services (store)]
    (doseq [n (range 100)] (fail! services alice (str "address-" n) 1))
    (is (refused? services alice "new"))
    (is (not (refused? services bob "new")))))


(deftest a-success-clears-only-its-pair
  (let [services (store)]
    (fail! services alice "a" 9)
    (fail! services alice "b" 10)
    (attempt! services alice "a" :succeeded)
    (fail! services alice "a" 9)
    (is (not (refused? services alice "a")))
    (is (refused? services alice "b"))
    (testing "the counts of the whole email and address keep the cleared failures"
      (doseq [n (range 72)] (fail! services alice (str "address-" n) 1))
      (is (refused? services alice "new")))))


(deftest a-cleared-email-keeps-the-address-count
  (let [{:keys [limits] :as services} (store)]
    (fail! services alice "a" 10)
    (doseq [n (range 90)] (fail! services alice (str "address-" n) 1))
    (is (refused? services alice "new"))
    (limits/-clear-email! limits alice)
    (is (not (refused? services alice "a")))
    (is (not (refused? services alice "new")))
    (doseq [n (range 90)] (fail! services (str "email-" n) "a" 1))
    (is (refused? services alice "a"))))


(deftest a-pending-attempt-counts-for-one-minute
  (let [{:keys [limits] :as services} (store)]
    (dotimes [_ 10] (limits/-start-attempt! limits alice "a"))
    (is (= 60 (:retry-after (limits/-start-attempt! limits alice "a"))))
    (wait! services 61)
    (is (some? (:attempt-id (limits/-start-attempt! limits alice "a"))))))


(deftest the-longest-wait-is-shown
  (let [services (store)]
    (doseq [n (range 100)] (fail! services (str "email-" n) "a" 1))
    (wait! services 300)
    (fail! services alice "b" 10)
    (is (= 600 (:retry-after (attempt! services bob "a" :failed))))
    (is (= 900 (:retry-after (attempt! services alice "b" :failed))))))

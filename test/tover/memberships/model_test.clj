(ns tover.memberships.model-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.memberships.model :as model]))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(def cid {:membership-id #uuid "00000000-0000-0000-0000-0000000000c1" :role "member" :status "active" :revision 3})


(deftest check-target-rejects-stale-revision
  (is (= :changed-since-review (rejection #(model/check-target! cid 2 #{"active"} model/not-active))))
  (is (= :changed-since-review (rejection #(model/check-target! nil 2 #{"active"} model/not-active))))
  (is (nil? (model/check-target! cid 3 #{"active"} model/not-active))))


(deftest check-target-rejects-wrong-status
  (is (= :not-suspended (rejection #(model/check-target! cid 3 #{"suspended"} model/not-suspended)))))


(deftest admin-may-not-manage-owner
  (is (false? (model/may-manage? {:role "admin" :status "active"} {:role "owner"})))
  (is (true? (model/may-manage? {:role "admin" :status "active"} {:role "admin"})))
  (is (true? (model/may-manage? {:role "owner" :status "active"} {:role "owner"})))
  (is (false? (model/may-manage? {:role "owner" :status "suspended"} {:role "member"}))))


(deftest reason-blank-is-absent
  (is (nil? (model/reason {:reason "   "})))
  (is (nil? (model/reason {})))
  (is (= "late" (model/reason {:reason " late "}))))


(deftest reason-over-500-rejects
  (is (= :reason-too-long (rejection #(model/reason {:reason (apply str (repeat 501 "x"))})))))


(deftest keeps-an-owner
  (let [ann #uuid "00000000-0000-0000-0000-0000000000a1"
        dee #uuid "00000000-0000-0000-0000-0000000000d1"]
    (is (true? (model/keeps-an-owner? [ann dee] #{ann})))
    (is (false? (model/keeps-an-owner? [ann] #{ann})))))

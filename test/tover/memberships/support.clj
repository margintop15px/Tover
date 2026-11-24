(ns tover.memberships.support
  "Shared people and helpers for the membership scenario tests (board chapters 34–39). Ann and Dee are Owners,
  Bob is an Admin, Cid a Member, all in one workspace."
  (:require
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [tover.invitations.fixtures :as invitations])
  (:import
    [java.time Instant]))


(def model-namespaces
  (into invitations/model-namespaces
        ['tover.memberships.model 'tover.memberships.role.model 'tover.memberships.suspension.model
         'tover.memberships.removal.model 'tover.memberships.departure.model 'tover.memberships.ownership.model]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! model-namespaces)
  (f))


(def workspace #uuid "00000000-0000-0000-0000-0000000000f1")
(def now (Instant/parse "2026-11-24T10:00:00Z"))


(defn member
  {:malli/schema [:=> [:cat :int :string :string :int] :map]}
  [n role status revision]
  {:membership-id (parse-uuid (format "00000000-0000-0000-0000-0000000001%02d" n))
   :user-id       (parse-uuid (format "00000000-0000-0000-0000-0000000002%02d" n))
   :workspace-id  workspace :role role :status status :revision revision})


(def ann (member 1 "owner" "active" 1))
(def dee (member 2 "owner" "active" 1))
(def bob (member 3 "admin" "active" 4))
(def cid (member 4 "member" "active" 3))


(defn decide
  "Decide command `id` for `actor` with `target`, the active Owner ids `owners` and `input`."
  {:malli/schema [:=> [:cat :qualified-keyword :map [:maybe :map] [:sequential :uuid] :map] :map]}
  [id actor target owners input]
  (runtime/decide id {:actor (:user-id actor) :now    now    :scope  {:workspace-id workspace}
                      :own   actor            :target target :owners owners}
                  (merge (when target {:membership-id (:membership-id target) :expected-revision (:revision target)})
                         input)))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn events
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [plan]
  (mapv #(select-keys % [:type :data]) (:events plan)))


(defn owners
  {:malli/schema [:=> [:cat [:* :map]] [:vector :uuid]]}
  [& people]
  (mapv :user-id people))

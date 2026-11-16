(ns tover.invitations.team.model
  "Workspace Team: current, suspended and removed members and the latest offer per recipient with its own
  delivery state, read with current management permission."
  (:require
    [bel.core :refer [defreadmodel]]
    [bel.validation :as validation]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.status.model :as status])
  (:import
    [java.time Instant]))


(def Member
  [:map
   [:user-id :uuid]
   [:name {:optional true} :string]
   [:email :string]
   [:role :string]
   [:status [:enum "active" "suspended" "removed"]]
   [:removed-at {:optional true} validation/Instant]])


(def Offer
  [:map
   [:invitation-id :uuid]
   [:email :string]
   [:role :string]
   [:state [:enum "pending" "accepted" "revoked" "expired" "invalidated"]]
   [:expires-at validation/Instant]
   [:delivery {:optional true} status/Delivery]
   [:can-resend? :boolean]
   [:can-cancel? :boolean]])


(def Team
  [:map [:members [:vector Member]] [:invitations [:vector Offer]]])


(defn- normalized
  {:malli/schema [:=> [:cat :string] :string]}
  [email]
  (issue/normalize email))


(defn offer
  "One offer with its latest delivery request; Resend and Cancel follow current eligibility only."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:maybe :map] validation/Instant] Offer]}
  [invitation delivery membership now]
  (let [state    (let [state (status/effective-status invitation now)]
                   (if (and (= "pending" state) (issue/invalidated? membership invitation)) "invalidated" state))
        current  (some-> delivery status/delivery-status)
        pending? (= "pending" state)]
    (cond-> {:invitation-id (:invitation-id invitation)
             :email         (normalized (:email invitation))
             :role          (:role invitation)
             :state         state
             :expires-at    (:expires-at invitation)
             :can-resend?   (boolean (and pending? (not (:active? current)) (not= "permanent" (:problem current))
                                          (or (nil? current) (not (.isBefore ^Instant now (:next-resend-at current))))))
             :can-cancel?   pending?}
      current (assoc :delivery current))))


(defn team
  "Members sorted by email; the latest offer per recipient, newest first."
  {:malli/schema [:=> [:cat [:map [:members [:sequential :map]] [:invitations [:sequential :map]]
                             [:deliveries [:sequential :map]]]
                       validation/Instant]
                  Team]}
  [{:keys [members invitations deliveries]} now]
  (let [by-email   (into {} (map (juxt #(normalized (:email %)) identity)) members)
        latest     (->> invitations
                        (sort-by :created-at #(compare %2 %1))
                        (reduce (fn [seen invitation]
                                  (let [email (normalized (:email invitation))]
                                    (if (contains? seen email) seen (assoc seen email invitation))))
                                {}))
        deliveries (->> deliveries
                        (sort-by :requested-at #(compare %2 %1))
                        (reduce (fn [seen delivery]
                                  (if (contains? seen (:invitation-id delivery)) seen (assoc seen (:invitation-id delivery) delivery)))
                                {}))]
    {:members     (vec (sort-by (juxt :email :user-id) (map #(select-keys % [:user-id :name :email :role :status :removed-at]) members)))
     :invitations (->> (vals latest)
                       (sort-by :created-at #(compare %2 %1))
                       (mapv #(offer % (get deliveries (:invitation-id %)) (get by-email (normalized (:email %))) now)))}))


(defreadmodel workspace-team
  {:input  [:map]
   :output Team
   :policy ::issue/invitation-manager
   :cofx   [[:rows :tover.invitations.persistence/team]
            [:clock :bel/now]]}
  [{:keys [rows clock]} _]
  (team rows clock))

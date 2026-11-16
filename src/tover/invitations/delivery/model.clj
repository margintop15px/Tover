(ns tover.invitations.delivery.model
  "Invitation email delivery: a four-attempt budget with durable outcomes. The processor picks due requests;
  each attempt is recorded before the email is handed to the mailer, so an uncertain send is never retried blindly."
  (:require
    [bel.core :as core :refer [defcommand defevent defprocessor]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [tover.invitations.delivery.email :as email]
    [tover.invitations.issue.model :as invitations]
    [tover.invitations.persistence :as persistence])
  (:import
    [java.time Duration Instant]))


(def attempt-budget
  "Submissions per request: one attempt and three retries."
  4)


(def reconciliation
  "How long an Unconfirmed submission waits for its outcome before its request stops."
  (Duration/ofSeconds 60))


(def retry-waits
  "Wait after the temporary failure of attempt n, at index n - 1."
  [(Duration/ofMinutes 1) (Duration/ofMinutes 5) (Duration/ofMinutes 30)])


(def Attempt
  [:int {:min 1 :max attempt-budget}])


(def Problem
  [:map [:type [:enum :temporary :permanent :uncertain]] [:message :string]])


(defmethod runtime/policy ::delivery-service
  [_ {:keys [principal]} _]
  (some? (:service principal)))


(defevent invitation-delivery-attempted
  {:schema [:map
            [:delivery-id :uuid]
            [:attempts :int]
            [:delivery-status [:= "unconfirmed"]]
            [:next-attempt-at {:optional true} validation/Instant]
            [:attempted-at validation/Instant]]})


(defevent invitation-delivery-stopped
  {:schema [:map
            [:delivery-id :uuid]
            [:delivery-status [:= "stopped"]]
            [:next-attempt-at {:optional true} validation/Instant]]})


(defevent invitation-delivery-stopped-unconfirmed
  {:schema [:map
            [:delivery-id :uuid]
            [:delivery-status [:= "stopped"]]
            [:last-problem [:= "uncertain"]]
            [:next-attempt-at {:optional true} validation/Instant]]})


(defevent invitation-email-submitted
  {:schema [:map
            [:delivery-id :uuid]
            [:delivery-status [:= "submitted"]]
            [:submitted-at validation/Instant]
            ;; Absent: submission ends the reconciliation deadline.
            [:next-attempt-at {:optional true} validation/Instant]]})


(defevent invitation-email-failed
  {:schema [:map
            [:delivery-id :uuid]
            [:delivery-status [:enum "retrying" "exhausted" "failed" "unconfirmed"]]
            [:last-problem [:enum "temporary" "permanent" "uncertain"]]
            [:next-attempt-at {:optional true} validation/Instant]]})


(defprocessor ^{:malli/schema [:=> [:cat [:map [:delivery-id :uuid] [:workspace-id :uuid] [:requested-by :uuid] [:attempts :int]
                                          [:delivery-status {:optional true} :string]]]
                               core/ProcessorRequest]} invitation-delivery
  {:todo ::persistence/due-deliveries}
  [{:keys [delivery-id workspace-id requested-by attempts delivery-status]}]
  (if (= "unconfirmed" delivery-status)
    {:type  :command     :id    ::stop-unconfirmed-delivery                  :scope {:workspace-id workspace-id}
     :actor requested-by :input {:delivery-id delivery-id :attempt attempts}}
    {:type  :command     :id    ::send-invitation-email :scope {:workspace-id workspace-id}
     :actor requested-by :input {:delivery-id delivery-id :attempt (inc attempts)}}))


(def ^:private delivery-cofx
  [[:delivery ::invitations/deliveries (fn [_ input] (select-keys input [:delivery-id]))]])


(defn- current?
  "An outcome applies only to the attempt it reports, while that attempt is still unconfirmed."
  {:malli/schema [:=> [:cat [:maybe :map] :int] :boolean]}
  [delivery attempt]
  (boolean (and delivery (= (:attempts delivery) attempt) (= "unconfirmed" (:delivery-status delivery)))))


(defcommand send-invitation-email
  {:scope :workspace :policy ::delivery-service
   :input [:map [:delivery-id :uuid] [:attempt Attempt]]
   :cofx  [[:delivery ::invitations/deliveries (fn [_ input] (select-keys input [:delivery-id]))]
           [:invitation ::invitations/invitations
            (fn [{:keys [delivery]} _] {:invitation-id (:invitation-id delivery)})]
           [:workspace-name ::persistence/workspace-name (fn [_ _] {})]
           [:language ::persistence/recipient-language
            (fn [{:keys [invitation]} _] {:email (:email invitation)})]]
   :emits #{::invitation-delivery-attempted ::invitation-delivery-stopped}}
  [{:keys [now delivery invitation workspace-name language]} {:keys [delivery-id attempt]}]
  (cond
    (or (nil? delivery) (nil? invitation)
        (not (contains? #{"queued" "retrying"} (:delivery-status delivery)))
        (not= (:attempts delivery) (dec attempt)))
    {}

    (or (not= "pending" (:status invitation)) (not (.isBefore ^Instant now (:expires-at invitation))))
    {:events [{:type ::invitation-delivery-stopped :data {:delivery-id delivery-id :delivery-status "stopped"}}]}

    :else
    {:events [{:type ::invitation-delivery-attempted
               :data {:delivery-id     delivery-id :attempts attempt :delivery-status "unconfirmed" :attempted-at now
                      :next-attempt-at (.plus ^Instant now ^Duration reconciliation)}}]
     :fx     [{:id    ::email/send
               :input {:invitation-id  (:invitation-id invitation) :to   (:email invitation) :language   (or language "en")
                       :workspace-name workspace-name              :role (:role invitation)  :expires-at (:expires-at invitation)}
               :then  {:type :command :id ::record-email-submitted :input {:delivery-id delivery-id :attempt attempt}}
               :catch {:type :command :id ::record-email-failed :input {:delivery-id delivery-id :attempt attempt}}}]}))


(defcommand record-email-submitted
  {:scope :workspace :policy ::delivery-service
   :input [:map [:delivery-id :uuid] [:attempt Attempt] [:outcome {:optional true} [:map-of :keyword :any]]]
   :cofx  delivery-cofx
   :emits #{::invitation-email-submitted}}
  [{:keys [now delivery]} {:keys [delivery-id attempt]}]
  (if (current? delivery attempt)
    {:events [{:type ::invitation-email-submitted
               :data {:delivery-id delivery-id :delivery-status "submitted" :submitted-at now}}]}
    {}))


(defcommand record-email-failed
  {:scope :workspace :policy ::delivery-service
   :input [:map [:delivery-id :uuid] [:attempt Attempt] [:problem Problem]]
   :cofx  delivery-cofx
   :emits #{::invitation-email-failed}}
  [{:keys [now delivery]} {:keys [delivery-id attempt problem]}]
  (if-not (current? delivery attempt)
    {}
    (let [kind   (:type problem)
          status (case kind
                   :temporary (if (< attempt attempt-budget) "retrying" "exhausted")
                   :permanent "failed"
                   :uncertain "unconfirmed")
          ;; Uncertainty keeps the attempt's original reconciliation deadline.
          retry  (case status
                   "retrying" (.plus ^Instant now ^Duration (get retry-waits (dec attempt)))
                   "unconfirmed" (:next-attempt-at delivery)
                   nil)]
      {:events [{:type ::invitation-email-failed
                 :data (cond-> {:delivery-id delivery-id :delivery-status status :last-problem (name kind)}
                         retry (assoc :next-attempt-at retry))}]})))


;; After the reconciliation deadline an unresolved attempt stops its request: no further sends under it.
(defcommand stop-unconfirmed-delivery
  {:scope :workspace :policy ::delivery-service
   :input [:map [:delivery-id :uuid] [:attempt Attempt]]
   :cofx  delivery-cofx
   :emits #{::invitation-delivery-stopped-unconfirmed}}
  [{:keys [now delivery]} {:keys [delivery-id attempt]}]
  (if (and (current? delivery attempt) (some? (:next-attempt-at delivery))
           (not (.isBefore ^Instant now (:next-attempt-at delivery))))
    {:events [{:type ::invitation-delivery-stopped-unconfirmed
               :data {:delivery-id delivery-id :delivery-status "stopped" :last-problem "uncertain"}}]}
    {}))

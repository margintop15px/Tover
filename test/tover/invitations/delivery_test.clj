(ns tover.invitations.delivery-test
  "Board scenarios for the invitation delivery processor and its outcome commands."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.invitations.delivery.email :as email]
    [tover.invitations.delivery.model :as model]
    [tover.invitations.persistence :as persistence])
  (:import
    [java.time Duration Instant]))


(def workspace #uuid "00000000-0000-0000-0000-0000000000f1")
(def actor #uuid "00000000-0000-0000-0000-0000000000a1")
(def invitation-id #uuid "00000000-0000-0000-0000-0000000000c1")
(def delivery-id #uuid "00000000-0000-0000-0000-0000000000d1")
(def now (Instant/parse "2026-11-12T10:00:00Z"))
(def expires (.plus now (Duration/ofHours 100)))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.accounts.profile.model 'tover.workspaces.create.model
                        'tover.invitations.issue.model 'tover.invitations.delivery.model])
  (f))


(use-fixtures :each fixture/isolated-registry register)


(def delivery
  {:delivery-id  delivery-id :invitation-id   invitation-id :workspace-id workspace :requested-by actor
   :requested-at now         :delivery-status "queued"      :attempts     0})


(def invitation
  {:invitation-id invitation-id :workspace-id workspace :email      "ann@example.test" :role       "admin"
   :invited-by    actor         :status       "pending" :expires-at expires            :created-at now})


(defn decide
  {:malli/schema [:=> [:cat :qualified-keyword :map :map] :map]}
  [id facts input]
  (runtime/decide id (merge {:actor    actor    :now        now        :scope          {:workspace-id workspace}
                             :delivery delivery :invitation invitation :workspace-name "Acme"                    :language nil}
                            facts)
                  input))


(defn send!
  {:malli/schema [:=> [:cat :map :int] :map]}
  [facts attempt]
  (decide ::model/send-invitation-email facts {:delivery-id delivery-id :attempt attempt}))


(deftest processor-maps-due-row
  (is (= {:type  :command :id ::model/send-invitation-email :scope {:workspace-id workspace} :actor actor
          :input {:delivery-id delivery-id :attempt 3}}
         (model/invitation-delivery {:delivery-id delivery-id :workspace-id workspace :requested-by actor :attempts 2}))))


(deftest first-attempt-is-recorded-and-sends
  (let [{:keys [events fx]} (send! {} 1)]
    (is (= [{:type ::model/invitation-delivery-attempted
             :data {:delivery-id delivery-id :attempts 1 :delivery-status "unconfirmed" :attempted-at now}}]
           (mapv #(select-keys % [:type :data]) events)))
    (is (= [{:id    ::email/send
             :input {:invitation-id invitation-id :to         "ann@example.test" :language "en" :workspace-name "Acme"
                     :role          "admin"       :expires-at expires}
             :then  {:type :command :id ::model/record-email-submitted :input {:delivery-id delivery-id :attempt 1}}
             :catch {:type :command :id ::model/record-email-failed :input {:delivery-id delivery-id :attempt 1}}}]
           fx))))


(deftest recipient-language-is-used
  (is (= "ru" (get-in (send! {:language "ru"} 1) [:fx 0 :input :language]))))


(deftest clearing-events-omit-the-cleared-field
  (is (not (contains? (get-in (send! {} 1) [:events 0 :data]) :next-attempt-at)))
  (is (not (contains? (get-in (send! {:invitation (assoc invitation :status "revoked")} 1) [:events 0 :data]) :next-attempt-at)))
  (is (not (contains? (get-in (decide ::model/record-email-failed {:delivery (assoc delivery :attempts 1 :delivery-status "unconfirmed")}
                                      {:delivery-id delivery-id :attempt 1 :problem {:type :permanent :message "x"}})
                              [:events 0 :data])
                      :next-attempt-at))))


(deftest expired-offer-stops-delivery
  (let [{:keys [events fx]} (send! {:invitation (assoc invitation :expires-at now)} 1)]
    (is (= [{:type ::model/invitation-delivery-stopped :data {:delivery-id delivery-id :delivery-status "stopped"}}]
           (mapv #(select-keys % [:type :data]) events)))
    (is (empty? fx))))


(deftest cancelled-offer-stops-delivery
  (is (= ::model/invitation-delivery-stopped
         (get-in (send! {:invitation (assoc invitation :status "revoked")} 1) [:events 0 :type]))))


(deftest already-attempted-is-noop
  (is (= {:events [] :fx []} (select-keys (send! {:delivery (assoc delivery :attempts 1 :delivery-status "unconfirmed")} 1) [:events :fx])))
  (is (empty? (:events (send! {:delivery (assoc delivery :attempts 1 :delivery-status "retrying")} 1)))))


(deftest retrying-delivery-sends-next-attempt
  (is (= 2 (get-in (send! {:delivery (assoc delivery :attempts 1 :delivery-status "retrying")} 2) [:events 0 :data :attempts]))))


(def sent (assoc delivery :attempts 1 :delivery-status "unconfirmed"))


(defn submitted
  {:malli/schema [:=> [:cat :map :int] :map]}
  [d attempt]
  (decide ::model/record-email-submitted {:delivery d} {:delivery-id delivery-id :attempt attempt}))


(defn failed
  {:malli/schema [:=> [:cat :int :keyword] :map]}
  [attempt type]
  (decide ::model/record-email-failed {:delivery (assoc delivery :attempts attempt :delivery-status "unconfirmed")}
          {:delivery-id delivery-id :attempt attempt :problem {:type type :message "boom"}}))


(deftest submitted-records-only-current-attempt
  (is (= [{:type ::model/invitation-email-submitted
           :data {:delivery-id delivery-id :delivery-status "submitted" :submitted-at now}}]
         (mapv #(select-keys % [:type :data]) (:events (submitted sent 1))))))


(deftest stale-outcome-is-ignored
  (is (empty? (:events (submitted (assoc sent :attempts 2) 1))))
  (is (empty? (:events (decide ::model/record-email-failed {:delivery (assoc sent :attempts 2)}
                               {:delivery-id delivery-id :attempt 1 :problem {:type :temporary :message "x"}}))))
  (is (empty? (:events (submitted (assoc sent :delivery-status "submitted") 1)))))


(deftest temporary-failures-follow-schedule
  (doseq [[attempt minutes] [[1 1] [2 5] [3 30]]]
    (is (= [{:type ::model/invitation-email-failed
             :data {:delivery-id     delivery-id :delivery-status "retrying" :last-problem "temporary"
                    :next-attempt-at (.plus now (Duration/ofMinutes minutes))}}]
           (mapv #(select-keys % [:type :data]) (:events (failed attempt :temporary)))))))


(deftest fourth-temporary-failure-exhausts
  (is (= {:delivery-id delivery-id :delivery-status "exhausted" :last-problem "temporary"}
         (get-in (failed 4 :temporary) [:events 0 :data]))))


(deftest permanent-failure-stops
  (is (= {:delivery-id delivery-id :delivery-status "failed" :last-problem "permanent"}
         (get-in (failed 1 :permanent) [:events 0 :data]))))


(deftest uncertain-is-unconfirmed
  (is (= {:delivery-id delivery-id :delivery-status "unconfirmed" :last-problem "uncertain"}
         (get-in (failed 2 :uncertain) [:events 0 :data]))))


(def input
  {:invitation-id invitation-id :to "ann@example.test" :language "en" :workspace-name "Acme" :role "admin" :expires-at expires})


(deftest message-names-workspace-role-expiry-and-link
  (let [{:keys [to subject text html]} (email/message "https://app.test" input)]
    (is (= "ann@example.test" to))
    (is (str/includes? subject "Acme"))
    (is (str/includes? text "https://app.test/invitations/00000000-0000-0000-0000-0000000000c1"))
    (is (str/includes? html "https://app.test/invitations/00000000-0000-0000-0000-0000000000c1"))
    (is (str/includes? text "2026-11-16"))
    (is (str/includes? text "an admin"))))


(deftest russian-differs-and-nil-falls-back-to-english
  (let [en (email/message "https://app.test" input)]
    (is (not= (:subject en) (:subject (email/message "https://app.test" (assoc input :language "ru")))))
    (is (= en (email/message "https://app.test" (assoc input :language nil))))))


(deftest workspace-name-is-escaped-in-html
  (let [{:keys [html text]} (email/message "https://app.test" (assoc input :workspace-name "<b>"))]
    (is (str/includes? html "&lt;b&gt;"))
    (is (not (str/includes? html "<b>")))
    (is (str/includes? text "<b>"))))


(deftest send-effect-hands-message-to-mailer
  (let [calls (atom [])]
    (is (= {} (runtime/effect! ::email/send {:resources {:mailer #(swap! calls conj %) :origin "https://app.test"}} input)))
    (is (= "ann@example.test" (:to (first @calls))))))


(deftest persistence-coeffects-are-declared
  (is (every? #(contains? (methods runtime/coeffect) %)
              [::persistence/due-deliveries ::persistence/recipient-language ::persistence/workspace-name])))

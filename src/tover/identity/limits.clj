(ns tover.identity.limits
  "Tover's own limits on public identity requests: one confirmation and one recovery request per email in 60
  seconds, and failed sign-in attempts by email and network address (board `Resend Confirmation Email` and
  `Sign In`). They never depend on an account. Visitors are anonymous and nothing here is business history, so
  these are plain rows and not Bel commands. Emails are stored only as hashes."
  (:require
    [buddy.core.codecs :as codecs]
    [buddy.core.hash :as hash]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [next.jdbc.date-time :as date-time]
    [next.jdbc.result-set :as result-set])
  (:import
    [java.time Duration Instant]
    [java.util Locale]))


(date-time/read-as-instant)


(defprotocol Limits
  (^{:malli/schema [:=> [:cat :any [:enum :confirmation :recovery] :string] [:maybe :int]]}
    -start-email! [limits purpose email-key]
    "Start the 60 seconds of `purpose`: nil, or the remaining seconds.")
  (^{:malli/schema [:=> [:cat :any :string :string] [:map [:attempt-id {:optional true} :uuid] [:retry-after {:optional true} :int]]]}
    -start-attempt! [limits email-key address]
    "Reserve a sign-in attempt before the provider is asked: {:attempt-id id} or {:retry-after seconds}.")
  (^{:malli/schema [:=> [:cat :any :uuid [:enum :failed :succeeded :discarded]] :any]}
    -settle-attempt! [limits attempt-id outcome]
    "Record the outcome: :failed counts, :succeeded clears its email and address pair, :discarded never counts.")
  (^{:malli/schema [:=> [:cat :any :string] :any]}
    -clear-email! [limits email-key]
    "Clear every count of the email, but not the count of any network address."))


(def email-seconds
  60)


(def window-seconds
  (* 15 60))


(def pending-seconds
  "An attempt whose outcome was never recorded stops counting; provider calls end well before this."
  60)


(def Row
  [:map [:email-key :string] [:address :string] [:at inst?] [:status [:enum "pending" "failed"]]
   [:pair-cleared :boolean] [:email-cleared :boolean]])


(defn email-key
  {:malli/schema [:=> [:cat :string] :string]}
  [email]
  (codecs/bytes->hex (hash/sha256 (String/.toLowerCase (str/trim email) Locale/ROOT))))


(defn remaining
  "Whole seconds from `now` until `at` plus `seconds`, at least 1 while that is ahead; 0 once reached."
  {:malli/schema [:=> [:cat inst? inst? :int] :int]}
  [now at seconds]
  (let [millis (.toMillis (Duration/between now (Instant/.plusSeconds at seconds)))]
    (if (pos? millis) (quot (+ millis 999) 1000) 0)))


(defn retry-after
  "The longest wait of the limits reached by `rows` for this email and address, or nil."
  {:malli/schema [:=> [:cat inst? [:sequential Row] :string :string] [:maybe :int]]}
  [now rows email address]
  (let [email?   #(= email (:email-key %))
        address? #(= address (:address %))
        wait     (fn [limit counted?]
                   ;; The attempt is allowed once fewer than `limit` rows count, so after the limit-th longest wait.
                   (let [waits (->> rows
                                    (filter counted?)
                                    (map #(remaining now (:at %) (if (= "pending" (:status %)) pending-seconds window-seconds)))
                                    (filter pos?)
                                    (sort >))]
                     (nth waits (dec limit) nil)))]
    (some->> [(wait 10 #(and (email? %) (address? %) (not (:pair-cleared %)) (not (:email-cleared %))))
              (wait 100 #(and (email? %) (not (:email-cleared %))))
              (wait 100 address?)]
             (remove nil?)
             seq
             (apply max))))


(def ^:private columns
  {:builder-fn result-set/as-unqualified-kebab-maps})


(defrecord JdbcLimits [datasource]
  Limits
  (-start-email! [_ purpose email-key]
    (jdbc/with-transaction [tx datasource]
      (jdbc/execute-one! tx ["delete from app_private.identity_email_limits
                              where started_at < clock_timestamp() - make_interval(secs => ?) and email_key <> ?"
                             email-seconds email-key])
      (when-not (jdbc/execute-one!
                  tx ["insert into app_private.identity_email_limits(purpose,email_key) values(?,?)
                       on conflict(purpose,email_key) do update set started_at=excluded.started_at
                       where identity_email_limits.started_at <= clock_timestamp() - make_interval(secs => ?)
                       returning started_at" (name purpose) email-key email-seconds])
        (let [{:keys [started-at now]}
              (jdbc/execute-one! tx ["select started_at, clock_timestamp() as now from app_private.identity_email_limits
                                      where purpose=? and email_key=?" (name purpose) email-key] columns)]
          (max 1 (remaining now started-at email-seconds))))))

  (-start-attempt! [_ email-key address]
    (jdbc/with-transaction [tx datasource]
      ;; Attempts for one email or one address decide one after another; the sorted order avoids deadlocks.
      (doseq [lock (sort [(str "identity-email/" email-key) (str "identity-address/" address)])]
        (jdbc/execute-one! tx ["select pg_advisory_xact_lock(hashtextextended(?, 0))" lock]))
      (jdbc/execute-one! tx ["delete from app_private.identity_attempts where attempt_id in
                              (select attempt_id from app_private.identity_attempts
                               where at < clock_timestamp() - make_interval(secs => ?) for update skip locked)" window-seconds])
      (let [rows (jdbc/execute! tx ["select email_key,address,at,status,pair_cleared,email_cleared
                                     from app_private.identity_attempts where email_key=? or address=?" email-key address] columns)
            now  (:now (jdbc/execute-one! tx ["select clock_timestamp() as now"] columns))]
        (if-let [seconds (retry-after now rows email-key address)]
          {:retry-after seconds}
          (select-keys (jdbc/execute-one! tx ["insert into app_private.identity_attempts(email_key,address) values(?,?)
                                               returning attempt_id" email-key address] columns)
                       [:attempt-id])))))

  (-settle-attempt! [_ attempt-id outcome]
    (jdbc/with-transaction [tx datasource]
      (if (= :failed outcome)
        (jdbc/execute-one! tx ["update app_private.identity_attempts set status='failed' where attempt_id=?" attempt-id])
        (let [{:keys [email-key address]}
              (jdbc/execute-one! tx ["delete from app_private.identity_attempts where attempt_id=? returning email_key,address"
                                     attempt-id] columns)]
          (when (and email-key (= :succeeded outcome))
            (jdbc/execute-one! tx ["update app_private.identity_attempts set pair_cleared=true
                                    where email_key=? and address=? and status='failed'" email-key address]))))
      nil))

  (-clear-email! [_ email-key]
    (jdbc/execute-one! datasource ["update app_private.identity_attempts set email_cleared=true
                                    where email_key=? and status='failed'" email-key])
    nil))


(alter-meta! #'->JdbcLimits assoc :malli/schema [:=> [:cat :any] :map])
(alter-meta! #'map->JdbcLimits assoc :malli/schema [:=> [:cat :map] :map])


(defn jdbc-limits
  {:malli/schema [:=> [:cat [:map [:datasource :any]]] :map]}
  [{:keys [datasource]}]
  (->JdbcLimits datasource))

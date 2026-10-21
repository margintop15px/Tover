(ns tover.identity
  "Offline identity fixtures. This namespace is absent from the production classpath."
  (:require
    [clojure.string :as str]
    [tover.auth.core :as auth]
    [tover.workspaces.core :as workspaces])
  (:import
    [java.time Instant]
    [java.util UUID]))


(def ^:private timestamp
  "2026-01-01T00:00:00Z")


(defn- public-user
  {:malli/schema [:=> [:cat :map] auth/User]}
  [user]
  (select-keys user [:user-id :email :email-confirmed?]))


(defn- normalized-email
  {:malli/schema [:=> [:cat :string] :string]}
  [email]
  (str/lower-case (str/trim email)))


(defn- find-user
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [state email]
  (some #(when (= (normalized-email email) (:email %)) %) (vals (:users state))))


(defn- checked-user
  {:malli/schema [:=> [:cat :any :string] :map]}
  [state token]
  (or (get-in @state [:users (get-in @state [:sessions token :user-id])])
      (auth/fail! :unauthenticated 401 false)))


(defn- issue-tokens!
  {:malli/schema [:=> [:cat :any :uuid] auth/Tokens]}
  [state user-id]
  (let [access-token  (str (UUID/randomUUID))
        refresh-token (str (UUID/randomUUID))
        session       {:user-id user-id :refresh-token refresh-token}]
    (swap! state #(-> %
                      (assoc-in [:sessions access-token] session)
                      (assoc-in [:refresh refresh-token] access-token)))
    {:access-token  access-token
     :refresh-token refresh-token
     :expires-at    (+ 3600 (.getEpochSecond (Instant/now)))
     :user          (public-user (get-in @state [:users user-id]))}))


(defn- queue-email!
  {:malli/schema [:=> [:cat :any :keyword :map] :nil]}
  [state type input]
  (swap! state update :emails conj
         (assoc (select-keys input [:email :redirect-to])
                :email (normalized-email (:email input))
                :type type :token-hash (str (UUID/randomUUID))))
  nil)


(defn- membership!
  {:malli/schema [:=> [:cat :any :string :uuid :boolean] :map]}
  [state token workspace-id manager?]
  (let [user       (checked-user state token)
        membership (get-in @state [:memberships workspace-id (:user-id user)])]
    (when-not (and (= :active (:status membership))
                   (or (not manager?) (contains? #{:owner :admin} (:role membership))))
      (auth/fail! :forbidden 403 false))
    membership))


(defn- add-workspace!
  {:malli/schema [:=> [:cat :any :uuid :string] :uuid]}
  [state user-id name]
  (let [workspace-id (UUID/randomUUID)]
    (auth/check-input! [:string {:min 2}] (str/trim name))
    (swap! state #(-> %
                      (assoc-in [:workspaces workspace-id] {:name (str/trim name)})
                      (assoc-in [:memberships workspace-id user-id]
                                {:role :owner :status :active :created-at (str (Instant/now))})))
    workspace-id))


(defn- member-with-email
  {:malli/schema [:=> [:cat :map :uuid :string] [:maybe :map]]}
  [state workspace-id email]
  (when-let [user (find-user state email)]
    (get-in state [:memberships workspace-id (:user-id user)])))


(defn- manage-invitation
  {:malli/schema [:=> [:cat :any :string :map] :map]}
  [state token {:keys [workspace-id action invite-id email role] :or {role :member}}]
  (membership! state token workspace-id true)
  (locking state
    (let [previous (get-in @state [:invitations invite-id])
          _        (when (and (not= action :create)
                              (not= workspace-id (:workspace-id previous)))
                     (auth/fail! :not-found 404 false))
          email    (if (= action :create) (normalized-email (or email "")) (:email previous))
          role     (if (= action :create) role (:role previous))
          related  (filter #(and (= workspace-id (:workspace-id %)) (= email (:email %)))
                           (vals (:invitations @state)))
          pending  (filter #(contains? #{:pending :expired} (:status %)) related)
          now      (Instant/now)]
      (when-not (contains? #{:create :resend :cancel} action)
        (auth/fail! :invalid-input 400 false))
      (when (and (not= action :create)
                 (not (contains? #{:pending :expired} (:status previous)))
                 (not (and (= action :cancel) (= :revoked (:status previous)))))
        (auth/fail! :invite-changed 409 false))
      (if (= action :cancel)
        (do
          (doseq [invite pending] (swap! state assoc-in [:invitations (:id invite) :status] :revoked))
          {:ok true})
        (do
          (when-not (and (re-matches #"[^\s@]+@[^\s@]+\.[^\s@]+" email)
                         (<= (count email) 254) (contains? #{:admin :member} role))
            (auth/fail! :invalid-input 400 false))
          (when (contains? #{:active :suspended} (:status (member-with-email @state workspace-id email)))
            (auth/fail! :member-exists 409 false))
          (when (and (= action :create) (seq pending))
            (auth/fail! :invite-exists 409 false {:invite-id (:id (last (sort-by :created-at pending)))}))
          (when (some #(.isAfter (Instant/parse (:created-at %)) (.minusSeconds now 60)) related)
            (auth/fail! :rate-limited 429 false {:retry-after 60}))
          (let [id         (UUID/randomUUID)
                invitation {:id         id        :workspace-id workspace-id                    :email email :role role :status :pending
                            :created-at (str now) :expires-at   (str (.plusSeconds now 604800))}]
            (swap! state assoc-in [:invitations id] invitation)
            (select-keys invitation [:id :email :role])))))))


(defn fixtures
  "Return fresh offline services. All fixture accounts use password tover-development."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [workspace-id         (UUID/fromString "00000000-0000-4000-8000-000000000101")
        other-workspace-id   (UUID/fromString "00000000-0000-4000-8000-000000000102")
        users                (mapv (fn [id email]
                                     {:user-id  (UUID/fromString id) :email email :email-confirmed? true
                                      :password "tover-development"})
                                   ["00000000-0000-4000-8000-000000000001"
                                    "00000000-0000-4000-8000-000000000002"
                                    "00000000-0000-4000-8000-000000000003"]
                                   ["owner@example.test" "member@example.test" "other@example.test"])
        [owner member other] (map :user-id users)
        state                (atom {:users       (into {} (map (juxt :user-id identity)) users)
                                    :workspaces  {workspace-id       {:name "Development workspace"}
                                                  other-workspace-id {:name "Other workspace"}}
                                    :memberships {workspace-id       {owner  {:role :owner :status :active :created-at timestamp}
                                                                      member {:role :member :status :active :created-at timestamp}}
                                                  other-workspace-id {owner {:role :member :status :active :created-at timestamp}
                                                                      other {:role :owner :status :active :created-at timestamp}}}
                                    :sessions    {} :refresh {} :emails [] :invitations {}})
        provider
        (reify
          auth/Auth
          (sign-up! [_ {:keys [email password workspace-name] :as input}]
            (auth/check-input! [:map [:email :string] [:password [:string {:min 1}]]] input)
            (locking state
              (when (find-user @state email) (auth/fail! :email-exists 422 false))
              (let [user {:user-id          (UUID/randomUUID) :email    (normalized-email email)
                          :email-confirmed? false             :password password}]
                (swap! state assoc-in [:users (:user-id user)] user)
                (when-not (str/blank? workspace-name) (add-workspace! state (:user-id user) workspace-name))
                (queue-email! state :signup input)
                {:user (public-user user)})))
          (sign-in! [_ {:keys [email password]}]
            (let [user (find-user @state email)]
              (when-not (and user (= password (:password user)))
                (auth/fail! :invalid-credentials 400 false))
              (when-not (:email-confirmed? user) (auth/fail! :email-not-confirmed 400 false))
              (issue-tokens! state (:user-id user))))
          (current-user [_ token]
            (public-user (checked-user state token)))
          (refresh! [_ token]
            (locking state
              (let [access-token (get-in @state [:refresh token])
                    session      (get-in @state [:sessions access-token])]
                (when-not session (auth/fail! :invalid-refresh-token 400 false))
                (swap! state #(-> % (update :refresh dissoc token) (update :sessions dissoc access-token)))
                (issue-tokens! state (:user-id session)))))
          (sign-out! [_ token]
            (when-let [session (get-in @state [:sessions token])]
              (swap! state #(-> % (update :sessions dissoc token)
                                (update :refresh dissoc (:refresh-token session)))))
            nil)
          (verify-email! [_ {:keys [token-hash type]}]
            (locking state
              (let [email (some #(when (and (= token-hash (:token-hash %)) (= type (:type %))) %) (:emails @state))
                    user  (when email (find-user @state (:email email)))]
                (when-not user (auth/fail! :verification-expired 403 false))
                (swap! state #(-> %
                                  (update :emails (fn [emails] (filterv (fn [email] (not= token-hash (:token-hash email))) emails)))
                                  (assoc-in [:users (:user-id user) :email-confirmed?] true)))
                (issue-tokens! state (:user-id user)))))
          (recover! [_ input]
            (when (find-user @state (:email input)) (queue-email! state :recovery input))
            nil)
          (set-password! [_ token password]
            (let [user (checked-user state token)]
              (auth/check-input! [:string {:min 1}] password)
              (swap! state assoc-in [:users (:user-id user) :password] password)
              (public-user user)))
          (invite! [_ input]
            (locking state
              (when (find-user @state (:email input)) (auth/fail! :email-exists 422 false))
              (let [user {:user-id          (UUID/randomUUID) :email    (normalized-email (:email input))
                          :email-confirmed? false             :password nil}]
                (swap! state assoc-in [:users (:user-id user)] user)
                (queue-email! state :invite input))))
          (magic-link! [_ input]
            (when-not (find-user @state (:email input)) (auth/fail! :not-found 404 false))
            (queue-email! state :magiclink input))
          workspaces/Directory
          (list-memberships [_ token]
            (let [user-id (:user-id (checked-user state token))]
              (->> (:memberships @state)
                   (keep (fn [[workspace-id members]]
                           (when (= :active (get-in members [user-id :status]))
                             {:workspace-id workspace-id
                              :name         (get-in @state [:workspaces workspace-id :name])
                              :role         (get-in members [user-id :role])
                              :created-at   (get-in members [user-id :created-at])})))
                   (sort-by (juxt :created-at (comp str :workspace-id)))
                   vec)))
          (create-workspace! [_ token name]
            (add-workspace! state (:user-id (checked-user state token)) name))
          (team [_ token workspace-id]
            (membership! state token workspace-id true)
            {:members     (->> (get-in @state [:memberships workspace-id])
                               (map (fn [[user-id membership]]
                                      (merge {:user-id            user-id
                                              :email              (get-in @state [:users user-id :email])
                                              :name               nil
                                              :email-confirmed-at (when (get-in @state [:users user-id :email-confirmed?]) timestamp)
                                              :last-sign-in-at    nil :recovery-requested-at nil}
                                             (select-keys membership [:role :status :recovery-requested-at]))))
                               (sort-by :email) vec)
             :invitations (->> (vals (:invitations @state))
                               (filter #(and (= workspace-id (:workspace-id %)) (contains? #{:pending :expired} (:status %))))
                               (group-by :email)
                               vals
                               (map #(last (sort-by :created-at %)))
                               (sort-by :created-at) vec)})
          (manage-invitation! [_ token input]
            (manage-invitation state token input))
          (revoke-reservation! [_ token workspace-id invite-id]
            (membership! state token workspace-id true)
            (locking state
              (when-not (= workspace-id (get-in @state [:invitations invite-id :workspace-id]))
                (auth/fail! :not-found 404 false))
              (when (= :pending (get-in @state [:invitations invite-id :status]))
                (swap! state assoc-in [:invitations invite-id :status] :revoked)))
            nil)
          (request-member-recovery! [_ token workspace-id user-id]
            (membership! state token workspace-id true)
            (locking state
              (let [member (get-in @state [:memberships workspace-id user-id])
                    now    (Instant/now)]
                (when-not member (auth/fail! :not-found 404 false))
                (when-not (= :active (:status member)) (auth/fail! :member-inactive 409 false))
                (when (and (:recovery-requested-at member)
                           (.isAfter (Instant/parse (:recovery-requested-at member)) (.minusSeconds now 60)))
                  (auth/fail! :rate-limited 429 false {:retry-after 60}))
                (swap! state assoc-in [:memberships workspace-id user-id :recovery-requested-at] (str now))
                {:email (get-in @state [:users user-id :email])})))
          (accept-invites! [_ token]
            (let [user (checked-user state token)]
              (if-not (:email-confirmed? user)
                0
                (locking state
                  (reduce
                    (fn [count invite]
                      (let [path    [:memberships (:workspace-id invite) (:user-id user)]
                            current (get-in @state path)]
                        (if (contains? #{:active :suspended} (:status current))
                          (do (when (= :active (:status current))
                                (swap! state assoc-in [:invitations (:id invite) :status] :accepted))
                              (if (= :active (:status current)) (inc count) count))
                          (do (swap! state #(-> %
                                                (assoc-in path {:role (:role invite) :status :active :created-at (str (Instant/now))})
                                                (assoc-in [:invitations (:id invite) :status] :accepted)))
                              (inc count)))))
                    0
                    (->> (vals (:invitations @state))
                         (filter #(and (= (:email user) (:email %)) (= :pending (:status %))
                                       (.isAfter (Instant/parse (:expires-at %)) (Instant/now))))
                         (sort-by :created-at #(compare %2 %1)))))))))]
    {:auth               provider           :workspaces          provider           :users users :workspace-id workspace-id
     :other-workspace-id other-workspace-id :second-workspace-id other-workspace-id :state state}))

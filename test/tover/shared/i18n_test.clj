(ns tover.shared.i18n-test
  (:require
    [bel.handler :as html]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.auth.core :as auth]
    [tover.auth.supabase :as supabase]
    [tover.inventory.completion :as completion]
    [tover.inventory.filters :as filters]
    [tover.inventory.grid :as grid]
    [tover.inventory.smart :as smart]
    [tover.inventory.tables :as tables]
    [tover.inventory.timeline :as timeline]
    [tover.inventory.windows :as windows]
    [tover.operations.ui :as operations]
    [tover.shared.i18n :as i18n]
    [tover.support :as support]
    [tover.teams.core :as teams]
    [tover.workspaces.web :as workspaces]))


(deftest catalog-has-complete-russian-translations-and-matching-parameters
  (let [english (get i18n/messages "en") russian (get i18n/messages "ru")]
    (is (= (set (keys english)) (set (keys russian))))
    (is (> (count english) 400))
    (doseq [[key message] english]
      (let [translation (get russian key)
            variants    (if (map? translation) (vals translation) [translation])
            source      (if (map? message) (get message "other") message)]
        (doseq [variant variants]
          (is (not (str/blank? variant)) key)
          (is (or (re-find #"[А-Яа-яЁё]" variant)
                  (str/blank? (str/replace variant #"\{[\w-]+\}" ""))) key)
          (is (= (set (re-seq #"\{[\w-]+\}" source)) (set (re-seq #"\{[\w-]+\}" variant))) key))))))


(deftest locale-negotiation-and-plural-forms
  (doseq [[header expected] [[nil "en"] ["de,ru-RU;q=0.9,en;q=0.7" "ru"]
                             ["ru;q=0.2,en-US;q=0.8" "en"] ["ru;q=0,en" "en"]
                             ["RU-ru,en;q=0.5" "ru"] ["fr,*;q=0.5" "en"]]]
    (is (= expected (i18n/browser-locale header))))
  (let [request {:headers {"accept-language" "ru"} :cookies {"tover-language" {:value "en"}}}]
    (is (= "en" (i18n/resolve-locale request))))
  (is (= "ru" (i18n/resolve-locale {:tover/identity {:user {:locale "ru"}} :cookies {"tover-language" {:value "en"}}})))
  (is (= "en" (i18n/resolve-locale {:cookies {"tover-language" {:value "invalid"}}})))
  (doseq [[n expected] [[1 "1 товар"] [2 "2 товара"] [5 "5 товаров"] [11 "11 товаров"]
                        [21 "21 товар"] [22 "22 товара"] [25 "25 товаров"] [111 "111 товаров"]]]
    (is (= expected (i18n/t "ru" "{count} items" {:count n})))))


(deftest account-language-synchronizes-the-guest-cookie-without-overriding-a-new-choice
  (let [request  {:tover/identity {:user {:locale "ru"}} :cookies {"tover-language" {:value "en"}}}
        response ((i18n/wrap-locale (fn [_] {:status 200})) request)
        changed  ((i18n/wrap-locale (fn [_] {:status 303 :cookies {"tover-language" {:value "en"}}})) request)]
    (is (= "ru" (get-in response [:cookies "tover-language" :value])))
    (is (= "en" (get-in changed [:cookies "tover-language" :value])))))


(deftest all-operation-and-column-labels-translate-without-changing-data
  (let [entry   {:id      "entry" :kind :operation :type :production :occurred-at "2026-10-20T09:00:00Z"
                 :inputs  [{:item-id "a" :name "Purchase" :quantity 4 :unit "pcs" :location "Stock"}]
                 :outputs [{:item-id "b" :name "Return" :quantity 2 :unit "pack" :location "Operations"}]}
        record  {:entity-id "entry" :revision 1 :entry entry :actor "user" :actor-label "Admin"}
        project #(first (operations/rows [entry] [record]))
        english (binding [i18n/*locale* "en"] (project))
        russian (binding [i18n/*locale* "ru"] (project))]
    (is (= "Production" (:operation english)))
    (is (= "Производство" (:operation russian)))
    (is (= (:inputs english) (:inputs russian)))
    (is (= (:outputs english) (:outputs russian)))
    (is (= "Purchase" (get-in russian [:detail :sections 0 :rows 0 0])))
    (is (= "Admin" (:value (first (filter #(= "Submitted by" (:key %)) (get-in russian [:detail :metadata]))))))
    (is (str/includes? (binding [i18n/*locale* "ru"]
                         (html/render-html (timeline/operation-details (:detail russian)))) "Производство")))
  (binding [i18n/*locale* "ru"]
    (doseq [type [:purchase :return :transfer :production :defect :sale :write-off :payment]]
      (is (re-find #"[А-Яа-я]" (grid/label type))))
    (doseq [column (concat (filters/columns :stock) (filters/columns :operations)
                           (:columns (tables/stock [])) (:columns (tables/history {}))
                           (:columns (tables/operations [] {:column-keys (mapv :key grid/columns)})))]
      (is (or (= "" (:label column)) (re-find #"[А-Яа-я]" (:label column))) (str column)))
    (let [model (smart/model {} {:mode "types"} "" {} 1)]
      (is (= 8 (count (:options model)))))
    (let [model (smart/model {} {:mode "types"} "Закупка" {} 1)]
      (is (= ["purchase"] (mapv :type (:options model)))))
    (let [errors (:errors (completion/check {:kind :operation :type :payment}))]
      (is (seq errors))
      (is (every? #(re-find #"[А-Яа-я]" (:message %)) errors)))))


(deftest timeline-translates-every-operation-type-without-translating-goods
  (binding [i18n/*locale* "ru"]
    (doseq [[type expected] {:purchase "Закупка" :return "Возврат" :transfer  "Перемещение" :production "Производство"
                             :defect   "Брак"    :sale   "Продажа" :write-off "Списание"    :payment    "Платёж"}]
      (let [chart  (timeline/chart {:item        {:id "goods" :name "Purchase"}
                                    :items       {"goods" {:name "Purchase" :unit "pcs"}}
                                    :snapshot-at "2026-10-21T12:00:00Z"
                                    :records     [{:item-id     "goods"                :location "Stock"    :operation-id "operation" :operation-revision 1
                                                   :occurred-at "2026-10-20T12:00:00Z" :kind     :operation :type         type        :before             1 :after 2 :delta 1}]})
            markup (html/render-html chart)]
        (is (str/includes? markup expected) (name type))
        (is (str/includes? markup "Purchase"))))))


(deftest cached-windows-do-not-reuse-another-language
  (binding [windows/*cache* (atom {:queries {} :reads {}})]
    (let [context {:repo {} :user-id (random-uuid) :workspace-id (random-uuid)}
          build   (fn [] {:id "stock" :columns [] :rows [] :merges []})
          english (windows/initial! (assoc context :locale "en") {:queryKey "same" :generation 0 :build build})
          russian (windows/initial! (assoc context :locale "ru") {:queryKey "same" :generation 0 :build build})]
      (is (not= (get-in english [:viewport :queryId]) (get-in russian [:viewport :queryId]))))))


(deftest account-locale-uses-the-authenticated-user-endpoint
  (support/with-server
    (fn [_] {:body {:id            (str support/user-id) :email "user@example.test" :email_confirmed_at "2026-10-20T09:00:00Z"
                    :user_metadata {:locale "ru" :display_name "Existing name"}}})
    (fn [{:keys [url requests]}]
      (let [provider (supabase/auth {:auth-url url :api-key "public" :admin-key "private"})]
        (is (= "ru" (:locale (auth/set-locale! provider "user-token" "ru"))))
        (is (= {:locale "ru"} (get-in (first @requests) [:body :data]))))
      (is (= :put (:method (first @requests))))
      (is (= "/user" (:path (first @requests))))
      (is (= "Bearer user-token" (get-in (first @requests) [:headers "authorization"]))))))


(deftest guest-and-account-preferences-survive-requests
  (support/with-application
    (fn [{:keys [handler browser users]}]
      (let [request! (:request! browser)]
        (is (str/includes? (:body (request! :get "/auth/login" {} {:headers {"accept-language" "ru-RU"}})) "Войти"))
        (is (= 403 (:status (request! :post "/action/tover.auth.web/set-language" {"locale" "ru"} {:csrf? false}))))
        (is (= 303 (:status (support/login! browser (first users)))))
        (is (= 303 (:status (request! :post "/action/tover.auth.web/set-language" {"locale" "ru" "return-to" "/"}))))
        ;; Separate browser/session reads the same account preference.
        (let [other (support/browser handler)]
          (support/login! other (first users))
          (is (str/includes? (:body ((:request! other) :get "/workspace")) "Рабочие пространства")))
        (is (= 422 (:status (request! :post "/action/tover.auth.web/set-language" {"locale" "de"}))))))))


(deftest failed-account-save-retains-language-and-the-original-destination
  (support/with-application
    (fn [{:keys [auth] :as services}]
      (assoc services :auth (support/auth-provider auth {:set-locale (fn [_ _] (throw (ex-info "private provider detail" {})))})))
    (fn [{:keys [browser users]}]
      (support/login! browser (first users))
      (let [response ((:request! browser) :post "/action/tover.auth.web/set-language" {"locale" "ru" "return-to" "/workspace"})]
        (is (= 503 (:status response))))
      (let [body (:body ((:request! browser) :post "/action/tover.auth.web/set-language" {"locale" "ru" "return-to" "/workspace"}))]
        (is (str/includes? body "Could not save language. Please try again."))
        (is (str/includes? body "value=\"/workspace\""))
        (is (not (str/includes? body "private provider detail")))))))


(deftest immutable-shape-errors-and-email-templates-have-both-languages
  (doseq [[_ message] (re-seq #"sh:message \"([^\"]+)\"" (slurp (io/resource "inventory/shapes.ttl")))]
    (is (contains? (get i18n/messages "ru") message)))
  (doseq [profile ["local-identity" "production"] file ["confirmation" "invite" "recovery" "magic-link"]]
    (let [template (slurp (str "deployment/" profile "/templates/" file ".html"))]
      (is (str/includes? template "{{ if eq .Data.locale \"ru\" }}"))
      (is (str/includes? template "{{ .TokenHash }}"))
      (is (str/includes? template "/auth/confirm?token_hash="))))
  (is (= "Не удалось выполнить запрос."
         (binding [i18n/*locale* "ru"] (i18n/error-message (ex-info "private upstream body" {}))))))


(deftest specific-workspace-and-delivery-errors-survive-localization
  (doseq [locale ["en" "ru"]]
    (support/with-application
      (fn [{:keys [auth] :as services}]
        (assoc services :auth (support/auth-provider auth {:invite  (fn [_] (auth/fail! :unavailable 503 true))
                                                           :recover (fn [_] (auth/fail! :unavailable 503 false))})))
      (fn [{:keys [browser users]}]
        (let [request! (:request! browser)]
          (support/login! browser (first users))
          (request! :post "/action/tover.auth.web/set-language" {"locale" locale})
          (doseq [[route input message] [["/action/tover.workspaces.web/select-workspace"
                                          {"workspace-id" (str (random-uuid))} "This workspace is not accessible."]
                                         ["/action/tover.teams.core/invite"
                                          {"email" "uncertain@example.test" "role" "member"}
                                          "Email delivery is unconfirmed. The invitation is still pending."]
                                         ["/action/tover.teams.core/recovery"
                                          {"user-id" (str (:user-id (first users)))}
                                          "The service is unavailable. Please try again."]]]
            (let [response (request! :post route input)]
              (is (contains? #{403 503} (:status response)))
              (is (str/includes? (:body response) (i18n/t locale message {}))))))))))


(deftest team-statuses-empty-workspaces-and-provider-errors-use-the-selected-language
  (doseq [locale ["en" "ru"]]
    (binding [i18n/*locale* locale]
      (let [members          (for [role [:owner :admin :member] status [:active :suspended]]
                               {:email "Person" :role role :status status :user-id (random-uuid)})
            invitations      (for [status [:pending :expired :accepted :revoked]]
                               {:email "Recipient" :status status :id (random-uuid)})
            markup           (html/render-html (teams/team-content {} {:members members :invitations invitations}))
            empty-workspaces (html/render-html (workspaces/workspace-content {:tover/identity {:memberships []}}))]
        (doseq [key ["Owner" "Admin" "Member" "Active" "Suspended" "Pending" "Expired" "Accepted" "Revoked" "Resend" "Cancel"]]
          (is (str/includes? markup (i18n/t key)) key))
        (is (str/includes? empty-workspaces (i18n/t "Create workspace")))
        (doseq [[code key] i18n/identity-errors]
          (is (= (i18n/t key) (i18n/error-message (ex-info "Identity request failed." {:code code}))) (name code)))))))


(deftest smart-unit-labels-translate-without-changing-the-unit-value
  (doseq [locale ["en" "ru"]]
    (binding [i18n/*locale* locale]
      (let [entry  {:kind :operation :type :purchase :lines [{:unit "pcs"}]}
            model  (smart/model {:compositions [{:id "composition" :entry entry}]} {:mode "add" :id "composition"} "" {} 1)
            chip   (first (filter #(= "lines/0/unit" (:key %)) (:chips model)))
            typed  (smart/model {:compositions [{:id "composition" :entry entry}]}
                                {:mode "add" :id "composition" :field "lines/0/unit"} "pcs" {} 2)
            option (first (smart/field-values {:rows [{:unit "pcs"}]} {:key "lines/0/unit"}))]
        (is (= (i18n/t "pcs") (:value chip)))
        (is (= (i18n/t "pcs") (:label option)))
        (is (= "pcs" (:value option)))
        (is (= (i18n/t "pcs") (:label (first (:options typed)))))
        (is (= "pcs" (:value (first (:options typed)))))
        (is (= "pcs" (get-in entry [:lines 0 :unit])))))))

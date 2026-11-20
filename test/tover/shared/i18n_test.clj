(ns tover.shared.i18n-test
  (:require
    [bel.handler :as html]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.fixtures :as fixtures]
    [tover.shared.i18n :as i18n]
    [tover.support :as support]))


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
  (is (= "en" (i18n/resolve-locale {:tover/identity {:user {:locale "ru"} :password-required? true}
                                    :cookies        {"tover-language" {:value "en"}}})))
  (is (= "ru" (i18n/resolve-locale {:tover/identity {:user {:locale "ru"} :password-required? true}})))
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


(deftest guest-and-account-preferences-survive-requests
  (support/with-application
    (fn [{:keys [handler browser state] :as services}]
      (let [request! (:request! browser)
            guest    "/action/tover.identity.shell/set-language"
            action   "/action/tover.accounts.profile.web/set-language"]
        (is (str/includes? (:body (request! :get "/auth/login" {} {:headers {"accept-language" "ru-RU"}})) "Войти"))
        (is (= 403 (:status (request! :post guest {"locale" "ru"} {:csrf? false}))))
        (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id :uri "/workspace"})
        (is (= 303 (:status (request! :post action {"locale" "ru" "return-to" "/workspace"}))))
        (is (= "ru" (get-in @state [:db :profiles fixtures/owner-id :language])))
        ;; A separate browser/session reads the account language from the profile on its next request.
        (let [other (support/browser handler)]
          (support/sign-in! services other "owner@example.test" {:workspace-id fixtures/workspace-id})
          (is (str/includes? (:body ((:request! other) :get "/workspace")) "Рабочие пространства")))
        (is (= 422 (:status (request! :post action {"locale" "de"}))))))))


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


(deftest provider-errors-use-the-selected-language
  (doseq [locale ["en" "ru"]]
    (binding [i18n/*locale* locale]
      (doseq [[code key] i18n/identity-errors]
        (is (= (i18n/t key) (i18n/error-message (ex-info "Identity request failed." {:code code}))) (name code))))))



(ns tover.marketplace.connections-routes-test
  "Connection forms through the application's routes and error middleware, posted with the fields a browser sends."
  (:require
    [bel-rdf.adapter :as adapter]
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.persistence :as catalogues]
    [tover.catalogues.stores.model :as stores]
    [tover.fixtures :as fixtures]
    [tover.inventory.actors :as actors]
    [tover.invitations.fixtures :as invitations]
    [tover.marketplace.connections-web-test :as forms]
    [tover.marketplace.connections.model :as model]
    [tover.marketplace.connections.web :as web]
    [tover.marketplace.connector :as connector]
    [tover.shared.i18n :as i18n]
    [tover.support :as support]))


(use-fixtures :each bel-test/isolated-registry)


(defn with-connection
  "Calls `(f services ctx post!)` for a signed-in owner of a workspace whose connection c-1 is active."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (let [bel-system fixtures/bel-system]
    (with-redefs [fixtures/bel-system
                  (fn [{:keys [repo] :as services}]
                    (cond-> (bel-system services)
                      repo (assoc-in [:stores :catalogue]
                                     (adapter/store {:repo repo :graphs catalogues/graphs :guard catalogues/legacy-guard}))
                      repo (assoc-in [:resources :marketplace]
                                     (assoc (connector/connector {:repo repo :connector-key forms/key-text})
                                            :verify forms/fake-verify))))]
      (support/with-application
        (fn [{:keys [browser state] :as services}]
          (with-redefs [actors/labels (fixtures/actor-labels state)]
            (let [session (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
                  ctx     (runtime/prepare-context (fixtures/context services session))
                  post!   (fn [action form & [options]]
                            ((:request! browser) :post (bel/action-url action)
                                                 (merge {"command-id"   (str (random-uuid))         "connection-id" "c-1"
                                                         "workspace-id" (str fixtures/workspace-id)} form)
                                                 (or options {})))]
              (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
              (is (= 303 (:status (post! ::web/connect {"command-id" "connect-1" "provider" "ozon"
                                                        "client-id"  "123"       "api-key"  "good"}))))
              (f services ctx post!))))))))


(defn visible
  "The page without the message catalog every document embeds."
  {:malli/schema [:=> [:cat :map] :string]}
  [response]
  (str/replace (str (:body response)) #"<meta name=\"tover-messages\"[^>]*>" ""))


(deftest every-rejection-shows-the-connection
  (doseq [locale ["en" "ru"]]
    (with-connection
      (fn [_ ctx post!]
        (let [conflict "This connection changed. Reload the page."
              entered  "Check the entered fields."
              reject   (fn [status message action form]
                         (let [response (post! action form {:headers {"accept-language" locale}})
                               html     (visible response)]
                           (is (= status (:status response)) (str action " " message))
                           (is (get-in i18n/messages [locale message]) message)
                           (is (str/includes? html "data-connection-id=\"c-1\"") (str action " " message))
                           (is (str/includes? html (i18n/t locale message {})) (str action " " message))
                           (is (not (str/includes? (pr-str response) "wrong-key")))))]
          (reject 422 "Credentials rejected" ::web/replace-credentials {"client-id" "123" "api-key" "wrong-key"})
          (reject 422 entered ::web/replace-credentials {"client-id" "" "api-key" "wrong-key"})
          (reject 409 conflict ::web/replace-credentials {"command-id" "connect-1" "client-id" "123" "api-key" "wrong-key"})
          (doseq [action [::web/pause ::web/resume ::web/sync-now]]
            (reject 409 conflict action {"command-id" "connect-1"})
            (reject 422 entered action {"command-id" ""}))
          (runtime/execute! ctx ::model/pause-marketplace-connection {:command-id "p" :input {:connection-id "c-1"}})
          (reject 409 "Connection paused" ::web/sync-now {}))))))


(deftest a-rejection-without-a-connection-to-show-is-a-plain-client-error
  (with-connection
    (fn [_ _ post!]
      (doseq [[action form]       {::web/replace-credentials {"client-id" "123" "api-key" "wrong-key"}
                                   ::web/pause               {}
                                   ::web/resume              {}
                                   ::web/sync-now            {}}
              [id status message] (cons ["c-2" 404 "Unknown connection"]
                                        (map #(vector % 422 "The request could not be completed.") forms/unsafe-ids))
              :let                [response (post! action (assoc form "connection-id" id))]]
        (is (= [status "text/plain; charset=utf-8" message]
               [(:status response) (get-in response [:headers "content-type"]) (:body response)])
            (str action " " (pr-str id)))
        (is (not (str/includes? (pr-str response) "wrong-key")))))))


(deftest an-unknown-connection-page-is-not-found
  (with-connection
    (fn [{:keys [browser]} _ _]
      (is (= 404 (:status ((:request! browser) :get "/settings/marketplace/c-2")))))))


(deftest connections-live-on-the-settings-page
  (invitations/with-memory-coeffects
    #(with-connection
       (fn [{:keys [browser]} _ post!]
         (let [settings (visible ((:request! browser) :get "/settings"))]
           (is (str/includes? settings "id=\"marketplace-title\""))
           (is (str/includes? settings "href=\"/settings/marketplace/c-1\""))
           (is (not (str/includes? settings "href=\"/marketplace\""))))
         (testing "a rejected connect renders the settings page with the reason"
           (let [response (post! ::web/connect {"connection-id" "c-2" "provider" "ozon"
                                                "client-id"     "123" "api-key"  "good"})]
             (is (= 409 (:status response)))
             (is (str/includes? (visible response) "id=\"marketplace-title\""))))))))

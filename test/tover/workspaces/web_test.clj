(ns tover.workspaces.web-test
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.shared.i18n :as i18n]
    [tover.support :as support]
    [tover.workspaces.web :as web]))


(defn- elements
  {:malli/schema [:=> [:cat :any] [:sequential [:vector :any]]]}
  [hiccup]
  (filter #(and (vector? %) (keyword? (first %))) (tree-seq coll? seq hiccup)))


(defn- workspace-request
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [memberships]
  {:anti-forgery-token "csrf"
   :tover/identity     {:memberships memberships :auth-version (random-uuid)}})


(deftest create-workspace-disclosure-opens-for-empty-memberships-and-retained-input
  (let [membership {:workspace-id (random-uuid) :name "Studio" :role :owner}]
    (doseq [[memberships params expected-open expected-name]
            [[[] {} true ""]
             [[membership] {} false ""]
             [[membership] {:name ""} true ""]
             [[membership] {:name "Retained workspace"} true "Retained workspace"]]]
      (let [nodes      (elements (web/workspace-content (assoc (workspace-request memberships) :params params)))
            disclosure (first (filter #(= :details.management-disclosure (first %)) nodes))
            name-input (first (filter #(= "name" (get-in % [1 :name])) nodes))]
        (is (= expected-open (get-in disclosure [1 :open])))
        (is (= expected-name (get-in name-input [1 :value])))))
    (let [nodes   (elements (web/workspace-content (workspace-request [])))
          content (set (filter string? (tree-seq coll? seq nodes)))]
      (is (contains? content "No workspaces yet."))
      (is (contains? content "Create a workspace to get started."))
      (is (empty? (filter #(= :table.management-table (first %)) nodes))))))


(deftest workspace-rows-have-scoped-open-forms-and-current-marker
  (let [current-id  (random-uuid)
        other-id    (random-uuid)
        memberships [{:workspace-id current-id :name "Current studio" :role :owner}
                     {:workspace-id other-id :name "Other studio" :role :member}]
        request     (assoc-in (workspace-request memberships) [:tover/identity :workspace-id] current-id)
        nodes       (elements (web/workspace-content request))
        rows        (filter #(= :tr (first %)) nodes)
        forms       (filter #(= :form (first %)) nodes)
        open-forms  (filter #(= (bel/action-url :tover.workspaces.web/select-workspace) (get-in % [1 :action])) forms)]
    (is (= 3 (count rows)))
    (is (= 2 (count open-forms)))
    (is (= #{(str current-id) (str other-id)}
           (set (for [form  open-forms
                      input (elements form)
                      :when (= "workspace-id" (get-in input [1 :name]))]
                  (get-in input [1 :value])))))
    (is (= 1 (count (filter #(= :span.management-current (first %)) nodes))))
    (is (= "Current workspace" (last (first (filter #(= :span.management-current (first %)) nodes)))))
    (is (every? #(= "col" (get-in % [1 :scope])) (filter #(contains? #{:th :th.management-role} (first %)) nodes)))
    (doseq [form forms]
      (let [inputs (elements form)]
        (is (= "post" (get-in form [1 :method])))
        (is (= "csrf" (get-in (first (filter #(= "__anti-forgery-token" (get-in % [1 :name])) inputs)) [1 :value])))
        (is (= (str (get-in request [:tover/identity :auth-version]))
               (get-in (first (filter #(= "_tover-context" (get-in % [1 :name])) inputs)) [1 :value])))))
    (doseq [form (filter #(not= (bel/action-url :tover.auth.web/sign-out) (get-in % [1 :action])) forms)]
      (is (str/includes? (get-in form [1 :data-on:submit]) "retry: 'never'"))
      (is (str/includes? (get-in form [1 :data-on:submit]) "retryMaxCount: 0")))))


(deftest single-workspace-and-russian-management-labels
  (let [membership {:workspace-id (random-uuid) :name "Studio" :role :admin}
        request    (assoc-in (workspace-request [membership]) [:tover/identity :workspace-id] (:workspace-id membership))
        nodes      (binding [i18n/*locale* "ru"] (elements (web/workspace-content request)))
        labels     (set (filter string? (tree-seq coll? seq nodes)))]
    (is (= 1 (count (filter #(= :td.management-primary (first %)) nodes))))
    (is (contains? labels (i18n/t "ru" "Admin" {})))
    (is (contains? labels (i18n/t "ru" "Create workspace" {})))
    (is (contains? labels (i18n/t "ru" "Current workspace" {})))))


(deftest inaccessible-workspace-and-stale-team-form-cannot-retarget-mutations
  (support/with-application
    (fn [{:keys [browser users state other-workspace-id]}]
      (let [{:keys [request!]} browser]
        (support/login! browser (first users))
        (request! :get "/team")
        (let [old-context (:context @(:state browser))]
          (is (= 403 (:status (request! :post (bel/action-url :tover.workspaces.web/select-workspace) {"workspace-id" (str (random-uuid))}))))
          (is (= 303 (:status (request! :post (bel/action-url :tover.workspaces.web/select-workspace) {"workspace-id" (str other-workspace-id)}))))
          (let [response (request! :post "/action/tover.teams.core/invite" {"email" "stale@example.test" "role" "member" "_tover-context" old-context})]
            (is (= 409 (:status response)))
            (is (= "workspace-changed" (get-in response [:headers "x-tover-error"])))
            (is (empty? (:invitations @state)))))))))

(ns tover.invitations.delivery.email
  "The invitation email: localized text and HTML, and the effect that hands it to the mailer."
  (:require
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.shared.i18n :as i18n])
  (:import
    [java.time ZoneOffset]
    [java.time.format DateTimeFormatter]))


(def Input
  [:map
   [:invitation-id :uuid]
   [:to :string]
   [:language {:optional true} [:maybe :string]]
   [:workspace-name :string]
   [:role :string]
   [:expires-at [:fn #(instance? java.time.Instant %)]]])


(defn- escape
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (-> text (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))


(defn message
  {:malli/schema [:=> [:cat :string Input] [:map [:to :string] [:subject :string] [:text :string] [:html :string]]]}
  [origin {:keys [invitation-id to language workspace-name role expires-at]}]
  (binding [i18n/*locale* (or (i18n/supported language) "en")]
    (let [link    (str origin "/invitations/" invitation-id)
          expires (.format (.withZone DateTimeFormatter/ISO_LOCAL_DATE ZoneOffset/UTC) expires-at)
          role    (i18n/t (str "Invitation email role " role))
          params  (fn [workspace] {:workspace workspace :role role :link link :expires expires})
          body    (fn [workspace] (i18n/t "Invitation email body" (params workspace)))]
      {:to      to
       :subject (i18n/t "Invitation email subject" {:workspace workspace-name})
       :text    (body workspace-name)
       :html    (str "<p>"
                     (-> (escape (body workspace-name))
                         (str/replace (escape link) (str "<a href=\"" (escape link) "\">" (escape link) "</a>"))
                         (str/replace "\n" "<br>"))
                     "</p>")})))


(defmethod runtime/effect! ::send
  [_ {:keys [resources]} input]
  ((:mailer resources) (message (:origin resources) input))
  {})

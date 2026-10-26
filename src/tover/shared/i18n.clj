(ns tover.shared.i18n
  "Shared message catalog. Locale is bound only at request/recipient rendering boundaries."
  (:require
    [charred.api :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]))


(def messages
  (json/read-json (slurp (io/resource "i18n/messages.json"))))


(def ^:dynamic *locale*
  "en")


(defn supported
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [locale]
  (when (contains? #{"en" "ru"} locale) locale))


(defn browser-locale
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [header]
  (or (->> (str/split (or header "") #",")
           (keep-indexed
             (fn [index part]
               (let [[language & options] (str/split (str/trim part) #";")
                     quality              (try
                                            (if-let [q (some #(second (re-matches #"\s*q=([0-9.]+)\s*" %)) options)]
                                              (Double/parseDouble q) 1.0)
                                            (catch Exception _ 0.0))]
                 (when (and (pos? quality) (<= quality 1.0))
                   {:locale  (supported (first (str/split (str/lower-case language) #"-")))
                    :quality quality :index index}))))
           (filter :locale)
           (sort-by (juxt (comp - :quality) :index))
           first :locale)
      "en"))


(defn resolve-locale
  {:malli/schema [:=> [:cat :map] :string]}
  [request]
  (or (supported (get-in request [:tover/identity :user :locale]))
      (supported (get-in request [:cookies "tover-language" :value]))
      (browser-locale (get-in request [:headers "accept-language"]))))


(defn plural-category
  {:malli/schema [:=> [:cat :string :int] :string]}
  [locale n]
  (let [n (abs (long n))]
    (if (= locale "ru")
      (cond
        (and (= 1 (mod n 10)) (not= 11 (mod n 100))) "one"
        (and (<= 2 (mod n 10) 4) (not (<= 12 (mod n 100) 14))) "few"
        :else "many")
      (if (= n 1) "one" "other"))))


(defn t
  {:malli/schema [:function [:=> [:cat :string] :string] [:=> [:cat :string :map] :string] [:=> [:cat :string :string :map] :string]]}
  ([key] (t *locale* key {}))
  ([key parameters] (t *locale* key parameters))
  ([locale key parameters]
   (let [message (or (get-in messages [locale key]) (get-in messages ["en" key]) key)
         message (if (map? message)
                   (get message (plural-category locale (:count parameters)) (get message "other"))
                   message)]
     (str/replace message #"\{([\w-]+)\}"
                  (fn [[placeholder name]] (str (get parameters (keyword name) placeholder)))))))


(defn unit-label
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [unit]
  (if (= "pcs" unit) (t "pcs") unit))


(defn translate-labels
  "Translate only declared presentation fields in column/option definitions, never entered values."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [definitions]
  (mapv #(cond-> % (:label %) (update :label t)) definitions))


(defn catalog-meta
  {:malli/schema [:=> [:cat] [:vector :any]]}
  []
  [:meta {:name "tover-messages" :content (json/write-json-str (get messages *locale*))}])


(declare error-message)


(defn wrap-locale
  {:malli/schema [:=> [:cat [:fn ifn?]] [:fn ifn?]]}
  [handler]
  (fn [request]
    (let [locale (resolve-locale request)]
      (binding [*locale* locale]
        (let [response (handler (assoc request :tover/locale locale
                                       :bel/translate-title #(t locale % {})
                                       :bel/error-message (fn [error] (binding [*locale* locale] (error-message error)))))]
          (cond-> (update response :headers #(merge {"x-tover-language" locale} %))
            (and (supported (get-in request [:tover/identity :user :locale]))
                 (not= locale (get-in request [:cookies "tover-language" :value]))
                 (not (get-in response [:cookies "tover-language"])))
            (assoc-in [:cookies "tover-language"]
                      {:value  locale :path "/" :max-age 31536000 :http-only true :same-site :lax
                       :secure (= :https (:scheme request))})))))))



(def identity-errors
  {:weak-password         "Choose a stronger password."
   :same-password         "Choose a different password."
   :verification-invalid  "This link has expired or has already been used."
   :invalid-refresh-token "Please sign in again."
   :unauthenticated       "Please sign in again."
   :forbidden             "You do not have permission to do this."
   :invite-changed        "This invitation changed. Reload the page."
   :invite-exists         "An invitation already exists for this email."
   :member-exists         "This user is already a workspace member."
   :invalid-credentials   "Invalid email or password."
   :email-not-confirmed   "Confirm your email before signing in."
   :email-exists          "An account with this email already exists."
   :verification-expired  "This link has expired or has already been used."
   :rate-limited          "Too many requests. Please try again later."
   :invalid-input         "Check the entered fields."
   :invalid-response      "The service is unavailable. Please try again."
   :unavailable           "The service is unavailable. Please try again."
   :delivery-failed       "The email could not be sent. Please try again."
   :member-inactive       "This member is inactive."
   :not-found             "The requested record was not found."})


(defn error-message
  {:malli/schema [:=> [:cat [:or :map [:fn #(instance? Throwable %)]]] :string]}
  [error]
  (let [{:keys [code message-key message-params locale]} (if (map? error) error (ex-data error))
        raw (if (map? error) (:message error) (.getMessage ^Throwable error))
        message (or message-key
                    (when (and (not= raw "Identity request failed.") (contains? (get messages "en") raw)) raw)
                    (get identity-errors code)
                    "The request could not be completed.")]
    (t (or (supported locale) *locale*) message (or message-params {}))))

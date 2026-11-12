(ns tover.shared.email
  "SMTP mailer and send-outcome classification. Messages carry recipient data,
  so nothing here puts addresses or bodies into exception messages or data."
  (:import
    [jakarta.mail Authenticator AuthenticationFailedException Message$RecipientType MessagingException
     PasswordAuthentication Session Transport]
    [jakarta.mail.internet InternetAddress MimeBodyPart MimeMessage MimeMultipart]
    [org.eclipse.angus.mail.smtp SMTPAddressFailedException SMTPSendFailedException]
    [org.eclipse.angus.mail.util MailConnectException]))


(def Outcome [:enum :temporary :permanent :uncertain])


(def Config
  [:map
   [:host :string]
   [:port :int]
   [:user {:optional true} [:maybe :string]]
   [:password {:optional true} [:maybe :string]]
   [:from :string]
   [:sender-name {:optional true} [:maybe :string]]
   [:starttls? {:optional true} :boolean]])


(def Message
  [:map [:to :string] [:subject :string] [:text :string] [:html :string]])


(defn- smtp-code
  "Return code of the first SMTP failure in the next-exception chain, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :int]]}
  [^Throwable e]
  (loop [x e, n 0]
    (cond
      (or (nil? x) (> n 20)) nil
      (instance? SMTPAddressFailedException x) (.getReturnCode ^SMTPAddressFailedException x)
      (instance? SMTPSendFailedException x) (.getReturnCode ^SMTPSendFailedException x)
      (instance? MessagingException x) (recur (.getNextException ^MessagingException x) (inc n))
      :else nil)))


(defn classify
  "Classify a send failure. Connection and authentication failures mean nothing
  was submitted; anything unrecognised is :uncertain."
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)]] Outcome]}
  [^Throwable e]
  (let [rc (smtp-code e)]
    (cond
      (and rc (<= 500 rc 599)) :permanent
      (and rc (<= 400 rc 499)) :temporary
      (or (instance? MailConnectException e)
          (instance? AuthenticationFailedException e)) :temporary
      :else :uncertain)))


(defn- session
  {:malli/schema [:=> [:cat :map] :any]}
  [{:keys [host port user password starttls?]}]
  (let [props (doto (java.util.Properties.)
                (.put "mail.smtp.host" host)
                (.put "mail.smtp.port" (str port))
                (.put "mail.smtp.connectiontimeout" "10000")
                (.put "mail.smtp.timeout" "10000")
                (.put "mail.smtp.writetimeout" "10000")
                (.put "mail.smtp.starttls.enable" (str (boolean starttls?)))
                (.put "mail.smtp.starttls.required" (str (boolean starttls?))))]
    (if user
      (do (.put props "mail.smtp.auth" "true")
          (Session/getInstance props (proxy [Authenticator] []
                                       (getPasswordAuthentication []
                                         (PasswordAuthentication. user (or password ""))))))
      (Session/getInstance props))))


(defn- build
  {:malli/schema [:=> [:cat :any :map :map] :any]}
  [^Session session {:keys [from sender-name]} {:keys [to subject text html]}]
  (let [alt (doto (MimeMultipart. "alternative")
              (.addBodyPart (doto (MimeBodyPart.) (.setText text "UTF-8" "plain")))
              (.addBodyPart (doto (MimeBodyPart.) (.setText html "UTF-8" "html"))))]
    (doto (MimeMessage. session)
      (.setFrom (if sender-name (InternetAddress. ^String from ^String sender-name "UTF-8") (InternetAddress. ^String from)))
      (.setRecipient Message$RecipientType/TO (InternetAddress. ^String to))
      (.setSubject subject "UTF-8")
      (.setContent alt)
      (.saveChanges))))


(defn smtp-mailer
  "Return a function that sends one multipart/alternative message. It returns {}
  or throws ex-info {:type outcome} with the original exception as cause."
  {:malli/schema [:=> [:cat Config] [:=> [:cat Message] :map]]}
  [config]
  (let [sess (session config)]
    (fn [message]
      (try
        (Transport/send (build sess config message))
        {}
        (catch Exception e
          (throw (ex-info "SMTP send failed" {:type (classify e)} e)))))))

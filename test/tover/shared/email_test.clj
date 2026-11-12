(ns tover.shared.email-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.shared.email :as email])
  (:import
    [jakarta.mail Address AuthenticationFailedException SendFailedException]
    [jakarta.mail.internet InternetAddress]
    [org.eclipse.angus.mail.smtp SMTPAddressFailedException SMTPSendFailedException]
    [org.eclipse.angus.mail.util MailConnectException SocketConnectException]))


(defn- addr-failed
  {:malli/schema [:=> [:cat :int] :any]}
  [rc]
  (SMTPAddressFailedException. (InternetAddress. "a@example.test") "RCPT" (int rc) "fail"))


(defn- send-failed
  {:malli/schema [:=> [:cat :int] :any]}
  [rc]
  (SMTPSendFailedException. "DATA" (int rc) "fail" nil
                            (into-array Address []) (into-array Address []) (into-array Address [])))


(deftest classify-smtp-codes
  (testing "permanent"
    (is (= :permanent (email/classify (addr-failed 550))))
    (is (= :permanent (email/classify (send-failed 554)))))
  (testing "temporary"
    (is (= :temporary (email/classify (addr-failed 450))))
    (is (= :temporary (email/classify (send-failed 451))))))


(deftest classify-connection-and-auth
  (is (= :temporary (email/classify (MailConnectException. (SocketConnectException. "x" (java.io.IOException. "io") "h" 25 1000)))))
  (is (= :temporary (email/classify (AuthenticationFailedException. "bad")))))


(deftest classify-uncertain
  (is (= :uncertain (email/classify (java.net.SocketTimeoutException. "t"))))
  (is (= :uncertain (email/classify (RuntimeException. "x"))))
  (is (= :uncertain (email/classify (SendFailedException. "no code")))))


(deftest classify-wrapped
  (testing "first SMTP exception in the next-exception chain decides"
    (let [e (SendFailedException. "wrapped")]
      (.setNextException e (addr-failed 550))
      (is (= :permanent (email/classify e)))))
  (testing "SMTP code outside 4xx/5xx is uncertain"
    (is (= :uncertain (email/classify (addr-failed 250))))))

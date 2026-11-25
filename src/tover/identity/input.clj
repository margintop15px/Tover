(ns tover.identity.input
  "Email and password requirements checked by Tover before the identity provider is asked (board
  `Registration Requirements`), so a refused field is the same for every account and starts no limit."
  (:require
    [tover.shared.i18n :as i18n])
  (:import
    [java.nio.charset StandardCharsets]))


(def ^:private email-pattern
  #"[^@\s\p{Cntrl}]+@[^@\s\p{Cntrl}]+")


(defn email-error
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [email]
  (when-not (and (<= (count email) 255) (re-matches email-pattern email))
    (i18n/t "Enter a valid email address.")))


(defn new-password-error
  "At least 6 characters and at most 72 UTF-8 bytes. Sign In does not apply this."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [^String password]
  (cond
    (< (.codePointCount password 0 (count password)) 6) (i18n/t "Enter a password of at least 6 characters.")
    (> (alength (.getBytes password StandardCharsets/UTF_8)) 72) (i18n/t "This password is too long. Use a shorter one.")))

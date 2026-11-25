(ns tover.identity.input-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.identity.input :as input]))


(deftest email-addresses
  (doseq [email ["a@b" "Alice+x@example.test" "a.b@c-d.example"]]
    (is (nil? (input/email-error email)) email))
  (doseq [email ["" "a" "a@" "@b" "a b@c.d" "a@b@c" "a@b\nc" (str (apply str (repeat 250 "a")) "@b.test")]]
    (is (= "Enter a valid email address." (input/email-error email)) email)))


(deftest new-passwords
  (is (= "Enter a password of at least 6 characters." (input/new-password-error "12345")))
  (is (= "Enter a password of at least 6 characters." (input/new-password-error "😀😀😀")))
  (is (nil? (input/new-password-error "123456")))
  (testing "72 bytes are 36 Cyrillic letters"
    (is (nil? (input/new-password-error (apply str (repeat 36 "я")))))
    (is (= "This password is too long. Use a shorter one." (input/new-password-error (apply str (repeat 37 "я")))))))

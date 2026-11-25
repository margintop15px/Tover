(ns tover.shared.web.address-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.shared.web.address :as address]))


(defn- visitor
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string] :boolean] :string]}
  [forwarded remote trusted?]
  (address/visitor {:headers (cond-> {} forwarded (assoc "x-forwarded-for" forwarded)) :remote-addr remote} trusted?))


(deftest the-proxy-entry-is-the-last
  (is (= "192.0.2.1" (visitor "192.0.2.1" "172.18.0.2" true)))
  (is (= "192.0.2.1" (visitor "203.0.113.9, 192.0.2.1" "172.18.0.2" true))))


(deftest the-header-is-ignored-without-our-proxy
  (is (= "172.18.0.2" (visitor "192.0.2.1" "172.18.0.2" false))))


(deftest only-literals-count
  (doseq [value ["dead.beef" "example.test" "" "," "999.1.1.1" "fe80::1%eth0" ":::" "1.2.3"]]
    (is (= "172.18.0.2" (visitor value "172.18.0.2" true)) value))
  (is (= "unknown" (visitor nil nil true)))
  (is (= "unknown" (visitor nil "" false))))


(deftest ipv6-counts-by-network
  (testing "one /64"
    (is (= (visitor "2001:db8:1:2::1" nil true) (visitor "2001:db8:1:2:ffff:ffff:ffff:ffff" nil true))))
  (testing "another /64"
    (is (not= (visitor "2001:db8:1:2::1" nil true) (visitor "2001:db8:1:3::1" nil true))))
  (is (= "192.0.2.1" (visitor "::ffff:192.0.2.1" nil true))))

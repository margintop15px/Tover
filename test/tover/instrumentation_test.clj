(ns tover.instrumentation-test
  (:require [bel.fixture :as fixture]
            [clojure.test :refer [deftest is]]
            [instrumentation :as instrumentation]
            [malli.core :as m]
            [tover.stock.ui :as ui]))


(deftest every-application-function-has-an-active-schema
  (is (some #(= 'bel.core (-> % meta :ns ns-name)) (instrumentation/function-vars)))
  (is (some #(= 'bel.handler (-> % meta :ns ns-name)) (instrumentation/function-vars)))
  (is (some #(= 'tover.stock.web (-> % meta :ns ns-name)) (instrumentation/function-vars)))
  (is (not (contains? (set (instrumentation/function-vars)) #'fixture/row)))
  (doseq [var (instrumentation/function-vars)]
    (let [{:keys [ns name]} (meta var)]
      (is (:malli/schema (meta var)) (str var))
      (is (get-in (m/function-schemas) [(ns-name ns) name]) (str var))
      (is (:malli.instrument/original (meta @var)) (str var))))
  (is (thrown? clojure.lang.ExceptionInfo (ui/stock-review {:proposal "not-a-proposal"}))))


(deftest instrumentation-restarts-after-namespace-and-var-removal
  (let [deleted  (create-ns 'instrumentation.deleted-fixture)
        retained (create-ns 'instrumentation.retained-fixture)
        schema   [:=> [:cat :string] :string]]
    (try
      (intern deleted 'removed identity)
      (intern retained 'removed identity)
      (intern retained 'explicit identity)
      (m/-register-function-schema! (ns-name deleted) 'removed schema {})
      (m/-register-function-schema! (ns-name retained) 'removed schema {})
      (m/-register-function-schema! (ns-name retained) 'explicit schema {:scope #{:input}})
      (remove-ns (ns-name deleted))
      (ns-unmap retained 'removed)
      (is (pos? (instrumentation/start!)))
      (is (not (contains? (m/function-schemas) 'instrumentation.deleted-fixture)))
      (is (nil? (get-in (m/function-schemas) ['instrumentation.retained-fixture 'removed])))
      (is (= schema (m/form (:schema (get-in (m/function-schemas) ['instrumentation.retained-fixture 'explicit])))))
      (is (= #{:input} (:scope (get-in (m/function-schemas) ['instrumentation.retained-fixture 'explicit]))))
      (is (thrown? clojure.lang.ExceptionInfo (ui/stock-review {:proposal "not-a-proposal"})))
      (finally
        (remove-ns (ns-name deleted))
        (remove-ns (ns-name retained))
        (instrumentation/start!)))))

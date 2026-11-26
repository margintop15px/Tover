(ns tover.marketplace.proposals-test
  "Board chapter 45 slice 2 · Propose the imported operation: which source evidence becomes which pending entry,
  resolved through confirmed mappings and baselines, with the facts still missing."
  (:require
    [clojure.test :refer [are deftest is testing]]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.sync.observations :as observations]))


(def connection "c1")


(def listings
  [{:external-product-id "c1:mug-red" :connection-id connection
    :identifiers         [{:type "product-id" :value "mug-red" :active true} {:type "article" :value "MUG-R" :active true}
                          {:type "sku" :value "100" :role "primary" :active true}]}
   {:external-product-id "c1:mug-blue" :connection-id connection
    :identifiers         (observations/write-json [{:type "product-id" :value "mug-blue" :active true}
                                                   {:type "article" :value "MUG-B" :active true}
                                                   {:type "sku" :value "200" :role "primary" :active true}])}
   {:external-product-id "c1:unmatched" :connection-id connection
    :identifiers         [{:type "sku" :value "300" :active true}]}
   ;; Another account's listing with the same SKU never resolves this connection's lines.
   {:external-product-id "c2:mug-red" :connection-id "c2" :identifiers [{:type "sku" :value "100" :active true}]}])


(def mappings
  {"c1:mug-red"  {:external-product-id "c1:mug-red" :connection-id connection :stock-item-id "red" :state "confirmed" :mapping-revision 1}
   "c1:mug-blue" {:external-product-id "c1:mug-blue" :connection-id connection :stock-item-id "blue" :state "confirmed" :mapping-revision 1}})


(def warehouse-mappings
  {"c1:fbs:3"           {:external-warehouse-id "c1:fbs:3" :connection-id connection :warehouse-type "fbs" :warehouse-id "home" :state "confirmed"}
   "c1:fbo:7"           {:external-warehouse-id "c1:fbo:7" :connection-id connection :warehouse-type "fbo" :warehouse-id "ozon-7" :state "confirmed"}
   "c1:supply-origin:9" {:external-warehouse-id "c1:supply-origin:9" :connection-id connection :warehouse-type "supply-origin" :warehouse-id "home" :state "confirmed"}})


(defn- baseline
  {:malli/schema [:=> [:cat :string :string] :map]}
  [item warehouse]
  {:baseline-id (str connection "|" item "|" warehouse) :connection-id connection :stock-item-id item :warehouse-id warehouse
   :instant     "2026-11-01T00:00:00Z"                  :record-id     (str "opening-" item "-" warehouse) :established-at "2026-11-01T00:00:00Z"})


(def baselines
  (into {} (map (juxt :baseline-id identity))
        (for [item ["red" "blue"] warehouse ["home" "ozon-7"]] (baseline item warehouse))))


(def handed-over-posting
  {:source-operation-id "c1:fbs-posting:P1" :connection-id connection             :kind           "fbs-posting"          :status         "delivered" :terminal true
   :complete            true :occurred-at                  "2026-11-10T09:00:00Z" :handed-over-at "2026-11-10T09:00:00Z" :from-warehouse "c1:fbs:3"
   :lines               (observations/write-json [{:line-key "1"     :identifiers    [{:type "sku" :value "100"}] :quantity "2"
                                                   :price    "99.90" :price-currency "RUB"}])})


(def fbo-posting
  {:source-operation-id "c1:fbo-posting:F1" :connection-id connection             :kind           "fbo-posting" :status "delivered" :terminal true
   :complete            true :occurred-at                  "2026-11-05T09:00:00Z" :from-warehouse "c1:fbo:7"
   :lines               (observations/write-json [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "1"}])})


(def operations
  (into {} (map (juxt :source-operation-id identity)) [handed-over-posting fbo-posting]))


(def facts
  {:connection         {:connection-id connection}
   :products           listings
   :mappings           mappings
   :warehouse-mappings warehouse-mappings
   :baselines          baselines
   :operations         operations
   :currency           "RUB"})


(defn- proposal
  {:malli/schema [:function [:=> [:cat :map] :any] [:=> [:cat :map :map] :any]]}
  ([op] (proposal facts op))
  ([facts op] (proposals/proposal facts op)))


(defn- one-line
  {:malli/schema [:=> [:cat :map] :string]}
  [line]
  (observations/write-json [(merge {:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "1"} line)]))


(defn- return
  {:malli/schema [:=> [:cat :map] :map]}
  [m]
  (merge {:source-operation-id "c1:return:R1"         :connection-id connection :kind "return" :terminal true :complete false
          :occurred-at         "2026-11-12T10:00:00Z" :missing       (observations/write-json ["receiving warehouse"])
          :lines               (one-line {})}
         m))


(def fbo-return
  (return {:status "ReturnedToOzon" :return-schema "fbo" :related-operation "c1:fbo-posting:F1"}))


(def fbs-return
  (return {:source-operation-id "c1:return:R2" :status "ReturnedToOzon" :return-schema "fbs" :related-operation "c1:fbs-posting:P1"}))


(def rfbs-return
  (return {:source-operation-id "c1:rfbs-return:R3" :kind "rfbs-return" :status "ReceivedBySeller" :related-operation "c1:fbs-posting:P1"
           :occurred-at         nil :missing              (observations/write-json ["actual time" "line quantity" "receiving warehouse"])
           :lines               (observations/write-json [{:line-key "1" :identifiers [{:type "sku" :value "100"}]}])}))


(def supply
  {:source-operation-id "c1:supply:S1" :connection-id connection :kind "supply" :status "COMPLETED" :terminal true :complete false
   :missing             (observations/write-json ["actual time"]) :from-warehouse "c1:supply-origin:9" :to-warehouse "c1:fbo:7"
   :lines               (observations/write-json [{:line-key  "1"        :identifiers [{:type "sku" :value "100"}] :quantity "5"
                                                   :warehouse "c1:fbo:7"}])})


(defn- removal
  {:malli/schema [:=> [:cat :boolean] :map]}
  [disposed]
  {:source-operation-id (str "c1:removal:from-stock/7/" (if disposed "disposed" "given")) :connection-id connection :kind "removal"
   :status              "completed" :terminal true :complete false :disposed disposed :occurred-at "2026-11-15T00:00:00Z"
   :missing             (observations/write-json ["warehouse"]) :lines (one-line {:quantity "4"})})


(def removal-given-out (removal false))
(def removal-disposed (removal true))


(def cancelled-before-handover
  (merge handed-over-posting {:source-operation-id "c1:fbs-posting:P9" :status "cancelled" :occurred-at nil :handed-over-at nil}))


(deftest kind-by-evidence
  (are [op kind] (= kind (get-in (proposal op) [:entry :kind]))
    handed-over-posting "sale" fbo-return "customer-return"
    fbs-return "customer-return" rfbs-return "customer-return"
    supply "transfer" removal-given-out "transfer"
    removal-disposed "write-off" cancelled-before-handover nil)
  (testing "nothing for a return only initiated or a status update"
    (is (nil? (proposal (return {:status "OnSellerApproval" :terminal false :return-schema "fbs"}))))
    (is (nil? (proposal (assoc handed-over-posting :status "awaiting_packaging" :terminal false :occurred-at nil :handed-over-at nil))))
    (is (nil? (proposal (assoc supply :status "IN_TRANSIT" :terminal false))))
    (is (nil? (proposal (assoc removal-given-out :status "requested" :occurred-at nil)))))
  (testing "a cancelled posting that was handed over stays a Sale: only a receipt puts stock back"
    (is (= "sale" (get-in (proposal (assoc handed-over-posting :status "cancelled")) [:entry :kind])))))


(deftest sale-from-the-mapped-warehouse-at-the-handover-time
  (is (= {:entry   {:kind  "sale" :occurred-at "2026-11-10T09:00:00Z"
                    :lines [{:line-id "c1:fbs-posting:P1/1" :stock-item-id "red" :quantity 2 :warehouse-id "home" :unit-price "99.90"}]}
          :missing []}
         (proposal handed-over-posting))))


(deftest fbs-return-prefills-the-postings-warehouse
  (let [{:keys [entry missing]} (proposal fbs-return)]
    (is (= ["home"] (map :warehouse-id (:lines entry))))
    (is (= [] missing) "still missing nothing else")))


(deftest fbo-return-received-by-ozon
  (testing "an FBO return received by Ozon whose sale left warehouse W is prefilled with W"
    (let [{:keys [entry missing]} (proposal fbo-return)]
      (is (= "customer-return" (:kind entry)))
      (is (= ["ozon-7"] (map :warehouse-id (:lines entry))))
      (is (= [] missing)))))


(deftest fbo-return-received-by-the-seller
  (let [received (assoc fbo-return :status "ReceivedBySeller")]
    (testing "the connection's only mapped seller warehouse"
      (is (= ["home"] (map :warehouse-id (get-in (proposal received) [:entry :lines])))))
    (testing "two mapped seller warehouses: blank, the receiving warehouse still missing"
      (let [facts                   (assoc-in facts [:warehouse-mappings "c1:fbs:4"]
                                              {:external-warehouse-id "c1:fbs:4" :connection-id connection :warehouse-type "rfbs" :warehouse-id "shop" :state "confirmed"})
            {:keys [entry missing]} (proposal facts received)]
        (is (= [nil] (map :warehouse-id (:lines entry))))
        (is (= ["receiving warehouse"] missing))))))


(deftest return-of-an-unknown-posting-is-blank
  (let [{:keys [entry missing]} (proposal (assoc fbs-return :related-operation "c1:fbs-posting:unknown"))]
    (is (= [nil] (map :warehouse-id (:lines entry))))
    (is (= ["receiving warehouse"] missing))))


(deftest rfbs-return-lists-its-missing-facts
  (let [{:keys [entry missing]} (proposal rfbs-return)]
    (is (= [{:line-id "c1:rfbs-return:R3/1" :stock-item-id "red"}] (:lines entry)))
    (is (= ["actual time" "line quantity" "receiving warehouse"] missing))))


(deftest supply-is-a-transfer-between-the-mapped-warehouses
  (is (= {:entry   {:kind  "transfer" :from-warehouse-id "home" :to-warehouse-id "ozon-7"
                    :lines [{:line-id "c1:supply:S1/1" :stock-item-id "red" :quantity 5}]}
          :missing ["actual time"]}
         (proposal supply))))


(deftest disposed-reason
  (is (= "Disposed by Ozon" (get-in (proposal removal-disposed) [:entry :reason])))
  (is (nil? (get-in (proposal removal-given-out) [:entry :reason]))))


(deftest price-only-in-the-workspace-currency
  (is (= ["99.90"] (map :unit-price (get-in (proposal handed-over-posting) [:entry :lines]))))
  (is (= [nil] (map :unit-price (get-in (proposal (assoc facts :currency "EUR") handed-over-posting) [:entry :lines]))))
  (is (= [nil] (map :unit-price (get-in (proposal (assoc handed-over-posting :lines (one-line {:price "99.90"}))) [:entry :lines])))
      "a price without its currency")
  (is (= [nil] (map :unit-price (get-in (proposal (assoc supply :lines (one-line {:price "99.90" :price-currency "RUB" :quantity "5"})))
                                        [:entry :lines])))
      "only a Sale and a Customer return carry a price"))


(defn- lines
  {:malli/schema [:=> [:cat :string] :any]}
  [text]
  (proposals/resolve-lines facts (assoc handed-over-posting :lines (one-line {:quantity text}))))


(deftest quantities-stay-whole
  (are [text quantity] (= quantity (:quantity (first (lines text))))
    "3" 3 "1.5" nil "0" nil "10000000000" nil "-1" nil "abc" nil)
  (testing "a quantity that is not whole is a missing fact, never truncated"
    (is (= ["line quantity"] (:missing (proposal (assoc handed-over-posting :lines (one-line {:quantity "1.5"}))))))))


(deftest one-item-on-two-lines
  (let [op    (assoc handed-over-posting
                     :lines (observations/write-json [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "2"}
                                                      {:line-key  "2"  :identifiers [{:type "article" :value "MUG-R"}] :quantity "3"
                                                       :duplicate true}]))
        lines (get-in (proposal op) [:entry :lines])]
    (is (= ["c1:fbs-posting:P1/1" "c1:fbs-posting:P1/2"] (map :line-id lines)))
    (is (= ["red" "red"] (map :stock-item-id lines)))
    (is (= 5 (reduce + (map :quantity lines))) "the balance falls by their sum")))


(deftest listing-resolution
  (letfn [(resolved [identifiers]
            (:external-product-id (first (proposals/resolve-lines facts (assoc handed-over-posting :lines (one-line {:identifiers identifiers}))))))]
    (is (= "c1:mug-red" (resolved [{:type "sku" :value "100"}])) "within the connection")
    (is (= "c1:mug-blue" (resolved [{:type "sku" :value "200"} {:type "article" :value "MUG-R"}])) "SKU first")
    (is (= "c1:mug-blue" (resolved [{:type "sku" :value "999"} {:type "product-id" :value "mug-blue"} {:type "article" :value "MUG-R"}]))
        "then product id")
    (is (= "c1:mug-red" (resolved [{:type "article" :value "MUG-R"}])) "then article")
    (is (nil? (resolved [{:type "sku" :value "999"}])))
    (testing "two listings for one identifier resolve to none"
      (let [facts (update facts :products conj {:external-product-id "c1:mug-red-2" :connection-id connection
                                                :identifiers         [{:type "sku" :value "100" :active true}]})]
        (is (nil? (:external-product-id (first (proposals/resolve-lines facts handed-over-posting)))))))
    (testing "an inactive binding resolves nothing"
      (let [facts (assoc facts :products [{:external-product-id "c1:mug-red" :connection-id connection
                                           :identifiers         [{:type "sku" :value "100" :active false}]}])]
        (is (nil? (:external-product-id (first (proposals/resolve-lines facts handed-over-posting)))))))))


(deftest not-resolvable
  (testing "a line without a confirmed match"
    (is (nil? (proposal (assoc handed-over-posting :lines (one-line {:identifiers [{:type "sku" :value "300"}]})))))
    (is (nil? (proposal (assoc-in facts [:mappings "c1:mug-red" :state] "suspended") handed-over-posting)))
    (is (= "c1:mug-red" (:external-product-id (first (proposals/resolve-lines (assoc-in facts [:mappings "c1:mug-red" :state] "suspended")
                                                                              handed-over-posting))))
        "the listing still resolves; its item does not"))
  (testing "a stated warehouse without a confirmed match"
    (is (nil? (proposal (assoc handed-over-posting :from-warehouse "c1:fbs:5"))))
    (is (nil? (proposal (assoc-in facts [:warehouse-mappings "c1:fbs:3" :state] "removed") handed-over-posting))))
  (testing "no baseline"
    (is (nil? (proposal (assoc facts :baselines {}) handed-over-posting)))))


(deftest supply-to-an-unmatched-drop-off
  (is (nil? (proposal (update facts :warehouse-mappings dissoc "c1:supply-origin:9") supply))))


(deftest posting-without-handover-time
  (testing "a delivered posting without a handover date, mapped and baselined: a Sale missing the actual time"
    (let [{:keys [entry missing]} (proposal (assoc handed-over-posting :occurred-at nil :handed-over-at nil :complete false
                                                   :missing (observations/write-json ["actual time"])))]
      (is (= "sale" (:kind entry)))
      (is (nil? (:occurred-at entry)))
      (is (= ["actual time"] missing)))))


(deftest old-operation
  (testing "a posting before the cutover has no entry"
    (is (nil? (proposal (assoc handed-over-posting :occurred-at "2026-10-30T09:00:00Z"))))
    (is (nil? (proposal (assoc handed-over-posting :occurred-at "2026-11-01T00:00:00Z"))) "at the instant is history")
    (is (false? (:after-baseline (first (proposals/resolve-lines facts (assoc handed-over-posting :occurred-at "2026-10-30T09:00:00Z")))))))
  (testing "lines both before and after their baselines: proposed whole"
    (let [facts (assoc-in facts [:baselines "c1|blue|home" :instant] "2026-11-20T00:00:00Z")
          op    (assoc handed-over-posting
                       :lines (observations/write-json [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "1"}
                                                        {:line-key "2" :identifiers [{:type "sku" :value "200"}] :quantity "1"}]))]
      (is (= [true false] (map :after-baseline (proposals/resolve-lines facts op))))
      (is (= 2 (count (get-in (proposal facts op) [:entry :lines])))))))


(deftest nothing-matched-yet
  (testing "340 postings naming 12 unmatched listings: no entry"
    (let [listings (for [n (range 12)] {:external-product-id (str "c1:l" n) :connection-id connection
                                        :identifiers         [{:type "sku" :value (str "9" n) :active true}]})
          postings (for [n (range 340)]
                     (assoc handed-over-posting :source-operation-id (str "c1:fbs-posting:N" n)
                            :lines (one-line {:identifiers [{:type "sku" :value (str "9" (mod n 12))}]})))]
      (is (= 340 (count postings)))
      (is (every? nil? (map (partial proposal (update facts :products into listings)) postings))))))

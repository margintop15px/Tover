(ns tover.inventory.layouts-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.grid :as grid]
    [tover.inventory.layouts :as layouts]
    [tover.inventory.store :as store]
    [tover.shared.rdf :as rdf])
  (:import
    [java.nio.file Files]))


(def user-id
  #uuid "00000000-0000-0000-0000-000000000011")


(def other-user-id
  #uuid "00000000-0000-0000-0000-000000000012")


(def workspace-id
  #uuid "00000000-0000-0000-0000-000000000001")


(defn- with-store
  {:malli/schema [:=> [:cat [:=> [:cat :map :any] :any]] :any]}
  [f]
  (let [path (.toFile (Files/createTempDirectory "tover-layouts-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)]
    (try (f {:repo repo :user-id user-id :workspace-id workspace-id} path)
         (finally
           (rdf/close! repo)
           (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn- error-data
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest account-layouts-survive-reopen-and-do-not-touch-accepted-inventory
  (with-store
    (fn [{:keys [repo] :as context} path]
      (let [entry {:kind  :opening :occurred-at "2026-01-01"
                   :lines [{:item-id "a" :name "A" :location "Main" :quantity 3}]}
            draft (store/save-entry! repo context {:entry entry :actor (str user-id)})]
        (store/submit! repo context {:selected                 [(select-keys draft [:id :revision])]
                                     :expected-ledger-revision 0 :actor (str user-id) :command-id "initial-stock"}))
      (let [before (store/snapshot repo context)
            saved  (layouts/save! context {:gridId "stock"             :widths  {"name" 420 :quantity 120}
                                           :order  ["quantity" "name"] :user-id other-user-id})]
        (is (= {:widths {"name" 420 "quantity" 120} :order ["quantity" "name"] :version 1 :lastColumn "quantity"} saved))
        (is (= {"stock" saved} (layouts/load-all context)))
        (is (= {"stock" saved} (layouts/load-all (assoc context :workspace-id (random-uuid)))))
        (is (= {} (layouts/load-all (assoc context :user-id other-user-id))))
        (is (= {} (layouts/load-all (dissoc context :user-id))))
        (is (= before (store/snapshot repo context)))
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (is (= {"stock" saved} (layouts/load-all (assoc context :repo reopened))))
            (is (= before (store/snapshot reopened context)))
            (finally (rdf/close! reopened))))))))


(deftest patches-preserve-hidden-columns-other-grids-and-unrelated-widths
  (with-store
    (fn [context _]
      (layouts/save! context {:gridId "shared-draft"              :widths {"name" 400 "amount" 100}
                              :order  ["name" "amount" "article"]})
      (layouts/save! context {:gridId "stock" :widths {"location" 180}})
      (is (= {:widths {"name" 400 "amount" 100 "article" 170}
              :order  ["article" "amount" "name" "quantity"]  :version 2 :lastColumn "article"}
             (layouts/save! context {:gridId "shared-draft"                :widths {:article 170}
                                     :order  ["article" "name" "quantity"]})))
      (is (= {"location" 180} (get-in (layouts/load-all context) ["stock" :widths])))
      (is (= 3 (:version (layouts/save! context {:gridId "shared-draft" :widths {"quantity" 100} :version 200})))))))


(deftest concurrent-width-patches-commit-without-losing-other-columns
  (with-store
    (fn [context _]
      (let [widths {"name" 410 "quantity" 120 "unit" 90 "location" 180 "article" 150 "barcode" 170}
            ready  (promise)
            saves  (mapv (fn [[key width]]
                           (future @ready (layouts/save! context {:gridId "stock" :widths {key width}}))) widths)]
        (deliver ready true)
        (doseq [save saves] (is (map? (deref save 10000 ::timeout))))
        (let [saved (get (layouts/load-all context) "stock")]
          (is (= {:widths widths :order [] :version (count widths)} (dissoc saved :lastColumn)))
          (is (contains? widths (:lastColumn saved))))))))


(deftest order-only-saves-preserve-the-last-manually-resized-column
  (with-store
    (fn [context _]
      (layouts/save! context {:gridId "stock" :widths {"name" 400}})
      (is (= "name" (:lastColumn (layouts/save! context {:gridId "stock" :order ["quantity" "name"]}))))
      (is (= "quantity" (:lastColumn (layouts/save! context {:gridId "stock" :widths {"quantity" 150}}))))
      (is (= "quantity" (:lastColumn (layouts/save! context {:gridId "stock" :widths {}})))))))


(deftest settings-boundaries-reject-unsupported-columns-and-unbounded-values
  (with-store
    (fn [context _]
      (doseq [input [{:gridId "unknown" :widths {"name" 200}}
                     {:gridId "stock"}
                     {:gridId "stock" :widths nil}
                     {:gridId "stock" :widths {"name" 49}}
                     {:gridId "stock" :widths {"name" 2401}}
                     {:gridId "stock" :widths {"name" 100.5}}
                     {:gridId "stock" :widths {"name" "100"}}
                     {:gridId "stock" :widths {"name" 100 :name 200}}
                     {:gridId "stock" :widths {:other/name 200}}
                     {:gridId "stock" :widths {"unknown" 100}}
                     {:gridId "shared-draft" :widths {"select" 100}}
                     {:gridId "shared-draft" :widths {"actions" 100}}
                     {:gridId "stock" :order '("name")}
                     {:gridId "stock" :order ["name" "name"]}
                     {:gridId "stock" :order ["unknown"]}
                     {:gridId "shared-draft" :order ["actions" "name"]}]]
        (is (= {:type :invalid-input :status 400} (error-data #(layouts/save! context input))) (pr-str input)))
      (is (= {:type :forbidden :status 403}
             (error-data #(layouts/save! (dissoc context :user-id) {:gridId "stock" :widths {"name" 200}}))))
      (is (= {:type :forbidden :status 403}
             (error-data #(layouts/save! (assoc context :user-id (str user-id)) {:gridId "stock" :order []}))))
      (is (= {} (layouts/load-all context))))))


(deftest layout-projection-moves-cells-and-vertical-merges-by-logical-column
  (let [entry     {:kind    :operation :type :production
                   :inputs  [{:name "A" :quantity 2} {:name "B" :quantity 3}]
                   :outputs [{:name "C" :quantity 1}]}
        model     (grid/drafts {:drafts [{:id "production" :revision 4 :entry entry}]})
        layout    {:widths {"name" 410 "quantity" 120} :order ["quantity" "name" "reason" "type"] :version 7 :lastColumn "name"}
        actual    (layouts/apply-layout model layout)
        old-index (zipmap (map :key (:columns model)) (range))
        new-index (zipmap (map :key (:columns actual)) (range))]
    (is (= "select" (:key (first (:columns actual)))))
    (is (= "actions" (:key (last (:columns actual)))))
    (is (= ["quantity" "name" "reason" "type"] (mapv :key (subvec (:columns actual) 1 5))))
    (is (= 410 (:width (nth (:columns actual) (new-index "name")))))
    (is (= (:widths layout) (:savedWidths actual)))
    (is (= (:order layout) (:columnOrder actual)))
    (is (= 7 (:layoutVersion actual)))
    (is (= "name" (:lastColumn actual)))
    (doseq [key (keys old-index) index (range (count (:rows model)))]
      (is (= (get-in model [:rows index :cells (old-index key)])
             (get-in actual [:rows index :cells (new-index key)]))))
    (doseq [{:keys [col] :as merge} (:merges model)]
      (is (some #{(assoc merge :col (new-index (:key (nth (:columns model) col))))} (:merges actual))))))


(deftest new-columns-append-and-full-width-merged-details-keep-their-anchor
  (let [model  {:id   "stock" :columns [{:key "name"} {:key "quantity"} {:key "new-column"}]
                :rows [{:id "row-a" :cells [{:value "A"} {:value 2} {:value "new"}]}] :merges []}
        actual (layouts/apply-layout model {:widths {"name" 350} :order ["absent" "quantity" "name"]})]
    (is (= ["quantity" "name" "new-column"] (mapv :key (:columns actual))))
    (is (= [2 "A" "new"] (mapv :value (get-in actual [:rows 0 :cells]))))
    (is (= 0 (:layoutVersion actual)))
    (is (= [{:row 0 :col 1 :rowspan 2}]
           (:merges (layouts/apply-layout (assoc model :merges [{:row 0 :col 0 :rowspan 2}])
                                          {:order ["quantity" "name"]}))))
    (testing "A full-width detail remains at the first visible cell"
      (let [detail     {:value "Detail" :operationDetail "operation-a"}
            full-width (assoc model :rows [{:id "detail" :cells [detail {} {}]}]
                              :merges [{:row 0 :col 0 :rowspan 1 :colspan 3}])
            projected  (layouts/apply-layout full-width {:order ["quantity" "new-column" "name"]})]
        (is (= detail (get-in projected [:rows 0 :cells 0])))
        (is (= [{} {}] (subvec (get-in projected [:rows 0 :cells]) 1)))))))

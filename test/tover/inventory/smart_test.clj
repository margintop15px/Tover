(ns tover.inventory.smart-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.inventory.smart :as smart]))


(defn composing
  {:malli/schema [:=> [:cat :map] :map]}
  [entry]
  {:items {} :rows [] :drafts [] :compositions [{:id "private" :revision 2 :saved-at "2026-10-21T10:00:00Z" :entry entry}]})


(def composer-state
  {:mode "add" :id "private" :revision 2})


(deftest suggestions-come-from-server-columns-and-operation-choices
  (let [snapshot {:items {"item-a" {:name "Notebook"}} :rows [{:name "Notebook" :location "Main"}] :drafts []}
        goods    (smart/model snapshot {} "goo" {} 1)
        values   (smart/model snapshot {:field "name"} "Note" {} 2)]
    (is (= ["Goods"] (mapv :label (filter #(= "field" (:kind %)) (:options goods)))))
    (is (= "Notebook" (get-in values [:options 0 :value])))
    (is (= "item-a" (get-in values [:options 0 :itemId])))
    (doseq [state [{} {:mode "types"}]]
      (let [options (:options (smart/model snapshot state "ass" {} 3))]
        (is (= 8 (count options)))
        (is (every? #(= "type" (:kind %)) options))))
    (is (= ["Production"] (mapv :label (:options (smart/model snapshot {:mode "types"} "prod" {} 5)))))))


(deftest idle-menu-only-starts-an-operation-and-typed-search-remains-available
  (let [snapshot {:compositions [{:id "unfinished" :revision 2 :entry {:type :purchase}}]}]
    (is (= ["add"] (mapv :kind (:options (smart/model snapshot {} "" {} 1)))))
    (is (= ["add"] (mapv :kind (:options (smart/model snapshot {} "  " {} 2)))))
    (is (some #(and (= "field" (:kind %)) (= "quantity" (:key %))) (:options (smart/model snapshot {} "quan" {} 3))))
    (is (some #(= "resumes" (:kind %)) (:options (smart/model snapshot {} "resume" {} 4))))))


(deftest filled-fields-and-defaults-are-chips-not-repeated-suggestions
  (let [entry     {:kind  :operation :type :purchase :occurred-at "2026-10-21T10:00:00Z" :party "Supplier"
                   :lines [{:item-id "new" :unit "pcs" :name "Notebook" :quantity "" :location ""}]}
        model     (smart/model (composing entry) composer-state "" {} 1)
        keys      (set (map :key (:options model)))
        chip-keys (set (map :key (:chips model)))]
    (is (not-any? keys ["party" "occurred-at" "lines/0/name" "lines/0/unit"]))
    (is (every? chip-keys ["party" "occurred-at" "lines/0/name" "lines/0/unit"]))
    (is (keys "lines/0/quantity"))
    (is (not (:canDone model)))
    (is (not-any? #(= "done" (:kind %)) (:options model)))
    (let [cleared (smart/model (composing (assoc-in entry [:lines 0 :unit] "")) composer-state "" {} 2)]
      (is (some #(= "lines/0/unit" (:key %)) (:options cleared))))))


(deftest done-is-only-offered-for-a-structurally-complete-entry
  (let [entry {:kind      :operation :type   :payment :occurred-at "2026-10-21T10:00:00Z" :party "Supplier"
               :direction :outgoing  :amount "12.34"  :currency    "EUR"}
        model (smart/model (composing entry) composer-state "" {} 1)]
    (is (:canDone model))
    (is (= "done" (get-in model [:options 0 :kind])))
    (is (not-any? #(= "field" (:kind %)) (:options (smart/model (composing entry) composer-state "party" {} 2))))
    (is (some #(= "direction" (:key %)) (:chips model)))))


(deftest production-and-payment-have-their-own-fields
  (let [production (smart/entry-fields {:kind :operation :type :production :inputs [{}] :outputs [{}]})
        payment    (smart/entry-fields {:kind :operation :type :payment})]
    (is (some #(= "inputs/0/quantity" (:key %)) production))
    (is (some #(= "outputs/0/name" (:key %)) production))
    (is (not-any? #(= "party" (:key %)) production))
    (is (some #(= "amount" (:key %)) payment))
    (is (not-any? #(re-find #"^(lines|inputs|outputs)/" (:key %)) payment))))


(deftest filters-are-outside-composer-and-replacement-is-explicit
  (let [filters {"name" [{:id "a" :type "notEq" :value "Excluded"}]}
        model   (smart/model {} {} "Note" filters 1)]
    (is (empty? (:chips model)))
    (is (= "replace-filter" (:kind (last (:options model)))))
    (is (empty? (:chips (smart/model {} {} "" {"name" [{:id "a" :type "begins" :value "Note"}]} 2))))))


(deftest latest-private-composition-resumes-without-a-focus-instruction
  (let [snapshot {:compositions [{:id "old" :revision 1 :saved-at "2026-10-20T00:00:00Z" :entry {:type :payment}}
                                 {:id "new" :revision 3 :saved-at "2026-10-21T00:00:00Z" :entry {:type :payment}}]}
        model    (smart/initial-model snapshot {})]
    (is (= "new" (get-in model [:state :id])))
    (is (not (contains? model :focus)))
    (is (= ["new" "old"] (mapv :id (:options (smart/model snapshot {:mode "resumes"} "" {} 1)))))
    (is (nil? (smart/initial-model {} {})))))


(deftest unavailable-composition-has-a-start-again-action
  (let [model (smart/model {} composer-state "Attempt" {} 1)]
    (is (:unavailable model))
    (is (= "Attempt" (:text model)))
    (is (= "restart" (get-in model [:options 0 :kind])))))


(deftest entry-is-a-pure-component-with-view-specific-identity
  (doseq [view [:stock :operations]]
    (let [attributes (get-in (smart/entry {:view view :init "initialize()" :on-suggest "suggest()" :on-complete "complete()"}) [2 1])]
      (is (= (str "stock-entry-" (name view)) (:id attributes)))
      (is (= "initialize()" (:data-effect attributes)))
      (is (= "suggest()" (:data-on:smart-suggest attributes)))
      (is (= "complete()" (:data-on:smart-complete attributes))))))


(deftest model-explicitly-clears-prior-field-and-entry-state-for-signal-merge-patches
  (let [model (smart/model {} {:mode "filter"} "" {} 3)]
    (doseq [key [:id :revision :field :replacing]]
      (is (contains? (:state model) key))
      (is (nil? (get-in model [:state key]))))))


(deftest normal-model-explicitly-clears-stale-conflict-signals
  (let [model (smart/model {} {} "" {} 2)]
    (is (contains? model :conflict))
    (is (nil? (:conflict model)))
    (is (false? (:failed model)))
    (is (nil? (get-in model [:state :conflict]))))
  (let [model (smart/model {} {:mode "unavailable" :id "gone" :revision 1} "Attempt" {} 3)]
    (is (:unavailable model))
    (is (= "restart" (get-in model [:options 0 :kind])))))

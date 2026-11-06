(ns tover.catalogues.schema)


(def kinds
  [[:goods "Goods"] [:warehouses "Warehouses"] [:suppliers "Suppliers"]
   [:categories "Categories"] [:stores "Stores"]])


(def columns
  {:goods      [[:name "Name"] [:article "Article"] [:barcode "Barcode"] [:unit "Unit"]
                [:category-id "Category"] [:store-id "Store"]]
   :warehouses [[:name "Name"] [:description "Description"] [:purpose "Purpose"]]
   :suppliers  [[:name "Name"] [:address "Address"] [:contact "Contact information"]]
   :categories [[:name "Name"]]
   :stores     [[:name "Name"] [:warehouse-id "Default warehouse"]]})


(def references
  {:category-id :categories :store-id :stores :warehouse-id :warehouses})


(def goods-fields
  [:name :article :barcode :unit])


(def Kind
  (into [:enum] (map (comp name first) kinds)))


(def Query
  [:map
   [:kind Kind]
   [:query {:optional true} [:string {:max 500}]]
   [:sort {:optional true} :string]
   [:direction {:optional true} [:enum "asc" "desc"]]
   [:filters {:optional true} :map]])


(def Save
  [:map
   [:kind Kind]
   [:id [:string {:min 1 :max 500}]]
   [:revision [:int {:min 0}]]
   [:path :string]
   [:value [:string {:max 500}]]
   [:referenceName {:optional true} [:string {:min 1 :max 500}]]
   [:commandId [:string {:min 1 :max 100}]]])

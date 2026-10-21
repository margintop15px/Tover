(ns tover.operations.examples)


(defn- physical-ids
  {:malli/schema [:=> [:cat :string :int :int] [:vector :string]]}
  [prefix from through]
  (mapv #(format "%s-%03d" prefix %) (range from (inc through))))


;; Each physical unit has one current location; production creates new identities.
(def rows
  [{:id                "demo-notebook-main"       :name     "Notebook A5" :quantity "22"        :unit        "pcs"                    :location "Main shelf" :article "NB-A5"
    :evidence          "Production sheet PS-1013" :editable true          :batchId  "NB-261009" :physicalIds (physical-ids "NB" 9 30)
    :originOperationId "demo-opening-notebooks"}
   {:id                "demo-cable-storage"  :name     "USB-C cable, 1 m" :quantity "14"         :unit        "pcs" :location "Storage box" :article "USB-C-1"
    :evidence          "Transfer TR-1011"    :editable true               :batchId  "CBL-261009" :physicalIds (physical-ids "CBL" 11 24)
    :originOperationId "demo-opening-cables"}
   {:id                "demo-cable-main"          :name     "USB-C cable, 1 m" :quantity "2"          :unit        "pcs"                 :location "Main shelf" :article "USB-C-1"
    :evidence          "Production sheet PS-1013" :editable true               :batchId  "CBL-261009" :physicalIds ["CBL-009" "CBL-010"]
    :originOperationId "demo-opening-cables"}
   {:id                "demo-tea-main"              :name     "Green tea, 100 g" :quantity "18"         :unit        "packs"                   :location "Main shelf" :article "TEA-G100"
    :evidence          "Packaging label correction" :editable true               :batchId  "TEA-261010" :physicalIds (physical-ids "TEA" 1 18)
    :originOperationId "demo-opening-tea"}
   {:id                "demo-kit-workshop"         :name                "Desk kit"                 :quantity "3" :unit        "kits"                   :location "Workshop" :article "DESK-KIT"
    :evidence          "Transfer TR-1014"          :editable            true :batchId "KIT-261013"               :physicalIds (physical-ids "KIT" 6 8)
    :originOperationId "demo-production-desk-kits" :predecessorBatchIds ["NB-261009" "CBL-261009"]}
   {:id                "demo-kit-main"             :name                "Desk kit"                 :quantity "4" :unit        "kits" :location "Main shelf" :article "DESK-KIT"
    :evidence          "Customer return RET-1016"  :editable            true :batchId "KIT-261013"               :physicalIds ["KIT-001" "KIT-003" "KIT-004" "KIT-005"]
    :originOperationId "demo-production-desk-kits" :predecessorBatchIds ["NB-261009" "CBL-261009"]}])


(defn- effect
  {:malli/schema [:=> [:cat :string :string :string :string :string :string :string [:vector :string]] :map]}
  [row-id quantity before after delta direction role physical-ids]
  (merge (select-keys (some #(when (= row-id (:id %)) %) rows)
                      [:name :article :unit :location :batchId :originOperationId :predecessorBatchIds])
         {:rowId row-id :quantity quantity :before before :after after :delta delta :direction direction :role role :physicalIds physical-ids}))


(def entries
  [{:id         "demo-opening-notebooks" :kind      "opening"                 :type     "Opening stock" :example true
    :rowId      "demo-notebook-main" :name          "Notebook A5"             :article  "NB-A5"         :unit    "pcs" :location "Main shelf"
    :summary    "30 notebooks" :context             "Paper & Co → Main shelf" :supplier "Paper & Co"
    :recordedAt "2026-10-09T09:00:00Z" :effectiveAt "2026-10-09T09:00:00Z"
    :before     "0" :after "30" :delta "+30" :reason "Declare existing notebook stock" :evidence "Opening count OC-1009"
    :effects    [(effect "demo-notebook-main" "30" "0" "30" "+30" "in" "destination" (physical-ids "NB" 1 30))]}
   {:id         "demo-opening-cables" :kind         "opening"                    :type     "Opening stock" :example true
    :rowId      "demo-cable-storage" :name          "USB-C cable, 1 m"           :article  "USB-C-1"       :unit    "pcs" :location "Storage box"
    :summary    "24 USB-C cables" :context          "Cable Supply → Storage box" :supplier "Cable Supply"
    :recordedAt "2026-10-09T09:15:00Z" :effectiveAt "2026-10-09T09:15:00Z"
    :before     "0" :after "24" :delta "+24" :reason "Declare existing cable stock" :evidence "Opening count OC-1009"
    :effects    [(effect "demo-cable-storage" "24" "0" "24" "+24" "in" "destination" (physical-ids "CBL" 1 24))]}
   {:id         "demo-opening-tea" :kind            "opening"                :type     "Opening stock" :example true
    :rowId      "demo-tea-main" :name               "Green tea, 100 g"       :article  "TEA-100"       :unit    "packs" :location "Main shelf"
    :summary    "18 green tea packs" :context       "Tea House → Main shelf" :supplier "Tea House"
    :recordedAt "2026-10-10T08:30:00Z" :effectiveAt "2026-10-10T08:30:00Z"
    :before     "0" :after "18" :delta "+18" :reason "Declare existing tea stock" :evidence "Opening count OC-1010"
    :effects    [(assoc (effect "demo-tea-main" "18" "0" "18" "+18" "in" "destination" (physical-ids "TEA" 1 18)) :article "TEA-100")]}
   {:id         "demo-transfer-cables" :kind                  "transfer"                 :type "Transfer" :example true
    :name       "USB-C cable, 1 m" :article                   "USB-C-1"                  :unit "pcs"
    :summary    "10 USB-C cables" :context                    "Storage box → Main shelf"
    :recordedAt "2026-10-11T10:00:00Z" :effectiveAt           "2026-10-11T10:00:00Z"
    :reason     "Move components near the workshop" :evidence "Transfer TR-1011"
    :effects    [(effect "demo-cable-storage" "10" "24" "14" "-10" "out" "source" (physical-ids "CBL" 1 10))
                 (effect "demo-cable-main" "10" "0" "10" "+10" "in" "destination" (physical-ids "CBL" 1 10))]}
   {:id         "demo-data-edit-tea" :kind               "data_edit"            :type    "Data edit" :example true
    :rowId      "demo-tea-main" :name                    "Green tea, 100 g"     :article "TEA-G100"  :unit    "packs" :location "Main shelf"
    :summary    "Green tea · Article corrected" :context "Main shelf"
    :recordedAt "2026-10-12T14:20:00Z" :effectiveAt      "2026-10-12T14:20:00Z"
    :before     "18" :after "18" :delta "0" :reason "Match the article printed on the pack" :evidence "Packaging label correction"
    :changes    [{:field "Article" :before "TEA-100" :after "TEA-G100"}]
    :effects    [(effect "demo-tea-main" "0" "18" "18" "0" "none" "edited" (physical-ids "TEA" 1 18))]}
   {:id               "demo-production-desk-kits" :kind "production" :type "Production" :example true
    :name             "Desk kit" :article "DESK-KIT" :unit                 "kits"
    :summary          "8 desk kits · 2 inputs → 1 output" :context "Main shelf → Workshop"
    :recordedAt       "2026-10-13T10:30:00Z" :effectiveAt "2026-10-13T10:30:00Z"
    :startedAt        "2026-10-13T09:00:00Z" :finishedAt "2026-10-13T10:30:00Z"
    :reason           "Assemble a notebook and cable into each desk kit" :evidence               "Production sheet PS-1013"
    :inputBatchIds    ["NB-261009" "CBL-261009"] :outputBatchIds ["KIT-261013"]
    :inputPhysicalIds (into (physical-ids "NB" 1 8) (physical-ids "CBL" 1 8)) :outputPhysicalIds (physical-ids "KIT" 1 8)
    :lineage          (mapv (fn [number]
                              {:outputId (format "KIT-%03d" number)
                               :inputIds [(format "NB-%03d" number) (format "CBL-%03d" number)]})
                            (range 1 9))
    :effects          [(effect "demo-notebook-main" "8" "30" "22" "-8" "out" "consumed" (physical-ids "NB" 1 8))
                       (effect "demo-cable-main" "8" "10" "2" "-8" "out" "consumed" (physical-ids "CBL" 1 8))
                       (effect "demo-kit-workshop" "8" "0" "8" "+8" "in" "produced" (physical-ids "KIT" 1 8))]}
   {:id         "demo-transfer-kits" :kind "transfer" :type "Transfer" :example true
    :name       "Desk kit" :article "DESK-KIT" :unit        "kits"
    :summary    "5 desk kits" :context "Workshop → Main shelf"
    :recordedAt "2026-10-14T08:45:00Z" :effectiveAt              "2026-10-14T08:45:00Z"
    :reason     "Put assembled kits on the sale shelf" :evidence "Transfer TR-1014"
    :effects    [(effect "demo-kit-workshop" "5" "8" "3" "-5" "out" "source" (physical-ids "KIT" 1 5))
                 (effect "demo-kit-main" "5" "0" "5" "+5" "in" "destination" (physical-ids "KIT" 1 5))]}
   {:id         "demo-sale" :kind                   "sale" :type "Sale" :example true
    :summary    "2 desk kits" :context              "Main shelf → Studio North" :customer "Studio North"
    :recordedAt "2026-10-15T11:10:00Z" :effectiveAt "2026-10-15T11:10:00Z"
    :reason     "Fulfil a desk kit order" :evidence "Sale INV-1015"
    :effects    [(effect "demo-kit-main" "2" "5" "3" "-2" "out" "sold" ["KIT-001" "KIT-002"])]}
   {:id         "demo-return" :kind "return" :type "Return" :example true
    :summary    "1 desk kit" :context "Studio North → Main shelf" :customer "Studio North"
    :recordedAt "2026-10-16T09:00:00Z" :effectiveAt                  "2026-10-16T09:00:00Z"
    :reason     "Unopened desk kit returned to sale stock" :evidence "Customer return RET-1016" :relatedOperationId "demo-sale"
    :effects    [(effect "demo-kit-main" "1" "3" "4" "+1" "in" "returned" ["KIT-001"])]}])

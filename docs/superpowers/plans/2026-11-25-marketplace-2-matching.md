# Marketplace 2: Matching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Members match Ozon listings to stock items and Ozon warehouses to Tover warehouses, in bulk, in tables.

**Architecture:** Pure candidate rules over observed listings and the catalogue; one mapping row per listing or warehouse plus one history row per mapping revision. A guard processor suspends mappings whose confirmed identity changed.

**Tech Stack:** Clojure, Bel, RDF4J, Malli, Datastar, Jspreadsheet through `inventory-grid`.

**Spec:** board chapters 42 (`c0de0000-0000-4000-8000-000000000004`), 43 (`…005`).

## Global Constraints

All of [the roadmap's rules](2026-11-25-marketplace-0-roadmap.md#rules-binding-every-task). Also:
- Rules propose; only a member's command creates a mapping.
- A bulk command accepts every row or none, and names every offending row.
- Accepted operations are never rewritten by a mapping change.

## Review Focus
1. Barcode `"0"`, blank or with surrounding spaces in a listing: never a candidate, conflict or adoptable value (task 1).
2. The same listing or the same barcode twice in one batch: rejected naming both rows (tasks 4, 5).
3. 2,000 listings selected at once: one command, under 10 s in the store test, no partial result (task 5).
4. A listing suspended and then remapped: old reasons do not survive (task 7).
5. An item moved to another product after confirmation, barcodes moving with it: no false `barcode-moved` (task 7).

---

### Task 1: Candidate rules

**Files:** Create `src/tover/marketplace/products/candidates.clj`, `test/tover/marketplace/candidates_test.clj`. Board 42 slice 1.

**Interfaces:** Consumes `sync/ExternalProduct` rows with parsed `:identifiers` `[{:type :value :active}]`. Produces
- `candidates [{:keys [products stock-items barcodes]} listing] → {:candidates [{:stock-item-id :item-revision :rule :evidence :preselected?}] :conflicts [{:barcode :product-ids}]}`; rules `"barcode-owner" "article-match" "name-equal"`.
- `barcode-owners [barcodes stock-items] → {barcode product-id}`.
- `identity-basis [listing definitions item] → {:attributes {id [value]} :pack :unit :variant-label}`; attributes are those with `:separates-variants`, or size and colour (definitions named `Размер`, `Российский размер`, `Цвет товара`, `Название цвета`, kept in one set in `candidates.clj`; with no definition at all, every attribute) when the definition is unknown.

- [ ] **Step 1: Failing tests**

```clojure
(deftest one-item-owns-the-barcode
  (is (= [["mug-1" "barcode-owner" true]]
         (map (juxt :stock-item-id :rule :preselected?) (:candidates (candidates catalogue (listing {:barcodes ["4600000000017"]})))))))
(deftest barcode-shared-by-sizes
  (is (= [["tee-l" false] ["tee-m" false]] (map (juxt :stock-item-id :preselected?) (:candidates (candidates shirts (listing {:barcodes ["1"]})))))))
(deftest two-owners
  (is (= {:candidates-preselected 0 :conflicts 1} (counts (candidates catalogue (listing {:barcodes ["mug-code" "plate-code"]}))))))
(deftest barcode-and-article-disagree ...)   ; nothing preselected
(deftest name-equal-is-never-preselected ...) ; "  Mug   RED " equals "Mug" + "red"
(deftest archived-and-defective-are-no-candidates ...)
(deftest zero-and-blank-barcodes-are-ignored
  (is (= {:candidates [] :conflicts []} (candidates catalogue (listing {:barcodes ["0" "" " "]})))))
```

- [ ] **Step 2:** `clojure -M:dev:test --focus tover.marketplace.candidates-test` → FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): match candidates`.

### Task 2: Mapping models and the matching read

**Files:** Create `products/model.clj`, `test/tover/marketplace/products_test.clj`. Board 42 slices 1, 3, 5, 7.

**Interfaces:** Produces
- `::product-mappings` id `:external-product-id`: `:connection-id :stock-item-id? :listing-revision :item-revision :identity-basis(JSON) :mapping-revision :state("confirmed"|"suspended"|"removed") :reasons(JSON)? :changed-by :changed-at`.
- `::product-mapping-revisions` id `"<external-product-id>#<mapping-revision>"`, created by all four events, never updated.
- `::product-deferrals` id `:external-product-id`: `:reason? :deferred-by :deferred-at`; deleted by mapped/changed events.
- Events `external-product-mapped`, `product-match-deferred`, `product-mapping-changed`, `product-mapping-suspended`, `product-mapping-reconfirmed`.
- Coeffects `::mappings [connection-id?] → {external-product-id mapping}`, `::deferrals`, `::listings [connection-id]`.
- Read models `unmatched-external-products-and-candidates`, `matching-progress`, `mapped-and-remaining-listings`, `mapping-details-and-source-evidence` (input `{:connection-id …}` / `{:external-product-id …}`).
- `connections/count-part :unmatched-listings`.
- `defmulti waiting-operations [context connection-id] → {external-product-id n}`, `:default` `{}`; plan 4 implements it.

- [ ] **Step 1: Failing tests:** rows sorted by waiting operations; deferred rows only with `{:state "deferred"}`; archived listings are listed; progress counts `{:mapped 1 :unmatched 2 :deferred 1 :suspended 1}`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): product mapping reads`.

### Task 3: Dirty connections

**Files:** Modify `connector.clj` (resource gains `:dirty (atom #{})`), `sync/model.clj`; test `sync_test.clj`.

**Interfaces:** Produces effect `::sync/mark-dirty {:connection-id}`; `sync/take-dirty! [marketplace all-ids now] → #{connection-id}`: the marked ones, plus all of them once a minute and at start. Added to the `:fx` of recorded batches, mapping, pause and resume here; plans 3–5 add theirs. ponytail: in memory, right for the single application instance the board decided; the minute sweep covers inventory edits and restarts.

- [ ] **Step 1: Failing test:** `marked-once-is-taken-once`, `everything-once-a-minute`. **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): re-evaluate changed connections`.

### Task 4: Confirm, defer, change, reconfirm

**Files:** `products/model.clj`, `products_test.clj`. Board 42 slices 2, 6, 8, c.

**Interfaces:** Produces commands `confirm-product-mapping {:rows [{:external-product-id :listing-revision :stock-item-id :item-revision :adopt-barcodes?}]}`, `defer-product-match {:external-product-ids :reason?}`, `change-product-mapping {:external-product-id :mapping-revision :stock-item-id? :item-revision?}`, `reconfirm-product-mapping {:external-product-id :mapping-revision :listing-revision :item-revision}`. Rejection types `:stale :barcode-conflict :item-not-available :already-mapped :not-suspended :item-archived`; each carries `:rows [{:external-product-id :code :message :owner-product?}]`. Every accepted one has `:fx` `records/publish` and `::sync/mark-dirty {:connection-id}`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest one-click
  (is (= {:external-product-id "c:9001" :stock-item-id "mug-1" :mapping-revision 1 :state "confirmed"}
         (select-keys (event (confirm [row]) ::products/external-product-mapped) [...]))))
(deftest foreign-barcode
  (is (= [["c:9001" :barcode-conflict "Plate"]] (offending #(confirm [row-with-plate-code])))))
(deftest adopt-a-barcode ...)           ; stock-item-barcode-assigned in the same plan
(deftest adopted-barcode-must-be-active-on-the-listing ...)
(deftest two-members ...)               ; second confirm → :already-mapped when it differs, {} when equal
(deftest same-listing-twice-in-a-batch ...)
(deftest one-bad-row-accepts-nothing ...)
(deftest remap ...)                     ; previous stock item kept in revision 1, revision 2 names B
(deftest removing-returns-to-unmatched ...)
(deftest another-workspaces-item-is-not-available ...)
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): confirm and change product mappings`.

### Task 5: Register products for listings

**Files:** `products/model.clj`, `products_test.clj`. Board 42 slice 4.

**Interfaces:** Produces `register-product-for-external-listing {:rows [{:external-product-id :listing-revision :product-id? :product-revision? :product-name? :variant-label? :article? :barcodes? :unit}]}` and `item-id [external-product-id]` → `"listing:<external-product-id>"`, `product-id` of a new name → `"listing-product:<first external-product-id by order>"`. Uniqueness reuses `acceptance`'s rules: extract its private `uniqueness` into `tover.inventory.changes/uniqueness [facts lines]` (same output) and call it from both.

- [ ] **Step 1: Failing tests:** `two-colours` (1 product, 2 items, 2 mappings), `collision-in-the-batch` (40 rows, exactly 2 marked, no events), `retry` (same command id → one item), `unit-defaults-to-pcs-in-the-read`, `item-takes-the-connections-active-store`, `same-new-name-forms-one-product` (trimmed, case-insensitive), `two-thousand-rows` through `support/with-store`.
- [ ] **Step 2:** FAIL. **Step 3:** Extract (acceptance tests stay green), then implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): register products for listings`.

### Task 6: The shared table and the matching page

**Files:** Create `src/tover/marketplace/grid.clj`, `resources/public/marketplace.js`, `products/web.clj`, `test/tover/marketplace/products_web_test.clj`, `test/browser/marketplace.{config,spec}.mjs`.

**Interfaces:** Produces
- `grid/workbench [{:keys [id columns rows groups filters selection-actions query-view command-action]}] → Hiccup`, rendering `<marketplace-workbench>` around `<inventory-grid>`. `columns` `[{:key :label :type :editable? :options?}]`; `rows` `[{:id :revision :cells {key value} :findings {key text}}]`.
- JS custom element `marketplace-workbench`: emits `marketplace-query`, `marketplace-command {command, rows}`; reads `tableResponse`, `commandResponse`; renders authoritative server rows; keeps attempted values of rejected rows with their reason.
- Pages `/marketplace/:connection-id/products` and `/marketplace/:connection-id/products/:listing-id` (Mapping Details).

Table: listing evidence (read-only), suggested item with picker, adopt-barcode ticks, new-product columns (name, variant, article, barcodes, unit), waiting operations; selection actions Confirm, Register, Defer.

- [ ] **Step 1: Failing web tests:** the page has one table and no `<form>`; `confirm` action returns `{:type :command :id ::products/confirm-product-mapping …}`; a `:barcode-conflict` renders on its row; a non-member gets the existing access page; text from Ozon is escaped.
- [ ] **Step 2:** FAIL. **Step 3:** Implement, following `catalogues/grid.clj` (`table`, `saved`, `failed`, `save`). **Step 4:** PASS, then the browser spec: select three rows, confirm, reload, still confirmed; keyboard only; 400 px. **Step 5:** Commit `feat(marketplace): product matching table`.

### Task 7: Mapping guard

**Files:** Create `products/guard.clj`, `test/tover/marketplace/guard_test.clj`. Board 42 slices 9, a, b.

**Interfaces:** Produces `guard/reasons [facts mapping] → [{:rule :before :after}]` with rules `"listing-identity-changed" "barcode-moved" "item-identity-changed" "item-archived"` (plan 4's eligibility calls it in its own commit); command `suspend-product-mapping {:external-product-id :mapping-revision :reasons}`; processor `mapping-guard`; read model `suspended-mappings-awaiting-review`.

- [ ] **Step 1: Failing tests:** `renamed`, `pack-changed` (`"1"` → `"2"` both shown), `article-price-cost-change-pass`, `barcode-moved-to-another-product`, `item-moved-with-its-barcodes-passes`, `unit-or-label-changed`, `archived`, `already-suspended-gains-the-new-reason`, `reconfirm-makes-current-values-the-basis`, `remap-clears-reasons`, `running-twice-emits-once`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement; the processor looks at connections marked dirty and once a minute at all. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): mapping guard`.

### Task 8: Warehouse matching

**Files:** Create `warehouses/{model,web}.clj`, tests `warehouses_test.clj`, `warehouses_web_test.clj`. Board 43.

**Interfaces:** Produces `::warehouse-mappings` id `:external-warehouse-id` (`:connection-id :warehouse-id? :mapping-revision :state :changed-by :changed-at`) with revisions as in task 2; commands `confirm-warehouse-mapping {:rows [{:external-warehouse-id :revision :warehouse-id? :warehouse-name?}]}`, `change-warehouse-mapping {:external-warehouse-id :mapping-revision :warehouse-id?}`; coeffect `::mappings`; read models `unmatched-external-warehouses`, `warehouse-mappings`; `connections/count-part :unmatched-warehouses`. New warehouse id `"listing-warehouse:<external-warehouse-id>"`. Page `/marketplace/:connection-id/warehouses`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest many-ozon-warehouses ...)   ; 12 rows, one command → 12 warehouse-registered + 12 external-warehouse-mapped
(deftest name-taken ...)             ; alias or other row; nothing accepted, row marked
(deftest fbo-warehouse-is-not-shared
  (are [a b] (= :already-used (rejection #(confirm-both a b "main")))
    "fbo" "fbo"   "fbo" "fbs"   "fbs" "fbo"))        ; also across connections
(deftest seller-warehouses-may-share (is (nil? (rejection #(confirm-both "fbs" "supply-origin" "main")))))
(deftest second-account-suggestion-adds-the-label ...) ; "Хоругвино · Shop LLC"
(deftest blank-name-suggests-the-id ...)
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS; browser check. **Step 5:** Commit `feat(marketplace): warehouse matching`; slices → `ready`.

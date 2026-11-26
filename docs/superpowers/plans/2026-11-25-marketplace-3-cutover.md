# Marketplace 3: Opening Amendment and Cutover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make wrong openings fixable, then let Owners and Admins set the instant and balances from which Ozon movements count.

**Architecture:** `tover.marketplace.provenance` holds cutover entries and baselines, readable by inventory without a dependency on the rest of the marketplace. A cutover is ordinary pending openings and corrections; accepting them emits the baseline in the same commit.

**Tech Stack:** Clojure, Bel, RDF4J, Malli, Datastar.

**Spec:** board chapters 14 (`43e0f1cc-26b2-48e5-8977-9c5dbe351850`), 17, 44 (`c0de0000-0000-4000-8000-000000000006`).

## Global Constraints

All of [the roadmap's rules](2026-11-25-marketplace-0-roadmap.md#rules-binding-every-task). Also:
- An observed quantity never changes a balance; it is frozen evidence beside the entry.
- Accepted history is append-only: an amendment is a new record revision.

## Review Focus

1. Amending an opening to an earlier date where the pair already has a record: rejected `:opening-not-first` (task 1).
2. Two entries for one scope from two connections sharing a warehouse: one opening only, the second is refused (task 4).
3. The same opening entry edited in the stock table after proposal (date, item, warehouse): refused; quantity and cost allowed (task 2).
4. Snapshot observed while a run was still going: never offered as prefill (task 3).
5. Comma decimals and more than 4 places typed as cost: same parsing as openings (task 4).

---

### Task 1: Amend an accepted opening

**Files:** Modify `inventory/entries/model.clj` (`latest-revision` :614, `check-amendment!` :639), `inventory/movements.clj` (`collapse`), `inventory/acceptance/model.clj` (`openings`), `inventory/stock/model.clj` (`history-rows`), `inventory/web.clj`, `resources/public/stock.js`; tests `amendment_test.clj`, `acceptance_test.clj`, `web_test.clj`. Board 14 slices propose, accept, read.

**Interfaces:** Produces `entries/amendable? [kind]` — true for `"opening"` and every `operation-rules` kind; `movements/collapse` returns opening facts with `:unit-cost` and `:quantity` 0 kept.

- [ ] **Step 1: Failing tests**

```clojure
(deftest wrong-cost          ; 5 at 100.00 → 120.00
  (is (= [5 600.0000M] (balance-after (amend opening {:unit-cost "120.00"})))))
(deftest amended-below-later-sales
  (is (= [:negative-balance "red" "main"] (problem-of (amend opening {:quantity 3})))))   ; sale of 4 accepted
(deftest openings-cannot-be-cancelled (is (= :validation (rejection #(save {:amends "o#1" :cancel true})))))
(deftest the-stock-item-does-not-change (is (= :validation (rejection #(save {:amends "o#1" :stock-item-id "blue"})))))
(deftest corrections-stay-unamendable ...)
(deftest moved-before-another-record (is (= :opening-not-first (code-of (amend opening {:occurred-at (day 1)})))))
(deftest amending-is-not-a-second-opening ...)  ; same pair, no :second-opening
(deftest changing-the-warehouse-moves-the-opening ...)
```

- [ ] **Step 2:** `--focus tover.inventory.amendment-test` → FAIL. **Step 3:** Implement; `openings` uses `standing` (history without the amended records), which `review` already passes. **Step 4:** Edit on an opening's history row opens Preview with quantity, unit cost, date and warehouse editable; browser check in `stock.spec.mjs`. **Step 5:** PASS; commit `feat(inventory): amend openings`.

### Task 2: Provenance and cutover rules in inventory

**Files:** Create `marketplace/provenance.clj`, `test/tover/marketplace/provenance_test.clj`; modify `entries/model.clj`, `acceptance/model.clj`; add the namespace to the registry lists of the inventory tests.

**Interfaces:** Produces
- Events `marketplace-cutover-proposed`, `cutover-baseline-established`; models `::cutover-entries` id `:entry-id` (`:connection-id :stock-item-id :warehouse-id :instant :observation(JSON)? :cost-source? :proposed-by :proposed-at`), `::baselines` id `"<connection>|<item>|<warehouse>"` (`:connection-id :stock-item-id :warehouse-id :instant :record-id :established-at`).
- Coeffect `::facts → {:cutover-entries {entry-id row} :baselines {id row}}` (plan 4 adds `:imports`, `:acceptances`).
- `cutover-baseline-established` adds `::sync/mark-dirty` for its connection through `provenance/dirty-fx [connection-ids] → [fx]`, which names the effect by keyword.
- Pure: `baseline [facts connection-id item warehouse]`, `save-problem [facts entry-before input] → nil | {:field :message}`, `acceptance-problems [facts lines] → [Problem]`, `acceptance-events [facts picked records actor now] → [event]`.
- `accept-reviewed-inventory-changes` body extracted to `acceptance/plan [facts input {:keys [actor]}] → {:events :fx}`; plan 4 reuses it. `:emits` gains `cutover-baseline-established`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest counts-from-the-cutover ...)         ; opening 120 accepted → baseline event with record-id in the same plan
(deftest moving-the-date
  (is (= "Cutover date is fixed" (message #(save {:amends "o#1" :occurred-at (day 2)})))))
(deftest pending-cutover-entry-keeps-its-scope ...) ; item, warehouse, date edits refused; quantity, cost saved
(deftest wrong-count ...)                     ; amending a baseline opening's count keeps the instant
(deftest baseline-correction-is-not-cancelled ...)
(deftest discarding-frees-the-scope ...)
(deftest acceptance-without-marketplace-facts-is-unchanged ...) ; whole acceptance_test stays green
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement; discard deletes the `cutover-entries` row through `inventory-draft-discarded {:delete true}`. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): cutover baselines`.

### Task 3: Readiness

**Files:** Create `cutover/model.clj`, `test/tover/marketplace/cutover_test.clj`. Board 44 slices 1, 5.

**Interfaces:** Consumes `sync/finished-passes`, `sync/stock-figures`, `products/::mappings`, `warehouses/::mappings`, `valuation/replay`. Produces
- `cutover/default-instant [passes]`, `cutover/earliest [positions-at-first-coverage]` (latest `:first-covered-at` of the operation domains; nil while any lacks it, and then nothing can be proposed).
- `cutover/scopes [facts {:keys [connection-id instant]}] → [{:stock-item-id :warehouse-id :readiness :observed? :observed-at? :suggested-quantity? :balance? :result :suggested-cost? :cost-source? :mapping-revisions}]`.
- `scopes` marks `needs product match` / `needs warehouse match` rows by listing or external warehouse; suggested fields appear only on `ready` rows.
- Read models `cutover-readiness-and-observed-balances`, `baseline-coverage-by-stock-item-and-warehouse`; `connections/count-part :scopes-without-baseline` and `:baselines`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest cost-from-ozon (is (= ["350" "Ozon"] (cost {:average nil :listing-cost "350" :currency "RUB"}))))
(deftest tover-average-wins ...)
(deftest listings-disagree-on-cost ...)      ; blank
(deftest moved-instant ...)                  ; suggested-quantity absent, observed present
(deftest listings-of-one-item-are-summed ...)
(deftest seller-warehouse-quantity-is-evidence-only ...)
(deftest unfinished-pass-is-not-used ...)
(deftest not-ready-names-the-missing-match ...)
(deftest balance-is-at-the-instant ...)      ; later records excluded; none → absent → result "opening"
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): cutover readiness`.

### Task 4: Propose, review, accept

**Files:** `cutover/model.clj`, `cutover/web.clj`, `inventory/web.clj` (Review shows observed, proposed, difference), tests. Board 44 slices 2–4.

**Interfaces:** Produces `propose-marketplace-cutover {:connection-id :instant :scopes [{:stock-item-id :warehouse-id :product-mapping-revisions :warehouse-mapping-revisions :quantity :unit-cost? :observation?}]}`, policy `connections/::manager`; emits `marketplace-cutover-proposed` (one per scope), `shared-inventory-entry-saved`, `inventory-entry-line-saved` through `entries/line-events`. Entry id `"cutover:<connection>|<item>|<warehouse>#<n>"`, `n` counting earlier proposals of the scope. Rejections `:not-a-manager :scope-not-ready :instant-out-of-range :cost-required :stale`.

- [ ] **Step 1: Failing tests:** `opening-and-correction` (A 120 at 350 → opening, B 8 → correction, balances unchanged), `cost-missing`, `several-batches`, `instant-before-first-coverage-names-the-earliest`, `future-instant`, `comma-cost`, `member-may-accept-but-not-propose`, `scope-already-pending-or-baselined`, `second-connection-same-scope`, and a store test that proposes, accepts through `accept-reviewed-inventory-changes`, and finds one baseline each.
- [ ] **Step 2:** FAIL. **Step 3:** Implement; Cutover view of the Operations page at `/operations/marketplace/:connection-id/cutover` (instant picker plus table; coverage table below), in the view switcher from Task 5. The connection page shows coverage counts and links there. **Step 4:** PASS; browser check. **Step 5:** Commit `feat(marketplace): cutover`; slices → `ready`.

### Task 5: Matching as Operations views (decided 2026-11-26, runs right after Task 1)

Board chapters 42, 43, 44 UI elements: matching and cutover are views of the Operations page, one per connection, not under Settings. The connection page only links to them.

**Files:** `marketplace/products/web.clj`, `marketplace/warehouses/web.clj`, `marketplace/connections/web.clj`, `inventory/operations/web.clj`, `resources/i18n/messages.json`, tests `products_web_test.clj`, `warehouses_web_test.clj`, `connections_web_test.clj`, `test/browser/marketplace.spec.mjs`.

**Interfaces:** Produces routes `/operations/marketplace/:connection-id/products`, `/operations/marketplace/:connection-id/products/:listing-id`, `/operations/marketplace/:connection-id/warehouses`; a view switcher component in `inventory/operations/web.clj` (`operations-views [{:keys [current connections]}]`) listing Operations, then per active connection Product matching, Warehouse matching (Task 4 adds Cutover). The `/settings/marketplace/:connection-id/{products,warehouses}` routes are removed, not redirected (not deployed).

- [ ] **Step 1: Failing tests:** matching pages answer at the new paths and 404 at the old ones; the connection page links to the new paths; the Operations page shows the view switcher with one entry per connection; the Operations nav tab is current on the matching pages.
- [ ] **Step 2:** FAIL. **Step 3:** Move the pages; back link goes to `/operations`. **Step 4:** PASS; browser check. **Step 5:** Commit `refactor(marketplace): matching as operations views`.

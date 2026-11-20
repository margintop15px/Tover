# Inventory Cutover: Openings Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Members can see stock on Bel products, stock items and warehouses, declare opening stock (optionally with a unit cost and a new stock item) as shared drafts with findings, review it and accept it atomically, inspect per-item history, and set the workspace currency. Legacy stock, inventory and operations code is removed.

**Architecture:** Clean cutover. Every new model lives in the Bel `:catalogue` RDF store next to the Bel catalogues (§12.1 of `docs/bel-architecture.md`), so acceptance registers catalogue records and writes ledger records in one serializable commit. Decisions are pure `defcommand` handlers over coeffects; stock, valuation and findings are pure functions over loaded records. The new stock page reuses the shared `inventory-grid` element through a new `stock-workbench` adapter, following `tover.catalogues.grid` and `catalogues.js`.

**Tech Stack:** Clojure, Bel (`defmodel`, `defcommand`, `defreadmodel`, `defview`, `defaction`, `defpage`, `defstream`), RDF4J via `bel-rdf`, Malli, Datastar, Jspreadsheet CE (`inventory-grid.js`), Kaocha, Playwright.

**Spec:** the prooph board (workspace `ec484260-1c12-45d7-a8a7-f0af8e2fc520`), all slices `planned` on 2026-11-20:
- `04a · Set workspace currency`: all slices.
- `13 · Inspect stock, history and lineage`: all slices.
- `14 · Declare opening stock`: all slices.
- `16 · Prepare and manage inventory drafts`: "Read shared pending inventory work", "Save a shared pending entry", "Read draft findings", "Read work eligible for discard", "Discard unaccepted inventory work", "Read remaining draft work". The four private-composition slices are **not** in this plan.
- `17 · Review and accept inventory changes`: all slices.
- `32a · Register catalogue records through inventory acceptance`: all slices.

Field tables, rules and rejections are in the shared element details. Read them with `get_element` before each task; they win over this plan. Each `## Examples` item in a slice becomes one test.

## Global Constraints

- Clojure work is REPL-first: attach to the worktree nREPL (`clj-nrepl-eval --discover-ports`, else start `clojure -M:dev:nrepl`), apply edits with `(user/start!)`. Never `:reload-all` or `refresh-all`.
- Fix unbalanced delimiters with `clj-paren-repair <file>`, never by hand.
- A `defaction` returns a command descriptor `{:type :command :id … :command-id … :input … :then … :catch …}` and never calls `runtime/execute!`. Tests assert the returned map.
- Malli maps stay open (no `{:closed true}`).
- Code names follow board names: `Workspace Currency Changed` is `::workspace-currency-changed`.
- Model fields are scalar (`:string :uuid :time/instant :int :boolean`, string enums). Money is an exact decimal **string** (`"100.00"`), parsed with `bigdec`. Lists (draft lines, barcodes, record lines) are child entities, one per element, never JSON fields.
- The owner may change the `Workspace Currency` at any time (user decision 2026-11-20). A change relabels existing amounts; nothing converts and nothing locks.
- Board "attempt id" is the Bel `command-id`. It is not repeated in command inputs; C2 recovery is Bel's.
- Every inventory money amount is in the `Workspace Currency`. No per-amount currency field anywhere.
- Kinds: this batch accepts kind `"opening"` only. The kind enum is `[:enum "opening"]`; later chapters widen it.
- Never touch `.data/` stores or legacy RDF graphs. No destructive store operations. No migrations in this plan.
- i18n: every new user-facing string goes into `resources/i18n/messages.json` in both `en` and `ru`.
- Board statuses: set a slice to `in-progress` when its task starts and `ready` when its tests pass. Never set `planned`, `reviewed` or `deployed`.
- Checks before every commit: `clojure -M:fmt check`, `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj`. Use the commit attribution line from the session.

## Review Focus

1. **Concurrent edits to one shared entry.** Two members save the same entry against the same revision: the second is rejected as stale and keeps the attempted value. Test in Task 4.
2. **Catalogue changes between review and accept.** A product is renamed, or a barcode is assigned elsewhere, after the review: accept must reject as Review outdated and commit nothing. Test in Task 8.
3. **Decimal input as Russian users type it.** `"100,50"` is the same as `"100.50"`. More than 4 fraction digits, negatives and text are rejected naming the field. Test in Task 4.
4. **Occurrence time across zones.** An ISO instant with an offset (`2026-11-20T00:00:00+03:00`) is stored as the same UTC instant, truncated to whole seconds. Test in Task 4.
5. **Workspace switched in another tab.** A save or accept whose input `workspace-id` differs from the request scope is rejected as Not permitted and never retargeted. Tests in Tasks 4 and 8.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/tover/inventory/stock_items/model.clj` | `stock-items` and `stock-item-barcodes` models and their events (32a, 17). |
| `src/bel/core.clj`, `src/bel/runtime.clj`, `src/bel/store.clj`, both adapters | The `:delete` event mapping (Task 1). |
| `src/tover/inventory/entries/model.clj` | `entries`, `entry-lines` and `entry-line-barcodes` models, Save Shared Inventory Entry, Discard Inventory Draft, the pending-work read model (16, 14, 32a). |
| `src/tover/inventory/acceptance/model.clj` | `acceptances`, `records` and `record-lines` models, the review read model and Accept Reviewed Inventory Changes (17, 14, 32a). |
| `src/tover/inventory/valuation.clj` | Pure replay: balances, moving average and the timeline check (13). |
| `src/tover/inventory/findings.clj` | Pure draft findings (16). |
| `src/tover/inventory/references.clj` | Pure line resolution and suggestions (32a). |
| `src/tover/inventory/stock/model.clj` | Stock table, item history and lineage read models (13). |
| `src/tover/inventory/web.clj` | Stock page, views, actions, stream (13, 14, 16, 17, 32a UI). |
| `resources/public/stock.js` | `stock-workbench` adapter over `inventory-grid`. |
| `src/tover/workspaces/currency/model.clj` | Workspace Currency model, command and read model (04a). |
| `src/tover/workspaces/currency/web.clj` | `/settings` page with the currency setting (04a). |
| `test/tover/inventory/*_test.clj`, `test/tover/workspaces/currency_test.clj` | One test per slice example plus the Review Focus tests. |
| `test/browser/stock.spec.mjs` | The tester flow end to end. |

---

### Task 1: Inventory models

**Files:**
- Modify: `src/bel/store.clj` (protocol method `-delete! [store tx scope model id]`), `src/bel/core.clj` (`attach-model!` mapping schema accepts `{:delete true}`; keys `#{:set}`, `#{:create}` or `#{:delete}`), `src/bel/runtime.clj` (`apply-event!` calls `store/-delete!` with `(get (:data event) (:id model))`), `src/bel_rdf/adapter.clj` (`delete-model!`: remove the type and every mapped field predicate of the subject in the domain graph; unrelated predicates stay, as in `write-model!`), `src/bel_postgres/adapter.clj` (`-delete!` throws `ex-info` `:unsupported-mapping`; `ponytail:` add DELETE with a privilege migration when a directory model needs it), `dev/tover/fixtures.clj` and `test/bel/runtime_test.clj` (in-memory `-delete!` = `dissoc`), `docs/bel-dsl-proposal.md` (one line on `:delete`)
- Create: `src/tover/inventory/stock_items/model.clj`, `src/tover/inventory/entries/model.clj` (models only), `src/tover/inventory/acceptance/model.clj` (models only)
- Test: `test/bel/runtime_test.clj`, `test/bel_rdf/adapter_test.clj` (or the existing RDF adapter test namespace), `test/tover/inventory/models_test.clj`

**Interfaces:**
- Consumes: `::records/workspace-member` policy (`tover.catalogues.records`), `::products/products`, `::warehouses/warehouses`.
- Produces (all `:store :catalogue`, `:policy ::records/workspace-member`):
  - `:tover.inventory.stock-items.model/stock-items`: id `:stock-item-id`; fields `:product-id` (`{:bel/ref ::products/products}`), `:variant-label`?, `:article`?, `:unit`?, `:condition [:enum "regular" "defective"]`, `:regular-item-id`?, `:status [:enum "active" "archived"]`, `:revision`. Event `::stock-item-registered` `{:create …all fields}`.
  - `:tover.inventory.stock-items.model/stock-item-barcodes`: id `:barcode-id` = `(str stock-item-id "|" barcode)`; fields `:stock-item-id` (ref), `:barcode`. Event `::stock-item-barcode-assigned` `{:create [:stock-item-id :barcode]}`.
  - Bel mapping `{:delete true}`: the event's data carries the model id; the entity is removed. Deleting a missing entity is a no-op.
  - `:tover.inventory.entries.model/entries`: id `:entry-id`; fields `:kind [:enum "opening"]`, `:occurred-at :time/instant`?, `:reason`?, `:status [:enum "pending" "accepted" "discarded"]`, `:revision`, `:saved-by :uuid`. Events `::shared-inventory-entry-saved` `{:set [:kind :occurred-at :reason :status :revision :saved-by]}` (`bel-rdf.adapter/apply-model!` writes a missing entity, so the first save needs no separate create), `::inventory-draft-discarded` and `::shared-inventory-entry-accepted`, both `{:set [:status :revision]}`.
  - `:tover.inventory.entries.model/entry-lines`: id `:entry-line-id` = `(str entry-id "/" line-id)`; fields `:entry-id` (ref), `:line-id`, `:position :int`, `:stock-item-id`? (ref), new-item fields `:product-id`? (ref) | `:product-name`?, `:variant-label`?, `:article`?, `:unit`?, then `:warehouse-id`? (ref) | `:warehouse-name`?, `:quantity :int`?, `:unit-cost`? (decimal string). Events `::inventory-entry-line-saved` `{:set …all fields}` and `::inventory-entry-line-removed` `{:delete true}`.
  - `:tover.inventory.entries.model/entry-line-barcodes`: id `(str entry-line-id "|" barcode)`; fields `:entry-line-id` (ref), `:barcode`. Events `::inventory-entry-line-barcode-added` `{:create …}` and `::inventory-entry-line-barcode-removed` `{:delete true}`.
  - `entries/Line` (Malli): the command-facing line map `{:line-id … :barcodes [..] …}` with the fields above. `(entries/line-events entry-id old-lines new-lines) -> [event]`: saved events for every new line, removed events for lines and barcodes no longer present. `(entries/assemble lines barcodes) -> [Line]` sorted by `:position`.
  - `:tover.inventory.acceptance.model/acceptances`: id `:acceptance-id`; fields `:actor :uuid`, `:recorded-at :time/instant`, `:record-count :int`. Event `::inventory-changes-accepted` `{:create …}`.
  - `:tover.inventory.acceptance.model/records`: id `:record-revision-id` = `(str record-id "#" revision)`; fields `:record-id`, `:revision`, `:acceptance-id` (ref), `:kind`, `:occurred-at`, `:sequence :int`, `:amends`?, `:cancelled :boolean`, `:reason`?. Event `::inventory-record-accepted` `{:create …}`.
  - `:tover.inventory.acceptance.model/record-lines`: id `:record-line-id` = `(str record-revision-id "/" position)`; fields `:record-revision-id` (ref), `:position :int`, `:stock-item-id` (ref), `:warehouse-id` (ref), `:quantity-change :int`, `:unit-cost`?. Event `::inventory-record-line-accepted` `{:create …}`.
The board's line lists become one event per line in the same commit, because the RDF adapter is scalar-only (§12.4 gap 1). Draft lines and accepted lines are both entities, so SHACL and SPARQL see them.

- [ ] **Step 1: Write the failing Bel tests**: `delete-mapping-removes-the-entity` in `test/bel/runtime_test.clj` (in-memory store: create, then a `{:delete true}` event, then `-load` is nil), `delete-mapping-schema` (`{:delete true}` attaches; `{:delete true :set [:x]}` is rejected), and in the RDF adapter test `delete-keeps-unrelated-predicates` (an entity with one mapped field and one foreign predicate: after delete the mapped field and type are gone, the foreign predicate stays) and `delete-of-a-missing-entity-is-a-no-op`.
- [ ] **Step 2: Run, expect FAIL** `clojure -M:dev:test --focus bel.runtime-test` (unknown mapping key).
- [ ] **Step 3: Implement `:delete`** in core, runtime, store protocol and the four implementers listed above.
- [ ] **Step 4: Run, expect PASS**, then commit `feat(bel): :delete event mapping`.
- [ ] **Step 5: Write the failing test** `models-round-trip-through-the-store`: with `fixture/with-store` and the `context` helper copied from `products_test.clj`, execute a throwaway test command that emits one of each event above, then `store/-load` each model and assert the stored maps equal the event data (barcode id `"item-1|4600000000017"`, record id `"rec-1#1"`, line id `"rec-1#1/0"`, entry line id `"entry-1/l1"`). Also `saving-a-line-without-cost-clears-it` and `removed-line-and-barcode-are-gone`, plus a pure `line-events-diff` test: old lines `l1 l2`, new lines `l2 l3` gives saved `l2 l3` and removed `l1` with its barcodes.
- [ ] **Step 6: Run, expect FAIL** `clojure -M:dev:test --focus tover.inventory.models-test` fails with unknown models.
- [ ] **Step 7: Declare the models and events** in the three namespaces, plus `Line`, `line-events` and `assemble`.
- [ ] **Step 8: Run, expect PASS.**
- [ ] **Step 9: Commit** `feat(inventory): Bel models for stock items, shared entries and accepted records`.

### Task 2: Valuation and balances

**Files:**
- Create: `src/tover/inventory/valuation.clj`
- Test: `test/tover/inventory/valuation_test.clj`

**Interfaces:**
- Consumes: record and record-line maps as stored in Task 1.
- Produces:
  - `(valuation/business-order records) -> [record]`: sort key `[occurred-at (if opening 0 1) acceptance-recorded-at sequence]`. Only the latest revision per `:record-id` that is not `:cancelled` counts.
  - `(valuation/replay records lines) -> {:balances {[stock-item-id warehouse-id] Balance} :steps [Step] :error (maybe {:type :negative-balance :stock-item-id :warehouse-id :occurred-at})}`.
  - `Balance = {:quantity int :average (maybe BigDecimal) :valued? boolean}`. `Step = {:record-revision-id :position :stock-item-id :warehouse-id :quantity-change :balance-after :unit-cost}`.
  - `(valuation/value balance) -> (maybe BigDecimal)` = quantity × average when valued.
  - `(valuation/display-amount amount currency-code) -> string`: scale from `java.util.Currency/getDefaultFractionDigits`, `HALF_EVEN`.

Rules are in `Accepted Stock by Product and Warehouse` details (Valuation section). Implement the generic line rule only: a positive line with cost blends; a positive line without cost enters at the average, or makes the pair unvalued when there is none; a negative line leaves at the average; at zero balance an unvalued pair becomes valued again on the next costed line; the last average is kept at zero. Average `setScale 4 HALF_EVEN`. `ponytail:` Transfer, Production and Defect record-level costing arrive with chapters 21–23.

- [ ] **Step 1: Write the failing tests**, one per chapter 13 slice 1 example that is about numbers:
  - `opening-cost-values-the-cell`: opening 5 at `"100.00"`, value `500.00M`.
  - `costed-incoming-moves-the-average`: 5 at 100 then +5 at `"120.00"`, average `110.0000M`, value `1100.00M`.
  - `outgoing-keeps-the-average`: 10 at 110, −4, quantity 6, value `660.00M`.
  - `uncosted-opening-is-unvalued`: `:valued? false`, `value` nil.
  - `unvalued-until-zero-then-restarts`: uncosted +3, −3, +2 at `"50"`, average `50.0000M`, valued.
  - `negative-intermediate-balance-is-an-error`: opening 5 on day 1, −5 on day 10, then −2 dated day 5; `:error` names the pair and day 10.
  - `superseded-revision-does-not-count`: revisions 1 (qty 5) and 2 (qty 6) of one record; quantity 6.
  - `opening-sorts-first-at-equal-instants`.
  - `display-amount-uses-currency-minor-units`: `"1100.00"` for EUR, `"1100"` for JPY.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement the functions above.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): moving-average valuation and timeline replay`.

### Task 3: Workspace currency (04a)

**Files:**
- Create: `src/tover/workspaces/currency/model.clj`, `src/tover/workspaces/currency/web.clj`
- Modify: `src/tover/shared/ui/core.clj:55` (add a `"/settings"` "Settings" nav link), `resources/i18n/messages.json`
- Test: `test/tover/workspaces/currency_test.clj`

**Interfaces:**
- Consumes: `::records/workspace-member` policy.
- Produces:
  - Model `::workspace-currencies` (`:store :catalogue`), id `:currency-key` always `"currency"`, fields `:currency`, `:previous-currency`, `:changed-by :uuid`, `:changed-at :time/instant`. Event `::workspace-currency-changed` `{:set …}`.
  - `(currency/current row) -> string`: `(:currency row)` or `"EUR"`.
  - Policy `::owner`: the principal's membership for the scope workspace has `:role :owner`.
  - Command `::change-workspace-currency`, input `[:map [:workspace-id :uuid] [:currency [:string {:max 10}]]]`. Rejection types `:not-an-owner`, `:unknown-currency`; workspace mismatch is `:not-permitted`. Messages come from the board.
  - Read model `::workspace-currency` -> `{:currency string :can-change? boolean}`.
  - Coeffect `::currency` -> string (the current code), used by Tasks 4, 7 and 8.
  - Page `/settings`: currency `<select>` from `Currency/getAvailableCurrencies` codes, sorted; read-only text for non-owners; a Save action returning a descriptor.

Placing the currency in `:catalogue` (not `:directory`) lets review and accept read it in the same serializable store as the records; the review fingerprint includes it, so a change between review and accept outdates the review. Add that row to §12.1 of `docs/bel-architecture.md` in this task.

- [ ] **Step 1: Write the failing tests**, one per 04a example: `new-workspace-shows-eur`, `changed-currency-is-shown`, `non-owner-sees-it-read-only`, `owner-changes-currency`, `change-relabels-accepted-amounts` (store test after Task 8 exists; until then assert the read model returns the new code; `ponytail:` extend in Task 9 with the stock table), `same-value-records-nothing` (plan is `{}`), `non-owner-rejects`, `lowercase-is-normalized-and-unknown-rejects` (`"rub"` gives `RUB`, `"XYZ"` gives `:unknown-currency`), `settings-save-returns-a-descriptor` (assert the map, `runtime/execute!` redefined to throw).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement the model, policy, command, read model, coeffects, page and messages.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(workspaces): workspace currency setting (04a)`.

### Task 4: Save and discard shared entries (16, 14, 32a)

**Files:**
- Modify: `src/tover/inventory/entries/model.clj`
- Test: `test/tover/inventory/entries_test.clj`

**Interfaces:**
- Consumes: `entries/Line`, `line-events`, `assemble` (Task 1); `::currency` coeffect (Task 3).
- Produces:
  - Command `::save-shared-inventory-entry`, input per the `Save Shared Inventory Entry` Input table, minus attempt id: `:workspace-id :uuid`, `:entry-id`, `:expected-revision`?, `:kind`, `:occurred-at`? (ISO string with offset), `:reason`?, `:lines [:vector Line]`. Emits `::shared-inventory-entry-saved` with `:revision` = expected + 1 (1 for new), plus `line-events` against the stored lines. Discard and accept emit removed events for every line and barcode.
  - Command `::discard-inventory-draft`, input `{:workspace-id :entry-id :revision}`, emits `::inventory-draft-discarded`.
  - `(entries/parse-amount s) -> BigDecimal` or a `:validation` rejection with `:field`: accepts `,` or `.`, at most 4 fraction digits, ≥ 0.
  - `(entries/parse-instant s) -> Instant` truncated to seconds.
  - Read model `::pending-entries` -> `{:entries [{:entry-id :revision :kind :occurred-at :reason :lines :blockers [{:line-id :field :code}]}]}`, pending only. Blocker codes: `:quantity-missing`, `:warehouse-missing`, `:item-missing`, `:occurred-at-missing`, `:archived-reference`.
  - Rejection types: `:stale-draft` (data `:current`), `:validation` (data `:field`), `:entry-not-pending`, `:not-permitted`.

- [ ] **Step 1: Write the failing tests**: every example on 16 "Save a shared pending entry", 16 "Read shared pending inventory work", 16 discard slices, 14 "Propose opening stock" and 32a "Save the pending entry…", plus Review Focus 1 (`second-save-against-old-revision-is-stale`), 3 (`comma-decimal-and-bounds`), 4 (`offset-instant-stored-as-utc-seconds`) and 5 (`other-workspace-id-is-not-permitted`).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement the commands, parsers, blockers and read model.** Incomplete content saves; only types are checked. New selections must reference active records (`records/check-related!`); an already-saved archived reference stays, with an `:archived-reference` blocker.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): save and discard shared inventory entries`.

### Task 5: Draft findings (16)

**Files:**
- Create: `src/tover/inventory/findings.clj`
- Test: `test/tover/inventory/findings_test.clj`

**Interfaces:**
- Consumes: decoded pending entries (Task 4), products, stock items, barcodes, warehouses, suppliers rows, `valuation/replay` balances.
- Produces: `(findings/derive {:entries :products :stock-items :barcodes :warehouses :balances}) -> [{:entry-id :line-id :field :code :level :related}]`, with codes and levels exactly as the slice's rules table: `:new-reference` info, `:similar-reference` warning, `:duplicate-variant` error, `:insufficient-stock` error / combined warning, `:barcode-conflict` error, `:article-conflict` error. `(findings/derive-within-limit facts) -> (maybe [finding])` runs `derive` in a `future` with a 2000 ms deref timeout, returning nil and logging `::findings-unavailable` on timeout or error.

Name comparison: `records/name-key` plus collapsing inner whitespace. Opening counts as incoming.

- [ ] **Step 1: Write the failing tests**: the 8 examples in "Read draft findings", plus `incomplete-entry-evaluates-without-error` and `other-workspace-data-never-appears` (facts from another workspace passed in are ignored by workspace id).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): draft findings`.

### Task 6: Reference resolution and suggestions (32a)

**Files:**
- Create: `src/tover/inventory/references.clj`
- Test: `test/tover/inventory/references_test.clj`

**Interfaces:**
- Produces:
  - `(references/resolve text {:products :stock-items :barcodes}) -> {:stock-item-id id} | {:choose [stock-item-id…] :product-id id} | {:new-product name} | nil`. Exact barcode resolves to its owner (an item, or a choice when several items of one product share it); an exact product name resolves to its only item, or to a choice.
  - `(references/suggest text facts) -> [{:kind "stock-item" | "product" :id :label :fill {…}}]`. A stock-item suggestion fills name, variant, article, barcode and unit; a product suggestion fills name and the product's default warehouse (`products/prefill-warehouse`). Active records only; at most 20.

- [ ] **Step 1: Write the failing tests**: the examples in 32a "Read inventory drafts and active catalogue suggestions" and "Resolve draft identities and warehouse choices" (`did-you-mean` comes from Task 5, so that test only asserts that `resolve` returns nil for `"main "` as a new warehouse).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): draft line resolution and suggestions`.

### Task 7: Review (17 inspect, 14 review, 32a review)

**Files:**
- Modify: `src/tover/inventory/acceptance/model.clj`
- Test: `test/tover/inventory/review_test.clj`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces:
  - `(acceptance/review facts {:selection [{:entry-id :revision}] :order [entry-id]}) -> Review`.
  - `Review = {:valid? boolean :effects [{:stock-item-id :warehouse-id :before Balance :after Balance}] :registrations [{:type :product|:stock-item|:barcode|:warehouse :id :label}] :problems [{:entry-id :line-id :code}] :fingerprint string}`.
  - Problem codes (these block acceptance): `:entry-incomplete`, `:entry-changed`, `:future-occurrence`, `:second-opening`, `:opening-not-first`, `:negative-balance`, `:uniqueness-conflict` (data `:owner-product`), `:duplicate-variant`, `:archived-reference`.
  - `(acceptance/fingerprint facts selection) -> string`: SHA-256 hex of `pr-str` of: the selection, revisions of every referenced product, stock item and warehouse, the sorted record ids of accepted lines on the affected pairs, the barcode and article owners used, and the currency.
  - Deterministic new ids: stock item `(str "item:" line-id)`; product and warehouse `(str "product:" first-line-id)` / `(str "warehouse:" first-line-id)`, where first-line-id is the smallest line id proposing that name key in the selection.
  - Read model `::inventory-review` with the input above.
  - Coeffect `::now` -> `Instant` (authoritative time; tests pass it in facts).

- [ ] **Step 1: Write the failing tests**: every example in 14 "Review the opening declaration", 32a "Read saved references and select complete entries" and "Review registration and the exact inventory effects", and 17 "Select pending changes for review" and "Inspect the reviewed effects" (the tie example is out: openings never tie on one pair, so assert there is no order choice).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): review of selected openings with registrations and fingerprint`.

### Task 8: Accept reviewed inventory changes (17, 14, 32a)

**Files:**
- Modify: `src/tover/inventory/acceptance/model.clj`
- Test: `test/tover/inventory/acceptance_test.clj`

**Interfaces:**
- Consumes: `acceptance/review`, `::now`, `::currency`, `::products/product-registered`, `::warehouses/warehouse-registered`.
- Produces:
  - Command `::accept-reviewed-inventory-changes`, input per the `Accept Reviewed Inventory Changes` Input table minus attempt id: `:workspace-id`, `:acceptance-id`, `:selection`, `:order`, `:fingerprint`. Rejection types from that element's Rejections list as keywords: `:review-outdated`, `:future-occurrence`, `:negative-balance`, `:second-opening`, `:unit-locked`, `:uniqueness-conflict`, `:archived-reference`, `:not-permitted`.
  - Emits, in one plan: registrations (`::products/product-registered`, `::stock-item-registered`, `::stock-item-barcode-assigned`, `::warehouses/warehouse-registered`), one `::inventory-record-accepted` per entry (record id = entry id, revision 1, sequence = position in `:order`), one `::inventory-record-line-accepted` per line, one `::shared-inventory-entry-accepted` per entry, and `::inventory-changes-accepted`. Fx: publish `:tover.inventory.web/changed`.
  - Read model `::acceptance-result` input `{:acceptance-id}` -> `{:records [...] :balances [...] :pending-count int}`.

- [ ] **Step 1: Write the failing tests**: every example in 14 "Accept reviewed opening stock" and "Read declared stock and its history", 32a "Accept inventory and required registrations atomically" and "Read accepted identities and the durable outcome", and 17 "Accept the exact reviewed selection" and "Read the acceptance result and remaining work". Add Review Focus 2 (`rename-after-review-is-outdated`, `barcode-assigned-elsewhere-after-review-is-outdated`) and 5 (`accept-for-other-workspace-is-not-permitted`). Store test `acceptance-retry-returns-the-same-result`: execute twice with one command id; one acceptance and one item are stored.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**: recompute `review` from the facts inside the command, reject when its fingerprint differs or it is invalid, then build the events. Uniqueness is rechecked against the facts read in the owning serializable transaction.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): atomic acceptance of openings with catalogue registrations`.

### Task 9: Stock, history and lineage read models (13) and real catalogue usage

**Files:**
- Create: `src/tover/inventory/stock/model.clj`
- Modify: `src/tover/catalogues/products/model.clj` (`::usage`), `src/tover/catalogues/warehouses/model.clj` (`::usage`); remove the two `ponytail: faked` comments.
- Test: `test/tover/inventory/stock_test.clj`

**Interfaces:**
- Produces:
  - Read model `::stock-table` input `{:hide-empty? boolean}` (default true) -> `{:currency :warehouses [{:id :name}] :products [{:product-id :name :archived? :items [{:stock-item-id :variant-label :article :barcodes :unit :unit-locked? :archived? :cells {warehouse-id {:quantity :value :valued? :pending?}} :total {:quantity :value :valued}}] :total {...}}]}`. `:valued` is `"full" | "partial" | "none"`. Products are sorted by name key, items by variant label.
  - Read model `::item-history` input `{:stock-item-id :from :to}` -> `{:rows [{:record-id :revision :kind :occurred-at :sequence :warehouse-id :quantity-change :balance-after :unit-cost :actor :recorded-at :reason :superseded? }]}`. `balance-after` comes from the full replay; rows are filtered to `[from, to)`.
  - Read model `::item-lineage` input as above -> `{:status "none"}` until chapter 22. `ponytail:` no production exists yet.
  - Products `::usage`: `{product-id {:active-stock-items n :stock-items-ever n :pending-entry entry-id}}`. Warehouses `::usage`: `{:stock? (any non-zero balance) :pending-entry id}`.

- [ ] **Step 1: Write the failing tests**: every example in 13 "Inspect accepted stock", "Inspect a product's accepted history" (default and remembered period are UI, so test `[from, to)` filtering and true running balances), "Inspect production lineage" (`none`), and the 32a example "Shared reference blocks archiving".
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): stock table, item history and real catalogue usage`.

### Task 10: Stock page

**Files:**
- Create: `src/tover/inventory/web.clj`, `resources/public/stock.js`
- Modify: `src/tover/app.clj:131` (add `'tover.stock 'tover.operations` to `:scan-exclude-ns`), `src/tover/shared/web/core.clj:47-49` (topics become `"tover.inventory.web/changed"`), `src/tover/catalogues/records.clj:92`, `src/tover/catalogues/products/model.clj` publish topic, `src/tover/shared/ui/core.clj:55` (drop the Operations link), shell script tag for `/stock.js`, `resources/i18n/messages.json`
- Test: `test/tover/inventory/web_test.clj`, `test/browser/stock.spec.mjs`

**Interfaces:**
- Consumes: the read models and commands of Tasks 3–9.
- Produces:
  - `defpage page` at `"/"`; `defstream changed`; views `::table` (stock grid model JSON), `::pending`, `::suggestions`, `::review`, `::history`, `::saved`, `::failed`; actions `::save-entry`, `::discard-entry`, `::accept` (each returns a descriptor with `:then` and `:catch` views, like `catalogues.grid/save`).
  - `(web/stock-grid table {:mode "units"|"money"}) -> grid model` in the `inventory-grid` shape used by `catalogues.grid/grid`: a product group row, item sub-rows, one column per warehouse, a Total column; unvalued money cells show `"—"`; partial totals carry `:partial true`.
  - `<stock-workbench>` in `stock.js`, built on `inventory-grid.js` like `catalogues.js`:
    - an "Add opening" draft row with cells product, item, article, barcodes, unit, warehouse, quantity, unit cost, occurred at and reason;
    - autosave of the full entry on each cell commit, with Saving / Saved / Failed;
    - finding and blocker markers on cells;
    - row checkboxes, Review, then a review panel with Accept;
    - Discard per draft row;
    - row expansion opening history with a period select (7, 30, 90, 365 days, all; default 30) and History / Lineage tabs;
    - a "Hide products without stock items" checkbox (on by default) and a Units / Money toggle.

    The period and the Units / Money mode are kept in `localStorage` (`ponytail:` per-member preference moves to chapter 30). A failed read shows Retry, never empty stock.

- [ ] **Step 1: Write the failing tests**: in `web_test.clj`, every action returns its descriptor (redefine `runtime/execute!` to throw), plus `stock-grid` tests for group rows, a dash for unvalued cells, partial totals and the hide-empty default. In `stock.spec.mjs`, the tester flow: register product "T-shirt" and warehouse "Main" on `/catalogues`; on `/`, add an opening with new item "M", quantity 5, unit cost 100; review shows 0 → 5; accept; the stock shows 5 and 500.00 in Money; the history shows the opening.
- [ ] **Step 2: Run, expect FAIL** with `clojure -M:dev:test --focus tover.inventory.web-test` and `npx playwright test stock.spec.mjs` from `test/browser`.
- [ ] **Step 3: Implement the page, views, actions, adapter and messages.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(inventory): Bel stock page with opening drafts, review and history`.

### Task 11: Remove legacy stock, inventory and operations

**Files:**
- Delete: `src/tover/stock/`, `src/tover/operations/`, and every `src/tover/inventory/*.clj` except `actors.clj` and any file still required by `tover.catalogues.rename` (it is excluded from scan and kept for the journal integration test; `ponytail:` delete with the rename slice). Delete their tests under `test/tover/{stock,operations,inventory}/` (keep the new tests), and the legacy browser specs and fixtures (`ce-*.spec.mjs`, `refine-drafts`, `smart-workflow`, `grid.fixture.mjs`, viewport configs and servers) when nothing else imports them. Delete `stock-entry.js`, `stock-operations.js` and their CSS when unreferenced.
- Modify: `src/tover/catalogues/grid.clj` (move `sheet-icon` into `tover.shared.ui.core`; remove the `data-on:inventory-layout` handler and its signals), `src/tover/app.clj` (drop the `tover.inventory.smart-web` require and the scan excludes from Task 10), `tests.edn` (drop the skips naming deleted tests), `dev/tover/fixtures.clj` (docstring), `README.md` (Stock / Operations paragraphs: openings only until 15 and 18–28), `docs/bel-architecture.md` §12.4 (gaps 1 and 5 as implemented), `docs/board-sync.edn`.

Column layout persistence on catalogues goes away with `tover.inventory.layouts`. `ponytail:` chapter 30 brings per-member layouts back. `.data/` stores and `resources/inventory/` stay untouched.

- [ ] **Step 1: Delete and modify as listed.**
- [ ] **Step 2: Verify that nothing still references deleted code.** `grep -rlE "tover\.(stock|operations)|tover\.inventory\.(store|ledger|workbench|read-model|layouts|smart)" src dev test resources` prints nothing. Lint and formatting are clean.
- [ ] **Step 3: Run the whole suite.** `clojure -M:dev:test` passes, and `npx playwright test catalogues.spec.mjs stock.spec.mjs` passes.
- [ ] **Step 4: Set every batch slice to `ready`** on the board.
- [ ] **Step 5: Commit** `refactor(inventory): remove legacy stock, inventory and operations code`.

# Amend, cancel and copy operations (board chapters 26–28) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Accepted operations are edited in place as pending revisions, cancelled, and copied on the Operations page.

**Architecture:** A pending entry gains `:amends` (a `record-revision-id`) and `:cancel`. `movements/collapse` turns an accepted revision back into entry facts; the save command, review, findings and grid all use it. Acceptance writes the next revision of the same record; `valuation/effective` already counts only the latest. Copy is a plain new save whose input names its source only so archived references can be kept.

**Tech Stack:** Clojure, Bel, RDF4J catalogue store (additive optional fields, no migration), Jspreadsheet adapters, Kaocha, Playwright.

**Spec:** prooph board chapters 26, 27, 28 and the shared details of `Save Shared Inventory Entry` and `Accept Reviewed Inventory Changes` § Amendment and cancellation. The board wins over this plan.

## Global Constraints

- Every slice example has a scenario test.
- Rejection types: `:target-not-current`, `:already-cancelled`; a second pending amendment of one record is `:stale-draft`; stale review is `:review-outdated`.
- No change to `resources/inventory` semantics, no destructive database action.
- Text goes through `i18n/t` with en and ru messages.

## Review Focus

1. A revision at an unchanged instant keeps its place among same-second records (Task 2).
2. A pending amendment must not count on top of its accepted record in findings or on the stock page (Task 3).
3. A cancellation has no lines, yet must outdate a review that touches its pairs (Task 2).
4. Editing the time or reason of a pending revision keeps `:amends` (Task 4).
5. Two edits queued before the first save returns land in one entry (Task 5).

### Task 1: Entries
Modify `src/tover/inventory/movements.clj`, `src/tover/inventory/entries/model.clj`; test `movements_test.clj` (new), `entries_test.clj`.
- [ ] `(movements/collapse record lines) -> entry facts` — `:kind :occurred-at :reason` party, payment and transfer fields, `:lines` with ids `<record-revision-id>-<position>`; test round trip `expand ∘ collapse` for every kind.
- [ ] `Entry`/`SaveEntry` gain `:amends`, `:cancel`; `SaveEntry` gains `:copies` (not stored). Tests: pending revision saved; kind change, Opening target, cancel without amends, cancel with lines are invalid; stale and cancelled target; second pending entry for one record; copy keeps an archived supplier while an ordinary save rejects it.

### Task 2: Acceptance and replay
Modify `valuation.clj`, `acceptance/model.clj`; test `amendment_test.clj` (new).
- [ ] `business-order` places a record by the revision that last changed its instant.
- [ ] `review`/`accept`: next revision, `::operation-amended`, `::operation-cancelled`, effects and fingerprint over old and new pairs, problems above. One test per example of chapters 26 and 27 review and accept slices.
- [ ] `operations` returns cancelled operations flagged, and `:revisions`.

### Task 3: Reads around pending revisions
Modify `entries/model.clj` (pending-entries), `web.clj`, `findings.clj`, `stock/model.clj`, `catalogues/grid.clj`, `stock.js`.
- [ ] Amended operations count net in findings and on the stock page; history shows an amended operation once and a cancelled one marked.

### Task 4: Operations page server side
Modify `operations/web.clj`, `web.clj` (`describe-entry`); test `operations_web_test.clj`.
- [ ] One renderer for pending, accepted, amended and cancelled groups; `cancel` and `copy` fields of `save-operation`; `below` in the table query puts a copy under its source.

### Task 5: Browser
Modify `operations.js`, `stock.css`, `messages.json`; test `operations.spec.mjs`.
- [ ] Amend, discard, cancel, revisions and copy journey in Playwright.

### Task 6: Checks
- [ ] `clojure -M:dev:test`, fmt, lint, Playwright stock config; docstrings and `docs/board-sync.edn`; board slices to `ready`.

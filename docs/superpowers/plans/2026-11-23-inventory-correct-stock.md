# Inventory: Correct Stock (chapter 15) Implementation Plan

**Goal:** Members count stock and change stock items from the stock table. A count is an absolute balance; field, barcode, move, archive and restore changes are proposed on the row; Submit reviews and accepts them atomically with openings.

**Spec:** prooph board chapter `15 · Correct stock`, all five slices `planned` 2026-11-23. Shared details of `Stock Corrected`, `Save Shared Inventory Entry` (§ Kind Correction), `Accept Reviewed Inventory Changes` (§ Stock item changes), `Inventory Record Accepted` and the `Stock Item …` events win over this plan. Each slice example is one test.

## Decisions taken while planning
- One Correction entry per stock row, so it holds one stock item's change set. `ponytail:` the board allows a list of changes; one per entry until a screen needs more.
- A change set is stored as one `entry-changes` row (id = entry id) plus `entry-change-barcodes` rows (`add`/`remove`). An empty string clears article or variant label.
- `Stock Item Updated` and `Stock Item Moved` carry the resulting values (Bel `:set` replaces every listed field).
- Unit lock: a unit change is rejected when the item has any accepted line; a unit set together with the item's first opening in one acceptance is fine.
- Archive blockers: nonzero balance after the selection's counts; any other pending entry naming the item. Defective counterparts and units in transit wait for chapters 23 and 46.

## Tasks
1. **Valuation.** A line with `:counted-quantity` sets the balance; its step carries the derived change. Count up enters at the average (unvalued when none), count down leaves at the average. Tests: `valuation_test`.
2. **Stock item events.** `stock-item-updated`, `stock-item-moved`, `stock-item-archived`, `stock-item-restored`, `stock-item-barcode-removed` on the stock item models.
3. **Entries.** Kind `correction`; `entry-changes` and `entry-change-barcodes`; save validates existing items only, no unit cost, unit list, at most one of archive/restore. Pending read includes `:change`; blockers allow change-only entries without time. Tests: `entries_test` (Propose examples).
4. **Review and accept.** Correction lines become `counted-quantity` record lines; a count needs history for its pair; change sets become catalogue events checked against the produced state: unit lock, unit list, variant/article/barcode ownership, move rules, archive blockers, restore needs product, one writer per field. Fingerprint covers changed items. Tests: `review_test`, `acceptance_test` (Review and Accept examples).
5. **Findings.** Count direction against the accepted balance; codes `unit-locked`, `archive-blocked`, `restore-needs-product`, `move-conflict`, `field-conflict`; ownership and duplicate-variant for edited values. Tests: `findings_test`.
6. **Stock table read model.** `:history?` per cell; archived items flagged; history rows label Correction with the derived change. Tests: `stock_test` (Read examples).
7. **Stock page.** Cells with history save a count to the row's Correction entry; item cells on existing rows save changes; row actions Move, Archive, Restore; "Show archived items" toggle; pending values shown as accepted → pending. Tests: `web_test`, Playwright `stock.spec.mjs`.
8. Format, lint, full suite, commit per task group, slices `ready`.

# Marketplace 5: Corrections and Moves Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Propose Ozon's later changes as amendments, show accepted imports with provenance, and find moves between Ozon warehouses by comparing snapshots.

**Architecture:** Corrections compare the new revision's proposal with the proposal of the accepted revision. Moves compare finished snapshots with replayed balances. Both only create pending entries.

**Tech Stack:** Clojure, Bel, RDF4J, Malli, Datastar.

**Spec:** board chapter 45 (`c0de0000-0000-4000-8000-000000000007`) slices 7, 8, 9, b, c.

## Global Constraints

All of [the roadmap's rules](2026-11-25-marketplace-0-roadmap.md#rules-binding-every-task). Also:
- Nothing here is ever accepted automatically.

## Review Focus

1. Cancelled after handover with the handover kept: no cancellation proposed (task 1).
2. Mapping changed after acceptance: no amendment, because the comparison uses the mappings that were accepted (task 1).
3. Snapshot taken during unfinished operations: `waiting for operations`, never a move (task 3).
4. An item sold between the snapshot and now: compared with the balance at the snapshot (task 3).
5. Backdated acceptance that makes yesterday's agreement false: the last agreeing snapshot moves back (task 3).

---

### Task 1: Source corrections

**Files:** Create `operations/corrections.clj`, `test/tover/marketplace/corrections_test.clj`; modify `provenance.clj`. Board slices 7, 8.

**Interfaces:** Consumes `proposals/proposal`, `sync/::source-revisions`, `provenance/::source-acceptances`, `entries` amendments. Produces `corrections/difference [accepted-proposal new-proposal] → nil | {:kind "amendment" :entry} | {:kind "cancellation"}` comparing only lines, quantities, warehouses and time, resolved with the accepted mapping revisions; command `propose-source-correction {:source-operation-id :revision}`; event `source-correction-proposed`, model `::source-corrections` id `:record-id`; processor `source-correction-intake`. Entry id `"import:<source-operation-id>@<record-revision>"`, `:amends` the latest record revision.

- [ ] **Step 1: Failing tests:** `quantity-changed` (one amendment to 2, stock unchanged), `newer-revision` (same entry, now 1), `edited-proposal-is-kept-with-a-conflict`, `members-own-edit-is-not-proposed-back`, `status-and-price-changes-propose-nothing`, `withdrawn-effect-is-a-cancellation`, `cancelled-after-handover-proposes-nothing`, `already-cancelled`, `discarded-is-not-repeated-for-the-same-revision`, `change-back-withdraws-the-unedited-proposal`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): source corrections`.

### Task 2: Accepted imports and provenance

**Files:** `operations/model.clj`, `inventory/operations/web.clj` (source badge, provenance panel and link; there is no marketplace operations page), tests. Board slice 9.

**Interfaces:** Produces read model `accepted-imported-operations-and-provenance {:connection-id}`: record, source operation and revision, accepted by, edited, mapping per line, later revisions, pending proposals.

- [ ] **Step 1:** Failing tests for one automatic and one edited acceptance, and a record with a pending amendment. **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): provenance of accepted imports`.

### Task 3: Stock differences

**Files:** Create `moves/model.clj`, `test/tover/marketplace/moves_test.clj`; modify `sync/model.clj` (retention). Board slice b; roadmap decision 4.

**Interfaces:** Produces `moves/balance-at [history instant]`, `moves/differences [facts run] → [{:stock-item-id :warehouse-id :ozon :tover :difference}]`, `moves/item-state → "equal"|"moved"|"unexplained"|"waiting for operations"`, `moves/last-agreeing [facts item]`, `moves/pairs [differences] → [{:from :to :quantity}]`; read model `stock-differences {:connection-id}`; event `source-stock-pass-expired` deleting figures older than the 30 last finished passes, emitted by the batch that finishes a pass.

- [ ] **Step 1: Failing tests**

```clojure
(deftest moved (is (= [{:from "W1" :to "W2" :quantity 5}] (pairs {"W1" -5 "W2" 5}))))
(deftest largest-first (is (= [["W1" "W3" 6] ["W2" "W3" 1] ["W2" "W4" 3]] (tuples (pairs {"W1" -6 "W2" -4 "W3" 7 "W4" 3})))))
(deftest lost ...)                        ; −5 / +3 → unexplained
(deftest no-agreeing-snapshot ...)        ; shown, no move
(deftest still-not-accepted-next-day ...) ; same last agreeing run
(deftest undecided-operation ...)         ; waiting; aside and history count as decided
(deftest warehouse-without-baseline ...)  ; item not compared
(deftest seller-warehouses-not-compared ...)
(deftest balance-at-the-snapshot ...)
(deftest thirty-first-pass-expires-the-oldest ...)
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): stock differences`.

### Task 4: Detected moves

**Files:** `moves/model.clj`, `moves/web.clj`, tests. Board slice c.

**Interfaces:** Produces `propose-detected-move {:connection-id :stock-item-id :last-agreeing-run :current-run}` (moves computed on commit facts), event `detected-move-proposed`, model `::detected-moves` id `"<connection>|<item>|<last-agreeing-run>"` (`:current-run :moves(JSON) :entries(JSON) :proposed-at`); entry id `"move:<key>|<from>|<to>"`; processor `move-detection`; page `/settings/marketplace/:connection-id/differences`.

- [ ] **Step 1: Failing tests:** `member-accepts` (dated one second after the last agreeing snapshot; stock changes only then), `still-there-next-day`, `explained-later` (withdrawn), `different-moves-replace-unedited`, `edited-transfer-is-kept`, `discarded-is-not-repeated`, `paused-connection`, `stale-when-a-newer-pass-exists`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS; browser check. **Step 5:** Commit `feat(marketplace): detected moves`; slices → `ready`.

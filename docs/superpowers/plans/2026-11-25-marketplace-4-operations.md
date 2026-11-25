# Marketplace 4: Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show every downloaded Ozon operation with exactly one next step, and accept posting Sales without a person.

**Architecture:** One pure classifier decides next step, proposal and eligibility for an operation from facts. Processors only turn its answer into commands; every command re-runs it on its own commit's facts.

**Tech Stack:** Clojure, Bel, RDF4J, Malli, Datastar.

**Spec:** board chapter 45 (`c0de0000-0000-4000-8000-000000000007`) slices 1–6 and a.

## Global Constraints

All of [the roadmap's rules](2026-11-25-marketplace-0-roadmap.md#rules-binding-every-task). Also:
- Automatic: Sale from a posting only. Everything else waits for a member.
- Return prefill: posting's warehouse; an FBO return in status `ReceivedBySeller` takes the connection's only mapped seller warehouse, else blank. A compensated return carries the finding "Compensated by Ozon".
- A pending entry exists only for a resolvable operation; otherwise no entry.
- A source operation is accepted at most once.
- Discard, set-aside, acceptance and import-entry saves add `::sync/mark-dirty` (plan 2) to their `:fx`; inventory code calls it through `provenance/dirty-fx [connection-ids]`.
- Deployment needs the roadmap's live check recorded on the board.

## Review Focus

1. Two accounts selling one item from one seller warehouse, both short: only the operations stock covers are accepted, in occurrence order per connection (task 5).
2. Fractional, zero, missing or huge quantity text ("1.5", "0", "10000000000"): `enter missing facts`, never truncated (task 1).
3. An operation whose lines map to one item twice: two lines, both counted once (task 1).
4. A member edits the entry while intake re-evaluates: the edit stays (task 3).
5. Worker stopped between commit and reply: the retry finds the acceptance and emits nothing (task 5).

---

### Task 1: From operation to proposal

**Files:** Create `operations/proposals.clj`, `test/tover/marketplace/proposals_test.clj`. Board 45 slice 2 table.

**Interfaces:** Produces
- `proposals/resolve-lines [facts operation] → [{:line-key :external-product-id? :mapping? :stock-item-id? :quantity? :warehouse-id? :unit-price? :baseline? :after-baseline?}]`; a line resolves its listing by SKU, then product id, then article, within the connection; two listings for one identifier resolve to none.
- `proposals/proposal [facts operation] → {:entry {:kind :occurred-at? :store-id? :from-warehouse-id? :to-warehouse-id? :reason? :lines [{:line-id "<operation-id>/<line-key>" …}]} :missing [text]} | nil`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest kind-by-evidence
  (are [op kind] (= kind (get-in (proposal op) [:entry :kind]))
    fbs-posting-handed-over "sale"      fbo-return "customer-return"  fbs-return "customer-return"
    rfbs-return "customer-return"       supply "transfer"             removal-given-out "transfer"
    removal-disposed "write-off"        cancelled-before-handover nil))
(deftest fbs-return-prefills-the-postings-warehouse ...) ; still missing nothing else
(deftest disposed-reason (is (= "Disposed by Ozon" (get-in (proposal removal-disposed) [:entry :reason]))))
(deftest price-only-in-the-workspace-currency ...)
(deftest quantities-stay-whole
  (are [text quantity] (= quantity (:quantity (first (lines text)))) "3" 3 "1.5" nil "0" nil "10000000000" nil))
(deftest one-item-on-two-lines ...) ; two lines, balance falls by their sum
(deftest supply-to-an-unmatched-drop-off ...)  ; nil: not resolvable
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): operation proposals`.

### Task 2: One next step

**Files:** Create `operations/steps.clj`, `test/tover/marketplace/steps_test.clj`. Board 45 slices 3, 5.

**Interfaces:** Produces
- `steps/next-steps` — ordered vector of the 17 board values, in table order.
- `steps/classify [facts operation] → {:next-step :group? {:decision :id :label} :entry-id? :missing :findings :suggested-correction? {:stock-item-id :warehouse-id :instant :count} :failing-conditions [text]}`.
- `steps/eligible? [facts operation]` ⇔ next step `automatic`; failing conditions name connection state, mapping, baseline, completeness, member edit, source conflict, validation.
- `facts`: `{:connection :products :mappings :guard-facts :warehouse-mappings :baselines :imports :acceptances :aside :operations :catalogue :history :entries :currency :now}`.

- [ ] **Step 1: Failing test, the precedence table**

```clojure
(deftest first-that-applies
  (are [operation facts step] (= step (:next-step (steps/classify facts operation)))
    posting (edited-conflict)          "resolve conflict"
    posting (unmatched)                "match product"
    posting (suspended unmatched-wh)   "confirm match again"
    posting (unmatched-warehouse)      "match warehouse"
    posting (no-baseline)              "set cutover"
    mixed   (baselined)                "remove old lines"
    no-time (baselined)                "waiting for Ozon"
    no-time-terminal (baselined)       "enter missing facts"
    fbs-return (baselined)             "confirm return warehouse"
    fbo-return (baselined)             "confirm return warehouse"
    fbo-return (posting-undecided)     "accept the sale first"
    posting (balance 2)                "fix shortage"
    posting (paused)                   "connection stopped"
    supply  (baselined)                "accept"
    posting (baselined)                "automatic"
    before-cutover (baselined)         "history"
    posting (aside)                    "set aside"
    cancelled-before-handover (baselined) "no effect"))
(deftest shortage-suggestion (is (= {:count 3 :instant just-before} (select-keys (suggested posting-of-3 (balance 2)) [:count :instant]))))
(deftest at-the-instant-is-history ...)     ; occurred-at = baseline instant
(deftest return-of-a-pre-cutover-sale-counts ...)
(deftest first-sync ...)                    ; 340 operations, 12 groups, no entry
(deftest edited-entry-is-not-automatic ...)
(deftest suspension-found-now-is-not-automatic ...) ; guard/reasons non-empty, no suspended event yet
```

`history`, `set aside` and `no effect` are tested before the table's other rows (roadmap decision 5): an operation in one of those states never shows another step.

- [ ] **Step 2:** FAIL. **Step 3:** Implement. Order of evaluation: no effect, set aside, history, then the table. Shortage comes from `acceptance/review` over the operation's own entry; `just-before` is one second before `:occurred-at`; the suggested correction is the count that makes every later balance ≥ 0. A missing-fact return in a terminal status that is neither received nor compensated is `enter missing facts`; compensated or cancelled without receipt is `no effect`. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): next step of every operation`.

### Task 3: Propose and withdraw entries

**Files:** Create `operations/model.clj`, `test/tover/marketplace/operations_test.clj`; modify `provenance.clj`. Board 45 slices 1, 2.

**Interfaces:** Produces
- In `provenance`: events `imported-operation-proposed`, `source-operation-accepted`, `source-operation-set-aside`; models `::imports` id `:source-operation-id` (`:revision :entry-id :entry-revision :missing(JSON) :source-conflict :proposed-at`), `::source-acceptances` id `:source-operation-id` (`:revision :record-id :accepted-by :edited :mappings-used(JSON) :accepted-at`), `::asides` id `:source-operation-id`.
- `propose-imported-operation {:source-operation-id :revision}`; the decision builds the entry from its own facts, so input carries no entry. Entry id `"import:<source-operation-id>"`, `:saved-by` the connector UUID. Edited ⇔ stored `:saved-by` differs from the connector.
- Processor `marketplace-operation-intake`.
- `actors/enrich`: an unresolved actor UUID is tried as `(principal-label context "connector" id)`.

- [ ] **Step 1: Failing tests:** `posting-without-handover-time` (pending Sale missing "actual time"), `repeat-emits-nothing`, `newer-revision-replaces-unedited`, `newer-revision-over-an-edit-is-a-conflict` (entry kept), `set-aside-is-refused`, `already-accepted-is-refused`, `mapping-changed-since-read-is-stale`, `paused-connection`, `remapped-unedited-entry-follows-the-mapping`, `no-longer-resolvable-unedited-entry-is-withdrawn`, `history-shows-ozon-account`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): propose imported operations`.

### Task 4: Set aside, and acceptance by members

**Files:** `operations/model.clj`, `provenance.clj`, `entries/model.clj` (discard), `acceptance/model.clj`. Board 45 slices a, 6; roadmap decision 2.

**Interfaces:** Produces `set-source-operations-aside {:operations [{:source-operation-id :revision}] :aside :reason?}`; `provenance/acceptance-problems` gains `:before-cutover` (line named) and `:already-accepted`; `acceptance/rejection` gains both, messages "Before cutover" and "This operation is already accepted."; `acceptance-events` adds `source-operation-accepted` with `:edited`. Fingerprint gains the import rows and baselines of the selection.

- [ ] **Step 1: Failing tests:** `not-tracked-stock` (20 rows; entries removed in the same plan; bring back → proposed again), `new-revision-stays-aside`, `return-warehouse` (W changed to V), `edited`, `with-manual-entries`, `line-before-cutover-names-the-line`, `correction-in-the-same-selection-covers-the-shortage`, `discarding-sets-aside`, `manual-acceptance-while-paused`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS, all inventory tests unchanged. **Step 5:** Commit `feat(marketplace): review imported operations`.

### Task 5: Automatic acceptance

**Files:** `operations/model.clj`, `operations_test.clj`. Board 45 slices 3, 4.

**Interfaces:** Produces `accept-imported-operation-automatically {:source-operation-id :revision :entry-id :entry-revision}`, command id and acceptance id `"auto/<source-operation-id>#<revision>"`, body: `steps/eligible?` on commit facts, refusal of any line that would register a record, then `acceptance/plan`; processor `automatic-acceptance` takes one per connection by occurrence time, then source operation id; read model `imported-proposals-and-acceptance-eligibility`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest short-stock ...)        ; nothing accepted, next step "fix shortage"
(deftest supply ...)             ; complete supply still waits
(deftest retry ...)              ; same command id → first result, no events
(deftest race-with-a-member ...) ; two threads through support/with-store: exactly one acceptance
(deftest paused-just-before (is (= :not-eligible (rejection #(accept (paused))))))
(deftest one-failure-does-not-block-the-next ...)
(deftest two-accounts-short-of-one-item ...) ; balance 3, sales of 2 and 2: one accepted, the other "fix shortage"
(deftest never-registers ...)
(deftest actor-is-the-connector ...)
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Store test with 340 operations, 12 listings: match, cutover, sync to empty queue; record its duration in the commit message. If one pass over 5,000 operations exceeds 2 s, index history per pair before continuing. **Step 6:** Commit `feat(marketplace): automatic acceptance`.

### Task 6: Operations page

**Files:** Create `operations/web.clj`, `operations_web_test.clj`; extend `marketplace.js`, `marketplace.spec.mjs`; `connections/count-part :next-steps`; `products/waiting-operations`. Board 45 slice 5; also `imported-operations-needing-review`.

- [ ] **Step 1: Failing tests:** groups most operations first ("Match Mug red – 48 operations"); hidden steps only when filtered; the suggested correction is highlighted and accepted together with its Sale; set aside and bring back on a selection; a group link opens matching with that listing filtered; no buyer data in the HTML.
- [ ] **Step 2:** FAIL. **Step 3:** Implement `/marketplace/:connection-id/operations`. **Step 4:** PASS; browser spec. **Step 5:** Commit `feat(marketplace): imported operations table`; slices → `ready`.
- [ ] **Step 6:** Stop. Ask a person to run the roadmap's live check before deployment.

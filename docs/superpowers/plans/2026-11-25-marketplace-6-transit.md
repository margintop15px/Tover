# Marketplace 6: Transit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show units travelling to and from customers, and keep their stock items from being archived.

**Architecture:** A derived view over source operations, their revisions and their acceptances. No stored state, no stock effect.

**Tech Stack:** Clojure, Bel, RDF4J, Malli, Datastar.

**Spec:** board chapter 46 (`c0de0000-0000-4000-8000-000000000008`); `Stock Item Archived`.

## Global Constraints

All of [the roadmap's rules](2026-11-25-marketplace-0-roadmap.md#rules-binding-every-task). Also:
- Transit is never part of a warehouse balance.

## Review Focus

1. Partial delivery of a multi-line posting: each line keeps its own state (task 1).
2. A return without a known posting: shown as returning, its units not counted twice (task 1).
3. Unrecognised Ozon status: `unknown`, not travelling (task 1).
4. Domain unread for exactly 30 days (task 1).
5. A wrong accepted Sale cancelled in Tover: leaves transit totals (task 1).

---

### Task 1: Transit states

**Files:** Create `transit/model.clj`, `test/tover/marketplace/transit_test.clj`.

**Interfaces:** Consumes `steps/classify`, `provenance/::facts`, `sync/::source-revisions`, sync positions. Produces `transit/shipments [facts now] → [{:source-operation-id :line-key :stock-item-id :quantity :state :counted :actual-time? :observed-at :missing?}]`, states `"travelling" "delivered" "returning" "returned" "unknown" "awaiting facts"`; `transit/blocking-items [shipments] → {stock-item-id [source-operation-id]}`; read models `shipments-in-transit {:connection-id? :from? :to?}`, `shipment-status-history {:source-operation-id}`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest posting-awaiting-dispatch-time ...) ; "awaiting facts", counted false, W shows 10
(deftest facts-arrive ...)                   ; travelling 2, counted, W shows 8
(deftest delivery-deducts-nothing-more ...)
(deftest return-restores-once ...)           ; returned only with an accepted Customer return
(deftest thirty-days (are [days state] (= state (state-after days)) 29 "travelling" 30 "unknown"))
(deftest before-cutover-and-aside-are-left-out ...)
(deftest lines-keep-their-own-state ...)
(deftest return-without-a-posting ...)
(deftest unrecognised-status (is (= "unknown" (state "brand_new_status"))))
(deftest cancelled-sale-is-not-counted ...)
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): shipments in transit`.

### Task 2: Archive guard and pages

**Files:** `transit/web.clj`; modify `inventory/changes.clj:104` (archive), `acceptance/model.clj`, `inventory/web.clj:319`; tests `review_test.clj`, `transit_web_test.clj`.

**Interfaces:** `changes/problems` facts gain `:in-transit {stock-item-id [source-operation-id]}` through coeffect `:tover.marketplace.transit.model/blocking` listed in `acceptance/review-facts`; problem `:archive-blocked` with `:source-operation-id`. Pages `/settings/marketplace/:connection-id/transit` and `…/transit/:operation-id`.

- [ ] **Step 1: Failing tests:** `travelling-units-block-archiving`, `returning-units-block-archiving`, `unknown-does-not-block`, `delivered-does-not-block`; page filters by state and connection; timeline keeps actual and observed time apart and has no buyer data.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS; browser check. **Step 5:** Commit `feat(marketplace): transit pages and archive guard`; slices → `ready`.

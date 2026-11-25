# Marketplace 1: Connect and Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the existing Ozon branch and bring connection and sync to the 2026-11-25 contracts.

**Architecture:** Keep the branch's processors, sealed secrets and revision model. Replace verification, extend the fetch steps, change warehouse and stock identity, and fold throttling into `Record Source Observations`.

**Tech Stack:** Clojure, Bel, RDF4J, Malli, Kaocha, Datastar.

**Spec:** board chapters 40 (`c0de0000-0000-4000-8000-000000000002`), 41 (`…003`); `docs/ozon-source-contracts.md`.

## Global Constraints

All of [the roadmap's rules](2026-11-25-marketplace-0-roadmap.md#rules-binding-every-task). Also:
- 15 domains, in this order: `warehouses products sku-aliases attributes attribute-definitions prices stock-totals stock-by-warehouse stock-analytics fbs-postings fbo-postings returns rfbs-returns supplies removals`.
- The branch was never deployed, so ids may change without a data migration. Local RDF stores holding branch data are left alone; use a separate `TOVER_INVENTORY_PATH`.

## Review Focus

1. Cancelled after handover with the date gone: occurred at stays (task 6).
2. A key whose role list is empty or misses one call of a domain: that domain is unreadable, connecting still works (task 2).
3. Process dies between sealing a secret and the command: the unreferenced secret is destroyed on the next attempt, never left usable (task 2).
4. A listing with no row in a finished stock pass: shows 0; in an unfinished pass: shows nothing (task 5).
5. The same warehouse id as `fbs` and as `supply-origin`: two external warehouses (task 4).

---

### Task 1: Merge the branch

**Files:** merge `claude/marketplace-sync-4a8721`; conflicts in `resources/i18n/messages.json`, `src/tover/accounts/profile/web.clj`, `src/tover/identity/provider.clj`, `src/tover/shared/ui/core.clj`. Modify `test/tover/marketplace/{connections,sync}_test.clj`, `docs/bel-architecture.md:281`.

**Interfaces:** Produces the branch's public functions unchanged: `connections/{next-sync-at,due?,domain-rows,status}`, `sync/{connector-actor,coverage,due-rows,fetch-request,payload-hash,entity-id}`, `ozon/step`, `connector/{store-secret!,destroy-secret!,api-key}`.

- [ ] **Step 1:** New worktree from `main`; `git merge --no-commit claude/marketplace-sync-4a8721`.
- [ ] **Step 2:** Resolve: `messages.json` is the union of keys (main wins on equal keys); `profile/web.clj` and `provider.clj` take main (the branch commit `6e48e4e` only reformatted them); `ui/core.clj` takes main plus the branch's marketplace navigation entry. Drop the branch's formatting-only changes in `identity/{access,session,shell}.clj`, `identity/confirm/web.clj`, `identity/recovery/model.clj`: `git diff main --stat -- src/tover/identity src/tover/accounts` must be empty.
- [ ] **Step 3:** Replace `tover.inventory.store-test` (gone on main) with `tover.support` in both tests: `fixture/with-store` → `support/with-store`, `fixture/scope` → `support/scope`. `support/bel-system` passes `:marketplace` through `:resources`.
- [ ] **Step 4:** In `docs/bel-architecture.md` §12.1 move Marketplace Connections to `:catalogue`, reason: archive + pause and automatic acceptance need one commit. Update `docs/marketplace-integration-plan.md` links to the roadmap.
- [ ] **Step 5:** `clojure -M:dev:test` → 0 failures; format and lint clean; `(user/start!)` and open `/marketplace`.
- [ ] **Step 6:** Commit `merge: marketplace connection and sync`. Delete nothing; old worktrees stay until a person says so.

### Task 2: Verify with seller info and roles

**Files:** Modify `connector.clj` (delete `probes`), `connections/model.clj`, `connections/web.clj`; tests `connector_test.clj`, `connections_test.clj`, `connections_web_test.clj`. Board 40 slices 1, 2, 8.

**Interfaces:** Produces
- `connector/domain-calls : {domain #{path}}` — the Domains table of chapter 41, every call.
- `connector/ozon-verify [post {:keys [client-id api-key]}] → Verification`, where `Verification` is `{:verification-outcome "verified"|"rejected"|"unreachable" :account-label? :account-currency? :readable-domains? :key-expires-at?}`. No `:provider-account-id`.
- `connections/Connection` gains `:account-currency :string`, `:key-expires-at {:optional true} At`. Commands lose `:provider-account-id` input; the stored value is the client id.

- [ ] **Step 1: Failing tests**

```clojure
(deftest readable-domains-come-from-roles
  (let [{:keys [post calls]} (fake {"/v1/seller/info" {:company {:name "Shop LLC" :currency "RUB"}}
                                    "/v1/roles"       {:expires_at "2027-01-01T00:00:00Z"
                                                       :roles [{:name "x" :methods (conj (domain-calls "products") "/v1/analytics/stocks")}]}})]
    (is (= {:verification-outcome "verified" :account-label "Shop LLC" :account-currency "RUB"
            :key-expires-at "2027-01-01T00:00:00Z" :readable-domains ["products" "stock-analytics"]}
           (connector/ozon-verify post credentials)))
    (is (= ["/v1/seller/info" "/v1/roles"] @calls) "no probes")))

(deftest one-missing-call-makes-the-domain-unreadable ...) ; supplies without /v1/supply-order/bundle is absent
(deftest outcome-by-status
  (are [status outcome] (= outcome (:verification-outcome (verify-with status)))
    400 "rejected" 401 "rejected" 403 "rejected" 404 "rejected"
    429 "unreachable" 500 "unreachable" :timeout "unreachable"))
(deftest unreferenced-secrets-are-destroyed ...) ; store-secret!, no command, next connect attempt → (api-key …) is nil
(deftest replacing-resets-the-failure-count ...) ; :consecutive-failures 0, next-attempt-at absent, sync-state unchanged
```

- [ ] **Step 2:** `clojure -M:dev:test --focus tover.marketplace.connector-test` → FAIL.
- [ ] **Step 3:** Implement. The connect action already seals before the command; add `connector/destroy-unreferenced! [connector workspace-id referenced]` and call it before sealing. Rejection message for missing products: "Products not readable".
- [ ] **Step 4:** Run all marketplace tests → PASS. Commit `feat(marketplace): verify Ozon keys with seller info and roles`.

### Task 3: Coverage view

**Files:** `connections/model.clj` (`status`, `BlockingReason`, read models), `connections/web.clj`. Board 40 slices 3, 9.

**Interfaces:** Produces `connections/status [connection store {:keys [manager? covered counts currency]}]`; `counts` is `{:unmatched-listings int :unmatched-warehouses int :scopes-without-baseline int :next-steps {step int} :baselines int}`. Coeffect `::connections/counts` returns zeros now; plans 2–4 replace one key each through `defmulti connections/count-part [part context connection-id]`, `:default` 0 (`{}` for `:next-steps`).

- [ ] **Step 1: Failing test**

```clojure
(deftest only-three-things-block-the-connection
  (is (= [] (:blocking-reasons (status connection {:baselines 1 :unmatched-listings 9}))))
  (is (= ["paused" "needs credentials" "no baseline"]
         (:blocking-reasons (status (assoc connection :sync-state "paused" :credentials-state "rejected") {:baselines 0}))))
  (is (:currency-notice? (status (assoc connection :account-currency "KZT") {:currency "RUB"}))))
```

- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement; page shows 15 domain rows, key expiry, currency notice, count links. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): connection coverage by next step`.

### Task 4: Warehouse identity and discovery

**Files:** Create `sync/observations.clj` (move `Observation`, `entity-id`, `observe`, `bindings`, `product-facts`, `expired-bindings`, `payload-hash` out of `sync/model.clj`, no behaviour change, separate commit); modify `sync/ozon.clj`. Board 41 slice 4.

**Interfaces:** Produces
- `observations/warehouse-id [connection-id type source-id]` → `"<connection>:<type>:<id>"`; model field `:fulfilment-type` renamed `:warehouse-type`, enum `fbo fbs rfbs supply-origin`.
- `ozon/warehouse-observation [type id name] → Observation`; FBO ones emitted beside the stock, posting, or supply that names them (a return's place is never a warehouse).
- Operation fields `:from-warehouse`, `:to-warehouse`, line `:warehouse` in observations are `{:type :id :name?}` and are stored as `warehouse-id` text.

- [ ] **Step 1: Failing tests**

```clojure
(deftest same-id-two-types-are-two-warehouses
  (is (= ["c:fbs:1" "c:supply-origin:1"] (map #(observations/warehouse-id "c" % "1") ["fbs" "supply-origin"]))))
(deftest fbo-posting-names-a-new-warehouse
  (is (some #(= ["warehouse" "fbo" "77"] ((juxt :kind :warehouse-type :source-id) %)) (observe-fbo-posting {:analytics_data {:warehouse_id 77}}))))
(deftest fbo-warehouses-never-expire ...)          ; full warehouses pass not stating it leaves :active true
(deftest pickup-point-is-not-a-warehouse ...)      ; FBS return with place 9: no warehouse observation
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement; `/v1/warehouse/fbo/seller/list` rows become `supply-origin`. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): warehouse identity by type`.

### Task 5: Prices, stock by warehouse, definitions

**Files:** `sync/ozon.clj`, `sync/observations.clj`, `sync/model.clj`, `connector.clj` (domains), `docs/ozon-source-contracts.md`.

**Interfaces:** Produces
- `connector/domains` with the 15 entries above.
- `External Product` gains `:cost`, `:cost-currency` (decimal text; `0` or blank absent); kind `"product-cost"`.
- `SourceStock` id is `"<listing>|<source>|<warehouse or type>|<run-id>"` for `warehouse-stock`, without run for the other two sources; fields `:run-id`, `:reserved`, `:fulfilment-type` incl. `fbp`.
- `sync/stock-figures [context connection-id run-id] → [row]` by SPARQL filter on run id, not a full scan.
- Sync positions gain `:first-covered-at`, set once by the first `Source Domain Traversed` of a domain: the window start for dated domains, the run start for full ones. Test: `first-coverage-is-set-once`.
- `sync/finished-passes [context connection-id domain] → [{:run-id :observed-at}]` newest first, from a `stock-passes` model written with `Source Domain Traversed`.
- `FieldDefinition` gains `:separates-variants :boolean` (roadmap decision 1).

- [ ] **Step 1:** Read the current official reference for the four new calls; record request and response fields in `docs/ozon-source-contracts.md`. A difference from the board goes on the board first.
- [ ] **Step 2: Failing tests**

```clojure
(deftest cost-is-kept-only-when-entered
  (is (= {:cost "350" :cost-currency "RUB"} (cost {:net_price 350 :currency_code "RUB"})))
  (is (nil? (cost {:net_price 0 :currency_code "RUB"}))))
(deftest figures-belong-to-their-run ...)          ; two runs → two rows; stock-figures returns one run's
(deftest no-row-in-a-finished-pass-is-zero ...)    ; finished → 0; unfinished → absent
(deftest reserved-is-inside-present ...)           ; present 10 reserved 3 → quantity 10
(deftest fourth-product-is-not-the-stock-cursor ...) ; /v4/product/info/stocks body has :cursor and no :last_id
```

- [ ] **Step 3:** FAIL. **Step 4:** Implement `step "prices"`, `step "stock-by-warehouse"` (FBO then FBS, `cursor`/`has_next`, this run's products in batches); fix `dropoff_warehouse`; never read `total`. **Step 5:** PASS. **Step 6:** Commit `feat(marketplace): prices and stock by warehouse`.

### Task 6: Operation completeness

**Files:** `sync/ozon.clj`, `sync/observations.clj`; `sync_ozon_test.clj`. Board 41 slice 4 table.

**Interfaces:** Produces
- `ozon/handed-over? [kind posting] → boolean`: status after handover (`delivering driver_pickup delivered client_arbitration arbitration not_accepted sent_by_seller`), or cancelled with `cancellation.cancelled_after_ship` (FBS) / with `fact_delivery_date` (FBO).
- Line gains `:price`, `:price-currency`; supply `:source-id` is the supply id; operation gains `:return-schema` (`fbo`|`fbs`), `:compensated` and `:disposed` for removals.
- `:missing` values, exactly: `"actual time" "line quantity" "warehouse" "receiving warehouse" "accepted quantity"`.

- [ ] **Step 1: Failing tests**

```clojure
(deftest posting-times
  (are [posting at] (= at (:occurred-at (ozon/fbs-posting posting)))
    (p "delivering" :fact "2026-11-26T10:00:00Z" :delivering "2026-11-26T09:00:00Z") "2026-11-26T10:00:00Z"
    (p "delivering" :delivering "2026-11-26T09:00:00Z")                              "2026-11-26T09:00:00Z"
    (p "awaiting_deliver" :fact "2026-11-26T10:00:00Z")                              nil))
(deftest fbo-posting-complete-after-handover ...)   ; complete, warehouse 77
(deftest fbo-return-lacks-receiving-warehouse ...) ; final_moment + quantity, place ignored, missing ["receiving warehouse"]
(deftest cancelled-fbo-posting-with-fact-date-is-handed-over ...) ; no date -> not handed over; FBS keeps cancelled_after_ship
(deftest fbs-return-lacks-receiving-warehouse ...)  ; missing ["receiving warehouse"], no to-warehouse
(deftest one-operation-per-supply ...)              ; order with 2 supplies → 2 operations
(deftest approved-quantity-summed-over-acceptance-acts ...) ; 4 + 6 → "10"; fact 9 ≠ approved 10 → "accepted quantity" missing
(deftest financial-price-wins ...)                  ; financial_data price over products price, currency kept
(deftest handover-survives-cancellation             ; roadmap decision 3
  (is (= "2026-11-26T10:00:00Z" (:occurred-at (after-revision (handed-over) (p "cancelled"))))))
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement; detail calls request analytics and financial data; `returns` window uses `visual_status_change_moment`, `rfbs-returns` uses `created_at`. `adapter-version` bumps, so unchanged operations are re-observed once. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): operation completeness by kind`.

### Task 7: Throttling and supplies traversal

**Files:** `sync/model.clj` (delete `record-fetch-setback`), `sync/ozon.clj`. Board 41 slices 3–6.

**Interfaces:** `RecordSourceObservations` gains `:resume-not-before {:optional true} At`, `:domain-unavailable {:optional true} :boolean`. `supplies` is not in `ozon/dated-domains`; its details are read for ids that are new, changed (`state_updated_date`) or non-terminal. `Step` state carries pages of at most 100 / 50 ids.

- [ ] **Step 1: Failing tests**

```clojure
(deftest throttled-for-20-minutes            ; run open, position unchanged, only provider-throttled emitted
  (is (= [::sync/provider-throttled] (events (record {:resume-not-before "2026-12-11T06:20:00Z" :observations []})))))
(deftest unavailable-leaves-the-readable-domains ...)
(deftest supplies-are-read-in-full ...)      ; second run lists all, details only the changed one
(deftest another-connections-connector-is-refused ...) ; actor of C2 on a run of C1 → :not-permitted
```

- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(marketplace): throttling through recorded batches`.

### Task 8: Archive a store, whole run, release notes

**Files:** `catalogues/stores/model.clj:106` (`archive-store`), `connections/model.clj`, `test/tover/marketplace/sync_test.clj`, `docs/identity-deployment.md`. Read board `Archive Store` first; skip with a comment on the board if that slice is not `planned`.

**Interfaces:** `archive-store` gains cofx `::stores/connections` (`:tover.marketplace.connections.model/all`, filtered by store) and emits `:tover.marketplace.connections.model/marketplace-connection-paused` with reason `"store archived"`.

- [ ] **Step 1: Failing tests:** `archiving-a-store-pauses-its-connections` (same commit, already paused ones untouched), `restoring-does-not-resume`, and `sync-through-the-store` extended to the 15 domains with one FBO warehouse discovered.
- [ ] **Step 2:** FAIL. **Step 3:** Implement; document `TOVER_CONNECTOR_KEY` (32 random bytes, base64, backed up separately; losing it means reconnecting). **Step 4:** Whole suite PASS. **Step 5:** Browser check: connect form with a fake provider, pause, resume, Sync now, 15 rows, 400 px width. **Step 6:** Commit; slices 40 and 41 → `ready`; move `docs/board-sync.edn`.

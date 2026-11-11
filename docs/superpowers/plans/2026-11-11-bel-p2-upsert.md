# Bel P2 Upsert (C5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Model mappings distinguish creation from update: `{:create [...]}` inserts and refuses an existing entity on every store; `{:set [...]}` replaces the mapped fields of an entity on every store (C5).

**Architecture:** `bel.store/Store` gains `-create!`; `-write!` becomes the update/upsert. `bel.runtime/apply-event!` picks the method from the mapping key. `PgStore` creates with a plain `INSERT` (unique violation → `:identity-conflict`) and writes with `INSERT … ON CONFLICT (id) DO UPDATE SET <mapped columns>` when the row is a complete entity, else `UPDATE … WHERE id` that must hit one row. `RdfStore` already merges on `-write!`; its `-create!` refuses an existing typed subject. Create Workspace switches to `:create`.

**Tech Stack:** Clojure, Malli, next.jdbc + HoneySQL (PostgreSQL), RDF4J, Kaocha.

**Spec:** `docs/bel-architecture.md` (§5 C5, §6.1, §9 P2). User decision 2026-11-11: creation is an explicit `:create` mapping (plain insert, `:identity-conflict` on an existing entity); `:set` is the upsert.

## Global Constraints

- Branch `bel/p2-upsert` from `bel/p1-protocols` (P1 is not merged yet).
- REPL-first (AGENTS.md): nREPL from this worktree, `:reload` only, never `:reload-all`; `clj-paren-repair` for delimiters.
- Every fn and protocol method carries `:malli/schema`; record constructors use `alter-meta!` (pattern: `tover.auth.sessions`).
- Prefer open Malli maps. No new dependencies, no SQL migration.
- A mapping is exactly one of `{:set [fields]}` or `{:create [fields]}`; fields are declared, distinct, exclude the identity (existing rule, now for both keys).
- C5 wording (spec §5): "An event's `:set` fields replace those fields on the entity named by the payload identity; other fields stay. Same meaning on every store." An absent optional mapped field clears it. A new entity requires all required fields (`docs/bel-dsl-proposal.md` §mappings).
- Tests: `clojure -M:dev:test` (baseline 584 tests / 9776 assertions) and `scripts/test-bel-workspace.sh` (baseline 33/261 + restart 2/13).

## Review Focus

1. Bob reusing Alice's workspace-id still fails `:identity-conflict`, not retryable, and Alice's name is unchanged → existing `isolation-and-no-overwrite-of-existing-workspaces` passes unchanged in Task 2.
2. A `:set` event whose payload omits an optional mapped field clears that column on Postgres (as RDF already removes the predicate) → test in Task 2.
3. A `:set` event on a Postgres identity with no row and an incomplete entity fails `:validation` (not a 500, not a partial insert) → test in Task 2.
4. A `:set` update touches only the named row; other rows and unmapped columns (`created_at`) stay → test in Task 2.
5. RDF `:create` on an existing goods subject fails `:identity-conflict` and leaves the entity unchanged → test in Task 3.

---

### Task 1: `:create` mappings and `Store/-create!` in Bel

**Files:**
- Modify: `src/bel/store.clj` (Store protocol), `src/bel/core.clj:283-290` (mapping check) and `:448-462` (event-schema check), `src/bel/runtime.clj` (`apply-event!`)
- Test: `test/bel/runtime_test.clj` (mapping validation cases at `models-reject-ambiguous-storage-and-mapping-declarations`, ~line 268)

**Interfaces:**
- Produces: `bel.store/Store` method `(-create! [store tx scope model row])` — insert the complete entity `row`; existing identity → `ex-info` `{:type :identity-conflict}`. `-write!` keeps `[store tx scope model fields row]`, docstring "Replace the mapped fields; insert when the entity is new and complete."
- Produces: a model's `:events` value is `{event {:set [k…]}}` or `{event {:create [k…]}}`; `apply-event!` calls `-create!` with `(select-keys data (conj fields id))` for `:create`, `-write!` for `:set`.

- [ ] **Step 1: Write the failing tests** in `test/bel/runtime_test.clj`. `MemoryStore` gets `-create!` (throws `:identity-conflict` when `rows` has the id, else `assoc`) and `-write!` becomes a merge (`(swap! rows update id merge row)` after `dissoc`-ing absent mapped fields).

```clojure
(deftest set-mapping-replaces-only-mapped-fields
  ;; model ::a {:id :id :store :memory :events {::changed {:set [:value]}}}, rows {"1" {:id "1" :value 0 :note "keep"}}
  ;; execute ::change with an event {:id "1" :value 1}
  (is (= {:id "1" :value 1 :note "keep"} (get @(:rows store) "1"))))

(deftest create-mapping-refuses-an-existing-entity
  ;; model ::a {:events {::changed {:create [:value]}}}, rows already holding "1"
  (is (= :identity-conflict (error-type #(runtime/execute! ctx ::change {:command-id "C" :input {:value 1}}))))
  (is (= {:id "1" :value 0 :note "keep"} (get @(:rows store) "1"))))
```

Mapping validation: add `{:events {::changed {:create [:value] :set [:value]}}}` and `{:events {::changed {:create []}}}` to the rejected cases in `models-reject-ambiguous-storage-and-mapping-declarations`; add `{:events {::changed {:create [:value]}}}` as accepted.

- [ ] **Step 2: Run** `clj-nrepl-eval -p <port> "(clojure.test/run-tests 'bel.runtime-test)"` → FAIL: `-create!` missing / `:create` rejected as a mapping.

- [ ] **Step 3: Implement** the protocol method, the mapping check (`(#{#{:set} #{:create}} (set (keys mapping)))`, fields from `(or (:set m) (:create m))`, Malli check `[:map-of :qualified-keyword [:or [:map [:set …]] [:map [:create …]]]]`), the event-schema check using the same field list, and `apply-event!` dispatch. No other runtime change.

- [ ] **Step 4: Run** the same command → PASS; then `clojure -M:dev:test` → baseline + new tests, 0 failures (adapters do not implement `-create!` yet and nothing maps `:create` outside tests).

- [ ] **Step 5: Commit** `feat(bel): create mappings and Store/-create!`

### Task 2: PostgreSQL create and upsert; Create Workspace uses `:create`

**Files:**
- Modify: `src/bel_postgres/adapter.clj` (`insert-model!` stays the `-create!` body and test seam; add `write-model!`; translate SQLState `23502` → `:validation`), `src/tover/workspaces/create/model.clj:76,84` (`:set` → `:create`)
- Test: `test/workspace_creation_integration.clj`

**Interfaces:**
- Consumes: `Store/-create!`, `-write!` (Task 1).
- Produces: `(bel-postgres.adapter/write-model! [{:keys [tx tables]} model fields row] :nil)` — when `(m/validate (:schema model) row)`: `INSERT … ON CONFLICT (<id column>) DO UPDATE SET <mapped columns> = EXCLUDED.<column>`; otherwise validate `row` against the id + mapped field schemas (optional fields may be absent) and `UPDATE <table> SET <mapped columns> WHERE <id column> = ?`; zero rows → `{:type :validation}` "A new entity requires all required fields". Absent mapped fields are written as NULL in both paths.

- [ ] **Step 1: Write the failing tests** in `test/workspace_creation_integration.clj`, calling the store directly inside `store/with-store` on `:directory`:

```clojure
(deftest set-replaces-mapped-columns-of-one-row
  ;; two users with profiles; capture alice's created_at
  ;; -write! ::model/profiles [:display-name] {:user-id alice :display-name "Alicia"}
  (is (= "Alicia" (display-name alice)))
  (is (= created-at (profile-created-at alice)))
  (is (= "Bob" (display-name bob))))

(deftest set-without-an-optional-mapped-field-clears-it
  ;; -write! ::model/profiles [:display-name] {:user-id alice}
  (is (nil? (display-name alice))))

(deftest set-on-a-missing-incomplete-entity-is-a-validation-error
  ;; -write! ::model/workspaces [:name] {:workspace-id (random-uuid) :name "X"}
  (is (= :validation (:type (error-data #(...)))))
  (is (= 0 (count-organizations id))))
```

- [ ] **Step 2: Run** `scripts/test-bel-workspace.sh` → the three new tests FAIL (insert-only `-write!`: duplicate key on profiles, NOT NULL/unknown on the others).

- [ ] **Step 3: Implement** `write-model!`, wire `PgStore` (`-create!` → `insert-model!`, `-write!` → `write-model!`), add the `23502` translation in `transact`, switch the two workspace mappings to `:create`. Remove the P1 `ponytail:` comment on `-write!`.

- [ ] **Step 4: Run** `scripts/test-bel-workspace.sh` → all pass, including unchanged `isolation-and-no-overwrite-of-existing-workspaces` and `rollback-at-every-persistence-stage` (it redefines `pg/insert-model!`, still the create path). Then `clojure -M:dev:test` → 0 failures.

- [ ] **Step 5: Commit** `feat(bel-postgres): create inserts, set upserts mapped columns`

### Task 3: RDF `-create!`

**Files:**
- Modify: `src/bel_rdf/adapter.clj` (`create-model!`, `RdfStore` `-create!`)
- Test: `test/tover/catalogues/rename_test.clj`

**Interfaces:**
- Consumes: `Store/-create!` (Task 1).
- Produces: `(bel-rdf.adapter/create-model! [{:keys [tx] :as context} model row] :nil)` — the subject already typed in the domain graph → `{:type :identity-conflict}`; else `write-model!`.

- [ ] **Step 1: Write the failing test** `create-refuses-an-existing-goods-subject`: after `baseline!`, call `store/-create!` on the `:catalogue` store inside `runtime/with-store ctx :catalogue :serializable` with `{:id "G" :name "Other" …}` → `:identity-conflict`; `(:name (:row (current ctx)))` is still `"Bolt"`. Second case: a new id `"H"` with a complete row is readable afterwards.
- [ ] **Step 2: Run** the namespace in the REPL → FAIL (no `-create!` implementation).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `clojure -M:dev:test` → 0 failures.
- [ ] **Step 5: Commit** `feat(bel-rdf): create refuses an existing subject`

### Task 4: Docs

**Files:**
- Modify: `docs/bel-architecture.md` (§5 C5 row: add "`:create` inserts and refuses an existing entity"; delete the "C5 is broken today" line; §6.1 add `-create!`; §9 P2 marked done), `docs/bel-dsl-proposal.md:154` (mapping paragraph: `:create` vs `:set`), `docs/bel-next-milestone.md` (PgStore row: create/upsert)

- [ ] **Step 1:** Update the docs above.
- [ ] **Step 2:** `clojure -M:fmt check` (only the two pre-existing files differ), `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` → 0 errors, 0 warnings.
- [ ] **Step 3: Commit** `docs(bel): record P2 create and upsert mappings`

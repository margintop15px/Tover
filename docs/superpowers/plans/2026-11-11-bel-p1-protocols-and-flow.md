# Bel P1: Protocols and Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Bel's adapter multimethods with protocols and make the acceptance/read flow plain functions, without changing any business behavior, stored data shape or Tover declaration semantics.

**Architecture:** `bel.store` defines `Transactional`, `Store`, `Outbox`, `Journal`; `bel.runtime` defines `Authority`. A `:bel/system` map in the request context names stores by key; Bel opens and shares transactions by store key. `bel-postgres` and `bel-rdf` become records built from constructor options; Tover supplies table bindings, graph naming, the legacy guard and an Authority record.

**Tech Stack:** Clojure, Malli, next.jdbc/HoneySQL, RDF4J, Integrant, Kaocha.

**Spec:** [docs/bel-architecture.md](../../bel-architecture.md) (§4, §5, §6, §9 P1, §10, §11).

## Global Constraints

- REPL-first (AGENTS.md): before editing Clojure, attach to the worktree nREPL (`clj-nrepl-eval --discover-ports`), load edits with `(require 'ns :reload)`; never `:reload-all` or `refresh-all`. Use `clj-paren-repair` for delimiter problems.
- Use `clojure -M:xref who-calls / who-dispatches` (after `clojure -M:xref init`) to find callers before deleting or renaming a var.
- Open Malli maps only; keep `:malli/schema` metadata on every public `defn`, matching existing style.
- No change to: event envelopes, receipt Nippy payload, RDF pending-record payload and graph IRIs (`bel-domain`, `bel-delivery`), journal SQL, migrations.
- Bel namespaces import no JDBC, RDF4J, Tover or integration namespace. Integrations import no Tover namespace and never each other.
- Remaining `defmulti` in `src/bel*` after P1: `runtime/policy`, `runtime/coeffect`, `runtime/effect!`, `expressions/expand-form`, plus Integrant keys. Nothing else.
- Test seams used by existing `with-redefs` stay as vars called by the flow: `bel.runtime/execute!`, `bel.runtime/with-authority`, `bel.journal/receipt`, `bel-rdf.adapter/save-pending!`, `bel-postgres.journal/save-receipt!`, `bel.core/operations`, `realtime/publish!`.
- Error `:type` values unchanged (§5 vocabulary).
- Verification: `clojure -M:dev:test` (unit), `scripts/test-bel-workspace.sh` (Docker PostgreSQL + RDF integration), `clojure -M:fmt check`, `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj`.

## Decisions fixed by this plan

| Topic | Decision |
| --- | --- |
| System map | `{:stores {key store} :journal key :scope kind :authority {:store key :impl authority} :resources {...}}`, placed in context at `:bel/system`. `:authority` optional. |
| Transactions | `:bel/transactions {store-key tx}`; join by key, else `-transact`. |
| Store scope | Adapters receive `{:kind k :id v}` from `bel.store/store-scope`; `{:user-id x}` → `{:kind :user :id x}`, also `:session-id`, `:workspace-id`. |
| Owning store | Store of models whose `:events` mapping includes any event in the command's `:emits`; none → journal store; more than one → `:configuration` at execute. |
| Read stores | A read opens, nested and up front, `:repeatable-read` transactions for the stores of every model reachable through its `:cofx` (through nested read models). |
| Coeffect store access | `(runtime/with-store ctx key isolation f)` → `(f ctx-with-:tx)`: join if open, else a transaction just for `f`. `:rdf/query` keeps its request shape and uses the system's single `RdfStore` key (more than one → `:configuration`). |
| Scope | `:scope` stays optional. Declaration `:scope` wins, else system `:scope` (Tover `:workspace`); direct model reads use the system `:scope`. Actor is always `:user-id`. |
| Model store | `:store` (keyword) required on `defmodel`. |
| Scenario helper | `bel.runtime/decide [command-id facts input] -> plan`, same validation as `execute!` (`:emits`, event schemas, registered effects). |
| Delivery | `bel.delivery/deliver-pending! [system]` loops every store satisfying `Outbox`; journal writes use `(:journal system)`. Integrant key `::worker` receives a system map. |

## Review Focus

1. Records pending in RDF before this refactor (old Nippy payload, `{:workspace-id …}` request scope) must still deliver and acknowledge. → test in Task 4.
2. User-scope and workspace-scope journal rows with equal UUIDs and command IDs stay separate after the scope conversion. → test in Task 3.
3. Authority locks on `:directory` stay held through a `:catalogue` commit, and revoked membership between two retries rejects the second. → existing goods integration scenarios must pass unchanged in Task 4.
4. A model whose `:store` key is missing from the system fails with `:configuration` naming the key, not an NPE. → test in Task 1.
5. A command whose emitted events map to models in two stores fails with `:configuration` before any transaction opens. → test in Task 2.

---

### Task 0: Baseline

**Files:** none.

- [ ] **Step 1:** The working tree on `main` contains large uncommitted work (bel slices, adapters, migrations 035–036). Ask the user how to baseline it (commit on a branch, or work on top uncommitted). Do not commit or branch without that answer.
- [ ] **Step 2:** Run `clojure -M:dev:test` and `scripts/test-bel-workspace.sh`; record pass counts. Every later task must return to these counts (± tests it adds/removes).

### Task 1: Store protocols and transaction sharing

**Files:**
- Modify: `src/bel/store.clj` (replace multimethods)
- Modify: `src/bel/journal.clj` (protocol-backed functions)
- Modify: `src/bel/runtime.clj:15-22, 96-125` (`Authority` protocol, `with-authority`)
- Test: `test/bel/store_test.clj` (rewrite)

**Interfaces:**
- Produces in `bel.store`: protocols `Transactional`, `Store`, `Outbox`, `Journal` (signatures in spec §6.1); `(store ctx key) -> store` (throws `{:type :configuration :store key}`); `(with-store ctx key isolation f) -> (f ctx)` with `:tx` and `[:bel/transactions key]` set; `(store-scope scope) -> {:kind :id}`.
- Produces in `bel.journal`: `(receipt ctx command-id)`, `(deliver! ctx delivery)`, `(history ctx)` — same names and arities as today, now via `Journal` on `(:journal system)`.
- Produces in `bel.runtime`: `(defprotocol Authority (-principal [a tx identity]))`; `(with-authority ctx owning-key isolation f)` — opens the authority store (`isolation` when it is the owning key, else `:read-committed`), calls `-principal` with `(:tx ctx)` and the context, passes the returned context to `f`. For P1 `-principal` still returns the augmented context (principal map is P3).

- [ ] **Step 1: Rewrite `store_test` against a test record.** Define in the test `(defrecord TestStore [name seen] store/Transactional (-transact [_ isolation f] …))` logging `[:begin name isolation]`, `[:commit name]`, `[:rollback name]` exactly like the current `::database` method, and `(defrecord TestAuthority [seen] runtime/Authority (-principal [_ tx ctx] (swap! seen conj :authorized) (assoc ctx :authority-connection tx)))`. Keep the three existing tests with the same names and assertions, built from `{:bel/system {:stores {:domain (->TestStore :domain seen) :identity (->TestStore :identity seen)} :authority {:store (if shared? :domain :identity) :impl (->TestAuthority seen)}}}`. Replace `a-named-resource-cannot-change-its-adapter-inside-a-transaction` with `unknown-store-key-is-a-configuration-error`: `(store/with-store ctx :missing :serializable identity)` throws `{:type :configuration :store :missing}`. Add `store-scope-converts-trusted-keys`: `{:workspace-id w}` → `{:kind :workspace :id w}`, `{:user-id u}` → `{:kind :user :id u}`, two keys → `:configuration`.
- [ ] **Step 2:** `clojure -M:dev:test --focus bel.store-test` → FAIL (protocols undefined).
- [ ] **Step 3:** Implement the `bel.store` interfaces above; delete `open-transaction`, `apply-model!`, `acceptance`, `save-acceptance!`, `domain`, `same-resource?`, `transact`. Implement `bel.runtime/Authority` and the new `with-authority`; delete `runtime/authority` multimethod. Point `bel.journal` functions at `(store/with-store ctx (:journal system) :read-committed …)` and `Journal`; delete its multimethods.
- [ ] **Step 4:** `--focus bel.store-test` → PASS. Other suites are expected to fail until Tasks 2–4.
- [ ] **Step 5:** Commit per Task 0 decision: `refactor(bel): store protocols and keyed transactions`.

### Task 2: Runtime flow as functions; `:store`, `decide`

**Files:**
- Modify: `src/bel/runtime.clj` (all of `prepare-context`, `snapshot`, `recover`, `apply-event!`, `accept!`, `history`, `load-facts`, `read*`, `read-model`, `plan`, `execute!`)
- Modify: `src/bel/core.clj:260-297` (`attach-model!` requires `:store`)
- Modify: `src/tover/workspaces/create/model.clj`, `src/tover/catalogues/rename/model.clj` (add `:store` to models)
- Test: `test/bel/runtime_test.clj`, `test/bel/core_test.clj` (model fixtures need `:store`)

**Interfaces:**
- Consumes: Task 1 `bel.store`, `with-authority`, `Authority`.
- Produces: `(execute! ctx id invocation) -> receipt`, `(read-model ctx id input) -> data`, `(history ctx) -> [events]` (same signatures as today); `(with-store ctx key isolation f)` re-exported as `runtime/with-store`; `(decide id facts input) -> {:events [...] :fx [...]}`; private `(owning-store definitions command-definition) -> key`, `(read-stores definitions id) -> #{key}`. `prepare-context` no longer reads `:bel/runtime`: scope from the declaration's `:scope`, else the system `:scope`, via `{:user :user-id :session :session-id :workspace :workspace-id}`; actor from `:user-id`; `:resources` from `(:resources system)`.
- Model lookups call `(store/-load store tx (store/store-scope scope) model id)`; mappings call `-write!` with the same argument order. Colocated journal: `(= owning (:journal system))` → `bel.journal/deliver!` in the same transaction; else `(store/-record! store tx scope delivery)`. Recovery: colocated → `bel.journal/receipt`; else `-accepted`, then `bel.journal/receipt`, else the existing `:journal-unavailable` error.
- `:cofx` app coeffects keep `(coeffect id ctx request)`; ctx keeps today's keys (`:tx` of the owning store for commands, of the first opened read store for reads — P3 trims this).

- [ ] **Step 1: Update runtime tests.** Replace `::memory` `snapshot`/`accept!`/`load-model` methods with `(defrecord MemoryStore [rows] store/Transactional (-transact [_ _ f] (f (Object.))) store/Store (-load [_ _ _ _ id] (get @rows id)) (-write! [_ _ _ _ _ row] (swap! rows assoc (:id row) row)) store/Journal (-receipt [_ _ _ _] nil) (-append! [_ _ _ _] nil) (-history [_ _ _] []))`; context `{:bel/system {:stores {:memory (->MemoryStore (atom {}))} :journal :memory :scope :user :resources {:seen (atom [])}} :user-id "alice" :allow? true :nested? true}`. Models gain `:store :memory`. `composed` gets `[:entity ::entity (fn [_ _] {:id nil})]` as its first coeffect so the store is opened for the whole read, and `::echo` records `(:tx ctx)` via `(runtime/with-store ctx :memory :repeatable-read …)`; existing identity assertions on `seen` must still hold. Remove the `{:store :rdf}` read-model bad option only if `:store` is now rejected for read models by the same check (it must be). Add:
  - `decide-runs-the-plan-check-without-a-store`: `(runtime/decide ::change {:actor "alice" :command-id "C" :now (Instant/now)} {:value 1})` returns one `::changed` event; a handler returning an undeclared event type throws `:validation`.
  - `missing-store-fails-at-declaration`: `attach-model!` without `:store` throws `:validation`.
  - `system-scope-is-the-default`: with system `:scope :workspace` and `:workspace-id` in context, `composed` (no declared scope) returns `:scope {:workspace-id …}`; the existing `explicit-scopes-use-only-trusted-identifiers` keeps proving a declared scope wins.
  - `command-mapping-two-stores-is-a-configuration-error` (Review Focus 5): a command emitting one event mapped by models in `:memory` and `:other` → `execute!` throws `:configuration` and no `-transact` ran.
  - `unknown-model-store-key` (Review Focus 4): a model with `:store :absent` read via `read-model` → `{:type :configuration :store :absent}`.
- [ ] **Step 2:** `--focus bel.runtime-test` → FAIL.
- [ ] **Step 3:** Implement. Delete `runtime/snapshot`, `accept!`, `load-model`, `check-source!` multimethods. Read opening: for each key in `(read-stores …)`, nest `with-store` at `:repeatable-read`, authority outermost via `with-authority` at `:repeatable-read` (owning key = first read store, else the authority store, so PostgreSQL-only reads such as `created-workspace` keep one Repeatable Read snapshot shared with authority, as today). `check-source!` call becomes a store responsibility (Task 4). Add `:store` to the Tover slice models (`:directory` for workspace creation, `:catalogue` for goods).
- [ ] **Step 4:** `--focus bel.runtime-test --focus bel.core-test` → PASS.
- [ ] **Step 5:** Commit: `refactor(bel): runtime flow over store protocols`.

### Task 3: PostgreSQL record and Tover directory wiring

**Files:**
- Modify: `src/bel_postgres/adapter.clj` (record `PgStore`, constructor `store`)
- Modify: `src/bel_postgres/journal.clj` (functions take `scope` `{:kind :id}`; record implements `Journal`)
- Modify: `src/tover/workspaces/persistence.clj` (`tables` data; `::membership` coeffect)
- Modify: `src/tover/auth/bel.clj` (record `CurrentSession` implementing `runtime/Authority`)
- Modify: `src/tover/workspaces/create/model.clj:110-111` (coeffect ids)
- Modify: `src/tover/shared/bel.clj` (`system` replaces `runtime-config`)
- Test: `test/workspace_creation_integration.clj`

**Interfaces:**
- Consumes: Task 1 protocols, Task 2 `with-store`.
- Produces: `(bel-postgres.adapter/store {:datasource ds :tables {model-id {:table kw :columns {field column}}}}) -> PgStore` implementing `Transactional` (today's `open-transaction` body), `Store` (`-load` = today's `load-model`; `-write!` = today's `insert-model!`, insert-only until P2), `Journal` (delegating to `receipt`, `write-batch` logic, `read-history`). Missing table binding → `{:type :configuration :model id}`.
- `bel-postgres.journal/scope-columns [scope]` maps `{:kind :user :id x}` → `{:scope_kind "user" :scope_id x}` and `:workspace` likewise; other kinds → `:configuration`. `save-receipt!` keeps its var name and is called by `-append!`.
- `tover.workspaces.persistence/tables` (def, data) replaces the three `model-binding` methods. Coeffect `:postgres/membership` renamed `::membership` (in `tover.workspaces.persistence`), using `runtime/with-store ctx :directory :read-committed`. `:postgres/receipt` stays for P1, reimplemented on `bel.journal/receipt` (renamed in P3).
- `(tover.auth.bel/->CurrentSession)`; `-principal` body = today's `authority` method body.
- `(tover.shared.bel/system {:keys [repo datasource realtime]}) -> system map` with `:directory` (PgStore on `datasource`) and `:catalogue` (Task 4); `:journal :directory`; `:scope :workspace`; `:authority {:store :directory :impl (->CurrentSession)}`; `:resources {:realtime realtime}`.

- [ ] **Step 1:** In `workspace_creation_integration.clj`, build context as `{:bel/system (wiring/system {:datasource (db "tover_app")}) :user-id … :session-id … :auth-session-id …}`; replace any `:bel/runtime` use. Add `user-and-workspace-journal-scopes-stay-separate` (Review Focus 2) if not already covered by the existing scope-collision scenario — check with `grep -n "collision\|scope_kind" test/*integration.clj` first and only add if absent.
- [ ] **Step 2:** `scripts/test-bel-workspace.sh` → FAIL on workspace scenarios.
- [ ] **Step 3:** Implement the interfaces; delete `model-binding`, journal multimethod `defmethod`s and `runtime-config`.
- [ ] **Step 4:** Script → workspace scenarios PASS.
- [ ] **Step 5:** Commit: `refactor(bel-postgres): store record and directory wiring`.

### Task 4: RDF record, outbox delivery and Goods wiring

**Files:**
- Modify: `src/bel_rdf/adapter.clj` (record `RdfStore`, constructor `store`)
- Modify: `src/bel/delivery.clj` (system-driven loop over `Outbox`)
- Modify: `src/tover/catalogues/persistence.clj` (`graphs` and `legacy-guard` as plain functions)
- Modify: `src/tover/shared/bel.clj` (`:catalogue` store; `delivery-system` replaces `delivery-context`)
- Test: `test/tover/catalogues/rename_test.clj`, `test/goods_journal_integration.clj`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `(bel-rdf.adapter/store {:repo repo :graphs (fn [scope] {:domain iri :delivery iri}) :guard (fn [tx scope] nil-or-throw)}) -> RdfStore` implementing `Transactional` (today's `open-transaction`), `Store` (`-load`/`-write!` = today's `load-model`/`apply-model!` merge), `Outbox` (`-record!` calls `save-pending!`; `-accepted`, `-pending` combining `pending-records` + `read-record` with per-record `{:source s :error e}`, `-ack!`). `:guard` runs inside `-transact` before `f` for `:serializable` transactions (replaces `check-source!`).
- `:rdf/query` request unchanged (`{:query flint-form :bindings map}`); the method finds the single `RdfStore` key in `(:stores system)` and calls `runtime/with-store` on it at `:repeatable-read`.
- `scope` for graphs: `{:kind :workspace :id uuid}`; `tover.catalogues.persistence/graph` takes that shape. The scope stored inside pending payloads stays `{:workspace-id …}`; convert with `store/store-scope` when reading a record.
- `(bel.delivery/deliver-pending! system) -> {:delivered n :failed n}`, `(deliver-one! system store-key delivery)`, `(start! system)`; `::worker` init receives a system map.
- `(tover.shared.bel/delivery-system repo writer-datasource)` → `{:stores {:catalogue (rdf store) :journal-writer (pg store on writer)} :journal :journal-writer}`.

- [ ] **Step 1:** Update `rename_test`'s `context` to `{:bel/system (wiring/system {:repo repo}) :workspace-id … :user-id … :memberships …}` (scope from the system default `:workspace`); the line-31 `with-redefs` of `with-authority` and the line-269 `:rdf/query` call keep working unchanged. Update `goods_journal_integration` contexts to `wiring/system` / `wiring/delivery-system`. Add `pre-refactor-pending-record-still-delivers` (Review Focus 1): write a pending record with `save-pending!` using a request scope `{:workspace-id w}` (as stored today), run `deliver-pending!`, assert `{:delivered 1 :failed 0}`, journal receipt present, pending payload removed, acceptance marker kept.
- [ ] **Step 2:** `--focus tover.catalogues.rename-test` and the script → FAIL.
- [ ] **Step 3:** Implement; delete `graphs`, `delivery/pending-records`, `read-record`, `acknowledge!` multimethods and their `defmethod`s, and `delivery-context`.
- [ ] **Step 4:** `clojure -M:dev:test` and the script → PASS at Task 0 counts plus added tests.
- [ ] **Step 5:** Commit: `refactor(bel-rdf): store record and outbox delivery`.

### Task 5: Cleanup and docs

**Files:**
- Modify: `docs/bel-next-milestone.md` (responsibility table: protocols and records instead of multimethods), `docs/bel-dsl-proposal.md` ("Coeffects and policies use multimethods" section: only policy/coeffect/effect remain; `runtime-config` example → `wiring/system`), `docs/bel-architecture.md` (§9 P1 marked done)
- Modify: `AGENTS.md` (mention `clojure -M:xref` for call lookups; fix "currently `035_description.sql`" to `037`)

- [ ] **Step 1:** `grep -rn "defmulti" src/bel src/bel_postgres src/bel_rdf` → only `policy`, `coeffect`, `effect!`, `expand-form`. `grep -rn "defmethod" src/tover | grep -v "ig/\|runtime/policy\|runtime/coeffect\|runtime/effect!"` → empty. `grep -rn "bel/runtime\b\|runtime-config\|delivery-context" src test dev` → empty.
- [ ] **Step 2:** Update the docs listed above.
- [ ] **Step 3:** `clojure -M:fmt check` (only the four pre-existing files may differ), `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` → no new warnings.
- [ ] **Step 4:** Commit: `docs(bel): record P1 protocols and flow`.

# Bel P3 Principal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Policies decide from the authority's principal and the trusted scope only; hook contexts are trimmed to what §6.3 lists; the journal receipt coeffect becomes Bel's `:bel/receipt`.

**Architecture:** `Authority/-principal` returns a plain principal map instead of an augmented context; `with-authority` stores it under `:principal` (nil when no authority is configured). `authorize!` calls `policy` with `{:principal :scope}` only. Coeffects receive `{:scope :actor :principal :bel/system :bel/transactions}` and reach a store through `runtime/with-store` (no `:tx`, no `:resources`). Tover's `CurrentSession` returns `{:user-id :confirmed? :password-required? :memberships}`; its two policies read that.

**Tech Stack:** Clojure, Malli, next.jdbc, RDF4J, Kaocha.

**Spec:** `docs/bel-architecture.md` §6.3 (what hooks receive), §9 P3. Branch `bel/p3-principal` from `bel/p2-upsert` (P1 and P2 are not merged).

## Global Constraints

- REPL-first (AGENTS.md): `:reload` only, never `:reload-all`; `clj-paren-repair` for delimiters; `clojure -M:xref` for callers.
- Every fn and protocol method carries `:malli/schema`. Prefer open Malli maps. No new dependencies, no migration.
- Principal (spec §6.3): "the plain map `-principal` returns (for Tover: user id, confirmation, password-required, memberships). Policies read it instead of arbitrary context keys."
- Effects keep `{:scope :actor :resources}` (already per §6.3). `Authority/-principal` keeps receiving the prepared context: identity keys (`:user-id`, `:session-id`, `:auth-session-id`) are application-defined, so Bel does not select them.
- §6.3 lists `:tx` for coeffects; since P1 coeffects open their store with `runtime/with-store`, so `:tx` is dropped and §6.3 is updated (Task 3).
- Tests: `clojure -M:dev:test` (baseline 587 tests / 9796 assertions) and `scripts/test-bel-workspace.sh` (baseline 39/270 + restart 2/13).

## Review Focus

1. Caller-supplied context keys (`:memberships`, `:confirmed?`, `:allow?`) never reach a policy; only the authority's principal does → test in Task 1.
2. With no authority configured, `:principal` is nil and a policy that needs it denies with `:forbidden`, not an NPE → test in Task 1.
3. Coeffects no longer see `:tx` or `:resources`; reading another store still goes through `with-store` and shares the operation's transaction → test in Task 1.
4. A retry after membership revocation re-derives the principal and is refused → existing `revocation-prevents-new-work-…` and `retries-do-not-restore-access-…` integration tests pass unchanged in Task 2.
5. A spoofed workspace-id with fabricated `:memberships` in the request context stays `:forbidden` for execute, read and history → existing spoof assertions in `goods_journal_integration.clj` pass unchanged in Task 2.

---

### Task 1: Principal, trimmed hook contexts and `:bel/receipt` in Bel

**Files:**
- Modify: `src/bel/runtime.clj` (`Authority` docstring, `with-authority`, `authorize!`, `load-facts` coeffect context, new `defmethod coeffect :bel/receipt`), `src/bel_postgres/journal.clj` (delete `defmethod runtime/coeffect :postgres/receipt`)
- Test: `test/bel/runtime_test.clj`, `test/bel/store_test.clj`

**Interfaces:**
- Produces: `(Authority/-principal [authority tx context] → map)` — the principal; throws `:forbidden`. `with-authority` calls `f` with `(assoc context :principal principal)`; without `:authority` in the system, `f` gets `(assoc context :principal nil)`.
- Produces: `policy` is called as `(policy id {:principal p :scope s} input)`.
- Produces: coeffect context `(select-keys context [:scope :actor :principal :bel/system :bel/transactions])`.
- Produces: `(defmethod runtime/coeffect :bel/receipt [_ context {:keys [command-id]}])` → `(journal/receipt context command-id)`.

- [ ] **Step 1: Write the failing tests.** In `runtime_test.clj` the system gets `:authority {:store :memory :impl (->TestPrincipal)}` where `TestPrincipal/-principal` returns `(select-keys context [:allow? :nested?])` (the test stands in for an application authority). `::allowed` and `::nested` read `(:principal context)`.

```clojure
(deftest policies-receive-only-principal-and-scope
  ;; ::spy policy records its context
  (is (= #{:principal :scope} (set (keys @seen-policy-context)))))

(deftest caller-context-does-not-authorize
  ;; authority returns {:allow? false}; caller context also carries :principal {:allow? true}
  (is (= :forbidden (error-type #(runtime/execute! ctx ::change {:command-id "C" :input {:value 1}})))))

(deftest without-authority-the-principal-is-nil
  ;; system without :authority; ::allowed reads (:allow? (:principal context))
  (is (= :forbidden (error-type #(runtime/execute! ctx ::change …)))))

(deftest coeffects-receive-a-trimmed-context
  (is (= #{:scope :actor :principal :bel/system :bel/transactions} (set (keys @seen-coeffect-context)))))

(deftest bel-receipt-reads-the-journal
  ;; MemoryStore -receipt returns the journaled delivery with that command-id;
  ;; read model ::receipt-view has cofx [:prior :bel/receipt (fn [_ input] input)] and returns :prior
  (let [receipt (runtime/execute! ctx ::change {:command-id "C" :input {:value 1}})]
    (is (= receipt (get-in (runtime/read-model ctx ::receipt-view {:command-id "C"}) [:result :receipt])))))
```

`::echo` stops reading `:resources`; it records `(:tx %)` into a test-level atom. `store_test.clj`'s `TestAuthority` returns `{:connection tx}` and its assertions read `(get-in ctx [:principal :connection])`.

- [ ] **Step 2: Run** `clj-nrepl-eval -p <port> "(clojure.test/run-tests 'bel.runtime-test 'bel.store-test)"` → FAIL on the new tests (policies see the whole context; coeffects see `:tx`/`:resources`; `:bel/receipt` undeclared).
- [ ] **Step 3: Implement** the Produces block. `with-authority`: `#(f (assoc % :principal (-principal impl (:tx %) %)))`; the no-authority branch assoc's nil. Move the receipt coeffect.
- [ ] **Step 4: Run** the same command → PASS. Tover namespaces still read the old context until Task 2; Kaocha runs in Task 2.
- [ ] **Step 5: Commit** `refactor(bel): policies read the authority principal`

### Task 2: Tover principal and policies

**Files:**
- Modify: `src/tover/auth/bel.clj` (`current` returns the principal; no `:tx`/`:workspace-id` assoc), `src/tover/workspaces/create/model.clj` (`::ordinary-user`, `:postgres/receipt` → `:bel/receipt`), `src/tover/catalogues/rename/model.clj` (`::workspace-member`)
- Test: `test/tover/catalogues/rename_test.clj` (`unit-authority` redef assoc's `:principal` built from the fixture context), `test/workspace_creation_integration.clj`, `test/goods_journal_integration.clj` (only where they read the old context)

**Interfaces:**
- Consumes: Task 1 principal, policy context and `:bel/receipt`.
- Produces: `(tover.auth.bel/current [tx context] → {:user-id uuid :confirmed? boolean :password-required? boolean :memberships [{:workspace-id uuid :role keyword}]})`; `:memberships` is `[]` in user scope. Same session, confirmation and membership checks as today.
- `::ordinary-user`: `confirmed?`, not `password-required?`, `(= (:user-id principal) (:user-id scope))`. `::workspace-member`: some membership whose `:workspace-id` equals `(:workspace-id scope)`.

- [ ] **Step 1: Write the failing test** `principal-is-the-current-session` in `workspace_creation_integration.clj`: `(runtime/with-authority (runtime/prepare-context ctx) #{} :read-committed :principal)` returns exactly `{:user-id … :confirmed? true :password-required? false :memberships []}`.
- [ ] **Step 2: Run** `scripts/test-bel-workspace.sh` → the new test FAILS (authority returns a context), and policy-dependent tests fail after Task 1.
- [ ] **Step 3: Implement** the Produces block; update `unit-authority` in `rename_test.clj` to `(fn [context _ _ read] (read (assoc context :principal {:user-id (:user-id context) :memberships (:memberships context)})))`.
- [ ] **Step 4: Run** `clojure -M:dev:test` → 0 failures; `scripts/test-bel-workspace.sh` → all pass, including Review Focus 4 and 5 tests unchanged.
- [ ] **Step 5: Commit** `refactor(tover): policies read the current-session principal`

### Task 3: Docs

**Files:**
- Modify: `docs/bel-architecture.md` (§6.3 coeffect row → `{:scope :actor :principal}` plus `with-store`; §9 P3 marked done), `docs/bel-dsl-proposal.md` (policy/coeffect context paragraph), `docs/bel-next-milestone.md` if it names `:postgres/receipt`

- [ ] **Step 1:** `grep -rn "postgres/receipt" src test docs` → only historical plan files.
- [ ] **Step 2:** Update the docs above.
- [ ] **Step 3:** `clojure -M:fmt check` (only `src/bel/handler.clj`, `src/tover/shared/supabase.clj` differ), `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` → 0 errors, 0 warnings.
- [ ] **Step 4: Commit** `docs(bel): record P3 principal`

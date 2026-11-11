# Bel P4 System Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Bel system and the journal delivery worker are Integrant keys in `tover.app`; the handler receives `:bel/system`; the local development profile runs the worker with its private journal login while the two slices stay excluded from routes.

**Architecture:** `tover.app/config` adds `::bel-system` (built by `tover.shared.bel/system` from `::repo`, `::datasource`, `::realtime`) and passes it to `::handler` as `:bel/system`. When both `TOVER_JOURNAL_DB_USER` and `TOVER_JOURNAL_DB_PASSWORD` are set it also adds `::journal-datasource`, `::delivery-system` (built by `tover.shared.bel/delivery-system`) and the existing `:bel.delivery/worker` key. Production stays unchanged until a deployment sets those two variables. The local identity profile applies 035/036, gives `tover_journal` a local login password and exports the two variables.

**Tech Stack:** Clojure, Integrant 1.0.1 (default `init-key` resolves the var named by the key, as `repo`/`datasource` do today), Malli, next.jdbc, Bash/Python profile script, Docker Compose.

**Spec:** `docs/bel-architecture.md` §9 P4: "`bel/system` and delivery worker as Integrant keys in `tover.app`; delete `runtime-config`/`delivery-context`" — done when "Dev system starts with slices excluded; worker running." `runtime-config`/`delivery-context` were already deleted in P1 (`grep -rn "runtime-config\|delivery-context" src dev test` → nothing). Branch `bel/p4-system` from `bel/p3-principal` (P1–P3 not merged).

## Global Constraints

- REPL-first (AGENTS.md): `:reload` only, never `:reload-all`; `clj-paren-repair` for delimiters; `clojure -M:xref` for callers. Dependency set unchanged, so no REPL restart.
- Every fn carries `:malli/schema`. Prefer open Malli maps. No new dependencies, no new migration (035/036 already exist; the local profile only applies them).
- Slices stay excluded: `bel/init!` keeps `:scan-exclude-ns ['tover.catalogues.rename 'tover.workspaces.create]` (spec: cutover is P5/P6).
- Journal credentials: the worker uses the private `tover_journal` role (migration 036: "Provision a private worker login separately. It gets no directory, Auth or session access."), never `tover_app`. Credentials stay out of Git, logs and browser assets.
- Optional pair: both journal variables → worker; neither → no worker keys; exactly one → `{:type :configuration}` naming the missing variable.
- Database safety: `identity.sh up` changes the developer's local database (additive migrations, `ALTER ROLE … LOGIN PASSWORD`). The executor asks the user before running it.
- Tests: `clojure -M:dev:test` (baseline 592 tests / 9804 assertions) and `scripts/test-bel-workspace.sh` (baseline 40/271 + restart 2/13).

## Review Focus

1. Only `TOVER_JOURNAL_DB_USER` set (password missing, or the reverse) fails `:configuration` at config time, not a pool error at first scan → test in Task 1.
2. The worker halts before the repository and the journal pool close; a normal `app/stop!` returns nil with the worker running → test in Task 1.
3. A system without journal variables (today's production `compose.yaml`) starts exactly as before: no worker keys, handler still built → test in Task 1.
4. An existing local `.env` without `TO_DEV_JOURNAL_PASSWORD` gets one appended by `up` (mode 0600) instead of compose failing on the `:?` guard; `init` still refuses to overwrite → script check in Task 2.
5. `user/dev-config` keeps the worker keys and the handler's `:bel/system` after it moves `::app/handler` to `:user/handler` → test in Task 2.

---

### Task 1: Integrant keys for the Bel system and the delivery worker

**Files:**
- Modify: `src/tover/app.clj` (requires `[bel.delivery :as delivery]` and `[tover.shared.bel :as wiring]`; new fns `bel-system`, `journal-datasource`, `delivery-system`; `ig/halt-key! ::journal-datasource`; `config`; `HandlerOptions`; `create-handler` context; `SystemState`; `stop!`/`start!` cleanup lists), `src/tover/shared/bel.clj` (`delivery-system` takes a map)
- Test: `test/tover/app_test.clj`, `test/goods_journal_integration.clj` (helper at line 68 only)

**Interfaces:**
- Produces: `(tover.app/bel-system [{:keys [repo datasource realtime]}] → map)` = `wiring/system`.
- Produces: `(tover.app/journal-datasource [options] → datasource)` = `database/datasource`; halt closes it with `database/close!`.
- Produces: `(tover.app/delivery-system [{:keys [repo datasource]}] → map)` = `(wiring/delivery-system {:repo repo :datasource datasource})`.
- Produces: `(tover.shared.bel/delivery-system [{:keys [repo datasource]}] → map)` (was two positional args).
- Produces: config keys, all optional in `SystemState`:

```clojure
::bel-system         {:repo (ig/ref ::repo) :datasource (ig/ref ::datasource) :realtime (ig/ref ::realtime)}
[::handler :bel/system] (ig/ref ::bel-system)
;; only when both journal variables are set:
::journal-datasource {:jdbc-url <TOVER_JDBC_URL> :user <TOVER_JOURNAL_DB_USER> :password <TOVER_JOURNAL_DB_PASSWORD>}
::delivery-system    {:repo (ig/ref ::repo) :datasource (ig/ref ::journal-datasource)}
:bel.delivery/worker (ig/ref ::delivery-system)
```

- `HandlerOptions` gains `[:bel/system {:optional true} :map]`; `create-handler` adds `:bel/system` to its `select-keys` context.

- [ ] **Step 1: Write the failing tests** in `test/tover/app_test.clj`. `environment` is the synthetic map from `dev_reload_test.clj:121-127`.

```clojure
(deftest bel-system-is-configured-for-the-handler
  (let [c (app/config 3031 "x" {:development? true :environment environment})]
    (is (= {:repo (ig/ref ::app/repo) :datasource (ig/ref ::app/datasource) :realtime (ig/ref ::app/realtime)}
           (::app/bel-system c)))
    (is (= (ig/ref ::app/bel-system) (get-in c [::app/handler :bel/system])))
    (is (not-any? #(contains? c %) [::app/journal-datasource ::app/delivery-system :bel.delivery/worker]))))

(deftest journal-variables-configure-the-worker
  (let [c (app/config 3031 "x" {:development? true
                                :environment (assoc environment "TOVER_JOURNAL_DB_USER" "tover_journal"
                                                                "TOVER_JOURNAL_DB_PASSWORD" "synthetic-journal")})]
    (is (= {:jdbc-url (get environment "TOVER_JDBC_URL") :user "tover_journal" :password "synthetic-journal"}
           (::app/journal-datasource c)))
    (is (= {:repo (ig/ref ::app/repo) :datasource (ig/ref ::app/journal-datasource)} (::app/delivery-system c)))
    (is (= (ig/ref ::app/delivery-system) (:bel.delivery/worker c)))))

(deftest one-journal-variable-is-a-configuration-error
  (doseq [extra [{"TOVER_JOURNAL_DB_USER" "tover_journal"} {"TOVER_JOURNAL_DB_PASSWORD" "synthetic-journal"}]]
    ;; error-data: (try (f) nil (catch ExceptionInfo e (ex-data e)))
    (is (= :configuration (:type (error-data #(app/config 3031 "x" {:development? true :environment (merge environment extra)})))))))

(deftest worker-runs-with-the-system-and-halts-before-its-resources
  ;; fixture-config + ::app/journal-datasource {} + ::app/delivery-system {:repo ref :datasource ref} + :bel.delivery/worker ref
  ;; redefs: database/datasource → (Object.), database/close! → records :pool-close once (stop! closes it again),
  ;;         delivery/start! → wraps the real start!, its close records :worker-close
  (let [system (app/start! configuration)]
    (is (instance? AutoCloseable (:bel.delivery/worker system)))
    (is (nil? (app/stop! system)))
    (is (= [:worker-close :pool-close] (filterv #{:worker-close :pool-close} @calls)))))
```

`goods_journal_integration.clj` `delivery-system` helper calls `(wiring/delivery-system {:repo repo :datasource (writer)})`.

- [ ] **Step 2: Run** `clj-nrepl-eval -p <port> "(clojure.test/run-tests 'tover.app-test)"` → FAIL: no `::app/bel-system`, no worker keys, no `:configuration` for a lone variable.
- [ ] **Step 3: Implement** the Produces block. In `config`, read both journal variables with `not-empty`; when exactly one is present throw `(ex-info (str "Missing configuration: " <other name>) {:type :configuration})`. Mirror `::datasource` in the `stop!` and `start!` cleanup lists for `::journal-datasource`. Integrant's reverse dependency order halts the worker before `::delivery-system`'s resources.
- [ ] **Step 4: Run** the same command → PASS; `clojure -M:dev:test` → 0 failures; `scripts/test-bel-workspace.sh` → all pass (helper signature change only).
- [ ] **Step 5: Commit** `feat(tover): Bel system and delivery worker as Integrant keys`

### Task 2: Local profile runs the worker

**Files:**
- Modify: `deployment/local-identity/identity.sh` (`init`, `up`, `app-env`), `deployment/local-identity/compose.yaml` (db service env), `dev/user.clj` (`local-environment`), `docs/identity-deployment.md` (variable list and profile steps), `docs/bel-architecture.md` (§9 P4 marked done; note the optional journal pair), `docs/bel-next-milestone.md` if it describes worker wiring
- Test: `test/tover/dev_reload_test.clj`

**Interfaces:**
- Consumes: Task 1 config keys and the optional journal pair.
- Produces: `.env` key `TO_DEV_JOURNAL_PASSWORD` (generated like `TO_DEV_APP_PASSWORD`); `user/local-environment` additionally returns `"TOVER_JOURNAL_DB_USER" "tover_journal"` and `"TOVER_JOURNAL_DB_PASSWORD" <TO_DEV_JOURNAL_PASSWORD>`; `app-env` exports the same two.
- `up`, in this order: append `TO_DEV_JOURNAL_PASSWORD=<token_urlsafe(32)>` to `.env` when absent (keep mode 0600, never rewrite other keys); start db/mail; db-roles; compose up; apply 033, 034, 035, 036; `ALTER ROLE tover_app WITH PASSWORD :'app_password'` and `ALTER ROLE tover_journal WITH LOGIN PASSWORD :'journal_password'` from the container environment (`compose.yaml` passes `TO_DEV_JOURNAL_PASSWORD` to `db` with the same `:?` guard as `TO_DEV_APP_PASSWORD`).

- [ ] **Step 1: Write the failing tests** in `dev_reload_test.clj`: the `local-environment` expectation (line ~221) gains the two journal entries from `.env` content `TO_DEV_JOURNAL_PASSWORD=synthetic-journal`; the `TOVER_CONFIG_CHECK` program adds the journal pair to `environment` and asserts `:worker? (= (ig/ref :tover.app/delivery-system) (:bel.delivery/worker development))` and `:bel-system? (= (ig/ref :tover.app/bel-system) (get-in development [:user/handler :bel/system]))`.
- [ ] **Step 2: Run** `clj-nrepl-eval -p <port> "(clojure.test/run-tests 'tover.dev-reload-test)"` → FAIL on the journal entries.
- [ ] **Step 3: Implement** the Produces block and the doc updates. `bash -n deployment/local-identity/identity.sh` → no output.
- [ ] **Step 4: Check the append** against a scratch copy: `cp` the script and a synthetic `.env` without the key into a temp profile dir, run only the append snippet → key added once, second run adds nothing, `stat -f %Lp` → `600`.
- [ ] **Step 5: Ask the user** before `deployment/local-identity/identity.sh up` (it changes the local database). On yes: run it, then `clj-nrepl-eval -p <port> "(user/stop!) (user/start!)"` → `:initiated`; `(some? (:bel.delivery/worker integrant.repl.state/system))` → true; no `:bel.delivery/delivery-scan-failed` in the REPL log after 5 s. Slice exclusion is covered by the unchanged `bel/init!` call and the existing route tests in Step 6. On no: record that the dev check is pending in the ledger and the final message.
- [ ] **Step 6: Run** `clojure -M:dev:test` → 0 failures; `scripts/test-bel-workspace.sh` → all pass; `clojure -M:fmt check` (only `src/bel/handler.clj`, `src/tover/shared/supabase.clj` differ); `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` → 0 errors, 0 warnings.
- [ ] **Step 7: Commit** `feat(dev): local profile runs the journal worker` and `docs(bel): record P4 system wiring`

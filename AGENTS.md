<!-- jbcontext-instructions-start -->
# Tools

## Semantic Code Search (jbcontext)

`jbcontext search "<query>"` searches the project's code with a prebuilt index that ranks code blocks by relevance to the query and shows the code of the top results. The query is a short sentence describing the code and/or the names you know, e.g. `jbcontext search "where failed HTTP requests are retried"` or `jbcontext search "RetryPolicy maxAttempts handling"`. Narrow to a directory with `-p <path>`.

- `jbcontext search` is the main way to find code in this project, both by what it does and by the names you know. Make it your first code search in every task and use it for every code lookup after that; use `grep_search` only when you need every literal occurrence of a string.
- When you review the project codebase, start with `jbcontext search`, using the behavior and the names from the issue, and keep using it for whatever code you need next: definitions, callers and usages, implementations, configuration or registrations. Use `grep_search` only to list every literal occurrence of a string.
- When you prepare tests, use `jbcontext search` to find the existing tests of the affected code and similar tests to copy the setup from.
- Run `jbcontext search` on its own and read its whole output: don't pipe it into `head`, `grep` or other commands. Open the files it returns with `open`, not with terminal commands.

<!-- jbcontext-instructions-end -->

## Clojure and REPL Rules

- Clojure work is REPL-first. Before editing any Clojure source, test, fixture,
  or build namespace, attach to or start an nREPL from the active worktree.
- Discover servers with `clj-nrepl-eval --discover-ports`.
- If no suitable worktree REPL is running, start
  `clojure -M:dev:nrepl` in a long-running terminal, then rediscover its port.
- If a sandbox denies a loopback socket with `Operation not permitted`, rerun
  the exact start or eval command through the environment's approval mechanism.
  The repository fallback client cannot bypass OS or sandbox permissions.
- Apply runtime and shared-fixture edits with
  `clj-nrepl-eval -p <port> "(user/start!)"`.
- Never use `:reload-all` or `tools.namespace/refresh-all`. They can re-evaluate
  library protocols and orphan live instances.
- Keep evals small, deterministic, and bounded. Do not run destructive file,
  Git, publication, or network actions through nREPL without explicit
  authorization.
- If startup, rediscovery, the fallback client, and required sandbox approval
  all fail, report the REPL as a blocker instead of switching silently to a
  CLI-only Clojure workflow.

## REPL-First Code Discovery

Use nREPL to inspect dependency and library APIs. Do not scrape source from
`~/.m2`, Git dependency caches, JARs, or generated POMs when the namespace is
available on the development classpath.

```clojure
(require '[some.library :as lib] :reload)
(dir some.library)
(doc lib/function)
(source lib/function)
(ns-publics 'some.library)
```

Repository source files may be read normally. Use `:reload` for manual
namespace investigation so an earlier require does not hide recent edits.

Find callers and multimethod implementations with `clojure -M:xref` (who-calls, who-dispatches) before grepping.

## Parenthesis Repair

Do not manually repair unbalanced Clojure delimiters.

```shell
clj-paren-repair path/to/file.clj
```

## Patterns to follow

- Always prefer an open map spec in Malli (don't put `{:closed true}` on `:map` unless strictly necessary)
- A `defaction` that runs a command returns a command descriptor (`{:type :command :id … :command-id … :input … :then … :catch …}`) and never calls `runtime/execute!` itself. Test the returned map. Map rejections with `:catch` or the route's `:bel/error-page` plus `:tover/problems`; post-commit work goes in the command's `:fx`. Inline `execute!` is only for workflows a descriptor cannot express; the docstring names why. Rules and allowed exceptions: [docs/bel-dsl-proposal.md](docs/bel-dsl-proposal.md#actions-return-command-descriptors).


# Tover

The repository root is the Clojure application. Read [README.md](README.md) for implementation status and [styleguide.md](styleguide.md) for formatting. The documents in [docs/](docs/README.md) describe intended behavior; release evidence requires tests and deployment checks.

## Board process

The prooph board is the source of truth for product behavior. Follow [docs/board-process.md](docs/board-process.md) for every task that reads or changes it.

- Never load the whole board. Read one slice (`get_chapter` with `slice_ids`) or catch up with `list_changelog_events` since the instant in `docs/board-sync.edn`.
- Implement only slices with status `planned`. Never set `planned`, `reviewed` or `deployed`; those belong to people.
- Field schemas and rules live in shared element details on the board. Do not restate them in repo docs. To find what a change touches, search the exact element name with `detail: "none"`.
- Feedback and requested changes go on the board as task comments, not into repo files.

## Commands

Run from the repository root:

```sh
clojure -M:dev          # Real local Supabase, empty inventory, hot reload and loopback nREPL
clojure -M:run          # No development seeds, examples or instrumentation
clojure -M:dev:test     # Instrumented Kaocha suite
clojure -M:fmt check
clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj
```

The development alias is required for tests because Kaocha hooks and instrumentation live under `dev`. Source lint covers `src` and `dev`; negative tests deliberately call functions with invalid types. Keep the existing project REPL lifecycle: `user/start!`, `user/stop!`, `user/refresh!`. Default development reads the existing private `deployment/local-identity/.env`; initialize and start that profile separately. It does not start Docker or apply migrations. Use `TOVER_IDENTITY_MODE=supabase` for explicit `TOVER_*` settings. Fixtures belong only to isolated tests. Dependency changes require a REPL restart.

## Structure and limits

- `src/tover`: domain folders for Identity (`tover.identity`: access policy, sessions, GoTrue provider, signup/sign-in/recovery/sign-out slices), Accounts (profile), Workspaces (create, select), Invitations, Stock and Operations; shared UI/web/infrastructure under `shared`; Integrant composition in `app.clj`. Onboarding slices are listed in `docs/bel-onboarding-mapping.md`.
- `src/bel`: declaration, rendering, validation and realtime transport support.
- `resources/public`: Datastar/Jspreadsheet CE browser adapters and static assets.
- `resources/inventory`: versioned RDF/OWL vocabulary, SHACL constraints and executable SPARQL projections/invariants.
- `src/tover/inventory`: semantic ledger, replay, shared drafts, acceptance and workbench. Whole quantities aggregate by stable item ID and location; no per-unit identities or automatic receipt allocation.
- `dev`: isolated fixtures (`tover.fixtures`: in-memory directory, sessions and fake GoTrue), instrumentation and REPL lifecycle. Keep development fixtures out of the production classpath.
- `test`: Clojure tests; `test/browser` has independent Playwright tooling.
- `supabase/migrations`: immutable SQL history; 033 supports fresh identity installation and upgrade from 032; every chain applies each file from 034 on in name order. See `docs/identity-deployment.md` for the local Auth/Postgres profile.
- `docs/legacy-contracts`: historical behavior and sanitized fixtures for later ports. Do not treat legacy inventory semantics as new requirements.
- `docs/bel-experiment-findings.md`: historical findings from retired experiments; use `docs/bel-dsl-proposal.md` and `docs/bel-next-milestone.md` for the proposed direction and next bounded step.

Keep changes small and testable. Onboarding (board chapters 01–07) runs on Bel; identity is read from Postgres on every request. Workspace isolation, durable shared drafts and atomic ledger Submit are implemented. Default development uses the local self-hosted profile (`TOVER_IDENTITY_MODE=local`). Production requires credentials and has no fixture fallback. Browser adapters forward edits and render authoritative server results. Imports, marketplace integrations, uploads and accounting reports remain deferred.

Keep business constraints in the semantic package where practical. Accepted history is structured RDF; stock and lineage derive from effective revisions. Semantic resource checksums are immutable within a store. Change ontology/shapes/queries only through an explicit reviewed migration and restored-backup rehearsal; never silently replace installed semantics. Preserve old stores and select a separate `TOVER_INVENTORY_PATH` for empty tester inventory.

## UI components

- Use `[props]` for components with data or event properties. Destructure when useful. Static components use `[]`.
- Put data and named event properties in the props map, such as `:model`, `:row`, `:on-click`, `:on-change` and `:init`.
- Use `[props & children]` only for a real caller-supplied content slot. Render children in that slot.
- Keep components pure. Views supply event/init expressions; components pass them into attributes without executing them or managing application state.
- Update callers and Malli schemas together. Use `[:cat Props]` for props-only components and `[:* :any]` only when children are accepted. Outputs remain `schema/Hiccup`.
- Preserve scoped Datastar lint hooks so interpolated Clojure still receives normal checks.

## Database safety

Never perform destructive database operations, including DELETE, TRUNCATE, DROP, resets or destructive overwrites, without explicit user approval identifying the exact target and impact. Repository cleanup does not authorize database changes.

Preserve historical migrations. New migration filenames use the next consecutive zero-padded three-digit prefix, currently `043_description.sql`. Do not use timestamp-only names, skip numbers or reuse a number.

Keep credentials, `.data`, backups, private experiment captures and generated dependencies out of Git. Never expose privileged credentials in browser assets.

<!-- jbcontext-instructions-start -->
# Semantic code search

Use `jbcontext search "<focused description>"` before planning, editing or exact search in unfamiliar code. Use `jbcontext search -p <relative-path> "<query>"` to narrow a known area. Read promising files directly after a relevant hit.

For broader or multi-step exploration, delegate to `context_explorer` with `fork_turns="none"`. Give it a focused intent. While it runs, read only already-known relevant files; call `wait_agent` after that work. Do not repeat broad searches or duplicate the delegated exploration. If the index lacks new files, use focused direct reads or `rg` in the known area.
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

## Parenthesis Repair

Do not manually repair unbalanced Clojure delimiters.

```shell
clj-paren-repair path/to/file.clj
```

# Tover

The repository root is the Clojure application. Read [README.md](README.md) for the current prototype boundary and [styleguide.md](styleguide.md) for formatting. The documents in [docs/](docs/README.md) describe intended behavior; they do not establish that a feature is implemented.

## Commands

Run from the repository root:

```sh
clojure -M:dev          # Local seeds, examples, hot reload and loopback nREPL
clojure -M:run          # No development seeds, examples or instrumentation
clojure -M:dev:test     # Instrumented Kaocha suite
clojure -M:fmt check
clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj
```

The development alias is required for tests because Kaocha hooks and instrumentation live under `dev`. Source lint covers `src` and `dev`; negative tests deliberately call functions with invalid types. Keep the existing project REPL lifecycle: `user/start!`, `user/stop!`, `user/refresh!`. Dependency changes require a REPL restart.

## Structure and limits

- `src/tover`: application, actions/views, inventory queries and RDF persistence.
- `src/bel`: declaration, rendering, validation and realtime transport support.
- `resources/public`: Datastar/RevoGrid browser adapters and static assets.
- `dev`: seeds, examples, instrumentation and REPL lifecycle. Keep development fixtures out of the production classpath.
- `test`: Clojure tests; `test/browser` has independent Playwright tooling.
- `supabase/migrations`: retained legacy SQL history. The current Clojure prototype does not use Supabase.
- `docs/legacy-contracts`: historical behavior and sanitized fixtures for later ports. Do not treat legacy inventory semantics as new requirements.
- `experiments/inventory-rdf`: historical offline proof. Its containment model is superseded by the current specifications.

Keep changes small and testable. This is still a single local workspace without authentication. Grid Submit is a browser simulation; only the existing direct quantity-adjustment actions persist. Do not claim later migration milestones are implemented.

## UI components

- Use `[props]` for components with data or event properties. Destructure when useful. Static components use `[]`.
- Put data and named event properties in the props map, such as `:model`, `:row`, `:on-click`, `:on-change` and `:init`.
- Use `[props & children]` only for a real caller-supplied content slot. Render children in that slot.
- Keep components pure. Views supply event/init expressions; components pass them into attributes without executing them or managing application state.
- Update callers and Malli schemas together. Use `[:cat Props]` for props-only components and `[:* :any]` only when children are accepted. Outputs remain `schema/Hiccup`.
- Preserve scoped Datastar lint hooks so interpolated Clojure still receives normal checks.

## Database safety

Never perform destructive database operations, including DELETE, TRUNCATE, DROP, resets or destructive overwrites, without explicit user approval identifying the exact target and impact. Repository cleanup does not authorize database changes.

Preserve historical migrations. New migration filenames use the next consecutive zero-padded three-digit prefix, currently `033_description.sql`. Do not use timestamp-only names, skip numbers or reuse a number.

Keep credentials, `.data`, backups, private experiment captures and generated dependencies out of Git. Never expose privileged credentials in browser assets.

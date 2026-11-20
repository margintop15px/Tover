# Tover

Tover is a Clojure inventory application using Bel/Datastar, Jspreadsheet CE and RDF4J NativeStore. Supabase Auth (GoTrue) holds credentials and sends account email; Postgres holds profiles, workspaces, invitations and private sessions, written by Bel commands. The [current specifications](docs/README.md) define the first tester contract; complete the release checks before inviting testers.

## Run locally

Requires JDK 25+ (the pinned RDF4J version needs Java 25), the Clojure CLI, Docker Compose v2 and Python 3. Initialize the [local identity profile](docs/identity-deployment.md) once, then start it without replacing its private configuration:

```sh
deployment/local-identity/identity.sh init
deployment/local-identity/identity.sh up
clojure -M:dev
```

Skip `init` if `deployment/local-identity/.env` already exists. Open <http://localhost:3030>. Default development reads that private profile directly and uses real local Auth, Postgres sessions and captured email. Application startup does not start Docker or apply migrations. Workspaces start empty; development no longer seeds prototype inventory or exposes demo switches.

Development defaults to `.data/tester-inventory-v1`, preserving previous inventory stores. Set `TOVER_INVENTORY_PATH` to select another store; an incompatible semantic package requires an explicit migration or another fresh path.

Integrant owns HTTP, realtime, identity/session adapters and RDF. The development REPL lifecycle remains:

```clojure
(user/stop!)
(user/start!)
(user/refresh!)
```

Development enables loopback nREPL, Malli instrumentation and hot reload. Route/hierarchy/policy changes require `user/refresh!`; dependency changes require a REPL restart. Production has no development namespaces, seeds, examples or schema instrumentation. Isolated test fixtures remain under the test/development tooling.

Bel emits μ/log traces for HTTP processing, page/view queries, rendering, actions, fragment writes and realtime delivery. Integrant owns the publisher: development writes JSON lines to ignored `logs/events.jsonl`; production writes JSON to stdout for Docker log rotation. Query recent development events with `(bel.log/recent 20)`. Durations use nanoseconds, and `:request-id`, `:mulog/root-trace` and `:mulog/parent-trace` correlate nested spans across SSE callbacks. Published fields exclude request payloads, URLs, identities, credentials and exception messages/data.

Identity spans measure JDBC connection acquisition, session transactions and changed-row persistence. Session storage, the Bel directory and readiness share an Integrant-owned HikariCP pool with 2 idle connections, at most 8 connections and a 2-second acquisition timeout. Unchanged sessions do not rewrite encrypted credentials; every protected request reads the session, profile, active memberships and reset boundary from Postgres in one locked transaction.

Every response carries `X-Request-Id` and `Server-Timing: app;dur=...` in milliseconds. This measures server processing through response preparation, including authentication and session middleware. Datastar fragment writes have separate spans; network latency and browser rendering are outside the server timer. In DevTools, use the response request ID to locate its JSON trace and compare the server duration with TTFB.

Static assets bypass sessions. Production uses browser caching with `Cache-Control: public, no-cache` and Ring's `Last-Modified`/304 revalidation, so unchanged files reuse the browser's stored copy while changed files are downloaded on the next request. Development sends `no-store` and does not return conditional 304 responses. Application pages and streams remain `private, no-store`.

## Inventory workflow

Catalogues contains separate editable Goods, Warehouses, Suppliers, Categories and Stores tables at `/catalogues/<kind>`. Active members save fields directly with revision checks and safe retries. Catalogue goods are selectable without stock; editable warehouse names retain immutable balance keys and previous-name aliases. Current metadata lives in a separate RDF graph, while accepted history remains unchanged. Existing accepted references bootstrap additively on first access.

Table cells suggest goods, warehouses and purchase suppliers. Unique exact goods names reuse catalogue identities; new inventory references register on Submit. Category, Store and Default warehouse cells can create and link missing records directly in one save.

Stock (board chapter 13) groups stock items under their products with one column per warehouse and a total. Units and Money switch the cell values; unvalued stock shows a dash and marks totals partial. Products without stock items are hidden by default. Expand an item to read its accepted history for a period; superseded revisions stay visible but do not count. Lineage is empty until production arrives.

Openings are the only inventory kind so far (chapters 14, 16, 17 and 32a); the other kinds arrive with chapters 15 and 18–28. Pending openings are shared drafts in an editable grid that autosaves each edit with a revision check. Typed product, variant and warehouse names resolve exactly to active records; anything else is registered on acceptance. Findings mark incomplete or conflicting cells. Select entries and Review to see each quantity and value change, the records to be registered and any problems; Accept commits exactly that reviewed selection atomically, or rejects it if anything changed since.

Jspreadsheet CE 5.0.4, jSuites 5.12.0 and Formula 2.0.2 are pinned and served locally with their MIT notices. The [asset manifest](resources/public/vendor/jspreadsheet-ce/manifest.json) records checksums.

The header switches between English and Russian and reloads the current page. Signed-in language preferences follow the account across devices; guests use a remembered browser choice or their browser language, with English as fallback. Switching can discard unsaved browser input. Interface text and error messages share `resources/i18n/messages.json`; business data stays as entered. Restart the app after changing the catalog. Authentication emails use the recipient's saved language, defaulting to English, with bilingual subjects.

## Identity and storage

Board chapters 01–07 (signup and confirmation, sign-in, password recovery and interrupted-reset completion, sign-out, profile and language, workspace creation, invitations with resend/cancel/accept and durable email delivery, and workspace selection) run on Bel; see [the onboarding mapping](docs/bel-onboarding-mapping.md). Sign-in never selects a workspace or accepts an invitation; a person chooses both explicitly. Every protected request and realtime delivery checks the current session and membership. Teammate recovery and membership management (chapters 29, 34–39) are not implemented. Managers administer Team; active members can edit and submit inventory drafts. Provider tokens stay encrypted in private Postgres sessions; cookies contain opaque identifiers. Mutations require CSRF and the configured origin/page context.

RDF stores workspace items, immutable accepted operation/correction revisions, shared drafts, projection revisions and submission receipts. A versioned RDF/OWL/SHACL/SPARQL package defines domain relationships and constraints wherever practical. Clojure coordinates authorization, transactions, concurrency, semantic validation and delivery. Stock quantities and history rebuild from effective accepted records; current labels come from the canonical catalogue graph. Existing prototype stores and anonymous graphs are preserved; they are not assigned to new workspaces.

## Production

Start with the [deployment quick-start](deployment/README.md) for publishing and updating a release.

Use the [production deployment guide](docs/production-deployment.md) for a single-host Compose installation with HTTPS, real SMTP, private Supabase ingress, pinned images, persistent RDF/Postgres volumes and backup/restore helpers.

The selected fresh tester host is [VDS by Selectel](docs/selectel-deployment.md), with an 8 GiB memory budget, bounded logs and scheduled consistent app-data backups. Arrange an encrypted off-server copy; full-VM backups remain an optional billed mode. Build an amd64 release bundle outside Russia and transfer it over SSH. The server loads all five images locally; installation and upgrades require no GitHub, Maven or registry access. The runbook includes bundle creation, server, DNS, SMTP and private API settings.

On the connected build machine, commit all pending changes, push an annotated release tag and publish the verified bundle as a GitHub release. Plain `vMAJOR.MINOR.PATCH` tags create normal releases; other tags create prereleases:

```sh
python3 deployment/production/publish.py v0.1.0 \
  --message "Release v0.1.0" --output /tmp/tover-v0.1.0
```

This requires authenticated Git/`gh`, Docker and Buildx. It checks the bundle and, by default, rehearses fresh installation plus backup/restore before creating the GitHub release. For small changes, run focused checks and add `--skip-rehearsal` for a fast release; its notes record that the rehearsal was skipped. Use the full rehearsal for schema, storage, dependency-version or recovery changes. See the [release runbook](docs/selectel-deployment.md#install-the-release) for failure handling and private evidence.

Inside the extracted bundle on the server:

```sh
deployment/production/production.sh load-images
export TOVER_ENV_FILE=/etc/tover/app.env
deployment/production/production.sh init
# Configure the private production .env: DNS, HTTPS origin, SMTP and release image tag.
deployment/production/production.sh check
deployment/production/production.sh install-fresh
```

The production profile uses a new dedicated inventory volume and does not read local identity credentials or `.data`. `clojure -M:run` requires explicit `TOVER_*` identity/database settings, a 32-byte Base64 session key and an HTTPS origin. `TOVER_PORT` defaults to 3030; `TOVER_INVENTORY_PATH` selects the store. `/healthz` checks the running process; `/readyz` checks RDF, JDBC sessions and Auth.

Back up identity, shared drafts and accepted inventory together. The production helper stops writers and closes the stores before copying them, keeps logical Postgres dumps and restores only into a fresh target. SQL migrations start from `001_baseline.sql`; `supabase/migrate.sh` applies each file once (see [identity deployments](docs/identity-deployment.md)). Apply pending migrations before this application version. The inventory ledger requires no SQL migration.

The ordered timeline query has quadratic worst-case growth within the affected component and a 15-second query limit. Complete multi-line operations and references can connect a large portion of inventory. Keep the first rollout small; see [measured timings and operating limits](docs/production-deployment.md) before expanding tester histories.

## Checks

```sh
clojure -M:dev:test
clojure -M:fmt check
clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s test/production -v
scripts/test-auth-security.sh
scripts/test-bel-workspace.sh
scripts/test-identity-integration.sh
```

Use the development alias for tests because instrumentation and Kaocha hooks live under `dev`. Clojure work is REPL-first; see [AGENTS.md](AGENTS.md) and [styleguide.md](styleguide.md).

Browser tooling is isolated under `test/browser`; Node is not an application runtime:

```sh
npm --prefix test/browser ci
cd test/browser
npx playwright install chromium
cd ../..
npm --prefix test/browser test
cd test/browser && npx playwright test --config ce.config.mjs
npx playwright test --config i18n.config.mjs
npx playwright test --config catalogues.config.mjs
npx playwright test --config identity.config.mjs
npx playwright test --config viewport.config.mjs
npx playwright test --config viewport-app.config.mjs
```

The viewport suites reproduce 10,000-record scrolling with isolated static fixtures, then exercise real RDF acceptance, window queries, details and member updates in a separate temporary application store. They do not use or modify local inventory. RDF read tests also forbid full snapshots/history during initial reads, verify bounded payload decoding, and cover projection backfill, reopen and rollback.

Reproduce scoped acceptance checks with `clojure -M:dev:test --focus tover.inventory.scoped-acceptance-test --focus tover.inventory.affected-query-test --focus tover.inventory.history-projection-test`. These compare incremental acceptance with full rebuilds and guard against decoding or replaying unrelated history.

Run `python3 scripts/test-i18n-emails.py` against the running local identity profile to check both languages for all four email templates and the English fallback. It creates uniquely named test accounts and retains them.

Release gates include all eight operations, heavy lineage/replay coverage, concurrent/stale/selective submissions, durable draft recovery, duplicate/lost-response handling and tenant isolation. Run two-member browser flows and real confirmation/invitation/recovery email checks against the production-shaped stack. Rehearse backup restoration and verify readiness/TLS/SMTP on the configured host. Unit tests alone do not establish deployment readiness.

## Repository boundaries

`src/tover` groups Identity, Accounts, Workspaces, Invitations, Catalogues and Inventory, with application authorization and persistence bindings. `src/bel` owns declarations, rendering, transport, flows and infrastructure contracts. Optional storage implementations live separately in `src/bel_rdf` and `src/bel_postgres`; Tover loads and wires them. Shared application infrastructure is under `tover.shared`. Production resources include browser assets and the semantic package. `dev` contains REPL tooling and isolated fixtures; `test` mirrors domains and contains browser/deployment checks.

Imports, marketplace integrations, evidence uploads and accounting reports are deferred. [Legacy contracts](docs/legacy-contracts/README.md) retain historical integration behavior. [Experiment findings](docs/bel-experiment-findings.md) summarize the retired prototypes. The [Bel DSL](docs/bel-dsl-proposal.md) runs onboarding live and an isolated [Goods rename slice](docs/bel-next-milestone.md), sharing a PostgreSQL event/receipt journal. Goods domain data remains RDF. Inventory and catalogue browser specs predate the cutover's explicit workspace selection and may fail until their own cutover. Keep credentials, private captures, generated dependencies and all persistent stores out of Git.

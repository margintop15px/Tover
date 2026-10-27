# Tover

Tover is a Clojure inventory application using Bel/Datastar, Jspreadsheet CE and RDF4J NativeStore. Supabase Auth/Postgres/PostgREST provide accounts, workspaces, invitations and private sessions. The [current specifications](docs/README.md) define the first tester contract; complete the release checks before inviting testers.

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

Identity spans measure JDBC connection acquisition, session transactions, changed-row persistence, Auth user verification and membership lookup. Session storage and readiness share an Integrant-owned HikariCP pool with 2 idle connections, at most 8 connections and a 2-second acquisition timeout. Unchanged sessions do not rewrite encrypted credentials; every protected request still verifies the current Auth user and workspace membership.

Every response carries `X-Request-Id` and `Server-Timing: app;dur=...` in milliseconds. This measures server processing through response preparation, including authentication and session middleware. Datastar fragment writes have separate spans; network latency and browser rendering are outside the server timer. In DevTools, use the response request ID to locate its JSON trace and compare the server duration with TTFB.

## Inventory workflow

Stock shows quantities by stable item ID and location. A purchase adds its item quantities to stock when accepted. Purchases have one item list with no separate arrival step or delivery status. Article/barcode edits retain the item's history. Different suppliers can contribute to the same balance. Operations shows accepted business history for Purchase, Return, Transfer, Production, Defect, Sale, Write-off and Payment. Financial amounts remain exact facts; Payment has no stock effect.

Both pages share the header and combined search/add-operation input. Choose an operation type to receive its server-defined fields. Operations Add row creates a business-operation draft; Stock Add row declares preexisting stock. Inline Stock edits become manual corrections. Production consumes input quantities and creates outputs atomically, including packing, unpacking and repacking.

Jspreadsheet CE 5.0.4, jSuites 5.12.0 and Formula 2.0.2 are pinned and served locally with their MIT notices. The [asset manifest](resources/public/vendor/jspreadsheet-ce/manifest.json) records checksums. The server defines merged operation groups and stable cell edit targets; formulas, arbitrary row/column insertion and manual merging are disabled. Review shows editable **Changes to submit**, then affected **Resulting stock** rows. Only changed cells are highlighted.

New smart-input operations autosave privately until Done (Cmd/Ctrl-Enter). Done validates the complete line and shares it as a pending workspace entry; refresh resumes unfinished work. Stock Add row and edits to existing pending entries remain shared. Preview selected entries on the server, then Submit that exact selection atomically. Unselected entries remain pending. Revision checks prevent silent overwrites; durable receipts make retries safe. Backdated changes replay affected history and must preserve nonnegative balances and available production inputs.

Preview and Submit query the affected item component directly from RDF, including complete multi-line operations, production dependencies, original-operation references and their immutable revision chains. The installed SHACL/SPARQL checks and independent replay validate that scoped chronology. Submit updates only affected accepted projection records, pending overlays and operation groups in the ledger transaction. Unrelated histories and projection payloads stay unread. Full rebuilds remain the recovery and equivalence check.

Stock and Operations use server-loaded spreadsheet viewports with a scrollbar spanning the filtered result. Windows target 200 rows and retain complete operation groups; large groups may exceed that target. The browser renders a bounded window and preserves its visible row during expansion and realtime refresh. Server query sessions are account/workspace scoped, expire after five idle minutes, and are capped at 32. Initial and subsequent windows use SPARQL filtering, ordering, counts and offsets over a disposable RDF read projection. Query sessions retain descriptors rather than full tables. Item choices load on demand, at most 30 matches. Existing stores receive a one-time backfill from saved stock and streamed latest records; later reads do not reconstruct full history. Projection changes commit atomically with drafts and accepted history.

Expand a Stock item to query only its own effects and audit revisions. Lineage additionally queries production parents before their consumption dates; unrelated workspace history stays unread. Its Gantt includes parents in separate item/location lanes; click a bar or marker to fetch the authorized historical operation revision. History resolves actor names or email addresses through the workspace. Operations has details without lineage. Pooled quantities do not imply allocation to particular receipts or individual physical units.

The server owns search, sorting, filtering, validation, history and acceptance. Smart search defaults to Goods and adds begins-with conditions; the filter strip and complex column-header filters use the same server state. Browser adapters own editor events, focus, selection and rendering. They do not simulate Submit, fabricate local history or calculate inventory business effects. One workspace SSE connection per tab carries draft/stock/operations notifications; reconnect refreshes authoritative state. Private composition edits do not broadcast workspace changes.

The header switches between English and Russian and reloads the current page. Signed-in language preferences follow the account across devices; guests use a remembered browser choice or their browser language, with English as fallback. Switching can discard unsaved browser input. Interface text and error messages share `resources/i18n/messages.json`; business data stays as entered. Restart the app after changing the catalog. Authentication emails use the recipient's saved language, defaulting to English, with bilingual subjects.

## Identity and storage

Signup, email confirmation, login/logout, recovery, workspace creation, invitations, joining and workspace switching use the real identity adapters. Every protected request and realtime delivery checks current membership. Managers administer Team; active members can edit and submit inventory drafts. Provider tokens stay encrypted in private Postgres sessions; cookies contain opaque identifiers. Mutations require CSRF and the configured origin/page context.

RDF stores workspace items, immutable accepted operation/correction revisions, shared drafts, projection revisions and submission receipts. A versioned RDF/OWL/SHACL/SPARQL package defines domain relationships and constraints wherever practical. Clojure coordinates authorization, transactions, concurrency, semantic validation and delivery. Stock/history rebuild from effective accepted records. Existing prototype stores and anonymous graphs are preserved; they are not assigned to new workspaces.

## Production

Use the [production deployment guide](docs/production-deployment.md) for a single-host Compose installation with HTTPS, real SMTP, private Supabase ingress, pinned images, persistent RDF/Postgres volumes and backup/restore helpers.

The selected fresh tester host is [VDS by Selectel](docs/selectel-deployment.md), with an 8 GiB memory budget, bounded logs and scheduled consistent app-data backups. Arrange an encrypted off-server copy; full-VM backups remain an optional billed mode. Build an amd64 release bundle outside Russia and transfer it over SSH. The server loads all five images locally; installation and upgrades require no GitHub, Maven or registry access. The runbook includes bundle creation, server, DNS, SMTP and private API settings.

On the connected build machine, commit all pending changes, push an annotated release tag and publish the verified bundle as a GitHub release. Plain `vMAJOR.MINOR.PATCH` tags create normal releases; other tags create prereleases:

```sh
python3 deployment/production/publish.py v0.1.0 \
  --message "Release v0.1.0" --output /tmp/tover-v0.1.0
```

This requires authenticated Git/`gh`, Docker and Buildx. It checks the bundle and rehearses fresh installation plus backup/restore before creating the GitHub release. See the [release runbook](docs/selectel-deployment.md#install-the-release) for failure handling and private evidence.

Inside the extracted bundle on the server:

```sh
deployment/production/production.sh load-images
export TOVER_ENV_FILE=/etc/tover/app.env
deployment/production/production.sh init
# Configure the private production .env: DNS, HTTPS origin, SMTP and release image tag.
deployment/production/production.sh check
deployment/production/production.sh install-fresh
```

The production profile uses a new dedicated inventory volume and does not read local identity credentials or `.data`. `clojure -M:run` requires explicit `TOVER_*` identity/database settings, a 32-byte Base64 session key and an HTTPS origin. `TOVER_PORT` defaults to 3030; `TOVER_INVENTORY_PATH` selects the store. `/healthz` checks the running process; `/readyz` checks RDF, JDBC sessions, Auth and PostgREST.

Back up identity, shared drafts and accepted inventory together. The production helper stops writers and closes the stores before copying them, keeps logical Postgres dumps and restores only into a fresh target. Preserve immutable SQL migrations: 033 installs fresh identity or upgrades the complete 032 contract; 034 adds member-authorized actor labels. Apply both before this application version. The inventory ledger requires no SQL migration.

The ordered timeline query has quadratic worst-case growth within the affected component and a 15-second query limit. Complete multi-line operations and references can connect a large portion of inventory. Keep the first rollout small; see [measured timings and operating limits](docs/production-deployment.md) before expanding tester histories.

## Checks

```sh
clojure -M:dev:test
clojure -M:fmt check
clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s test/production -v
scripts/test-auth-security.sh
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
npx playwright test --config identity.config.mjs
npx playwright test --config viewport.config.mjs
npx playwright test --config viewport-app.config.mjs
```

The viewport suites reproduce 10,000-record scrolling with isolated static fixtures, then exercise real RDF acceptance, window queries, details and member updates in a separate temporary application store. They do not use or modify local inventory. RDF read tests also forbid full snapshots/history during initial reads, verify bounded payload decoding, and cover projection backfill, reopen and rollback.

Reproduce scoped acceptance checks with `clojure -M:dev:test --focus tover.inventory.scoped-acceptance-test --focus tover.inventory.affected-query-test --focus tover.inventory.history-projection-test`. These compare incremental acceptance with full rebuilds and guard against decoding or replaying unrelated history.

Run `python3 scripts/test-i18n-emails.py` against the running local identity profile to check both languages for all four email templates and the English fallback. It creates uniquely named test accounts and retains them.

Release gates include all eight operations, heavy lineage/replay coverage, concurrent/stale/selective submissions, durable draft recovery, duplicate/lost-response handling and tenant isolation. Run two-member browser flows and real confirmation/invitation/recovery email checks against the production-shaped stack. Rehearse backup restoration and verify readiness/TLS/SMTP on the configured host. Unit tests alone do not establish deployment readiness.

## Repository boundaries

`src/tover` groups Auth, Workspaces, Teams, Stock, Operations and inventory services. `src/bel` owns rendering/declaration/transport infrastructure; shared application infrastructure is under `tover.shared`. Production resources include browser assets and the semantic package. `dev` contains REPL tooling and isolated fixtures; `test` mirrors domains and contains browser/deployment checks.

Imports, marketplace integrations, evidence uploads and accounting reports are deferred. [Legacy contracts](docs/legacy-contracts/README.md) retain historical integration behavior. The [offline RDF experiment](experiments/inventory-rdf/README.md) uses a superseded containment model. Keep credentials, private captures, generated dependencies and all persistent stores out of Git.

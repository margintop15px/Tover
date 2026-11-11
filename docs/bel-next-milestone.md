# Bel milestones

## Goods delivery to the shared journal

Status: implemented in isolation, 2026-11-11. No live route, existing-store migration or production database change. Follow the [DSL](bel-dsl-proposal.md) and [onboarding mapping](bel-onboarding-mapping.md).

The Goods action, pure command, event, model mapping, read model and view are unchanged. Rename still preserves the good's identity, validates its expected revision, advances it once and refreshes the complete current row. Domain data stays native RDF. PostgreSQL now owns the slice's event history and receipts alongside Create Workspace.

| Responsibility | Implementation |
| --- | --- |
| Goods schemas, decision, event, models and composed reads | `src/tover/catalogues/rename/model.clj` |
| Data-only action and rendering-only views | `src/tover/catalogues/rename/web.clj` |
| Acceptance, retry recovery and shared transaction boundaries | `src/bel/runtime.clj`, `src/bel/store.clj` |
| Journal dispatch and delivery lifecycle | `src/bel/journal.clj`, `src/bel/delivery.clj` |
| Optional RDF integration: native models, acceptance markers and pending records | `src/bel_rdf/adapter.clj`, `src/bel_rdf/core.clj` |
| Optional PostgreSQL integration: models, journal and JDBC values | `src/bel_postgres/adapter.clj`, `src/bel_postgres/journal.clj`, `src/bel_postgres/jdbc.clj` |
| Current Tover session/membership checks | `src/tover/auth/bel.clj` |
| Existing table bindings, graph names and legacy cutover guard | `src/tover/workspaces/persistence.clj`, `src/tover/catalogues/persistence.clj` |
| Resource and policy composition | `src/tover/shared/bel.clj` |
| Scope-aware journal permissions and private worker role | `supabase/migrations/036_bel_shared_journal.sql` |
| Cross-store failure, race and restart tests | `test/goods_journal_integration.clj` |

### Acceptance and delivery

1. Lock and validate the current PostgreSQL session, confirmation and workspace membership. Hold these locks through the RDF commit.
2. Authorize the command and resolve any prior acceptance. New decisions load domain facts from the RDF transaction.
3. Commit model mappings, a permanent command marker and the pending delivery batch together in RDF. Return the accepted result and refresh the view.
4. The background worker inserts the event batch and receipt in one PostgreSQL transaction. Duplicate delivery compares the receipt and every event's metadata, order and payload; missing, extra or changed facts stay pending for investigation.
5. After PostgreSQL confirms the commit, remove the pending payload. Keep the marker so retries must recover the journal receipt instead of rerunning a decision.

The marker contains no event or receipt payload. Before delivery, the pending batch is the crash-recovery record. After delivery, canonical history and receipt data live only in PostgreSQL. Missing journal data never means the command can run again.

The worker is an Integrant component with explicit repository and journal datasource resources. It polls on startup and every second, retries failures and closes before its resources. Concurrent workers are safe without a claim protocol. Decoding and scope validation happen per record; unreadable or misplaced records stay unchanged and cannot stop other workspaces. Failure logs identify their source. The private worker role can only read/insert workspace journal rows; it cannot read credentials, sessions or directory data, or update/delete journal rows. An accepted event remains deliverable after its actor loses access.

Bel holds the Goods authority transaction at Read Committed plus row locks through RDF commit. This lets a retry see delivery committed between its authorization check and its RDF lookup. For Create Workspace, authority, domain and journal reuse the same named PostgreSQL transaction. PostgreSQL domain read models retain Repeatable Read. A narrow private function locks membership without granting directory UPDATE privileges to the app.

The refactor changes no business declarations, SQL schema, graph IRIs, event envelopes, receipt encoding or pending-record shape. Bel owns flows and infrastructure contracts, with no storage-library imports. Separate `bel-rdf.*` and `bel-postgres.*` integrations implement those contracts; they import no Tover code and never call each other. Tover loads the integrations and supplies authorization, native bindings, graph allocation and semantic repository initialization. Its existing RDF API delegates to the RDF integration for legacy callers.

Journal-writer failure cannot block source acceptance or view refresh. A complete identity-database outage still prevents current authorization. The worker scans pending records sequentially; paging, alerting and throughput tuning remain outside this isolated milestone.

### Isolation and existing data

Journal identity is `(scope kind, scope UUID, command ID)`. User and workspace records cannot collide even when both IDs match. Current membership controls history reads; the app role cannot insert workspace history directly. The worker role has no public or Data API exposure.

New RDF writes use `bel-domain` and `bel-delivery`. A scope containing old `bel-events` or `bel-receipts` rejects acceptance with `:migration-required`; it is not migrated implicitly. Legacy catalogue/inventory graphs and stored rows remain untouched. The Goods and Create Workspace routes remain excluded from normal discovery.

Migration 036 extends the isolated journal tables. Existing PostgreSQL payloads are preserved; old events receive no invented arrival time or event order. Worker login credentials must be provisioned separately before live adoption. This milestone applies migrations only to a new disposable test cluster.

### Verification

```sh
scripts/test-bel-workspace.sh

clojure -M:dev:test

clojure -M:fmt check
clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj
```

Despite its original name, the script now checks both stores and both slices. It creates a fresh PostgreSQL 16.3 container with a unique data directory and loopback port, applies 033–036, reapplies 036, verifies the actual data directory before fixture writes and enables the worker login only there. It never reads `.env` or opens an existing application database. RDF fixtures are temporary stores. The first JVM halts with the restart-test RDF store open, bypassing shutdown hooks. The script restarts PostgreSQL, runs recovery in a new JVM, then removes its own cluster and files.

Verified 2026-11-11:

- A fresh process loads `bel.runtime` and `bel.delivery` without loading the storage integrations, next.jdbc, HoneySQL or Flint.
- 32 instrumented integration scenarios, 257 assertions: source rollback, PostgreSQL receipt/event rollback, lost acknowledgement before/after RDF cleanup, concurrent source retries and delivery workers, conflicting receipt/event content, missing events, unreadable and wrong-scope records, current authority and workspace isolation, scope-kind collisions, preserved native domain data and background delivery without blocking rendering. Retryable receipt recovery cannot render a rejected save.
- Two abrupt-JVM-exit/database-restart scenarios, 13 assertions. Goods covers pending, committed-but-unacknowledged and acknowledged deliveries with its RDF store left open at exit. Recovery neither repeats decisions nor duplicates history.
- The complete instrumented application suite: 575 tests, 9,620 assertions. Coverage includes transaction sharing and authority-lock lifetime, snapshot replacement, nested read policies, hostile bindings, stale edits, publication behavior, retryable recovery and legacy-row preservation. Source unit tests stub only the identity guard; the integration suite exercises it against PostgreSQL.

No test failures. Source lint has no errors or warnings; all refactor files pass formatting. The repository-wide formatting check still reports four files unchanged by this refactor: `bel/core.clj`, `bel/handler.clj`, `tover/shared/supabase.clj` and `tover/workspaces/create/model.clj`. Checks do not establish live cutover, backup restoration across independently restored stores, power-loss recovery, throughput or browser rollout.

## Create Workspace

Status: implemented in isolation and retained under the shared journal.

A confirmed user with a saved display name creates a workspace. The pure command emits Workspace Created and Workspace Owner Assigned. Native PostgreSQL rows, both events and the Nippy receipt commit together. UUIDs and instants stay native internally; Malli decodes transport input and JDBC protocols handle storage values. The view reads original creation outcome and current access separately. Creation neither selects the workspace nor seeds RDF data.

Business declarations live in `src/tover/workspaces/create/model.clj` and `web.clj`. Native table bindings and permission mechanics remain application infrastructure. Bel owns the acceptance workflow and the PostgreSQL adapter owns SQL mechanics. Database uniqueness owns identity collisions. Matching retries skip decisions and cannot restore removed membership. The shared store script retains failure, concurrency, session revocation, permissions and restart checks for this path.

## Next bounded milestone

Profile setup and shared routing, following chapters 03 and 03a. Move language into the profile with independent field revisions, derive readiness from the saved name and preserve ordinary forms across language changes. Keep Auth effects and invitation delivery in their own subsequent slices.

Live Goods cutover remains separate: account for every writer, preserve IDs/revisions/receipts and retain a baseline. Goods also change through inventory acceptance, projection registration and corrections. Rehearse on restored copies before enabling routes, and establish coordinated backup/restore rules for RDF acceptance markers and the PostgreSQL journal. Do not delete source markers or receipts without a retry-safe retention policy.

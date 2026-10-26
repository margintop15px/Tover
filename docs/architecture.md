# Tover architecture

Status: agreed first tester architecture, 2026-10-21. [Product requirements](product-requirements.md) own business semantics. This contract replaces the prototype's browser ledger and individual physical-unit assumptions.

## Runtime and ownership

Run one Clojure application using Bel/Datastar, http-kit, Reitit, Chassis and Jspreadsheet CE. Integrant owns HTTP, realtime, identity adapters, session storage and RDF4J NativeStore. Keep the existing development REPL lifecycle. Production contains no development fixtures, seeds, examples, reload middleware or browser simulations.

| Owner | Authority |
|---|---|
| RDF4J NativeStore | Workspace items, immutable accepted operation/correction revisions, shared drafts, projection revisions and durable submission receipts. |
| Supabase Auth/Postgres/PostgREST | Accounts, verification, memberships, workspace creation, invitations and manager roles. |
| Private Postgres sessions | Encrypted Auth tokens, session expiry and active workspace. Browser cookies contain opaque identifiers. |
| Versioned RDF/OWL/SHACL/SPARQL package | Domain vocabulary, relationships, business constraints, effective-history queries and derived projections. |
| Application server | Authorization, transaction/concurrency orchestration, semantic validation/replay execution, acceptance and query delivery. |
| Browser adapters | Editor events, focus, selection, scroll and rendering. Temporary unfinished text is not an acknowledged draft save. |

Do not persist inventory balances independently of their accepted history. Current stock is a reproducible projection grouped by stable item ID and location. Different suppliers do not split an item's identity. Production records input/output quantities rather than generating individual physical units.

Put business semantics in the declarative RDF/OWL vocabulary, SHACL constraints and SPARQL rules/queries wherever practical. Validate candidate graphs before acceptance and retain the semantic package version used for projections. Clojure coordinates trusted input, transactions, concurrency and delivery; it must not become an independent competing definition of domain relationships.

## Server workflow

Stock and Operations share a shell, header and combined search/add-operation input. The explicit operation type determines server-supplied fields and editors. Both entry paths use the same validation and acceptance service. Stock Add row creates a preexisting-stock declaration; Operations Add row creates a business-operation draft.

Accepting a Purchase increases stock by its item quantities at the operation date and locations. There is no separate order, arrival or delivery status.

Workspace-authorized queries/actions cover draft editing, preview, Submit, stock, operations, suggestions and expanded item history. Search, sorting and filtering execute on the server. Browser adapters send intent and render returned state; they do not calculate stock, fabricate accepted operations or implement a local Submit baseline.

Jspreadsheet CE uses locally pinned MIT assets without a frontend framework. Server models define row/column spans and stable item/draft IDs, field paths and revisions. Coordinates are presentation only. Keep active input and selection across authoritative snapshots; retain attempted values when revisions conflict. Disable formulas and spreadsheet-owned structural mutations. Review uses one editable changes grid and an affected-stock grid with field-level differences.

Smart-input compositions persist privately for their author until Done. Done validates the complete entry and moves it into the shared pending draft; it does not accept history.

A completed edit saves one draft-entry revision. A stale revision returns the saved entry and retains the attempted input for resolution. Pending draft content is shared; each reviewer's selection is local to that review.

Preview validates the selected complete entries against the accepted-ledger revision. Submit compares those ledger/draft revisions and commits accepted history, the projection revision, an idempotency receipt and draft removal atomically. Transfer/production lines are inseparable. A duplicate request returns the existing receipt. Unselected draft entries remain.

Replay effective accepted records in deterministic occurrence order, with stable tie-breaking. Validate the full affected chronology for negative balances and unavailable production inputs. Corrections and cancellations retain earlier immutable revisions. Item history queries return the item's own records; lineage queries traverse dated production lines without attributing pooled quantities to particular incoming lines.

## Identity and transport

Browsers call Tover. Auth and user-authenticated PostgREST calls run on the server. Keep provider credentials, privileged API keys and token pairs out of HTML, JavaScript and realtime connection objects.

Verify the current user and active membership for every protected request. Mutations require CSRF, the configured origin and the current page context. Manager policies protect Team administration. Workspace switching and logout close old streams; other tabs reload and stale workspace requests fail.

Use one SSE connection per tab with workspace-scoped draft, stock and operations topics. Publish notifications after commit. Deliver only to freshly authorized recipients. An initial connection or reconnect requests authoritative snapshots so disconnected notifications cannot leave a view stale. Notifications are invalidations; they do not carry an alternative browser ledger.

Bel declarations own typed inputs, queries/actions, page hierarchy and route metadata. Tover owns policies, business commands and document assets. Pure UI components receive data and event expressions in props. Internal navigation renders server patches; authentication and workspace changes use complete loads.

## Deployment and recovery

The [production Compose profile](production-deployment.md) exposes only Caddy on ports 80/443. Caddy terminates HTTPS and streams responses without buffering. Auth/PostgREST/Postgres remain Docker-internal; Auth has outbound SMTP access. Separate named volumes hold RDF, Postgres and TLS state. A fresh tester deployment does not reuse prototype `.data`.

The application runs as an unprivileged user from a JRE image with only the production classpath. `/healthz` reports the running process; `/readyz` probes RDF, the private session table through JDBC, Auth `/health` and PostgREST `/`. Failed dependencies return 503. Independent container checks cover Auth, Postgres and PostgREST.

Ordered full-timeline prefix validation currently has quadratic worst-case growth and a 15-second limit per query. A query timeout must roll back acceptance and preserve the draft. This supports a small tester rollout; benchmark representative larger histories before expanding use. The [deployment guide](production-deployment.md) records measured query timings.

A consistent maintenance backup stops application/Auth writers, dumps Postgres, stops the database and copies both closed data stores with secrets, image references and checksums. Restore only to a fresh deployment target, retain compatible image versions and verify the complete workflow before switching traffic. Preserve immutable SQL migrations; 033 is the fresh identity baseline and the upgrade from 032. Migration 034 provides workspace-authorized actor labels without granting members team-administration access.

No durable worker, queue, external blob store, SPARQL endpoint, marketplace connector or multi-instance coordination is required for this release. Add them only for a separately accepted capability.

# Goods identity and Ozon integration execution plan

Status: **M0 complete; M1 next**. Approved on 2026-11-06. M1–M9 are planned, not implemented.

This is the resumption entry point for work spanning multiple coding sessions. Read it with [AGENTS.md](../AGENTS.md), [product requirements](product-requirements.md), [RDF machinery](rdf-machinery.md), and [client UI](client-ui-spec.md). Requirements describe intended behavior; milestone evidence and deployment checks establish what has actually shipped.

## Current checkpoint

| Field | Recorded state |
| --- | --- |
| Application | Root Clojure application in `/Users/usuario/Projects/tover-app` |
| Branch | `main` |
| Starting release/commit | `v0.2.0`, `4e923ce6f10defce6beaca55c59819a3b32656f8` |
| Catalogue release | [v0.3.0](https://github.com/margintop15px/Tover/releases/tag/v0.3.0) |
| Deployed application commit | `d96fb3a83d394a75be2098f87c0347d158beabd8` |
| Production | `https://fin-craft.ru`, host `82.148.19.59` |
| Active release | `/srv/tover-releases/v0.3.0`, selected by `/srv/tover` |
| Application image | `tover:v0.3.0`, Linux amd64 |
| Verified image ID | `sha256:91e8ad1e2f75bea08364bbd00d625caff1d140a41a8ad226e31139ae58288289` |
| Deployment verified | `2026-11-06T00:35:39Z` (01:35 Europe/Madrid) |
| Authenticated catalogue smoke | Completed `2026-11-06T08:27:27Z` (09:27 Europe/Madrid): all five catalogues in English/Russian; original Stock view and Russian preference restored |
| Pre-upgrade backup | `/srv/tover-backups/2026-11-06-before-v0.3.0` |
| Backup evidence | `COMPLETE` present, all `SHA256SUMS` verified, manifest identifies v0.2.0 and its commit |
| Release archive | `tover-v0.3.0.tar.gz`, 580,093,415 bytes |
| Archive SHA-256 | `e90ae22f72a6d4dfc64c423d8f294e50ce972703ac782984b87ef63b911797b1` |
| Release manifest SHA-256 | `f7c0d1a16a4d7345b7f6607c2d8b84670e1cab2816dd2b7abd133eaa3f4739eb` |
| Documentation commit | The commit containing this checkpoint; resolve with `git log -1 -- docs/marketplace-integration-plan.md` |
| Next action | Begin M1: specify the external records, invariants, source-contract matrix, and sanitized executable fixtures |

The catalogue feature was pre-existing uncommitted work when planning began: 33 tracked modifications and 9 new files. All 42 files were committed and published in v0.3.0 before this document was created. This document is a separate documentation change after deployment; the release tag must continue to identify `d96fb3a83d394a75be2098f87c0347d158beabd8`.

The backup contains private identity data and configuration. Keep it private; do not copy its contents into Git or this plan. The old release and backup were preserved. The release used the explicitly requested fast path, so the full production-shaped installation/restore rehearsal was skipped.

## Locked decisions

These decisions came from the user discussion. Do not reopen them without contradictory evidence or an explicit product change.

| Topic | Decision |
| --- | --- |
| Goods identity | The stable workspace-local internal item ID is the sole inventory identity. External names, articles, barcodes, and marketplace IDs are linked facts. |
| Warehouses | The same item may have balances in several warehouses. Warehouse rows do not imply separate goods identities. |
| Matching trust | Only a user-confirmed external product → internal goods mapping qualifies for automatic acceptance. Exact identifiers and similarity may suggest candidates. |
| New operations | Automatically accept new, complete, valid source events with confirmed mappings through the existing ledger acceptance machinery. |
| Source corrections | Changes or cancellations affecting accepted inventory history go through Review. |
| Unmatched products | Review offers link existing, create distinct goods, and defer. Support bulk creation for selected products. |
| Cutover | Review explicit opening balances at a chosen cutover. Older movements remain reference evidence and are not counted again. |
| Sale timing | Remove units at confirmed physical dispatch, using the actual event time. |
| Transportation | Track shipment state and quantities separately after dispatch; do not add these quantities back to warehouse stock or invent a transit stock location. |
| Fetch cadence | Daily plus Sync now. Default scheduling is every 24 hours from the last successful completed sync. |
| Stores | Goods can have multiple Store relationships. Marketplace is the usual user-facing context; identifier scope includes the account/connection where required. |
| Extra columns | User-selectable columns expose linked external data. Required TOVER columns stay visible. This is not a request for an arbitrary user-defined schema editor. |
| Client rules | Small versioned presets for identifier uniqueness and required fields, backed by SHACL. |
| Initial scope | Inventory workflow: goods, warehouses, stock observations, customer dispatches/returns, and supported own-stock supply/removal movements. |
| Deferred | Financial reports, payouts/accounting, arbitrary rule editors, embeddings, general attribute-column sorting/filtering, marketplace write-back, and multi-instance workers. |
| Legacy Supabase | Imported source facts are useful research. Existing local-product links and accepted operations were used largely for testing and are not matching ground truth. Do not migrate them into the current ledger. |

The current eight ledger operation types remain Purchase, Return, Transfer, Production, Defect, Sale, Write-off, and Payment. Packing/unpacking/repacking use Production. Purchases add their direct quantities on acceptance; do not restore the old prototype's arrival workflow as part of this integration.

## Current implementation context

The following is implemented in the v0.3.0 baseline. It is distinct from the external model proposed below.

- Goods, Warehouses, Suppliers, Categories, and Stores catalogues already exist. Goods can be created without stock and are available to inventory item suggestions.
- Goods currently have scalar `name`, `article`, `barcode`, `unit`, optional `category-id`, and optional **single** `store-id`. Multiple stores require M2 work; they have not shipped in v0.3.0.
- A separate durable `catalogue-v1` graph holds current reference metadata, stable IDs, revisions, receipts, and relationships. The disposable inventory read projection is version 8.
- Warehouse catalogue records preserve immutable inventory location keys and previous-name aliases. Renames change labels, not stock identity.
- Current item selection reuses catalogue IDs. A new typed inventory name receives a stable provisional ID and draft-only `:catalogue-new?` coordination metadata. New references become catalogue records only on successful Submit.
- Direct catalogue saves are immediate, revisioned, and idempotent. Imported catalogue/link decisions must use reviewed proposals rather than silently calling that direct-save route during fetch.
- Preview/Submit validate selected entries, ledger/catalogue/draft revisions, the affected chronology, semantic constraints, and independent replay. Submit commits accepted records, projections, catalogue changes, draft removals, and receipts atomically.
- Accepted operations already feed Stock, Operations, item history, audit, and timeline. Imported operations should use these paths rather than a second inventory/history implementation.
- Catalogue-only goods have no accepted inventory history. History currently reports that absence; give it an explicit empty state when surfaced by imports.
- Review is currently stock/operation-centric. External-product linking, catalogue-only proposal selection, source provenance, and source-event deduplication do not exist yet.
- The installed inventory semantic package/checksums are immutable. Source integration graphs and constraints need their own versioning, and semantic changes require deliberate migration/rehearsal.
- Accepted actor IDs are strings, but history label resolution currently assumes user UUIDs. A stable connector actor needs explicit presentation support; do not invent a member UUID.
- There is no durable connector worker or scheduler. Add one Integrant-managed worker for the existing single application instance.

### Owning code and existing evidence

| Area | Entry points |
| --- | --- |
| Catalogue records, revisions, aliases, bootstrap | [catalogue store](../src/tover/catalogues/store.clj), [schema](../src/tover/catalogues/schema.clj), [query](../src/tover/catalogues/query.clj) |
| Catalogue UI and endpoints | [web](../src/tover/catalogues/web.clj), [UI](../src/tover/catalogues/ui.clj), [browser adapter](../resources/public/catalogues.js) |
| Drafts, preparation, Preview, Submit, receipts | [inventory store](../src/tover/inventory/store.clj): `preview`, `submit!`, `prepare-scoped`, `record-for` and projection helpers |
| Whole-unit effects and stable item/location balances | [ledger](../src/tover/inventory/ledger.clj), [schema](../src/tover/inventory/schema.clj) |
| Existing manual item selection | [item selection](../src/tover/inventory/item_selection.clj), [stock web](../src/tover/stock/web.clj) |
| Bounded reads and disposable projections | [read model](../src/tover/inventory/read_model.clj), [read query](../src/tover/inventory/read_query.clj) |
| History, timeline, and actor labels | [history query](../src/tover/inventory/history_query.clj), [timeline](../src/tover/inventory/timeline.clj), [actors](../src/tover/inventory/actors.clj) |
| Lifecycle | [application composition](../src/tover/app.clj) |
| Existing encryption pattern | [session storage](../src/tover/auth/sessions.clj): Buddy AES-256-GCM with a random 12-byte IV; connector credentials require a separate key |
| Catalogue regression coverage | [Clojure catalogue tests](../test/tover/catalogues/catalogues_test.clj), [browser catalogue tests](../test/browser/catalogues.spec.mjs) |

Do not assume the web authorization wrapper exists for a background job. The connector acceptance entry point must construct a trusted workspace scope, check the enabled connection/grant, and reuse the store-level validation/commit path. HTTP fetching must run outside inventory transactions.

## Ozon research context and limits

Live, read-only inspection of the legacy hosted Supabase `tover_app` project was performed on 2026-11-05. API documentation was checked again on 2026-11-06. These are dated observations, not fixed acceptance counts or guarantees of present source coverage.

The imported product mirror contained 2,563 rows: 2,560 API-shaped records across three connected feeds, plus three obvious mock records in an invalid connection. The connected feeds belonged to separate workspaces. Local mappings were explicitly excluded from conclusions about correct goods identity.

| Observation over the 2,560 non-mock records | Result |
| --- | --- |
| Articles present | 2,560; no duplicate article within a connection |
| No barcode | 1,090 (42.6%) |
| One barcode | 1,465 |
| Multiple barcodes | 5: four with two, one with three |
| Barcode collision within one connection | 0 |
| Barcode strings shared between connections | 8, with differing articles/product IDs |
| Products with multiple source SKUs | 485; observed maximum two |
| Repeated nonzero primary/source SKU within a connection | 0 |
| Invalid/sentinel primary SKU | Three unrelated products had `"0"` and empty source arrays |
| `OZN...` barcode bindings | 1,379; 478 suffixes differed from main SKU, and 421 of those matched another source SKU |
| Attribute facts | 38,452, covering 578 distinct attribute IDs |
| Attributes per product | 4–54, mean 15 |
| Multi-value attribute entries | 2,716 |
| Products with complex attribute groups | 221 |
| Category/type combinations | 128 |

Other concrete cases:

- One stable external product was observed under one article on October 29 and another from October 30 onward. On November 5 its warehouse analytics showed 320 available units across 12 warehouses. Article evolution and location balances are separate from goods identity.
- Two same-name clothing products differed in structured attribute `4295` (`56` versus `58`). Names and semantic similarity are insufficient to establish variant identity.
- The old importer sometimes copied `sku` or `offer_id` into a column named `ozon_product_id` when the source lacked a true product ID. All 4,442 non-mock posting lines failed a true-product-ID join on that column; SKU/article matching resolved 4,349. Preserve identifier types from endpoint contracts and raw evidence.
- Of 945 non-mock returns, main SKU resolved 793 to the current source catalogue; source-SKU aliases raised that to 804, and articles recovered two more. These are source-catalogue joins, not confirmation of internal goods mappings. Retain the remaining unresolved evidence.
- The product mirror is mutable/upserted. Complete historical barcode changes cannot be reconstructed from it. Snapshot/event observations retain some older identifier values.
- Product-level stock snapshots were totals by fulfillment type without named warehouses and often without SKU. Warehouse stock analytics supplied actual warehouse identities. Never sum those two representations as independent stock.
- The old normalized posting rows lacked actual delivery timestamps. Current detail endpoints expose handover information that the new adapter must fetch; processing and planned-shipment dates cannot substitute for actual physical event times.
- Attribute values were retained as IDs/collections and sanitized source JSON. Friendly column names/types/cardinality require category/type metadata. Imported numeric-looking strings must not be guessed into types without that metadata.

Use the [official Seller API](https://docs.ozon.ru/api/seller/) as the provider authority. Relevant contracts include product list/details/attributes, related-SKU lookup, barcode associations, category-attribute metadata, warehouse lists, warehouse analytics, posting list/details, returns, supplies, and removals. Revalidate the specific contracts when implementing M1/M6; older endpoint versions may be disabled.

The [legacy Ozon contract](legacy-contracts/ozon-fetching.md), [source manifest](legacy-contracts/sources.json), [synthetic Ozon cases](legacy-contracts/fixtures/ozon-cases.json), and [import-integrity contract](legacy-contracts/imports.md) retain useful regression cases. Archived importer code was inspected at commit `9f799586a62226bc62643e5dedc54d76a0176359` (`src/lib/ozon/sync.ts`). It is historical evidence, not code to restore wholesale.

The legacy fetcher preserved sanitized raw source payloads but did not union every barcode-bearing response source. Source refresh overwrote product rows. Preserve the useful contracts, not these limitations. Do not copy real payloads, credentials, buyer/contact/address details, or private captures into fixtures or this document.

## Approved implementation plan

### Intended outcome

Extend the current catalogue and inventory system so external products map to stable internal goods identities, imported operations use the existing acceptance machinery, and source data remains available for inspection and optional table columns. Keep the current ledger/catalogue authoritative for internal inventory and current user-controlled metadata.

### Model and interfaces

Add workspace-scoped external RDF records alongside the current model:

| Record | Responsibility |
| --- | --- |
| Connection | Provider/account identity, Store relationship, encrypted credentials, enabled state, daily schedule, and durable sync progress. |
| External product / warehouse | Stable source identity within a connection, independent of current name/article. |
| Source revision | Sanitized facts, observation time, supplied provider timestamps, payload hash, and adapter version; preserve meaningful changed revisions. |
| Identifier binding | Identifier type/value, source context, product relationship, and observed history. Keep main and historical/source SKUs separately. |
| Confirmed mapping | External product → internal goods or external warehouse → internal location, with confirmation actor/time and revision. |
| Import proposal | Catalogue creation, mapping, opening/correction, or operation proposal, with source references, dependencies, revision, and findings. |
| Acceptance provenance | Source event/revision → accepted entity/revision, with durable deduplication and receipts. |
| Client policy | Versioned constraints selected for a workspace or connection. |
| Field definition / view preference | External attribute names/types/cardinality and selected optional columns per user/workspace/table. |

Use open Malli maps and separately versioned external vocabularies/SHACL constraints. Preserve the installed inventory semantic package unless a specific ledger change proves necessary. Any semantic change needs an explicit migration and restored-backup rehearsal.

Use explicit mapping relationships for identity decisions. OWL keys or `owl:sameAs` must not turn a repeated external code into an automatic goods merge; SHACL expresses acceptance constraints. Identifier-conflict validation must include competing active bindings outside the selected item's existing inventory component and remain atomic under concurrent acceptance.

Identity and metadata behavior:

- Scope keys by workspace, connection, identifier type, and value. Preserve strings/leading zeros; absent identifiers and sentinel SKU `"0"` are unavailable.
- Never substitute one identifier type for another or derive SKUs from barcode formatting. Preserve all supplied barcodes and source SKUs.
- External descriptions/attributes refresh independently of user-controlled catalogue metadata. Current Article/Barcode fields stay descriptive/preferred values, not primary keys or last-source-wins fields.
- A confirmed mapping survives article/name changes. Changes to identity-relevant variants or units suspend automatic acceptance until reviewed.
- Multiple external products can map to one goods ID through explicit confirmation. Same names never establish equivalence; explicit distinct-variant creation remains possible despite a name collision.
- Mapping corrections affect future proposals. Accepted operations require explicit reviewed revisions.
- Migrate single Store assignments into a collection without losing IDs. Display the union of explicit assignments and confirmed-listing stores. Keep account scope distinct from the Store/marketplace.
- Preserve warehouse location keys/aliases. Multiple conflicting Store defaults require context or selection; an imported warehouse cannot silently be replaced by a default.

Source-to-inventory effects:

| Source evidence | TOVER behavior |
| --- | --- |
| Product metadata, identifiers, prices, attributes | Refresh external records. Catalogue creation/canonical changes require the appropriate reviewed action. |
| Stock snapshots and warehouse analytics | Observations and reconciliation evidence; not independent movements. |
| Confirmed customer dispatch | Sale at actual handover/delivery-start time, preserving every line and the confirmed source warehouse. |
| Physical customer return receipt | Incoming Return at actual receipt time and confirmed receiving location. Initiation alone has no stock effect. |
| Completed owned-goods supply/removal movement | Transfer when both locations, actual quantities, and completion evidence are known. Missing facts remain in Review. |
| Explicit disposal | Write-off with complete quantity/location/date/disposal evidence. |
| Transportation/status update | Update source status/transit projections without a second stock effect. |

Fetch details when lists are insufficient. Cancellation before dispatch has no stock movement. Cancellation after physical dispatch does not restore stock by itself; physical receipt or a reviewed correction accounts for that effect.

Extend Review to select catalogue/link proposals alongside existing inventory entries, retaining existing inventory-only requests. Selecting an operation includes required mapping/creation decisions; unresolved lines block the complete operation. Catalogue-only proposals can be accepted without inventing stock.

Preview binds relevant ledger, catalogue, source, mapping, and policy revisions. Submit commits catalogue changes, mappings, ledger records, provenance, source deduplication state, and receipts atomically. The narrow automatic-acceptance entry point uses the same preparation, semantic validation, replay, and commit logic. Publish the existing realtime invalidations only after commit.

Source refresh preserves user-edited pending proposals and exposes conflicts. Connector actors need explicit labels, not fake users.

### M0 — Establish the durable execution record

**Status: complete.**

Work: save this full plan, locked decisions, research context, checklist, evidence, and session log. Preserve the pre-existing catalogue changes, verify them, commit/deploy that release first, then save this document in a separate commit.

Keep intended behavior in the product/RDF/UI contracts as implementation progresses. This execution record tracks delivery and evidence; it must not become a competing source of application requirements.

Completion gate: another session can identify the exact baseline, active milestone, required checks, and next action from the repository alone. See the deployment evidence and session log below.

### M1 — External model and executable source contracts

**Status: next. Depends on M0.**

Define the records and invariants above with open Malli schemas, RDF vocabulary, and separate SHACL constraints. Write the Ozon contract matrix: identity, pagination, required fields, event time, terminal states, and resulting operation/proposal for each included domain.

Create sanitized fixtures for:

- Multiple barcodes and main/historical/source SKUs.
- Article rename under stable product ID; shared barcode across connections; missing barcode and sentinel SKU.
- Same names with different variants, unit/pack mismatches, and incorrectly typed legacy product-ID fields.
- Multi-line shipments, missing actual timestamps, partial responses, and late returns.

Define initial policy presets: barcode resolves to one internal item in its source context; stricter barcode-to-one-article/listing; optional required fields at mapping/catalogue acceptance. Validate active bindings and proposals while preserving conflicting source evidence. Core inventory/isolation constraints cannot be disabled.

Completion gate: fixtures distinguish valid, ambiguous, incomplete, and contradictory records without live Ozon or legacy local mappings.

### M2 — External persistence and catalogue relationships

**Status: pending. Depends on M1.**

Persist external records, source revisions, identifier bindings, field definitions, and confirmed mappings in the existing RDF repository. Meaningful changes create revisions; repeated identical fetches update observation/progress information without multiplying identical records.

Implement the additive Store-collection migration and source-derived memberships. Preserve catalogue-only goods, location keys, aliases, and existing metadata behavior. Add bounded source-key, candidate-goods, and identifier-conflict lookups; do not decode the whole source catalogue during ordinary reads.

Completion gate: reopen/rebuild preserves identifiers, mappings, history, Store assignments, and zero-stock goods. Source refresh and catalogue migration do not change quantities.

### M3 — Linking and catalogue creation in Review

**Status: pending. Depends on M2.**

Add imported catalogue/mapping proposals to Changes to submit. Provide link existing, create distinct goods with an explicit whole-unit counting basis, bulk creation of selected proposals, warehouse resolution, source/variant evidence, and actionable dependency errors.

Reuse a stable provisional goods ID across proposals for the same source product. Do not allocate a new item per operation line. Accept catalogue-only decisions independently or atomically with their dependent operations.

Completion gate: resolving an unfamiliar product and accepting its operation yields one consistent item in catalogue, Stock, Operations, history, and timeline. Deferred proposals retain all lines and do not block unrelated selected entries.

### M4 — Operation normalization and atomic acceptance

**Status: pending. Depends on M3.**

Build pure Ozon-to-proposal transformations against fixtures before connecting HTTP. Factor only the shared acceptance code required by manual and connector paths; retain the existing operation types and validation authority.

Use stable source-event identity for deduplication and revision/hash for change detection. Re-fetching an accepted event is a no-op. Changed accepted events produce revision proposals. A source cancellation produces a correction only if it retracts an accepted inventory effect. Concurrent attempts cannot create two ledger entities for one event.

Commit provenance and source-event acceptance state in the ledger receipt transaction; publish invalidations afterward.

Completion gate: imports feed the existing tables/history/timeline. Retry, response loss, restart, simultaneous manual/automatic acceptance, and validation failure cannot duplicate or partially accept an operation.

### M5 — Cutover, reconciliation, and transportation

**Status: pending. Depends on M4.**

Add reviewed cutover setup with frozen opening-balance evidence:

- Review absolute quantities at the cutover, showing the resulting correction when a balance already exists.
- Require confirmation when an observation does not establish that balance/time; never add a snapshot on top of existing quantities.
- Record baseline coverage by item/location; unresolved scopes remain blocked.
- Keep pre-cutover movements as reference evidence, and allow post-cutover receipt of a pre-cutover sale's return.
- Later snapshot discrepancies create Review proposals rather than automatic resets.

Build source-backed transit projections for dispatched/travelling, delivered, returning, physically returned, and unknown/incomplete states. Show quantities separately from warehouse stock, retain partial quantities, and distinguish actual event time from status observation time.

Completion gate: cutover does not double-count openings/earlier movements. Dispatch reduces warehouse stock while transit remains visible; delivery closes transit without another deduction; physical receipt restores stock once.

### M6 — Ozon read client and connection setup

**Status: pending. Depends on M5.**

Add manager-controlled connection validation, pause/reconnect, and encrypted server-side credentials using the installed encryption facilities with a separate connector key. Exclude credentials from logs, browser responses, payloads, and fixtures.

Implement an explicit read-endpoint allowlist for inventory domains, detail enrichment, related SKUs, and attribute metadata. Preserve int64 identifiers/exact decimals; bound requests, validate full pagination/detail batches, detect repeated cursors, and respect endpoint pacing.

Honor provider retry delays. Persist long waits instead of holding an inventory transaction or sleeping a worker through them.

Completion gate: mock-server tests cover pagination, wrapper changes, throttling, interruption, incomplete responses, and lossless numbers. A read-only live smoke test confirms contracts before enabling ingestion.

### M7 — Durable daily orchestration and automatic acceptance

**Status: pending. Depends on M6.**

Add one Integrant-managed worker with durable repository-backed jobs/checkpoints. Keep HTTP outside RDF transactions. Run every 24 hours from successful completion; Sync now reuses an active run or resumes a checkpoint. Commit page/batch progress before reporting completion, resume after restart, and retain coverage after partial failure rather than falsely advancing it.

Refresh known non-terminal/unresolved records beyond the incremental discovery window. Enrich/normalize required sources before automatic eligibility evaluation.

Automatic acceptance requires an enabled connection, completed baseline coverage, user-confirmed goods/warehouse mappings, complete compatible quantities/units/dates/locations, no user edits or unresolved source conflicts, current policy/core validation, and a new event rather than a historical change. Process eligible events in business chronology. Leave exceptions in Review.

Managers control connections/automation; active members resolve ordinary inventory Review items. Recheck the connection state before automatic commits.

Completion gate: restart, pause, authentication failure, provider outage, duplicate Sync now, and manual-review races preserve progress and inventory. Progress, successful coverage, and failures are accurately visible.

### M8 — External columns and source inspection

**Status: pending. Depends on M7.**

Use imported field definitions for a searchable, source-grouped column picker. Keep required columns visible and persist optional choices per user/workspace/table. Show source context for multiple listings and collections without multiplying stock rows or selecting an arbitrary first value.

Expose transit quantities on goods and shipment status on operations; accepted history exposes source details. Catalogue-only goods have an explicit empty history state.

Initially, arbitrary source attributes support display/inspection. Retain core sorting/filtering and explicitly implement server transportation filters; general sorting/filtering over every source attribute is deferred. Use bounded lookups for visible rows and existing realtime refreshes.

Completion gate: multiple listings/barcodes/warehouses do not multiply quantities or groups; required columns stay visible; preferences survive refresh and remain account/workspace-scoped.

### M9 — Migration rehearsal and controlled pilot

**Status: pending. Depends on M8.**

Run the complete workflow against an isolated copy at realistic catalogue/operation volume. Rehearse backup/restore for catalogue/external records, mappings, jobs, policies, preferences, ledger history, and receipts.

Activate one connection through setup/cutover. Verify ingestion and reviewed mappings before enabling automatic acceptance. Inspect legacy fetching schedules for operational overlap; changing the old Supabase system requires separate authorization.

Completion gate: pilot evidence covers stable daily fetch, new-event acceptance, actionable exceptions, reviewed corrections, interruption recovery, and migration/restore. Record remaining limitations before wider activation.

## Verification gates for M1–M9

Every Clojure coding session follows [AGENTS.md](../AGENTS.md): discover/attach to the active-worktree nREPL before edits, or start `clojure -M:dev:nrepl`; apply runtime/shared-fixture changes through `(user/start!)`; never use `:reload-all` or `refresh-all`. Reinspect dependency APIs through nREPL rather than cached JAR/source scraping. Use `clj-paren-repair` for delimiter repair.

Required scenarios:

- Workspace isolation; manager/member permissions; disabled connection before automatic commit.
- Multiple identifiers/source SKUs, article changes, scoped collisions, and policy changes outside the selected inventory component.
- Same-name variants, explicit new goods, whole-unit/pack conflicts, and no automatic unit conversion.
- Catalogue-only and mixed acceptance, stale source/mapping/policy/catalogue revisions, and all lines retained.
- Cutover boundaries, late events, pre-cutover references, and absolute reconciliation without double-counting.
- Dispatch/delivery, cancellation after shipment, partial returns, and separate transit quantities.
- New automatic events versus reviewed corrections; source refresh preserving user edits.
- Durable receipts, retries, concurrent acceptance, crash recovery, partial fetches, and lossless numbers.
- Projection rebuild equivalence, complete backup/restore, bounded reads, and safe semantic-query timeout behavior.
- English/Russian labels, errors, translation checks, keyboard flows, and browser workflows.

Run focused checks at each milestone. At integration/release gates run the full instrumented suite, formatting, lint, relevant browser suites, migration/restore, and explicit live provider checks. Do not treat old successful measurements as current evidence. Never use legacy local mappings as matching truth or commit private source captures.

## M0 release evidence

Release-candidate checks passed before publication on 2026-11-06. The later rows record deployment checks and the authenticated production follow-up:

| Check | Result |
| --- | --- |
| `clojure -M:dev:test` | 524 tests, 8,968 assertions, zero failures |
| `clojure -M:fmt check` | All source files formatted correctly |
| `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` | Zero errors/warnings; informational notes only |
| `node --test test/browser/i18n.test.mjs` | 3 checks passed |
| Catalogue Playwright config | 8 tests passed |
| i18n Playwright config | 14 tests passed, including both languages and all eight operations |
| Production `ce-adapter` + `ce-item-options` | 27 tests passed |
| `git diff --check` and publisher private-path guard | Passed; 332 nonignored paths checked before publication |
| Publisher | Clean release commit, annotated tag/push, amd64 bundle, image checksums/identities, archive, manifest, and GitHub release completed |
| Production backup | v0.2.0 closed stores captured; `COMPLETE`, manifest, source commit, and all backup checksums verified |
| Activation | `/srv/tover` and image setting select v0.3.0; runtime image ID matches the release manifest |
| Live health | App healthy; `/healthz` and `/readyz` both HTTP 200 / `ok` |
| Live public UI | English and Russian sign-in rendering verified; language preference restored to English |
| Live authenticated UI | Follow-up on 2026-11-06: Goods, Warehouses, Suppliers, Categories and Stores loaded in both English/Russian; translated grids, headers, search and Saved state verified; no browser console errors observed |

Browser commands ran from `test/browser`:

```sh
npx playwright test --config catalogues.config.mjs
npx playwright test --config i18n.config.mjs
npx playwright test ce-adapter.spec.mjs ce-item-options.spec.mjs --project production
```

Publication used:

```sh
python3 deployment/production/publish.py v0.3.0 \
  --message "Add workspace catalogues and inventory catalogue integration" \
  --output /private/tmp/tover-v0.3.0 \
  --skip-rehearsal
```

Release limitations and operational notes:

- Full production-shaped installation/restore rehearsal was explicitly skipped for the fast release. The consistent pre-upgrade backup was still taken and verified.
- The initial authenticated-check gap was resolved by the follow-up below after the user signed in to the in-app browser. This was navigation/rendering verification; live edit/save, validation, retry and concurrency scenarios remain covered by the isolated suites rather than production mutations.
- Local public-UI screenshots were saved to `/private/tmp/tover-v0.3.0-production-login-en.jpg` and `/private/tmp/tover-v0.3.0-production-login-ru.jpg`; these temporary files are optional local evidence, not durable prerequisites for resuming the plan.
- Authenticated catalogue screenshots are local at `/private/tmp/tover-v0.3.0-live-catalogues-en.jpg` and `/private/tmp/tover-v0.3.0-live-catalogues-ru.jpg`. They contain live business data: keep them out of Git. The bilingual outcome and scope are recorded below so resuming work does not depend on temporary images.
- Backup off-host copying and a restore from this particular backup were not performed in this session. The earlier catalogue unit suite does include reopen/rebuild and restored-store preservation tests; that does not replace a full production-shaped rehearsal.
- `source-revision` is a multi-line release metadata file: commit on the first line, `build-sha256` on the next. Compare the first line with `release.json` when verifying a commit. An initial pre-activation assertion assumed the whole file was a hash; it was corrected before any deployment-setting changes.
- Remote helper calls from a streamed shell script should receive `</dev/null` unless they deliberately need input. Docker exec inside the backup helper can otherwise consume the remaining outer script. The backup itself completed, and its verification was performed explicitly afterward.
- The full Clojure suite's instrumentation restart check can spend time exporting Malli/clj-kondo metadata. The run completed successfully; do not silently weaken that check because output pauses.

## Milestone checklist

| Milestone | State | Completion evidence / next requirement |
| --- | --- | --- |
| M0 | **Complete** | v0.3.0 committed/deployed; verified backup/health and bilingual authenticated catalogues; complete plan/context saved separately |
| M1 | **Next** | Define source model, invariants, contract matrix, policies, and sanitized fixtures |
| M2 | Pending | Persistence, bounded lookups, Store collection, reopen/rebuild evidence |
| M3 | Pending | Reviewed linking/creation, stable provisional IDs, mixed acceptance |
| M4 | Pending | Normalization, shared atomic acceptance, provenance, deduplication/races |
| M5 | Pending | Reviewed cutover/reconciliation, transit projection, boundary tests |
| M6 | Pending | Protected connection setup, lossless read client, contract/live smoke evidence |
| M7 | Pending | Restart-safe daily worker, eligibility, progress and failure evidence |
| M8 | Pending | Optional external columns, source inspection, transit UI, bounded reads |
| M9 | Pending | Restored-copy rehearsal, controlled pilot, deployment evidence |

## Session protocol

At the start of each session:

1. Read this checkpoint and current AGENTS instructions. Inspect Git state and the actual owning source; do not assume a clean tree or reinterpret another session's edits.
2. Verify the last recorded milestone gate and identify the first unfinished milestone. Recheck live release/provider state when it matters to the work.
3. Attach/start the worktree REPL before Clojure edits. Do not hardcode a historical nREPL port.
4. Continue the concrete next action. Keep changes within the selected milestone and preserve source/projection/storage boundaries.

At the end of each session:

1. Record the implemented behavior, code commit(s), affected interfaces, exact checks and outcomes, migration/rehearsal evidence, limitations, and where execution stopped.
2. Update milestone status only after its completion gate passes. Keep failed/incomplete checks visible.
3. Write one concrete next action and any genuine blocker. Revise a locked decision only for an explicit product change or contradictory evidence, and record the replacement and reason.
4. Commit the execution-record update with or immediately after the milestone work so the next session can resume from repository evidence.

Session-log template:

```text
Date / milestone:
Starting commit and relevant pre-existing work:
Behavior delivered / interfaces changed:
Commits (or this commit, resolvable through Git history):
Checks: exact commands, result counts, and failures:
Migration / provider / deployment evidence:
Known limitations or blockers:
Milestone gate: passed / not passed, with evidence:
Next action:
```

### 2026-11-06 — M0

- Starting point: main at v0.2.0 commit `4e923ce6f10defce6beaca55c59819a3b32656f8` plus the existing catalogue/inventory work (33 tracked changes, 9 new files).
- Committed all current work in `d96fb3a83d394a75be2098f87c0347d158beabd8`, published v0.3.0, transferred and verified the offline bundle, backed up the previous closed stores, and activated v0.3.0.
- Verified running image identity, healthy application, both public health endpoints, and public English/Russian rendering. The authenticated check was initially pending and was completed in the follow-up below.
- Saved this full execution record after deployment, with a separate documentation commit and a link from the documentation index. No external-model, fetcher, or M1 implementation was added.
- Gate: M0 complete; the full release rehearsal limitation remains explicit, and the initial authenticated-smoke gap was resolved in the follow-up below.
- Next action: **M1**. Read the current catalogue/acceptance contracts, define the external model and source-to-operation contract matrix, and implement sanitized executable cases before introducing live fetching.

### 2026-11-06 — M0 authenticated production follow-up

- Starting commit: `9cff9b46bc0dbe23dc754275822dbab1a4091cf5`. The user completed sign-in to the existing in-app production tab.
- Reconfirmed `/srv/tover-releases/v0.3.0`, application image `tover:v0.3.0`, the recorded image ID, and healthy application state. No application deployment or code change was needed.
- Completed ten unique catalogue/locale combinations. Navigation, populated and empty grids, translated column headers, search, and Saved/Сохранено status rendered successfully:

| Catalogue | English headers | Russian headers | Result |
| --- | --- | --- | --- |
| Goods / Товар | Name, Article, Barcode, Unit, Category, Store | Название, Артикул, Штрихкод, Единица, Категория, Магазин | Passed in both languages |
| Warehouses / Склады | Name, Description, Purpose | Название, Описание, Назначение | Passed in both languages |
| Suppliers / Поставщики | Name, Address, Contact information | Название, Адрес, Контактная информация | Passed in both languages |
| Categories / Категории | Name | Название | Passed in both languages |
| Stores / Магазины | Name, Default warehouse | Название, Склад по умолчанию | Passed in both languages |

- No browser console errors were observed. An early English screenshot caught incomplete page rendering; it was replaced after waiting for document load and the named grid. The fully loaded English and Russian Goods layouts were visually checked.
- Performed no catalogue cell edits, record creation, or inventory submission. Returned to the original Stock page and restored the original Russian language preference. Existing pending-change indicators matched the initial state.
- Saved bilingual screenshots only to the private temporary paths above; no live record contents or images were added to the repository.
- Updated this execution record in a separate documentation commit. M0 remains complete; **M1 remains next**. The full production-shaped restore rehearsal and live mutation workflows were not rerun for this documentation-only follow-up.

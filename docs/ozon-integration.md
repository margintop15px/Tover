# Ozon Marketplace Integration

This document describes the Ozon Seller API integration in Tover: how a seller
connects an account, which Ozon endpoints are used, which data is mirrored, how
read-only marketplace evidence is transformed into Tover operation candidates,
and why some Ozon data remains reporting-only.

Research basis:

- Ozon Seller API reference: https://docs.ozon.ru/api/seller/
- Every active request/response contract below was re-verified in the live
  Seller API reference on 2026-07-27. Paths are recorded beside their decoder
  tests so future contract audits have an explicit verification date.

## Product Contract

The integration is read-only against Ozon. Tover never mutates Ozon prices,
stocks, postings, shipments, returns, products, chats, campaigns, or labels.

Tover does write local data:

- encrypted marketplace credentials;
- Ozon mirror tables;
- Ozon-to-Tover product and warehouse mappings;
- marketplace operation candidates;
- manually committed Tover operations.

The key product rule is: Ozon evidence becomes a Tover operation candidate only
when the evidence truthfully supports a Tover operation type. Finance,
settlement, payout, legal-entity, and report data is mirrored for analytics, but
is not forced into supplier-oriented Tover `payment` operations.

## Authentication

The user connects Ozon from Settings > Integrations by entering:

- `Client-Id`;
- `Api-Key`.

Every Ozon Seller API request sends:

```txt
Client-Id: <client id>
Api-Key: <api key>
Content-Type: application/json
```

Ozon uses `POST` for many read endpoints. Read-only safety is therefore enforced
by `src/lib/ozon/client.ts` with a strict server-side endpoint allowlist, not by
HTTP method.

Each `OzonClient` spaces request starts by at least 25 ms, limiting it to at
most 40 starts per second below Ozon's 50-request limit. Every HTTP attempt has
a 30-second timeout. Safe read requests get at most four total attempts for
transport/timeouts, HTTP `408`, `425`, `429`, and `500`-`599`. The delays before
attempts two through four are approximately 500 ms, 1 second, and 2 seconds,
plus up to 250 ms of jitter. Durable requests keep the same four-attempt ceiling,
but the shared execution signal stops pacing, backoff, or an attempt when the
remaining step budget is exhausted; the durable scheduler then resumes from
the checkpoint.

`Retry-After` is accepted as either seconds or an HTTP date.
`Item-Retry-After` is interpreted as minutes. A server-provided delay overrides
a shorter local delay, and every delay is capped at 30 seconds. HTTP `400`,
`401`, `403`, and ordinary `404` responses are not retried. The typed client
error exposes only a sanitized endpoint, status, code, message, and calculated
retry delay to server code. Durable step persistence reduces that further to a
fixed message plus safe kind/status/retry metadata; credentials and raw Ozon
payloads are never stored in step errors.

Credentials are encrypted with `OZON_CREDENTIAL_ENCRYPTION_KEY`. The
implementation derives an AES-256-GCM key from this secret with SHA-256 and uses
a random 12-byte IV for every encryption. Public API responses only expose masked
credential hints.

`OZON_API_BASE_URL` defaults to `https://api-seller.ozon.ru`. Tests override it
with a local mock server.

## Code Map

- `src/lib/ozon/client.ts`: Ozon client, base URL override, read-only endpoint
  allowlist, credential validation.
- `src/lib/ozon/credentials.ts`: credential encryption/decryption and hints.
- `src/lib/ozon/sync.ts`: ordered domain registry, pagination, mirror upserts,
  candidate generation, and raw payload sanitization.
- `src/lib/ozon/durable-runner.ts`: step claiming, execution, error
  classification, durable retry scheduling, and lease-aware finish calls.
- `src/lib/ozon/durable-sync.ts`: begin/resume and retry-failed coordination,
  public run summaries, and recovery counts.
- `src/lib/ozon/candidates.ts`: candidate normalization, validation, evidence
  hashing, mapping, and local product/warehouse creation.
- `src/app/api/integrations/ozon/*`: connection, validate, sync, candidate
  review, approval, and commit APIs.
- `src/app/settings/page.tsx`: connection credential management and validation.
- `src/app/operations/marketplaces/page.tsx`: marketplace sync controls, mirror
  counts, and candidate review entrypoints.
- `src/app/operations/page.tsx`: shows a Review candidates button when Ozon has
  pending ready, needs-mapping, or approved candidates.
- `src/app/operations/marketplace/ozon/page.tsx`: candidate review UI.
- `supabase/migrations/012_ozon_marketplace_integration.sql`: base connection,
  mirror, candidate, and sync-run tables.
- `supabase/migrations/013_ozon_candidate_approved_status.sql`: manual approval
  status.
- `supabase/migrations/014_ozon_domain_expansion.sql`: expanded source types,
  full operation-type candidate constraint, and additional Ozon mirror tables.
- `supabase/migrations/015_ozon_commit_hardening.sql`: transient `committing`
  status, `completed_with_errors` sync status, and Ozon commit claim/source
  guards.
- `supabase/migrations/020_ozon_sync_recovery.sql`: durable sync steps, active
  run constraints, leases, service-role worker RPCs, and Supabase Cron
  scheduling.
- `supabase/migrations/021_ozon_sync_checkpoints_observability.sql`: checkpoint,
  failure-count, event timeline, versioned worker RPCs, and covering indexes.
- `supabase/migrations/022_ozon_evidence_accounting_correctness.sql`: mirror
  provenance, evidence hashes, atomic candidate commits, and nullable inventory
  cost basis.
- `supabase/migrations/024_ozon_live_contract_fixes.sql`:
  seller-relevant warehouse counts and explicit fresh-project service-worker
  privileges.
- `supabase/migrations/025_ozon_relevant_warehouse_identity.sql`:
  identity-first warehouse relevance so a same-name global location cannot be
  counted as seller evidence, plus the database-linter-correct volatility for
  the safe step-error sanitizer.
- `supabase/migrations/026_optimize_ozon_relevant_warehouse_counts.sql`:
  materializes the connection-scoped warehouse reference set once so
  authenticated RLS checks stay comfortably inside the Data API statement
  timeout.

## Verified Seller API Operations

The endpoint path is the operation identifier used by the generated Seller API
reference and by Tover's endpoint-specific decoder tests. These contracts were
last re-verified against the official Ozon Seller API reference on 2026-07-27:

| Domain | Official operation ID | Decoder contract |
| --- | --- | --- |
| Warehouses | `/v2/warehouse/list`, `/v1/warehouse/fbo/seller/list` (`WarehouseFboSellerList`) | seller FBS/rFBS cursor list and account-scoped seller FBO warehouses; other Ozon locations are added only when the seller's own data references them |
| Products | `/v3/product/list`, `/v3/product/info/list`, `/v4/product/info/attributes`, `/v5/product/info/prices` | string IDs; one identifier family per info-list request; nested price; string/array images |
| Stocks | `/v4/product/info/stocks` | stock `type`, `present`, `reserved`; empty `stocks[]` means no stock rows; no warehouse |
| Postings | `/v4/posting/fbs/list`, `/v3/posting/fbo/list` | Money product price and schema-specific `analytics_data` warehouse |
| Returns | `/v1/returns/list`, `/v2/returns/rfbs/list`, `/v2/returns/rfbs/get` | documented nested filters, numeric-zero first `last_id`, root/member wrappers |
| Finance | `/v1/finance/accrual/types`, `/v1/finance/accrual/by-day` | `accrual_id`, Money, item/non-item fees |
| Legal | `/v1/finance/document-b2b-sales/json`, `/v1/posting/unpaid-legal/product/list` | B2B `buyer_info`/`info`/`operations`; reporting only |
| Reports | `/v1/finance/mutual-settlement`, `/v1/finance/compensation`, `/v1/finance/decompensation`, `/v1/finance/cash-flow-statement/list`, `/v1/finance/products/buyout`, `/v1/report/info` | monthly codes, half-month cash flow keyed by period boundaries/currency, <=31-day buyout |
| Removals | `/v1/removal/from-stock/list`, `/v1/removal/from-supply/list` | return/box IDs, stock type, dates, utilization evidence |
| Supplies | `/v3/supply-order/list`, `/v3/supply-order/get`, `/v1/supply-order/bundle` | `order_ids`, nested `supplies[]`, paginated bundle items |
| Analytics | `/v1/analytics/stocks`, `/v1/analytics/turnover/stocks` | official named counts; one turnover request/minute |
| Discounted | `/v1/report/list`, `/v1/report/discounted/create`, `/v1/product/info/discounted` | one-based `SELLER_DISCOUNTED`, direct code, reporting-only detail |

## Data Model

Core tables:

- `marketplace_connections`: one Ozon connection per workspace, encrypted
  credentials, health, last sync status, and last sync metadata.
- `marketplace_sync_runs`: every sync run, date window, status, summary, and
  error.
- `marketplace_sync_run_steps`: the twelve ordered domain steps, attempt count,
  durable state, next attempt, ten-minute lease, safe error, and step summary.
- `ozon_products`: Ozon product mirror and local product mapping.
- `ozon_warehouses`: Ozon warehouse mirror and local warehouse mapping.
- `ozon_stock_snapshots`: raw stock snapshots from Ozon product stock APIs.
- `ozon_postings`, `ozon_posting_items`: FBS/FBO posting mirrors.
- `ozon_returns`: return mirrors.
- `ozon_finance_transactions`: accrual rows and fee/service detail.
- `marketplace_operation_candidates`: staged local operation candidates.
- `marketplace_operation_commit_claims`: local commit claims that prevent
  duplicate operation creation for the same Ozon candidate/source during manual
  commit.

Expanded mirror tables:

- `ozon_legal_entity_sales`: B2B/legal-entity sales registers.
- `ozon_unpaid_legal_products`: unpaid legal-entity product rows.
- `ozon_finance_reports`: cash-flow, buyout, compensation, decompensation, and
  mutual-settlement report rows or report-code references.
- `ozon_report_runs`: async report code/status/file metadata.
- `ozon_removals`: removal/disposal evidence.
- `ozon_supply_orders`, `ozon_supply_order_items`: FBO supply movement evidence.
- `ozon_stock_analytics`: marketplace stock analytics snapshots.
- `ozon_turnover_analytics`: turnover/stock-out analytics.
- `ozon_discounted_products`: discounted, damaged, or markdown product evidence.

Every mirror row stores a sanitized raw payload for audit/debugging. Sanitization
removes fields whose keys indicate personal data, including address, buyer,
contact, customer, email, fio, personal names, passport, phone, and recipient.
Legal-entity mirrors keep company/report identifiers such as invoice number,
company name, INN, and KPP, but redact personal contacts, phones, emails, names,
and addresses.

## Endpoint Allowlist

The allowlist is intentionally identical to the active integration. It contains
the warehouse, product, stock, posting, return, finance/legal/report, removal,
supply, analytics, and discounted paths listed in **Verified Seller API
Operations** above, plus `/v1/report/list`,
`/v1/report/discounted/create`, and `/v1/product/info/discounted`.

There are no unused taxonomy, legacy posting-detail, realization, generic
finance-transaction, or generic report-generation endpoints in the allowlist.
Any Ozon endpoint that changes price, stock, product content, shipment state,
cancellation state, labels, chat, promotions, or campaigns remains forbidden.

## Sync Flow

`POST /api/integrations/ozon/sync` begins a run or resumes the one active run for
the connection. A new run gets these twelve ordered step rows. Manual execution
runs sequentially within a bounded request budget; unfinished work remains in
Supabase instead of being lost with the request. Calling the route while a run
is active returns the same run and makes scheduled retries immediately
eligible. It also reactivates failed steps in that same active run; completed
and skipped steps remain untouched.

The route returns HTTP `202` while the run is `running` or `retrying`, and HTTP
`200` for terminal states. The response includes `runId`, the accumulated
successful step summaries, and safe recovery counts. Completed or validly
skipped steps are never claimed again, so resuming a run does not repeat their
Ozon requests.

The default date window is the last 30 days. The caller can pass `dateFrom` and
`dateTo` for backfills. The selected window is stored on the run and reused by
automatic and manual recovery.

Step states are:

- `pending`: not yet claimed;
- `running`: owned by a live lease;
- `retry_scheduled`: transient failure waiting for its next attempt;
- `completed`: successful;
- `skipped`: valid empty result;
- `failed`: terminal failure.

Run status is derived after every step transition:

- `retrying` when any step has a scheduled retry;
- `running` while work remains without a scheduled retry;
- `completed` when every step completed or was validly skipped;
- `completed_with_errors` when terminal failures coexist with successful or
  skipped steps;
- `failed` when no step succeeds.

Transport/timeouts, Ozon `408`, `425`, `429`, and `500`-`599`, plus database or
runtime errors not explicitly marked permanent, use durable retries. HTTP `400`,
`401`, `403`, unknown `404`, and explicit configuration/invariant errors are
permanent. Failed attempts 1 through 7 are scheduled after exactly 1 minute,
5 minutes, 15 minutes, 1 hour, 3 hours, 6 hours, and 12 hours. The eighth
actual failure becomes terminal. Claims increment `attempt_count`; only real
executed failures increment `failure_count`. Permanent failures terminate
without scheduling a retry. Checkpoint yields and report-poll waits do not
increment `failure_count`. An operator-triggered retry of a failed step resets
the current retry-cycle failure count, preserves its checkpoint and summary,
and records a `retry_requested` event; prior failure events remain unchanged.

The HTTP client retries safe retrieval requests at most four times with a
30-second attempt timeout, paced request starts, jittered backoff, and Ozon
retry-header support. Report-creation operations are not retried after an
ambiguous transport or server failure because the report may already have
been created; the durable runner records the transient failure and resumes
from the persisted phase instead.

Workers claim one step atomically. A claim increments the attempt count and
creates a unique ten-minute lease token. Another worker cannot claim work for
that connection while its lease is live. An expired `running` lease is
reclaimable; only the current token can finish it, so a stale worker cannot
overwrite the reclaimed result. The short database claim transaction is
serialized before `FOR UPDATE SKIP LOCKED`, preventing concurrent workers from
selecting different pending rows for the same connection. This lock is released
before API work starts, so independent connections still execute concurrently.
A partial unique index remains the final guard and permits only one `running` or
`retrying` run per connection. Stale-lease RPCs return a deterministic
prerequisite-state error rather than the retryable PostgreSQL serialization
code.

Each claimed execution receives one absolute deadline and one shared abort
signal for all Ozon requests, including request pacing. Manual work derives
that deadline from its 25-second request budget; Cron recovery uses a
100-second worker budget inside the route limit. The runner reserves the final
2 seconds for the lease-aware finish RPC, waits for the domain to unwind after
cancellation. A budget cancellation saves the latest checkpoint and yields the
step back to immediately due `pending`; it is not recorded as a failure.

Every successfully committed page, date, report phase, or detail batch can
extend the same lease through `checkpoint_ozon_sync_run_step_v2`. The lease
token is validated for checkpoints, yields, and finishes, so stale workers
cannot overwrite reclaimed progress. `GET /api/integrations/ozon/sync/[runId]`
returns only safe progress, counts, normalized errors, and the recent
`marketplace_sync_step_events` timeline.

Every caught step failure emits an `ozon_sync_step_failed` entry in the app
hosting logs. Correlate it with Supabase using `runId`, `connectionId`,
`stepKey`, and `attemptCount`. The entry includes retry classification and,
for typed Ozon errors, safe endpoint/status/code/reason fields. It never logs
credentials, ciphertext, authorization headers, request/response payloads,
stack traces, arbitrary unknown-error messages, or database error text.

### 1. Warehouses

Endpoints: `POST /v2/warehouse/list` and
`POST /v1/warehouse/fbo/seller/list`.

The FBS/rFBS seller list uses `limit <= 200` and cursor pagination. The FBO
seller list uses the account-scoped `WarehouseFboSellerList` contract and has
no request fields. Tover deliberately does not call
`/v1/warehouse/ozon/list`: that endpoint returns the global Ozon network, not
the seller's warehouses. Historical global mirror rows are not deleted during
rollout; they remain hidden unless the user mapped/ignored them or seller data
references their exact Ozon ID. Warehouse-name fallback is used only when the
source contract does not provide an ID.

Domain steps insert a referenced warehouse when an Ozon response supplies both
its ID and name. This insert-only enrichment never overwrites an existing
warehouse's official detail or user mapping. Tover stores Ozon warehouse ID,
name, fulfillment schema/status, sanitized raw payload, and a local warehouse
mapping.

Auto-mapping uses local warehouse name. Existing manual/ignored mappings are
preserved.

### 2. Products

Endpoints:

- `POST /v3/product/list`
- `POST /v3/product/info/list`
- `POST /v4/product/info/attributes`
- `POST /v5/product/info/prices`

`/v3/product/info/list` accepts only one identifier family per request. Tover
prefers `product_id` and sends separate `offer_id` or `sku` fallback requests
only for product references without a numeric product ID.

Tover stores Ozon product ID, `offer_id`, SKU, name, barcodes, images, status,
visibility, category/type identifiers, prices, attributes, sanitized raw payload,
and a local product mapping.

Auto-mapping uses local `products.sku_code` against Ozon `offer_id`, Ozon SKU,
or barcode. Manual/ignored mappings are preserved.

### 3. Stock Snapshots

Endpoint: `POST /v4/product/info/stocks`.

Tover inserts point-in-time rows into `ozon_stock_snapshots` by documented
stock `type`, `present`, and `reserved`. This endpoint does not identify a
warehouse, so Tover does not invent one or attach a local warehouse mapping.
An item with an empty `stocks[]` array contributes no stock rows; Tover does not
reinterpret the product wrapper as a stock record. Snapshots are mirrors, not
operations.

### 4. Postings

Endpoints:

- `POST /v4/posting/fbs/list`
- `POST /v3/posting/fbo/list`

Delivered FBS/FBO postings create `sale` candidates only when the response also
contains the documented `in_process_at` event timestamp. The delivered status
proves finality; `in_process_at` supplies the source date without inventing a
completion timestamp that these list contracts do not expose. Each Ozon posting
becomes one Tover sale candidate and may contain multiple line items.

Canceled postings create ignored audit candidates with a warning. Intermediate
posting states remain mirrored only.

### 5. Returns

Endpoints:

- `POST /v1/returns/list`
- `POST /v2/returns/rfbs/list`
- `POST /v2/returns/rfbs/get`

`/v1/returns/list` uses `filter.logistic_return_date`, `limit`, and `last_id`.
The first request sends numeric zero because the field is `int64`; later
cursors are preserved as decimal strings so large identifiers are not rounded
by JavaScript. The root `returns` member is followed while `has_next` is true.
The next `last_id` is the last returned row's `id`; Ozon does not return a
separate cursor. Its product price uses the documented legacy
`{ price, currency_code }` object. rFBS uses `filter.created_at`; a full page is
continued with the last row's `return_id`, using the same numeric-zero first
cursor, and detail responses are unwrapped from their `returns` member. The
current rFBS detail contract does not prove both quantity and seller-receipt
time, so those rows remain mirrors. A return candidate is created only when
Ozon explicitly proves seller receipt with `ReceivedBySeller` and supplies the
product, positive quantity, event date, and a mappable warehouse. The legacy
`ReturnedToSeller` spelling is also accepted for compatibility. `WriteOff` and
incomplete states remain mirrored.

### 6. Finance Accruals

Endpoints:

- `POST /v1/finance/accrual/types`
- `POST /v1/finance/accrual/by-day`

Tover uses `accrual_id` as the only transaction identity and stores nested
Money values as exact decimal strings for PostgreSQL `NUMERIC`. Identical
duplicate IDs inside one response page are coalesced; conflicting payloads for
one ID are a permanent invariant failure. It stores posting references, item
and non-item fees, commissions, delivery services, currency, and sanitized
raw payloads in `ozon_finance_transactions`.

These rows power marketplace profitability and fee analytics. They do not create
Tover `payment` operations because Tover payments currently model supplier
payments, not marketplace settlements or payouts.

### 7. Legal-Entity Sales

Endpoint: `POST /v1/finance/document-b2b-sales/json`.

Tover stores invoice/report identifiers, invoice date, posting number, company
identifiers, amount, product rows, sanitized raw payload, and operation candidate
link.

Legal-entity and unpaid-legal rows are reporting-only. They never create sale
candidates, avoiding duplicate or unsupported inventory operations.

Endpoint `POST /v1/posting/unpaid-legal/product/list` mirrors unpaid legal
products for reporting only.

### 8. Finance Reports

Endpoints:

- `POST /v1/finance/mutual-settlement`
- `POST /v1/finance/compensation`
- `POST /v1/finance/decompensation`
- `POST /v1/finance/cash-flow-statement/list`
- `POST /v1/finance/products/buyout`
- `POST /v1/report/info`

Report-generating endpoints may return an Ozon report code. Tover stores report
code, status, file URL, request params, and response payload in `ozon_report_runs`
and summarizes rows in `ozon_finance_reports`.

Ozon can legitimately return `404` while a monthly document does not yet
exist. Tover treats only these endpoint-matched identities as empty data:

- mutual settlement: `finance document not found`;
- compensation: `compensation document not found`;
- decompensation: `decompensation document not found`.

The identity must be the exact safe code/message or Ozon's terminal
`desc = ... document not found` form. That report/month is counted as skipped,
and the other report types, months, cash flow, and buyouts continue. Mismatched
identities and every other `404` remain permanent errors.

Cash flow is requested in calendar half-month periods (days 1-15 and 16-month
end) and follows `page_count`. Buyout windows are non-overlapping and no longer
than 31 days. Report creation and polling are separate checkpointed phases; a
waiting/processing code yields the step to a future `pending` action without
incrementing `failure_count`.

Buyout reports are seller-side sale evidence, not merchant purchases. They
remain reporting data.

### 9. Removals and Disposal

Endpoints:

- `POST /v1/removal/from-stock/list`
- `POST /v1/removal/from-supply/list`

Tover mirrors return/box identifiers, `quantity_for_return`, stock type, state,
warehouses, delivery/given-out/utilization dates, and preliminary delivery
price. Only an explicit `utilization_date` can generate a write-off candidate;
other removal rows remain mirror-only.

### 10. FBO Supplies

Endpoints:

- `POST /v3/supply-order/list`
- `POST /v3/supply-order/get`
- `POST /v1/supply-order/bundle`

Tover reads order-level `supplies[]`, persists supply and bundle identity/state,
storage warehouse, and completion date separately, and paginates
`/v1/supply-order/bundle` with `last_id`/`has_next`. Child replacement is one
database transaction with its parent order update, so a failed child insert
rolls the parent and child changes back and cannot erase valid bundle rows.
A transfer candidate requires a completed supply, explicit bundle quantity,
and destination. The local source warehouse is still a required user mapping.

The list request uses the real Ozon contract:

```json
{
  "filter": {
    "states": [
      "DATA_FILLING",
      "READY_TO_SUPPLY",
      "ACCEPTED_AT_SUPPLY_WAREHOUSE",
      "IN_TRANSIT",
      "ACCEPTANCE_AT_STORAGE_WAREHOUSE",
      "REPORTS_CONFIRMATION_AWAITING",
      "REPORT_REJECTED",
      "COMPLETED",
      "REJECTED_AT_SUPPLY_WAREHOUSE",
      "CANCELLED",
      "OVERDUE"
    ]
  },
  "last_id": "",
  "limit": 100,
  "sort_by": "ORDER_CREATION",
  "sort_dir": "DESC"
}
```

Tover reads `order_ids` and `last_id` from either the response root or its
`result` wrapper, canonicalizes numeric/string IDs, and follows the cursor until
it is empty or repeats (with a 100-page safety limit). It then calls
`POST /v3/supply-order/get` in batches of at most 50 IDs. The list endpoint is
not assumed to contain full order records.

The Ozon destination warehouse can auto-map. The local source warehouse is not
known from Ozon and must be selected by the user before commit.

### 11. Stock Analytics and Turnover

Endpoints:

- `POST /v1/analytics/stocks`
- `POST /v1/analytics/turnover/stocks`

Tover mirrors `valid_stock_count`, `available_stock_count`,
`requested_stock_count`, transit, customer/seller returns, defect, other,
excess, expiring, waiting-document, ADS, and IDC fields without relabeling
`requested_stock_count` as reserved stock. Each stock row is identified by its
documented SKU, cluster, and warehouse ID. A blank `warehouse_name` is stored
as `NULL`: it is descriptive metadata, not identity, and no placeholder
warehouse is invented. ID-based local mapping remains available; name-based
mapping is only a fallback. SKU, warehouse ID, `valid_stock_count`, and
`available_stock_count` remain mandatory so an incomplete snapshot cannot be
reported as successful. Turnover requests are paced to one start per minute
per Client-Id. These rows are
reporting/reconciliation evidence only in the current implementation. They do
not generate `inventory_adjustment` candidates because daily stock deltas can
repeat and over-adjust local inventory without a dedicated reconciliation
workflow.

Ozon may return transient `5xx` responses for this analytics endpoint. They use
the normal bounded HTTP retry policy and, if all HTTP attempts fail, the durable
step retry schedule. A live contract run on 2026-07-27 completed nine
100-SKU-or-smaller batches with the same request contract; an empty
`/v1/analytics/stocks` response is valid and is not converted into a failure.
When a request budget expires during the one-minute turnover pacing wait, the
unused limiter reservation is released before the checkpointed step yields.
Resuming therefore waits only for the original one-minute window instead of
postponing the request again.

### 12. Discounted or Damaged Products

Endpoint: `POST /v1/product/info/discounted`.

Tover obtains authoritative discounted SKU identifiers through Ozon's
`SELLER_DISCOUNTED` report using one-based pages. It reuses a completed report generated in
the preceding 10 minutes, resumes a `waiting`/`processing` report through
`POST /v1/report/info`, or starts one through
`POST /v1/report/discounted/create`. A processing report yields the same step
to a future action without recording a failure.
The report download accepts only the known Ozon report CDN hosts (or the exact
configured local mock origin), validates every redirect before following it,
shares the durable step deadline, and has its own 30-second timeout. It streams
the response with a hard 10 MiB limit instead of buffering an unbounded file.
Ozon currently returns this report as XLSX. Tover reads only
`xl/sharedStrings.xml` and `xl/worksheets/sheet1.xml`, caps their combined
expanded size at 20 MiB, and resolves the discounted child column from the
workbook's three-row `FBO Ozon SKU` header. A header-only workbook is a valid
zero-result report. CSV remains supported for older/local fixtures.

Seller-created discounted products whose mirrored payload explicitly has
`is_discounted: true` are also included. `has_discounted_item` identifies a
main product that has a discounted analogue, but does not provide that
analogue's SKU, so it is never submitted as if it were the discounted SKU.

The detail request uses Ozon's `discounted_skus` field and batches identifiers
in groups of at most 100.

Request failures are not converted to empty results: the durable runner
classifies and persists them so transient failures can recover and permanent
validation failures remain visible.

Tover mirrors the returned discounted products. It reads Ozon's damaged-product
evidence fields, including `reason_damaged`, `comment_reason_damaged`,
`defects`, mechanical/package damage, condition, and condition estimation.
Discounted products are always reporting-only. Ozon does not provide sufficient
quantity, warehouse, or operation-date evidence on this endpoint, so Tover
never invents those values and never creates a defect candidate.

## Recovery Worker Operations

On a fresh Supabase project, enable `pg_net` and `pg_cron`, and verify Vault,
before applying migration 020:

```sql
create extension if not exists pg_net with schema extensions;
create extension if not exists pg_cron;
select count(*) from vault.decrypted_secrets;
```

In current Supabase Dashboard projects Vault is exposed under Integrations and
may not appear with the literal `supabase_vault` label in the Extensions page.
The count-only query verifies that its decrypted view exists without copying
secret values into logs. If the `vault` schema is absent, enable Vault from
Dashboard Integrations before running migration 020. Migrations 021 and 022 add
checkpoints/events and evidence/cost-basis correctness; apply them before
deploying the corresponding app. Migration
`024_ozon_live_contract_fixes.sql` adds the seller-relevant warehouse
summary and makes `service_role` access/default privileges explicit so the
durable worker also works on a fresh project.

Run these statements as the project owner in the SQL Editor, or enable the same
extensions from Database > Extensions in the hosted Dashboard. `pg_net`
exposes `net.http_post` and its response table in the `net` schema; `pg_cron`
creates the `cron` schema. The `IF NOT EXISTS` form is safe for hosted projects
where an extension is already enabled. If extension creation is denied, a
project owner/database administrator must enable it before migration 020 and
before scheduling.

The Next.js deployment must set:

```bash
OZON_SYNC_RECOVERY_SECRET=replace-with-a-separate-long-random-secret
```

Supabase Cron calls
`POST /api/internal/integrations/ozon/recover`. The route requires the same
value in `x-tover-recovery-secret`, uses the service-role Supabase client, and
claims at most one due step per invocation. It returns only whether a step was
processed. Missing server configuration returns `503`; a bad secret returns
`401`. The app route has a 110-second execution budget, inside the migration's
120-second `pg_net` timeout. Claimed work receives a 100-second absolute
deadline, with an additional finish margin inside that worker budget.

Keep the production Vault URL pointed at the production deployment. Hosted
Supabase cannot call `localhost`. Manual local sync/retry does not use the Vault
URL or Cron. To exercise one recovery invocation against a local Next.js server,
set `OZON_SYNC_RECOVERY_SECRET` in `.env.local` and call:

```bash
RECOVERY_SECRET='the-same-value-used-in-.env.local'
curl -i -X POST http://localhost:3000/api/internal/integrations/ozon/recover \
  -H "x-tover-recovery-secret: $RECOVERY_SECRET"
```

The local route uses the Supabase URL and service-role key from the same local
environment. Use a local/staging Supabase project for isolated testing. If it
points to production, the call is a real production worker invocation; the
lease prevents duplicate ownership, but the call can still process live Ozon
data. Testing hosted Cron against local code requires an intentional temporary
HTTPS tunnel (or a separate Supabase branch) and a temporary Vault URL, followed
by restoring the production value.

After migration 020 is applied and the route is reachable, create the two
required Vault values with placeholders replaced at deployment time:

```sql
select vault.create_secret(
  'https://your-app.example.com/api/internal/integrations/ozon/recover',
  'tover_ozon_recovery_url'
);

select vault.create_secret(
  'replace-with-the-app-recovery-secret',
  'tover_ozon_recovery_secret'
);

select public.schedule_ozon_sync_recovery();
```

`schedule_ozon_sync_recovery()` verifies both secrets, removes any existing
`tover-ozon-sync-recovery` job, and recreates it on `* * * * *`. The scheduled
command reads the URL and header value from Vault on every execution. Never put
a real URL or secret in a migration.

Pause and resume an existing job without deleting durable work:

```sql
select cron.alter_job(
  job_id := (
    select jobid
    from cron.job
    where jobname = 'tover-ozon-sync-recovery'
  ),
  active := false
);

select cron.alter_job(
  job_id := (
    select jobid
    from cron.job
    where jobname = 'tover-ozon-sync-recovery'
  ),
  active := true
);
```

Remove it completely, or recreate it later:

```sql
select cron.unschedule('tover-ozon-sync-recovery');
select public.schedule_ozon_sync_recovery();
```

Inspect the definition and recent scheduler executions:

```sql
select *
from cron.job
where jobname = 'tover-ozon-sync-recovery';

select *
from cron.job_run_details
where jobid in (
  select jobid
  from cron.job
  where jobname = 'tover-ozon-sync-recovery'
)
order by start_time desc;
```

`cron.job_run_details` proves that pg_cron ran the SQL command. It does not
prove that the recovery endpoint succeeded: `net.http_post` only enqueues the
HTTP request and returns its request ID asynchronously. Inspect recent
responses with a bounded, header-free projection:

```sql
select
  id,
  status_code,
  timed_out,
  error_msg,
  content,
  created
from net._http_response
where created >= now() - interval '6 hours'
order by created desc
limit 100;
```

By default pg_net retains responses in `net._http_response` for six hours.
HTTP `401` means the Vault secret does not match the deployed
`OZON_SYNC_RECOVERY_SECRET`; `503` means the app variable is missing; HTTP
`500` returns the route's minimal recovery failure; and transport failures show
through `timed_out` or `error_msg`. The response `content` is safe here because
the recovery route returns only minimal JSON and no credentials. The query
intentionally omits headers and limits output to the recent diagnostic fields;
never select Vault decrypted secrets into logs.

Inspect recent runs and their ordered steps:

```sql
select
  runs.id,
  runs.connection_id,
  runs.status,
  runs.date_from,
  runs.date_to,
  runs.started_at,
  runs.completed_at,
  steps.step_order,
  steps.step_key,
  steps.state,
  steps.attempt_count,
  steps.failure_count,
  steps.checkpoint,
  steps.last_checkpoint_at,
  steps.next_attempt_at,
  steps.lease_expires_at,
  steps.last_error
from marketplace_sync_runs as runs
join marketplace_sync_run_steps as steps on steps.run_id = runs.id
where runs.provider = 'ozon'
order by runs.started_at desc, steps.step_order;
```

Monitor overdue retries and expired leases:

```sql
select
  run_id,
  connection_id,
  step_key,
  state,
  attempt_count,
  failure_count,
  checkpoint,
  last_checkpoint_at,
  next_attempt_at,
  lease_expires_at
from marketplace_sync_run_steps
where
  (state = 'retry_scheduled' and next_attempt_at < now())
  or (state = 'running' and lease_expires_at < now())
order by coalesce(next_attempt_at, lease_expires_at);
```

In the UI, **Retry now** calls the normal sync endpoint for an active
`running`/`retrying` run. It preserves attempt counts, completed/skipped steps,
and the original date window while making scheduled retries eligible
immediately. **Retry failed steps** is available for a terminal partial/failed
run; it calls `POST /api/integrations/ozon/sync/retry` with that `runId` and
resets only terminal `failed` steps to `pending`. It reuses the same run and date
window, while completed/skipped steps remain untouched.

## Inventory Cost Contract

`operation_items.unit_price` is transaction evidence: the invoice/sale price.
It is not inventory cost.

- Purchases and valued positive adjustments use their explicit unit price as
  cost.
- Sales and write-offs use only the cost already present in inventory.
- Returns reuse an existing known inventory cost; Ozon return price remains
  transaction evidence.
- Transfer and defect inbound movements inherit the source movement's cost.
- If a valid source cost does not exist, movement and balance cost stay `NULL`
  with `cost_basis_status = 'unknown'`.
- Weighted average is evaluated in PostgreSQL only when both the existing and
  incoming cost bases are known. Otherwise the resulting balance cost remains
  unknown.
- Inventory, movement, defect, and turnover totals—including report-template
  grouping—are calculated by versioned PostgreSQL `NUMERIC` RPCs. The API and
  UI only convert the returned values for display; they do not recompute
  monetary totals.

New operations use `cost_contract_version = 1`. Existing operations retain
version `0` until an explicitly approved reconciliation changes them. Migration
022 expands the schema and reporting functions but does not automatically
rebuild historical movements or balances.

## Production Reconciliation

`npm run ozon:reconcile -- --connection <uuid> --run <uuid>` is read-only. It
requires an exact Ozon connection/run pair and reports:

- the twelve step states and live leases;
- per-table current-contract, current-run, legacy/unverified, and superseded
  mirror coverage;
- return completeness and finance `accrual_id` identity/duplicate coverage;
- stale versus final candidates and their committed operation links;
- linked inventory movements, legacy cost contracts, unknown costs, and
  suspected transaction-price/cost matches.

The preview includes a deterministic SHA-256 confirmation digest. It does not
pretend to calculate historical after-values in JavaScript: exact historical
after-values require an isolated PostgreSQL ledger replay. Apply mode is
deliberately limited to the approved twelve-step repair reset:

```bash
npm run ozon:reconcile -- \
  --connection <uuid> \
  --run <uuid> \
  --apply \
  --confirm <exact-preview-digest>
```

Apply refuses a mismatched digest, a noncanonical step registry, or any live
lease. Completed, skipped, failed, and retry-scheduled steps can be selected for
this explicit repair; ordinary recovery still never repeats completed work. The
repair does not delete/supersede source mirrors and does not rebuild historical
operations, movements, or balances. Pause Cron and candidate commits
operationally before using apply. After the corrected run completes, generate a
new read-only preview. Soft supersession and any historical cost-contract
upgrade/rebuild require a separate explicit approval based on an isolated
PostgreSQL replay; they are not hidden inside the reset command.

## Candidate Status Preservation

Sync can update candidates in `needs_mapping` or `ready`.

Sync must not reset explicit user decisions:

- `approved` is preserved;
- `committing` is preserved as a locked commit/recovery state;
- `ignored` is preserved;
- `committed` is preserved and keeps `created_operation_id`.

This is why re-syncs can safely refresh raw payloads and mirror data without
undoing review work.

## Operation Support Matrix

| Tover operation type | Ozon evidence policy |
| --- | --- |
| `sale` | Generated only from final delivered FBS/FBO postings with documented `in_process_at`; the list contracts do not expose `delivered_at`. Legal-entity rows remain reporting-only. |
| `return` | Generated only from returns that explicitly prove seller receipt, quantity, product, event time, and destination warehouse. Current incomplete rFBS rows remain mirrors. |
| `write_off` | Generated from removal rows only when an explicit `utilization_date` proves disposal. |
| `transfer` | Generated from completed FBO supply orders, one candidate per product line, after user maps the unknown source warehouse. |
| `inventory_adjustment` | No Ozon candidate in the current implementation. Ozon stock analytics is mirrored for reconciliation/reporting only. |
| `defect` | No discounted-product candidate. Discounted detail lacks the quantity, warehouse, and event-time evidence required for a defect movement. |
| `purchase` | No candidate. Ozon buyout or supply data is not merchant purchase evidence from a supplier. |
| `payment` | No candidate. Ozon finance data is marketplace settlement/payout/accrual data; Tover `payment` is supplier-oriented. |
| `production` | No candidate. Ozon has no meaningful source event for production. |

Unsupported types may still have mirrored data for reporting, but are not
fabricated as operations.

## User Flow

1. Manager opens Settings > Integrations > Ozon.
2. Manager enters Client ID and API key.
3. Tover validates credentials with `POST /v2/warehouse/list`.
4. Manager opens Operations > Marketplaces.
5. Manager clicks Sync now for Ozon.
6. Tover mirrors Ozon products, warehouses, stocks, postings, returns, finance,
   legal-entity rows, removals, supplies, analytics, and discounted products.
7. If a transient step fails, Operations > Marketplaces shows automatic
   recovery, the next retry time, and the safe error while keeping successful
   mirror counts visible. The manager can choose Retry now.
8. A terminal partial failure offers Retry failed steps for the same run/window.
9. Tover generates candidates only for supported operation evidence.
10. Operations > Marketplaces shows mirror counts and sync health.
11. Operations shows Review candidates when Ozon has pending ready,
   needs-mapping, or approved candidates.
12. Manager opens `/operations/marketplace/ozon`.
13. Manager filters by status, operation type, source type, evidence, date, or
    mapping state.
14. Manager reviews candidate details and all line items.
15. Manager maps existing products/warehouses or creates missing local records.
16. Manager approves valid candidates.
17. Manager commits approved candidates.
18. Tover atomically validates current evidence, creates the operation and
    items, rebuilds inventory effects under a workspace advisory lock, links
    the commit claim, and marks the candidate committed.

Settings intentionally stays connection-only. It shows credential fields,
connection status, validation, disconnect, and last validated time. Sync buttons,
sync status, mirror counts, and Review candidates links live under Operations >
Marketplaces.

## Review UI Behavior

The Ozon review page shows:

- summary cards for needs mapping, ready, approved, committing, ignored, and
  committed;
- filters for status, operation type, source type, evidence support, mapping
  state, and date range;
- table rows with Ozon event ID, source, evidence label, status, first item, and
  validation count;
- a detail drawer with previous/next navigation across currently visible rows;
- per-line product and warehouse selectors;
- Create product and Create warehouse actions;
- Approve, Ignore, Restore to review, and Commit actions based on candidate
  status.

The detail drawer localizes validation labels and messages in the UI. Stored
validation payloads remain internal English messages.

Restore means `unignore`: the candidate is revalidated and returns to `ready` or
`needs_mapping`. It does not sync Ozon and does not create an operation.

## Commit Semantics

Only `approved` candidates with no validation errors can commit. The server also
requires candidate evidence to have `supportStatus = "commit_candidate"` and a
supported Tover operation type. Reporting-only and blocked evidence cannot be
approved or committed even if its payload looks operation-shaped.

Each candidate commit is one database transaction:

- already committed candidates are skipped;
- the candidate, connection, product, warehouse, store, source uniqueness,
  evidence version/hash, positive quantities, directions, and transfer
  symmetry are validated while locked;
- the operation, items, inventory rebuild, commit claim, and candidate link
  either all commit or all roll back;
- a transaction-scoped advisory lock serializes inventory changes per
  workspace.

## Current Simplifications and Follow-ups

- Candidate mapping-state and evidence filters partly evaluate normalized JSON in
  memory. This is acceptable for MVP review volumes but should become SQL JSON
  predicates or stored fields for large sellers.
- Finance/legal/reporting data is mirrored and counted in Settings. Rich
  reporting views for settlements, legal-entity sales, unpaid legal products,
  removals, supplies, and reconciliation gaps should be added outside the commit
  flow.
- Source warehouse mapping for supply transfers is candidate-level user input.
  Once a better local default or mapping model exists, this can become reusable.
- Discounted-product report files are downloaded with host, redirect, timeout,
  compressed-size, and expanded-size checks and parsed from Ozon's multi-row
  XLSX header. Other finance report files remain mirrored by report metadata;
  adding report-specific file decoders is outside the inventory commit flow.

## Test Coverage

The Ozon tests use `OZON_API_BASE_URL` to point Next.js at a local mock Seller API.

Covered by validation tests:

- sale, return, write-off, transfer, defect, and inventory-adjustment validation
  at the shared candidate boundary;
- missing product/warehouse mappings;
- invalid date, zero/negative quantity, negative price, and missing unit cost;
- unsupported purchase/payment/production evidence remains outside commit flow;
- support-status commit gating for reporting-only and blocked evidence;
- sync preservation rules for approved, committing, ignored, and committed
  candidates;
- Ozon raw payload sanitization keeps product/legal identifiers but removes
  personal buyer/contact/address data.

Covered by authenticated Playwright tests when Supabase service-role access is
available:

- invalid credentials;
- connection and sync with mocked Ozon;
- `/v4/posting/fbs/list` is used and deprecated `/v3/posting/fbs/list` is not;
- deprecated `/v3/finance/transaction/list` is not called;
- products, warehouses, postings, returns, finance, legal, report, removal,
  supply, and discounted-product mirrors sync;
- candidate review drawer navigation and localization;
- product and warehouse creation from Ozon data;
- manual mapping, approval, commit, and idempotent repeated commit;
- concurrent/double commit attempts create only one local operation;
- transient Ozon failures become `retrying`, recovery reuses the same run, and
  completed steps are not repeated;
- permanent failures become `completed_with_errors` or `failed`, and manual
  retry resets only failed steps;
- runner unit tests cover HTTP error classification, the exact durable retry
  schedule, and conversion of a transient attempt eight into a terminal result;
- migration-020 service-role RPC tests cover active-run serialization, single
  lease ownership, stale-token rejection, persistence/aggregation of
  caller-supplied failed results, selective reset, and run-to-step cascade
  cleanup;
- migrations 021 and 022 cover failure-count scheduling, cooperative yields,
  safe events, checkpoint/lease validation, atomic parent-child mirror
  replacement, evidence hashes, nullable cost basis, and atomic commits;
- exact endpoint-matched missing monthly finance documents are skipped while
  remaining report types continue;
- supported sale, return, write-off, and transfer evidence becomes normal Tover
  operations through one atomic RPC; discounted evidence remains reporting-only;
- inventory reports preserve unknown cost, and turnover uses the average of
  opening and closing known inventory cost rather than mislabeled closing cost;
- user decisions survive re-sync;
- buyer/contact PII is not persisted in raw Ozon payload mirrors.

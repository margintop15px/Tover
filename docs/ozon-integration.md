# Ozon Marketplace Integration

This document describes the Ozon Seller API integration in Tover: how a seller
connects an account, which Ozon endpoints are used, which data is mirrored, how
read-only marketplace evidence is transformed into Tover operation candidates,
and why some Ozon data remains reporting-only.

Research basis:

- Ozon Seller API reference: https://docs.ozon.ru/api/seller/en/
- Global Ozon API intro: https://docs.ozon.ru/global/en/api/intro/?country=other
- Ozon docs note that `POST /v3/finance/transaction/list` is deprecated and is
  scheduled to be disabled on 2026-07-06. The integration uses finance accrual
  endpoints instead.
- Ozon FBS listing moved to `POST /v4/posting/fbs/list`; the older
  `POST /v3/posting/fbs/list` is intentionally not allowlisted.

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
- `src/lib/ozon/sync.ts`: sync orchestration, pagination, mirror upserts,
  candidate generation, raw payload sanitization.
- `src/lib/ozon/candidates.ts`: candidate normalization, validation, mapping,
  local product/warehouse creation, and `processOperation` request building.
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

## Data Model

Core tables:

- `marketplace_connections`: one Ozon connection per workspace, encrypted
  credentials, health, last sync status, and last sync metadata.
- `marketplace_sync_runs`: every sync run, date window, status, summary, and
  error.
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

The allowlist contains only read-only Seller API paths.

Catalog and stock:

- `POST /v3/product/list`
- `POST /v3/product/info/list`
- `POST /v4/product/info/attributes`
- `POST /v4/product/info/stocks`
- `POST /v5/product/info/prices`
- `POST /v1/product/info/warehouse/stocks`
- `POST /v1/product/info/discounted`

Warehouses:

- `POST /v2/warehouse/list`

Postings:

- `POST /v4/posting/fbs/list`
- `POST /v3/posting/fbs/get`
- `POST /v3/posting/fbo/list`
- `POST /v2/posting/fbo/get`

Returns:

- `POST /v1/returns/list`
- `POST /v2/returns/rfbs/list`
- `POST /v2/returns/rfbs/get`

Finance, legal-entity, and reports:

- `POST /v1/finance/accrual/postings`
- `POST /v1/finance/accrual/types`
- `POST /v1/finance/accrual/by-day`
- `POST /v2/finance/realization`
- `POST /v1/finance/realization/posting`
- `POST /v1/finance/document-b2b-sales`
- `POST /v1/finance/document-b2b-sales/json`
- `POST /v1/finance/cash-flow-statement/list`
- `POST /v1/finance/mutual-settlement`
- `POST /v1/finance/products/buyout`
- `POST /v1/finance/compensation`
- `POST /v1/finance/decompensation`
- `POST /v1/posting/unpaid-legal/product/list`
- `POST /v1/report/postings/create`
- `POST /v1/report/products/create`
- `POST /v2/report/returns/create`
- `POST /v1/report/discounted/create`
- `POST /v1/report/info`
- `POST /v1/report/list`

Movement and analytics:

- `POST /v1/removal/from-stock/list`
- `POST /v1/removal/from-supply/list`
- `POST /v3/supply-order/list`
- `POST /v3/supply-order/get`
- `POST /v1/supply-order/bundle`
- `POST /v1/analytics/stocks`
- `POST /v1/analytics/turnover/stocks`

Taxonomy/enrichment:

- `POST /v1/description-category/tree`
- `POST /v1/description-category/attribute`
- `POST /v1/description-category/attribute/values`
- `POST /v1/description-category/attribute/values/search`

Intentionally not allowlisted:

- `POST /v3/posting/fbs/list` because the integration uses v4.
- `POST /v3/finance/transaction/list` because Ozon marks it deprecated and
  scheduled for shutdown on 2026-07-06.
- Any Ozon endpoint that changes price, stock, product content, shipment state,
  cancellation state, labels, chat, promotions, or campaigns.

## Sync Flow

`POST /api/integrations/ozon/sync` creates a `marketplace_sync_runs` row and runs
independent steps. A failed step records an error but does not necessarily abort
the whole sync. The connection health stores the last sync summary.

The default date window is the last 30 days. The caller can pass `dateFrom` and
`dateTo` for backfills.

### 1. Warehouses

Endpoint: `POST /v2/warehouse/list`.

Tover stores Ozon warehouse ID, name, fulfillment schema/status, sanitized raw
payload, and a local warehouse mapping.

Auto-mapping uses local warehouse name. Existing manual/ignored mappings are
preserved.

### 2. Products

Endpoints:

- `POST /v3/product/list`
- `POST /v3/product/info/list`
- `POST /v4/product/info/attributes`
- `POST /v5/product/info/prices`

Tover stores Ozon product ID, `offer_id`, SKU, name, barcodes, images, status,
visibility, category/type identifiers, prices, attributes, sanitized raw payload,
and a local product mapping.

Auto-mapping uses local `products.sku_code` against Ozon `offer_id`, Ozon SKU,
or barcode. Manual/ignored mappings are preserved.

### 3. Stock Snapshots

Endpoint: `POST /v4/product/info/stocks`.

Tover inserts point-in-time rows into `ozon_stock_snapshots` with present,
reserved, warehouse, fulfillment schema, local product mapping, and local
warehouse mapping. Snapshots are mirrors, not operations.

### 4. Postings

Endpoints:

- `POST /v4/posting/fbs/list`
- `POST /v3/posting/fbo/list`

Delivered FBS/FBO postings create `sale` candidates. Each Ozon posting becomes
one Tover sale candidate and may contain multiple line items.

Canceled postings create ignored audit candidates with a warning. Intermediate
posting states remain mirrored only.

### 5. Returns

Endpoints:

- `POST /v1/returns/list`
- `POST /v2/returns/rfbs/list`
- `POST /v2/returns/rfbs/get`

Final returned/accepted/received/done return states create `return` candidates
with inbound direction. Other return states remain mirrored.

### 6. Finance Accruals

Endpoints:

- `POST /v1/finance/accrual/types`
- `POST /v1/finance/accrual/by-day`

Tover stores accrual rows, posting references, amounts, currencies, fee items,
services, and sanitized raw payloads in `ozon_finance_transactions`.

These rows power marketplace profitability and fee analytics. They do not create
Tover `payment` operations because Tover payments currently model supplier
payments, not marketplace settlements or payouts.

### 7. Legal-Entity Sales

Endpoint: `POST /v1/finance/document-b2b-sales/json`.

Tover stores invoice/report identifiers, invoice date, posting number, company
identifiers, amount, product rows, sanitized raw payload, and operation candidate
link.

Legal-entity rows enrich sale analytics. They create fallback `sale` candidates
only when there is no posting candidate for the same posting number. This avoids
double-counting B2B sales that already arrived as normal Ozon postings.

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

Buyout reports are seller-side sale evidence, not merchant purchases. They remain
reporting data.

### 9. Removals and Disposal

Endpoints:

- `POST /v1/removal/from-stock/list`
- `POST /v1/removal/from-supply/list`

Tover mirrors removals in `ozon_removals`. Rows generate `write_off` candidates
only when the reason/status explicitly indicates disposal, utilization, write-off,
loss, or lost stock. Seller-return/removal rows that do not prove disposal remain
mirror-only.

### 10. FBO Supplies

Endpoints:

- `POST /v3/supply-order/list`
- `POST /v3/supply-order/get`
- `POST /v1/supply-order/bundle`

Completed/accepted/done/supplied/closed/received supply orders create `transfer`
candidates when bundle items are known. Tover transfer operations support one
product per operation, so a multi-product Ozon supply becomes one transfer
candidate per product line.

The Ozon destination warehouse can auto-map. The local source warehouse is not
known from Ozon and must be selected by the user before commit.

### 11. Stock Analytics and Turnover

Endpoints:

- `POST /v1/analytics/stocks`
- `POST /v1/analytics/turnover/stocks`

Tover mirrors marketplace stock and turnover analytics. These rows are
reporting/reconciliation evidence only in the current implementation. They do
not generate `inventory_adjustment` candidates because daily stock deltas can
repeat and over-adjust local inventory without a dedicated reconciliation
workflow.

### 12. Discounted or Damaged Products

Endpoint: `POST /v1/product/info/discounted`.

Tover mirrors discounted products. A `defect` candidate is generated only when
reason/status explicitly proves physical defect or damage. Generic markdowns or
discounts remain mirror-only.

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
| `sale` | Generated from delivered FBS/FBO postings. Legal-entity sales can create fallback sale candidates only when no posting candidate exists. |
| `return` | Generated from final Ozon returns and final rFBS return requests that prove inventory returned. |
| `write_off` | Generated from removal/disposal rows only when the reason clearly proves disposal, write-off, utilization, or loss. |
| `transfer` | Generated from completed FBO supply orders, one candidate per product line, after user maps the unknown source warehouse. |
| `inventory_adjustment` | No Ozon candidate in the current implementation. Ozon stock analytics is mirrored for reconciliation/reporting only. |
| `defect` | Generated only from discounted/removal evidence that explicitly proves physical defect or damage. |
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
7. Tover generates candidates only for supported operation evidence.
8. Operations > Marketplaces shows mirror counts and sync health.
9. Operations shows Review candidates when Ozon has pending ready,
   needs-mapping, or approved candidates.
10. Manager opens `/operations/marketplace/ozon`.
11. Manager filters by status, operation type, source type, evidence, date, or
    mapping state.
12. Manager reviews candidate details and all line items.
13. Manager maps existing products/warehouses or creates missing local records.
14. Manager approves valid candidates.
15. Manager commits approved candidates.
16. Tover calls existing `processOperation`, marks candidates committed, stores
    `created_operation_id`, and shows the created operations in the normal
    Operations list.

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

Commit is partial-safe:

- already committed candidates are skipped;
- failed candidates remain approved when failure happens before a commit claim;
- candidates with an active/unresolved claim stay `committing` with a visible
  recovery error;
- commit errors are visible in `validation_errors`;
- successful candidates become committed and store the created operation ID;
- `marketplace_operation_commit_claims` stores the candidate/source claim and
  operation link to prevent double-click or concurrent commit duplicates.

The claim guard prioritizes avoiding duplicate inventory operations. Rare crash
or link-failure cases may require manual recovery instead of automatic retry.

## Current Simplifications and Follow-ups

- Candidate mapping-state and evidence filters partly evaluate normalized JSON in
  memory. This is acceptable for MVP review volumes but should become SQL JSON
  predicates or stored fields for large sellers.
- Finance/legal/reporting data is mirrored and counted in Settings. Rich
  reporting views for settlements, legal-entity sales, unpaid legal products,
  removals, supplies, and reconciliation gaps should be added outside the commit
  flow.
- Ozon taxonomy endpoints are allowlisted for category and attribute enrichment,
  but taxonomy sync is not required for candidate commit.
- Source warehouse mapping for supply transfers is candidate-level user input.
  Once a better local default or mapping model exists, this can become reusable.
- Async report CSV parsing is represented by report-run metadata and parsed rows
  where the endpoint returns JSON. File download/parsing can be added for reports
  that only return a file URL.

## Test Coverage

The Ozon tests use `OZON_API_BASE_URL` to point Next.js at a local mock Seller API.

Covered by validation tests:

- sale, return, write-off, transfer, defect, and inventory-adjustment validation;
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
- partial sync errors surface as `completed_with_errors`;
- sale, return, write-off, transfer, and defect operations become normal Tover
  operations;
- user decisions survive re-sync;
- buyer/contact PII is not persisted in raw Ozon payload mirrors.

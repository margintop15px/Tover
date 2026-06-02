# Ozon Postponed Work Plan

This plan captures Ozon work intentionally deferred after the commit-hardening
pass. The current priority is safe read-only sync, manual review, and guarded
local operation commit. The items below should be handled after the core flow is
stable in a migrated test database.

## 1. Split `src/lib/ozon/sync.ts`

Current state: one large sync module owns endpoint calls, normalization, mirror
upserts, candidate generation, sanitization, and sync-run orchestration.

Target structure:

- `src/lib/ozon/sync/catalog.ts`: products, attributes, prices, product stocks.
- `src/lib/ozon/sync/orders.ts`: FBS/FBO postings and posting candidates.
- `src/lib/ozon/sync/returns.ts`: FBO/FBS/rFBS return mirrors and candidates.
- `src/lib/ozon/sync/finance.ts`: accruals, B2B/legal rows, unpaid legal rows.
- `src/lib/ozon/sync/reports.ts`: report code creation, report info polling, parsed report rows.
- `src/lib/ozon/sync/movements.ts`: removals, supplies, write-off and transfer candidates.
- `src/lib/ozon/sync/analytics.ts`: stock and turnover analytics mirrors.
- `src/lib/ozon/sync/candidate-builders.ts`: pure candidate construction helpers.
- `src/lib/ozon/sync/index.ts`: orchestration, run status, and shared step summary.

Sequencing:

1. Extract pure helpers first without behavior changes.
2. Move one domain at a time and keep public `syncOzonConnection` unchanged.
3. Run validation specs after each extraction.
4. Only after all domains are split, consider per-domain capability flags or step-level toggles.

Acceptance criteria:

- No endpoint call sequence changes except where tests explicitly cover them.
- `syncOzonConnection` response shape remains unchanged.
- Existing authenticated Ozon mock tests pass.
- Each new module has narrow imports and no circular dependencies.

## 2. SQL-Backed Candidate Filters

Current state: status, source, operation type, and date filters run in SQL.
Mapping-state and evidence-support filters load candidate rows and inspect JSON in
memory. This is acceptable for MVP accounts but will not scale for large seller
workspaces.

Target model:

- Add stored columns on `marketplace_operation_candidates`:
  - `mapping_state TEXT CHECK (mapping_state IN ('mapped', 'missing'))`
  - `support_status TEXT CHECK (support_status IN ('commit_candidate', 'reporting_only', 'blocked'))`
  - `item_count INTEGER`
  - optionally `first_product_name`, `first_warehouse_name` for fast list display.
- Maintain those columns during sync, mapping updates, create-product,
  create-warehouse, ignore/unignore, approve, and commit paths.
- Index `(workspace_id, provider, status, mapping_state, support_status, operation_date)`.

Sequencing:

1. Add columns as nullable and backfill from `normalized_operation`.
2. Update candidate writers to populate them.
3. Change list API filters to SQL predicates.
4. Remove in-memory post-filtering after backfill is verified.

Acceptance criteria:

- Candidate list API returns correct counts and pages with mapping/support filters.
- Pagination totals match filtered data.
- Large candidate fixtures do not require loading all rows to return one page.

## 3. Finance And Legal Reporting Views

Current state: finance, legal-entity, cash-flow, settlement, compensation,
decompensation, buyout, and unpaid legal rows are mirrored and counted in
Settings. They do not create Tover `payment` or `purchase` operations.

Target views:

- Marketplace finance dashboard:
  - accruals by period, posting, operation type, and service type;
  - commissions, delivery, refunds, compensations, decompensations;
  - gross sales, marketplace fees, and net marketplace result.
- Legal-entity sales view:
  - invoices/register rows with company identifiers, posting links, amount,
    products, and payment/unpaid state;
  - no personal contacts, names, phones, emails, or addresses.
- Settlement/cash-flow view:
  - cash-flow statements, mutual settlements, buyout reports, and report codes;
  - reconciliation gaps between postings, accruals, and legal reports.

Sequencing:

1. Add read-only APIs for each mirror/report table with date filters.
2. Add one consolidated marketplace finance page under Reports.
3. Link rows back to Ozon postings/products where posting number, offer ID, SKU,
   or Ozon SKU is known.
4. Add export only after the table views are stable.

Acceptance criteria:

- Finance/legal data is inspectable without creating local operations.
- Buyer PII is absent from API responses and UI.
- Users can filter by period, report type, posting number, product, and source.

## 4. Supply Transfer Candidate Improvements

Current state: FBO supplies can generate transfer candidates, but Ozon often
does not provide a local source warehouse. The user maps the source warehouse per
candidate. Supply list pagination/date-window behavior is still minimal.

Target behavior:

- Use real date windows and pagination/cursors for supply order list endpoints.
- Persist durable source-warehouse overrides by Ozon supply source evidence, not
  only inside one candidate's normalized operation.
- Add a reusable mapping/default model for common source warehouse choices.
- Require stronger completion evidence before generating transfer candidates.

Sequencing:

1. Improve supply list pagination and date-window request shape.
2. Add source override storage keyed by connection and source identity.
3. Reapply source override during re-sync before validation.
4. Add UI affordance for "use this source warehouse for similar supplies".

Acceptance criteria:

- Re-sync does not erase a user's source warehouse choice.
- Multi-product supplies still become one transfer candidate per product line.
- Transfer commit remains blocked until source and destination warehouses are known.

## 5. Defect Candidate Improvements

Current state: discounted/damaged product data can generate `defect` candidates
only when reason/status explicitly indicates physical defect or damage. Event
dates can be weak when Ozon does not provide a clear date.

Target behavior:

- Prefer Ozon event/update date over sync date.
- Store an evidence confidence and reason code for each defect candidate.
- Keep generic discounts/markdowns mirror-only.
- Add clearer UI copy explaining why a row is commit-eligible or reporting-only.

Sequencing:

1. Audit actual Ozon discounted product payload variants from real accounts.
2. Add explicit damage/defect reason mapping table.
3. Update candidate builder to use event/update date when present.
4. Add tests for generic markdown, damaged, defective, and ambiguous payloads.

Acceptance criteria:

- Defect candidates only appear for physical-damage evidence.
- Unknown or generic markdown reasons do not create candidates.
- Operation dates are source-derived whenever possible.

## 6. Stock Reconciliation Workflow

Current state: Ozon stock analytics and turnover analytics are mirrored only.
They do not create `inventory_adjustment` candidates because daily deltas can
repeat and over-adjust inventory.

Target behavior:

- Dedicated reconciliation view comparing Ozon stock, local balances, reserved
  stock, turnover, and recent operations.
- User selects a reconciliation period and explicitly creates one adjustment
  proposal per product/warehouse after reviewing the discrepancy.
- Candidate identity should be stable by product/warehouse/reconciliation period,
  not by daily snapshot alone.

Sequencing:

1. Build read-only reconciliation table from mirrors and local balances.
2. Add discrepancy grouping and explanatory evidence.
3. Add "create adjustment candidate" as an explicit local action.
4. Reuse the existing candidate review/approve/commit flow.

Acceptance criteria:

- Repeated syncs do not create repeated adjustment candidates for the same gap.
- Adjustment candidate quantity is explainable from a chosen reconciliation period.
- User can see local balance, Ozon stock, and resulting adjustment before approval.

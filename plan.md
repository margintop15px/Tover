# Minimal, meaningful prototype for a marketplace turnover tracking app with Supabase and Next.js

## Prototype goal and “within days” scope boundary

A prototype that delivers immediate value in days should answer two questions reliably, with drill-down that builds user trust:

- **“How much did we sell?”** (GMV and sales volume) with a clickable list of the underlying orders/lines.
- **“How healthy is our stock?”** (current stock value and/or stock risk) with a readable list of critical SKUs.

This aligns with the practical UI logic in your draft spec: a *top-down* dashboard with *drill-down*, plus a *critical stock* widget based on sales tempo and an *import tile* for Excel/CSV. fileciteturn0file0

Given you already have **Supabase + PostgreSQL** and a **Next.js app with file upload**, the fastest meaningful prototype is:

- **Data model**: 4 canonical tables (`orders`, `order_lines`, `inventory_snapshots`, `payments`) plus 2 tiny ingestion tables (`imports`, `import_errors`) to make CSV ingestion debuggable.
- **Ingestion**: upload CSV → create “import run” record → parse/validate server-side → bulk upsert into canonical tables → record rejected rows.
- **Metrics**: compute **GMV** + **sales volume** (card KPIs) and **stock value / critical stock** (one more KPI or drill-down list).
- **UI**: 2–3 KPI cards + 1 drill-down table + a CSV import panel.

This plan intentionally avoids “heavy BI” features (alerting, forecasting, realtime streaming) until you have trustworthy canonical data. Supabase’s own guidance emphasises choosing import methods based on size and requirements, and highlights “Supabase API” imports as programmatic but cautions against very large bulk imports via API. citeturn5view2

## Minimal database schema design for Supabase/PostgreSQL

### Design principles for a 4-table core

To keep the schema small yet usable:

- Model **orders** at the header level (per order), and **order_lines** at the item level (per SKU per order). This is the minimum grain required for GMV, AOV, product/channel breakdown, and drill-down lists.
- Model **inventory_snapshots** as a *periodic snapshot fact* (e.g., per day per SKU), because it makes stock value cards and “critical stock in N days” feasible without building a full warehouse/WMS movement ledger.
- Model **payments** to allow reconciliation later (paid vs unpaid, refunds, fees) and to separate “order created” from “cash settled”—a common marketplace reality.

If you later need data isolation per client/team, PostgreSQL row-level security (RLS) is a native feature and is also a core Supabase pattern. citeturn0search11turn2search2

### Canonical tables and relationships

The table below lists the minimal set of fields that unlock your first dashboard.

| Table | Purpose | Key fields (minimal) | Relationships / constraints |
|---|---|---|---|
| `orders` | Order header for drill-down and dedupe | `id uuid PK`, `workspace_id uuid`, `source text`, `external_order_id text`, `ordered_at timestamptz`, `currency char(3)`, `status text` | `UNIQUE(workspace_id, source, external_order_id)` for dedupe |
| `order_lines` | Item-level sales facts (GMV, volume) | `id uuid PK`, `order_id uuid FK`, `sku text`, `quantity integer`, `unit_price_gross numeric(12,2)`, `discount_amount numeric(12,2)`, `tax_amount numeric(12,2)` | `INDEX(order_id)`, optionally `UNIQUE(order_id, sku, line_external_id)` |
| `inventory_snapshots` | Stock on hand at a point in time | `id uuid PK`, `workspace_id uuid`, `snapshot_date date`, `sku text`, `on_hand_qty numeric(14,3)`, `unit_cost numeric(12,4)` | `UNIQUE(workspace_id, snapshot_date, sku)` |
| `payments` | Payment lifecycle events and netting | `id uuid PK`, `workspace_id uuid`, `source text`, `external_payment_id text`, `order_id uuid NULL`, `amount numeric(12,2)`, `fee_amount numeric(12,2)`, `currency char(3)`, `paid_at timestamptz`, `status text` | `UNIQUE(workspace_id, source, external_payment_id)` |
| `imports` | Track each CSV import run | `id uuid PK`, `workspace_id uuid`, `file_path text`, `import_type text`, `status text`, `created_at timestamptz`, `summary jsonb` | `summary jsonb` recommended for structured metadata. citeturn3search21 |
| `import_errors` | Store row-level failures | `id uuid PK`, `import_id uuid FK`, `row_number int`, `error_code text`, `error_detail text`, `raw_row jsonb` | Allows showing “these 17 rows failed and why” |

Using `jsonb` for `imports.summary` and `import_errors.raw_row` matches Supabase’s guidance that `jsonb` is recommended for most JSON use cases in Postgres. citeturn3search21

### Minimal DDL you can paste into Supabase

```sql
-- Enable UUID generation (Supabase typically has this, but include for completeness)
create extension if not exists "pgcrypto";

create table if not exists public.orders (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null,
  source text not null,                         -- e.g., 'shopify', 'amazon', 'manual_csv'
  external_order_id text not null,              -- order id in source system
  ordered_at timestamptz not null,
  currency char(3) not null,
  status text not null default 'created',       -- keep as text for speed; can become an enum later
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id, source, external_order_id)
);

create index if not exists idx_orders_workspace_date
  on public.orders (workspace_id, ordered_at desc);

create table if not exists public.order_lines (
  id uuid primary key default gen_random_uuid(),
  order_id uuid not null references public.orders(id) on delete cascade,
  sku text not null,
  quantity integer not null check (quantity > 0),
  unit_price_gross numeric(12,2) not null check (unit_price_gross >= 0),
  discount_amount numeric(12,2) not null default 0 check (discount_amount >= 0),
  tax_amount numeric(12,2) not null default 0 check (tax_amount >= 0),
  created_at timestamptz not null default now()
);

create index if not exists idx_order_lines_order_id
  on public.order_lines (order_id);

create index if not exists idx_order_lines_sku
  on public.order_lines (sku);

create table if not exists public.inventory_snapshots (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null,
  snapshot_date date not null,
  sku text not null,
  on_hand_qty numeric(14,3) not null check (on_hand_qty >= 0),
  unit_cost numeric(12,4) not null check (unit_cost >= 0),
  created_at timestamptz not null default now(),
  unique (workspace_id, snapshot_date, sku)
);

create index if not exists idx_inventory_snapshots_latest
  on public.inventory_snapshots (workspace_id, sku, snapshot_date desc);

create table if not exists public.payments (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null,
  source text not null,                          -- 'stripe', 'paypal', 'manual_csv'
  external_payment_id text not null,
  order_id uuid null references public.orders(id) on delete set null,
  amount numeric(12,2) not null,
  fee_amount numeric(12,2) not null default 0,
  currency char(3) not null,
  paid_at timestamptz null,
  status text not null default 'pending',
  created_at timestamptz not null default now(),
  unique (workspace_id, source, external_payment_id)
);

create index if not exists idx_payments_workspace_paid_at
  on public.payments (workspace_id, paid_at desc);

create table if not exists public.imports (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null,
  file_path text not null,                       -- Supabase Storage path or external URL
  import_type text not null,                     -- 'orders_csv' | 'inventory_csv' | 'payments_csv'
  status text not null default 'uploaded',       -- 'uploaded'|'processing'|'completed'|'failed'
  summary jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  completed_at timestamptz null
);

create table if not exists public.import_errors (
  id uuid primary key default gen_random_uuid(),
  import_id uuid not null references public.imports(id) on delete cascade,
  row_number integer not null,
  error_code text not null,
  error_detail text not null,
  raw_row jsonb not null,
  created_at timestamptz not null default now()
);
```

The dedupe behaviour in this prototype hinges on **unique constraints** plus **UPSERT** (`INSERT ... ON CONFLICT DO UPDATE`), which PostgreSQL defines as an atomic “insert or update” outcome even under concurrency. citeturn2search0

### Comparison: two minimal schema options

| Option | What you store | Pros in a “days” prototype | Cons / when it breaks |
|---|---|---|---|
| Normalised core (recommended) | `orders` + `order_lines` + `inventory_snapshots` + `payments` | Clean drill-down; supports partial re-import; aligns with future analytics; dedupe is straightforward | Slightly more tables |
| Single “fact_sales” table only | One table with order + line fields merged | Fastest to ship; simplest queries | Harder to dedupe correctly; harder to evolve (payments + inventory don’t fit cleanly); drill-down becomes messy |

The normalised core is still small, but it preserves correct grains, which is crucial for turnover metrics and reconciliation later.

## Data ingestion and ETL from CSV via Next.js upload

### The simplest robust ingestion pattern

Because you already have file upload capability, the fastest reliable pattern is:

1. **Upload file** (client → Next.js route handler) using `multipart/form-data`.
2. **Persist file** in Supabase Storage (optional but recommended for audit and reprocessing).
3. Create an `imports` row (`status = 'processing'`).
4. Parse CSV server-side, validate, and upsert into canonical tables in batches.
5. Save row-level failures into `import_errors`.
6. Mark `imports.status = 'completed'` with a JSON summary.

Next.js route handlers are designed for custom request handlers using the Web `Request`/`Response` APIs. citeturn6view0turn6view1  
The Web `Request.formData()` method reads the request body and returns a `FormData` object, which is the core of handling file uploads in this approach. citeturn4search0

### Storage and key handling in Supabase

Two important Supabase security points influence how you upload/import:

- Supabase distinguishes publishable/anon keys vs elevated secret/service keys; elevated keys provide full access and **bypass row-level security**, so they must only be used server-side. citeturn5view0  
- Supabase Storage uploads are controlled by RLS policies on `storage.objects`; for uploads you generally need an `INSERT` policy, and for overwrite (“upsert”) you also need `SELECT` and `UPDATE`. citeturn5view1turn4search3  

For a days-long prototype, choose one of these two paths:

- **Fastest**: upload file from the Next.js server to Storage using a server-side Supabase client with an elevated key (safe *if kept on the server*). citeturn5view0  
- **More product-like**: server generates a signed upload URL and client uploads directly; Supabase documents signed upload URLs as valid for 2 hours and usable “without further authentication,” subject to storage RLS policies. citeturn5view3turn5view1  

### Validation, deduplication, and error handling rules

A minimal ingestion spec that avoids silent data corruption:

- **Header validation**: reject the file if required columns are not present.
- **Type validation**: parse numbers strictly; parse dates to ISO; enforce currency as 3 letters.
- **Row validation** (examples):
  - `quantity > 0`
  - `unit_price_gross >= 0`
  - `snapshot_date` must parse to a date
- **Deduplication strategy**:
  - Orders: `(workspace_id, source, external_order_id)` unique constraint
  - Payments: `(workspace_id, source, external_payment_id)` unique constraint
  - Inventory snapshots: `(workspace_id, snapshot_date, sku)` unique constraint
  - Lines: either “delete and reinsert lines for the order” or a line-level unique key; for MVP, “delete and reinsert by order” is often simplest and safest.
- **Error capture**:
  - Store rejected rows in `import_errors` with `raw_row jsonb` and a human-readable message; `jsonb` is recommended for JSON storage and query. citeturn3search21  

### Minimal ETL implementation sketch

**Server-side CSV import in batches** (outline):

- Parse CSV rows.
- Group rows by `external_order_id`.
- Upsert `orders`, then insert `order_lines`.
- Use Postgres `ON CONFLICT` logic for upserts; Postgres documents `ON CONFLICT DO UPDATE` as the UPSERT mechanism. citeturn2search0  

If your CSVs are large, the fastest bulk-loading method in PostgreSQL is `COPY`. PostgreSQL defines `COPY FROM` as loading data from a file into a table, but it requires server-side file access; with Supabase-managed Postgres you typically won’t have filesystem access, so the “parse in app and insert/upsert” approach is fine for a small prototype. citeturn2search1turn5view2

## Analytical SQL queries for two dashboard cards and drill-down

### Metric definitions for the prototype

- **GMV**: sum of items sold at gross price (before fees), computed from order lines. GMV is a standard marketplace metric used to describe total value flowing through the marketplace. citeturn0search3  
- **Sales volume**: total units sold, computed from order lines.
- **Stock value** (optional third KPI): sum of `(on_hand_qty × unit_cost)` from the latest inventory snapshot date.
- **Inventory turnover** (optional, simplified): for MVP, compute a practical proxy: `units_sold_in_period / average_on_hand_units_in_period`. A full accounting-grade turnover ratio usually uses COGS / average inventory value, but that requires reliable cost/COGS inputs. citeturn0search3turn0search9

### SQL for KPI card: GMV + sales volume over a date range

```sql
-- Parameters:
--   :workspace_id uuid
--   :from_ts timestamptz
--   :to_ts timestamptz

select
  sum(ol.quantity * ol.unit_price_gross) as gmv_gross,
  sum(ol.quantity)::bigint as units_sold,
  count(distinct o.id)::bigint as orders_count
from public.orders o
join public.order_lines ol on ol.order_id = o.id
where
  o.workspace_id = :workspace_id
  and o.ordered_at >= :from_ts
  and o.ordered_at <  :to_ts
  and o.status not in ('cancelled');  -- adjust to your source semantics
```

This query is intentionally “line-driven” so that it remains correct even if order header totals are missing or inconsistent across CSV sources.

### SQL for KPI card: current stock value (latest snapshot)

```sql
-- Parameters:
--   :workspace_id uuid

with latest as (
  select max(snapshot_date) as snapshot_date
  from public.inventory_snapshots
  where workspace_id = :workspace_id
)
select
  l.snapshot_date,
  sum(s.on_hand_qty * s.unit_cost) as stock_value_cost,
  sum(s.on_hand_qty) as total_units_on_hand
from latest l
join public.inventory_snapshots s
  on s.workspace_id = :workspace_id
 and s.snapshot_date = l.snapshot_date
group by l.snapshot_date;
```

This aligns with your spec’s “top metric cards” concept that includes “stock value (cost price)” as a primary card. fileciteturn0file0

### Drill-down query: list orders behind GMV card (paginated)

```sql
-- Parameters:
--   :workspace_id uuid
--   :from_ts timestamptz
--   :to_ts timestamptz
--   :limit int
--   :offset int

select
  o.id,
  o.source,
  o.external_order_id,
  o.ordered_at,
  o.currency,
  o.status,
  sum(ol.quantity * ol.unit_price_gross) as order_gmv,
  sum(ol.quantity)::bigint as order_units
from public.orders o
join public.order_lines ol on ol.order_id = o.id
where
  o.workspace_id = :workspace_id
  and o.ordered_at >= :from_ts
  and o.ordered_at <  :to_ts
group by o.id
order by o.ordered_at desc
limit :limit offset :offset;
```

### Drill-down query: list order lines behind an order

```sql
-- Parameters:
--   :order_id uuid

select
  ol.id,
  ol.sku,
  ol.quantity,
  ol.unit_price_gross,
  ol.discount_amount,
  ol.tax_amount,
  (ol.quantity * ol.unit_price_gross) as line_gmv
from public.order_lines ol
where ol.order_id = :order_id
order by ol.sku;
```

### “Critical stock in N days” list (high-value widget)

Your spec explicitly calls for a widget listing SKUs that will run out in the next **N days** based on sales velocity. fileciteturn0file0  
A minimal/credible implementation uses:

- Latest inventory snapshot per SKU
- Average daily units sold over the last 7 (or 14) days

```sql
-- Parameters:
--   :workspace_id uuid
--   :lookback_days int        -- e.g., 7
--   :n_days numeric          -- e.g., 14

with latest_snapshot as (
  select
    sku,
    max(snapshot_date) as snapshot_date
  from public.inventory_snapshots
  where workspace_id = :workspace_id
  group by sku
),
stock as (
  select s.sku, s.on_hand_qty
  from public.inventory_snapshots s
  join latest_snapshot ls
    on ls.sku = s.sku
   and ls.snapshot_date = s.snapshot_date
  where s.workspace_id = :workspace_id
),
sales_daily as (
  select
    ol.sku,
    date_trunc('day', o.ordered_at)::date as d,
    sum(ol.quantity) as units_sold
  from public.orders o
  join public.order_lines ol on ol.order_id = o.id
  where
    o.workspace_id = :workspace_id
    and o.ordered_at >= (now() - (:lookback_days || ' days')::interval)
    and o.status not in ('cancelled')
  group by ol.sku, date_trunc('day', o.ordered_at)
),
sales_velocity as (
  select
    sku,
    (sum(units_sold) / greatest(:lookback_days, 1))::numeric as avg_units_per_day
  from sales_daily
  group by sku
)
select
  st.sku,
  st.on_hand_qty,
  sv.avg_units_per_day,
  case
    when sv.avg_units_per_day <= 0 then null
    else (st.on_hand_qty / sv.avg_units_per_day)
  end as days_remaining
from stock st
left join sales_velocity sv on sv.sku = st.sku
where
  sv.avg_units_per_day > 0
  and (st.on_hand_qty / sv.avg_units_per_day) <= :n_days
order by days_remaining asc
limit 50;
```

This yields a short, actionable list—perfect for a prototype dashboard.

### Query approach comparison: on-the-fly vs materialised summary

| Approach | How it works | When it’s best | Notes |
|---|---|---|---|
| On-the-fly aggregation (MVP default) | Run `SUM()` queries directly on `orders` + `order_lines` | Small datasets, low complexity, fastest to build | Add indexes on `(workspace_id, ordered_at)` and `order_id` for acceptable performance |
| Materialised view (next step) | Precompute daily metrics into a materialised view, refresh on schedule | Growing data volume, slower dashboards | PostgreSQL supports `CREATE MATERIALIZED VIEW` and refreshing it later. citeturn0search15turn0search2 |

If you do add materialised views, PostgreSQL documents `REFRESH MATERIALIZED VIEW CONCURRENTLY` for refreshing without blocking concurrent reads (with specific requirements). citeturn0search2

## Next.js API design for metrics and drill-down

### Route handler structure and runtime notes

For a prototype, **REST-style JSON endpoints** are simplest. Next.js route handlers:

- Live in `app/.../route.ts`.
- Support common HTTP methods (GET/POST/etc.).
- Use the Web `Request` and `Response` APIs. citeturn6view0turn6view1

Because dashboard metrics must always reflect the latest imports, treat these endpoints as **dynamic** (avoid cached GET behaviour unless you explicitly set revalidation). The Next.js docs note GET route handlers can be cached by default and describe how to opt out. citeturn6view0

### Recommended minimal endpoints

**Metrics**
- `GET /api/metrics/summary?from=...&to=...&workspaceId=...`
  - Returns GMV, units_sold, orders_count, last_inventory_snapshot_date, stock_value_cost.
- `GET /api/metrics/critical-stock?days=14&lookback=7&workspaceId=...`
  - Returns the critical SKU list.

**Drill-down**
- `GET /api/orders?from=...&to=...&limit=50&offset=0&workspaceId=...`
- `GET /api/orders/:id/lines`

**Ingestion**
- `POST /api/imports` (multipart/form-data: file + import_type)
- `GET /api/imports/:id` (status + summary)
- `GET /api/imports/:id/errors?limit=...&offset=...`

This maps cleanly to your “top metrics → click → list” requirement. fileciteturn0file0

### Supabase client usage in Next.js (fast, current best practice)

Supabase’s current docs recommend using the `@supabase/ssr` package for SSR patterns and provide migration guidance from older “auth helpers” packages. citeturn3search0turn3search1

For the prototype:

- Use a **server-side Supabase client** in route handlers to query/insert.
- Keep elevated keys server-only; Supabase documents that secret/service keys bypass RLS and should be used only in backend components. citeturn5view0

### Example JSON API responses

**`GET /api/metrics/summary`**

```json
{
  "workspaceId": "d9b2f18b-3caa-4c86-96b0-0c526ac3b5ad",
  "range": { "from": "2026-01-01T00:00:00Z", "to": "2026-02-02T00:00:00Z" },
  "kpis": {
    "gmvGross": 128934.50,
    "unitsSold": 4821,
    "ordersCount": 1337,
    "stockValueCost": 41250.77,
    "inventorySnapshotDate": "2026-02-01"
  },
  "meta": { "computedAt": "2026-02-02T10:12:33Z" }
}
```

**`GET /api/orders?limit=2&offset=0`**

```json
{
  "page": { "limit": 2, "offset": 0, "totalEstimate": 1337 },
  "items": [
    {
      "id": "8dfe4f4f-5a3a-4d2e-b357-4f3119c39f63",
      "source": "shopify",
      "externalOrderId": "1000451",
      "orderedAt": "2026-02-01T15:32:10Z",
      "currency": "EUR",
      "status": "paid",
      "orderGmv": 129.98,
      "orderUnits": 2
    },
    {
      "id": "c0f6d2bb-08b2-4b17-a2b3-8c8c9d0d5b20",
      "source": "amazon",
      "externalOrderId": "405-1234567-1234567",
      "orderedAt": "2026-02-01T14:10:00Z",
      "currency": "EUR",
      "status": "shipped",
      "orderGmv": 59.99,
      "orderUnits": 1
    }
  ]
}
```

### File upload endpoint behaviour (practical minimum)

The client submits a form with `FormData`. Next.js forms guidance shows submitting `FormData` to server endpoints and notes server-side endpoints can use sensitive environment variables without exposing them to the client. citeturn6view2  
On the server, the route handler reads the body via `request.formData()`. citeturn4search0turn6view0

To store uploads securely, Supabase Storage access is governed by RLS policies on `storage.objects`. citeturn5view1  
If you choose client-direct uploads, Supabase supports signed upload URLs and documents the required permissions. citeturn5view3turn5view1

## Minimal dashboard UI components that feel “real” immediately

### Minimum viable dashboard layout

A small dashboard that still feels like a “turnover cockpit”:

- **Header filters**: date range (from/to), optional “source/channel”.
- **KPI cards** (2 required, 3 ideal):
  - GMV (gross)
  - Units sold (or orders count)
  - Stock value (latest snapshot) or “Critical SKUs (count)”
- **Drill-down table** (click-through from GMV card):
  - Orders list with order GMV and units
- **Critical stock list** (optional but high impact):
  - SKU, on-hand, avg/day, days remaining
- **Import panel**:
  - Upload CSV + select import type + show last import status/errors

This matches your desired patterns: top cards, drill-down, proactive “critical stock”, and import tile. fileciteturn0file0

### Interaction design for the prototype

Keep interactions explicit:

- Clicking GMV card opens `/orders?from=...&to=...`
- Clicking a row opens `/orders/:id`
- “Critical stock” rows link to `/sku/:sku` (later) or to a filtered orders list.

The first meaningful “wow” is when a user can click GMV → see orders → click order → see lines, and the numbers reconcile.

### Suggested component set

A minimal but complete UI can be built with:

- `DateRangePicker` (or two date inputs)
- `KpiCard` (value + delta placeholder + “last updated”)
- `DataTable` (server-paginated)
- `UploadCard` (file input + import type dropdown + submit + status)
- `InlineErrorList` (top N import errors with download link)

You do not need a full drag-and-drop widget grid in the first days; you can add this after proving metric correctness. Your spec’s “widgetised workspace” can be a later enhancement. fileciteturn0file0

## Scalability and extensibility from this prototype

### How to extend without rewriting

This prototype becomes a platform if you add capabilities in this order:

1. **More metrics**: AOV, returns rate, take rate, payment fees; these are additive once your order_lines and payments are trustworthy (and you have clear definitions of marketplace metrics like GMV). citeturn0search3  
2. **More ingestion sources**:
   - Add CSV templates for each marketplace.
   - Later add API connectors (Shopify/Amazon/etc.) once canonical tables stabilise.
3. **Performance improvements**:
   - Add daily rollup tables or materialised views; Postgres documents materialised view creation and refresh. citeturn0search15turn0search2  
4. **Role-based access control and multi-workspace**:
   - Introduce RLS policies per `workspace_id`; Postgres and Supabase both document RLS concepts and policy attachment. citeturn0search11turn2search2  
5. **Alerts**:
   - Start with server-side scheduled checks (“critical stock”, “import failed”) and in-app notifications; then add email/SMS later.
6. **Realtime**:
   - Add event-driven ingestion routes and status updates when you need live dashboards (after data correctness is established).

### Key risks (and quick mitigations) in a “days” build

- **Incorrect dedupe** → users lose trust.
  - Mitigation: enforce unique constraints and use UPSERT for atomic dedupe. citeturn2search0  
- **Leaking elevated Supabase keys** → catastrophic.
  - Mitigation: keep secret/service keys strictly server-side; Supabase documents elevated keys bypass RLS. citeturn5view0  
- **Silent partial imports** → numbers don’t match.
  - Mitigation: store `imports` status + `import_errors` with raw rows (`jsonb`) so the UI can show what failed. citeturn3search21  
- **Storage upload blocked by RLS** → broken importer UX.
  - Mitigation: set minimal RLS policies on `storage.objects` (INSERT, and SELECT/UPDATE if overwriting). citeturn5view1turn4search3  

### A realistic “within days” implementation checklist

- **Day 1**: Create tables + indexes; build `/api/imports` that parses CSV and upserts orders/order_lines; show import status and errors.
- **Day 2**: Implement SQL KPIs (GMV, units sold) + orders drill-down endpoints + dashboard page with 2 cards and a table.
- **Day 3**: Add inventory snapshot import + stock value KPI + critical stock query/widget; small UX polish; basic workspace scoping.
- **Day 4–5 (buffer)**: Hardening (constraints, error messages, performance), and add simple filters (source/channel, SKU search).

This sequencing is optimised for producing a working prototype that users can interact with immediately, while keeping a clean upgrade path to richer marketplace analytics.

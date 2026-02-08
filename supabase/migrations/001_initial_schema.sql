-- Enable UUID generation
create extension if not exists "pgcrypto";

-- Orders: header-level order data
create table if not exists public.orders (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null,
  source text not null,
  external_order_id text not null,
  ordered_at timestamptz not null,
  currency char(3) not null,
  status text not null default 'created',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id, source, external_order_id)
);

create index if not exists idx_orders_workspace_date
  on public.orders (workspace_id, ordered_at desc);

-- Order lines: item-level sales facts
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

-- Inventory snapshots: stock on hand at a point in time
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

-- Payments: payment lifecycle events
create table if not exists public.payments (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null,
  source text not null,
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

-- Imports: track each CSV import run
create table if not exists public.imports (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null,
  file_path text not null,
  import_type text not null,
  status text not null default 'uploaded',
  summary jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  completed_at timestamptz null
);

-- Import errors: row-level failures
create table if not exists public.import_errors (
  id uuid primary key default gen_random_uuid(),
  import_id uuid not null references public.imports(id) on delete cascade,
  row_number integer not null,
  error_code text not null,
  error_detail text not null,
  raw_row jsonb not null,
  created_at timestamptz not null default now()
);

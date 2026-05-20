# Tover

Multi-tenant marketplace turnover tracker built with Next.js + Supabase.

## Features

- **Auth & Multi-tenancy** — email/password auth, organization creation at signup, role-based access (`owner`, `admin`, `member`), member invites
- **Inventory Management** — products, warehouses, suppliers, categories, stores
- **Operations Engine** — 8 operation types: purchase, sale, return, write-off, transfer, production, defect, payment
- **Weighted-Average Cost Tracking** — automatic cost recalculation on purchases and production
- **Product Balance Tracking** — real-time per-warehouse stock and cost balances
- **CSV Imports** — orders, order lines, inventory snapshots, payments
- **Reports** — Inventory Balances (current + historical), Product Movement, Supplier Debt (with drill-down)
- **Workspace Settings** — configurable currency, category/store requirement toggles with default backfill
- **Dashboard** — KPI summary and critical stock alerts
- **Column Visibility** — per-table column show/hide with localStorage persistence
- **Bilingual UI** — English and Russian with runtime locale switching

## Stack

- Next.js 16 (App Router)
- React 19 + TypeScript
- Supabase (Auth + Postgres + RLS)
- shadcn/ui (New York style) + Radix UI + Lucide icons
- Tailwind CSS 4
- Playwright (E2E)

## Prerequisites

- Node.js `>=20.9.0`
- npm
- Supabase project (URL, anon key, service role key)

## Quick Start

```bash
cp .env.local.example .env.local
# fill values in .env.local
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

## Environment Variables

Use `.env.local.example` as template.

```bash
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=your-anon-key
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key
NEXT_PUBLIC_SITE_URL=http://localhost:3000
# Optional E2E overrides:
# E2E_EMAIL=playwright@tover.local
# E2E_PASSWORD=Playwright-dev-password-1
```

Notes:
- `SUPABASE_SERVICE_ROLE_KEY` is required for server-side invite operations.
- Playwright authenticated tests auto-provision `playwright@tover.local` in local dev when `E2E_EMAIL`/`E2E_PASSWORD` are omitted and the Supabase service role key is present.
- Never expose `SUPABASE_SERVICE_ROLE_KEY` to the browser.

## Supabase Setup

### 1. Run migrations (in order)

Files in `supabase/migrations/`:
- `001_initial_schema.sql` — orders, order lines, inventory snapshots, payments, imports
- `002_auth_orgs_rbac.sql` — auth, organizations, RBAC, RLS policies
- `003_inventory_system.sql` — inventory entities, operations, balances, RPCs
- `004_report_functions.sql` — report RPCs (inventory balances, product movement, supplier debt) and indexes
- `005_workspace_settings.sql` — workspace settings table (currency, category/store requirements) with RLS
- `006_product_name_unique.sql` — unique constraint on product names (excluding defect copies)

Option A (Supabase SQL Editor):
- Run migrations in order: `001` through `006`.

Option B (psql):

```bash
psql "$SUPABASE_DB_URL" -f supabase/migrations/001_initial_schema.sql
psql "$SUPABASE_DB_URL" -f supabase/migrations/002_auth_orgs_rbac.sql
psql "$SUPABASE_DB_URL" -f supabase/migrations/003_inventory_system.sql
psql "$SUPABASE_DB_URL" -f supabase/migrations/004_report_functions.sql
psql "$SUPABASE_DB_URL" -f supabase/migrations/005_workspace_settings.sql
psql "$SUPABASE_DB_URL" -f supabase/migrations/006_product_name_unique.sql
```

### 2. Configure Auth URLs in Supabase

In Auth settings:
- Site URL: `http://localhost:3000`
- Additional redirect URLs: `http://localhost:3000/auth/callback`

### 3. Create first organization

- Go to `/signup`
- Enter full name, organization name, email, password
- Confirm email (if email confirmations are enabled)

### 4. Seed demo data (optional)

Populate the database with sample inventory data (categories, warehouses, suppliers, products, operations):

```bash
npm run seed  # requires dev server running + E2E_EMAIL/E2E_PASSWORD env vars
```

Authenticated Playwright runs use the same auth model. In local dev, they load `.env.local` and create/update a dev-only owner user automatically when explicit `E2E_EMAIL`/`E2E_PASSWORD` are not set. Set `E2E_AUTO_PROVISION=false` to disable that behavior.

## Auth + Organization Model

Core behavior:
- Signup creates `profiles` row.
- Signup with `organization_name` creates a new organization.
- Signup creator gets `owner` membership.
- `owner` and `admin` can invite users.
- Invited users can join organization on acceptance/login.

Roles:
- `owner`: full org control
- `admin`: manage users + operational data
- `member`: standard read/use access

Tenant scoping:
- All data tables use `workspace_id` which maps to `organizations.id`.
- RLS policies enforce organization membership.

## Developer Commands

### App lifecycle

```bash
npm run dev              # Start dev server
npm run build            # Production build
npm run start            # Start production server
npm run lint             # ESLint (flat config, ESLint 9)
npm run seed             # Seed demo inventory data
```

### E2E tests (Playwright)

Install once:

```bash
npm install -D @playwright/test
npx playwright install chromium
```

Run:

```bash
npm run test:e2e
npm run test:e2e:headed
npm run test:e2e:ui
npm run test:e2e:debug
```

Authenticated E2E tests require:

```bash
E2E_EMAIL=your-test-user@example.com
E2E_PASSWORD=your-test-password
```

What runs by default:
- public route tests (no credentials required)
- authenticated tests are skipped unless `E2E_EMAIL` and `E2E_PASSWORD` are set

## Demo Data (CSV)

CSV files for the original import pipeline are in `supabase/demo-data/`:
- `orders_demo.csv`
- `order_lines_demo.csv`
- `inventory_demo.csv`
- `payments_demo.csv`

Use the dashboard upload card after logging in as an `owner` or `admin`.

## API Quick Reference

All routes require authenticated session unless noted.

### Auth & User

- `GET /api/auth/me` — current user profile + memberships
- `PATCH /api/auth/me` — update display name
- `POST /api/auth/invite` — invite user to organization (`owner`/`admin`)

### Dashboard & Metrics

- `GET /api/metrics/summary` — KPI summary for active organization
- `GET /api/metrics/critical-stock` — critical stock list

### Orders

- `GET /api/orders` — orders list
- `GET /api/orders/:id/lines` — order line details

### CSV Imports

- `POST /api/imports` — upload/import CSV (`owner`/`admin`)
- `GET /api/imports` — list imports
- `GET /api/imports/:id` — import detail
- `GET /api/imports/:id/errors` — import errors

### Inventory — Master Data

- `GET|POST /api/categories` — list / create category
- `GET|PUT|DELETE /api/categories/:id` — get / update / delete category
- `GET|POST /api/stores` — list / create store
- `GET|PUT|DELETE /api/stores/:id` — get / update / delete store
- `GET|POST /api/warehouses` — list / create warehouse
- `GET|PUT|DELETE /api/warehouses/:id` — get / update / delete warehouse
- `GET|POST /api/suppliers` — list / create supplier
- `GET|PUT|DELETE /api/suppliers/:id` — get / update / delete supplier
- `GET|POST /api/products` — list / create product
- `GET|PUT|DELETE /api/products/:id` — get / update / delete product

### Inventory — Operations & Balances

- `GET /api/operations` — list operations (with filters)
- `POST /api/operations` — create operation (dispatches to type-specific processor)
- `GET /api/operations/:id` — operation detail with items
- `GET /api/product-balances` — current product balances per warehouse

### Reports

- `GET /api/reports/inventory-balances` — inventory balances (current or historical by date)
- `GET /api/reports/product-movement` — product movement aggregation by date range
- `GET /api/reports/supplier-debt` — supplier debt summary (all-time + period)
- `GET /api/reports/supplier-debt/:supplierId` — drill-down: paginated operations for a supplier

### Settings

- `GET /api/settings` — workspace settings (currency, category/store requirements)
- `PATCH /api/settings` — update workspace settings (`owner`/`admin`)

## Navigation Structure

The app uses a sidebar layout (`AppShell` + `AppSidebar`):

```
Dashboard        /
Orders           /orders
Operations       /operations
Settings         /settings

Master Data (collapsible group):
  Products       /products
  Categories     /categories
  Warehouses     /warehouses
  Stores         /stores
  Suppliers      /suppliers

Reports (collapsible group):
  Inventory      /reports/inventory
  Movement       /reports/movement
  Supplier Debt  /reports/supplier-debt
```

## Project Structure

```
src/
  app/                          Next.js App Router pages and API routes
    api/                        Route handlers (auth, metrics, orders, imports, inventory,
                                reports, settings)
    categories/                 Category management page
    stores/                     Store management page
    warehouses/                 Warehouse management page
    suppliers/                  Supplier management page
    products/                   Product management page
    operations/                 Operations list + create form
    reports/                    Report pages
      inventory/                Inventory balances (current + historical)
      movement/                 Product movement report
      supplier-debt/            Supplier debt report with drill-down
      operations/               Operations log report
    settings/                   Workspace settings (currency, requirements, team)
  components/
    ui/                         shadcn/ui primitives (button, card, dialog, dropdown-menu,
                                field, input, label, select, switch, table, tabs, sheet, etc.)
    AppShell.tsx                Sidebar layout wrapper (hidden on auth pages)
    AppSidebar.tsx              Sidebar navigation with collapsible groups
    DataTable.tsx               Reusable data table with column visibility control
    Pagination.tsx              Pagination controls for reports
    ReportFilterBar.tsx         Shared filter bar for report pages
    InviteForm.tsx              Team invite form component
    KpiCard.tsx                 Dashboard KPI card
    UploadCard.tsx              CSV upload component
    ...
  contexts/
    WorkspaceSettingsContext.tsx Workspace settings provider (currency, defaults)
  lib/
    operations/                 Operation processors and validation
      validate-operation.ts     Input validation
      process-purchase.ts       Purchase processor (updates balances + cost)
      process-production.ts     Production processor
      process-transfer.ts       Transfer processor
      process-simple.ts         Sale, return, write-off, defect processor
      process-payment.ts        Payment processor
      update-balances.ts        Balance update helpers
      index.ts                  Dispatcher
    supabase-server.ts          Server-side Supabase client factories
    supabase-browser.ts         Browser Supabase client singleton
    request-context.ts          Route context helper (auth + workspace resolution)
    csv-parsers.ts              CSV import parsers
    format-currency.ts          Currency formatting utility (Intl.NumberFormat)
  types/
    database.ts                 DB table interfaces and API response types
    inventory.ts                Inventory, operation, report, and settings types
  i18n/                         Custom i18n (English + Russian)
scripts/
  seed.ts                       Seed script for demo inventory data
docs/
  initial-plan.md               Original project plan
  inventory-system-plan.md      Inventory system design document
  design-prototype.jpg          UI design prototype
supabase/
  migrations/                   SQL migrations (001–006)
  demo-data/                    CSV demo files for import pipeline
tests/
  e2e/                          Playwright E2E tests
```

## Troubleshooting

### `Missing NEXT_PUBLIC_SUPABASE_URL` or other env errors
- Verify `.env.local` exists and values are set.
- Restart dev server after env changes.

### Redirect loop to `/login`
- Session cookie is missing/expired.
- Ensure auth callback URL is configured exactly as `http://localhost:3000/auth/callback`.

### `No active organization membership`
- User exists but has no `organization_memberships` row.
- Re-run signup/invite flow or check membership data in DB.

### Invite emails not arriving
- Check Supabase Auth email settings / SMTP configuration.
- Check invite record in `organization_invites`.

### `playwright: command not found`
- Install Playwright dev dependency and browser:
  - `npm install -D @playwright/test`
  - `npx playwright install chromium`

## Security Notes

- Service role key is used only in server code for privileged auth admin actions.
- App data access is enforced via Postgres RLS and organization membership policies.
- Do not call privileged operations from client-side code.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Tover is a multi-tenant marketplace turnover tracker built with Next.js 16 (App Router), React 19, TypeScript, and Supabase (Postgres + Auth). It tracks orders, inventory, sales metrics, and critical stock alerts, supporting CSV data imports and role-based access control.

## Commands

```bash
npm run dev              # Start dev server on localhost:3000
npm run build            # Production build
npm run lint             # Run ESLint (flat config, ESLint 9)
npm run test:e2e         # Run Playwright E2E tests (requires dev server or auto-starts one)
npm run test:e2e:headed  # Playwright in headed browser mode
npm run test:e2e:debug   # Playwright with debugger
npm run test:e2e:ui      # Playwright interactive UI mode
npm run seed             # Seed demo inventory data (requires dev server + E2E_EMAIL/E2E_PASSWORD)
```

E2E tests require `E2E_EMAIL` and `E2E_PASSWORD` env vars for authenticated test projects. Tests are in `tests/e2e/` with three Playwright projects: `setup` (auth), `chromium-public`, and `chromium-authenticated`.

## Architecture

### Tech Stack
- **Next.js 16.1.6** with App Router, **React 19**, **TypeScript** (strict mode)
- **Supabase** for Postgres DB, Auth (email/password), and RLS
- **shadcn/ui** (New York style) with Tailwind CSS v4, Radix UI primitives, Lucide icons
- **PapaParse** for CSV parsing

### Path Alias
`@/*` maps to `./src/*`

### Key Directories
- `src/app/` — Next.js App Router pages and API routes
- `src/components/ui/` — shadcn/ui primitives (button, card, dialog, field, input, table, tabs, sheet, etc.)
- `src/components/` — app-specific components (AppShell, AppSidebar, DataTable, Pagination, ReportFilterBar, InviteForm, KpiCard, UploadCard, etc.)
- `src/contexts/` — React context providers (WorkspaceSettingsContext)
- `src/lib/` — Supabase clients, auth helpers, CSV parsers, currency formatting, utilities
- `src/lib/operations/` — operation processors and validation (purchase, sale, transfer, production, etc.)
- `src/types/database.ts` — TypeScript interfaces for all DB tables and API responses
- `src/types/inventory.ts` — inventory entity, operation, and request types
- `src/i18n/` — Custom i18n with Context API (English + Russian)
- `supabase/migrations/` — SQL migrations (schema, auth, RLS, inventory, reports, settings)
- `scripts/` — seed script for demo data
- `docs/` — design docs and plans

### Multi-Tenancy
Every data table has a `workspace_id` column that maps to `organizations.id`. RLS policies enforce per-organization data isolation. Users access data through their `organization_memberships`.

### Authentication & Authorization
- Supabase Auth with cookie-based sessions managed by `@supabase/ssr`
- `middleware.ts` protects all routes except `/login`, `/signup`, `/forgot-password`, `/reset-password`, `/auth/*`
- Two Supabase client factories in `src/lib/supabase-server.ts`:
  - `createUserServerClient()` — per-request client with cookie auth
  - `createServiceRoleClient()` — admin client for privileged operations
- Browser client singleton in `src/lib/supabase-browser.ts`

### API Route Pattern
All API routes use `getRouteContext(request, options)` from `src/lib/request-context.ts` which:
1. Validates the user session
2. Resolves organization membership and workspace
3. Optionally enforces manager role (`requireManager: true` for owner/admin)
4. Returns `{ supabase, user, workspaceId, role }`

Errors are handled via `RouteAuthError` class and `toRouteErrorResponse()` helper.

### RBAC Roles
- **owner**, **admin** — manager roles (can invite users, manage imports)
- **member** — standard read access

### CSV Import Pipeline
Import types: `orders_csv`, `order_lines_csv`, `inventory_csv`, `payments_csv`. The flow is: parse with PapaParse → validate headers → validate rows → create import record → insert valid rows → store errors in `import_errors` → update import status. Parsers are in `src/lib/csv-parsers.ts`.

### I18n
Custom Context API solution in `src/i18n/`. Uses `useI18n()` hook returning `{ locale, t, setLocale }`. Translations are typed objects in `en.ts`/`ru.ts`. Locale persisted to localStorage key `tover-locale`.

### Inventory System

**Tables** (all scoped by `workspace_id`):
- `categories`, `stores`, `warehouses`, `suppliers` — master data entities
- `products` — linked to category, with optional default warehouse/supplier
- `product_balances` — per-product, per-warehouse quantity and weighted-average cost
- `operations` — header record with type, date, warehouse, optional supplier/destination
- `operation_items` — line items with product, quantity, price

**8 operation types**: `purchase`, `sale`, `return`, `write_off`, `transfer`, `production`, `defect`, `payment`

**Processing pipeline**: validate input → create operation record → create operation items → update product balances (type-specific logic)

**RPCs** (Postgres functions):
- `update_product_balance` — generic balance upsert
- `process_purchase_balance` — updates balance with weighted-average cost recalculation
- `process_production_balances` — deducts components and adds produced product
- `report_inventory_balances_at_date` — compute historical balances by replaying operations
- `report_product_movement` — aggregate movement quantities by product/warehouse/type
- `report_supplier_debt` — calculate purchased/paid totals per supplier

**Processor files** in `src/lib/operations/`:
- `validate-operation.ts` — input validation
- `process-purchase.ts` — purchase (updates cost via weighted average)
- `process-production.ts` — production (deducts components, adds output)
- `process-transfer.ts` — transfer between warehouses
- `process-simple.ts` — sale, return, write-off, defect
- `process-payment.ts` — supplier payment
- `update-balances.ts` — shared balance update helpers
- `index.ts` — dispatcher that routes to correct processor by operation type

### Reports System
3 report types with dedicated pages and API routes:
- **Inventory Balances** (`/reports/inventory`) — current or historical, units/cost toggle, dynamic warehouse columns
- **Product Movement** (`/reports/movement`) — aggregated by product/warehouse within date range
- **Supplier Debt** (`/reports/supplier-debt`) — period + all-time totals, creditor/debitor classification, drill-down

Report API routes: `/api/reports/inventory-balances`, `/api/reports/product-movement`, `/api/reports/supplier-debt`, `/api/reports/supplier-debt/[supplierId]`

Shared components: `Pagination.tsx` (offset-based), `ReportFilterBar.tsx` (flex layout wrapper)

### Workspace Settings
- `workspace_settings` table: `currency` (3-letter ISO, default EUR), `category_required`, `default_category_id`, `store_required`, `default_store_id`
- API: `GET|PATCH /api/settings` (PATCH requires manager role)
- Client-side: `WorkspaceSettingsContext` (`src/contexts/WorkspaceSettingsContext.tsx`) provides settings + `refetch()`
- Currency formatting: `formatCurrency()` in `src/lib/format-currency.ts` using `Intl.NumberFormat`

### UI Patterns
- Entity pages follow CRUD pattern: fetch data → `DataTable` → `Dialog` for create/edit
- `DataTable` supports column visibility control with localStorage persistence (`tover-columns-{tableId}`)
- Forms use `Field`/`FieldLabel` from `@/components/ui/field`
- `AppShell` (`src/components/AppShell.tsx`) conditionally renders sidebar (hidden on auth pages)
- `AppSidebar` has collapsible "Master Data" and "Reports" groups, plus Settings link

### Database Migrations
- `001_initial_schema.sql` — orders, order lines, inventory snapshots, payments, imports
- `002_auth_orgs_rbac.sql` — auth, organizations, memberships, RBAC, RLS policies
- `003_inventory_system.sql` — inventory entities, operations, balances, indexes, RPCs
- `004_report_functions.sql` — report RPCs (inventory balances, product movement, supplier debt) + index
- `005_workspace_settings.sql` — workspace settings table with RLS, auto-seeds defaults
- `006_product_name_unique.sql` — partial unique constraint on product names (excludes defect copies)

## Environment Variables

Required in `.env.local` (see `.env.local.example`):
```
NEXT_PUBLIC_SUPABASE_URL
NEXT_PUBLIC_SUPABASE_ANON_KEY
SUPABASE_SERVICE_ROLE_KEY        # server-only
NEXT_PUBLIC_SITE_URL
```

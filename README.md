# Tover

Multi-tenant marketplace turnover tracker built with Next.js + Supabase.

This app supports:
- email/password auth with Supabase
- organization creation at signup
- role-based access (`owner`, `admin`, `member`)
- member invites per organization
- CSV imports for orders, order lines, inventory, and payments
- dashboard KPIs and order drill-down

## Stack

- Next.js 16 (App Router)
- React 19 + TypeScript
- Supabase (Auth + Postgres)
- Tailwind CSS 4
- Playwright (E2E)

## Prerequisites

- Node.js `>=20.9.0`
- npm
- Supabase project (URL, anon key, service role key)

## Quick Start

```bash
cd /Users/usuario/Projects/tover-app
cp .env.local.example .env.local
# fill values in .env.local
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

## Environment Variables

Use `/Users/usuario/Projects/tover-app/.env.local.example` as template.

```bash
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=your-anon-key
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key
NEXT_PUBLIC_SITE_URL=http://localhost:3000
```

Notes:
- `SUPABASE_SERVICE_ROLE_KEY` is required for server-side invite operations.
- Never expose `SUPABASE_SERVICE_ROLE_KEY` to the browser.

## Supabase Setup

### 1. Run migrations (in order)

Files:
- `/Users/usuario/Projects/tover-app/supabase/migrations/001_initial_schema.sql`
- `/Users/usuario/Projects/tover-app/supabase/migrations/002_auth_orgs_rbac.sql`

Option A (Supabase SQL Editor):
- Run `001` first, then `002`.

Option B (psql):

```bash
psql "$SUPABASE_DB_URL" -f supabase/migrations/001_initial_schema.sql
psql "$SUPABASE_DB_URL" -f supabase/migrations/002_auth_orgs_rbac.sql
```

### 2. Configure Auth URLs in Supabase

In Auth settings:
- Site URL: `http://localhost:3000`
- Additional redirect URLs: `http://localhost:3000/auth/callback`

### 3. Create first organization

- Go to `/signup`
- Enter full name, organization name, email, password
- Confirm email (if email confirmations are enabled)

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
- Existing business tables still use `workspace_id`.
- In this project, `workspace_id` maps to `organizations.id`.
- RLS policies enforce organization membership.

## Developer Commands

### App lifecycle

```bash
npm run dev
npm run build
npm run start
npm run lint
```

### Useful checks

```bash
# Type-check without emitting files
npx tsc --noEmit

# Build with webpack (useful fallback if Turbopack has local issues)
npm run build -- --webpack
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

## Demo Data

CSV files are available in:
- `/Users/usuario/Projects/tover-app/supabase/demo-data/orders_demo.csv`
- `/Users/usuario/Projects/tover-app/supabase/demo-data/order_lines_demo.csv`
- `/Users/usuario/Projects/tover-app/supabase/demo-data/inventory_demo.csv`
- `/Users/usuario/Projects/tover-app/supabase/demo-data/payments_demo.csv`

Use the dashboard upload card after logging in as an `owner` or `admin`.

## API Quick Reference

All routes require authenticated session unless noted.

- `GET /api/auth/me`: current user profile + memberships
- `PATCH /api/auth/me`: update display name
- `POST /api/auth/invite`: invite user to an organization (`owner`/`admin`)

- `GET /api/metrics/summary`: KPI summary for active organization
- `GET /api/metrics/critical-stock`: critical stock list

- `GET /api/orders`: orders list
- `GET /api/orders/:id/lines`: order line details

- `POST /api/imports`: upload/import CSV (`owner`/`admin`)
- `GET /api/imports`: list imports
- `GET /api/imports/:id`: import detail
- `GET /api/imports/:id/errors`: import errors

## Project Structure

- `/Users/usuario/Projects/tover-app/src/app` - Next.js pages/routes
- `/Users/usuario/Projects/tover-app/src/app/api` - route handlers
- `/Users/usuario/Projects/tover-app/src/lib` - Supabase clients and request context
- `/Users/usuario/Projects/tover-app/supabase/migrations` - SQL migrations
- `/Users/usuario/Projects/tover-app/tests/e2e` - Playwright tests

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

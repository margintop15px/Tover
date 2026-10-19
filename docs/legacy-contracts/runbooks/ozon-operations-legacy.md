# Historical Ozon operations

This is a copied historical runbook, not instructions for the replacement runtime. All old source paths and commands below belong to commit `9f799586a62226bc62643e5dedc54d76a0176359`. No command here was executed during extraction. Configuration may have drifted; production changes require their own review and authorization.

Read the [current retention boundary](../ozon-fetching.md) first. The old scheduler is external state; removing Next.js files does not stop it. These excerpts preserve inspection, pause/resume and recovery details for a later deliberate cutover. The described inventory reconciliation and repair target only the old schema.

Source: `baseline:docs/ozon-integration.md:615-831` (verbatim recovery excerpt).

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
value in `x-tover-recovery-secret`, uses the service-role Supabase client,
queues every due automatic Ozon run, and claims at most one due step per
invocation. It returns only whether a step was processed. Missing server
configuration returns `503`; a bad secret returns `401`. The app route has a
110-second execution budget, inside the migration's
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


Source: `baseline:docs/ozon-integration.md:867-902` (verbatim reconciliation excerpt).

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

Source: `baseline:docs/ozon-integration.md:917-933` (verbatim operation support excerpt).

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


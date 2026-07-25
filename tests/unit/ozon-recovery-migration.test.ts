import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const migrationPath = join(
  process.cwd(),
  "supabase/migrations/020_ozon_sync_recovery.sql"
);

function migrationSql() {
  return readFileSync(migrationPath, "utf8");
}

function compact(value: string) {
  return value.replace(/--.*$/gm, " ").replace(/\s+/g, " ").trim();
}

function functionDefinition(sql: string, name: string) {
  const pattern = new RegExp(
    `create\\s+or\\s+replace\\s+function\\s+public\\.${name}\\b[\\s\\S]*?as\\s+\\$function\\$[\\s\\S]*?\\$function\\$`,
    "i"
  );
  const match = sql.match(pattern);
  assert.ok(match, `missing function public.${name}`);
  return compact(match[0]);
}

const stepKeys = [
  "warehouses",
  "products",
  "stocks",
  "postings",
  "returns",
  "finance",
  "legalEntities",
  "reports",
  "removals",
  "supplies",
  "analytics",
  "discountedProducts",
];

test("migration creates the exact ordered durable step registry", () => {
  const sql = compact(migrationSql());
  const values = [...sql.matchAll(/\(\s*(\d+)\s*,\s*'([^']+)'\s*\)/g)]
    .map((match) => [Number(match[1]), match[2]] as const)
    .filter(([order, key]) => order >= 1 && order <= 12 && stepKeys.includes(key));

  assert.deepEqual(values.slice(0, 12), stepKeys.map((key, index) => [index + 1, key]));
  assert.match(sql, /create table public\.marketplace_sync_run_steps/i);
  assert.match(
    sql,
    /state text not null default 'pending' check \(state in \('pending', 'running', 'retry_scheduled', 'completed', 'skipped', 'failed'\)\)/i
  );
  assert.match(sql, /unique \(run_id, step_key\)/i);
  assert.match(sql, /unique \(run_id, step_order\)/i);
  assert.match(
    sql,
    /foreign key \(run_id, workspace_id, connection_id, provider\) references public\.marketplace_sync_runs\(id, workspace_id, connection_id, provider\) on delete cascade/i,
    "the denormalized step context must match its parent run"
  );
  assert.match(
    sql,
    /foreign key \(connection_id, workspace_id, provider\) references public\.marketplace_connections\(id, workspace_id, provider\) on delete cascade/i,
    "a run must belong to the referenced connection tenant"
  );
  assert.match(sql, /lease_token uuid/i);
  assert.match(sql, /lease_expires_at timestamptz/i);
  assert.match(
    sql,
    /state = 'running' and lease_token is not null and lease_expires_at is not null[\s\S]*?state <> 'running' and lease_token is null and lease_expires_at is null/i,
    "only running steps may carry a complete lease"
  );
  assert.match(
    sql,
    /state <> 'retry_scheduled' or next_attempt_at is not null/i,
    "scheduled retries must always be due at a concrete time"
  );
  assert.match(sql, /last_error jsonb/i);
  assert.match(sql, /summary jsonb not null default '\{\}'::jsonb/i);
  assert.match(sql, /attempt_count integer not null default 0 check \(attempt_count >= 0\)/i);
  assert.match(
    sql,
    /step_key text not null check \(step_key in \('warehouses', 'products', 'stocks', 'postings', 'returns', 'finance', 'legalEntities', 'reports', 'removals', 'supplies', 'analytics', 'discountedProducts'\)\)/i
  );
  assert.match(
    sql,
    /check \(\s*\(step_order = 1 and step_key = 'warehouses'\)[\s\S]*?\(step_order = 12 and step_key = 'discountedProducts'\)\s*\)/i,
    "the schema must reject a valid step key paired with the wrong order"
  );
  assert.match(sql, /created_at timestamptz not null default now\(\)/i);
  assert.match(sql, /updated_at timestamptz not null default now\(\)/i);
});

test("migration normalizes legacy runs before enforcing active-run uniqueness", () => {
  const sql = compact(migrationSql());
  const normalizeAt = sql.search(
    /update public\.marketplace_sync_runs[\s\S]*?set status = 'failed'/i
  );
  const statusConstraintAt = sql.search(
    /add constraint marketplace_sync_runs_status_check/i
  );
  const activeIndexAt = sql.search(
    /create unique index[\s\S]*?on public\.marketplace_sync_runs\s*\(connection_id\)[\s\S]*?where status in \('running', 'retrying'\)/i
  );

  assert.ok(normalizeAt >= 0, "legacy running runs must be marked interrupted");
  assert.ok(statusConstraintAt > normalizeAt, "status constraint must follow normalization");
  assert.ok(activeIndexAt > statusConstraintAt, "active-run index must be created last");
  assert.match(
    sql,
    /marketplace_sync_runs_status_check[\s\S]*?'retrying'/i
  );
  assert.match(
    sql,
    /marketplace_connections_last_sync_status_check[\s\S]*?'retrying'/i
  );
  assert.match(
    sql,
    /update public\.marketplace_connections[\s\S]*?coalesce\(health, '\{\}'::jsonb\) \|\|/i,
    "legacy normalization must preserve unrelated health keys"
  );
  assert.match(
    sql,
    /case when status = 'disabled' then status/i,
    "legacy normalization must not re-enable disabled connections"
  );
});

test("step RLS is member-readable and admin-writable with due-work indexes", () => {
  const sql = compact(migrationSql());

  assert.match(sql, /alter table public\.marketplace_sync_run_steps enable row level security/i);
  assert.match(
    sql,
    /for select to authenticated using \(public\.app_is_org_member\(workspace_id\)\)/i
  );
  assert.match(
    sql,
    /for all to authenticated using \(public\.app_has_org_role\(workspace_id, array\['owner', 'admin'\]\)\)/i
  );
  assert.match(
    sql,
    /create index[\s\S]*?marketplace_sync_run_steps[\s\S]*?\(state, next_attempt_at/i
  );
  assert.match(
    sql,
    /create index[\s\S]*?marketplace_sync_run_steps[\s\S]*?\(run_id, step_order\)/i
  );
  assert.match(sql, /create trigger set_marketplace_sync_run_steps_updated_at/i);
});

test("recovery RPCs are service-role-only security definers with a fixed search path", () => {
  const sql = compact(migrationSql());
  const names = [
    "begin_or_resume_ozon_sync_run",
    "claim_ozon_sync_run_step",
    "finish_ozon_sync_run_step",
    "retry_failed_ozon_sync_run_steps",
    "schedule_ozon_sync_recovery",
  ];

  for (const name of names) {
    const definition = functionDefinition(migrationSql(), name);
    assert.match(definition, /security definer/i, `${name} must be SECURITY DEFINER`);
    assert.match(
      definition,
      /set search_path = ''/i,
      `${name} must have an empty search_path`
    );
    assert.match(
      sql,
      new RegExp(
        `revoke execute on function public\\.${name}\\([^;]*\\) from public, anon, authenticated`,
        "i"
      )
    );
    assert.match(
      sql,
      new RegExp(
        `grant execute on function public\\.${name}\\([^;]*\\) to service_role`,
        "i"
      )
    );
  }
});

test("begin/resume serializes per connection and inserts all steps exactly once", () => {
  const definition = functionDefinition(migrationSql(), "begin_or_resume_ozon_sync_run");

  assert.match(definition, /pg_advisory_xact_lock/i);
  assert.match(definition, /where connection_id = p_connection_id and status in \('running', 'retrying'\)/i);
  assert.match(definition, /on conflict \(run_id, step_key\) do nothing/i);
  assert.match(
    definition,
    /set next_attempt_at = now\(\)[\s\S]*?where run_id = v_run\.id[\s\S]*?state = 'retry_scheduled'/i
  );
});

test("claims are serialized, SKIP LOCKED, and protected by a ten-minute UUID lease", () => {
  const definition = functionDefinition(migrationSql(), "claim_ozon_sync_run_step");
  const advisoryLockAt = definition.search(/pg_advisory_xact_lock/i);
  const rowLockAt = definition.search(/for update skip locked/i);

  assert.match(definition, /p_run_id uuid default null/i);
  assert.match(definition, /for update skip locked/i);
  assert.match(definition, /pg_advisory_xact_lock/i);
  assert.ok(
    advisoryLockAt >= 0 && rowLockAt > advisoryLockAt,
    "claim must take the per-connection advisory lock before its row lock"
  );
  assert.match(definition, /state = 'pending'/i);
  assert.match(definition, /state = 'retry_scheduled'[\s\S]*?next_attempt_at <= now\(\)/i);
  assert.match(definition, /state = 'running'[\s\S]*?lease_expires_at <= now\(\)/i);
  assert.match(definition, /not exists[\s\S]*?lease_expires_at > now\(\)/i);
  assert.match(definition, /attempt_count = attempt_count \+ 1/i);
  assert.match(definition, /lease_token = gen_random_uuid\(\)/i);
  assert.match(definition, /lease_expires_at = now\(\) \+ interval '10 minutes'/i);
  assert.match(
    definition,
    /if exists[\s\S]*?state = 'running'[\s\S]*?lease_expires_at > now\(\)[\s\S]*?return/i,
    "claim must recheck live work after taking the per-connection lock"
  );
  assert.match(
    definition,
    /parent_run\.status in \('running', 'retrying'\)/i,
    "claim must not execute work for a terminal parent run"
  );
});

test("finish enforces the live lease and derives run and connection state atomically", () => {
  const definition = functionDefinition(migrationSql(), "finish_ozon_sync_run_step");
  const advisoryLockAt = definition.search(/pg_advisory_xact_lock/i);
  const stepUpdateAt = definition.search(
    /update public\.marketplace_sync_run_steps/i
  );

  assert.ok(
    advisoryLockAt >= 0 && stepUpdateAt > advisoryLockAt,
    "finish must take the per-connection advisory lock before mutating its step"
  );
  assert.match(definition, /lease_token = p_lease_token/i);
  assert.match(definition, /lease_expires_at > now\(\)/i);
  assert.match(definition, /state = 'running'/i);
  assert.match(
    definition,
    /p_state not in \('retry_scheduled', 'completed', 'skipped', 'failed'\)/i
  );
  assert.match(definition, /lease_token = null/i);
  assert.match(definition, /lease_expires_at = null/i);
  assert.match(definition, /when count\(\*\) filter \(where state = 'retry_scheduled'\) > 0 then 'retrying'/i);
  assert.match(
    definition,
    /when count\(\*\) filter \(where state in \('running', 'pending'\)\) > 0 then 'running'/i
  );
  assert.match(
    definition,
    /when count\(\*\) filter \(where state = 'failed'\) > 0[\s\S]*?count\(\*\) filter \(where state in \('completed', 'skipped'\)\) > 0 then 'completed_with_errors'/i
  );
  assert.match(definition, /else 'completed'/i);
  assert.match(definition, /coalesce\(health, '\{\}'::jsonb\) \|\|/i);
  assert.match(definition, /case when status = 'disabled' then status/i);
});

test("manual retry reactivates only failed steps in a terminal run", () => {
  const definition = functionDefinition(
    migrationSql(),
    "retry_failed_ozon_sync_run_steps"
  );

  assert.match(
    definition,
    /v_run\.status not in \('completed', 'completed_with_errors', 'failed'\)/i
  );
  assert.match(definition, /where run_id = p_run_id and state = 'failed'/i);
  assert.match(definition, /attempt_count = 0/i);
  assert.match(definition, /state = 'pending'/i);
  assert.doesNotMatch(definition, /state in \('failed', 'completed', 'skipped'\)/i);
  assert.match(definition, /set status = 'running'/i);
  assert.match(
    definition,
    /if exists[\s\S]*?status in \('running', 'retrying'\)[\s\S]*?raise exception/i,
    "a terminal run cannot be reactivated beside another active run"
  );
});

test("scheduler reads only approved Vault names and replaces the named one-minute job", () => {
  const sql = compact(migrationSql());
  const definition = functionDefinition(migrationSql(), "schedule_ozon_sync_recovery");

  assert.match(definition, /tover_ozon_recovery_url/i);
  assert.match(definition, /tover_ozon_recovery_secret/i);
  assert.match(definition, /tover-ozon-sync-recovery/i);
  assert.match(definition, /cron\.unschedule/i);
  assert.match(definition, /cron\.schedule/i);
  assert.match(definition, /'\* \* \* \* \*'/i);
  assert.match(definition, /net\.http_post/i);
  assert.match(definition, /x-tover-recovery-secret/i);
  assert.match(definition, /timeout_milliseconds\s*:=\s*120000/i);
  assert.match(
    definition,
    /url\s*:=\s*\(\s*select decrypted_secret from vault\.decrypted_secrets where name = 'tover_ozon_recovery_url'/i,
    "the scheduled command must resolve its URL from Vault at runtime"
  );
  assert.match(
    definition,
    /jsonb_build_object\([\s\S]*?'x-tover-recovery-secret'[\s\S]*?\(\s*select decrypted_secret from vault\.decrypted_secrets where name = 'tover_ozon_recovery_secret'/i,
    "the scheduled command must resolve its secret from Vault at runtime"
  );
  assert.doesNotMatch(
    definition,
    /https?:\/\//i,
    "the scheduler must not embed a recovery URL"
  );
  assert.match(sql, /pause[\s\S]*?cron\.unschedule/i);
  assert.match(sql, /resume[\s\S]*?schedule_ozon_sync_recovery/i);
  assert.match(sql, /inspect[\s\S]*?cron\.job/i);
});

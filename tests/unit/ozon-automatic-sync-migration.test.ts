import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const sql = readFileSync(
  join(
    process.cwd(),
    "supabase/migrations/028_ozon_automatic_incremental_sync.sql"
  ),
  "utf8"
).replace(/--.*$/gm, " ").replace(/\s+/g, " ").trim();

test("automatic sync migration persists UTC coverage and due scheduling state", () => {
  assert.match(sql, /add column if not exists last_synced_through timestamptz/i);
  assert.match(sql, /add column if not exists next_automatic_sync_at timestamptz/i);
  assert.match(sql, /add column if not exists advances_watermark boolean not null default false/i);
  assert.match(
    sql,
    /max\(date_to\).*?status = 'completed'.*?last_synced_through/i
  );
  assert.match(
    sql,
    /idx_marketplace_connections_ozon_auto_due.*?next_automatic_sync_at.*?status in \('connected', 'error'\)/i
  );
});

test("database-owned resolver starts from the UTC month or completed watermark and rejects bad backfills", () => {
  assert.match(
    sql,
    /create or replace function public\.begin_or_resume_ozon_sync_run_v3/i
  );
  assert.match(
    sql,
    /status in \('connected', 'error'\).*?for update/i
  );
  assert.match(
    sql,
    /status in \('running', 'retrying'\).*?if found then return v_run/i
  );
  assert.match(
    sql,
    /coalesce\( v_connection\.last_synced_through, date_trunc\('month', v_now at time zone 'utc'\) at time zone 'utc' \)/i
  );
  assert.match(sql, /ozon sync window requires both dates/i);
  assert.match(sql, /v_date_to > v_now/i);
  assert.match(sql, /v_advances_watermark boolean := false/i);
  assert.match(sql, /if p_date_from is null then.*?v_advances_watermark := true/i);
  assert.match(sql, /set advances_watermark = v_advances_watermark/i);
});

test("scheduler uses a short advisory-locked enqueue, skips active runs, and advances only to next UTC midnight", () => {
  assert.match(
    sql,
    /create or replace function public\.enqueue_due_ozon_sync_runs_v1/i
  );
  assert.match(sql, /next_automatic_sync_at <= p_now/i);
  assert.match(sql, /pg_advisory_xact_lock.*?v_connection\.id/i);
  assert.match(sql, /status in \('running', 'retrying'\).*?continue/i);
  assert.match(
    sql,
    /date_trunc\('day', p_now at time zone 'utc'\) \+ interval '1 day'.*?at time zone 'utc'/i
  );
});

test("only a fully completed run moves the watermark, while disable stops future claims without erasing history", () => {
  assert.match(
    sql,
    /last_synced_through = case when v_status = 'completed' and v_run\.advances_watermark and v_run\.date_to is not null/i
  );
  assert.match(
    sql,
    /create or replace function public\.disable_ozon_connection_v1/i
  );
  assert.match(sql, /credential_ciphertext = '\{\}'::jsonb/i);
  assert.match(sql, /next_automatic_sync_at = null/i);
  assert.match(sql, /state in \('pending', 'running', 'retry_scheduled'\)/i);
  assert.match(
    sql,
    /claim_ozon_sync_run_step_v2.*?connections\.status in \('connected', 'error'\)/i
  );
});

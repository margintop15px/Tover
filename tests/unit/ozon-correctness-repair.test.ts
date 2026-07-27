import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { normalizeOzonCandidateOperation } from "../../src/lib/ozon/candidates";
import {
  OzonIncompleteResponseError,
  OzonInvariantError,
} from "../../src/lib/ozon/client";
import {
  classifyOzonSyncError,
  durableRetryDelayMs,
} from "../../src/lib/ozon/durable-runner";
import {
  canonicalJson,
  decodeCashFlowReportRows,
  decodeOzonReturn,
  decimalString,
  OzonReportDownloadError,
  splitCashFlowPeriods,
  splitDateWindows,
} from "../../src/lib/ozon/sync";
import { getReportTableRows } from "../../src/lib/reports/report-display";
import { buildReportUrl } from "../../src/lib/reports/report-runner";

const migration021 = readFileSync(
  new URL(
    "../../supabase/migrations/021_ozon_sync_checkpoints_observability.sql",
    import.meta.url
  ),
  "utf8"
);
const migration022 = readFileSync(
  new URL(
    "../../supabase/migrations/022_ozon_evidence_accounting_correctness.sql",
    import.meta.url
  ),
  "utf8"
);
const repairRetryMigration = readFileSync(
  new URL(
    "../../supabase/migrations/20260727133620_ozon_repair_retry_scheduled_steps.sql",
    import.meta.url
  ),
  "utf8"
);
const reconcileScript = readFileSync(
  new URL("../../scripts/ozon-reconcile.ts", import.meta.url),
  "utf8"
);

test("canonical values preserve exact decimals and recursively sort evidence keys", () => {
  assert.equal(decimalString("00012345678901234567890.12000"), "12345678901234567890.12");
  assert.equal(decimalString("-0.000"), "0");
  assert.equal(decimalString("1,2500"), "1.25");
  assert.equal(decimalString({ amount: "10.00" }), null);
  assert.equal(
    canonicalJson({ z: [{ b: 2, a: 1 }], a: { d: 4, c: 3 } }),
    '{"a":{"c":3,"d":4},"z":[{"a":1,"b":2}]}'
  );
});

test("candidate normalization keeps quantities and prices as canonical decimal strings", () => {
  const normalized = normalizeOzonCandidateOperation({
    type: "sale",
    operationDate: "2026-07-27",
    items: [
      {
        productId: "product-id",
        warehouseId: "warehouse-id",
        quantity: "0002.500",
        unitPrice: "1099.9900",
      },
    ],
  });

  assert.equal(normalized.items?.[0].quantity, "2.5");
  assert.equal(normalized.items?.[0].unitPrice, "1099.99");
});

test("cash-flow periods use Ozon's complete calendar halves and buyout periods do not overlap", () => {
  assert.deepEqual(splitCashFlowPeriods("2026-01-10", "2026-02-20"), [
    { from: "2026-01-01", to: "2026-01-15" },
    { from: "2026-01-16", to: "2026-01-31" },
    { from: "2026-02-01", to: "2026-02-15" },
    { from: "2026-02-16", to: "2026-02-28" },
  ]);
  assert.deepEqual(splitDateWindows("2026-01-01", "2026-03-05", 31), [
    { from: "2026-01-01", to: "2026-01-31" },
    { from: "2026-02-01", to: "2026-03-03" },
    { from: "2026-03-04", to: "2026-03-05" },
  ]);
});

test("cash-flow rows use period and currency identity and deduplicate exact repeats", () => {
  const rub = {
    period: {
      id: "11567022278500",
      begin: "2026-07-01T00:00:00.000Z",
      end: "2026-07-15T23:59:59.999Z",
    },
    orders_amount: "1000.2500",
    currency_code: "RUB",
  };
  const rows = decodeCashFlowReportRows(
    [rub, { ...rub }, { ...rub, currency_code: "USD" }],
    "workspace-id",
    "connection-id",
    "run-id"
  );

  assert.deepEqual(
    rows.map((row) => ({
      external_id: row.external_id,
      period_start: row.period_start,
      period_end: row.period_end,
      amount: row.amount,
      currency_code: row.currency_code,
    })),
    [
      {
        external_id: "cash-flow:11567022278500:RUB",
        period_start: "2026-07-01",
        period_end: "2026-07-15",
        amount: "1000.25",
        currency_code: "RUB",
      },
      {
        external_id: "cash-flow:11567022278500:USD",
        period_start: "2026-07-01",
        period_end: "2026-07-15",
        amount: "1000.25",
        currency_code: "USD",
      },
    ]
  );
});

test("cash-flow rows reject conflicting payloads for one period and currency", () => {
  const period = {
    id: "11567022278500",
    begin: "2026-07-01T00:00:00.000Z",
    end: "2026-07-15T23:59:59.999Z",
  };

  assert.throws(
    () =>
      decodeCashFlowReportRows(
        [
          { period, orders_amount: "1000", currency_code: "RUB" },
          { period, orders_amount: "1001", currency_code: "RUB" },
        ],
        "workspace-id",
        "connection-id"
      ),
    OzonInvariantError
  );
});

test("turnover arithmetic stays in PostgreSQL and preserves unknown costs", () => {
  assert.match(
    migration022,
    /CREATE OR REPLACE FUNCTION public\.report_turnover_v2/
  );
  assert.match(
    migration022,
    /p_from - 1[\s\S]*opening_cost[\s\S]*closing_cost[\s\S]*\/ 2::numeric/
  );
  assert.match(
    migration022,
    /average_inventory_cost IS NULL[\s\S]*outflow_cost \/ values\.average_inventory_cost/
  );
});

test("inventory template grouping is delegated to PostgreSQL", () => {
  const url = buildReportUrl(
    "inventory_balances",
    "as_of",
    ["warehouse"],
    { asOfDate: "2026-07-31" }
  );
  assert.match(url, /groupBy=warehouse/);
  assert.deepEqual(
    getReportTableRows("inventory_balances", "warehouse", {
      rows: [{ groupName: "Warehouse", totalCost: "1.2345" }],
    }),
    [{ groupName: "Warehouse", totalCost: "1.2345" }]
  );
  assert.match(
    migration022,
    /CREATE OR REPLACE FUNCTION public\.report_inventory_balances_grouped_v2/
  );
});

test("return decoders preserve official identifiers, money, and explicit completion evidence", () => {
  assert.deepEqual(
    decodeOzonReturn(
      {
        id: "9223372036854775807",
        posting_number: "FBO-1",
        schema: "FBO",
        visual: { status: { sys_name: "ReturnedToSeller" } },
        logistic: {
          return_date: "2026-07-26T10:00:00Z",
          final_moment: "2026-07-27T10:00:00Z",
        },
        target_place: { id: "987654321012345678", name: "Returns warehouse" },
        product: {
          product_id: "123456789012345678",
          offer_id: "OFFER-1",
          sku: "987654321012345678",
          name: "Product",
          quantity: "1.500",
          price: { price: "10.2500", currency_code: "RUB" },
        },
      },
      "fbo_fbs"
    ),
    {
      returnId: "9223372036854775807",
      postingNumber: "FBO-1",
      status: "ReturnedToSeller",
      schema: "FBO",
      logisticReturnDate: "2026-07-26T10:00:00.000Z",
      logisticFinalMoment: "2026-07-27T10:00:00.000Z",
      returnedAt: "2026-07-27T10:00:00.000Z",
      offerId: "OFFER-1",
      sku: "987654321012345678",
      ozonProductId: "123456789012345678",
      productName: "Product",
      quantity: "1.5",
      price: "10.25",
      currencyCode: "RUB",
      warehouseId: "987654321012345678",
      warehouseName: "Returns warehouse",
    }
  );

  const rfbs = decodeOzonReturn(
    {
      return_id: "RFBS-1",
      state: { state: "ArrivedAtReturnPlace" },
      product: {
        sku: "123",
        offer_id: "RFBS-OFFER",
        price: "7.10",
        currency_code: "RUB",
      },
      warehouse_id: "321",
    },
    "rfbs"
  );
  assert.equal(rfbs.quantity, null);
  assert.equal(rfbs.returnedAt, null);
  assert.equal(rfbs.price, "7.1");
});

test("failure count alone controls the durable retry schedule", () => {
  assert.deepEqual(
    Array.from({ length: 8 }, (_, index) => durableRetryDelayMs(index + 1)),
    [
      60_000,
      300_000,
      900_000,
      3_600_000,
      10_800_000,
      21_600_000,
      43_200_000,
      null,
    ]
  );
  assert.equal(
    classifyOzonSyncError(
      new OzonIncompleteResponseError("missing required contract field")
    ).retryable,
    false
  );
  assert.equal(
    classifyOzonSyncError(new OzonReportDownloadError(404)).retryable,
    false
  );
  assert.equal(
    classifyOzonSyncError(new OzonReportDownloadError(503)).retryable,
    true
  );
});

test("migration 021 separates execution count, failure count, checkpoints, and yields", () => {
  assert.match(migration021, /ADD COLUMN IF NOT EXISTS checkpoint JSONB/);
  assert.match(migration021, /ADD COLUMN IF NOT EXISTS failure_count INTEGER/);
  assert.match(migration021, /CREATE TABLE IF NOT EXISTS public\.marketplace_sync_step_events/);
  assert.match(migration021, /CREATE OR REPLACE FUNCTION public\.checkpoint_ozon_sync_run_step_v2/);
  assert.match(migration021, /CREATE OR REPLACE FUNCTION public\.yield_ozon_sync_run_step_v2/);
  assert.match(
    migration021,
    /CREATE OR REPLACE FUNCTION public\.begin_or_resume_ozon_sync_run_v2[\s\S]*public\.retry_failed_ozon_sync_run_steps_v2\(v_run\.id\)/
  );
  assert.match(
    migration021,
    /failure_count = failure_count \+\s+CASE\s+WHEN p_state = 'retry_scheduled' THEN 1\s+WHEN p_state = 'failed'\s+AND COALESCE\(\(v_error ->> 'retryable'\)::boolean, false\)/
  );
  assert.match(migration021, /length\(v_text\) BETWEEN 1 AND 500/);
  assert.match(migration021, /authorization\|api\[-_ \]\?key/);
  assert.match(
    migration021,
    /checkpoint = CASE\s+WHEN p_state IN \('completed', 'skipped'\) THEN '\{\}'::jsonb\s+ELSE checkpoint/
  );
  assert.match(
    migration021,
    /WHERE run_id = p_run_id\s+AND state IN \('failed', 'retry_scheduled'\)/
  );
  assert.match(
    migration021,
    /step_key = ANY\(p_step_keys\)\s+AND state IN \('completed', 'skipped', 'failed'\)/
  );
  assert.match(
    migration021,
    /CREATE OR REPLACE FUNCTION public\.finish_ozon_sync_run_step_v2[\s\S]*DECLARE\s+v_step public\.marketplace_sync_run_steps%ROWTYPE;\s+v_error JSONB := public\._sanitize_ozon_sync_step_error\(p_last_error\);/
  );
  assert.doesNotMatch(
    migration021,
    /CREATE OR REPLACE FUNCTION public\.(?:checkpoint|yield)_ozon_sync_run_step_v2[\s\S]{0,500}p_last_error/
  );
  assert.match(
    migration021,
    /SET state = 'pending',[\s\S]*lease_token = NULL,[\s\S]*lease_expires_at = NULL/
  );
});

test("approved repair can reset legacy scheduled retries without a live lease", () => {
  assert.match(
    repairRetryMigration,
    /state IN \('completed', 'skipped', 'failed', 'retry_scheduled'\)/
  );
  assert.match(
    repairRetryMigration,
    /state = 'running'\s+AND lease_expires_at > clock_timestamp\(\)/
  );
  assert.match(
    reconcileScript,
    /\["completed", "skipped", "failed", "retry_scheduled"\]/
  );
});

test("migration 022 makes new inventory costs evidence-based without rebuilding history", () => {
  assert.match(migration022, /ALTER COLUMN unit_cost DROP NOT NULL/);
  assert.match(migration022, /cost_basis_status IN \('known', 'transferred', 'unknown'\)/);
  assert.match(migration022, /ALTER COLUMN cost_contract_version SET DEFAULT 1/);
  assert.match(
    migration022,
    /IF v_balance\.unit_cost IS NOT NULL THEN[\s\S]*COALESCE\(v_balance\.cost_basis_status, 'unknown'\)[\s\S]*v_unit_cost := COALESCE\(v_item\.unit_price, 0\);[\s\S]*v_cost_status := 'unknown'/
  );
  assert.match(
    migration022,
    /ELSE\s+v_cost_status := COALESCE\(v_balance\.cost_basis_status, 'unknown'\);\s+v_unit_cost := CASE\s+WHEN v_cost_status = 'unknown' THEN NULL\s+ELSE v_balance\.unit_cost/
  );
  assert.match(
    migration022,
    /CASE WHEN v_unit_cost IS NULL THEN NULL\s+ELSE v_item\.quantity \* v_unit_cost END/
  );
  assert.equal(
    migration022.match(/PERFORM public\.rebuild_inventory_reporting/g)?.length,
    1
  );
  assert.doesNotMatch(
    migration022,
    /UPDATE public\.(?:product_balances|inventory_movements)\s+SET cost_basis_status/
  );
  assert.match(
    migration022,
    /total_cost IS NULL OR cost_basis_status = 'unknown'/
  );
  assert.match(
    migration022,
    /im\.total_cost IS NULL OR im\.cost_basis_status = 'unknown'/
  );
});

test("migration 022 atomically validates evidence, mappings, and transfer symmetry", () => {
  assert.match(
    migration022,
    /CREATE OR REPLACE FUNCTION public\.replace_ozon_posting_with_items_v2[\s\S]*PERFORM public\.replace_ozon_posting_items_v2/
  );
  assert.match(
    migration022,
    /CREATE OR REPLACE FUNCTION public\.replace_ozon_supply_order_with_items_v2[\s\S]*PERFORM public\.replace_ozon_supply_order_items_v2/
  );
  assert.match(
    migration022,
    /CREATE OR REPLACE FUNCTION public\.commit_ozon_operation_candidate_v2/
  );
  assert.match(migration022, /pg_advisory_xact_lock/);
  assert.match(
    migration022,
    /v_candidate\.normalized_operation IS DISTINCT FROM p_operation/
  );
  assert.match(migration022, /Ozon transfer evidence is asymmetric/);
  assert.match(migration022, /PERFORM public\.rebuild_inventory_reporting\(p_workspace_id\)/);
  assert.match(
    migration022,
    /GRANT EXECUTE ON FUNCTION public\.commit_ozon_operation_candidate_v2[\s\S]*TO service_role/
  );
  assert.match(migration022, /cmd = 'ALL'/);
  assert.match(migration022, /FOR INSERT TO authenticated/);
  assert.match(migration022, /FOR UPDATE TO authenticated/);
  assert.match(migration022, /FOR DELETE TO authenticated/);
  assert.match(
    migration022,
    /DROP POLICY IF EXISTS "operation_items_write_admin"[\s\S]*CREATE POLICY "operation_items_insert_admin_v2"[\s\S]*CREATE POLICY "operation_items_update_admin_v2"[\s\S]*CREATE POLICY "operation_items_delete_admin_v2"/
  );
});

import { createHash } from "node:crypto";
import { createClient } from "@supabase/supabase-js";

const STEP_KEYS = [
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
] as const;

const MIRROR_TABLES = [
  "ozon_warehouses",
  "ozon_products",
  "ozon_stock_snapshots",
  "ozon_postings",
  "ozon_posting_items",
  "ozon_returns",
  "ozon_finance_transactions",
  "ozon_report_runs",
  "ozon_legal_entity_sales",
  "ozon_unpaid_legal_products",
  "ozon_finance_reports",
  "ozon_removals",
  "ozon_supply_orders",
  "ozon_supply_order_items",
  "ozon_stock_analytics",
  "ozon_turnover_analytics",
  "ozon_discounted_products",
] as const;

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.connectionId || !args.runId) {
    throw new Error(
      "Usage: npm run ozon:reconcile -- --connection <uuid> --run <uuid> [--apply --confirm <digest>]"
    );
  }
  if (!isUuid(args.connectionId) || !isUuid(args.runId)) {
    throw new Error("Connection and run IDs must be exact UUIDs");
  }
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!url || !serviceKey) {
    throw new Error("Supabase URL and service-role key are required");
  }
  const supabase = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const [connection, run, steps, returns, candidates] =
    await Promise.all([
      one(
        supabase
          .from("marketplace_connections")
          .select("id, workspace_id, provider, status")
          .eq("id", args.connectionId)
          .eq("provider", "ozon")
          .maybeSingle()
      ),
      one(
        supabase
          .from("marketplace_sync_runs")
          .select("id, workspace_id, connection_id, status, date_from, date_to")
          .eq("id", args.runId)
          .eq("connection_id", args.connectionId)
          .eq("provider", "ozon")
          .maybeSingle()
      ),
      many(
        supabase
          .from("marketplace_sync_run_steps")
          .select(
            "step_key,state,attempt_count,failure_count,next_attempt_at,lease_expires_at,last_error"
          )
          .eq("run_id", args.runId)
          .eq("connection_id", args.connectionId)
          .order("step_order")
      ),
      allPages((from, to) =>
        supabase
          .from("ozon_returns")
          .select(
            "id,status,returned_at,quantity,local_product_id,operation_candidate_id"
          )
          .eq("connection_id", args.connectionId!)
          .range(from, to)
      ),
      allPages((from, to) =>
        supabase
          .from("marketplace_operation_candidates")
          .select(
            "id,status,source_type,evidence_version,evidence_hash,created_operation_id"
          )
          .eq("connection_id", args.connectionId!)
          .eq("provider", "ozon")
          .range(from, to)
      ),
    ]);

  if (!connection || !run) throw new Error("Connection or run not found");
  if (
    run.connection_id !== connection.id ||
    run.workspace_id !== connection.workspace_id
  ) {
    throw new Error("Connection and run scope do not match");
  }

  const mirrorCoverage = await Promise.all(
    MIRROR_TABLES.map(async (table) => {
      const [total, currentContract, currentRun, superseded] =
        await Promise.all([
          exactCount(
            supabase
              .from(table)
              .select("id", { count: "exact", head: true })
              .eq("connection_id", args.connectionId!)
          ),
          exactCount(
            supabase
              .from(table)
              .select("id", { count: "exact", head: true })
              .eq("connection_id", args.connectionId!)
              .eq("source_contract_version", "seller-api-2026-07-27")
          ),
          exactCount(
            supabase
              .from(table)
              .select("id", { count: "exact", head: true })
              .eq("connection_id", args.connectionId!)
              .eq("last_sync_run_id", args.runId!)
          ),
          exactCount(
            supabase
              .from(table)
              .select("id", { count: "exact", head: true })
              .eq("connection_id", args.connectionId!)
              .not("superseded_at", "is", null)
          ),
        ]);
      return {
        table,
        total,
        currentContract,
        currentRun,
        legacyOrUnverified: total - currentContract,
        superseded,
      };
    })
  );

  const financeRows = await allPages((from, to) =>
    supabase
      .from("ozon_finance_transactions")
      .select(
        "transaction_id,raw_payload,source_contract_version,last_sync_run_id,superseded_at"
      )
      .eq("connection_id", args.connectionId!)
      .range(from, to)
  );
  const financeIdentityCoverage = financeCoverage(financeRows);

  const operationIds = [
    ...new Set(
      candidates
        .map((row) => row.created_operation_id)
        .filter((value): value is string => typeof value === "string")
    ),
  ];
  const operations = await rowsForIds(
    operationIds,
    (ids) =>
      supabase
        .from("operations")
        .select("id,type,cost_contract_version")
        .eq("workspace_id", connection.workspace_id)
        .in("id", ids)
  );
  const movementRows = await rowsForIds(
    operationIds,
    (ids) =>
      supabase
        .from("inventory_movements")
        .select(
          "id,operation_id,operation_item_id,operation_type,direction,quantity,unit_cost,total_cost,cost_basis_status"
        )
        .eq("workspace_id", connection.workspace_id)
        .in("operation_id", ids)
  );
  const operationItemIds = movementRows
    .map((row) => row.operation_item_id)
    .filter((value): value is string => typeof value === "string");
  const operationItems = await rowsForIds(
    operationItemIds,
    (ids) =>
      supabase
        .from("operation_items")
        .select("id,unit_price")
        .in("id", ids)
  );
  const operationItemPrice = new Map(
    operationItems.map((row) => [row.id, row.unit_price])
  );
  const [balanceCount, unknownBalanceCount] = await Promise.all([
    exactCount(
      supabase
        .from("product_balances")
        .select("id", { count: "exact", head: true })
        .eq("workspace_id", connection.workspace_id)
    ),
    exactCount(
      supabase
        .from("product_balances")
        .select("id", { count: "exact", head: true })
        .eq("workspace_id", connection.workspace_id)
        .or("unit_cost.is.null,cost_basis_status.eq.unknown")
    ),
  ]);
  const now = Date.now();
  const persistedStepKeys = steps.map((step) => String(step.step_key));
  const registryComplete =
    persistedStepKeys.length === STEP_KEYS.length &&
    STEP_KEYS.every((key) => persistedStepKeys.includes(key));
  const preview = {
    connectionId: connection.id,
    runId: run.id,
    workspaceId: connection.workspace_id,
    dateWindow: { from: run.date_from, to: run.date_to },
    runStatus: run.status,
    registryComplete,
    liveLeaseCount: steps.filter(
      (step) =>
        step.state === "running" &&
        typeof step.lease_expires_at === "string" &&
        Date.parse(step.lease_expires_at) > now
    ).length,
    stepStates: steps.map((step) => ({
      stepKey: step.step_key,
      state: step.state,
      executionCount: step.attempt_count,
      failureCount: step.failure_count,
      nextActionAt: step.next_attempt_at,
    })),
    mirrorCoverage,
    contractFindings: {
      incompleteReturnMirrorCount: returns.filter(
        (row) => !row.returned_at || !row.quantity || !row.local_product_id
      ).length,
      financeIdentityCoverage,
    },
    staleCandidateCount: candidates.filter(
      (row) => row.evidence_version !== 1 || !row.evidence_hash
    ).length,
    finalCandidateCount: candidates.filter((row) =>
      ["approved", "committing", "committed", "ignored"].includes(
        String(row.status)
      )
    ).length,
    committedOperationCount: operationIds.length,
    inventory: {
      linkedOperationCount: operations.length,
      legacyCostContractOperationCount: operations.filter(
        (row) => row.cost_contract_version !== 1
      ).length,
      linkedMovementCount: movementRows.length,
      unknownCostMovementCount: movementRows.filter(
        (row) =>
          row.cost_basis_status === "unknown" ||
          row.unit_cost === null ||
          row.total_cost === null
      ).length,
      valuedSaleOrWriteOffCount: movementRows.filter(
        (row) =>
          ["sale", "write_off"].includes(String(row.operation_type)) &&
          row.unit_cost !== null
      ).length,
      suspectedSalePriceAsCostCount: movementRows.filter((row) => {
        if (!["sale", "write_off", "return"].includes(String(row.operation_type))) {
          return false;
        }
        const invoicePrice = operationItemPrice.get(row.operation_item_id);
        return (
          row.unit_cost !== null &&
          invoicePrice !== null &&
          invoicePrice !== undefined &&
          canonicalDecimalText(row.unit_cost) ===
            canonicalDecimalText(invoicePrice)
        );
      }).length,
      balanceCount,
      unknownCostBalanceCount: unknownBalanceCount,
      historicalAfterValues: {
        available: false,
        reason:
          "Exact after-values require an isolated PostgreSQL ledger replay; this command does not mutate or simulate against production tables.",
      },
    },
  };
  const digest = createHash("sha256")
    .update(canonicalJson(preview))
    .digest("hex");

  process.stdout.write(
    `${JSON.stringify({ mode: args.apply ? "apply" : "preview", preview, digest }, null, 2)}\n`
  );

  if (!args.apply) return;
  if (args.confirm !== digest) {
    throw new Error("Apply refused: --confirm must exactly match the preview digest");
  }
  if (!registryComplete) {
    throw new Error("Apply refused: the run does not contain the exact step registry");
  }
  if (preview.liveLeaseCount > 0) {
    throw new Error("Apply refused: a live Ozon step lease exists");
  }
  if (
    steps.some(
      (step) =>
        !["completed", "skipped", "failed", "retry_scheduled"].includes(
          String(step.state)
        )
    )
  ) {
    throw new Error(
      "Apply refused: every selected step must be completed, skipped, failed, or retry-scheduled"
    );
  }
  const { error } = await supabase.rpc("repair_ozon_sync_run_steps_v2", {
    p_run_id: run.id,
    p_step_keys: [...STEP_KEYS],
  });
  if (error) throw new Error("Approved Ozon repair reset failed");
  process.stdout.write(
    `${JSON.stringify({
      applied: true,
      runId: run.id,
      action: "reset_selected_steps",
      note: "No source mirror or historical operation was deleted or rebuilt.",
    })}\n`
  );
}

function parseArgs(values: string[]) {
  const result: {
    connectionId?: string;
    runId?: string;
    apply: boolean;
    confirm?: string;
  } = { apply: false };
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--connection") result.connectionId = values[++index];
    else if (value === "--run") result.runId = values[++index];
    else if (value === "--confirm") result.confirm = values[++index];
    else if (value === "--apply") result.apply = true;
    else throw new Error(`Unknown argument: ${value}`);
  }
  return result;
}

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;
    return `{${Object.keys(record)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
}

function canonicalDecimalText(value: unknown) {
  const text = String(value ?? "").trim();
  const match = text.match(/^(-?)(\d+)(?:\.(\d+))?$/);
  if (!match) return text;
  const integer = match[2].replace(/^0+(?=\d)/, "");
  const fraction = (match[3] ?? "").replace(/0+$/, "");
  const sign = match[1] === "-" && (integer !== "0" || fraction) ? "-" : "";
  return `${sign}${integer}${fraction ? `.${fraction}` : ""}`;
}

function financeCoverage(rows: Array<Record<string, unknown>>) {
  const byAccrualId = new Map<string, Set<string>>();
  let identityMismatchCount = 0;
  for (const row of rows) {
    const raw = isRecord(row.raw_payload) ? row.raw_payload : {};
    const accrualId =
      typeof raw.accrual_id === "string" || typeof raw.accrual_id === "number"
        ? String(raw.accrual_id)
        : null;
    const transactionId =
      typeof row.transaction_id === "string" ? row.transaction_id : null;
    if (!accrualId || !transactionId) continue;
    if (accrualId !== transactionId) identityMismatchCount += 1;
    const ids = byAccrualId.get(accrualId) ?? new Set<string>();
    ids.add(transactionId);
    byAccrualId.set(accrualId, ids);
  }
  return {
    rowCount: rows.length,
    identityMismatchCount,
    duplicateAccrualCoverageCount: [...byAccrualId.values()].filter(
      (ids) => ids.size > 1
    ).length,
  };
}

async function exactCount(
  query: PromiseLike<{
    count: number | null;
    error: { message: string } | null;
  }>
) {
  const { count, error } = await query;
  if (error) throw new Error(error.message);
  return count ?? 0;
}

async function allPages(
  load: (
    from: number,
    to: number
  ) => PromiseLike<{
    data: unknown;
    error: { message: string } | null;
  }>
) {
  const result: Array<Record<string, unknown>> = [];
  for (let from = 0; ; from += 1000) {
    const { data, error } = await load(from, from + 999);
    if (error) throw new Error(error.message);
    const page = (data ?? []) as Array<Record<string, unknown>>;
    result.push(...page);
    if (page.length < 1000) return result;
  }
}

async function rowsForIds(
  ids: string[],
  load: (
    ids: string[]
  ) => PromiseLike<{
    data: unknown;
    error: { message: string } | null;
  }>
) {
  const rows: Array<Record<string, unknown>> = [];
  for (let index = 0; index < ids.length; index += 100) {
    const { data, error } = await load(ids.slice(index, index + 100));
    if (error) throw new Error(error.message);
    rows.push(...((data ?? []) as Array<Record<string, unknown>>));
  }
  return rows;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    value
  );
}

async function one(query: PromiseLike<{ data: unknown; error: { message: string } | null }>) {
  const { data, error } = await query;
  if (error) throw new Error(error.message);
  return data as Record<string, unknown> | null;
}

async function many(
  query: PromiseLike<{ data: unknown; error: { message: string } | null }>
) {
  const { data, error } = await query;
  if (error) throw new Error(error.message);
  return (data ?? []) as Array<Record<string, unknown>>;
}

main().catch((error) => {
  process.stderr.write(
    `${error instanceof Error ? error.message : "Ozon reconciliation failed"}\n`
  );
  process.exitCode = 1;
});

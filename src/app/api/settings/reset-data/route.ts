import { NextRequest, NextResponse } from "next/server";
import type { SupabaseClient } from "@supabase/supabase-js";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

const CONFIRMATION = "RESET";

type CountGroup =
  | "operations"
  | "imports"
  | "reports"
  | "masterData"
  | "balances"
  | "legacyCommerce"
  | "marketplace";

type ResetCountItem = {
  key: string;
  table: string;
  group: CountGroup;
  join?: string;
};

const COUNT_TABLES = [
  { key: "operations", table: "operations", group: "operations" },
  {
    key: "operationItems",
    table: "operation_items",
    group: "operations",
    join: "operations",
  },
  { key: "operationImports", table: "operation_imports", group: "imports" },
  {
    key: "operationImportCandidates",
    table: "operation_import_candidates",
    group: "imports",
  },
  {
    key: "operationImportCommittedOperations",
    table: "operation_import_committed_operations",
    group: "imports",
  },
  {
    key: "operationImportFingerprints",
    table: "operation_import_fingerprints",
    group: "imports",
  },
  { key: "legacyImports", table: "imports", group: "imports" },
  {
    key: "legacyImportErrors",
    table: "import_errors",
    group: "imports",
    join: "imports",
  },
  { key: "reportTemplates", table: "report_templates", group: "reports" },
  { key: "products", table: "products", group: "masterData" },
  { key: "categories", table: "categories", group: "masterData" },
  { key: "stores", table: "stores", group: "masterData" },
  { key: "warehouses", table: "warehouses", group: "masterData" },
  { key: "suppliers", table: "suppliers", group: "masterData" },
  { key: "productBalances", table: "product_balances", group: "balances" },
  { key: "inventoryMovements", table: "inventory_movements", group: "balances" },
  { key: "inventorySnapshots", table: "inventory_snapshots", group: "balances" },
  { key: "orders", table: "orders", group: "legacyCommerce" },
  {
    key: "orderLines",
    table: "order_lines",
    group: "legacyCommerce",
    join: "orders",
  },
  { key: "payments", table: "payments", group: "legacyCommerce" },
  {
    key: "marketplaceCommitClaims",
    table: "marketplace_operation_commit_claims",
    group: "marketplace",
  },
  {
    key: "marketplaceCandidates",
    table: "marketplace_operation_candidates",
    group: "marketplace",
  },
  {
    key: "marketplaceSyncRuns",
    table: "marketplace_sync_runs",
    group: "marketplace",
  },
  { key: "ozonProducts", table: "ozon_products", group: "marketplace" },
  { key: "ozonWarehouses", table: "ozon_warehouses", group: "marketplace" },
  {
    key: "ozonStockSnapshots",
    table: "ozon_stock_snapshots",
    group: "marketplace",
  },
  { key: "ozonPostings", table: "ozon_postings", group: "marketplace" },
  {
    key: "ozonPostingItems",
    table: "ozon_posting_items",
    group: "marketplace",
  },
  { key: "ozonReturns", table: "ozon_returns", group: "marketplace" },
  {
    key: "ozonFinanceTransactions",
    table: "ozon_finance_transactions",
    group: "marketplace",
  },
  { key: "ozonReports", table: "ozon_report_runs", group: "marketplace" },
  {
    key: "ozonLegalEntitySales",
    table: "ozon_legal_entity_sales",
    group: "marketplace",
  },
  {
    key: "ozonUnpaidLegalProducts",
    table: "ozon_unpaid_legal_products",
    group: "marketplace",
  },
  {
    key: "ozonFinanceReports",
    table: "ozon_finance_reports",
    group: "marketplace",
  },
  { key: "ozonRemovals", table: "ozon_removals", group: "marketplace" },
  { key: "ozonSupplies", table: "ozon_supply_orders", group: "marketplace" },
  {
    key: "ozonSupplyOrderItems",
    table: "ozon_supply_order_items",
    group: "marketplace",
  },
  {
    key: "ozonStockAnalytics",
    table: "ozon_stock_analytics",
    group: "marketplace",
  },
  {
    key: "ozonTurnoverAnalytics",
    table: "ozon_turnover_analytics",
    group: "marketplace",
  },
  {
    key: "ozonDiscountedProducts",
    table: "ozon_discounted_products",
    group: "marketplace",
  },
] as const satisfies readonly ResetCountItem[];

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId, role } = await getRouteContext(request);
    const { counts, groups, total } = await getResetCounts(
      supabase,
      workspaceId
    );

    return NextResponse.json({
      canReset: role === "owner",
      role,
      confirmation: CONFIRMATION,
      counts,
      groups,
      total,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId, role } = await getRouteContext(request);

    if (role !== "owner") {
      return NextResponse.json(
        { error: "Only workspace owners can reset account data" },
        { status: 403 }
      );
    }

    const body = await request.json();
    if (body.confirmation !== CONFIRMATION) {
      return NextResponse.json(
        { error: "RESET confirmation is required" },
        { status: 400 }
      );
    }

    const { data, error } = await supabase.rpc("reset_workspace_account_data", {
      p_workspace_id: workspaceId,
      p_confirmation: CONFIRMATION,
    });

    if (error) {
      if (error.code === "22023") {
        return NextResponse.json({ error: error.message }, { status: 400 });
      }
      if (error.code === "42501") {
        return NextResponse.json({ error: error.message }, { status: 403 });
      }
      if (isMissingResetSchemaError(error)) {
        return NextResponse.json(
          { error: "Run the workspace reset migration before using this action." },
          { status: 409 }
        );
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({ ok: true, result: data });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

async function getResetCounts(supabase: SupabaseClient, workspaceId: string) {
  const counts: Record<string, number> = {};
  const groups: Record<CountGroup, number> = {
    operations: 0,
    imports: 0,
    reports: 0,
    masterData: 0,
    balances: 0,
    legacyCommerce: 0,
    marketplace: 0,
  };

  for (const item of COUNT_TABLES) {
    const value = await countWorkspaceRows(supabase, item, workspaceId);
    counts[item.key] = value;
    groups[item.group] += value;
  }

  return {
    counts,
    groups,
    total: Object.values(groups).reduce((sum, value) => sum + value, 0),
  };
}

async function countWorkspaceRows(
  supabase: SupabaseClient,
  item: ResetCountItem,
  workspaceId: string
) {
  const selectColumns = item.join ? `id, ${item.join}!inner(workspace_id)` : "id";
  let query = supabase
    .from(item.table)
    .select(selectColumns, { count: "exact", head: true });
  query = item.join
    ? query.eq(`${item.join}.workspace_id`, workspaceId)
    : query.eq("workspace_id", workspaceId);

  const { count, error } = await query;

  if (error) {
    if (isMissingResetSchemaError(error)) return 0;
    throw error;
  }

  return count || 0;
}

function isMissingResetSchemaError(error: { code?: string; message?: string }) {
  const message = error.message || "";
  return (
    error.code === "42P01" ||
    error.code === "PGRST202" ||
    error.code === "PGRST205" ||
    message.includes("does not exist") ||
    message.includes("schema cache") ||
    message.includes("reset_workspace_account_data")
  );
}

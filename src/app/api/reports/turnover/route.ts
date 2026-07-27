import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type { TurnoverReport, TurnoverRow } from "@/types/inventory";

export const dynamic = "force-dynamic";

interface TurnoverRpcRow {
  group_id: string;
  group_name: string;
  sku_code: string | null;
  outflow_cost: number | string | null;
  average_inventory_cost: number | string | null;
  turnover_ratio: number | string | null;
  turnover_days: number | string | null;
}

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);
    const from = searchParams.get("from");
    const to = searchParams.get("to");
    const groupBy = searchParams.get("groupBy") || "product";

    if (!from || !to) {
      return NextResponse.json(
        { error: "from and to date parameters are required" },
        { status: 400 }
      );
    }

    const { data, error } = await supabase.rpc("report_turnover_v2", {
      p_workspace_id: workspaceId,
      p_from: from,
      p_to: to,
      p_group_by: groupBy,
      p_product_id: searchParams.get("productId"),
      p_category_id: searchParams.get("categoryId"),
      p_warehouse_id: searchParams.get("warehouseId"),
      p_store_id: searchParams.get("storeId"),
    });
    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const rows: TurnoverRow[] = ((data ?? []) as TurnoverRpcRow[]).map(
      (row) => ({
        groupId: row.group_id,
        groupName: row.group_name,
        skuCode: row.sku_code,
        outflowCost: nullableNumericValue(row.outflow_cost),
        averageInventoryCost: nullableNumericValue(
          row.average_inventory_cost
        ),
        turnoverRatio: nullableNumericValue(row.turnover_ratio),
        turnoverDays: nullableNumericValue(row.turnover_days),
      })
    );
    const report: TurnoverReport = { from, to, groupBy, rows };
    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

function nullableNumericValue(value: number | string | null) {
  if (value === null) return null;
  return typeof value === "number" ? value : Number(value);
}

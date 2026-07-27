import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type { DefectDynamicsReport, DefectDynamicsRow } from "@/types/inventory";

export const dynamic = "force-dynamic";

interface DefectRpcRow {
  group_id: string;
  group_name: string;
  sku_code: string | null;
  defect_in_quantity: number | string;
  defect_out_quantity: number | string;
  defect_balance_delta: number | string;
  defect_cost: number | string | null;
  grand_defect_in_quantity: number | string;
  grand_defect_out_quantity: number | string;
  grand_defect_cost: number | string | null;
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

    const { data, error } = await supabase.rpc(
      "report_defect_dynamics_v2",
      {
        p_workspace_id: workspaceId,
        p_from: from,
        p_to: to,
        p_group_by: groupBy,
        p_product_id: searchParams.get("productId"),
        p_category_id: searchParams.get("categoryId"),
        p_warehouse_id: searchParams.get("warehouseId"),
        p_store_id: searchParams.get("storeId"),
      }
    );
    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const rpcRows = (data ?? []) as DefectRpcRow[];
    const rows: DefectDynamicsRow[] = rpcRows.map((row) => ({
      groupId: row.group_id,
      groupName: row.group_name,
      skuCode: row.sku_code,
      defectInQuantity: numericValue(row.defect_in_quantity),
      defectOutQuantity: numericValue(row.defect_out_quantity),
      defectBalanceDelta: numericValue(row.defect_balance_delta),
      defectCost: nullableNumericValue(row.defect_cost),
    }));
    const totals = rpcRows[0];
    const report: DefectDynamicsReport = {
      from,
      to,
      groupBy,
      rows,
      totals: {
        defectInQuantity: totals
          ? numericValue(totals.grand_defect_in_quantity)
          : 0,
        defectOutQuantity: totals
          ? numericValue(totals.grand_defect_out_quantity)
          : 0,
        defectCost: totals
          ? nullableNumericValue(totals.grand_defect_cost)
          : 0,
      },
    };
    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

function numericValue(value: number | string) {
  return typeof value === "number" ? value : Number(value);
}

function nullableNumericValue(value: number | string | null) {
  return value === null ? null : numericValue(value);
}

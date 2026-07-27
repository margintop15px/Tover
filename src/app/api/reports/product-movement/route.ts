import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type {
  ProductMovementReport,
  ProductMovementRow,
  QualityStatus,
} from "@/types/inventory";

export const dynamic = "force-dynamic";

interface MovementRpcRow {
  group_id: string;
  group_name: string;
  sku_code: string | null;
  quality_status: QualityStatus | null;
  purchase_in: number | string;
  purchase_in_cost: number | string | null;
  sale_out: number | string;
  sale_out_cost: number | string | null;
  return_in: number | string;
  return_in_cost: number | string | null;
  write_off_out: number | string;
  write_off_out_cost: number | string | null;
  transfer_in: number | string;
  transfer_in_cost: number | string | null;
  transfer_out: number | string;
  transfer_out_cost: number | string | null;
  production_in: number | string;
  production_in_cost: number | string | null;
  production_out: number | string;
  production_out_cost: number | string | null;
  defect_out: number | string;
  defect_out_cost: number | string | null;
  inventory_adjustment_in: number | string;
  inventory_adjustment_in_cost: number | string | null;
  net: number | string;
  net_cost: number | string | null;
  has_negative: boolean;
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
      "report_product_movement_v2",
      {
        p_workspace_id: workspaceId,
        p_from: from,
        p_to: to,
        p_group_by: groupBy,
        p_product_id: searchParams.get("productId"),
        p_category_id: searchParams.get("categoryId"),
        p_warehouse_id: searchParams.get("warehouseId"),
        p_store_id: searchParams.get("storeId"),
        p_quality_status: searchParams.get("qualityStatus"),
      }
    );
    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const rows: ProductMovementRow[] = ((data ?? []) as MovementRpcRow[]).map(
      (row) => ({
        groupId: row.group_id,
        groupName: row.group_name,
        skuCode: row.sku_code,
        qualityStatus: row.quality_status,
        purchaseIn: numericValue(row.purchase_in),
        purchaseInCost: nullableNumericValue(row.purchase_in_cost),
        saleOut: numericValue(row.sale_out),
        saleOutCost: nullableNumericValue(row.sale_out_cost),
        returnIn: numericValue(row.return_in),
        returnInCost: nullableNumericValue(row.return_in_cost),
        writeOffOut: numericValue(row.write_off_out),
        writeOffOutCost: nullableNumericValue(row.write_off_out_cost),
        transferIn: numericValue(row.transfer_in),
        transferInCost: nullableNumericValue(row.transfer_in_cost),
        transferOut: numericValue(row.transfer_out),
        transferOutCost: nullableNumericValue(row.transfer_out_cost),
        productionIn: numericValue(row.production_in),
        productionInCost: nullableNumericValue(row.production_in_cost),
        productionOut: numericValue(row.production_out),
        productionOutCost: nullableNumericValue(row.production_out_cost),
        defectOut: numericValue(row.defect_out),
        defectOutCost: nullableNumericValue(row.defect_out_cost),
        inventoryAdjustmentIn: numericValue(row.inventory_adjustment_in),
        inventoryAdjustmentInCost: nullableNumericValue(
          row.inventory_adjustment_in_cost
        ),
        net: numericValue(row.net),
        netCost: nullableNumericValue(row.net_cost),
        hasNegative: row.has_negative,
      })
    );

    const report: ProductMovementReport = { from, to, groupBy, rows };
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

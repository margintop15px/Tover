import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type {
  InventoryBalanceCell,
  InventoryBalanceRow,
  InventoryBalancesReport,
  QualityStatus,
} from "@/types/inventory";

export const dynamic = "force-dynamic";

interface InventoryBalanceRpcRow {
  product_id: string;
  product_name: string;
  sku_code: string | null;
  category_name: string | null;
  store_id: string | null;
  store_name: string | null;
  quality_status: QualityStatus;
  warehouses: Array<{
    warehouseId: string;
    warehouseName: string;
    qualityStatus: QualityStatus;
    quantity: number | string;
    totalCost: number | string | null;
    hasNegative: boolean;
  }>;
  total_quantity: number | string;
  total_cost: number | string | null;
  has_negative: boolean;
  grand_total_quantity: number | string;
  grand_total_cost: number | string | null;
  grand_has_negative: boolean;
}

interface GroupedInventoryBalanceRpcRow {
  group_id: string;
  group_name: string;
  sku_code: string | null;
  total_quantity: number | string;
  total_cost: number | string | null;
  has_negative: boolean;
  grand_total_quantity: number | string;
  grand_total_cost: number | string | null;
  grand_has_negative: boolean;
}

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);
    const asOfDate =
      searchParams.get("date") || new Date().toISOString().split("T")[0];
    const groupBy = searchParams.get("groupBy");
    const commonRpcArgs = {
      p_workspace_id: workspaceId,
      p_target_date: asOfDate,
      p_product_id: searchParams.get("productId"),
      p_category_id: searchParams.get("categoryId"),
      p_warehouse_id: searchParams.get("warehouseId"),
      p_store_id: searchParams.get("storeId"),
      p_quality_status: searchParams.get("qualityStatus"),
      p_search: searchParams.get("search"),
      p_hide_zeros: searchParams.get("hideZeros") === "true",
      p_negatives_only: searchParams.get("negativesOnly") === "true",
    };

    if (groupBy) {
      const { data, error } = await supabase.rpc(
        "report_inventory_balances_grouped_v2",
        { ...commonRpcArgs, p_group_by: groupBy }
      );
      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }
      const groupedRows = (data ?? []) as GroupedInventoryBalanceRpcRow[];
      const totals = groupedRows[0];
      return NextResponse.json({
        asOfDate,
        rows: groupedRows.map((row) => ({
          groupId: row.group_id,
          groupName: row.group_name,
          skuCode: row.sku_code,
          totalQuantity: numericValue(row.total_quantity),
          totalCost: nullableNumericValue(row.total_cost),
          hasNegative: row.has_negative,
        })),
        totals: {
          totalQuantity: totals
            ? numericValue(totals.grand_total_quantity)
            : 0,
          totalCost: totals
            ? nullableNumericValue(totals.grand_total_cost)
            : 0,
          hasNegative: totals?.grand_has_negative ?? false,
        },
      });
    }

    const { data, error } = await supabase.rpc(
      "report_inventory_balances_v2",
      commonRpcArgs
    );
    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const rpcRows = (data ?? []) as InventoryBalanceRpcRow[];
    const warehouseColumns = new Map<string, string>();
    const rows: InventoryBalanceRow[] = rpcRows.map((row) => {
      const warehouses: InventoryBalanceCell[] = (row.warehouses ?? []).map(
        (warehouse) => {
          warehouseColumns.set(
            warehouse.warehouseId,
            warehouse.warehouseName
          );
          return {
            warehouseId: warehouse.warehouseId,
            warehouseName: warehouse.warehouseName,
            qualityStatus: warehouse.qualityStatus,
            quantity: numericValue(warehouse.quantity),
            totalCost: nullableNumericValue(warehouse.totalCost),
            hasNegative: warehouse.hasNegative,
          };
        }
      );
      return {
        productId: row.product_id,
        productName: row.product_name,
        skuCode: row.sku_code,
        categoryName: row.category_name,
        storeId: row.store_id,
        storeName: row.store_name,
        qualityStatus: row.quality_status,
        warehouses,
        totalQuantity: numericValue(row.total_quantity),
        totalCost: nullableNumericValue(row.total_cost),
        hasNegative: row.has_negative,
      };
    });
    const totals = rpcRows[0];
    const report: InventoryBalancesReport = {
      asOfDate,
      warehouseColumns: [...warehouseColumns.entries()]
        .map(([id, name]) => ({ id, name }))
        .sort((left, right) => left.name.localeCompare(right.name)),
      rows,
      totals: {
        totalQuantity: totals
          ? numericValue(totals.grand_total_quantity)
          : 0,
        totalCost: totals
          ? nullableNumericValue(totals.grand_total_cost)
          : 0,
        hasNegative: totals?.grand_has_negative ?? false,
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

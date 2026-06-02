import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { loadReportLookups } from "@/lib/reports/lookups";
import type { TurnoverReport, TurnoverRow } from "@/types/inventory";

export const dynamic = "force-dynamic";

const DAY_MS = 86400000;

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);
    const from = searchParams.get("from");
    const to = searchParams.get("to");
    const groupBy = searchParams.get("groupBy") || "product";
    const productId = searchParams.get("productId");
    const categoryId = searchParams.get("categoryId");
    const warehouseId = searchParams.get("warehouseId");
    const storeId = searchParams.get("storeId");

    if (!from || !to) {
      return NextResponse.json({ error: "from and to date parameters are required" }, { status: 400 });
    }

    let movementQuery = supabase
      .from("inventory_movements")
      .select("product_id, warehouse_id, store_id, operation_type, direction, total_cost")
      .eq("workspace_id", workspaceId)
      .gte("operation_date", from)
      .lte("operation_date", to);

    if (productId) movementQuery = movementQuery.eq("product_id", productId);
    if (warehouseId) movementQuery = movementQuery.eq("warehouse_id", warehouseId);
    if (storeId) movementQuery = movementQuery.eq("store_id", storeId);

    const { data, error } = await movementQuery;
    if (error) return NextResponse.json({ error: error.message }, { status: 500 });
    const movements = data || [];
    const lookups = await loadReportLookups(supabase, workspaceId, movements);

    const days = Math.max(
      1,
      Math.floor((Date.parse(to) - Date.parse(from)) / DAY_MS) + 1
    );
    const rowsByGroup = new Map<string, TurnoverRow>();

    for (const movement of movements) {
      const product = lookups.products.get(movement.product_id);
      if (!product || product.is_defect_copy) continue;
      if (categoryId && product.category_id !== categoryId) continue;

      const movementStore = movement.store_id
        ? lookups.stores.get(movement.store_id)
        : null;
      const productStore = product.store_id
        ? lookups.stores.get(product.store_id)
        : null;
      const warehouse = lookups.warehouses.get(movement.warehouse_id);
      const effectiveStoreId = movement.store_id || product.store_id || "unassigned";
      const effectiveStoreName = movementStore?.name || productStore?.name || "No store";

      const groupId =
        groupBy === "warehouse"
          ? movement.warehouse_id
          : groupBy === "store"
            ? effectiveStoreId
            : movement.product_id;
      const groupName =
        groupBy === "warehouse"
          ? warehouse?.name || "Unknown"
          : groupBy === "store"
            ? effectiveStoreName
            : product.name;

      let row = rowsByGroup.get(groupId);
      if (!row) {
        row = {
          groupId,
          groupName,
          skuCode: groupBy === "product" ? product.sku_code : null,
          outflowCost: 0,
          averageInventoryCost: 0,
          turnoverRatio: null,
          turnoverDays: null,
        };
        rowsByGroup.set(groupId, row);
      }

      if (
        movement.direction === "out" &&
        ["sale", "write_off", "defect"].includes(movement.operation_type)
      ) {
        row.outflowCost += Number(movement.total_cost);
      }
    }

    const { data: balances, error: balancesError } = await supabase.rpc(
      "report_inventory_balances_at_date",
      { p_workspace_id: workspaceId, p_target_date: to }
    );
    if (balancesError) {
      return NextResponse.json({ error: balancesError.message }, { status: 500 });
    }

    for (const balance of balances || []) {
      const product = lookups.products.get(balance.product_id);
      const effectiveStoreId = balance.store_id || product?.store_id || "unassigned";
      if (productId && balance.product_id !== productId) continue;
      if (categoryId && product?.category_id !== categoryId) continue;
      if (warehouseId && balance.warehouse_id !== warehouseId) continue;
      if (storeId && effectiveStoreId !== storeId) continue;

      const groupId =
        groupBy === "warehouse"
          ? balance.warehouse_id
          : groupBy === "store"
            ? effectiveStoreId
            : balance.product_id;
      const row = rowsByGroup.get(groupId);
      if (row) row.averageInventoryCost += Number(balance.total_cost);
    }

    const rows = Array.from(rowsByGroup.values()).map((row) => {
      const ratio =
        row.averageInventoryCost > 0 ? row.outflowCost / row.averageInventoryCost : null;
      return {
        ...row,
        turnoverRatio: ratio,
        turnoverDays: ratio && ratio > 0 ? days / ratio : null,
      };
    });
    rows.sort((a, b) => b.outflowCost - a.outflowCost);

    const report: TurnoverReport = { from, to, groupBy, rows };
    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

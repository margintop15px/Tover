import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { loadReportLookups } from "@/lib/reports/lookups";
import type { DefectDynamicsReport, DefectDynamicsRow } from "@/types/inventory";

export const dynamic = "force-dynamic";

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

    let query = supabase
      .from("inventory_movements")
      .select("product_id, warehouse_id, store_id, direction, quantity, total_cost")
      .eq("workspace_id", workspaceId)
      .eq("quality_status", "defect")
      .gte("operation_date", from)
      .lte("operation_date", to);

    if (productId) query = query.eq("product_id", productId);
    if (warehouseId) query = query.eq("warehouse_id", warehouseId);
    if (storeId) query = query.eq("store_id", storeId);

    const { data, error } = await query;
    if (error) return NextResponse.json({ error: error.message }, { status: 500 });
    const movements = data || [];
    const lookups = await loadReportLookups(supabase, workspaceId, movements);

    const rowsByGroup = new Map<string, DefectDynamicsRow>();

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
          defectInQuantity: 0,
          defectOutQuantity: 0,
          defectBalanceDelta: 0,
          defectCost: 0,
        };
        rowsByGroup.set(groupId, row);
      }

      const quantity = Number(movement.quantity);
      const cost = Number(movement.total_cost);
      if (movement.direction === "in") {
        row.defectInQuantity += quantity;
        row.defectCost += cost;
      } else {
        row.defectOutQuantity += quantity;
        row.defectCost -= cost;
      }
    }

    const rows = Array.from(rowsByGroup.values()).map((row) => ({
      ...row,
      defectBalanceDelta: row.defectInQuantity - row.defectOutQuantity,
    }));
    rows.sort((a, b) => b.defectCost - a.defectCost);

    const report: DefectDynamicsReport = {
      from,
      to,
      groupBy,
      rows,
      totals: {
        defectInQuantity: rows.reduce((sum, row) => sum + row.defectInQuantity, 0),
        defectOutQuantity: rows.reduce((sum, row) => sum + row.defectOutQuantity, 0),
        defectCost: rows.reduce((sum, row) => sum + row.defectCost, 0),
      },
    };

    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

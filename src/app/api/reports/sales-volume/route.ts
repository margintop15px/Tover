import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { loadReportLookups } from "@/lib/reports/lookups";
import type { SalesVolumeReport, SalesVolumeRow } from "@/types/inventory";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);
    const from = searchParams.get("from");
    const to = searchParams.get("to");
    const groupBy = searchParams.get("groupBy") || "store";
    const productId = searchParams.get("productId");
    const warehouseId = searchParams.get("warehouseId");
    const storeId = searchParams.get("storeId");

    if (!from || !to) {
      return NextResponse.json({ error: "from and to date parameters are required" }, { status: 400 });
    }

    let query = supabase
      .from("inventory_movements")
      .select("product_id, warehouse_id, store_id, operation_type, direction, quantity")
      .eq("workspace_id", workspaceId)
      .gte("operation_date", from)
      .lte("operation_date", to)
      .in("operation_type", ["sale", "return"]);

    if (productId) query = query.eq("product_id", productId);
    if (warehouseId) query = query.eq("warehouse_id", warehouseId);
    if (storeId) query = query.eq("store_id", storeId);

    const { data, error } = await query;
    if (error) return NextResponse.json({ error: error.message }, { status: 500 });
    const movements = data || [];
    const lookups = await loadReportLookups(supabase, workspaceId, movements);

    const rowsByGroup = new Map<string, SalesVolumeRow>();

    for (const movement of movements) {
      const product = lookups.products.get(movement.product_id);
      if (!product || product.is_defect_copy) continue;

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
        groupBy === "product"
          ? movement.product_id
          : groupBy === "warehouse"
            ? movement.warehouse_id
            : effectiveStoreId;
      const groupName =
        groupBy === "product"
          ? product.name
          : groupBy === "warehouse"
            ? warehouse?.name || "Unknown"
            : effectiveStoreName;

      let row = rowsByGroup.get(groupId);
      if (!row) {
        row = {
          groupId,
          groupName,
          skuCode: groupBy === "product" ? product.sku_code : null,
          soldQuantity: 0,
          returnedQuantity: 0,
          netSoldQuantity: 0,
          shareOfStoreSales: 0,
        };
        rowsByGroup.set(groupId, row);
      }

      const quantity = Number(movement.quantity);
      if (movement.operation_type === "sale" && movement.direction === "out") {
        row.soldQuantity += quantity;
      } else if (movement.operation_type === "return" && movement.direction === "in") {
        row.returnedQuantity += quantity;
      }
    }

    const rows = Array.from(rowsByGroup.values()).map((row) => ({
      ...row,
      netSoldQuantity: row.soldQuantity - row.returnedQuantity,
    }));
    const totalNet = rows.reduce((sum, row) => sum + row.netSoldQuantity, 0);
    for (const row of rows) {
      row.shareOfStoreSales = totalNet > 0 ? row.netSoldQuantity / totalNet : 0;
    }
    rows.sort((a, b) => b.netSoldQuantity - a.netSoldQuantity);

    const report: SalesVolumeReport = {
      from,
      to,
      groupBy,
      rows,
      totals: {
        soldQuantity: rows.reduce((sum, row) => sum + row.soldQuantity, 0),
        returnedQuantity: rows.reduce((sum, row) => sum + row.returnedQuantity, 0),
        netSoldQuantity: rows.reduce((sum, row) => sum + row.netSoldQuantity, 0),
      },
    };

    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

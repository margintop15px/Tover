import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type { ProductMovementRow, ProductMovementReport } from "@/types/inventory";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const from = searchParams.get("from");
    const to = searchParams.get("to");
    const groupBy = searchParams.get("groupBy") || "product";
    const productId = searchParams.get("productId");
    const warehouseId = searchParams.get("warehouseId");

    if (!from || !to) {
      return NextResponse.json(
        { error: "from and to date parameters are required" },
        { status: 400 }
      );
    }

    const { data: rpcData, error: rpcError } = await supabase.rpc(
      "report_product_movement",
      { p_workspace_id: workspaceId, p_from: from, p_to: to }
    );

    if (rpcError) {
      return NextResponse.json({ error: rpcError.message }, { status: 500 });
    }

    // Collect unique IDs for name lookups
    const productIds = new Set<string>();
    const warehouseIds = new Set<string>();

    for (const row of rpcData || []) {
      productIds.add(row.product_id);
      warehouseIds.add(row.warehouse_id);
    }

    // Fetch names in parallel
    const [productsRes, warehousesRes] = await Promise.all([
      productIds.size > 0
        ? supabase
            .from("products")
            .select("id, name, sku_code, is_defect_copy")
            .in("id", Array.from(productIds))
        : { data: [] },
      warehouseIds.size > 0
        ? supabase
            .from("warehouses")
            .select("id, name")
            .in("id", Array.from(warehouseIds))
        : { data: [] },
    ]);

    const productNames = new Map<string, { name: string; sku_code: string | null; is_defect_copy: boolean }>();
    for (const p of productsRes.data || []) {
      productNames.set(p.id, { name: p.name, sku_code: p.sku_code, is_defect_copy: p.is_defect_copy });
    }

    const warehouseNames = new Map<string, string>();
    for (const w of warehousesRes.data || []) {
      warehouseNames.set(w.id, w.name);
    }

    // Filter raw rows
    let filtered = (rpcData || []).filter((row: { product_id: string; operation_type: string }) => {
      if (row.operation_type === "inventory_adjustment") return false;
      const prod = productNames.get(row.product_id);
      if (prod?.is_defect_copy) return false;
      return !(productId && row.product_id !== productId);

    });
    if (warehouseId) {
      filtered = filtered.filter((row: { warehouse_id: string }) => row.warehouse_id === warehouseId);
    }

    // Group and pivot
    const groupMap = new Map<string, ProductMovementRow>();

    for (const row of filtered) {
      const groupId = groupBy === "product" ? row.product_id : row.warehouse_id;
      const prod = productNames.get(row.product_id);
      const groupName = groupBy === "product"
        ? (prod?.name ?? "Unknown")
        : (warehouseNames.get(row.warehouse_id) ?? "Unknown");
      const skuCode = groupBy === "product" ? (prod?.sku_code ?? null) : null;

      let entry = groupMap.get(groupId);
      if (!entry) {
        entry = {
          groupId,
          groupName,
          skuCode,
          purchaseIn: 0,
          saleOut: 0,
          returnIn: 0,
          writeOffOut: 0,
          transferIn: 0,
          transferOut: 0,
          productionIn: 0,
          productionOut: 0,
          defectOut: 0,
          inventoryAdjustmentIn: 0,
          net: 0,
        };
        groupMap.set(groupId, entry);
      }

      const qty = Number(row.total_quantity);
      const type = row.operation_type as string;
      const dir = row.direction as string;

      if (type === "purchase" && dir === "in") entry.purchaseIn += qty;
      else if (type === "sale" && dir === "out") entry.saleOut += qty;
      else if (type === "return" && dir === "in") entry.returnIn += qty;
      else if (type === "write_off" && dir === "out") entry.writeOffOut += qty;
      else if (type === "transfer" && dir === "in") entry.transferIn += qty;
      else if (type === "transfer" && dir === "out") entry.transferOut += qty;
      else if (type === "production" && dir === "in") entry.productionIn += qty;
      else if (type === "production" && dir === "out") entry.productionOut += qty;
      else if (type === "defect" && dir === "out") entry.defectOut += qty;
    }

    // Compute net for each row
    const rows = Array.from(groupMap.values()).map((r) => ({
      ...r,
      net:
        r.purchaseIn + r.returnIn + r.transferIn + r.productionIn
        - r.saleOut - r.writeOffOut - r.transferOut - r.productionOut - r.defectOut,
    }));

    rows.sort((a, b) => a.groupName.localeCompare(b.groupName));

    const report: ProductMovementReport = {
      from,
      to,
      groupBy,
      rows,
    };

    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

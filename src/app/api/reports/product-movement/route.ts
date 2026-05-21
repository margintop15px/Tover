import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type {
  ProductMovementReport,
  ProductMovementRow,
  QualityStatus,
} from "@/types/inventory";

export const dynamic = "force-dynamic";

type MovementRpcRow = {
  product_id: string;
  warehouse_id: string;
  store_id: string | null;
  quality_status: string;
  operation_type: string;
  direction: string;
  total_quantity: number;
  total_cost: number;
  has_negative: boolean;
};

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const from = searchParams.get("from");
    const to = searchParams.get("to");
    const groupBy = searchParams.get("groupBy") || "product";
    const productId = searchParams.get("productId");
    const warehouseId = searchParams.get("warehouseId");
    const storeId = searchParams.get("storeId");
    const qualityStatus = searchParams.get("qualityStatus");

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

    const rawRows = (rpcData || []) as MovementRpcRow[];
    const productIds = [...new Set(rawRows.map((row) => row.product_id))];
    const warehouseIds = [...new Set(rawRows.map((row) => row.warehouse_id))];
    const storeIds = [
      ...new Set(rawRows.map((row) => row.store_id).filter(Boolean)),
    ] as string[];

    const [productsRes, warehousesRes, storesRes] = await Promise.all([
      productIds.length > 0
        ? supabase
            .from("products")
            .select("id, name, sku_code, is_defect_copy, store_id, stores(name)")
            .in("id", productIds)
        : { data: [] },
      warehouseIds.length > 0
        ? supabase.from("warehouses").select("id, name").in("id", warehouseIds)
        : { data: [] },
      storeIds.length > 0
        ? supabase.from("stores").select("id, name").in("id", storeIds)
        : { data: [] },
    ]);

    const products = new Map<
      string,
      { name: string; skuCode: string | null; isDefectCopy: boolean; storeId: string | null; storeName: string | null }
    >();
    for (const product of productsRes.data || []) {
      const store = product.stores as unknown as { name: string } | null;
      products.set(product.id, {
        name: product.name,
        skuCode: product.sku_code,
        isDefectCopy: product.is_defect_copy,
        storeId: product.store_id,
        storeName: store?.name ?? null,
      });
    }

    const warehouses = new Map<string, string>();
    for (const warehouse of warehousesRes.data || []) warehouses.set(warehouse.id, warehouse.name);

    const stores = new Map<string, string>();
    for (const store of storesRes.data || []) stores.set(store.id, store.name);

    const groupMap = new Map<string, ProductMovementRow>();

    for (const row of rawRows) {
      if (row.operation_type === "inventory_adjustment") continue;
      const product = products.get(row.product_id);
      if (!product || product.isDefectCopy) continue;
      const effectiveStoreId = row.store_id || product.storeId;

      if (productId && row.product_id !== productId) continue;
      if (warehouseId && row.warehouse_id !== warehouseId) continue;
      if (storeId && effectiveStoreId !== storeId) continue;
      if (qualityStatus && row.quality_status !== qualityStatus) continue;

      const groupId =
        groupBy === "warehouse"
          ? row.warehouse_id
          : groupBy === "store"
            ? effectiveStoreId || "unassigned"
            : groupBy === "quality"
              ? row.quality_status
              : row.product_id;

      const groupName =
        groupBy === "warehouse"
          ? warehouses.get(row.warehouse_id) ?? "Unknown"
          : groupBy === "store"
            ? (effectiveStoreId ? stores.get(effectiveStoreId) || product.storeName : null) ?? "No store"
            : groupBy === "quality"
              ? row.quality_status
              : product.name;

      let entry = groupMap.get(groupId);
      if (!entry) {
        entry = {
          groupId,
          groupName,
          skuCode: groupBy === "product" ? product.skuCode : null,
          qualityStatus:
            groupBy === "quality" ? (row.quality_status as QualityStatus) : null,
          purchaseIn: 0,
          purchaseInCost: 0,
          saleOut: 0,
          saleOutCost: 0,
          returnIn: 0,
          returnInCost: 0,
          writeOffOut: 0,
          writeOffOutCost: 0,
          transferIn: 0,
          transferInCost: 0,
          transferOut: 0,
          transferOutCost: 0,
          productionIn: 0,
          productionInCost: 0,
          productionOut: 0,
          productionOutCost: 0,
          defectOut: 0,
          defectOutCost: 0,
          inventoryAdjustmentIn: 0,
          inventoryAdjustmentInCost: 0,
          net: 0,
          netCost: 0,
          hasNegative: false,
        };
        groupMap.set(groupId, entry);
      }
      if (!entry) continue;

      const qty = Number(row.total_quantity);
      const cost = Number(row.total_cost);
      const type = row.operation_type;
      const dir = row.direction;

      if (type === "purchase" && dir === "in") {
        entry.purchaseIn += qty;
        entry.purchaseInCost += cost;
      } else if (type === "sale" && dir === "out") {
        entry.saleOut += qty;
        entry.saleOutCost += cost;
      } else if (type === "return" && dir === "in") {
        entry.returnIn += qty;
        entry.returnInCost += cost;
      } else if (type === "write_off" && dir === "out") {
        entry.writeOffOut += qty;
        entry.writeOffOutCost += cost;
      } else if (type === "transfer" && dir === "in") {
        entry.transferIn += qty;
        entry.transferInCost += cost;
      } else if (type === "transfer" && dir === "out") {
        entry.transferOut += qty;
        entry.transferOutCost += cost;
      } else if (type === "production" && dir === "in") {
        entry.productionIn += qty;
        entry.productionInCost += cost;
      } else if (type === "production" && dir === "out") {
        entry.productionOut += qty;
        entry.productionOutCost += cost;
      } else if (type === "defect" && dir === "out") {
        entry.defectOut += qty;
        entry.defectOutCost += cost;
      } else if (type === "inventory_adjustment" && dir === "in") {
        entry.inventoryAdjustmentIn += qty;
        entry.inventoryAdjustmentInCost += cost;
      }
      entry.hasNegative = entry.hasNegative || Boolean(row.has_negative);
    }

    const rows = Array.from(groupMap.values()).map((row) => ({
      ...row,
      net:
        row.purchaseIn +
        row.returnIn +
        row.transferIn +
        row.productionIn +
        row.inventoryAdjustmentIn -
        row.saleOut -
        row.writeOffOut -
        row.transferOut -
        row.productionOut -
        row.defectOut,
      netCost:
        row.purchaseInCost +
        row.returnInCost +
        row.transferInCost +
        row.productionInCost +
        row.inventoryAdjustmentInCost -
        row.saleOutCost -
        row.writeOffOutCost -
        row.transferOutCost -
        row.productionOutCost -
        row.defectOutCost,
    }));

    rows.sort((a, b) => a.groupName.localeCompare(b.groupName));

    const report: ProductMovementReport = { from, to, groupBy, rows };
    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

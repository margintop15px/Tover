import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type {
  InventoryBalanceRow,
  InventoryBalancesReport,
  QualityStatus,
} from "@/types/inventory";

export const dynamic = "force-dynamic";

interface Filters {
  productId: string | null;
  categoryId: string | null;
  warehouseId: string | null;
  storeId: string | null;
  qualityStatus: string | null;
  search: string | null;
  hideZeros: boolean;
  negativesOnly: boolean;
}

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const today = new Date().toISOString().split("T")[0];
    const asOfDate = searchParams.get("date") || today;
    const filters: Filters = {
      productId: searchParams.get("productId"),
      categoryId: searchParams.get("categoryId"),
      warehouseId: searchParams.get("warehouseId"),
      storeId: searchParams.get("storeId"),
      qualityStatus: searchParams.get("qualityStatus"),
      search: searchParams.get("search"),
      hideZeros: searchParams.get("hideZeros") === "true",
      negativesOnly: searchParams.get("negativesOnly") === "true",
    };

    const { data: rpcData, error: rpcError } = await supabase.rpc(
      "report_inventory_balances_at_date",
      { p_workspace_id: workspaceId, p_target_date: asOfDate }
    );

    if (rpcError) {
      return NextResponse.json({ error: rpcError.message }, { status: 500 });
    }

    const productIds = [
      ...new Set((rpcData || []).map((r: { product_id: string }) => r.product_id)),
    ];
    const warehouseIds = [
      ...new Set((rpcData || []).map((r: { warehouse_id: string }) => r.warehouse_id)),
    ];
    const storeIds = [
      ...new Set(
        (rpcData || [])
          .map((r: { store_id: string | null }) => r.store_id)
          .filter(Boolean)
      ),
    ] as string[];

    const [productsRes, warehousesRes, storesRes] = await Promise.all([
      productIds.length > 0
        ? supabase
            .from("products")
            .select("id, name, sku_code, is_defect_copy, category_id, store_id, categories(name), stores(name)")
            .in("id", productIds)
        : { data: [] },
      warehouseIds.length > 0
        ? supabase.from("warehouses").select("id, name").in("id", warehouseIds)
        : { data: [] },
      storeIds.length > 0
        ? supabase.from("stores").select("id, name").in("id", storeIds)
        : { data: [] },
    ]);

    const productInfo = new Map<
      string,
      {
        name: string;
        skuCode: string | null;
        isDefectCopy: boolean;
        categoryId: string | null;
        storeId: string | null;
        categoryName: string | null;
        storeName: string | null;
      }
    >();

    for (const p of productsRes.data || []) {
      const cat = p.categories as unknown as { name: string } | null;
      const st = p.stores as unknown as { name: string } | null;
      productInfo.set(p.id, {
        name: p.name,
        skuCode: p.sku_code,
        isDefectCopy: p.is_defect_copy,
        categoryId: p.category_id,
        storeId: p.store_id,
        categoryName: cat?.name ?? null,
        storeName: st?.name ?? null,
      });
    }

    const warehouseInfo = new Map<string, string>();
    for (const w of warehousesRes.data || []) warehouseInfo.set(w.id, w.name);

    const storeInfo = new Map<string, string>();
    for (const s of storesRes.data || []) storeInfo.set(s.id, s.name);

    const productMap = new Map<string, InventoryBalanceRow>();
    const warehouseSet = new Map<string, string>();

    for (const row of rpcData || []) {
      const productId = row.product_id as string;
      const warehouseId = row.warehouse_id as string;
      const movementStoreId = row.store_id as string | null;
      const qualityStatus = (row.quality_status || "ordinary") as QualityStatus;
      const quantity = Number(row.quantity);
      const totalCost = Number(row.total_cost);
      const hasNegative = Boolean(row.has_negative);

      const product = productInfo.get(productId);
      if (!product || product.isDefectCopy) continue;
      const effectiveStoreId = movementStoreId || product.storeId;
      const effectiveStoreName = effectiveStoreId
        ? storeInfo.get(effectiveStoreId) || product.storeName
        : product.storeName;

      if (filters.productId && productId !== filters.productId) continue;
      if (filters.categoryId && product.categoryId !== filters.categoryId) continue;
      if (filters.storeId && effectiveStoreId !== filters.storeId) continue;
      if (filters.warehouseId && warehouseId !== filters.warehouseId) continue;
      if (filters.qualityStatus && qualityStatus !== filters.qualityStatus) continue;
      if (filters.search) {
        const search = filters.search.toLowerCase();
        if (
          !product.name.toLowerCase().includes(search) &&
          !(product.skuCode && product.skuCode.toLowerCase().includes(search))
        ) {
          continue;
        }
      }

      const warehouseName = warehouseInfo.get(warehouseId) ?? "";
      warehouseSet.set(warehouseId, warehouseName);

      const rowKey = `${productId}:${effectiveStoreId || ""}:${qualityStatus}`;
      let reportRow = productMap.get(rowKey);
      if (!reportRow) {
        reportRow = {
          productId,
          productName: product.name,
          skuCode: product.skuCode,
          categoryName: product.categoryName,
          storeId: effectiveStoreId ?? null,
          storeName: effectiveStoreName ?? null,
          qualityStatus,
          warehouses: [],
          totalQuantity: 0,
          totalCost: 0,
          hasNegative: false,
        };
        productMap.set(rowKey, reportRow);
      }

      reportRow.warehouses.push({
        warehouseId,
        warehouseName,
        qualityStatus,
        quantity,
        totalCost,
        hasNegative,
      });
      reportRow.totalQuantity += quantity;
      reportRow.totalCost += totalCost;
      reportRow.hasNegative = reportRow.hasNegative || hasNegative || quantity < 0;
    }

    let rows = Array.from(productMap.values());
    if (filters.hideZeros) rows = rows.filter((r) => r.totalQuantity !== 0);
    if (filters.negativesOnly) {
      rows = rows.filter((r) => r.hasNegative || r.totalQuantity < 0);
    }
    rows.sort((a, b) => a.productName.localeCompare(b.productName));

    const report: InventoryBalancesReport = {
      asOfDate,
      warehouseColumns: Array.from(warehouseSet.entries())
        .map(([id, name]) => ({ id, name }))
        .sort((a, b) => a.name.localeCompare(b.name)),
      rows,
      totals: {
        totalQuantity: rows.reduce((sum, row) => sum + row.totalQuantity, 0),
        totalCost: rows.reduce((sum, row) => sum + row.totalCost, 0),
        hasNegative: rows.some((row) => row.hasNegative || row.totalQuantity < 0),
      },
    };

    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

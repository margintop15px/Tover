import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type { InventoryBalanceRow, InventoryBalancesReport } from "@/types/inventory";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const date = searchParams.get("date");
    const categoryId = searchParams.get("categoryId");
    const warehouseId = searchParams.get("warehouseId");
    const storeId = searchParams.get("storeId");
    const search = searchParams.get("search");
    const hideZeros = searchParams.get("hideZeros") === "true";
    const negativesOnly = searchParams.get("negativesOnly") === "true";

    const today = new Date().toISOString().split("T")[0];
    const isHistorical = date && date !== today;

    if (isHistorical) {
      return await buildHistoricalReport(
        supabase, workspaceId, date,
        { categoryId, warehouseId, storeId, search, hideZeros, negativesOnly }
      );
    }

    return await buildCurrentReport(
      supabase, workspaceId,
      { categoryId, warehouseId, storeId, search, hideZeros, negativesOnly }
    );
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

interface Filters {
  categoryId: string | null;
  warehouseId: string | null;
  storeId: string | null;
  search: string | null;
  hideZeros: boolean;
  negativesOnly: boolean;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function buildCurrentReport(supabase: any, workspaceId: string, filters: Filters) {
  const { data, error } = await supabase
    .from("product_balances")
    .select("product_id, warehouse_id, quantity, unit_cost, products(name, sku_code, is_defect_copy, category_id, store_id, categories(name), stores(name)), warehouses(name)")
    .eq("workspace_id", workspaceId);
  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  // Build product map and warehouse set
  const productMap = new Map<string, InventoryBalanceRow>();
  const warehouseSet = new Map<string, string>();

  for (const row of data || []) {
    const prod = row.products as unknown as {
      name: string;
      sku_code: string | null;
      is_defect_copy: boolean;
      category_id: string | null;
      store_id: string | null;
      categories: { name: string } | null;
      stores: { name: string } | null;
    } | null;
    const wh = row.warehouses as unknown as { name: string } | null;

    if (!prod || prod.is_defect_copy) continue;

    // Apply filters
    if (filters.categoryId && prod.category_id !== filters.categoryId) continue;
    if (filters.storeId && prod.store_id !== filters.storeId) continue;
    if (filters.search) {
      const s = filters.search.toLowerCase();
      if (
        !prod.name.toLowerCase().includes(s) &&
        !(prod.sku_code && prod.sku_code.toLowerCase().includes(s))
      ) continue;
    }

    const whId = row.warehouse_id as string;
    if (filters.warehouseId && whId !== filters.warehouseId) continue;

    warehouseSet.set(whId, wh?.name ?? "");

    const productId = row.product_id as string;
    let prodRow = productMap.get(productId);
    if (!prodRow) {
      prodRow = {
        productId,
        productName: prod.name,
        skuCode: prod.sku_code,
        categoryName: prod.categories?.name ?? null,
        storeName: prod.stores?.name ?? null,
        warehouses: [],
        totalQuantity: 0,
        totalCost: 0,
      };
      productMap.set(productId, prodRow);
    }

    const qty = Number(row.quantity);
    const cost = Number(row.unit_cost);
    prodRow.warehouses.push({
      warehouseId: whId,
      warehouseName: wh?.name ?? "",
      quantity: qty,
      totalCost: qty * cost,
    });
    prodRow.totalQuantity += qty;
    prodRow.totalCost += qty * cost;
  }

  let rows = Array.from(productMap.values());

  if (filters.hideZeros) {
    rows = rows.filter((r) => r.totalQuantity !== 0);
  }
  if (filters.negativesOnly) {
    rows = rows.filter((r) => r.totalQuantity < 0);
  }

  rows.sort((a, b) => a.productName.localeCompare(b.productName));

  const warehouseColumns = Array.from(warehouseSet.entries())
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name));

  const totals = {
    totalQuantity: rows.reduce((s, r) => s + r.totalQuantity, 0),
    totalCost: rows.reduce((s, r) => s + r.totalCost, 0),
  };

  const report: InventoryBalancesReport = {
    asOfDate: new Date().toISOString().split("T")[0],
    warehouseColumns,
    rows,
    totals,
  };

  return NextResponse.json(report);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function buildHistoricalReport(supabase: any, workspaceId: string, date: string, filters: Filters) {
  const { data: rpcData, error: rpcError } = await supabase.rpc(
    "report_inventory_balances_at_date",
    { p_workspace_id: workspaceId, p_target_date: date }
  );

  if (rpcError) {
    return NextResponse.json({ error: rpcError.message }, { status: 500 });
  }

  // Fetch product and warehouse names
  const productIds = [...new Set((rpcData || []).map((r: { product_id: string }) => r.product_id))];
  const warehouseIds = [...new Set((rpcData || []).map((r: { warehouse_id: string }) => r.warehouse_id))];

  const [productsRes, warehousesRes] = await Promise.all([
    productIds.length > 0
      ? supabase
          .from("products")
          .select("id, name, sku_code, is_defect_copy, category_id, store_id, categories(name), stores(name)")
          .in("id", productIds)
      : { data: [] },
    warehouseIds.length > 0
      ? supabase.from("warehouses").select("id, name").in("id", warehouseIds)
      : { data: [] },
  ]);

  const productInfo = new Map<string, {
    name: string; sku_code: string | null; is_defect_copy: boolean;
    category_id: string | null; store_id: string | null;
    categoryName: string | null; storeName: string | null;
  }>();
  for (const p of productsRes.data || []) {
    const cat = p.categories as unknown as { name: string } | null;
    const st = p.stores as unknown as { name: string } | null;
    productInfo.set(p.id, {
      name: p.name,
      sku_code: p.sku_code,
      is_defect_copy: p.is_defect_copy,
      category_id: p.category_id,
      store_id: p.store_id,
      categoryName: cat?.name ?? null,
      storeName: st?.name ?? null,
    });
  }

  const warehouseInfo = new Map<string, string>();
  for (const w of warehousesRes.data || []) {
    warehouseInfo.set(w.id, w.name);
  }

  const productMap = new Map<string, InventoryBalanceRow>();
  const warehouseSet = new Map<string, string>();

  for (const row of rpcData || []) {
    const pId = row.product_id as string;
    const wId = row.warehouse_id as string;
    const qty = Number(row.quantity);

    const prod = productInfo.get(pId);
    if (!prod || prod.is_defect_copy) continue;

    if (filters.categoryId && prod.category_id !== filters.categoryId) continue;
    if (filters.storeId && prod.store_id !== filters.storeId) continue;
    if (filters.warehouseId && wId !== filters.warehouseId) continue;
    if (filters.search) {
      const s = filters.search.toLowerCase();
      if (
        !prod.name.toLowerCase().includes(s) &&
        !(prod.sku_code && prod.sku_code.toLowerCase().includes(s))
      ) continue;
    }

    const whName = warehouseInfo.get(wId) ?? "";
    warehouseSet.set(wId, whName);

    let prodRow = productMap.get(pId);
    if (!prodRow) {
      prodRow = {
        productId: pId,
        productName: prod.name,
        skuCode: prod.sku_code,
        categoryName: prod.categoryName,
        storeName: prod.storeName,
        warehouses: [],
        totalQuantity: 0,
        totalCost: 0,
      };
      productMap.set(pId, prodRow);
    }

    prodRow.warehouses.push({
      warehouseId: wId,
      warehouseName: whName,
      quantity: qty,
      totalCost: 0, // Historical cost not available via replay
    });
    prodRow.totalQuantity += qty;
  }

  let rows = Array.from(productMap.values());

  if (filters.hideZeros) {
    rows = rows.filter((r) => r.totalQuantity !== 0);
  }
  if (filters.negativesOnly) {
    rows = rows.filter((r) => r.totalQuantity < 0);
  }

  rows.sort((a, b) => a.productName.localeCompare(b.productName));

  const warehouseColumns = Array.from(warehouseSet.entries())
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name));

  const totals = {
    totalQuantity: rows.reduce((s, r) => s + r.totalQuantity, 0),
    totalCost: rows.reduce((s, r) => s + r.totalCost, 0),
  };

  const report: InventoryBalancesReport = {
    asOfDate: date,
    warehouseColumns,
    rows,
    totals,
  };

  return NextResponse.json(report);
}

import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const productId = searchParams.get("productId");
    const warehouseId = searchParams.get("warehouseId");
    const limit = parseInt(searchParams.get("limit") || "200", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);

    let query = supabase
      .from("product_balances")
      .select("*, products(name, sku_code), warehouses(name)", {
        count: "exact",
      })
      .eq("workspace_id", workspaceId)
      .order("product_id")
      .range(offset, offset + limit - 1);

    if (productId) query = query.eq("product_id", productId);
    if (warehouseId) query = query.eq("warehouse_id", warehouseId);

    const { data, error, count } = await query;

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({
      page: { limit, offset, totalEstimate: count },
      items: (data || []).map((r) => ({
        id: r.id,
        productId: r.product_id,
        productName: (r.products as { name: string; sku_code: string | null } | null)?.name ?? "",
        warehouseId: r.warehouse_id,
        warehouseName: (r.warehouses as { name: string } | null)?.name ?? "",
        quantity: Number(r.quantity),
        unitCost: Number(r.unit_cost),
      })),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

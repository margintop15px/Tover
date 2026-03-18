import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ supplierId: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { supplierId } = await params;
    const { searchParams } = new URL(request.url);

    const limit = parseInt(searchParams.get("limit") || "50", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);
    const from = searchParams.get("from");
    const to = searchParams.get("to");

    let query = supabase
      .from("operations")
      .select("*, suppliers(name)", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .eq("supplier_id", supplierId)
      .in("type", ["purchase", "payment"])
      .order("operation_date", { ascending: false })
      .order("created_at", { ascending: false })
      .range(offset, offset + limit - 1);

    if (from) query = query.gte("operation_date", from);
    if (to) query = query.lte("operation_date", to);

    const { data, error, count } = await query;

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    // Fetch items summary for purchase operations
    const operationIds = (data || []).map((o) => o.id);
    const itemsMap = new Map<
      string,
      { productName: string; warehouseName: string; quantity: number; unitPrice: number | null }[]
    >();

    if (operationIds.length > 0) {
      const { data: items } = await supabase
        .from("operation_items")
        .select("operation_id, quantity, unit_price, products(name), warehouses(name)")
        .in("operation_id", operationIds);

      if (items) {
        for (const item of items) {
          const list = itemsMap.get(item.operation_id) || [];
          const prod = item.products as unknown as { name: string } | null;
          const wh = item.warehouses as unknown as { name: string } | null;
          list.push({
            productName: prod?.name ?? "",
            warehouseName: wh?.name ?? "",
            quantity: item.quantity,
            unitPrice: item.unit_price,
          });
          itemsMap.set(item.operation_id, list);
        }
      }
    }

    return NextResponse.json({
      page: { limit, offset, totalEstimate: count },
      items: (data || []).map((o) => ({
        id: o.id,
        type: o.type,
        operationDate: o.operation_date,
        comment: o.comment,
        supplierId: o.supplier_id,
        supplierName:
          (o.suppliers as unknown as { name: string } | null)?.name ?? null,
        paymentAmount: o.payment_amount,
        createdAt: o.created_at,
        itemsSummary: itemsMap.get(o.id) || [],
      })),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { processOperation } from "@/lib/operations";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const limit = parseInt(searchParams.get("limit") || "50", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);
    const type = searchParams.get("type");
    const supplierId = searchParams.get("supplierId");
    const productId = searchParams.get("productId");
    const warehouseId = searchParams.get("warehouseId");
    const from = searchParams.get("from");
    const to = searchParams.get("to");

    // If filtering by product or warehouse, first find matching operation IDs
    let operationIdFilter: string[] | null = null;
    if (productId || warehouseId) {
      let itemQuery = supabase
        .from("operation_items")
        .select("operation_id");
      if (productId) itemQuery = itemQuery.eq("product_id", productId);
      if (warehouseId) itemQuery = itemQuery.eq("warehouse_id", warehouseId);
      const { data: matchingItems } = await itemQuery;
      operationIdFilter = [...new Set((matchingItems || []).map((i) => i.operation_id))];
      if (operationIdFilter.length === 0) {
        return NextResponse.json({
          page: { limit, offset, totalEstimate: 0 },
          items: [],
        });
      }
    }

    let query = supabase
      .from("operations")
      .select("*, suppliers(name)", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .order("operation_date", { ascending: false })
      .order("created_at", { ascending: false })
      .range(offset, offset + limit - 1);

    if (type) query = query.eq("type", type);
    if (supplierId) query = query.eq("supplier_id", supplierId);
    if (from) query = query.gte("operation_date", from);
    if (to) query = query.lte("operation_date", to);
    if (operationIdFilter) query = query.in("id", operationIdFilter);

    const { data, error, count } = await query;

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    // Fetch first operation item per operation for summary
    const operationIds = (data || []).map((o) => o.id);
    const itemsMap = new Map<
      string,
      { productName: string; warehouseName: string; quantity: number }[]
    >();

    if (operationIds.length > 0) {
      const { data: items } = await supabase
        .from("operation_items")
        .select("operation_id, quantity, direction, products(name), warehouses(name)")
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
          });
          itemsMap.set(item.operation_id, list);
        }
      }
    }

    return NextResponse.json({
      page: { limit, offset, totalEstimate: count },
      items: (data || []).map((o) => {
        const opItems = itemsMap.get(o.id) || [];
        return {
          id: o.id,
          type: o.type,
          operationDate: o.operation_date,
          comment: o.comment,
          supplierId: o.supplier_id,
          supplierName:
            (o.suppliers as unknown as { name: string } | null)?.name ?? null,
          paymentAmount: o.payment_amount,
          createdAt: o.created_at,
          itemsSummary: opItems,
        };
      }),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const body = await request.json();
    const result = await processOperation(supabase, workspaceId, body);

    if (result.errors) {
      return NextResponse.json(
        { errors: result.errors },
        { status: 400 }
      );
    }

    return NextResponse.json(
      { id: result.operation.id },
      { status: 201 }
    );
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

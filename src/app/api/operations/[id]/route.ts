import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { id } = await params;

    const { data: operation, error } = await supabase
      .from("operations")
      .select("*, suppliers(name)")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (error || !operation) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    // Fetch items with joins
    const { data: items } = await supabase
      .from("operation_items")
      .select("*, products(name), warehouses(name), stores(name)")
      .eq("operation_id", id)
      .order("direction", { ascending: false }); // 'out' first, then 'in'

    return NextResponse.json({
      id: operation.id,
      type: operation.type,
      operationDate: operation.operation_date,
      comment: operation.comment,
      supplierId: operation.supplier_id,
      supplierName:
        (operation.suppliers as unknown as { name: string } | null)?.name ?? null,
      paymentAmount: operation.payment_amount,
      createdAt: operation.created_at,
      items: (items || []).map((item) => ({
        id: item.id,
        productId: item.product_id,
        productName: (item.products as unknown as { name: string } | null)?.name ?? "",
        warehouseId: item.warehouse_id,
        warehouseName:
          (item.warehouses as unknown as { name: string } | null)?.name ?? "",
        quantity: item.quantity,
        unitPrice: item.unit_price,
        direction: item.direction,
        storeId: item.store_id,
        storeName: (item.stores as unknown as { name: string } | null)?.name ?? null,
      })),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

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

export async function PATCH(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const { id } = await params;
    const body = await request.json();

    const { data: operation, error: fetchError } = await supabase
      .from("operations")
      .select("id, type")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (fetchError || !operation) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    const updates: {
      operation_date?: string;
      comment?: string | null;
      supplier_id?: string | null;
      payment_amount?: number | null;
    } = {};

    if (typeof body.operationDate === "string") {
      if (isNaN(Date.parse(body.operationDate))) {
        return NextResponse.json(
          { error: "Valid date is required" },
          { status: 400 }
        );
      }
      updates.operation_date = body.operationDate;
    }

    if ("comment" in body) {
      updates.comment =
        typeof body.comment === "string" && body.comment.trim()
          ? body.comment.trim()
          : null;
    }

    if (operation.type === "purchase" || operation.type === "payment") {
      if (typeof body.supplierId === "string") {
        if (!body.supplierId) {
          return NextResponse.json(
            { error: "Supplier is required" },
            { status: 400 }
          );
        }
        updates.supplier_id = body.supplierId;
      }
    }

    if (operation.type === "payment") {
      if ("paymentAmount" in body) {
        const paymentAmount = Number(body.paymentAmount);
        if (!Number.isFinite(paymentAmount) || paymentAmount <= 0) {
          return NextResponse.json(
            { error: "Payment amount must be positive" },
            { status: 400 }
          );
        }
        updates.payment_amount = paymentAmount;
      }
    }

    const { data: updated, error: updateError } = await supabase
      .from("operations")
      .update(updates)
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .select("id")
      .single();

    if (updateError) {
      return NextResponse.json({ error: updateError.message }, { status: 500 });
    }

    return NextResponse.json({ id: updated.id });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

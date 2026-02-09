import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase } = await getRouteContext(request);

    const { data: order, error: orderError } = await supabase
      .from("orders")
      .select("id")
      .eq("id", id)
      .single();

    if (orderError || !order) {
      return NextResponse.json({ error: "Order not found" }, { status: 404 });
    }

    const { data: lines, error } = await supabase
      .from("order_lines")
      .select("*")
      .eq("order_id", id)
      .order("sku", { ascending: true });

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const items = (lines || []).map((line) => ({
      id: line.id,
      sku: line.sku,
      quantity: line.quantity,
      unitPriceGross: line.unit_price_gross,
      discountAmount: line.discount_amount,
      taxAmount: line.tax_amount,
      lineGmv: Math.round(line.quantity * line.unit_price_gross * 100) / 100,
    }));

    return NextResponse.json({ orderId: id, items });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

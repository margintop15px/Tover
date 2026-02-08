import { NextRequest, NextResponse } from "next/server";
import { createServerClient } from "@/lib/supabase-server";

export const dynamic = "force-dynamic";

export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const supabase = createServerClient();

    // Verify order exists
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

    const items = (lines || []).map((l) => ({
      id: l.id,
      sku: l.sku,
      quantity: l.quantity,
      unitPriceGross: l.unit_price_gross,
      discountAmount: l.discount_amount,
      taxAmount: l.tax_amount,
      lineGmv: Math.round(l.quantity * l.unit_price_gross * 100) / 100,
    }));

    return NextResponse.json({ orderId: id, items });
  } catch (err) {
    console.error("Order lines error:", err);
    return NextResponse.json(
      { error: "Internal server error" },
      { status: 500 }
    );
  }
}

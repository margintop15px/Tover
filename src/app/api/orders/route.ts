import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const now = new Date();
    const thirtyDaysAgo = new Date(now);
    thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

    const from = searchParams.get("from") || thirtyDaysAgo.toISOString();
    const to = searchParams.get("to") || now.toISOString();
    const limit = parseInt(searchParams.get("limit") || "50", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);

    const { data: orders, error, count } = await supabase
      .from("orders")
      .select("*", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .gte("ordered_at", from)
      .lt("ordered_at", to)
      .order("ordered_at", { ascending: false })
      .range(offset, offset + limit - 1);

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    if (!orders || orders.length === 0) {
      return NextResponse.json({
        page: { limit, offset, totalEstimate: 0 },
        items: [],
      });
    }

    const orderIds = orders.map((o) => o.id);
    const linesMap = new Map<string, { gmv: number; units: number }>();

    for (let i = 0; i < orderIds.length; i += 100) {
      const batch = orderIds.slice(i, i + 100);
      const { data: lines } = await supabase
        .from("order_lines")
        .select("order_id, quantity, unit_price_gross")
        .in("order_id", batch);

      if (lines) {
        for (const line of lines) {
          const existing = linesMap.get(line.order_id) || { gmv: 0, units: 0 };
          existing.gmv += line.quantity * line.unit_price_gross;
          existing.units += line.quantity;
          linesMap.set(line.order_id, existing);
        }
      }
    }

    const items = orders.map((o) => {
      const metrics = linesMap.get(o.id) || { gmv: 0, units: 0 };
      return {
        id: o.id,
        source: o.source,
        externalOrderId: o.external_order_id,
        orderedAt: o.ordered_at,
        currency: o.currency,
        status: o.status,
        orderGmv: Math.round(metrics.gmv * 100) / 100,
        orderUnits: metrics.units,
      };
    });

    return NextResponse.json({
      page: { limit, offset, totalEstimate: count },
      items,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

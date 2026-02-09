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

    const { data: salesData, error: salesError } = await supabase.rpc(
      "get_sales_summary",
      {
        p_workspace_id: workspaceId,
        p_from: from,
        p_to: to,
      }
    );

    let gmvGross = 0;
    let unitsSold = 0;
    let ordersCount = 0;

    if (salesError) {
      const { data: orders } = await supabase
        .from("orders")
        .select("id")
        .eq("workspace_id", workspaceId)
        .gte("ordered_at", from)
        .lt("ordered_at", to)
        .neq("status", "cancelled");

      if (orders && orders.length > 0) {
        ordersCount = orders.length;
        const orderIds = orders.map((o) => o.id);

        const allLines: { quantity: number; unit_price_gross: number }[] = [];
        for (let i = 0; i < orderIds.length; i += 100) {
          const batch = orderIds.slice(i, i + 100);
          const { data: lines } = await supabase
            .from("order_lines")
            .select("quantity, unit_price_gross")
            .in("order_id", batch);
          if (lines) {
            allLines.push(...lines);
          }
        }

        for (const line of allLines) {
          gmvGross += line.quantity * line.unit_price_gross;
          unitsSold += line.quantity;
        }
      }
    } else if (salesData && salesData.length > 0) {
      gmvGross = parseFloat(salesData[0].gmv_gross) || 0;
      unitsSold = parseInt(salesData[0].units_sold, 10) || 0;
      ordersCount = parseInt(salesData[0].orders_count, 10) || 0;
    }

    let stockValueCost: number | null = null;
    let inventorySnapshotDate: string | null = null;

    const { data: latestSnapshot } = await supabase
      .from("inventory_snapshots")
      .select("snapshot_date")
      .eq("workspace_id", workspaceId)
      .order("snapshot_date", { ascending: false })
      .limit(1)
      .single();

    if (latestSnapshot) {
      inventorySnapshotDate = latestSnapshot.snapshot_date;
      const { data: snapshots } = await supabase
        .from("inventory_snapshots")
        .select("on_hand_qty, unit_cost")
        .eq("workspace_id", workspaceId)
        .eq("snapshot_date", latestSnapshot.snapshot_date);

      if (snapshots) {
        stockValueCost = snapshots.reduce(
          (sum, snapshot) => sum + snapshot.on_hand_qty * snapshot.unit_cost,
          0
        );
      }
    }

    return NextResponse.json({
      workspaceId,
      range: { from, to },
      kpis: {
        gmvGross: Math.round(gmvGross * 100) / 100,
        unitsSold,
        ordersCount,
        stockValueCost:
          stockValueCost !== null
            ? Math.round(stockValueCost * 100) / 100
            : null,
        inventorySnapshotDate,
      },
      meta: { computedAt: new Date().toISOString() },
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

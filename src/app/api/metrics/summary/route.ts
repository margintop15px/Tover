import { NextRequest, NextResponse } from "next/server";
import { createServerClient, getWorkspaceId } from "@/lib/supabase-server";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const supabase = createServerClient();
    const { searchParams } = new URL(request.url);
    const workspaceId = searchParams.get("workspaceId") || getWorkspaceId();

    const now = new Date();
    const thirtyDaysAgo = new Date(now);
    thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

    const from = searchParams.get("from") || thirtyDaysAgo.toISOString();
    const to = searchParams.get("to") || now.toISOString();

    // GMV + sales volume + order count
    const { data: salesData, error: salesError } = await supabase.rpc(
      "get_sales_summary",
      {
        p_workspace_id: workspaceId,
        p_from: from,
        p_to: to,
      }
    );

    // If RPC not available, fall back to direct query
    let gmvGross = 0;
    let unitsSold = 0;
    let ordersCount = 0;

    if (salesError) {
      // Direct query fallback
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

        // Fetch order lines in batches
        const allLines: { quantity: number; unit_price_gross: number }[] = [];
        for (let i = 0; i < orderIds.length; i += 100) {
          const batch = orderIds.slice(i, i + 100);
          const { data: lines } = await supabase
            .from("order_lines")
            .select("quantity, unit_price_gross")
            .in("order_id", batch);
          if (lines) allLines.push(...lines);
        }

        for (const line of allLines) {
          gmvGross += line.quantity * line.unit_price_gross;
          unitsSold += line.quantity;
        }
      }
    } else if (salesData && salesData.length > 0) {
      gmvGross = parseFloat(salesData[0].gmv_gross) || 0;
      unitsSold = parseInt(salesData[0].units_sold) || 0;
      ordersCount = parseInt(salesData[0].orders_count) || 0;
    }

    // Stock value from latest snapshot
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
          (sum, s) => sum + s.on_hand_qty * s.unit_cost,
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
  } catch (err) {
    console.error("Metrics summary error:", err);
    return NextResponse.json(
      { error: "Internal server error" },
      { status: 500 }
    );
  }
}

import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);
    const nDays = parseInt(searchParams.get("days") || "14", 10);
    const lookbackDays = parseInt(searchParams.get("lookback") || "7", 10);

    const { data: allSnapshots } = await supabase
      .from("inventory_snapshots")
      .select("sku, snapshot_date, on_hand_qty")
      .eq("workspace_id", workspaceId)
      .order("snapshot_date", { ascending: false });

    if (!allSnapshots || allSnapshots.length === 0) {
      return NextResponse.json({ items: [] });
    }

    const latestBySkuMap = new Map<string, { sku: string; on_hand_qty: number }>();
    for (const snapshot of allSnapshots) {
      if (!latestBySkuMap.has(snapshot.sku)) {
        latestBySkuMap.set(snapshot.sku, {
          sku: snapshot.sku,
          on_hand_qty: snapshot.on_hand_qty,
        });
      }
    }

    const lookbackDate = new Date();
    lookbackDate.setDate(lookbackDate.getDate() - lookbackDays);

    const { data: orders } = await supabase
      .from("orders")
      .select("id")
      .eq("workspace_id", workspaceId)
      .gte("ordered_at", lookbackDate.toISOString())
      .neq("status", "cancelled");

    const velocityMap = new Map<string, number>();
    if (orders && orders.length > 0) {
      const orderIds = orders.map((o) => o.id);

      for (let i = 0; i < orderIds.length; i += 100) {
        const batch = orderIds.slice(i, i + 100);
        const { data: lines } = await supabase
          .from("order_lines")
          .select("sku, quantity")
          .in("order_id", batch);

        if (lines) {
          for (const line of lines) {
            velocityMap.set(
              line.sku,
              (velocityMap.get(line.sku) || 0) + line.quantity
            );
          }
        }
      }
    }

    const items: Array<{
      sku: string;
      onHandQty: number;
      avgUnitsPerDay: number;
      daysRemaining: number;
    }> = [];

    for (const [sku, stock] of latestBySkuMap) {
      const totalSold = velocityMap.get(sku) || 0;
      const avgPerDay = totalSold / Math.max(lookbackDays, 1);

      if (avgPerDay > 0) {
        const daysRemaining = stock.on_hand_qty / avgPerDay;
        if (daysRemaining <= nDays) {
          items.push({
            sku,
            onHandQty: stock.on_hand_qty,
            avgUnitsPerDay: Math.round(avgPerDay * 100) / 100,
            daysRemaining: Math.round(daysRemaining * 10) / 10,
          });
        }
      }
    }

    items.sort((a, b) => a.daysRemaining - b.daysRemaining);

    return NextResponse.json({ items: items.slice(0, 50) });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

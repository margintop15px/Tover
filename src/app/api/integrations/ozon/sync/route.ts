import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { createServiceRoleClient } from "@/lib/supabase-server";
import {
  createServiceRoleOzonSyncCoordinator,
  durableSyncHttpStatus,
  OZON_MANUAL_SYNC_BUDGET_MS,
  resolveOzonSyncWindow,
} from "@/lib/ozon/durable-sync";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = await request.json().catch(() => ({}));

    const { data: connection, error } = await supabase
      .from("marketplace_connections")
      .select("id, status")
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .maybeSingle();

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }
    if (!connection) {
      return NextResponse.json(
        { error: "Ozon connection not found" },
        { status: 404 }
      );
    }
    if (connection.status === "disabled") {
      return NextResponse.json(
        { error: "Ozon connection is disabled" },
        { status: 400 }
      );
    }

    const window = resolveOzonSyncWindow({
      dateFrom:
        typeof body.dateFrom === "string" && body.dateFrom
          ? body.dateFrom
          : undefined,
      dateTo:
        typeof body.dateTo === "string" && body.dateTo
          ? body.dateTo
          : undefined,
    });
    const connectionId = String(connection.id);
    const coordinator = createServiceRoleOzonSyncCoordinator(
      createServiceRoleClient(),
      { workspaceId, connectionId }
    );
    const result = await coordinator.beginOrResume({
      connectionId,
      ...window,
      budgetMs: OZON_MANUAL_SYNC_BUDGET_MS,
    });

    return NextResponse.json(result, {
      status: durableSyncHttpStatus(result.status),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

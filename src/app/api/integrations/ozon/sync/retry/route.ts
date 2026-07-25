import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { createServiceRoleClient } from "@/lib/supabase-server";
import {
  authorizeOzonRetryTarget,
  createServiceRoleOzonSyncCoordinator,
  durableSyncHttpStatus,
  OZON_MANUAL_SYNC_BUDGET_MS,
} from "@/lib/ozon/durable-sync";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = await request.json().catch(() => ({}));
    const runId =
      typeof body.runId === "string" ? body.runId.trim() : "";
    if (!runId) {
      return NextResponse.json(
        { error: "Ozon sync run ID is required" },
        { status: 400 }
      );
    }

    const { data: run, error: runError } = await supabase
      .from("marketplace_sync_runs")
      .select("id, workspace_id, connection_id, provider, status")
      .eq("id", runId)
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .maybeSingle();

    if (runError) {
      return NextResponse.json({ error: runError.message }, { status: 500 });
    }
    if (!run) {
      return NextResponse.json(
        { error: "Ozon sync run not found" },
        { status: 404 }
      );
    }

    const { data: connection, error: connectionError } = await supabase
      .from("marketplace_connections")
      .select("id, workspace_id, provider, status")
      .eq("id", run.connection_id)
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .maybeSingle();

    if (connectionError) {
      return NextResponse.json(
        { error: connectionError.message },
        { status: 500 }
      );
    }
    const scope = authorizeOzonRetryTarget(
      { workspaceId, runId },
      run,
      connection
    );
    if (!scope) {
      return NextResponse.json(
        { error: "Ozon sync run not found" },
        { status: 404 }
      );
    }
    if (connection?.status === "disabled") {
      return NextResponse.json(
        { error: "Ozon connection is disabled" },
        { status: 400 }
      );
    }

    const coordinator = createServiceRoleOzonSyncCoordinator(
      createServiceRoleClient(),
      scope
    );
    const result = await coordinator.retryFailed({
      runId,
      budgetMs: OZON_MANUAL_SYNC_BUDGET_MS,
    });
    return NextResponse.json(result, {
      status: durableSyncHttpStatus(result.status),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

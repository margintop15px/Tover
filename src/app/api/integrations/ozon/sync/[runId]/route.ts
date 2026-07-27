import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ runId: string }> }
) {
  try {
    const { runId } = await context.params;
    if (!UUID_PATTERN.test(runId)) {
      return NextResponse.json(
        { error: "Ozon sync run ID must be a UUID" },
        { status: 400 }
      );
    }

    const { supabase, workspaceId } = await getRouteContext(request);
    const [runResult, stepsResult, eventsResult] = await Promise.all([
      supabase
        .from("marketplace_sync_runs")
        .select("id, status, date_from, date_to, started_at, completed_at")
        .eq("id", runId)
        .eq("workspace_id", workspaceId)
        .eq("provider", "ozon")
        .maybeSingle(),
      supabase
        .from("marketplace_sync_run_steps")
        .select(
          "id, step_key, step_order, state, attempt_count, failure_count, checkpoint, summary, last_error, next_attempt_at, started_at, completed_at, updated_at"
        )
        .eq("run_id", runId)
        .eq("workspace_id", workspaceId)
        .eq("provider", "ozon")
        .order("step_order", { ascending: true }),
      supabase
        .from("marketplace_sync_step_events")
        .select(
          "id, step_key, event_type, execution_count, failure_count, phase, processed, total, endpoint, http_status, ozon_code, postgres_code, operation_name, next_action_at, safe_error, created_at"
        )
        .eq("run_id", runId)
        .eq("workspace_id", workspaceId)
        .order("created_at", { ascending: false })
        .limit(100),
    ]);

    if (runResult.error || !runResult.data) {
      return NextResponse.json(
        { error: "Ozon sync run not found" },
        { status: 404 }
      );
    }
    if (stepsResult.error || eventsResult.error) {
      throw new Error("Failed to load Ozon sync details");
    }

    return NextResponse.json({
      run: runResult.data,
      steps: (stepsResult.data ?? []).map((step) => ({
        id: step.id,
        stepKey: step.step_key,
        stepOrder: step.step_order,
        state: step.state,
        attemptCount: step.attempt_count,
        failureCount: step.failure_count,
        progress: safeProgress(step.checkpoint),
        summary: safeSummary(step.summary),
        lastError: safeError(step.last_error),
        nextActionAt: step.next_attempt_at,
        startedAt: step.started_at,
        completedAt: step.completed_at,
        updatedAt: step.updated_at,
      })),
      events: (eventsResult.data ?? []).map((event) => ({
        id: event.id,
        stepKey: event.step_key,
        eventType: event.event_type,
        executionCount: event.execution_count,
        failureCount: event.failure_count,
        phase: event.phase,
        processed: event.processed,
        total: event.total,
        endpoint: event.endpoint,
        httpStatus: event.http_status,
        ozonCode: event.ozon_code,
        postgresCode: event.postgres_code,
        operationName: event.operation_name,
        nextActionAt: event.next_action_at,
        lastError: safeError(event.safe_error),
        createdAt: event.created_at,
      })),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

function safeProgress(value: unknown) {
  if (!isRecord(value) || typeof value.phase !== "string") return null;
  return {
    phase: value.phase.slice(0, 80),
    processed: safeCount(value.processed) ?? 0,
    total: value.total === null ? null : safeCount(value.total),
  };
}

function safeSummary(value: unknown) {
  if (!isRecord(value)) return {};
  return Object.fromEntries(
    ["fetched", "inserted", "updated", "createdCandidates", "skipped"]
      .map((key) => [key, safeCount(value[key])] as const)
      .filter((entry): entry is [string, number] => entry[1] !== null)
  );
}

function safeError(value: unknown) {
  if (!isRecord(value)) return null;
  return Object.fromEntries(
    [
      "message",
      "kind",
      "status",
      "retryAfterMs",
      "retryable",
      "endpoint",
      "code",
      "reason",
      "postgresCode",
      "operationName",
    ]
      .filter((key) =>
        ["string", "number", "boolean"].includes(typeof value[key])
      )
      .map((key) => [key, value[key]])
  );
}

function safeCount(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

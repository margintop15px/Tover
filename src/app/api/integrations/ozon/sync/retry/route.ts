import { NextRequest, NextResponse } from "next/server";
import type { SupabaseClient } from "@supabase/supabase-js";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { createServiceRoleClient } from "@/lib/supabase-server";
import {
  authorizeOzonRetryTarget,
  createServiceRoleOzonSyncCoordinator,
  durableSyncHttpStatus,
  OZON_MANUAL_SYNC_BUDGET_MS,
  type OzonSyncResult,
} from "@/lib/ozon/durable-sync";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

interface RetryManagerContext {
  workspaceId: string;
  userClient: unknown;
}

export interface OzonRetryPostOperations {
  getManagerContext: (request: NextRequest) => Promise<RetryManagerContext>;
  findRun: (
    userClient: unknown,
    workspaceId: string,
    runId: string
  ) => Promise<{ run: unknown; errorMessage: string | null }>;
  findConnection: (
    userClient: unknown,
    workspaceId: string,
    connectionId: string
  ) => Promise<{ connection: unknown; errorMessage: string | null }>;
  createCoordinator: (scope: {
    workspaceId: string;
    connectionId: string;
  }) => {
    retryFailed: (input: {
      runId: string;
      budgetMs: number;
    }) => Promise<OzonSyncResult>;
  };
  routeError: (error: unknown) => NextResponse;
  logError: (message: string, error: unknown) => void;
}

const productionOperations: OzonRetryPostOperations = {
  getManagerContext: async (request) => {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    return { workspaceId, userClient: supabase };
  },
  findRun: async (userClient, workspaceId, runId) => {
    const { data, error } = await (userClient as SupabaseClient)
      .from("marketplace_sync_runs")
      .select("id, workspace_id, connection_id, provider, status")
      .eq("id", runId)
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .maybeSingle();
    return { run: data, errorMessage: error?.message ?? null };
  },
  findConnection: async (userClient, workspaceId, connectionId) => {
    const { data, error } = await (userClient as SupabaseClient)
      .from("marketplace_connections")
      .select("id, workspace_id, provider, status")
      .eq("id", connectionId)
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .maybeSingle();
    return { connection: data, errorMessage: error?.message ?? null };
  },
  createCoordinator: (scope) =>
    createServiceRoleOzonSyncCoordinator(createServiceRoleClient(), scope),
  routeError: toRouteErrorResponse,
  logError: (message, error) => console.error(message, error),
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function createOzonRetryPostHandler(
  operations: OzonRetryPostOperations = productionOperations
) {
  return async function ozonRetryPost(request: NextRequest) {
    try {
      const { workspaceId, userClient } =
        await operations.getManagerContext(request);
      const body = await request.json().catch(() => ({}));
      const runId =
        typeof body.runId === "string" ? body.runId.trim() : "";
      if (!runId) {
        return NextResponse.json(
          { error: "Ozon sync run ID is required" },
          { status: 400 }
        );
      }
      if (!UUID_PATTERN.test(runId)) {
        return NextResponse.json(
          { error: "Ozon sync run ID must be a UUID" },
          { status: 400 }
        );
      }

      const { run, errorMessage: runErrorMessage } =
        await operations.findRun(userClient, workspaceId, runId);
      if (runErrorMessage) {
        operations.logError(
          "Failed to load Ozon sync run",
          runErrorMessage
        );
        return NextResponse.json(
          { error: "Failed to load Ozon sync run" },
          { status: 500 }
        );
      }
      if (!isRunWithConnectionId(run)) {
        return NextResponse.json(
          { error: "Ozon sync run not found" },
          { status: 404 }
        );
      }

      const { connection, errorMessage: connectionErrorMessage } =
        await operations.findConnection(
          userClient,
          workspaceId,
          run.connection_id
        );
      if (connectionErrorMessage) {
        operations.logError(
          "Failed to load Ozon connection",
          connectionErrorMessage
        );
        return NextResponse.json(
          { error: "Failed to load Ozon connection" },
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
      if (isDisabledConnection(connection)) {
        return NextResponse.json(
          { error: "Ozon connection is disabled" },
          { status: 400 }
        );
      }

      const coordinator = operations.createCoordinator(scope);
      const result = await coordinator.retryFailed({
        runId,
        budgetMs: OZON_MANUAL_SYNC_BUDGET_MS,
      });
      return NextResponse.json(result, {
        status: durableSyncHttpStatus(result.status),
      });
    } catch (error) {
      return operations.routeError(error);
    }
  };
}

const productionPost = createOzonRetryPostHandler();

export async function POST(request: NextRequest) {
  return productionPost(request);
}

function isRunWithConnectionId(
  value: unknown
): value is { connection_id: string } {
  return (
    value !== null &&
    typeof value === "object" &&
    "connection_id" in value &&
    typeof value.connection_id === "string"
  );
}

function isDisabledConnection(value: unknown) {
  return (
    value !== null &&
    typeof value === "object" &&
    "status" in value &&
    value.status === "disabled"
  );
}

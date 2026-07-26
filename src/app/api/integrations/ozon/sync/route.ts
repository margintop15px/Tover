import { NextRequest, NextResponse } from "next/server";
import type { SupabaseClient } from "@supabase/supabase-js";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { createServiceRoleClient } from "@/lib/supabase-server";
import {
  createServiceRoleOzonSyncCoordinator,
  durableSyncHttpStatus,
  OZON_MANUAL_SYNC_BUDGET_MS,
  parseOzonSyncWindow,
  type OzonSyncResult,
} from "@/lib/ozon/durable-sync";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

interface SyncManagerContext {
  workspaceId: string;
  userClient: unknown;
}

interface SyncConnection {
  id: string;
  status: string;
}

export interface OzonSyncPostOperations {
  getManagerContext: (request: NextRequest) => Promise<SyncManagerContext>;
  findConnection: (
    userClient: unknown,
    workspaceId: string
  ) => Promise<{
    connection: SyncConnection | null;
    errorMessage: string | null;
  }>;
  createCoordinator: (scope: {
    workspaceId: string;
    connectionId: string;
  }) => {
    beginOrResume: (input: {
      connectionId: string;
      dateFrom: string;
      dateTo: string;
      budgetMs: number;
    }) => Promise<OzonSyncResult>;
  };
  now: () => number;
  routeError: (error: unknown) => NextResponse;
  logError: (message: string, error: unknown) => void;
}

const productionOperations: OzonSyncPostOperations = {
  getManagerContext: async (request) => {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    return { workspaceId, userClient: supabase };
  },
  findConnection: async (userClient, workspaceId) => {
    const { data, error } = await (userClient as SupabaseClient)
      .from("marketplace_connections")
      .select("id, status")
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .maybeSingle();
    return {
      connection: data
        ? { id: String(data.id), status: String(data.status) }
        : null,
      errorMessage: error?.message ?? null,
    };
  },
  createCoordinator: (scope) =>
    createServiceRoleOzonSyncCoordinator(createServiceRoleClient(), scope),
  now: Date.now,
  routeError: toRouteErrorResponse,
  logError: (message, error) => console.error(message, error),
};

export function createOzonSyncPostHandler(
  operations: OzonSyncPostOperations = productionOperations
) {
  return async function ozonSyncPost(request: NextRequest) {
    try {
      const { workspaceId, userClient } =
        await operations.getManagerContext(request);
      const body = await request.json().catch(() => ({}));
      const { connection, errorMessage } = await operations.findConnection(
        userClient,
        workspaceId
      );

      if (errorMessage) {
        operations.logError("Failed to load Ozon connection", errorMessage);
        return NextResponse.json(
          { error: "Failed to load Ozon connection" },
          { status: 500 }
        );
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

      const syncOptions = parseSyncOptions(body);
      if (!syncOptions) {
        return invalidSyncWindowResponse();
      }
      const window = parseOzonSyncWindow(syncOptions, operations.now());
      if (!window.ok) {
        return invalidSyncWindowResponse();
      }

      const coordinator = operations.createCoordinator({
        workspaceId,
        connectionId: connection.id,
      });
      const result = await coordinator.beginOrResume({
        connectionId: connection.id,
        ...window.value,
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

const productionPost = createOzonSyncPostHandler();

export async function POST(request: NextRequest) {
  return productionPost(request);
}

function parseSyncOptions(
  body: unknown
): { dateFrom?: string; dateTo?: string } | null {
  if (!isRecord(body)) return null;
  const options: { dateFrom?: string; dateTo?: string } = {};
  for (const key of ["dateFrom", "dateTo"] as const) {
    if (!(key in body)) continue;
    const value = body[key];
    if (typeof value !== "string" || !value) return null;
    options[key] = value;
  }
  return options;
}

function invalidSyncWindowResponse() {
  return NextResponse.json(
    { error: "Invalid Ozon sync date window" },
    { status: 400 }
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return (
    value !== null && typeof value === "object" && !Array.isArray(value)
  );
}

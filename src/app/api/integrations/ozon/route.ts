import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { validateOzonCredentials } from "@/lib/ozon/client";
import {
  credentialHint,
  encryptOzonCredentials,
} from "@/lib/ozon/credentials";
import {
  failedValidationHealth,
  publicOzonHealth,
  successfulValidationHealth,
} from "@/lib/ozon/health";
import type { OzonConnectionRecord, OzonCredentials } from "@/lib/ozon/types";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    return NextResponse.json(await loadOzonSummary(supabase, workspaceId));
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId, user } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = await request.json();
    const credentials = parseCredentials(body);
    if (!credentials) {
      return NextResponse.json(
        { error: "Client ID and API key are required" },
        { status: 400 }
      );
    }
    const name = String(body.name || "Ozon").trim() || "Ozon";
    const encrypted = encryptOzonCredentials(credentials);

    const { data: connection, error } = await supabase
      .from("marketplace_connections")
      .upsert(
        {
          workspace_id: workspaceId,
          provider: "ozon",
          name,
          credential_ciphertext: encrypted,
          client_id_hint: credentialHint(credentials.clientId),
          api_key_hint: credentialHint(credentials.apiKey),
          status: "draft",
          health: {},
          created_by: user.id,
        },
        { onConflict: "workspace_id,provider" }
      )
      .select("*")
      .single();

    if (error || !connection) {
      return NextResponse.json(
        { error: error?.message ?? "Failed to save Ozon connection" },
        { status: 500 }
      );
    }

    try {
      const validation = await validateOzonCredentials(credentials);
      const checkedAt = new Date().toISOString();
      await supabase
        .from("marketplace_connections")
        .update({
          status: "connected",
          health: successfulValidationHealth(validation, checkedAt),
          last_validated_at: checkedAt,
          last_sync_error: null,
        })
        .eq("id", connection.id);

      return NextResponse.json(await loadOzonSummary(supabase, workspaceId));
    } catch (validationError) {
      const message = publicErrorMessage(validationError);
      const checkedAt = new Date().toISOString();
      await supabase
        .from("marketplace_connections")
        .update({
          status: "invalid",
          health: failedValidationHealth(message, checkedAt),
          last_validated_at: checkedAt,
          last_sync_error: message,
        })
        .eq("id", connection.id);

      return NextResponse.json(
        {
          error: message,
          ...(await loadOzonSummary(supabase, workspaceId)),
        },
        { status: 400 }
      );
    }
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function DELETE(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const { error } = await supabase
      .from("marketplace_connections")
      .update({
        status: "disabled",
        credential_ciphertext: {},
        client_id_hint: null,
        api_key_hint: null,
        last_sync_error: null,
      })
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon");

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json(await loadOzonSummary(supabase, workspaceId));
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

function parseCredentials(body: Record<string, unknown>): OzonCredentials | null {
  const clientId = String(body.clientId || "").trim();
  const apiKey = String(body.apiKey || "").trim();

  if (!clientId || !apiKey) {
    return null;
  }

  return { clientId, apiKey };
}

async function loadOzonSummary(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  workspaceId: string
) {
  const { data: connection, error } = await supabase
    .from("marketplace_connections")
    .select("*")
    .eq("workspace_id", workspaceId)
    .eq("provider", "ozon")
    .maybeSingle();

  if (error) {
    if (isMissingIntegrationSchemaError(error)) {
      return {
        connected: false,
        connection: null,
        counts: emptyCounts(),
        recentRuns: [],
        setupError: "Ozon integration migration has not been applied.",
      };
    }
    throw new Error(error.message);
  }

  if (!connection) {
    return {
      connected: false,
      connection: null,
      counts: emptyCounts(),
      recentRuns: [],
    };
  }

  const connectionId = (connection as OzonConnectionRecord).id;
  const [
    products,
    unmappedProducts,
    warehouses,
    unmappedWarehouses,
    postings,
    returnsCount,
    financeTransactions,
    legalEntitySales,
    unpaidLegalProducts,
    financeReports,
    removals,
    supplies,
    stockAnalytics,
    discountedProducts,
    candidatesReady,
    candidatesNeedsMapping,
    syncRuns,
  ] = await Promise.all([
    countRows(supabase, "ozon_products", workspaceId, connectionId),
    countRows(supabase, "ozon_products", workspaceId, connectionId, {
      mapping_status: "unmapped",
    }),
    countRows(supabase, "ozon_warehouses", workspaceId, connectionId),
    countRows(supabase, "ozon_warehouses", workspaceId, connectionId, {
      mapping_status: "unmapped",
    }),
    countRows(supabase, "ozon_postings", workspaceId, connectionId),
    countRows(supabase, "ozon_returns", workspaceId, connectionId),
    countRows(supabase, "ozon_finance_transactions", workspaceId, connectionId),
    optionalCountRows(supabase, "ozon_legal_entity_sales", workspaceId, connectionId),
    optionalCountRows(supabase, "ozon_unpaid_legal_products", workspaceId, connectionId),
    optionalCountRows(supabase, "ozon_finance_reports", workspaceId, connectionId),
    optionalCountRows(supabase, "ozon_removals", workspaceId, connectionId),
    optionalCountRows(supabase, "ozon_supply_orders", workspaceId, connectionId),
    optionalCountRows(supabase, "ozon_stock_analytics", workspaceId, connectionId),
    optionalCountRows(supabase, "ozon_discounted_products", workspaceId, connectionId),
    countRows(supabase, "marketplace_operation_candidates", workspaceId, connectionId, {
      status: "ready",
    }),
    countRows(supabase, "marketplace_operation_candidates", workspaceId, connectionId, {
      status: "needs_mapping",
    }),
    supabase
      .from("marketplace_sync_runs")
      .select("id, status, started_at, completed_at, summary, error")
      .eq("workspace_id", workspaceId)
      .eq("connection_id", connectionId)
      .order("started_at", { ascending: false })
      .limit(5),
  ]);

  if (syncRuns.error) throw new Error(syncRuns.error.message);

  return {
    connected: connection.status === "connected",
    connection: publicConnection(connection as OzonConnectionRecord),
    counts: {
      products,
      unmappedProducts,
      warehouses,
      unmappedWarehouses,
      postings,
      returns: returnsCount,
      financeTransactions,
      legalEntitySales,
      unpaidLegalProducts,
      financeReports,
      removals,
      supplies,
      stockAnalytics,
      discountedProducts,
      candidatesReady,
      candidatesNeedsMapping,
    },
    recentRuns: syncRuns.data || [],
  };
}

async function countRows(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  table: string,
  workspaceId: string,
  connectionId: string,
  filters: Record<string, string> = {}
) {
  let query = supabase
    .from(table)
    .select("id", { count: "exact", head: true })
    .eq("workspace_id", workspaceId)
    .eq("connection_id", connectionId);

  for (const [key, value] of Object.entries(filters)) {
    query = query.eq(key, value);
  }

  const { count, error } = await query;
  if (error) throw new Error(error.message);
  return count || 0;
}

async function optionalCountRows(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  table: string,
  workspaceId: string,
  connectionId: string
) {
  try {
    return await countRows(supabase, table, workspaceId, connectionId);
  } catch (error) {
    if (isMissingIntegrationSchemaError(error as { code?: string; message?: string })) {
      return 0;
    }
    throw error;
  }
}

function publicConnection(connection: OzonConnectionRecord) {
  return {
    id: connection.id,
    name: connection.name,
    provider: connection.provider,
    status: connection.status,
    clientIdHint: connection.client_id_hint,
    apiKeyHint: connection.api_key_hint,
    health: publicOzonHealth(connection.health),
    lastValidatedAt: connection.last_validated_at,
    lastSyncAt: connection.last_sync_at,
    lastSyncStatus: connection.last_sync_status,
    lastSyncError: connection.last_sync_error,
    updatedAt: connection.updated_at,
  };
}

function emptyCounts() {
  return {
    products: 0,
    unmappedProducts: 0,
    warehouses: 0,
    unmappedWarehouses: 0,
    postings: 0,
    returns: 0,
    financeTransactions: 0,
    legalEntitySales: 0,
    unpaidLegalProducts: 0,
    financeReports: 0,
    removals: 0,
    supplies: 0,
    stockAnalytics: 0,
    discountedProducts: 0,
    candidatesReady: 0,
    candidatesNeedsMapping: 0,
  };
}

function publicErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function isMissingIntegrationSchemaError(error: { code?: string; message?: string }) {
  const message = error.message || "";
  return (
    error.code === "42P01" ||
    error.code === "PGRST205" ||
    message.includes("marketplace_connections") ||
    message.includes("does not exist") ||
    message.includes("schema cache")
  );
}

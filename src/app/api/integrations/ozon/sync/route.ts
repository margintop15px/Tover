import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { syncOzonConnection } from "@/lib/ozon/sync";

export const dynamic = "force-dynamic";

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

    const result = await syncOzonConnection(
      supabase,
      workspaceId,
      connection.id as string,
      {
        dateFrom:
          typeof body.dateFrom === "string" && body.dateFrom
            ? body.dateFrom
            : undefined,
        dateTo:
          typeof body.dateTo === "string" && body.dateTo
            ? body.dateTo
            : undefined,
      }
    );

    return NextResponse.json(result);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

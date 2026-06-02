import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { validateOzonCredentials } from "@/lib/ozon/client";
import { decryptOzonCredentials } from "@/lib/ozon/credentials";
import {
  failedValidationHealth,
  successfulValidationHealth,
} from "@/lib/ozon/health";
import type { OzonConnectionRecord } from "@/lib/ozon/types";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const { data: connection, error } = await supabase
      .from("marketplace_connections")
      .select("*")
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

    const credentials = decryptOzonCredentials(
      (connection as OzonConnectionRecord).credential_ciphertext
    );

    try {
      const validation = await validateOzonCredentials(credentials);
      const checkedAt = new Date().toISOString();
      const health = successfulValidationHealth(validation, checkedAt);
      await supabase
        .from("marketplace_connections")
        .update({
          status: "connected",
          health,
          last_validated_at: checkedAt,
          last_sync_error: null,
        })
        .eq("id", connection.id);

      return NextResponse.json({ ok: true, health });
    } catch (validationError) {
      const message =
        validationError instanceof Error
          ? validationError.message
          : String(validationError);

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

      return NextResponse.json({ error: message }, { status: 400 });
    }
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  getOzonCandidateOperation,
  normalizeOzonCandidateOperation,
  statusFromValidation,
  validateOzonCandidateOperation,
  type MarketplaceCandidateRow,
} from "@/lib/ozon/candidates";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const { data, error } = await supabase
      .from("marketplace_operation_candidates")
      .select("*")
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .in("status", ["ready", "needs_mapping"]);

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    let approved = 0;
    let blocked = 0;

    for (const candidate of (data || []) as MarketplaceCandidateRow[]) {
      const operation = normalizeOzonCandidateOperation(
        getOzonCandidateOperation(candidate)
      );
      const validationErrors = validateOzonCandidateOperation(operation);
      const nextStatus =
        validationErrors.length === 0 ? "approved" : statusFromValidation(validationErrors);

      const { error: updateError } = await supabase
        .from("marketplace_operation_candidates")
        .update({
          normalized_operation: operation,
          validation_errors: validationErrors,
          status: nextStatus,
        })
        .eq("id", candidate.id)
        .eq("workspace_id", workspaceId);

      if (updateError) throw new Error(updateError.message);

      if (nextStatus === "approved") approved += 1;
      else blocked += 1;
    }

    return NextResponse.json({ approved, blocked });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

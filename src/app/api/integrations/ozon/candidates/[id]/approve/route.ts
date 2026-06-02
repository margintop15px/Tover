import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  getOzonCandidateOperation,
  normalizeOzonCandidateOperation,
  validateOzonCandidateOperation,
  type MarketplaceCandidateRow,
} from "@/lib/ozon/candidates";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const candidate = await loadCandidate(supabase, workspaceId, id);
    if (!candidate) {
      return NextResponse.json({ error: "Candidate not found" }, { status: 404 });
    }
    if (candidate.status === "committed") {
      return NextResponse.json(
        { error: "Committed candidates cannot be approved again" },
        { status: 409 }
      );
    }
    if (candidate.status === "committing") {
      return NextResponse.json(
        { error: "Committing candidates cannot be approved" },
        { status: 409 }
      );
    }
    if (candidate.status === "ignored") {
      return NextResponse.json(
        { error: "Ignored candidates must be restored before approval" },
        { status: 400 }
      );
    }

    const operation = normalizeOzonCandidateOperation(
      getOzonCandidateOperation(candidate)
    );
    const validationErrors = validateOzonCandidateOperation(operation);

    if (validationErrors.length > 0) {
      await supabase
        .from("marketplace_operation_candidates")
        .update({
          normalized_operation: operation,
          validation_errors: validationErrors,
          status: "needs_mapping",
        })
        .eq("id", candidate.id)
        .eq("workspace_id", workspaceId);

      return NextResponse.json(
        { error: "Resolve validation errors before approval", validationErrors },
        { status: 400 }
      );
    }

    const { data, error } = await supabase
      .from("marketplace_operation_candidates")
      .update({
        normalized_operation: operation,
        validation_errors: [],
        status: "approved",
      })
      .eq("id", candidate.id)
      .eq("workspace_id", workspaceId)
      .select("*")
      .single();

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({ candidate: data });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

async function loadCandidate(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  workspaceId: string,
  id: string
) {
  const { data, error } = await supabase
    .from("marketplace_operation_candidates")
    .select("*")
    .eq("workspace_id", workspaceId)
    .eq("provider", "ozon")
    .eq("id", id)
    .maybeSingle();

  if (error) throw new Error(error.message);
  return data as MarketplaceCandidateRow | null;
}

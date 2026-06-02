import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  createWarehouseForCandidateItem,
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
    const body = (await request.json().catch(() => ({}))) as {
      itemIndex?: number;
    };
    const candidate = await loadCandidate(supabase, workspaceId, id);
    if (!candidate) {
      return NextResponse.json({ error: "Candidate not found" }, { status: 404 });
    }
    if (candidate.status === "committed") {
      return NextResponse.json(
        { error: "Committed candidates cannot be edited" },
        { status: 409 }
      );
    }
    if (candidate.status === "committing") {
      return NextResponse.json(
        { error: "Committing candidates cannot be edited" },
        { status: 409 }
      );
    }

    const updated = await createWarehouseForCandidateItem(
      supabase,
      candidate,
      body.itemIndex ?? 0
    );

    return NextResponse.json({ candidate: updated });
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

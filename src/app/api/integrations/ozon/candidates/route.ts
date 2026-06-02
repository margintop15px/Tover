import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  candidateSummary,
  getOzonCandidateOperation,
  type MarketplaceCandidateRow,
  type OzonCandidateSourceType,
  type OzonCandidateSupportStatus,
} from "@/lib/ozon/candidates";

export const dynamic = "force-dynamic";

type CandidateMappingState = "all" | "mapped" | "missing";
type CandidateSupportFilter = OzonCandidateSupportStatus | "all";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const limit = Math.min(parseInt(searchParams.get("limit") || "50", 10), 200);
    const offset = parseInt(searchParams.get("offset") || "0", 10);
    const status = searchParams.get("status");
    const operationType = searchParams.get("operationType");
    const from = searchParams.get("from");
    const to = searchParams.get("to");
    const mappingState = parseMappingState(searchParams.get("mappingState"));
    const sourceType = searchParams.get("sourceType");
    const supportStatus = parseSupportFilter(searchParams.get("supportStatus"));

    let query = supabase
      .from("marketplace_operation_candidates")
      .select("*", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .order("operation_date", { ascending: false, nullsFirst: false })
      .order("created_at", { ascending: false });

    if (status && status !== "all") query = query.eq("status", status);
    if (operationType && operationType !== "all") {
      query = query.eq("operation_type", operationType);
    }
    if (sourceType && sourceType !== "all") {
      query = query.eq("source_type", sourceType as OzonCandidateSourceType);
    }
    if (from) query = query.gte("operation_date", from);
    if (to) query = query.lte("operation_date", to);

    if (mappingState === "all" && supportStatus === "all") {
      query = query.range(offset, offset + limit - 1);
      const { data, error, count } = await query;
      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }

      return NextResponse.json({
        page: { limit, offset, total: count || 0 },
        summary: await loadSummary(supabase, workspaceId),
        items: data || [],
      });
    }

    const { data, error } = await query;
    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const filtered = ((data || []) as MarketplaceCandidateRow[]).filter((row) => {
      const mappingMatches =
        mappingState === "all" ||
        (mappingState === "mapped" ? isMapped(row) : !isMapped(row));
      const supportMatches =
        supportStatus === "all" || candidateSupportStatus(row) === supportStatus;
      return mappingMatches && supportMatches;
    });

    return NextResponse.json({
      page: { limit, offset, total: filtered.length },
      summary: await loadSummary(supabase, workspaceId),
      items: filtered.slice(offset, offset + limit),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

async function loadSummary(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  workspaceId: string
) {
  const { data, error } = await supabase
    .from("marketplace_operation_candidates")
    .select("*")
    .eq("workspace_id", workspaceId)
    .eq("provider", "ozon");

  if (error) throw new Error(error.message);
  return candidateSummary((data || []) as MarketplaceCandidateRow[]);
}

function parseMappingState(value: string | null): CandidateMappingState {
  if (value === "mapped" || value === "missing") return value;
  return "all";
}

function parseSupportFilter(value: string | null): CandidateSupportFilter {
  if (
    value === "commit_candidate" ||
    value === "reporting_only" ||
    value === "blocked"
  ) {
    return value;
  }
  return "all";
}

function isMapped(candidate: MarketplaceCandidateRow) {
  const operation = getOzonCandidateOperation(candidate);
  const items = operation.items || [];
  return (
    items.length > 0 &&
    items.every((item) => Boolean(item.productId) && Boolean(item.warehouseId))
  );
}

function candidateSupportStatus(candidate: MarketplaceCandidateRow) {
  const operation = getOzonCandidateOperation(candidate);
  return operation.supportStatus || "commit_candidate";
}

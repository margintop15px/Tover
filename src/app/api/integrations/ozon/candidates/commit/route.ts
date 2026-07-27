import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { createServiceRoleClient } from "@/lib/supabase-server";
import {
  getOzonCandidateOperation,
  normalizeOzonCandidateOperation,
  validateOzonCandidateOperation,
  type MarketplaceCandidateRow,
} from "@/lib/ozon/candidates";

export const dynamic = "force-dynamic";

interface CommitSuccess {
  candidateId: string;
  operationId: string;
  skipped?: boolean;
}

interface CommitFailure {
  candidateId: string;
  error: string;
}

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = (await request.json().catch(() => ({}))) as {
      candidateIds?: string[];
    };

    let query = supabase
      .from("marketplace_operation_candidates")
      .select("*")
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon")
      .order("operation_date", { ascending: true, nullsFirst: false })
      .order("created_at", { ascending: true });

    if (body.candidateIds?.length) {
      query = query.in("id", body.candidateIds);
    } else {
      query = query.in("status", ["approved", "committed"]);
    }

    const { data, error } = await query;
    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const committed: CommitSuccess[] = [];
    const failed: CommitFailure[] = [];
    const serviceRole = createServiceRoleClient();
    const foundIds = new Set(
      ((data || []) as MarketplaceCandidateRow[]).map((candidate) => candidate.id)
    );

    for (const candidateId of body.candidateIds || []) {
      if (!foundIds.has(candidateId)) {
        failed.push({ candidateId, error: "Candidate not found" });
      }
    }

    for (const candidate of (data || []) as MarketplaceCandidateRow[]) {
      if (candidate.created_operation_id) {
        committed.push({
          candidateId: candidate.id,
          operationId: candidate.created_operation_id,
          skipped: true,
        });
        continue;
      }

      if (candidate.status === "committing") {
        failed.push({
          candidateId: candidate.id,
          error: "Candidate is already being committed",
        });
        continue;
      }

      if (candidate.status !== "approved") {
        failed.push({
          candidateId: candidate.id,
          error: "Candidate must be approved before commit",
        });
        continue;
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
            status: "approved",
          })
          .eq("id", candidate.id)
          .eq("workspace_id", workspaceId);
        failed.push({
          candidateId: candidate.id,
          error: "Candidate has validation errors",
        });
        continue;
      }

      if (candidate.evidence_version !== 1 || !candidate.evidence_hash) {
        failed.push({
          candidateId: candidate.id,
          error: "Candidate evidence is stale; sync it again before commit",
        });
        continue;
      }
      try {
        const { data: result, error: commitError } = await serviceRole.rpc(
          "commit_ozon_operation_candidate_v2",
          {
            p_workspace_id: workspaceId,
            p_candidate_id: candidate.id,
            p_evidence_hash: candidate.evidence_hash,
            p_operation: operation,
          }
        );
        if (commitError) {
          throw new Error("Atomic Ozon candidate commit failed");
        }
        const row = Array.isArray(result) ? result[0] : result;
        const operationId =
          row && typeof row === "object" && "operation_id" in row
            ? String(row.operation_id)
            : "";
        if (!operationId) throw new Error("Atomic Ozon commit returned no operation");
        committed.push({
          candidateId: candidate.id,
          operationId,
          skipped:
            row && typeof row === "object" && "skipped" in row
              ? row.skipped === true
              : false,
        });
      } catch (commitError) {
        const message =
          commitError instanceof Error ? commitError.message : String(commitError);
        failed.push({ candidateId: candidate.id, error: message });
      }
    }

    return NextResponse.json({
      committed,
      failed,
      committedCount: committed.filter((item) => !item.skipped).length,
      skippedCount: committed.filter((item) => item.skipped).length,
      failedCount: failed.length,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

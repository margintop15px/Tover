import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { processOperation } from "@/lib/operations";
import {
  buildOperationRequest,
  getOzonCandidateOperation,
  normalizeOzonCandidateOperation,
  validateOzonCandidateOperation,
  type MarketplaceCandidateRow,
  type OzonCandidateOperation,
} from "@/lib/ozon/candidates";

export const dynamic = "force-dynamic";

type RouteSupabase = Awaited<ReturnType<typeof getRouteContext>>["supabase"];

interface CommitClaimRow {
  id: string;
  candidate_id: string;
  status: "claimed" | "committed" | "failed";
  operation_id: string | null;
  error: string | null;
}

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
    const foundIds = new Set(
      ((data || []) as MarketplaceCandidateRow[]).map((candidate) => candidate.id)
    );

    for (const candidateId of body.candidateIds || []) {
      if (!foundIds.has(candidateId)) {
        failed.push({ candidateId, error: "Candidate not found" });
      }
    }

    for (const candidate of (data || []) as MarketplaceCandidateRow[]) {
      const alreadyCommitted = await handleAlreadyCommitted(
        supabase,
        workspaceId,
        candidate
      );
      if (alreadyCommitted) {
        committed.push(alreadyCommitted);
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

      const claimResult = await claimCandidateForCommit(
        supabase,
        workspaceId,
        candidate,
        operation
      );
      if ("committed" in claimResult) {
        committed.push(claimResult.committed);
        continue;
      }
      if ("failed" in claimResult) {
        failed.push(claimResult.failed);
        continue;
      }

      const claim = claimResult.claim;
      let operationId: string | null = null;
      try {
        const result = await processOperation(
          supabase,
          workspaceId,
          buildOperationRequest({
            ...candidate,
            status: "committing",
            normalized_operation: operation,
          })
        );

        if (result.errors || !result.operation?.id) {
          throw new Error(
            result.errors?.map((item) => item.message).join("; ") ||
              "Failed to create operation"
          );
        }

        operationId = result.operation.id as string;
        await markClaimCommitted(supabase, workspaceId, claim.id, operationId);
        await markCandidateCommitted(supabase, workspaceId, candidate.id, operationId);

        committed.push({
          candidateId: candidate.id,
          operationId,
        });
      } catch (commitError) {
        const message =
          commitError instanceof Error ? commitError.message : String(commitError);
        if (operationId) {
          await markCandidateRecoveryRequired(
            supabase,
            workspaceId,
            candidate.id,
            `Operation ${operationId} was created, but commit linking failed: ${message}`
          );
        } else {
          await markClaimFailed(supabase, workspaceId, claim.id, message);
          await markCandidateRecoveryRequired(
            supabase,
            workspaceId,
            candidate.id,
            `Commit claim is locked for recovery: ${message}`
          );
        }
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

async function handleAlreadyCommitted(
  supabase: RouteSupabase,
  workspaceId: string,
  candidate: MarketplaceCandidateRow
): Promise<CommitSuccess | null> {
  const operationId = candidate.created_operation_id;
  if (!operationId) return null;

  if (candidate.status !== "committed") {
    await markCandidateCommitted(supabase, workspaceId, candidate.id, operationId);
  }

  return {
    candidateId: candidate.id,
    operationId,
    skipped: true,
  };
}

async function claimCandidateForCommit(
  supabase: RouteSupabase,
  workspaceId: string,
  candidate: MarketplaceCandidateRow,
  operation: OzonCandidateOperation
): Promise<
  | { claim: CommitClaimRow }
  | { committed: CommitSuccess }
  | { failed: CommitFailure }
> {
  const existingClaim = await findExistingClaim(supabase, workspaceId, candidate);
  if (existingClaim?.operation_id) {
    await markCandidateCommitted(
      supabase,
      workspaceId,
      candidate.id,
      existingClaim.operation_id
    );
    return {
      committed: {
        candidateId: candidate.id,
        operationId: existingClaim.operation_id,
        skipped: true,
      },
    };
  }
  if (existingClaim) {
    const message =
      existingClaim.error ||
      "Candidate already has an unresolved commit claim and requires manual recovery";
    await markCandidateRecoveryRequired(supabase, workspaceId, candidate.id, message);
    return { failed: { candidateId: candidate.id, error: message } };
  }

  const { data: claimedCandidate, error: claimStatusError } = await supabase
    .from("marketplace_operation_candidates")
    .update({
      status: "committing",
      normalized_operation: operation,
      validation_errors: [],
    })
    .eq("id", candidate.id)
    .eq("workspace_id", workspaceId)
    .eq("status", "approved")
    .is("created_operation_id", null)
    .select("*")
    .maybeSingle();

  if (claimStatusError) throw new Error(claimStatusError.message);

  if (!claimedCandidate) {
    const refreshed = await loadCandidate(supabase, workspaceId, candidate.id);
    if (refreshed?.created_operation_id) {
      return {
        committed: {
          candidateId: candidate.id,
          operationId: refreshed.created_operation_id,
          skipped: true,
        },
      };
    }
    return {
      failed: {
        candidateId: candidate.id,
        error: "Candidate was claimed or changed by another request",
      },
    };
  }

  const { data: claim, error: insertError } = await supabase
    .from("marketplace_operation_commit_claims")
    .insert({
      workspace_id: workspaceId,
      connection_id: candidate.connection_id,
      candidate_id: candidate.id,
      provider: candidate.provider,
      source_type: candidate.source_type,
      external_event_id: candidate.external_event_id,
      status: "claimed",
    })
    .select("id, candidate_id, status, operation_id, error")
    .single();

  if (!insertError && claim) return { claim: claim as CommitClaimRow };

  if ((insertError as { code?: string } | null)?.code === "23505") {
    const existingAfterInsertError = await findExistingClaim(
      supabase,
      workspaceId,
      candidate
    );
    if (existingAfterInsertError?.operation_id) {
      await markCandidateCommitted(
        supabase,
        workspaceId,
        candidate.id,
        existingAfterInsertError.operation_id
      );
      return {
        committed: {
          candidateId: candidate.id,
          operationId: existingAfterInsertError.operation_id,
          skipped: true,
        },
      };
    }
    const message =
      existingAfterInsertError?.error ||
      "Candidate source already has an unresolved commit claim";
    await markCandidateRecoveryRequired(supabase, workspaceId, candidate.id, message);
    return { failed: { candidateId: candidate.id, error: message } };
  }

  const message = insertError?.message || "Failed to create commit claim";
  await supabase
    .from("marketplace_operation_candidates")
    .update({
      status: "approved",
      validation_errors: [
        {
          field: "commit",
          message,
          severity: "error",
        },
      ],
    })
    .eq("id", candidate.id)
    .eq("workspace_id", workspaceId);
  return { failed: { candidateId: candidate.id, error: message } };
}

async function findExistingClaim(
  supabase: RouteSupabase,
  workspaceId: string,
  candidate: MarketplaceCandidateRow
) {
  const { data: byCandidate, error: candidateError } = await supabase
    .from("marketplace_operation_commit_claims")
    .select("id, candidate_id, status, operation_id, error")
    .eq("workspace_id", workspaceId)
    .eq("candidate_id", candidate.id)
    .maybeSingle();
  if (candidateError) throw new Error(candidateError.message);
  if (byCandidate) return byCandidate as CommitClaimRow;

  const { data: bySource, error: sourceError } = await supabase
    .from("marketplace_operation_commit_claims")
    .select("id, candidate_id, status, operation_id, error")
    .eq("workspace_id", workspaceId)
    .eq("provider", candidate.provider)
    .eq("source_type", candidate.source_type)
    .eq("external_event_id", candidate.external_event_id)
    .maybeSingle();
  if (sourceError) throw new Error(sourceError.message);
  return (bySource as CommitClaimRow | null) || null;
}

async function loadCandidate(
  supabase: RouteSupabase,
  workspaceId: string,
  candidateId: string
) {
  const { data, error } = await supabase
    .from("marketplace_operation_candidates")
    .select("id, status, created_operation_id")
    .eq("workspace_id", workspaceId)
    .eq("id", candidateId)
    .maybeSingle();
  if (error) throw new Error(error.message);
  return data as Pick<
    MarketplaceCandidateRow,
    "id" | "status" | "created_operation_id"
  > | null;
}

async function markCandidateCommitted(
  supabase: RouteSupabase,
  workspaceId: string,
  candidateId: string,
  operationId: string
) {
  const { error } = await supabase
    .from("marketplace_operation_candidates")
    .update({
      status: "committed",
      created_operation_id: operationId,
      validation_errors: [],
    })
    .eq("id", candidateId)
    .eq("workspace_id", workspaceId);
  if (error) throw new Error(error.message);
}

async function markCandidateRecoveryRequired(
  supabase: RouteSupabase,
  workspaceId: string,
  candidateId: string,
  message: string
) {
  const { error } = await supabase
    .from("marketplace_operation_candidates")
    .update({
      status: "committing",
      validation_errors: [
        {
          field: "commit",
          message,
          severity: "error",
        },
      ],
    })
    .eq("id", candidateId)
    .eq("workspace_id", workspaceId);
  if (error) throw new Error(error.message);
}

async function markClaimCommitted(
  supabase: RouteSupabase,
  workspaceId: string,
  claimId: string,
  operationId: string
) {
  const { error } = await supabase
    .from("marketplace_operation_commit_claims")
    .update({
      status: "committed",
      operation_id: operationId,
      error: null,
      committed_at: new Date().toISOString(),
    })
    .eq("id", claimId)
    .eq("workspace_id", workspaceId);
  if (error) throw new Error(error.message);
}

async function markClaimFailed(
  supabase: RouteSupabase,
  workspaceId: string,
  claimId: string,
  message: string
) {
  const { error } = await supabase
    .from("marketplace_operation_commit_claims")
    .update({
      status: "failed",
      error: message,
      failed_at: new Date().toISOString(),
    })
    .eq("id", claimId)
    .eq("workspace_id", workspaceId);
  if (error) throw new Error(error.message);
}

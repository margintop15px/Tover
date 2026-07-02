import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  loadOperationImportDuplicates,
  loadOperationImportRefData,
} from "@/lib/operation-imports/server";
import {
  candidateSummary,
  normalizeAndValidateDraft,
} from "@/lib/operation-imports/pipeline";
import type {
  BuiltCandidate,
  OperationImportCandidateRecord,
  OperationImportDraft,
} from "@/lib/operation-imports/types";

export const dynamic = "force-dynamic";

function asBuiltCandidate(row: OperationImportCandidateRecord): BuiltCandidate {
  return {
    rowIndex: row.row_index,
    fingerprint: row.fingerprint,
    status: row.status,
    confidence: row.confidence,
    source: row.source,
    raw: row.raw,
    operation: row.operation,
    normalizedOperation: row.normalized_operation,
    validationErrors: row.validation_errors,
    duplicateOf: row.duplicate_of,
  };
}

function candidateOperation(row: OperationImportCandidateRecord) {
  return (
    row.normalized_operation ||
    row.operation ||
    {}
  ) as OperationImportDraft;
}

async function recalculateSummary(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  importId: string
) {
  const { data } = await supabase
    .from("operation_import_candidates")
    .select("*")
    .eq("import_id", importId)
    .order("row_index", { ascending: true });

  const candidates = ((data || []) as OperationImportCandidateRecord[]).map(
    asBuiltCandidate
  );
  const summary = {
    ...candidateSummary(candidates),
    approved: candidates.filter((candidate) => candidate.status === "approved")
      .length,
    committed: candidates.filter((candidate) => candidate.status === "committed")
      .length,
  };

  await supabase
    .from("operation_imports")
    .update({
      summary,
      status:
        candidates.length > 0 &&
        candidates.every((candidate) => candidate.status === "approved")
          ? "ready"
          : "needs_review",
    })
    .eq("id", importId);

  return summary;
}

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase, workspaceId } = await getRouteContext(request);

    const { data: importRecord, error: importError } = await supabase
      .from("operation_imports")
      .select("*")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (importError || !importRecord) {
      return NextResponse.json({ error: "Import not found" }, { status: 404 });
    }

    const { data: candidates, error: candidateError } = await supabase
      .from("operation_import_candidates")
      .select("*")
      .eq("import_id", id)
      .order("row_index", { ascending: true });

    if (candidateError) {
      return NextResponse.json(
        { error: candidateError.message },
        { status: 500 }
      );
    }

    return NextResponse.json({
      import: importRecord,
      candidates: candidates || [],
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function PATCH(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = (await request.json()) as {
      candidateId?: string;
      operation?: OperationImportDraft;
      candidateUpdates?: { candidateId: string; operation: OperationImportDraft }[];
      approveCandidateId?: string;
      approveAll?: boolean;
    };

    const { data: importRecord, error: importError } = await supabase
      .from("operation_imports")
      .select("id, workspace_id, status")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (importError || !importRecord) {
      return NextResponse.json({ error: "Import not found" }, { status: 404 });
    }

    if (importRecord.status === "completed" || importRecord.status === "committing") {
      return NextResponse.json(
        { error: "Committed imports cannot be edited" },
        { status: 409 }
      );
    }

    const [ref, duplicates] = await Promise.all([
      loadOperationImportRefData(supabase, workspaceId),
      loadOperationImportDuplicates(supabase, workspaceId),
    ]);

    if (body.operation && body.candidateId) {
      const validation = normalizeAndValidateDraft(body.operation, ref, duplicates);
      const { data: updated, error } = await supabase
        .from("operation_import_candidates")
        .update({
          operation: body.operation,
          normalized_operation: validation.normalized,
          fingerprint: validation.fingerprint,
          validation_errors: validation.validationErrors,
          status: validation.status,
        })
        .eq("id", body.candidateId)
        .eq("import_id", id)
        .select()
        .single();

      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }

      const summary = await recalculateSummary(supabase, id);
      return NextResponse.json({ candidate: updated, summary });
    }

    if (Array.isArray(body.candidateUpdates) && body.candidateUpdates.length > 0) {
      if (
        body.candidateUpdates.some(
          (update) => !update.candidateId || !update.operation
        )
      ) {
        return NextResponse.json(
          { error: "Invalid candidate update" },
          { status: 400 }
        );
      }

      const updates = await Promise.all(
        body.candidateUpdates.map((update) => {
          const validation = normalizeAndValidateDraft(
            update.operation,
            ref,
            duplicates
          );
          return supabase
            .from("operation_import_candidates")
            .update({
              operation: update.operation,
              normalized_operation: validation.normalized,
              fingerprint: validation.fingerprint,
              validation_errors: validation.validationErrors,
              status: validation.status,
            })
            .eq("id", update.candidateId)
            .eq("import_id", id)
            .select()
            .single();
        })
      );

      const error = updates.find((update) => update.error)?.error;
      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }

      const summary = await recalculateSummary(supabase, id);
      return NextResponse.json({
        candidates: updates.map((update) => update.data),
        summary,
      });
    }

    if (body.approveCandidateId) {
      const { data: candidate, error: fetchError } = await supabase
        .from("operation_import_candidates")
        .select("*")
        .eq("id", body.approveCandidateId)
        .eq("import_id", id)
        .single();

      if (fetchError || !candidate) {
        return NextResponse.json({ error: "Candidate not found" }, { status: 404 });
      }

      const validation = normalizeAndValidateDraft(
        candidateOperation(candidate as OperationImportCandidateRecord),
        ref,
        duplicates
      );

      if (validation.validationErrors.length > 0) {
        await supabase
          .from("operation_import_candidates")
          .update({
            normalized_operation: validation.normalized,
            fingerprint: validation.fingerprint,
            validation_errors: validation.validationErrors,
            status: validation.status,
          })
          .eq("id", body.approveCandidateId)
          .eq("import_id", id);

        return NextResponse.json(
          { error: "Resolve validation errors before approval" },
          { status: 400 }
        );
      }

      const { data: updated, error } = await supabase
        .from("operation_import_candidates")
        .update({
          normalized_operation: validation.normalized,
          fingerprint: validation.fingerprint,
          validation_errors: [],
          status: "approved",
        })
        .eq("id", body.approveCandidateId)
        .eq("import_id", id)
        .select()
        .single();

      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }

      const summary = await recalculateSummary(supabase, id);
      return NextResponse.json({ candidate: updated, summary });
    }

    if (body.approveAll) {
      const { data: candidates, error: fetchError } = await supabase
        .from("operation_import_candidates")
        .select("*")
        .eq("import_id", id);

      if (fetchError) {
        return NextResponse.json({ error: fetchError.message }, { status: 500 });
      }

      const validations = ((candidates || []) as OperationImportCandidateRecord[]).map(
        (candidate) => ({
          candidate,
          validation: normalizeAndValidateDraft(
            candidateOperation(candidate),
            ref,
            duplicates
          ),
        })
      );

      const blocked = validations.filter(
        ({ validation }) => validation.validationErrors.length > 0
      );

      if (blocked.length > 0) {
        await Promise.all(
          blocked.map(({ candidate, validation }) =>
            supabase
              .from("operation_import_candidates")
              .update({
                normalized_operation: validation.normalized,
                fingerprint: validation.fingerprint,
                validation_errors: validation.validationErrors,
                status: validation.status,
              })
              .eq("id", candidate.id)
              .eq("import_id", id)
          )
        );

        return NextResponse.json(
          { error: `${blocked.length} candidates still need review` },
          { status: 400 }
        );
      }

      const approvable = validations.filter(({ candidate }) =>
        ["ready", "approved"].includes(candidate.status)
      );

      const updates = await Promise.all(
        approvable.map(({ candidate, validation }) =>
          supabase
            .from("operation_import_candidates")
            .update({
              normalized_operation: validation.normalized,
              fingerprint: validation.fingerprint,
              validation_errors: [],
              status: "approved",
            })
            .eq("id", candidate.id)
            .eq("import_id", id)
        )
      );

      const error = updates.find((update) => update.error)?.error;
      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }

      const summary = await recalculateSummary(supabase, id);
      return NextResponse.json({ summary });
    }

    return NextResponse.json({ error: "No update provided" }, { status: 400 });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

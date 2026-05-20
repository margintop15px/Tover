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

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const { data: importRecord, error: importError } = await supabase
      .from("operation_imports")
      .select("id, status")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (importError || !importRecord) {
      return NextResponse.json({ error: "Import not found" }, { status: 404 });
    }

    if (importRecord.status === "completed" || importRecord.status === "committing") {
      return NextResponse.json(
        { error: "Committed imports cannot be reprocessed" },
        { status: 409 }
      );
    }

    const [{ data: rows, error: rowError }, ref, duplicates] = await Promise.all([
      supabase
        .from("operation_import_candidates")
        .select("*")
        .eq("import_id", id)
        .order("row_index", { ascending: true }),
      loadOperationImportRefData(supabase, workspaceId),
      loadOperationImportDuplicates(supabase, workspaceId),
    ]);

    if (rowError) {
      return NextResponse.json({ error: rowError.message }, { status: 500 });
    }

    const updatedCandidates: BuiltCandidate[] = [];

    for (const row of (rows || []) as OperationImportCandidateRecord[]) {
      const operation = row.operation as OperationImportDraft;
      const validation = normalizeAndValidateDraft(operation, ref, duplicates);
      updatedCandidates.push({
        rowIndex: row.row_index,
        fingerprint: validation.fingerprint,
        status: validation.status,
        confidence: row.confidence,
        source: row.source,
        raw: row.raw,
        operation,
        normalizedOperation: validation.normalized,
        validationErrors: validation.validationErrors,
        duplicateOf: row.duplicate_of,
      });

      const { error } = await supabase
        .from("operation_import_candidates")
        .update({
          fingerprint: validation.fingerprint,
          normalized_operation: validation.normalized,
          validation_errors: validation.validationErrors,
          status: validation.status,
        })
        .eq("id", row.id);

      if (error) throw new Error(error.message);
    }

    const summary = candidateSummary(updatedCandidates);
    await supabase
      .from("operation_imports")
      .update({
        status: "needs_review",
        summary,
        findings: {
          reprocessedAt: new Date().toISOString(),
          candidateCount: updatedCandidates.length,
        },
      })
      .eq("id", id);

    return NextResponse.json({ summary });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

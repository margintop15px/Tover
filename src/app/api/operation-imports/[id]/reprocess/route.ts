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

type CreatedEntityReprocessRequest = {
  createdEntity?: {
    kind?: "product" | "supplier" | "warehouse";
    id?: string;
    name?: string;
    skuCode?: string | null;
  };
};

async function readJsonBody(request: NextRequest) {
  try {
    return (await request.json()) as CreatedEntityReprocessRequest;
  } catch {
    return {};
  }
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = await readJsonBody(request);

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

    if (body.createdEntity) {
      const { kind, id: entityId, name, skuCode } = body.createdEntity;
      if (!kind || !entityId || !["product", "supplier", "warehouse"].includes(kind)) {
        return NextResponse.json(
          { error: "Invalid created entity" },
          { status: 400 }
        );
      }

      const { data, error } = await supabase.rpc(
        "apply_operation_import_created_entity",
        {
          p_workspace_id: workspaceId,
          p_import_id: id,
          p_entity_kind: kind,
          p_entity_id: entityId,
          p_entity_name: name ?? null,
          p_sku_code: skuCode ?? null,
        }
      );

      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }

      return NextResponse.json(data);
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

    const { data: candidates, error: candidateFetchError } = await supabase
      .from("operation_import_candidates")
      .select("*")
      .eq("import_id", id)
      .order("row_index", { ascending: true });

    if (candidateFetchError) throw new Error(candidateFetchError.message);

    return NextResponse.json({ summary, candidates: candidates || [] });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  loadOperationImportDuplicates,
  loadOperationImportLoadPreview,
  loadOperationImportRefData,
  loadOperationImportReviewPage,
  normalizeOperationImportCandidatePage,
  recalculateOperationImportSummary,
} from "@/lib/operation-imports/server";
import { normalizeAndValidateDraft } from "@/lib/operation-imports/pipeline";
import type {
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
  limit?: number;
  offset?: number;
};

async function readJsonBody(request: NextRequest) {
  try {
    return (await request.json()) as CreatedEntityReprocessRequest;
  } catch {
    return {};
  }
}

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => `${JSON.stringify(key)}:${stableStringify(item)}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function validationChanged(
  row: OperationImportCandidateRecord,
  validation: ReturnType<typeof normalizeAndValidateDraft>
) {
  return (
    row.fingerprint !== validation.fingerprint ||
    row.status !== validation.status ||
    stableStringify(row.normalized_operation) !==
      stableStringify(validation.normalized) ||
    stableStringify(row.validation_errors || []) !==
      stableStringify(validation.validationErrors)
  );
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
    const paged = body.limit !== undefined || body.offset !== undefined;
    const page = paged
      ? normalizeOperationImportCandidatePage(body.limit, body.offset)
      : null;

    const { data: importRecord, error: importError } = await supabase
      .from("operation_imports")
      .select("*")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (importError || !importRecord) {
      return NextResponse.json({ error: "Import not found" }, { status: 404 });
    }

    if (importRecord.status === "completed" || importRecord.status === "committing") {
      if (body.createdEntity) {
        return NextResponse.json(
          { error: "Committed imports cannot be reprocessed" },
          { status: 409 }
        );
      }

      if (page) {
        const reviewPage = await loadOperationImportReviewPage(
          supabase,
          workspaceId,
          id,
          page
        );
        return NextResponse.json({
          import: importRecord,
          summary: importRecord.summary,
          status: importRecord.status,
          ...reviewPage,
        });
      }

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

      const [{ summary, status }, loadPreview] = await Promise.all([
        recalculateOperationImportSummary(supabase, workspaceId, id),
        loadOperationImportLoadPreview(supabase, workspaceId, id),
      ]);

      return NextResponse.json({ ...data, summary, status, loadPreview });
    }

    const rowQuery = supabase
      .from("operation_import_candidates")
      .select("*")
      .eq("workspace_id", workspaceId)
      .eq("import_id", id)
      .neq("status", "committed")
      .neq("status", "approved")
      .order("row_index", { ascending: true });

    const [{ data: rows, error: rowError }, ref, duplicates] = await Promise.all([
      page
        ? rowQuery.gte("row_index", page.offset).lt("row_index", page.offset + page.limit)
        : rowQuery,
      loadOperationImportRefData(supabase, workspaceId),
      loadOperationImportDuplicates(supabase, workspaceId),
    ]);

    if (rowError) {
      return NextResponse.json({ error: rowError.message }, { status: 500 });
    }

    const changed = ((rows || []) as OperationImportCandidateRecord[])
      .map((row) => ({
        row,
        validation: normalizeAndValidateDraft(
          row.operation as OperationImportDraft,
          ref,
          duplicates
        ),
      }))
      .filter(({ row, validation }) => validationChanged(row, validation));

    const updates = await Promise.all(
      changed.map(({ row, validation }) =>
        supabase
          .from("operation_import_candidates")
          .update({
            fingerprint: validation.fingerprint,
            normalized_operation: validation.normalized,
            validation_errors: validation.validationErrors,
            status: validation.status,
          })
          .eq("id", row.id)
      )
    );

    const updateError = updates.find((update) => update.error)?.error;
    if (updateError) throw new Error(updateError.message);

    const { summary, status } = await recalculateOperationImportSummary(
      supabase,
      workspaceId,
      id
    );
    await supabase
      .from("operation_imports")
      .update({
        findings: {
          reprocessedAt: new Date().toISOString(),
          candidateCount: (rows || []).length,
          changedCandidateCount: changed.length,
        },
      })
      .eq("id", id);

    if (page) {
      const reviewPage = await loadOperationImportReviewPage(
        supabase,
        workspaceId,
        id,
        page
      );
      return NextResponse.json({
        import: {
          ...importRecord,
          status,
          summary,
        },
        summary,
        status,
        ...reviewPage,
      });
    }

    const { data: candidates, error: candidateFetchError } = await supabase
      .from("operation_import_candidates")
      .select("*")
      .eq("import_id", id)
      .order("row_index", { ascending: true });

    if (candidateFetchError) throw new Error(candidateFetchError.message);

    const loadPreview = await loadOperationImportLoadPreview(
      supabase,
      workspaceId,
      id
    );

    return NextResponse.json({
      summary,
      status,
      candidates: candidates || [],
      loadPreview,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

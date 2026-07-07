import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { recalculateOperationImportSummary } from "@/lib/operation-imports/server";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase, workspaceId, user } = await getRouteContext(request, {
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

    if (importRecord.status === "completed") {
      return NextResponse.json(
        { error: "Import has already been committed" },
        { status: 409 }
      );
    }

    const { data: candidates, error: candidateError } = await supabase
      .from("operation_import_candidates")
      .select("id, status, validation_errors")
      .eq("import_id", id);

    if (candidateError) {
      return NextResponse.json(
        { error: candidateError.message },
        { status: 500 }
      );
    }

    const rows = candidates || [];
    const approvedValid = rows.filter(
      (candidate) =>
        candidate.status === "approved" &&
        (candidate.validation_errors || []).length === 0
    );

    if (rows.length === 0 || approvedValid.length === 0) {
      return NextResponse.json(
        {
          error:
            rows.length === 0
              ? "No candidates to commit"
              : "No approved candidates to commit",
        },
        { status: 400 }
      );
    }

    const { data, error } = await supabase.rpc("commit_operation_import", {
      p_workspace_id: workspaceId,
      p_import_id: id,
      p_approved_by: user.id,
    });

    if (error) {
      await supabase
        .from("operation_imports")
        .update({
          findings: { commitError: error.message },
        })
        .eq("id", id);
      await recalculateOperationImportSummary(supabase, workspaceId, id);

      return NextResponse.json({ error: error.message }, { status: 400 });
    }

    return NextResponse.json(data);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

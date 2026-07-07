import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  candidateSummary,
  detectFileKind,
  extractTabularOperations,
  hashBuffer,
  shouldUseTabularAiFallback,
  tabularAiFallbackReasons,
} from "@/lib/operation-imports/pipeline";
import {
  extractWithOpenAI,
  inferTabularImportPlan,
} from "@/lib/operation-imports/openai";
import {
  insertOperationImportCandidates,
  loadOperationImportDuplicates,
  loadOperationImportRefData,
} from "@/lib/operation-imports/server";
import type { ExtractionResult } from "@/lib/operation-imports/types";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const formData = await request.formData();
    const file = formData.get("file") as File | null;

    if (!file) {
      return NextResponse.json({ error: "No file provided" }, { status: 400 });
    }

    const buffer = Buffer.from(await file.arrayBuffer());
    const fileHash = hashBuffer(buffer);
    const detectedKind = detectFileKind(file.name, file.type);

    const { data: duplicateFiles } = await supabase
      .from("operation_imports")
      .select("id, status, created_at")
      .eq("workspace_id", workspaceId)
      .eq("file_hash", fileHash)
      .order("created_at", { ascending: false })
      .limit(5);

    const resumableImport = (duplicateFiles || []).find(
      (item) => !["completed", "failed"].includes(item.status)
    );
    if (resumableImport) {
      const [
        { data: existingImport, error: existingImportError },
        { data: existingCandidates, error: existingCandidateError },
      ] = await Promise.all([
        supabase
          .from("operation_imports")
          .select("*")
          .eq("workspace_id", workspaceId)
          .eq("id", resumableImport.id)
          .single(),
        supabase
          .from("operation_import_candidates")
          .select("*")
          .eq("workspace_id", workspaceId)
          .eq("import_id", resumableImport.id)
          .order("row_index", { ascending: true }),
      ]);

      if (existingImportError || existingCandidateError || !existingImport) {
        return NextResponse.json(
          {
            error: "Failed to resume import job",
            detail: existingImportError?.message || existingCandidateError?.message,
          },
          { status: 500 }
        );
      }

      return NextResponse.json({
        import: existingImport,
        candidates: existingCandidates || [],
        resumed: true,
      });
    }

    const { data: importRecord, error: importError } = await supabase
      .from("operation_imports")
      .insert({
        workspace_id: workspaceId,
        file_name: file.name,
        file_type: detectedKind,
        file_size: file.size,
        file_hash: fileHash,
        source_kind: "upload",
        status: "extracting",
        summary: {
          duplicateFiles: duplicateFiles || [],
        },
      })
      .select()
      .single();

    if (importError || !importRecord) {
      return NextResponse.json(
        { error: "Failed to create import job", detail: importError?.message },
        { status: 500 }
      );
    }

    const importId = importRecord.id;

    try {
      const [ref, existingDuplicates] = await Promise.all([
        loadOperationImportRefData(supabase, workspaceId),
        loadOperationImportDuplicates(supabase, workspaceId),
      ]);

      let extraction: ExtractionResult;

      if (detectedKind === "csv" || detectedKind === "xlsx") {
        const deterministic = await extractTabularOperations({
          fileName: file.name,
          mimeType: file.type,
          buffer,
          ref,
          existingDuplicates,
        });
        const fallbackReasons = tabularAiFallbackReasons(deterministic);

        if (shouldUseTabularAiFallback(deterministic)) {
          const planResult = await inferTabularImportPlan({
            fileType: detectedKind,
            tables: deterministic.tables,
            deterministicFindings: deterministic.findings,
            ref,
          }).catch((error) => ({
            fileType: detectedKind,
            plan: null,
            findings: {
              parser: "openai_tabular_plan",
              error: error instanceof Error ? error.message : "OpenAI plan failed",
              deterministicFindings: deterministic.findings,
            },
            extracted: {},
            generatedCode: null,
            generatedCodeResult: {},
            securityReport: {
              localGeneratedCodeExecution: false,
              openAiCodeInterpreter: false,
              reason: "OpenAI plan failed",
            },
          }));
          const planned = planResult.plan
            ? await extractTabularOperations({
                fileName: file.name,
                mimeType: file.type,
                buffer,
                ref,
                existingDuplicates,
                plan: planResult.plan,
              })
            : null;

          extraction =
            planned && planned.candidates.length > 0
              ? {
                  ...planned,
                  tables: deterministic.tables,
                  findings: {
                    ...planned.findings,
                    parser: "openai_tabular_plan_deterministic",
                    fallbackReasons,
                    planFindings: planResult.findings,
                    deterministicFindings: deterministic.findings,
                  },
                  extracted: {
                    ...planned.extracted,
                    tabularPlan: planResult.plan,
                    planExtraction: planResult.extracted,
                    deterministicPreview: deterministic.extracted,
                  },
                  generatedCode: planResult.generatedCode ?? null,
                  generatedCodeResult: planResult.generatedCodeResult ?? {},
                  securityReport: {
                    ...planned.securityReport,
                    aiPlan: planResult.securityReport,
                  },
                }
              : {
                  ...deterministic,
                  findings: {
                    ...deterministic.findings,
                    ...(deterministic.candidates.length === 0 &&
                    planResult.findings.error
                      ? { error: planResult.findings.error }
                      : {}),
                    aiFallback: {
                      attempted: true,
                      reasons: fallbackReasons,
                      error:
                        planResult.findings.error ??
                        "No AI tabular plan candidates returned",
                    },
                  },
                  securityReport: {
                    ...deterministic.securityReport,
                    aiFallback: planResult.securityReport,
                  },
                };
        } else {
          extraction = deterministic;
        }
      } else {
        extraction = await extractWithOpenAI({
          fileName: file.name,
          mimeType: file.type,
          buffer,
          ref,
          existingDuplicates,
        });
      }

      await insertOperationImportCandidates(
        supabase,
        workspaceId,
        importId,
        extraction.candidates
      );

      const { data: insertedCandidates, error: candidateFetchError } =
        await supabase
          .from("operation_import_candidates")
          .select("*")
          .eq("import_id", importId)
          .order("row_index", { ascending: true });

      if (candidateFetchError) throw new Error(candidateFetchError.message);

      const summary = {
        ...candidateSummary(extraction.candidates),
        duplicateFiles: duplicateFiles || [],
      };
      const nextStatus =
        extraction.candidates.length === 0 && extraction.findings.error
          ? "failed"
          : "needs_review";

      const { data: updated, error: updateError } = await supabase
        .from("operation_imports")
        .update({
          file_type: extraction.fileType,
          status: nextStatus,
          summary,
          findings: extraction.findings,
          extracted: extraction.extracted,
          generated_code: extraction.generatedCode ?? null,
          generated_code_result: extraction.generatedCodeResult ?? {},
          security_report: extraction.securityReport ?? {},
          completed_at: nextStatus === "failed" ? new Date().toISOString() : null,
        })
        .eq("id", importId)
        .select()
        .single();

      if (updateError) throw new Error(updateError.message);

      return NextResponse.json({
        import: updated,
        candidates: insertedCandidates || [],
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Extraction failed";
      await supabase
        .from("operation_imports")
        .update({
          status: "failed",
          findings: { error: message },
          completed_at: new Date().toISOString(),
        })
        .eq("id", importId);

      return NextResponse.json(
        { importId, status: "failed", error: message },
        { status: 400 }
      );
    }
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);
    const limit = parseInt(searchParams.get("limit") || "20", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);

    const { data, error, count } = await supabase
      .from("operation_imports")
      .select("*", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .order("created_at", { ascending: false })
      .range(offset, offset + limit - 1);

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({
      page: { limit, offset, total: count },
      items: data || [],
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

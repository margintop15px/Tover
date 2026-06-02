import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { validateReportTemplatePayload } from "@/lib/reports/template-validation";

export const dynamic = "force-dynamic";

function mapTemplate(row: Record<string, unknown>) {
  return {
    id: row.id,
    name: row.name,
    source: row.source,
    rowDimensions: row.row_dimensions,
    columnDimensions: row.column_dimensions,
    measures: row.measures,
    filters: row.filters,
    dateMode: row.date_mode,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { id } = await params;
    const { data, error } = await supabase
      .from("report_templates")
      .select("*")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (error || !data) return NextResponse.json({ error: "Not found" }, { status: 404 });
    return NextResponse.json(mapTemplate(data));
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function PATCH(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const { id } = await params;
    const body = await request.json();
    const { data: existing, error: existingError } = await supabase
      .from("report_templates")
      .select("*")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (existingError || !existing) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    const validation = validateReportTemplatePayload({
      name: body.name ?? existing.name,
      source: body.source ?? existing.source,
      rowDimensions: body.rowDimensions ?? existing.row_dimensions,
      columnDimensions: body.columnDimensions ?? existing.column_dimensions,
      measures: body.measures ?? existing.measures,
      filters: body.filters ?? existing.filters,
      dateMode: body.dateMode ?? existing.date_mode,
    });
    if (!validation.ok) {
      return NextResponse.json({ error: validation.error }, { status: 400 });
    }
    const payload = validation.payload;

    const { data, error } = await supabase
      .from("report_templates")
      .update({
        name: payload.name,
        source: payload.source,
        row_dimensions: payload.rowDimensions,
        column_dimensions: payload.columnDimensions,
        measures: payload.measures,
        filters: payload.filters,
        date_mode: payload.dateMode,
      })
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .select()
      .single();

    if (error) return NextResponse.json({ error: error.message }, { status: 500 });
    return NextResponse.json(mapTemplate(data));
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const { id } = await params;
    const { error } = await supabase
      .from("report_templates")
      .delete()
      .eq("id", id)
      .eq("workspace_id", workspaceId);

    if (error) return NextResponse.json({ error: error.message }, { status: 500 });
    return NextResponse.json({ success: true });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

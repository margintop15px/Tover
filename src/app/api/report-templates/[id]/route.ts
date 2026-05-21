import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

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
    const updates: Record<string, unknown> = {};

    if (body.name !== undefined) updates.name = String(body.name).trim();
    if (body.source !== undefined) updates.source = body.source;
    if (body.rowDimensions !== undefined) updates.row_dimensions = body.rowDimensions;
    if (body.columnDimensions !== undefined) updates.column_dimensions = body.columnDimensions;
    if (body.measures !== undefined) updates.measures = body.measures;
    if (body.filters !== undefined) updates.filters = body.filters;
    if (body.dateMode !== undefined) updates.date_mode = body.dateMode;

    const { data, error } = await supabase
      .from("report_templates")
      .update(updates)
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

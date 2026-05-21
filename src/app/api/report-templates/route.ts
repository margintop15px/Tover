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

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);
    const source = searchParams.get("source");

    let query = supabase
      .from("report_templates")
      .select("*")
      .eq("workspace_id", workspaceId)
      .order("source")
      .order("name");

    if (source) query = query.eq("source", source);

    const { data, error } = await query;
    if (error) return NextResponse.json({ error: error.message }, { status: 500 });

    return NextResponse.json({ items: (data || []).map(mapTemplate) });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId, user } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = await request.json();
    const name = typeof body.name === "string" ? body.name.trim() : "";
    if (!name) {
      return NextResponse.json({ error: "Name is required" }, { status: 400 });
    }

    const { data, error } = await supabase
      .from("report_templates")
      .insert({
        workspace_id: workspaceId,
        name,
        source: body.source,
        row_dimensions: body.rowDimensions || [],
        column_dimensions: body.columnDimensions || [],
        measures: body.measures || [],
        filters: body.filters || {},
        date_mode: body.dateMode || "period",
        created_by: user.id,
      })
      .select()
      .single();

    if (error) {
      if (error.code === "23505") {
        return NextResponse.json({ error: "A report template with this name already exists" }, { status: 409 });
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json(mapTemplate(data), { status: 201 });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

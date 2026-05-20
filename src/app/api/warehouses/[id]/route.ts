import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { applyImportDefaultFlag } from "@/lib/master-data-import-defaults";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { id } = await params;

    const { data, error } = await supabase
      .from("warehouses")
      .select("*")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (error || !data) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    return NextResponse.json({
      id: data.id,
      name: data.name,
      description: data.description,
      purpose: data.purpose,
      isDefaultDefect: data.is_default_defect,
      isImportDefault: data.is_import_default,
      createdAt: data.created_at,
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
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const { id } = await params;
    const body = await request.json();

    const updates: Record<string, unknown> = {};
    if (body.name !== undefined) {
      const name = body.name?.trim();
      if (!name) {
        return NextResponse.json(
          { error: "Name cannot be empty" },
          { status: 400 }
        );
      }
      updates.name = name;
    }
    if (body.description !== undefined)
      updates.description = body.description?.trim() || null;
    if (body.purpose !== undefined) updates.purpose = body.purpose || null;
    const hasImportDefaultUpdate = typeof body.isImportDefault === "boolean";

    if (Object.keys(updates).length === 0 && !hasImportDefaultUpdate) {
      return NextResponse.json(
        { error: "No fields to update" },
        { status: 400 }
      );
    }

    const query =
      Object.keys(updates).length > 0
        ? supabase
            .from("warehouses")
            .update(updates)
            .eq("id", id)
            .eq("workspace_id", workspaceId)
            .select()
            .single()
        : supabase
            .from("warehouses")
            .select()
            .eq("id", id)
            .eq("workspace_id", workspaceId)
            .single();

    const { data, error } = await query;

    if (error) {
      if (error.code === "23505") {
        return NextResponse.json(
          { error: "A warehouse with this name already exists" },
          { status: 409 }
        );
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    if (!data) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    if (hasImportDefaultUpdate) {
      await applyImportDefaultFlag(
        supabase,
        "warehouses",
        workspaceId,
        id,
        body.isImportDefault
      );
      data.is_import_default = body.isImportDefault;
    }

    return NextResponse.json({
      id: data.id,
      name: data.name,
      description: data.description,
      purpose: data.purpose,
      isDefaultDefect: data.is_default_defect,
      isImportDefault: data.is_import_default,
      createdAt: data.created_at,
    });
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

    // Prevent deletion of default defect warehouse
    const { data: warehouse } = await supabase
      .from("warehouses")
      .select("is_default_defect")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (warehouse?.is_default_defect) {
      return NextResponse.json(
        { error: "Cannot delete the default defect warehouse" },
        { status: 400 }
      );
    }

    const { error } = await supabase
      .from("warehouses")
      .delete()
      .eq("id", id)
      .eq("workspace_id", workspaceId);

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({ success: true });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

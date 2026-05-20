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
      .from("suppliers")
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
      address: data.address,
      contactInfo: data.contact_info,
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
    if (body.address !== undefined)
      updates.address = body.address?.trim() || null;
    if (body.contactInfo !== undefined)
      updates.contact_info = body.contactInfo?.trim() || null;
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
            .from("suppliers")
            .update(updates)
            .eq("id", id)
            .eq("workspace_id", workspaceId)
            .select()
            .single()
        : supabase
            .from("suppliers")
            .select()
            .eq("id", id)
            .eq("workspace_id", workspaceId)
            .single();

    const { data, error } = await query;

    if (error) {
      if (error.code === "23505") {
        return NextResponse.json(
          { error: "A supplier with this name already exists" },
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
        "suppliers",
        workspaceId,
        id,
        body.isImportDefault
      );
      data.is_import_default = body.isImportDefault;
    }

    return NextResponse.json({
      id: data.id,
      name: data.name,
      address: data.address,
      contactInfo: data.contact_info,
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

    const { error } = await supabase
      .from("suppliers")
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

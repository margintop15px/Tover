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
      .from("stores")
      .select("*")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (error || !data) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    let defaultWarehouseName: string | null = null;
    if (data.default_warehouse_id) {
      const { data: warehouse } = await supabase
        .from("warehouses")
        .select("name")
        .eq("id", data.default_warehouse_id)
        .single();
      defaultWarehouseName = warehouse?.name ?? null;
    }

    return NextResponse.json({
      id: data.id,
      name: data.name,
      defaultWarehouseId: data.default_warehouse_id,
      defaultWarehouseName,
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
    if (body.defaultWarehouseId !== undefined) {
      updates.default_warehouse_id = body.defaultWarehouseId || null;
    }
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
            .from("stores")
            .update(updates)
            .eq("id", id)
            .eq("workspace_id", workspaceId)
            .select()
            .single()
        : supabase
            .from("stores")
            .select()
            .eq("id", id)
            .eq("workspace_id", workspaceId)
            .single();

    const { data, error } = await query;

    if (error) {
      if (error.code === "23505") {
        return NextResponse.json(
          { error: "A store with this name already exists" },
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
        "stores",
        workspaceId,
        id,
        body.isImportDefault
      );
      data.is_import_default = body.isImportDefault;
    }

    let defaultWarehouseName: string | null = null;
    if (data.default_warehouse_id) {
      const { data: warehouse } = await supabase
        .from("warehouses")
        .select("name")
        .eq("id", data.default_warehouse_id)
        .single();
      defaultWarehouseName = warehouse?.name ?? null;
    }

    return NextResponse.json({
      id: data.id,
      name: data.name,
      defaultWarehouseId: data.default_warehouse_id,
      defaultWarehouseName,
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
      .from("stores")
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

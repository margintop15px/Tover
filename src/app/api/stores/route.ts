import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { applyImportDefaultFlag } from "@/lib/master-data-import-defaults";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const limit = parseInt(searchParams.get("limit") || "50", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);

    const { data, error, count } = await supabase
      .from("stores")
      .select("*", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .order("name")
      .range(offset, offset + limit - 1);

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const warehouseIds = [
      ...new Set((data || []).map((row) => row.default_warehouse_id).filter(Boolean)),
    ] as string[];
    const { data: warehouses } =
      warehouseIds.length > 0
        ? await supabase.from("warehouses").select("id, name").in("id", warehouseIds)
        : { data: [] };
    const warehouseNames = new Map(
      (warehouses || []).map((warehouse) => [warehouse.id, warehouse.name])
    );

    return NextResponse.json({
      page: { limit, offset, totalEstimate: count },
      items: (data || []).map((r) => ({
        id: r.id,
        name: r.name,
        defaultWarehouseId: r.default_warehouse_id,
        defaultWarehouseName: r.default_warehouse_id
          ? warehouseNames.get(r.default_warehouse_id) ?? null
          : null,
        isImportDefault: r.is_import_default,
        createdAt: r.created_at,
      })),
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const body = await request.json();
    const name = body.name?.trim();

    if (!name) {
      return NextResponse.json(
        { error: "Name is required" },
        { status: 400 }
      );
    }

    const { data, error } = await supabase
      .from("stores")
      .insert({
        workspace_id: workspaceId,
        name,
        default_warehouse_id: body.defaultWarehouseId || null,
      })
      .select()
      .single();

    if (error) {
      if (error.code === "23505") {
        return NextResponse.json(
          { error: "A store with this name already exists" },
          { status: 409 }
        );
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    if (body.isImportDefault === true) {
      await applyImportDefaultFlag(
        supabase,
        "stores",
        workspaceId,
        data.id,
        true
      );
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

    return NextResponse.json(
      {
        id: data.id,
        name: data.name,
        defaultWarehouseId: data.default_warehouse_id,
        defaultWarehouseName,
        isImportDefault: body.isImportDefault === true,
        createdAt: data.created_at,
      },
      { status: 201 }
    );
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

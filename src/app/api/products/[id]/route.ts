import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { id } = await params;

    const { data, error } = await supabase
      .from("products")
      .select("*, categories(name), stores(name)")
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .single();

    if (error || !data) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    return NextResponse.json({
      id: data.id,
      name: data.name,
      skuCode: data.sku_code,
      categoryId: data.category_id,
      categoryName: (data.categories as { name: string } | null)?.name ?? null,
      storeId: data.store_id,
      storeName: (data.stores as { name: string } | null)?.name ?? null,
      isDefectCopy: data.is_defect_copy,
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
    if (body.skuCode !== undefined)
      updates.sku_code = body.skuCode?.trim() || null;
    if (body.categoryId !== undefined)
      updates.category_id = body.categoryId || null;
    if (body.storeId !== undefined) updates.store_id = body.storeId || null;

    // Check workspace settings for required fields
    const { data: wsSettings } = await supabase
      .from("workspace_settings")
      .select("category_required, store_required")
      .eq("workspace_id", workspaceId)
      .maybeSingle();

    if (
      wsSettings?.category_required &&
      body.categoryId !== undefined &&
      !body.categoryId
    ) {
      return NextResponse.json(
        { error: "Category is required" },
        { status: 400 }
      );
    }
    if (
      wsSettings?.store_required &&
      body.storeId !== undefined &&
      !body.storeId
    ) {
      return NextResponse.json(
        { error: "Store is required" },
        { status: 400 }
      );
    }

    if (Object.keys(updates).length === 0) {
      return NextResponse.json(
        { error: "No fields to update" },
        { status: 400 }
      );
    }

    const { data, error } = await supabase
      .from("products")
      .update(updates)
      .eq("id", id)
      .eq("workspace_id", workspaceId)
      .select("*, categories(name), stores(name)")
      .single();

    if (error) {
      if (error.code === "23505") {
        const field = error.message.includes("sku_code") ? "sku" : "name";
        return NextResponse.json(
          {
            error:
              field === "sku"
                ? "A product with this SKU already exists"
                : "A product with this name already exists",
            field,
          },
          { status: 409 }
        );
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    if (!data) {
      return NextResponse.json({ error: "Not found" }, { status: 404 });
    }

    return NextResponse.json({
      id: data.id,
      name: data.name,
      skuCode: data.sku_code,
      categoryId: data.category_id,
      categoryName: (data.categories as { name: string } | null)?.name ?? null,
      storeId: data.store_id,
      storeName: (data.stores as { name: string } | null)?.name ?? null,
      isDefectCopy: data.is_defect_copy,
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
      .from("products")
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

import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const limit = parseInt(searchParams.get("limit") || "50", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);
    const search = searchParams.get("search")?.trim();
    const categoryId = searchParams.get("categoryId");
    const storeId = searchParams.get("storeId");

    let query = supabase
      .from("products")
      .select("*, categories(name), stores(name)", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .eq("is_defect_copy", false)
      .order("name")
      .range(offset, offset + limit - 1);

    if (search) {
      query = query.or(`name.ilike.%${search}%,sku_code.ilike.%${search}%`);
    }
    if (categoryId) {
      query = query.eq("category_id", categoryId);
    }
    if (storeId) {
      query = query.eq("store_id", storeId);
    }

    const { data, error, count } = await query;

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({
      page: { limit, offset, totalEstimate: count },
      items: (data || []).map((r) => ({
        id: r.id,
        name: r.name,
        skuCode: r.sku_code,
        categoryId: r.category_id,
        categoryName: (r.categories as { name: string } | null)?.name ?? null,
        storeId: r.store_id,
        storeName: (r.stores as { name: string } | null)?.name ?? null,
        isDefectCopy: r.is_defect_copy,
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

    const insert: Record<string, unknown> = {
      workspace_id: workspaceId,
      name,
    };

    if (body.skuCode !== undefined)
      insert.sku_code = body.skuCode?.trim() || null;
    if (body.categoryId !== undefined)
      insert.category_id = body.categoryId || null;
    if (body.storeId !== undefined) insert.store_id = body.storeId || null;

    const { data, error } = await supabase
      .from("products")
      .insert(insert)
      .select("*, categories(name), stores(name)")
      .single();

    if (error) {
      if (error.code === "23505") {
        return NextResponse.json(
          { error: "A product with this SKU already exists" },
          { status: 409 }
        );
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json(
      {
        id: data.id,
        name: data.name,
        skuCode: data.sku_code,
        categoryId: data.category_id,
        categoryName:
          (data.categories as { name: string } | null)?.name ?? null,
        storeId: data.store_id,
        storeName: (data.stores as { name: string } | null)?.name ?? null,
        isDefectCopy: data.is_defect_copy,
        createdAt: data.created_at,
      },
      { status: 201 }
    );
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

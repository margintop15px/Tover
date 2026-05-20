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
      .from("suppliers")
      .select("*", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .order("name")
      .range(offset, offset + limit - 1);

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({
      page: { limit, offset, totalEstimate: count },
      items: (data || []).map((r) => ({
        id: r.id,
        name: r.name,
        address: r.address,
        contactInfo: r.contact_info,
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

    const insert: Record<string, unknown> = {
      workspace_id: workspaceId,
      name,
    };

    if (body.address !== undefined)
      insert.address = body.address?.trim() || null;
    if (body.contactInfo !== undefined)
      insert.contact_info = body.contactInfo?.trim() || null;

    const { data, error } = await supabase
      .from("suppliers")
      .insert(insert)
      .select()
      .single();

    if (error) {
      if (error.code === "23505") {
        return NextResponse.json(
          { error: "A supplier with this name already exists" },
          { status: 409 }
        );
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    if (body.isImportDefault === true) {
      await applyImportDefaultFlag(
        supabase,
        "suppliers",
        workspaceId,
        data.id,
        true
      );
    }

    return NextResponse.json(
      {
        id: data.id,
        name: data.name,
        address: data.address,
        contactInfo: data.contact_info,
        isImportDefault: body.isImportDefault === true,
        createdAt: data.created_at,
      },
      { status: 201 }
    );
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

const DEFAULT_SETTINGS = {
  currency: "EUR",
  categoryRequired: false,
  defaultCategoryId: null,
  storeRequired: false,
  defaultStoreId: null,
};

const VALID_CURRENCY = /^[A-Z]{3}$/;

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);

    const { data, error } = await supabase
      .from("workspace_settings")
      .select("*")
      .eq("workspace_id", workspaceId)
      .maybeSingle();

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    if (!data) {
      return NextResponse.json(DEFAULT_SETTINGS);
    }

    return NextResponse.json({
      currency: data.currency,
      categoryRequired: data.category_required,
      defaultCategoryId: data.default_category_id,
      storeRequired: data.store_required,
      defaultStoreId: data.default_store_id,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function PATCH(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const body = await request.json();

    // Fetch current settings for comparison
    const { data: current } = await supabase
      .from("workspace_settings")
      .select("*")
      .eq("workspace_id", workspaceId)
      .maybeSingle();

    const currentCategoryRequired = current?.category_required ?? false;
    const currentStoreRequired = current?.store_required ?? false;

    // Build upsert payload
    const upsert: Record<string, unknown> = {
      workspace_id: workspaceId,
      currency: current?.currency ?? "EUR",
      category_required: currentCategoryRequired,
      default_category_id: current?.default_category_id ?? null,
      store_required: currentStoreRequired,
      default_store_id: current?.default_store_id ?? null,
    };

    // Validate and apply currency
    if (body.currency !== undefined) {
      const currency = String(body.currency).toUpperCase();
      if (!VALID_CURRENCY.test(currency)) {
        return NextResponse.json(
          { error: "Currency must be a 3-letter ISO code" },
          { status: 400 }
        );
      }
      upsert.currency = currency;
    }

    // Validate and apply category_required
    if (body.categoryRequired !== undefined) {
      const enabling = body.categoryRequired === true && !currentCategoryRequired;

      if (enabling) {
        if (!body.defaultCategoryId) {
          return NextResponse.json(
            { error: "Default category is required when enabling category requirement" },
            { status: 400 }
          );
        }
        // Verify category belongs to workspace
        const { data: cat } = await supabase
          .from("categories")
          .select("id")
          .eq("id", body.defaultCategoryId)
          .eq("workspace_id", workspaceId)
          .maybeSingle();

        if (!cat) {
          return NextResponse.json(
            { error: "Default category not found in this workspace" },
            { status: 400 }
          );
        }

        // Backfill products with NULL category_id
        const { error: backfillError } = await supabase
          .from("products")
          .update({ category_id: body.defaultCategoryId })
          .eq("workspace_id", workspaceId)
          .is("category_id", null)
          .eq("is_defect_copy", false);

        if (backfillError) {
          return NextResponse.json(
            { error: "Failed to backfill products: " + backfillError.message },
            { status: 500 }
          );
        }
      }

      upsert.category_required = body.categoryRequired;
      if (body.defaultCategoryId !== undefined) {
        upsert.default_category_id = body.defaultCategoryId || null;
      }
    }

    // Validate and apply store_required
    if (body.storeRequired !== undefined) {
      const enabling = body.storeRequired === true && !currentStoreRequired;

      if (enabling) {
        if (!body.defaultStoreId) {
          return NextResponse.json(
            { error: "Default store is required when enabling store requirement" },
            { status: 400 }
          );
        }
        // Verify store belongs to workspace
        const { data: store } = await supabase
          .from("stores")
          .select("id")
          .eq("id", body.defaultStoreId)
          .eq("workspace_id", workspaceId)
          .maybeSingle();

        if (!store) {
          return NextResponse.json(
            { error: "Default store not found in this workspace" },
            { status: 400 }
          );
        }

        // Backfill products with NULL store_id
        const { error: backfillError } = await supabase
          .from("products")
          .update({ store_id: body.defaultStoreId })
          .eq("workspace_id", workspaceId)
          .is("store_id", null)
          .eq("is_defect_copy", false);

        if (backfillError) {
          return NextResponse.json(
            { error: "Failed to backfill products: " + backfillError.message },
            { status: 500 }
          );
        }
      }

      upsert.store_required = body.storeRequired;
      if (body.defaultStoreId !== undefined) {
        upsert.default_store_id = body.defaultStoreId || null;
      }
    }

    // Upsert settings
    const { data, error } = await supabase
      .from("workspace_settings")
      .upsert(upsert, { onConflict: "workspace_id" })
      .select("*")
      .single();

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({
      currency: data.currency,
      categoryRequired: data.category_required,
      defaultCategoryId: data.default_category_id,
      storeRequired: data.store_required,
      defaultStoreId: data.default_store_id,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

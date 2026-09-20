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
    const { data: current, error: currentError } = await supabase
      .from("workspace_settings")
      .select("*")
      .eq("workspace_id", workspaceId)
      .maybeSingle();

    if (currentError) throw new Error(currentError.message);

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

    const defaults = [
      { field: "category", table: "categories", requiredKey: "categoryRequired", defaultKey: "defaultCategoryId" },
      { field: "store", table: "stores", requiredKey: "storeRequired", defaultKey: "defaultStoreId" },
    ] as const;

    // Validate the complete next state before performing any writes or backfills.
    for (const { field, table, requiredKey, defaultKey } of defaults) {
      if (body[requiredKey] !== undefined) {
        if (typeof body[requiredKey] !== "boolean") {
          return NextResponse.json({ error: `${requiredKey} must be a boolean` }, { status: 400 });
        }
        upsert[`${field}_required`] = body[requiredKey];
      }
      if (body[defaultKey] !== undefined) {
        if (body[defaultKey] !== null && typeof body[defaultKey] !== "string") {
          return NextResponse.json({ error: `${defaultKey} must be an ID or null` }, { status: 400 });
        }
        upsert[`default_${field}_id`] = body[defaultKey] || null;
      }
      const defaultId = upsert[`default_${field}_id`];
      if (upsert[`${field}_required`] && !defaultId) {
        return NextResponse.json({ error: `Default ${field} is required when ${field} is required` }, { status: 400 });
      }
      if (defaultId) {
        const { data: entity, error: lookupError } = await supabase
          .from(table)
          .select("id")
          .eq("id", defaultId)
          .eq("workspace_id", workspaceId)
          .maybeSingle();
        if (lookupError) throw new Error(lookupError.message);
        if (!entity) {
          return NextResponse.json({ error: `Default ${field} not found in this workspace` }, { status: 400 });
        }
      }
    }

    for (const { field } of defaults) {
      if (upsert[`${field}_required`] && !current?.[`${field}_required`]) {
        const { error: backfillError } = await supabase
          .from("products")
          .update({ [`${field}_id`]: upsert[`default_${field}_id`] })
          .eq("workspace_id", workspaceId)
          .is(`${field}_id`, null)
          .eq("is_defect_copy", false);
        if (backfillError) throw new Error(backfillError.message);
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

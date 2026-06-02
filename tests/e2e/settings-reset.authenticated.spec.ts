import { expect, test, type APIRequestContext } from "@playwright/test";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import {
  authSkipReason,
  hasAuthCredentials,
  loadLocalEnv,
} from "./auth-helpers";

const RUN_ID = Date.now().toString(36);

interface ResetSummary {
  canReset: boolean;
  role: string;
  total: number;
  groups: Record<string, number>;
}

function uniqueName(prefix: string) {
  return `E2E-Reset-${prefix}-${RUN_ID}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
}

test.describe("account data reset", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
  });

  test("rejects unauthenticated reset requests", async ({ playwright, baseURL }) => {
    const context = await playwright.request.newContext({
      baseURL,
      storageState: { cookies: [], origins: [] },
    });
    try {
      const response = await context.post("/api/settings/reset-data", {
        data: { confirmation: "RESET" },
      });
      expect(response.status()).toBe(401);
    } finally {
      await context.dispose();
    }
  });

  test("shows owner-only reset dialog and requires typed confirmation", async ({
    page,
    request,
  }) => {
    const summary = await getResetSummary(request);

    await page.goto("/settings");
    await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
    await expect(page.getByText("Danger zone")).toBeVisible();
    await expect(page.getByText("Remove all account data")).toBeVisible();

    if (!summary.canReset) {
      await expect(
        page.getByText("Only workspace owners can remove all account data.")
      ).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Remove all account data" })
      ).toBeDisabled();
      return;
    }

    await page.getByRole("button", { name: "Remove all account data" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(
      dialog.getByRole("heading", { name: "Remove all account data?" })
    ).toBeVisible();
    await expect(dialog.getByText("Will be deleted", { exact: true })).toBeVisible();
    await expect(dialog.getByText("Will be preserved", { exact: true })).toBeVisible();

    const destructiveButton = dialog.getByRole("button", {
      name: "Remove all account data",
    });
    await expect(destructiveButton).toBeDisabled();

    await dialog.getByLabel("Type RESET to confirm").fill("reset");
    await expect(destructiveButton).toBeDisabled();

    await dialog.getByLabel("Type RESET to confirm").fill("RESET");
    await expect(destructiveButton).toBeEnabled();

    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).not.toBeVisible();
  });

  test("resets only the selected workspace and preserves shell data", async ({
    request,
  }) => {
    const admin = getAdminClient();
    test.skip(!admin, adminSkipReason());

    const me = await getCurrentUser(request);
    const workspaceId = await createWorkspace(admin!, me.id, "admin");
    const otherWorkspaceId = await createWorkspace(admin!, me.id, "owner");

    try {
      const schemaSupported = await supportsResetSchema(admin!);
      test.skip(
        !schemaSupported,
        "Local Supabase schema is missing migration 016_workspace_data_reset.sql"
      );

      await seedResetData(admin!, workspaceId);
      await seedMinimalOtherWorkspaceData(admin!, otherWorkspaceId);

      const adminDenied = await request.post(
        `/api/settings/reset-data?workspaceId=${workspaceId}`,
        { data: { confirmation: "RESET" } }
      );
      expect(adminDenied.status()).toBe(403);

      await admin!
        .from("organization_memberships")
        .update({ role_id: "owner" })
        .eq("organization_id", workspaceId)
        .eq("user_id", me.id);

      const wrongConfirmation = await request.post(
        `/api/settings/reset-data?workspaceId=${workspaceId}`,
        { data: { confirmation: "WRONG" } }
      );
      expect(wrongConfirmation.status()).toBe(400);

      const before = await getResetSummary(
        request,
        `?workspaceId=${workspaceId}`
      );
      expect(before.total).toBeGreaterThan(0);

      const response = await request.post(
        `/api/settings/reset-data?workspaceId=${workspaceId}`,
        { data: { confirmation: "RESET" } }
      );
      expect(response.status(), await response.text()).toBe(200);

      await expectWorkspaceCount(admin!, "operations", workspaceId, 0);
      await expectWorkspaceCount(admin!, "products", workspaceId, 0);
      await expectWorkspaceCount(admin!, "categories", workspaceId, 0);
      await expectWorkspaceCount(admin!, "stores", workspaceId, 0);
      await expectWorkspaceCount(admin!, "warehouses", workspaceId, 0);
      await expectWorkspaceCount(admin!, "suppliers", workspaceId, 0);
      await expectWorkspaceCount(admin!, "product_balances", workspaceId, 0);
      await expectWorkspaceCount(admin!, "report_templates", workspaceId, 0);
      await expectWorkspaceCount(admin!, "imports", workspaceId, 0);
      await expectWorkspaceCount(admin!, "orders", workspaceId, 0);
      await expectWorkspaceCount(
        admin!,
        "marketplace_operation_candidates",
        workspaceId,
        0
      );
      await expectWorkspaceCount(admin!, "ozon_products", workspaceId, 0);

      const { data: connection } = await admin!
        .from("marketplace_connections")
        .select("credential_ciphertext, last_sync_at, last_sync_status, last_sync_error")
        .eq("workspace_id", workspaceId)
        .single();
      expect(connection?.credential_ciphertext).toEqual({ test: "secret" });
      expect(connection?.last_sync_at).toBeNull();
      expect(connection?.last_sync_status).toBeNull();
      expect(connection?.last_sync_error).toBeNull();

      await expectWorkspaceCount(
        admin!,
        "organization_memberships",
        workspaceId,
        1,
        "organization_id"
      );
      await expectWorkspaceCount(admin!, "workspace_settings", workspaceId, 1);
      await expectWorkspaceCount(admin!, "products", otherWorkspaceId, 1);
    } finally {
      await admin!.from("organizations").delete().eq("id", workspaceId);
      await admin!.from("organizations").delete().eq("id", otherWorkspaceId);
    }
  });
});

async function getResetSummary(
  request: APIRequestContext,
  query = ""
): Promise<ResetSummary> {
  const response = await request.get(`/api/settings/reset-data${query}`);
  expect(response.status(), await response.text()).toBe(200);
  return (await response.json()) as ResetSummary;
}

async function getCurrentUser(request: APIRequestContext) {
  const response = await request.get("/api/auth/me");
  expect(response.status(), await response.text()).toBe(200);
  const payload = await response.json();
  return payload.user as { id: string; email: string };
}

function getAdminClient() {
  loadLocalEnv();
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const serviceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!url || !serviceRoleKey) return null;

  return createClient(url, serviceRoleKey, {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
    },
  });
}

function adminSkipReason() {
  return "Set NEXT_PUBLIC_SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY to run reset data tests.";
}

async function supportsResetSchema(admin: SupabaseClient) {
  const { error } = await admin.rpc("reset_workspace_account_data", {
    p_workspace_id: "00000000-0000-0000-0000-000000000000",
    p_confirmation: "WRONG",
  });

  return error?.code === "22023";
}

async function createWorkspace(
  admin: SupabaseClient,
  userId: string,
  role: "owner" | "admin"
) {
  const { data: org, error: orgError } = await admin
    .from("organizations")
    .insert({ name: uniqueName("Workspace"), created_by: userId })
    .select("id")
    .single();
  expect(orgError).toBeNull();

  const workspaceId = org!.id as string;
  const { error: membershipError } = await admin
    .from("organization_memberships")
    .insert({
      organization_id: workspaceId,
      user_id: userId,
      role_id: role,
      status: "active",
    });
  expect(membershipError).toBeNull();

  const { error: settingsError } = await admin
    .from("workspace_settings")
    .insert({
      workspace_id: workspaceId,
      currency: "EUR",
      category_required: true,
      store_required: true,
    });
  expect(settingsError).toBeNull();

  return workspaceId;
}

async function seedResetData(admin: SupabaseClient, workspaceId: string) {
  const { data: category } = await admin
    .from("categories")
    .insert({ workspace_id: workspaceId, name: uniqueName("Category") })
    .select("id")
    .single();
  const { data: store } = await admin
    .from("stores")
    .insert({ workspace_id: workspaceId, name: uniqueName("Store") })
    .select("id")
    .single();
  const { data: warehouse } = await admin
    .from("warehouses")
    .insert({ workspace_id: workspaceId, name: uniqueName("Warehouse") })
    .select("id")
    .single();
  const { data: supplier } = await admin
    .from("suppliers")
    .insert({ workspace_id: workspaceId, name: uniqueName("Supplier") })
    .select("id")
    .single();
  const { data: product } = await admin
    .from("products")
    .insert({
      workspace_id: workspaceId,
      name: uniqueName("Product"),
      sku_code: uniqueName("SKU"),
      category_id: category!.id,
      store_id: store!.id,
    })
    .select("id")
    .single();
  const { data: operation } = await admin
    .from("operations")
    .insert({
      workspace_id: workspaceId,
      type: "purchase",
      operation_date: "2099-01-01",
      supplier_id: supplier!.id,
    })
    .select("id")
    .single();
  await admin.from("operation_items").insert({
    operation_id: operation!.id,
    product_id: product!.id,
    warehouse_id: warehouse!.id,
    quantity: 1,
    unit_price: 10,
    direction: "in",
    store_id: store!.id,
  });
  await admin.from("product_balances").insert({
    workspace_id: workspaceId,
    product_id: product!.id,
    warehouse_id: warehouse!.id,
    quantity: 1,
    unit_cost: 10,
  });
  await admin.from("report_templates").insert({
    workspace_id: workspaceId,
    name: uniqueName("Report"),
    source: "inventory_balances",
  });
  const { data: importRun } = await admin
    .from("imports")
    .insert({
      workspace_id: workspaceId,
      file_path: "reset-test.csv",
      import_type: "orders",
    })
    .select("id")
    .single();
  await admin.from("import_errors").insert({
    import_id: importRun!.id,
    row_number: 1,
    error_code: "test",
    error_detail: "test",
    raw_row: {},
  });
  const { data: order } = await admin
    .from("orders")
    .insert({
      workspace_id: workspaceId,
      source: "test",
      external_order_id: uniqueName("Order"),
      ordered_at: "2099-01-01T00:00:00Z",
      currency: "EUR",
      status: "created",
    })
    .select("id")
    .single();
  await admin.from("order_lines").insert({
    order_id: order!.id,
    sku: "SKU",
    quantity: 1,
    unit_price_gross: 10,
  });
  await admin.from("payments").insert({
    workspace_id: workspaceId,
    source: "test",
    external_payment_id: uniqueName("Payment"),
    order_id: order!.id,
    amount: 10,
    currency: "EUR",
  });
  await admin.from("inventory_snapshots").insert({
    workspace_id: workspaceId,
    snapshot_date: "2099-01-01",
    sku: "SKU",
    on_hand_qty: 1,
    unit_cost: 10,
  });

  const { data: connection } = await admin
    .from("marketplace_connections")
    .insert({
      workspace_id: workspaceId,
      provider: "ozon",
      name: "Ozon",
      credential_ciphertext: { test: "secret" },
      status: "connected",
      last_sync_at: "2099-01-01T00:00:00Z",
      last_sync_status: "completed",
      last_sync_error: "old error",
    })
    .select("id")
    .single();
  await admin.from("marketplace_sync_runs").insert({
    workspace_id: workspaceId,
    connection_id: connection!.id,
    provider: "ozon",
    status: "completed",
  });
  const { data: candidate } = await admin
    .from("marketplace_operation_candidates")
    .insert({
      workspace_id: workspaceId,
      connection_id: connection!.id,
      provider: "ozon",
      source_type: "posting",
      external_event_id: uniqueName("event"),
      status: "ready",
      operation_type: "sale",
      operation_date: "2099-01-01",
    })
    .select("id")
    .single();
  await admin.from("marketplace_operation_commit_claims").insert({
    workspace_id: workspaceId,
    connection_id: connection!.id,
    candidate_id: candidate!.id,
    provider: "ozon",
    source_type: "posting",
    external_event_id: uniqueName("claim"),
  });
  await admin.from("ozon_products").insert({
    workspace_id: workspaceId,
    connection_id: connection!.id,
    ozon_product_id: uniqueName("ozon-product"),
    offer_id: "offer",
    local_product_id: product!.id,
  });
  await admin.from("ozon_warehouses").insert({
    workspace_id: workspaceId,
    connection_id: connection!.id,
    ozon_warehouse_id: uniqueName("ozon-warehouse"),
    name: "Ozon Warehouse",
    local_warehouse_id: warehouse!.id,
  });
}

async function seedMinimalOtherWorkspaceData(
  admin: SupabaseClient,
  workspaceId: string
) {
  await admin.from("products").insert({
    workspace_id: workspaceId,
    name: uniqueName("OtherProduct"),
  });
}

async function expectWorkspaceCount(
  admin: SupabaseClient,
  table: string,
  workspaceId: string,
  expected: number,
  workspaceColumn = "workspace_id"
) {
  const { count, error } = await admin
    .from(table)
    .select("id", { count: "exact", head: true })
    .eq(workspaceColumn, workspaceId);
  expect(error).toBeNull();
  expect(count).toBe(expected);
}

import { createClient, type SupabaseClient, type User } from "@supabase/supabase-js";
import { processOperation } from "../src/lib/operations";
import type { CreateOperationRequest } from "../src/types/inventory";

const SAMPLE_PREFIX = "Report QA seed:";

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

function serviceClient(): SupabaseClient {
  return createClient(
    requiredEnv("NEXT_PUBLIC_SUPABASE_URL"),
    requiredEnv("SUPABASE_SERVICE_ROLE_KEY"),
    {
      auth: {
        autoRefreshToken: false,
        persistSession: false,
      },
    }
  );
}

async function findUser(admin: SupabaseClient): Promise<User> {
  const email = requiredEnv("E2E_EMAIL").toLowerCase();
  const { data, error } = await admin.auth.admin.listUsers({
    page: 1,
    perPage: 1000,
  });
  if (error) throw error;
  const user = data.users.find((item) => item.email?.toLowerCase() === email);
  if (!user) throw new Error(`User ${email} not found`);
  return user;
}

async function findWorkspace(admin: SupabaseClient, userId: string): Promise<string> {
  const explicitWorkspaceId = process.env.SAMPLE_WORKSPACE_ID || process.argv[2];
  if (explicitWorkspaceId) {
    const { data: organization, error: organizationError } = await admin
      .from("organizations")
      .select("id")
      .eq("id", explicitWorkspaceId)
      .maybeSingle();
    if (organizationError) throw organizationError;
    if (!organization?.id) {
      throw new Error(`SAMPLE_WORKSPACE_ID=${explicitWorkspaceId} does not exist.`);
    }
    return explicitWorkspaceId;
  }

  const envWorkspaceId = process.env.DEFAULT_WORKSPACE_ID;
  if (envWorkspaceId) {
    const { data: organization, error: organizationError } = await admin
      .from("organizations")
      .select("id")
      .eq("id", envWorkspaceId)
      .maybeSingle();
    if (organizationError) throw organizationError;
    if (organization?.id) return envWorkspaceId;
    console.warn(`Ignoring DEFAULT_WORKSPACE_ID=${envWorkspaceId}; organization does not exist.`);
  }

  const { data, error } = await admin
    .from("organization_memberships")
    .select("organization_id")
    .eq("user_id", userId)
    .eq("status", "active")
    .limit(1)
    .single();

  if (error) throw error;
  if (!data?.organization_id) throw new Error("No active workspace found");
  return data.organization_id as string;
}

async function upsertNamed(
  admin: SupabaseClient,
  table: string,
  workspaceId: string,
  values: Record<string, unknown>
) {
  const writeValues = { ...values };
  const { data: existing, error: findError } = await admin
    .from(table)
    .select("id")
    .eq("workspace_id", workspaceId)
    .eq("name", values.name)
    .limit(1)
    .maybeSingle();

  if (findError) throw findError;
  if (existing?.id) {
    const { data, error } = await admin
      .from(table)
      .update(writeValues)
      .eq("id", existing.id)
      .select("id")
      .single();
    if (error) {
      if (
        table === "stores" &&
        error.code === "PGRST204" &&
        error.message.includes("default_warehouse_id")
      ) {
        delete writeValues.default_warehouse_id;
        const fallback = await admin
          .from(table)
          .update(writeValues)
          .eq("id", existing.id)
          .select("id")
          .single();
        if (fallback.error) throw fallback.error;
        return fallback.data.id as string;
      }
      throw error;
    }
    return data.id as string;
  }

  const { data, error } = await admin
    .from(table)
    .insert({ workspace_id: workspaceId, ...writeValues })
    .select("id")
    .single();
  if (error) {
    if (
      table === "stores" &&
      error.code === "PGRST204" &&
      error.message.includes("default_warehouse_id")
    ) {
      delete writeValues.default_warehouse_id;
      const fallback = await admin
        .from(table)
        .insert({ workspace_id: workspaceId, ...writeValues })
        .select("id")
        .single();
      if (fallback.error) throw fallback.error;
      return fallback.data.id as string;
    }
    throw error;
  }
  return data.id as string;
}

async function deletePreviousSample(admin: SupabaseClient, workspaceId: string) {
  const { data: operations, error } = await admin
    .from("operations")
    .select("id")
    .eq("workspace_id", workspaceId)
    .like("comment", `${SAMPLE_PREFIX}%`);

  if (error) throw error;
  const ids = (operations || []).map((operation) => operation.id as string);
  if (ids.length === 0) return 0;

  const { error: deleteError } = await admin
    .from("operations")
    .delete()
    .in("id", ids);
  if (deleteError) throw deleteError;

  const { error: rebuildError } = await admin.rpc("rebuild_inventory_reporting", {
    p_workspace_id: workspaceId,
  });
  if (
    rebuildError &&
    !rebuildError.message.includes("rebuild_inventory_reporting")
  ) {
    throw rebuildError;
  }

  return ids.length;
}

async function hasReportingSchema(admin: SupabaseClient) {
  const { error } = await admin
    .from("operation_items")
    .insert({
      operation_id: "00000000-0000-0000-0000-000000000000",
      product_id: "00000000-0000-0000-0000-000000000000",
      warehouse_id: "00000000-0000-0000-0000-000000000000",
      quantity: 1,
      direction: "in",
      quality_status: "ordinary",
    });
  return !(
    error &&
    error.code === "PGRST204" &&
    error.message.includes("quality_status")
  );
}

async function createOperation(
  admin: SupabaseClient,
  workspaceId: string,
  body: CreateOperationRequest
) {
  const result = await processOperation(admin, workspaceId, body);
  if (result.errors) {
    throw new Error(JSON.stringify(result.errors));
  }
  return result.operation?.id as string;
}

async function createLegacyOperation(
  admin: SupabaseClient,
  workspaceId: string,
  body: CreateOperationRequest
) {
  const { data: operation, error: opError } = await admin
    .from("operations")
    .insert({
      workspace_id: workspaceId,
      type: body.type,
      operation_date: body.operationDate,
      comment: body.comment || null,
      supplier_id: body.supplierId || null,
      payment_amount: body.paymentAmount || null,
    })
    .select("id")
    .single();
  if (opError) throw opError;

  const operationId = operation.id as string;
  const itemRows =
    body.type === "payment"
      ? []
      : (body.items || []).map((item) => ({
          operation_id: operationId,
          product_id: item.productId,
          warehouse_id: item.warehouseId,
          quantity: item.quantity,
          unit_price: item.unitPrice || null,
          direction:
            item.direction ||
            (body.type === "return" || body.type === "purchase" ? "in" : "out"),
          store_id: item.storeId || null,
        }));

  if (itemRows.length > 0) {
    const { error: itemError } = await admin
      .from("operation_items")
      .insert(itemRows);
    if (itemError) throw itemError;
  }

  for (const item of itemRows) {
    if (body.type === "purchase") {
      const { error } = await admin.rpc("process_purchase_balance", {
        p_workspace_id: workspaceId,
        p_product_id: item.product_id,
        p_warehouse_id: item.warehouse_id,
        p_purchase_qty: item.quantity,
        p_purchase_unit_price: item.unit_price || 0,
      });
      if (error) throw error;
    } else {
      const qtyDelta = item.direction === "in" ? item.quantity : -item.quantity;
      const { error } = await admin.rpc("update_product_balance", {
        p_workspace_id: workspaceId,
        p_product_id: item.product_id,
        p_warehouse_id: item.warehouse_id,
        p_qty_delta: qtyDelta,
        p_new_unit_cost: null,
      });
      if (error) throw error;
    }
  }

  return operationId;
}

async function main() {
  const admin = serviceClient();
  const user = await findUser(admin);
  const workspaceId = await findWorkspace(admin, user.id);

  console.log(`Workspace: ${workspaceId}`);
  const deletedCount = await deletePreviousSample(admin, workspaceId);
  console.log(`Removed previous sample operations: ${deletedCount}`);
  const reportingSchema = await hasReportingSchema(admin);
  if (!reportingSchema) {
    console.warn(
      "Reporting migration 011 is not applied; creating legacy operation rows without quality/ledger data."
    );
  }

  const categories = {
    marketplace: await upsertNamed(admin, "categories", workspaceId, {
      name: "Report QA / Marketplace",
    }),
    produce: await upsertNamed(admin, "categories", workspaceId, {
      name: "Report QA / Fresh Produce",
    }),
  };

  const warehouses = {
    ozon: await upsertNamed(admin, "warehouses", workspaceId, {
      name: "Report QA / OZON",
      purpose: "storage",
      description: "Shared marketplace warehouse for multiple cabinets",
    }),
    centro: await upsertNamed(admin, "warehouses", workspaceId, {
      name: "Report QA / Fruteria Centro Stock",
      purpose: "sales",
      description: "Physical branch stock",
    }),
    norte: await upsertNamed(admin, "warehouses", workspaceId, {
      name: "Report QA / Fruteria Norte Stock",
      purpose: "sales",
      description: "Physical branch stock",
    }),
  };

  const stores = {
    ozonAlpha: await upsertNamed(admin, "stores", workspaceId, {
      name: "Report QA / OZON Cabinet Alpha",
      default_warehouse_id: warehouses.ozon,
    }),
    ozonBeta: await upsertNamed(admin, "stores", workspaceId, {
      name: "Report QA / OZON Cabinet Beta",
      default_warehouse_id: warehouses.ozon,
    }),
    centro: await upsertNamed(admin, "stores", workspaceId, {
      name: "Report QA / Fruteria Centro",
      default_warehouse_id: warehouses.centro,
    }),
    norte: await upsertNamed(admin, "stores", workspaceId, {
      name: "Report QA / Fruteria Norte",
      default_warehouse_id: warehouses.norte,
    }),
  };

  const suppliers = {
    marketplace: await upsertNamed(admin, "suppliers", workspaceId, {
      name: "Report QA / Marketplace Import LLC",
      contact_info: "supply@example.test",
    }),
    fruit: await upsertNamed(admin, "suppliers", workspaceId, {
      name: "Report QA / Iberia Fruit Coop",
      contact_info: "fruit@example.test",
    }),
  };

  const products = {
    alphaContainer: await upsertNamed(admin, "products", workspaceId, {
      name: "Report QA / Alpha Silicone Food Container",
      sku_code: "RQA-OZ-A-CONTAINER",
      category_id: categories.marketplace,
      store_id: stores.ozonAlpha,
    }),
    betaContainer: await upsertNamed(admin, "products", workspaceId, {
      name: "Report QA / Beta Silicone Food Container",
      sku_code: "RQA-OZ-B-CONTAINER",
      category_id: categories.marketplace,
      store_id: stores.ozonBeta,
    }),
    alphaScale: await upsertNamed(admin, "products", workspaceId, {
      name: "Report QA / Alpha Kitchen Scale",
      sku_code: "RQA-OZ-A-SCALE",
      category_id: categories.marketplace,
      store_id: stores.ozonAlpha,
    }),
    bananasCentro: await upsertNamed(admin, "products", workspaceId, {
      name: "Report QA / Centro Bananas",
      sku_code: "RQA-FR-C-BANANA",
      category_id: categories.produce,
      store_id: stores.centro,
    }),
    applesNorte: await upsertNamed(admin, "products", workspaceId, {
      name: "Report QA / Norte Apples",
      sku_code: "RQA-FR-N-APPLE",
      category_id: categories.produce,
      store_id: stores.norte,
    }),
  };

  const operations: CreateOperationRequest[] = [
    {
      type: "purchase",
      operationDate: "2026-04-20",
      comment: `${SAMPLE_PREFIX} opening stock`,
      supplierId: suppliers.marketplace,
      items: [
        { productId: products.alphaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 120, unitPrice: 8 },
        { productId: products.betaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonBeta, quantity: 90, unitPrice: 8.4 },
        { productId: products.alphaScale, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 35, unitPrice: 14.5 },
      ],
    },
    {
      type: "purchase",
      operationDate: "2026-04-21",
      comment: `${SAMPLE_PREFIX} fruteria opening stock`,
      supplierId: suppliers.fruit,
      items: [
        { productId: products.bananasCentro, warehouseId: warehouses.centro, storeId: stores.centro, quantity: 300, unitPrice: 1.1 },
        { productId: products.applesNorte, warehouseId: warehouses.norte, storeId: stores.norte, quantity: 220, unitPrice: 1.4 },
      ],
    },
    {
      type: "sale",
      operationDate: "2026-04-25",
      comment: `${SAMPLE_PREFIX} first sales wave`,
      items: [
        { productId: products.alphaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 35 },
        { productId: products.betaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonBeta, quantity: 20 },
        { productId: products.bananasCentro, warehouseId: warehouses.centro, storeId: stores.centro, quantity: 90 },
      ],
    },
    {
      type: "return",
      operationDate: "2026-04-28",
      comment: `${SAMPLE_PREFIX} customer returns`,
      items: [
        { productId: products.alphaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 3 },
        { productId: products.bananasCentro, warehouseId: warehouses.centro, storeId: stores.centro, quantity: 5 },
      ],
    },
    {
      type: "purchase",
      operationDate: "2026-05-02",
      comment: `${SAMPLE_PREFIX} replenishment with changed cost`,
      supplierId: suppliers.marketplace,
      items: [
        { productId: products.alphaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 80, unitPrice: 9 },
        { productId: products.betaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonBeta, quantity: 40, unitPrice: 8.8 },
      ],
    },
    {
      type: "purchase",
      operationDate: "2026-05-03",
      comment: `${SAMPLE_PREFIX} fresh produce replenishment`,
      supplierId: suppliers.fruit,
      items: [
        { productId: products.bananasCentro, warehouseId: warehouses.centro, storeId: stores.centro, quantity: 180, unitPrice: 1.25 },
        { productId: products.applesNorte, warehouseId: warehouses.norte, storeId: stores.norte, quantity: 140, unitPrice: 1.55 },
      ],
    },
    {
      type: "sale",
      operationDate: "2026-05-08",
      comment: `${SAMPLE_PREFIX} may sales by store`,
      items: [
        { productId: products.alphaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 50 },
        { productId: products.alphaScale, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 11 },
        { productId: products.betaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonBeta, quantity: 28 },
        { productId: products.applesNorte, warehouseId: warehouses.norte, storeId: stores.norte, quantity: 80 },
      ],
    },
    ...(reportingSchema
      ? [
          {
            type: "defect",
            operationDate: "2026-05-10",
            comment: `${SAMPLE_PREFIX} damaged fresh stock`,
            productId: products.bananasCentro,
            sourceWarehouseId: warehouses.centro,
            quantity: 8,
          } as CreateOperationRequest,
          {
            type: "write_off",
            operationDate: "2026-05-11",
            comment: `${SAMPLE_PREFIX} write off damaged stock`,
            items: [
              { productId: products.bananasCentro, warehouseId: warehouses.centro, storeId: stores.centro, quantity: 5, qualityStatus: "defect" },
            ],
          } as CreateOperationRequest,
        ]
      : [
          {
            type: "write_off",
            operationDate: "2026-05-11",
            comment: `${SAMPLE_PREFIX} write off damaged fresh stock`,
            items: [
              { productId: products.bananasCentro, warehouseId: warehouses.centro, storeId: stores.centro, quantity: 8 },
            ],
          } as CreateOperationRequest,
        ]),
    {
      type: "sale",
      operationDate: "2026-05-16",
      comment: `${SAMPLE_PREFIX} late period sales`,
      items: [
        { productId: products.alphaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonAlpha, quantity: 22 },
        { productId: products.betaContainer, warehouseId: warehouses.ozon, storeId: stores.ozonBeta, quantity: 18 },
        { productId: products.bananasCentro, warehouseId: warehouses.centro, storeId: stores.centro, quantity: 120 },
        { productId: products.applesNorte, warehouseId: warehouses.norte, storeId: stores.norte, quantity: 50 },
      ],
    },
    {
      type: "payment",
      operationDate: "2026-05-18",
      comment: `${SAMPLE_PREFIX} supplier payment marketplace`,
      supplierId: suppliers.marketplace,
      paymentAmount: 1800,
    },
    {
      type: "payment",
      operationDate: "2026-05-19",
      comment: `${SAMPLE_PREFIX} supplier overpayment fruit`,
      supplierId: suppliers.fruit,
      paymentAmount: 900,
    },
  ];

  for (const operation of operations) {
    const id = reportingSchema
      ? await createOperation(admin, workspaceId, operation)
      : await createLegacyOperation(admin, workspaceId, operation);
    console.log(`${operation.operationDate} ${operation.type} ${id}`);
  }

  console.log(`Created sample operations: ${operations.length}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

import { expect, test, type APIRequestContext } from "@playwright/test";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import {
  authSkipReason,
  hasAuthCredentials,
  loadLocalEnv,
} from "./auth-helpers";

const RUN_ID = Date.now().toString(36);

function uniqueName(prefix: string) {
  return `E2E-Import-${prefix}-${RUN_ID}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
}

test.describe("operation import targeted reprocess", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
  });

  test("applies a created product by exact SKU without returning candidates", async ({
    request,
  }) => {
    const admin = getAdminClient();
    test.skip(!admin, adminSkipReason());
    test.skip(
      !(await supportsTargetedReprocess(admin!)),
      "Local Supabase schema is missing migration 018_operation_import_targeted_reprocess.sql"
    );

    const workspaceId = await getManagerWorkspaceId(request);
    const productName = uniqueName("Product");
    const skuCode = uniqueName("SKU");
    const createdIds = { importId: "", productId: "" };

    try {
      const { data: product, error: productError } = await admin!
        .from("products")
        .insert({ workspace_id: workspaceId, name: productName, sku_code: skuCode })
        .select("id")
        .single();
      expect(productError).toBeNull();
      createdIds.productId = product!.id as string;

      const importId = await createImport(admin!, workspaceId);
      createdIds.importId = importId;

      const [match, differentSku, cleared] = await insertCandidates(admin!, [
        productCandidate(workspaceId, importId, 0, productName, skuCode),
        productCandidate(
          workspaceId,
          importId,
          1,
          productName,
          uniqueName("OtherSKU")
        ),
        productCandidate(workspaceId, importId, 2, productName, skuCode, false),
      ]);

      const response = await request.post(
        `/api/operation-imports/${importId}/reprocess?workspaceId=${workspaceId}`,
        {
          data: {
            createdEntity: {
              kind: "product",
              id: createdIds.productId,
              name: productName,
              skuCode,
            },
          },
        }
      );
      expect(response.ok(), await response.text()).toBeTruthy();
      const body = await response.json();

      expect(body.candidates).toBeUndefined();
      expect(body.updatedCandidateIds).toEqual([match.id]);
      expect(body.summary).toMatchObject({
        total: 3,
        ready: 1,
        needsReview: 2,
      });

      const rows = await getCandidates(admin!, [match.id, differentSku.id, cleared.id]);
      expect(rows.get(match.id)?.operation.items[0]).toMatchObject({
        productId: createdIds.productId,
        productName,
        skuCode,
        createProduct: false,
      });
      expect(rows.get(match.id)?.status).toBe("ready");
      expect(rows.get(differentSku.id)?.operation.items[0].productId).toBeUndefined();
      expect(rows.get(cleared.id)?.operation.items[0].productId).toBeUndefined();
    } finally {
      await cleanup(admin!, createdIds);
    }
  });

  test("applies created supplier and warehouse by exact name", async ({
    request,
  }) => {
    const admin = getAdminClient();
    test.skip(!admin, adminSkipReason());
    test.skip(
      !(await supportsTargetedReprocess(admin!)),
      "Local Supabase schema is missing migration 018_operation_import_targeted_reprocess.sql"
    );

    const workspaceId = await getManagerWorkspaceId(request);
    const supplierName = uniqueName("Supplier");
    const warehouseName = uniqueName("Warehouse");
    const createdIds = { importId: "", supplierId: "", warehouseId: "" };

    try {
      const { data: supplier, error: supplierError } = await admin!
        .from("suppliers")
        .insert({ workspace_id: workspaceId, name: supplierName })
        .select("id")
        .single();
      expect(supplierError).toBeNull();
      createdIds.supplierId = supplier!.id as string;

      const { data: warehouse, error: warehouseError } = await admin!
        .from("warehouses")
        .insert({ workspace_id: workspaceId, name: warehouseName })
        .select("id")
        .single();
      expect(warehouseError).toBeNull();
      createdIds.warehouseId = warehouse!.id as string;

      const importId = await createImport(admin!, workspaceId);
      createdIds.importId = importId;

      const [supplierCandidate, warehouseCandidate] = await insertCandidates(
        admin!,
        [
          supplierCandidateRecord(workspaceId, importId, 0, supplierName),
          warehouseCandidateRecord(workspaceId, importId, 1, warehouseName),
        ]
      );

      const supplierResponse = await request.post(
        `/api/operation-imports/${importId}/reprocess?workspaceId=${workspaceId}`,
        {
          data: {
            createdEntity: {
              kind: "supplier",
              id: createdIds.supplierId,
              name: supplierName,
            },
          },
        }
      );
      expect(supplierResponse.ok(), await supplierResponse.text()).toBeTruthy();
      const supplierBody = await supplierResponse.json();
      expect(supplierBody.candidates).toBeUndefined();
      expect(supplierBody.updatedCandidateIds).toEqual([supplierCandidate.id]);

      const warehouseResponse = await request.post(
        `/api/operation-imports/${importId}/reprocess?workspaceId=${workspaceId}`,
        {
          data: {
            createdEntity: {
              kind: "warehouse",
              id: createdIds.warehouseId,
              name: warehouseName,
            },
          },
        }
      );
      expect(warehouseResponse.ok(), await warehouseResponse.text()).toBeTruthy();
      const warehouseBody = await warehouseResponse.json();
      expect(warehouseBody.candidates).toBeUndefined();
      expect(warehouseBody.updatedCandidateIds).toEqual([warehouseCandidate.id]);

      const rows = await getCandidates(admin!, [
        supplierCandidate.id,
        warehouseCandidate.id,
      ]);
      expect(rows.get(supplierCandidate.id)?.operation).toMatchObject({
        supplierId: createdIds.supplierId,
        supplierName,
        createSupplier: false,
      });
      expect(rows.get(warehouseCandidate.id)?.operation.items[0]).toMatchObject({
        warehouseId: createdIds.warehouseId,
        warehouseName,
        createWarehouse: false,
      });
    } finally {
      await cleanup(admin!, createdIds);
    }
  });
});

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
  return "Set NEXT_PUBLIC_SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY to run operation import DB tests.";
}

async function supportsTargetedReprocess(admin: SupabaseClient) {
  const { error } = await admin.rpc("apply_operation_import_created_entity", {
    p_workspace_id: "00000000-0000-0000-0000-000000000000",
    p_import_id: "00000000-0000-0000-0000-000000000000",
    p_entity_kind: "product",
    p_entity_id: "00000000-0000-0000-0000-000000000000",
    p_entity_name: "probe",
    p_sku_code: "probe",
  });

  return error?.code !== "PGRST202";
}

async function getManagerWorkspaceId(request: APIRequestContext) {
  const response = await request.get("/api/auth/me");
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = (await response.json()) as {
    memberships: { organizationId: string; role: string }[];
  };
  const membership = body.memberships.find((item) =>
    ["owner", "admin"].includes(item.role)
  );
  test.skip(!membership, "Authenticated user is not a workspace manager.");
  return membership!.organizationId;
}

async function createImport(admin: SupabaseClient, workspaceId: string) {
  const { data, error } = await admin
    .from("operation_imports")
    .insert({
      workspace_id: workspaceId,
      file_name: uniqueName("file.csv"),
      file_type: "csv",
      file_size: 1,
      file_hash: uniqueName("hash"),
      status: "needs_review",
      summary: { total: 0, ready: 0, needsReview: 0, approved: 0, committed: 0 },
    })
    .select("id")
    .single();
  expect(error).toBeNull();
  return data!.id as string;
}

async function insertCandidates(
  admin: SupabaseClient,
  rows: Record<string, unknown>[]
) {
  const { data, error } = await admin
    .from("operation_import_candidates")
    .insert(rows)
    .select("id");
  expect(error).toBeNull();
  return data as { id: string }[];
}

function productCandidate(
  workspaceId: string,
  importId: string,
  rowIndex: number,
  productName: string,
  skuCode: string,
  createProduct?: boolean
) {
  const operation = {
    type: "purchase",
    operationDate: "2026-02-01",
    supplierId: "00000000-0000-0000-0000-000000000001",
    items: [
      {
        productName,
        skuCode,
        warehouseId: "00000000-0000-0000-0000-000000000002",
        quantity: 1,
        unitPrice: 1,
        ...(createProduct === undefined ? {} : { createProduct }),
      },
    ],
  };

  return baseCandidate(workspaceId, importId, rowIndex, operation, [
    {
      field: "items[0].productId",
      message: "Product is required",
      severity: "error",
    },
  ]);
}

function supplierCandidateRecord(
  workspaceId: string,
  importId: string,
  rowIndex: number,
  supplierName: string
) {
  return baseCandidate(
    workspaceId,
    importId,
    rowIndex,
    {
      type: "purchase",
      operationDate: "2026-02-01",
      supplierName,
      items: [
        {
          productId: "00000000-0000-0000-0000-000000000001",
          warehouseId: "00000000-0000-0000-0000-000000000002",
          quantity: 1,
          unitPrice: 1,
        },
      ],
    },
    [
      {
        field: "supplierId",
        message: "Supplier is required",
        severity: "error",
      },
    ]
  );
}

function warehouseCandidateRecord(
  workspaceId: string,
  importId: string,
  rowIndex: number,
  warehouseName: string
) {
  return baseCandidate(
    workspaceId,
    importId,
    rowIndex,
    {
      type: "purchase",
      operationDate: "2026-02-01",
      supplierId: "00000000-0000-0000-0000-000000000001",
      items: [
        {
          productId: "00000000-0000-0000-0000-000000000001",
          warehouseName,
          quantity: 1,
          unitPrice: 1,
        },
      ],
    },
    [
      {
        field: "items[0].warehouseId",
        message: "Warehouse is required",
        severity: "error",
      },
    ]
  );
}

function baseCandidate(
  workspaceId: string,
  importId: string,
  rowIndex: number,
  operation: Record<string, unknown>,
  validationErrors: Record<string, unknown>[]
) {
  return {
    workspace_id: workspaceId,
    import_id: importId,
    row_index: rowIndex,
    fingerprint: uniqueName("fingerprint"),
    status: "needs_review",
    confidence: 0.9,
    source: { kind: "csv", rowNumber: rowIndex + 1 },
    raw: {},
    operation,
    normalized_operation: operation,
    validation_errors: validationErrors,
  };
}

async function getCandidates(admin: SupabaseClient, ids: string[]) {
  const { data, error } = await admin
    .from("operation_import_candidates")
    .select("id, operation, status")
    .in("id", ids);
  expect(error).toBeNull();
  return new Map(
    (data || []).map((row) => [
      row.id as string,
      {
        operation: row.operation as {
          supplierId?: string;
          supplierName?: string;
          createSupplier?: boolean;
          items: Record<string, unknown>[];
        },
        status: row.status as string,
      },
    ])
  );
}

async function cleanup(
  admin: SupabaseClient,
  ids: {
    importId?: string;
    productId?: string;
    supplierId?: string;
    warehouseId?: string;
  }
) {
  if (ids.importId) {
    await admin.from("operation_imports").delete().eq("id", ids.importId);
  }
  if (ids.productId) {
    await admin.from("products").delete().eq("id", ids.productId);
  }
  if (ids.supplierId) {
    await admin.from("suppliers").delete().eq("id", ids.supplierId);
  }
  if (ids.warehouseId) {
    await admin.from("warehouses").delete().eq("id", ids.warehouseId);
  }
}

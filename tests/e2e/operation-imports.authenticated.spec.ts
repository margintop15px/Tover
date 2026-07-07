import { expect, test, type APIRequestContext } from "@playwright/test";
import crypto from "node:crypto";
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

  test("commits approved rows while leaving unresolved rows for later", async ({
    request,
  }) => {
    const admin = getAdminClient();
    test.skip(!admin, adminSkipReason());

    const workspaceId = await getManagerWorkspaceId(request);
    const createdIds = {
      importId: "",
      productId: "",
      supplierId: "",
      warehouseId: "",
    };

    try {
      const productName = uniqueName("PartialProduct");
      const supplierName = uniqueName("PartialSupplier");
      const warehouseName = uniqueName("PartialWarehouse");

      const { data: product, error: productError } = await admin!
        .from("products")
        .insert({
          workspace_id: workspaceId,
          name: productName,
          sku_code: uniqueName("PartialSKU"),
        })
        .select("id")
        .single();
      expect(productError).toBeNull();
      createdIds.productId = product!.id as string;

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

      const approvedOperation = validPurchaseOperation(createdIds);
      const unresolvedOperation = {
        ...approvedOperation,
        items: [
          {
            warehouseId: createdIds.warehouseId,
            quantity: 1,
            unitPrice: 2,
          },
        ],
      };
      const [approved, unresolved] = await insertCandidates(admin!, [
        baseCandidate(workspaceId, importId, 0, approvedOperation, [], "approved"),
        baseCandidate(
          workspaceId,
          importId,
          1,
          unresolvedOperation,
          [
            {
              field: "items[0].productId",
              message: "Product is required",
              severity: "error",
            },
          ],
          "needs_review"
        ),
      ]);

      const reopenResponse = await request.get(
        `/api/operation-imports/${importId}?workspaceId=${workspaceId}`
      );
      const reopenText = await reopenResponse.text();
      expect(reopenResponse.ok(), reopenText).toBeTruthy();
      expect(JSON.parse(reopenText)).toMatchObject({
        import: { id: importId },
        candidates: [{ id: approved.id }, { id: unresolved.id }],
      });

      const firstCommit = await request.post(
        `/api/operation-imports/${importId}/commit?workspaceId=${workspaceId}`
      );
      const firstCommitText = await firstCommit.text();
      test.skip(
        firstCommit.status() === 400 &&
          firstCommitText.includes("All candidates must be approved before commit"),
        "Local Supabase schema is missing migration 019_operation_import_partial_commit.sql"
      );
      expect(firstCommit.ok(), firstCommitText).toBeTruthy();
      expect(JSON.parse(firstCommitText)).toMatchObject({ committed: 1 });

      let importRecord = await getImport(admin!, importId);
      expect(importRecord.status).toBe("needs_review");
      expect(importRecord.summary).toMatchObject({
        total: 2,
        committed: 1,
        approved: 0,
      });

      let rows = await getCandidates(admin!, [approved.id, unresolved.id]);
      expect(rows.get(approved.id)?.status).toBe("committed");
      expect(rows.get(approved.id)?.createdOperationId).toBeTruthy();
      expect(rows.get(unresolved.id)?.status).toBe("needs_review");
      expect(rows.get(unresolved.id)?.createdOperationId).toBeNull();

      const secondOperation = {
        ...approvedOperation,
        items: [{ ...approvedOperation.items[0], quantity: 2 }],
      };
      const { error: updateError } = await admin!
        .from("operation_import_candidates")
        .update({
          operation: secondOperation,
          normalized_operation: secondOperation,
          validation_errors: [],
          status: "approved",
          fingerprint: uniqueName("fingerprint"),
        })
        .eq("id", unresolved.id);
      expect(updateError).toBeNull();

      const secondCommit = await request.post(
        `/api/operation-imports/${importId}/commit?workspaceId=${workspaceId}`
      );
      const secondCommitText = await secondCommit.text();
      expect(secondCommit.ok(), secondCommitText).toBeTruthy();
      expect(JSON.parse(secondCommitText)).toMatchObject({ committed: 1 });

      importRecord = await getImport(admin!, importId);
      expect(importRecord.status).toBe("completed");
      expect(importRecord.summary).toMatchObject({
        total: 2,
        committed: 2,
        approved: 0,
      });

      rows = await getCandidates(admin!, [approved.id, unresolved.id]);
      expect(rows.get(unresolved.id)?.status).toBe("committed");
      expect(rows.get(unresolved.id)?.createdOperationId).toBeTruthy();
    } finally {
      await cleanup(admin!, createdIds);
    }
  });

  test("resumes the newest unfinished import when uploading the same file hash", async ({
    request,
  }) => {
    const admin = getAdminClient();
    test.skip(!admin, adminSkipReason());

    const workspaceId = await getManagerWorkspaceId(request);
    const csv = Buffer.from("Date,Type\n2099-02-01,Purchase\n");
    const fileHash = crypto.createHash("sha256").update(csv).digest("hex");
    const createdIds = { importId: "" };

    try {
      const importId = await createImport(admin!, workspaceId, fileHash);
      createdIds.importId = importId;

      const response = await request.post(
        `/api/operation-imports?workspaceId=${workspaceId}`,
        {
          multipart: {
            file: {
              name: "resume.csv",
              mimeType: "text/csv",
              buffer: csv,
            },
          },
        }
      );
      const responseText = await response.text();
      expect(response.ok(), responseText).toBeTruthy();
      expect(JSON.parse(responseText)).toMatchObject({
        import: { id: importId },
        resumed: true,
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

async function createImport(
  admin: SupabaseClient,
  workspaceId: string,
  fileHash = uniqueName("hash")
) {
  const { data, error } = await admin
    .from("operation_imports")
    .insert({
      workspace_id: workspaceId,
      file_name: uniqueName("file.csv"),
      file_type: "csv",
      file_size: 1,
      file_hash: fileHash,
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
  validationErrors: Record<string, unknown>[],
  status = "needs_review"
) {
  return {
    workspace_id: workspaceId,
    import_id: importId,
    row_index: rowIndex,
    fingerprint: uniqueName("fingerprint"),
    status,
    confidence: 0.9,
    source: { kind: "csv", rowNumber: rowIndex + 1 },
    raw: {},
    operation,
    normalized_operation: operation,
    validation_errors: validationErrors,
  };
}

function validPurchaseOperation(ids: {
  productId: string;
  supplierId: string;
  warehouseId: string;
}) {
  return {
    type: "purchase",
    operationDate: "2099-02-01",
    supplierId: ids.supplierId,
    items: [
      {
        productId: ids.productId,
        warehouseId: ids.warehouseId,
        quantity: 1,
        unitPrice: 2,
      },
    ],
  };
}

async function getImport(admin: SupabaseClient, importId: string) {
  const { data, error } = await admin
    .from("operation_imports")
    .select("status, summary")
    .eq("id", importId)
    .single();
  expect(error).toBeNull();
  return data as { status: string; summary: Record<string, unknown> };
}

async function getCandidates(admin: SupabaseClient, ids: string[]) {
  const { data, error } = await admin
    .from("operation_import_candidates")
    .select("id, operation, status, created_operation_id")
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
        createdOperationId: row.created_operation_id as string | null,
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
    operationIds?: string[];
  }
) {
  let operationIds = ids.operationIds || [];
  if (ids.importId && operationIds.length === 0) {
    const { data } = await admin
      .from("operation_import_committed_operations")
      .select("operation_id")
      .eq("import_id", ids.importId);
    operationIds = (data || []).map((row) => row.operation_id as string);
  }
  if (operationIds.length > 0) {
    await admin.from("operations").delete().in("id", operationIds);
  }
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

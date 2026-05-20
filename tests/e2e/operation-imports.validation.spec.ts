import { expect, test } from "@playwright/test";
import {
  extractTabularOperations,
  fingerprintOperation,
  normalizeAndValidateDraft,
  shouldUseTabularAiFallback,
  tabularAiFallbackReasons,
} from "../../src/lib/operation-imports/pipeline";
import type {
  RefData,
  TabularImportPlan,
} from "../../src/lib/operation-imports/types";

const ref: RefData = {
  categories: [
    {
      id: "00000000-0000-0000-0000-000000000005",
      name: "Ingredients",
      isImportDefault: false,
      createdAt: "2026-01-01T00:00:00.000Z",
    },
  ],
  products: [
    {
      id: "00000000-0000-0000-0000-000000000001",
      name: "Flour",
      skuCode: "FLR",
      categoryId: null,
      categoryName: null,
      storeId: null,
      storeName: null,
      isDefectCopy: false,
      createdAt: "2026-01-01T00:00:00.000Z",
    },
  ],
  warehouses: [
    {
      id: "00000000-0000-0000-0000-000000000002",
      name: "Main",
      description: null,
      purpose: null,
      isDefaultDefect: false,
      isImportDefault: false,
      createdAt: "2026-01-01T00:00:00.000Z",
    },
  ],
  suppliers: [
    {
      id: "00000000-0000-0000-0000-000000000003",
      name: "Acme",
      address: null,
      contactInfo: null,
      isImportDefault: false,
      createdAt: "2026-01-01T00:00:00.000Z",
    },
  ],
  stores: [],
};

test.describe("operation import validation", () => {
  test("detects purchase columns and resolves master data", async () => {
    const csv = [
      "Date,Type,Supplier,SKU,Warehouse,Quantity,Unit Price,Comment",
      "2026-02-01,Purchase,Acme,FLR,Main,12,3.5,invoice 100",
    ].join("\n");

    const result = await extractTabularOperations({
      fileName: "operations.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv),
      ref,
      existingDuplicates: [],
    });

    expect(result.candidates).toHaveLength(1);
    expect(result.candidates[0].status).toBe("ready");
    expect(result.candidates[0].normalizedOperation).toMatchObject({
      type: "purchase",
      supplierId: ref.suppliers[0].id,
      operationDate: "2026-02-01",
      items: [
        {
          productId: ref.products[0].id,
          warehouseId: ref.warehouses[0].id,
          quantity: 12,
          unitPrice: 3.5,
        },
      ],
    });
  });

  test("defaults unknown references to create mode", () => {
    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierName: "New supplier",
        items: [
          {
            productName: "Unknown ingredient",
            warehouseName: "New warehouse",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      ref,
      []
    );

    expect(validation.status).toBe("ready");
    expect(validation.normalized.createSupplier).toBe(true);
    expect(validation.normalized.items?.[0]).toMatchObject({
      createProduct: true,
      createWarehouse: true,
    });
  });

  test("uses import defaults when non-product references are absent", () => {
    const defaultsRef: RefData = {
      categories: ref.categories,
      products: [
        {
          ...ref.products[0],
          id: "00000000-0000-0000-0000-000000000011",
        },
      ],
      warehouses: [
        {
          ...ref.warehouses[0],
          id: "00000000-0000-0000-0000-000000000012",
          isImportDefault: true,
        },
      ],
      suppliers: [
        {
          ...ref.suppliers[0],
          id: "00000000-0000-0000-0000-000000000013",
          isImportDefault: true,
        },
      ],
      stores: [
        {
          id: "00000000-0000-0000-0000-000000000014",
          name: "Default store",
          isImportDefault: true,
          createdAt: "2026-01-01T00:00:00.000Z",
        },
      ],
    };

    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        items: [{ productName: "Flour", quantity: 2, unitPrice: 5 }],
      },
      defaultsRef,
      []
    );

    expect(validation.status).toBe("ready");
    expect(validation.normalized.supplierId).toBe(defaultsRef.suppliers[0].id);
    expect(validation.normalized.items?.[0]).toMatchObject({
      productId: defaultsRef.products[0].id,
      warehouseId: defaultsRef.warehouses[0].id,
      storeId: defaultsRef.stores[0].id,
    });
  });

  test("does not use a default product when product text is absent", () => {
    const defaultsRef: RefData = {
      categories: ref.categories,
      products: ref.products,
      warehouses: [{ ...ref.warehouses[0], isImportDefault: true }],
      suppliers: [{ ...ref.suppliers[0], isImportDefault: true }],
      stores: [],
    };

    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        items: [{ quantity: 2, unitPrice: 5 }],
      },
      defaultsRef,
      []
    );

    expect(validation.status).toBe("needs_review");
    expect(
      validation.validationErrors.some(
        (error) => error.field === "items[0].productId"
      )
    ).toBe(true);
    expect(validation.normalized.items?.[0].productId).toBeUndefined();
  });

  test("does not replace raw unrecognized references with defaults", () => {
    const defaultsRef: RefData = {
      categories: ref.categories,
      products: [ref.products[0]],
      warehouses: [{ ...ref.warehouses[0], isImportDefault: true }],
      suppliers: [{ ...ref.suppliers[0], isImportDefault: true }],
      stores: [],
    };

    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierName: "Visible supplier text",
        items: [
          {
            productName: "Visible product text",
            warehouseName: "Visible warehouse text",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      defaultsRef,
      []
    );

    expect(validation.status).toBe("ready");
    expect(validation.normalized.supplierId).toBeUndefined();
    expect(validation.normalized.createSupplier).toBe(true);
    expect(validation.normalized.items?.[0]).toMatchObject({
      createProduct: true,
      createWarehouse: true,
    });
    expect(validation.normalized.items?.[0].productId).toBeUndefined();
    expect(validation.normalized.items?.[0].warehouseId).toBeUndefined();
  });

  test("blocks duplicates by operation fingerprint", () => {
    const operation = {
      type: "purchase" as const,
      operationDate: "2026-02-01",
      supplierId: ref.suppliers[0].id,
      items: [
        {
          productId: ref.products[0].id,
          warehouseId: ref.warehouses[0].id,
          quantity: 12,
          unitPrice: 3.5,
        },
      ],
    };

    const fingerprint = fingerprintOperation(operation);
    const validation = normalizeAndValidateDraft(operation, ref, [
      {
        fingerprint,
        importId: "import-1",
        operationId: "operation-1",
      },
    ]);

    expect(validation.status).toBe("needs_review");
    expect(validation.validationErrors.some((error) => error.field === "duplicate")).toBe(
      true
    );
  });

  test("requests AI fallback when table headers are unfamiliar", async () => {
    const csv = [
      "When,Movement partner,Thing,Storage place,How many,Money each",
      "2026-02-01,Acme,Flour,Main,12,3.5",
    ].join("\n");

    const result = await extractTabularOperations({
      fileName: "custom-template.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv),
      ref,
      existingDuplicates: [],
    });

    expect(shouldUseTabularAiFallback(result)).toBe(true);
    expect(tabularAiFallbackReasons(result).length).toBeGreaterThan(0);
  });

  test("uses an inferred tabular plan to extract unfamiliar columns deterministically", async () => {
    const csv = [
      "When,Movement partner,Thing,Storage place,How many,Money each",
      "2026-02-01,Acme,Flour,Main,12,3.5",
    ].join("\n");
    const plan: TabularImportPlan = {
      dateFormat: "YYYY-MM-DD",
      decimalSeparator: ".",
      thousandsSeparator: null,
      sheets: [
        {
          sheetName: "CSV",
          sheetIndex: 0,
          headerRowIndex: 0,
          dataStartRowIndex: 1,
          dataEndRowIndex: null,
          columns: {
            operationDate: 0,
            type: null,
            productName: 2,
            skuCode: null,
            warehouseName: 3,
            storeName: null,
            sourceWarehouseName: null,
            destinationWarehouseName: null,
            quantity: 4,
            unitPrice: 5,
            supplierName: 1,
            paymentAmount: null,
            comment: null,
            direction: null,
          },
          defaults: {
            type: "purchase",
            operationDate: null,
            supplierName: null,
            warehouseName: null,
            comment: null,
          },
          confidence: 0.9,
          warnings: [],
        },
      ],
    };

    const result = await extractTabularOperations({
      fileName: "custom-template.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv),
      ref,
      existingDuplicates: [],
      plan,
    });

    expect(result.candidates).toHaveLength(1);
    expect(result.candidates[0].status).toBe("ready");
    expect(result.candidates[0].normalizedOperation).toMatchObject({
      type: "purchase",
      supplierId: ref.suppliers[0].id,
      operationDate: "2026-02-01",
      items: [
        {
          productId: ref.products[0].id,
          warehouseId: ref.warehouses[0].id,
          quantity: 12,
          unitPrice: 3.5,
        },
      ],
    });
  });
});

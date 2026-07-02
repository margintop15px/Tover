import { expect, test } from "@playwright/test";
import {
  extractTabularOperations,
  fingerprintOperation,
  normalizeAndValidateDraft,
  shouldUseTabularAiFallback,
  tabularAiFallbackReasons,
} from "../../src/lib/operation-imports/pipeline";
import { sanitizeExtractedComment } from "../../src/lib/operation-imports/openai";
import type {
  RefData,
  TabularImportPlan,
} from "../../src/lib/operation-imports/types";
import { ru } from "../../src/i18n/ru";

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

const skuIdentityRef: RefData = {
  ...ref,
  products: [
    {
      ...ref.products[0],
      id: "00000000-0000-0000-0000-000000000021",
      name: "Краскопульт аккумуляторный",
      skuCode: "3855415095",
    },
    {
      ...ref.products[0],
      id: "00000000-0000-0000-0000-000000000022",
      name: "Краскопульт аккумуляторный",
      skuCode: "3689224323",
    },
    {
      ...ref.products[0],
      id: "00000000-0000-0000-0000-000000000023",
      name: "Пароочиститель для уборки дома",
      skuCode: "3578291196",
    },
  ],
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

  test("auto-selects exact source text before user clears it", () => {
    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierName: "Acme",
        items: [
          {
            productName: "Flour",
            skuCode: "FLR",
            warehouseName: "Main",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      ref,
      []
    );

    expect(validation.status).toBe("ready");
    expect(validation.normalized.supplierId).toBe(ref.suppliers[0].id);
    expect(validation.normalized.items?.[0]).toMatchObject({
      productId: ref.products[0].id,
      warehouseId: ref.warehouses[0].id,
    });
  });

  test("matches products by exact SKU when duplicate names exist", () => {
    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierName: "Acme",
        items: [
          {
            productName: "Краскопульт аккумуляторный",
            skuCode: "3689224323",
            warehouseName: "Main",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      skuIdentityRef,
      []
    );

    expect(validation.status).toBe("ready");
    expect(validation.normalized.items?.[0].productId).toBe(
      "00000000-0000-0000-0000-000000000022"
    );
  });

  test("does not fall back to product name when SKU is unknown", () => {
    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierName: "Acme",
        items: [
          {
            productName: "Краскопульт аккумуляторный",
            skuCode: "NEW-SKU",
            warehouseName: "Main",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      skuIdentityRef,
      []
    );

    expect(validation.status).toBe("needs_review");
    expect(validation.normalized.items?.[0]).toMatchObject({
      productName: "Краскопульт аккумуляторный",
      skuCode: "NEW-SKU",
      createProduct: false,
    });
    expect(validation.normalized.items?.[0].productId).toBeUndefined();
  });

  test("reprocesses sibling rows after a matching product is created", () => {
    const draft = {
      type: "purchase" as const,
      operationDate: "2026-02-01",
      supplierName: "Acme",
      items: [
        {
          productName: "Краскопульт аккумуляторный",
          skuCode: "NEW-SKU",
          warehouseName: "Main",
          quantity: 2,
          unitPrice: 5,
        },
      ],
    };
    const refAfterCreate: RefData = {
      ...skuIdentityRef,
      products: [
        ...skuIdentityRef.products,
        {
          ...skuIdentityRef.products[0],
          id: "00000000-0000-0000-0000-000000000024",
          name: "Краскопульт аккумуляторный",
          skuCode: "NEW-SKU",
        },
      ],
    };

    const beforeCreate = normalizeAndValidateDraft(draft, skuIdentityRef, []);
    const afterCreate = normalizeAndValidateDraft(draft, refAfterCreate, []);
    const differentSku = normalizeAndValidateDraft(
      {
        ...draft,
        items: [{ ...draft.items[0], skuCode: "OTHER-SKU" }],
      },
      refAfterCreate,
      []
    );
    const explicitlyCleared = normalizeAndValidateDraft(
      {
        ...draft,
        items: [{ ...draft.items[0], createProduct: false }],
      },
      refAfterCreate,
      []
    );

    expect(beforeCreate.normalized.items?.[0].productId).toBeUndefined();
    expect(afterCreate.normalized.items?.[0].productId).toBe(
      "00000000-0000-0000-0000-000000000024"
    );
    expect(differentSku.normalized.items?.[0].productId).toBeUndefined();
    expect(explicitlyCleared.normalized.items?.[0].productId).toBeUndefined();
  });

  test("matches product name only when the name is unique and SKU is absent", () => {
    const uniqueNameValidation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierName: "Acme",
        items: [
          {
            productName: "Пароочиститель для уборки дома",
            warehouseName: "Main",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      skuIdentityRef,
      []
    );
    const duplicateNameValidation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierName: "Acme",
        items: [
          {
            productName: "Краскопульт аккумуляторный",
            warehouseName: "Main",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      skuIdentityRef,
      []
    );

    expect(uniqueNameValidation.status).toBe("ready");
    expect(uniqueNameValidation.normalized.items?.[0].productId).toBe(
      "00000000-0000-0000-0000-000000000023"
    );
    expect(duplicateNameValidation.status).toBe("needs_review");
    expect(duplicateNameValidation.normalized.items?.[0].productId).toBeUndefined();
  });

  test("maps SKU before article columns in tabular imports", async () => {
    const csv = [
      "Date,Type,Supplier,Название товара,Артикул,SKU,Warehouse,Quantity,Unit Price",
      "2026-02-01,Purchase,Acme,Краскопульт аккумуляторный,Kraskopult_akb_NEW,3855415095,Main,2,5",
    ].join("\n");

    const result = await extractTabularOperations({
      fileName: "marketplace-products.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv),
      ref: skuIdentityRef,
      existingDuplicates: [],
    });

    expect(result.candidates).toHaveLength(1);
    expect(result.candidates[0].status).toBe("ready");
    expect(result.candidates[0].normalizedOperation.items?.[0]).toMatchObject({
      productId: "00000000-0000-0000-0000-000000000021",
      skuCode: "3855415095",
    });
  });

  test("leaves extracted unknown references unmapped", () => {
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

    expect(validation.status).toBe("needs_review");
    expect(validation.normalized.createSupplier).toBe(false);
    expect(validation.normalized.items?.[0]).toMatchObject({
      createProduct: false,
      createWarehouse: false,
    });
    expect(validation.normalized.items?.[0].productName).toBe(
      "Unknown ingredient"
    );
    expect(
      validation.validationErrors.map((error) => error.field)
    ).toEqual(
      expect.arrayContaining([
        "supplierId",
        "items[0].productId",
        "items[0].warehouseId",
      ])
    );
  });

  test("does not reselect an exact product after the user clears it", () => {
    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "2026-02-01",
        supplierId: ref.suppliers[0].id,
        items: [
          {
            productName: "Flour",
            skuCode: "FLR",
            createProduct: false,
            warehouseId: ref.warehouses[0].id,
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      ref,
      []
    );

    expect(validation.status).toBe("needs_review");
    expect(validation.normalized.items?.[0]).toMatchObject({
      productName: "Flour",
      createProduct: false,
    });
    expect(validation.normalized.items?.[0].productId).toBeUndefined();
    expect(
      validation.validationErrors.some(
        (error) => error.field === "items[0].productId"
      )
    ).toBe(true);
  });

  test("normalizes space-delimited dates from image extraction", () => {
    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "23 04 2026",
        supplierName: "Acme",
        items: [
          {
            productName: "Flour",
            warehouseName: "Main",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      ref,
      []
    );

    expect(validation.status).toBe("ready");
    expect(validation.normalized.operationDate).toBe("2026-04-23");
  });

  test("flags unparseable dates before approval", () => {
    const validation = normalizeAndValidateDraft(
      {
        type: "purchase",
        operationDate: "not a date",
        supplierName: "Acme",
        items: [
          {
            productName: "Flour",
            warehouseName: "Main",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      ref,
      []
    );

    expect(validation.status).toBe("needs_review");
    expect(validation.normalized.operationDate).toBeUndefined();
    expect(
      validation.validationErrors.some(
        (error) => error.field === "operationDate"
      )
    ).toBe(true);
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

  test("does not use defaults after categorical selections are cleared", () => {
    const defaultsRef: RefData = {
      categories: ref.categories,
      products: ref.products,
      warehouses: [{ ...ref.warehouses[0], isImportDefault: true }],
      suppliers: [{ ...ref.suppliers[0], isImportDefault: true }],
      stores: [
        {
          id: "00000000-0000-0000-0000-000000000016",
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
        createSupplier: false,
        items: [
          {
            productId: ref.products[0].id,
            createWarehouse: false,
            createStore: false,
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      defaultsRef,
      []
    );

    expect(validation.status).toBe("needs_review");
    expect(validation.normalized.supplierId).toBeUndefined();
    expect(validation.normalized.items?.[0].warehouseId).toBeUndefined();
    expect(validation.normalized.items?.[0].storeId).toBeUndefined();
    expect(
      validation.validationErrors.map((error) => error.field)
    ).toEqual(expect.arrayContaining(["supplierId", "items[0].warehouseId"]));
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

  test("does not use defaults for unrecognized source references", () => {
    const defaultsRef: RefData = {
      categories: ref.categories,
      products: [ref.products[0]],
      warehouses: [{ ...ref.warehouses[0], isImportDefault: true }],
      suppliers: [{ ...ref.suppliers[0], isImportDefault: true }],
      stores: [
        {
          id: "00000000-0000-0000-0000-000000000015",
          name: "Fallback store",
          isImportDefault: true,
          createdAt: "2026-01-01T00:00:00.000Z",
        },
      ],
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
            storeName: "Visible store text",
            quantity: 2,
            unitPrice: 5,
          },
        ],
      },
      defaultsRef,
      []
    );

    expect(validation.status).toBe("needs_review");
    expect(validation.normalized.supplierId).toBeUndefined();
    expect(validation.normalized.createSupplier).toBe(false);
    expect(validation.normalized.items?.[0]).toMatchObject({
      createProduct: false,
      createWarehouse: false,
      createStore: false,
    });
    expect(validation.normalized.items?.[0].productId).toBeUndefined();
    expect(validation.normalized.items?.[0].warehouseId).toBeUndefined();
    expect(validation.normalized.items?.[0].storeId).toBeUndefined();
  });

  test("translates operation import validation messages to Russian", () => {
    expect(ru.operationImportValidationField("items[0].productId")).toBe(
      "Товар в позиции 1"
    );
    expect(ru.operationImportValidationMessage("Product is required")).toBe(
      "Укажите товар"
    );
    expect(
      ru.operationImportValidationMessage(
        "Likely duplicate of operation operation-1"
      )
    ).toBe("Вероятный дубликат операции operation-1");
  });

  test("keeps image comments as recognized note text, not model discourse", () => {
    expect(
      sanitizeExtractedComment(
        'Товарный чек № [blank]; сумма 384000; handwritten top note appears: "Армель, Юревиз,"; продавец handwritten is difficult to read.'
      )
    ).toBe("Армель, Юревиз,");
    expect(sanitizeExtractedComment("delivered after close")).toBe(
      "delivered after close"
    );
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

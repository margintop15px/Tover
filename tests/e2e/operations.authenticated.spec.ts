import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
} from "@playwright/test";
import {
  authSkipReason,
  hasAuthCredentials,
  inventoryAdjustmentSchemaSkipReason,
  supportsInventoryAdjustmentOperations,
} from "./auth-helpers";

const RUN_ID = Date.now().toString(36);

function uniqueName(prefix: string, testInfo: { workerIndex: number }): string {
  return `E2E-${prefix}-${RUN_ID}-${testInfo.workerIndex}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
}

async function expectJson<T>(
  responsePromise: Promise<APIResponse>,
  status: number
): Promise<T> {
  const response = await responsePromise;
  const bodyText = await response.text();
  expect(response.status(), bodyText).toBe(status);
  return JSON.parse(bodyText) as T;
}

async function postJson<T>(
  request: APIRequestContext,
  url: string,
  data: Record<string, unknown>,
  status = 201
): Promise<T> {
  const response = await request.post(url, { data });
  const bodyText = await response.text();
  expect(response.status(), bodyText).toBe(status);
  return JSON.parse(bodyText) as T;
}

interface EntityResponse {
  id: string;
  name: string;
}

interface OperationListResponse {
  page: {
    limit: number;
    offset: number;
    totalEstimate: number;
  };
  items: OperationRow[];
}

interface OperationRow {
  id: string;
  operationId: string;
  itemId: string | null;
  type: string;
  operationDate: string;
  comment: string | null;
  productId: string | null;
  productName: string | null;
  warehouseId: string | null;
  warehouseName: string | null;
  quantity: number | null;
  unitPrice: number | null;
  direction: string | null;
  paymentAmount: number | null;
  supplierId: string | null;
  supplierName: string | null;
  itemsSummary: unknown[];
}

interface OperationDetailsResponse {
  id: string;
  type: string;
  operationDate: string;
  comment: string | null;
  supplierId: string | null;
  supplierName: string | null;
  paymentAmount: number | null;
  items: {
    productId: string;
    productName: string;
    warehouseId: string;
    warehouseName: string;
    quantity: number;
    unitPrice: number | null;
    direction: string;
  }[];
}

interface ProductBalancesResponse {
  items: {
    productId: string;
    warehouseId: string;
    quantity: number;
    unitCost: number;
  }[];
}

async function createProduct(
  request: APIRequestContext,
  name: string
): Promise<EntityResponse> {
  return postJson<EntityResponse>(request, "/api/products", {
    name,
    skuCode: `${name}-SKU`,
  });
}

async function createWarehouse(
  request: APIRequestContext,
  name: string
): Promise<EntityResponse> {
  return postJson<EntityResponse>(request, "/api/warehouses", {
    name,
    description: "E2E operations test warehouse",
    purpose: "storage",
  });
}

async function createSupplier(
  request: APIRequestContext,
  name: string
): Promise<EntityResponse> {
  return postJson<EntityResponse>(request, "/api/suppliers", {
    name,
    contactInfo: "e2e@example.com",
  });
}

test.describe("operations API", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
  });

  test("creates inventory adjustments, updates balances, and keeps audit rows out of the default list", async ({
    request,
  }, testInfo) => {
    const supportsInventoryAdjustment =
      await supportsInventoryAdjustmentOperations();
    test.skip(
      supportsInventoryAdjustment === false && !process.env.CI,
      inventoryAdjustmentSchemaSkipReason()
    );

    const product = await createProduct(
      request,
      uniqueName("AdjustmentProduct", testInfo)
    );
    const warehouse = await createWarehouse(
      request,
      uniqueName("AdjustmentWarehouse", testInfo)
    );
    const operationDate = "2099-02-01";

    const invalid = await request.post("/api/operations", {
      data: {
        type: "inventory_adjustment",
        operationDate,
        items: [
          {
            productId: product.id,
            warehouseId: warehouse.id,
            quantity: 17,
            unitPrice: 0,
          },
        ],
      },
    });
    expect(invalid.status()).toBe(400);
    expect(await invalid.json()).toMatchObject({
      errors: [
        {
          field: "items[0].unitPrice",
          message: "Unit cost must be positive",
        },
      ],
    });

    const createdResponse = await request.post("/api/operations", {
      data: {
        type: "inventory_adjustment",
        operationDate,
        comment: "  Initial counted stock  ",
        items: [
          {
            productId: product.id,
            warehouseId: warehouse.id,
            quantity: 17,
            unitPrice: 42,
          },
        ],
      },
    });
    const createdBodyText = await createdResponse.text();
    if (
      createdResponse.status() === 500 &&
      createdBodyText.includes("operations_type_check")
    ) {
      test.skip(
        !process.env.CI,
        inventoryAdjustmentSchemaSkipReason()
      );
    }
    expect(createdResponse.status(), createdBodyText).toBe(201);
    const created = JSON.parse(createdBodyText) as { id: string };

    const details = await expectJson<OperationDetailsResponse>(
      request.get(`/api/operations/${created.id}`),
      200
    );
    expect(details).toMatchObject({
      id: created.id,
      type: "inventory_adjustment",
      operationDate,
      supplierId: null,
      supplierName: null,
      paymentAmount: null,
      items: [
        {
          productId: product.id,
          productName: product.name,
          warehouseId: warehouse.id,
          warehouseName: warehouse.name,
          quantity: 17,
          unitPrice: 42,
          direction: "in",
        },
      ],
    });

    const balances = await expectJson<ProductBalancesResponse>(
      request.get(
        `/api/product-balances?productId=${product.id}&warehouseId=${warehouse.id}`
      ),
      200
    );
    expect(balances.items).toHaveLength(1);
    expect(balances.items[0]).toMatchObject({
      productId: product.id,
      warehouseId: warehouse.id,
      quantity: 17,
      unitCost: 42,
    });

    const auditScope = `productId=${product.id}&warehouseId=${warehouse.id}&from=${operationDate}&to=${operationDate}`;
    const defaultList = await expectJson<OperationListResponse>(
      request.get(`/api/operations?${auditScope}`),
      200
    );
    expect(defaultList.items.map((item) => item.operationId)).not.toContain(
      created.id
    );

    const auditList = await expectJson<OperationListResponse>(
      request.get(
        `/api/operations?type=inventory_adjustment&${auditScope}`
      ),
      200
    );
    expect(auditList.items).toHaveLength(1);
    expect(auditList.items[0]).toMatchObject({
      operationId: created.id,
      itemId: expect.any(String),
      type: "inventory_adjustment",
      productId: product.id,
      warehouseId: warehouse.id,
      quantity: 17,
      unitPrice: 42,
      direction: "in",
    });
  });

  test("recalculates product balance cost as weighted average on purchases", async ({
    request,
  }, testInfo) => {
    const [product, warehouse, supplier] = await Promise.all([
      createProduct(request, uniqueName("CostProduct", testInfo)),
      createWarehouse(request, uniqueName("CostWarehouse", testInfo)),
      createSupplier(request, uniqueName("CostSupplier", testInfo)),
    ]);
    const operationDate = "2099-02-02";

    await postJson<{ id: string }>(request, "/api/operations", {
      type: "purchase",
      operationDate,
      supplierId: supplier.id,
      items: [
        {
          productId: product.id,
          warehouseId: warehouse.id,
          quantity: 100,
          unitPrice: 10,
        },
      ],
    });

    await postJson<{ id: string }>(request, "/api/operations", {
      type: "purchase",
      operationDate,
      supplierId: supplier.id,
      items: [
        {
          productId: product.id,
          warehouseId: warehouse.id,
          quantity: 20,
          unitPrice: 11,
        },
      ],
    });

    const balances = await expectJson<ProductBalancesResponse>(
      request.get(
        `/api/product-balances?productId=${product.id}&warehouseId=${warehouse.id}`
      ),
      200
    );
    expect(balances.items).toHaveLength(1);
    expect(balances.items[0]).toMatchObject({
      productId: product.id,
      warehouseId: warehouse.id,
      quantity: 120,
    });
    expect(balances.items[0].unitCost).toBeCloseTo(10.1667, 4);

    const inventoryReport = await expectJson<{
      rows: {
        productId: string;
        totalQuantity: number;
        totalCost: number;
      }[];
    }>(
      request.get(
        `/api/reports/inventory-balances?search=${encodeURIComponent(product.name)}`
      ),
      200
    );
    const productRow = inventoryReport.rows.find(
      (row) => row.productId === product.id
    );
    expect(productRow).toMatchObject({
      productId: product.id,
      totalQuantity: 120,
    });
    expect(productRow?.totalCost).toBeCloseTo(1220, 2);
  });

  test("flattens operation items into sortable, filterable, paginated rows", async ({
    request,
  }, testInfo) => {
    const [productA, productB, warehouse, supplier] = await Promise.all([
      createProduct(request, uniqueName("FlatProductA", testInfo)),
      createProduct(request, uniqueName("FlatProductB", testInfo)),
      createWarehouse(request, uniqueName("FlatWarehouse", testInfo)),
      createSupplier(request, uniqueName("FlatSupplier", testInfo)),
    ]);
    const operationDate = "2099-03-01";

    const created = await postJson<{ id: string }>(request, "/api/operations", {
      type: "purchase",
      operationDate,
      supplierId: supplier.id,
      comment: "multi-line purchase",
      items: [
        {
          productId: productA.id,
          warehouseId: warehouse.id,
          quantity: 7,
          unitPrice: 10,
        },
        {
          productId: productB.id,
          warehouseId: warehouse.id,
          quantity: 3,
          unitPrice: 20,
        },
      ],
    });

    const listScope = `type=purchase&supplierId=${supplier.id}&from=${operationDate}&to=${operationDate}`;
    const sorted = await expectJson<OperationListResponse>(
      request.get(`/api/operations?${listScope}&sortBy=quantity&sortDir=asc`),
      200
    );
    expect(sorted.page.totalEstimate).toBe(2);
    expect(sorted.items.map((item) => item.quantity)).toEqual([3, 7]);
    expect(new Set(sorted.items.map((item) => item.operationId))).toEqual(
      new Set([created.id])
    );
    expect(sorted.items.every((item) => item.itemsSummary.length === 1)).toBe(
      true
    );

    const filtered = await expectJson<OperationListResponse>(
      request.get(
        `/api/operations?type=purchase&productId=${productA.id}&from=${operationDate}&to=${operationDate}`
      ),
      200
    );
    expect(filtered.items).toHaveLength(1);
    expect(filtered.items[0]).toMatchObject({
      operationId: created.id,
      productId: productA.id,
      productName: productA.name,
      warehouseId: warehouse.id,
      warehouseName: warehouse.name,
      supplierId: supplier.id,
      supplierName: supplier.name,
      quantity: 7,
      unitPrice: 10,
      direction: "in",
    });

    const paged = await expectJson<OperationListResponse>(
      request.get(
        `/api/operations?${listScope}&sortBy=quantity&sortDir=asc&limit=1&offset=1`
      ),
      200
    );
    expect(paged.page).toEqual({
      limit: 1,
      offset: 1,
      totalEstimate: 2,
    });
    expect(paged.items).toHaveLength(1);
    expect(paged.items[0].quantity).toBe(7);
  });

  test("patches editable operation fields and rejects invalid payment edits", async ({
    request,
  }, testInfo) => {
    const [supplierA, supplierB] = await Promise.all([
      createSupplier(request, uniqueName("PaymentSupplierA", testInfo)),
      createSupplier(request, uniqueName("PaymentSupplierB", testInfo)),
    ]);

    const created = await postJson<{ id: string }>(request, "/api/operations", {
      type: "payment",
      operationDate: "2099-04-01",
      supplierId: supplierA.id,
      paymentAmount: 50,
      comment: "initial payment",
    });

    const invalidAmount = await request.patch(`/api/operations/${created.id}`, {
      data: { paymentAmount: 0 },
    });
    expect(invalidAmount.status()).toBe(400);
    expect(await invalidAmount.json()).toEqual({
      error: "Payment amount must be positive",
    });

    const missingSupplier = await request.patch(`/api/operations/${created.id}`, {
      data: { supplierId: "" },
    });
    expect(missingSupplier.status()).toBe(400);
    expect(await missingSupplier.json()).toEqual({
      error: "Supplier is required",
    });

    const updated = await request.patch(`/api/operations/${created.id}`, {
      data: {
        operationDate: "2099-04-02",
        supplierId: supplierB.id,
        paymentAmount: 125,
        comment: "  settled after review  ",
      },
    });
    expect(updated.status()).toBe(200);
    expect(await updated.json()).toEqual({ id: created.id });

    const details = await expectJson<OperationDetailsResponse>(
      request.get(`/api/operations/${created.id}`),
      200
    );
    expect(details).toMatchObject({
      id: created.id,
      type: "payment",
      operationDate: "2099-04-02",
      supplierId: supplierB.id,
      supplierName: supplierB.name,
      paymentAmount: 125,
      comment: "settled after review",
      items: [],
    });
  });
});

test.describe("operations UI", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
  });

  test("shows grouped operation choices including inventory adjustments", async ({
    page,
  }) => {
    await page.goto("/operations/new");
    await expect(
      page.getByRole("heading", { name: "New Operation" })
    ).toBeVisible();

    const main = page.locator("main");
    await expect(main.getByText("Loading...")).not.toBeVisible();

    await expect(page.getByRole("tab", { name: "Incoming" })).toBeVisible();
    await expect(
      page.getByRole("tab", { name: "Internal Movement" })
    ).toBeVisible();
    await expect(page.getByRole("tab", { name: "Outgoing" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Adjustments" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Payments" })).toBeVisible();

    await page.getByRole("tab", { name: "Adjustments" }).click();
    await expect(main.getByText("Inventory Adjustment")).toBeVisible();
    await expect(main.getByText("Unit cost")).toBeVisible();
    await expect(main.getByText("Supplier", { exact: true })).not.toBeVisible();

    await page.getByRole("tab", { name: "Payments" }).click();
    await expect(main.getByText("Payment amount")).toBeVisible();

    await page.getByRole("tab", { name: "Internal Movement" }).click();
    await main.getByText("Transfer", { exact: true }).click();
    await expect(main.getByText("Source warehouse")).toBeVisible();
    await expect(main.getByText("Destination warehouse")).toBeVisible();

    await main.getByText("Defect", { exact: true }).click();
    await expect(main.getByText("Source warehouse")).toBeVisible();
    await expect(main.getByText("Quantity")).toBeVisible();
  });

  test("opens operation details and preserves list filters when editing", async ({
    request,
    page,
  }, testInfo) => {
    const [product, warehouse, supplier] = await Promise.all([
      createProduct(request, uniqueName("UiProduct", testInfo)),
      createWarehouse(request, uniqueName("UiWarehouse", testInfo)),
      createSupplier(request, uniqueName("UiSupplier", testInfo)),
    ]);
    const operationDate = "2099-05-01";
    const created = await postJson<{ id: string }>(request, "/api/operations", {
      type: "purchase",
      operationDate,
      supplierId: supplier.id,
      comment: "visible in details",
      items: [
        {
          productId: product.id,
          warehouseId: warehouse.id,
          quantity: 9,
          unitPrice: 31,
        },
      ],
    });

    await page.goto(
      `/operations?type=purchase&productId=${product.id}&from=${operationDate}&to=${operationDate}&sortBy=quantity&sortDir=asc`
    );
    const main = page.locator("main");
    await expect(main.getByText("Loading...")).not.toBeVisible();
    const row = main.locator("tbody tr").filter({ hasText: product.name });
    await expect(row).toBeVisible();
    await expect(row).toContainText(warehouse.name);
    await expect(row).toContainText(supplier.name);

    await row.getByRole("button", { name: "View Operation" }).click();
    await expect(page.getByRole("heading", { name: "Operation Details" })).toBeVisible();
    await expect(page.getByText("visible in details")).toBeVisible();
    await expect(page.getByText(product.name).last()).toBeVisible();

    await page.keyboard.press("Escape");
    await row.getByRole("button", { name: "Edit Operation" }).click();
    await expect(page).toHaveURL(new RegExp(`/operations/${created.id}/edit`));
    await expect(
      page.getByRole("heading", { name: "Edit Operation" })
    ).toBeVisible();
    await expect(page.getByText(product.name)).toBeVisible();
    await expect(page.getByText("Line items are read-only for now.")).toBeVisible();

    await page.locator("textarea").fill("edited through the UI");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page).toHaveURL(
      new RegExp(
        `/operations\\?type=purchase&productId=${product.id}&from=${operationDate}&to=${operationDate}&sortBy=quantity&sortDir=asc`
      )
    );
    await expect(main.getByText("Loading...")).not.toBeVisible();
    await expect(main.locator("tbody tr").filter({ hasText: product.name })).toBeVisible();

    const details = await expectJson<OperationDetailsResponse>(
      request.get(`/api/operations/${created.id}`),
      200
    );
    expect(details.comment).toBe("edited through the UI");
  });

  test("persists operation table column visibility with header labels", async ({
    page,
  }) => {
    await page.goto("/operations");
    await page.evaluate(() => {
      localStorage.removeItem("tover-columns-operations-unified");
    });
    await page.reload();

    const main = page.locator("main");
    await expect(main.getByText("Loading...")).not.toBeVisible();
    await expect(main.getByRole("table")).toBeVisible();
    await expect(
      main.getByRole("columnheader", { name: /Supplier/ })
    ).toBeVisible();

    await page.getByRole("button", { name: "Columns" }).click();
    await page.getByRole("menuitemcheckbox", { name: "Supplier" }).click();
    await expect(
      main.getByRole("columnheader", { name: /Supplier/ })
    ).not.toBeVisible();

    await page.reload();
    await expect(main.getByText("Loading...")).not.toBeVisible();
    await expect(
      main.getByRole("columnheader", { name: /Supplier/ })
    ).not.toBeVisible();
  });
});

import { expect, test } from "@playwright/test";
import type { APIRequestContext } from "@playwright/test";
import { authSkipReason, hasAuthCredentials } from "./auth-helpers";

/** Unique run ID to avoid collisions across parallel/repeated runs */
const RUN_ID = Date.now().toString(36);

function uniqueName(prefix: string): string {
  return `E2E-${prefix}-${RUN_ID}`;
}

async function postJson<T>(
  request: APIRequestContext,
  url: string,
  body: Record<string, unknown>
): Promise<T> {
  const response = await request.post(url, { data: body });
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()) as T;
}

async function getJson<T>(
  request: APIRequestContext,
  url: string
): Promise<T> {
  const response = await request.get(url);
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()) as T;
}

test.describe("inventory management", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
  });

  // ── Sidebar navigation ──────────────────────────────────────────────

  test.describe("sidebar navigation", () => {
    test("sidebar shows all navigation items", async ({ page }) => {
      await page.goto("/");
      await expect(page).toHaveURL(/\/operations$/);

      const sidebarLinks = page.locator("aside nav a");
      await expect(sidebarLinks.first()).toHaveText("Operations");
      await expect(page.getByRole("link", { name: "Dashboard" })).toHaveCount(0);
      await expect(page.getByRole("link", { name: "Settings" })).toBeVisible();

      // Reports group is expanded by default.
      await expect(page.getByRole("button", { name: "Reports" })).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Inventory Balances" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Product Movement" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Supplier Debt" })
      ).toBeVisible();

      // Master Data group is collapsed by default.
      await expect(page.getByRole("button", { name: "Master Data" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Products" })).not.toBeVisible();

      // Log out button
      await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
    });

    test("Master Data group is collapsible", async ({ page }) => {
      await page.goto("/");

      const productsLink = page.getByRole("link", { name: "Products" });
      await expect(productsLink).not.toBeVisible();

      await page.getByRole("button", { name: "Master Data" }).click();
      await expect(productsLink).toBeVisible();

      await page.getByRole("button", { name: "Master Data" }).click();
      await expect(productsLink).not.toBeVisible();
    });

    test("navigate to each entity page via sidebar", async ({ page }) => {
      await page.goto("/");
      await page.getByRole("button", { name: "Master Data" }).click();

      // Categories
      await page.getByRole("link", { name: "Categories" }).click();
      await expect(page).toHaveURL(/\/categories$/);
      await expect(
        page.getByRole("heading", { name: "Categories" })
      ).toBeVisible();

      // Warehouses
      await page.getByRole("link", { name: "Warehouses" }).click();
      await expect(page).toHaveURL(/\/warehouses$/);
      await expect(
        page.getByRole("heading", { name: "Warehouses" })
      ).toBeVisible();

      // Suppliers
      await page.getByRole("link", { name: "Suppliers" }).click();
      await expect(page).toHaveURL(/\/suppliers$/);
      await expect(
        page.getByRole("heading", { name: "Suppliers" })
      ).toBeVisible();

      // Stores
      await page.getByRole("link", { name: "Stores" }).click();
      await expect(page).toHaveURL(/\/stores$/);
      await expect(
        page.getByRole("heading", { name: "Stores" })
      ).toBeVisible();

      // Products
      await page.getByRole("link", { name: "Products" }).click();
      await expect(page).toHaveURL(/\/products$/);
      await expect(
        page.getByRole("heading", { name: "Products" })
      ).toBeVisible();

      // Operations
      await page.getByRole("link", { name: "Operations" }).click();
      await expect(page).toHaveURL(/\/operations$/);
      await expect(
        page.getByRole("heading", { name: "Operations" })
      ).toBeVisible();
    });
  });

  // ── Master Data Defaults ────────────────────────────────────────────

  test.describe("master data import defaults", () => {
    test("only one supported item per master data type can be the import default", async ({
      request,
    }) => {
      const suffix = uniqueName("ImportDefault");

      const categoryA = await postJson<{ id: string }>(request, "/api/categories", {
        name: `${suffix}-Category-A`,
        isImportDefault: true,
      });
      const categoryB = await postJson<{ id: string }>(request, "/api/categories", {
        name: `${suffix}-Category-B`,
        isImportDefault: true,
      });
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/categories/${categoryA.id}`
        )).isImportDefault
      ).toBe(false);
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/categories/${categoryB.id}`
        )).isImportDefault
      ).toBe(true);

      const storeA = await postJson<{ id: string }>(request, "/api/stores", {
        name: `${suffix}-Store-A`,
        isImportDefault: true,
      });
      const storeB = await postJson<{ id: string }>(request, "/api/stores", {
        name: `${suffix}-Store-B`,
        isImportDefault: true,
      });
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/stores/${storeA.id}`
        )).isImportDefault
      ).toBe(false);
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/stores/${storeB.id}`
        )).isImportDefault
      ).toBe(true);

      const warehouseA = await postJson<{ id: string }>(
        request,
        "/api/warehouses",
        {
          name: `${suffix}-Warehouse-A`,
          isImportDefault: true,
        }
      );
      const warehouseB = await postJson<{ id: string }>(
        request,
        "/api/warehouses",
        {
          name: `${suffix}-Warehouse-B`,
          isImportDefault: true,
        }
      );
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/warehouses/${warehouseA.id}`
        )).isImportDefault
      ).toBe(false);
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/warehouses/${warehouseB.id}`
        )).isImportDefault
      ).toBe(true);

      const supplierA = await postJson<{ id: string }>(request, "/api/suppliers", {
        name: `${suffix}-Supplier-A`,
        isImportDefault: true,
      });
      const supplierB = await postJson<{ id: string }>(request, "/api/suppliers", {
        name: `${suffix}-Supplier-B`,
        isImportDefault: true,
      });
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/suppliers/${supplierA.id}`
        )).isImportDefault
      ).toBe(false);
      expect(
        (await getJson<{ isImportDefault: boolean }>(
          request,
          `/api/suppliers/${supplierB.id}`
        )).isImportDefault
      ).toBe(true);
    });
  });

  // ── Categories CRUD ─────────────────────────────────────────────────

  test.describe("categories CRUD", () => {
    test("create, edit, and delete a category", async ({ page }) => {
      const catName = uniqueName("Cat");
      const catNameEdited = `${catName}-edited`;

      await page.goto("/categories");
      await expect(
        page.getByRole("heading", { name: "Categories" })
      ).toBeVisible();

      // Create
      await page.getByRole("button", { name: "New Category" }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await expect(
        page.getByRole("heading", { name: "New Category" })
      ).toBeVisible();
      await page.getByLabel("Category name").fill(catName);
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify created
      const row = page.getByRole("row").filter({ hasText: catName });
      await expect(row).toBeVisible();

      // Edit
      await row.getByRole("button").first().click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await expect(
        page.getByRole("heading", { name: "Edit Category" })
      ).toBeVisible();
      await page.getByLabel("Category name").clear();
      await page.getByLabel("Category name").fill(catNameEdited);
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify edited
      const editedRow = page.getByRole("row").filter({ hasText: catNameEdited });
      await expect(editedRow).toBeVisible();

      // Delete
      page.once("dialog", (dialog) => dialog.accept());
      await editedRow.getByRole("button").nth(1).click();
      await expect(editedRow).not.toBeVisible();
    });
  });

  // ── Stores CRUD ─────────────────────────────────────────────────────

  test.describe("stores CRUD", () => {
    test("create, edit, and delete a store", async ({ page }) => {
      const storeName = uniqueName("Store");
      const storeNameEdited = `${storeName}-edited`;

      await page.goto("/stores");
      await expect(
        page.getByRole("heading", { name: "Stores" })
      ).toBeVisible();

      // Create
      await page.getByRole("button", { name: "New Store" }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await page.getByLabel("Store name").fill(storeName);
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify
      const row = page.getByRole("row").filter({ hasText: storeName });
      await expect(row).toBeVisible();

      // Edit
      await row.getByRole("button").first().click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await page.getByLabel("Store name").clear();
      await page.getByLabel("Store name").fill(storeNameEdited);
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify edited
      const editedRow = page
        .getByRole("row")
        .filter({ hasText: storeNameEdited });
      await expect(editedRow).toBeVisible();

      // Delete
      page.once("dialog", (dialog) => dialog.accept());
      await editedRow.getByRole("button").nth(1).click();
      await expect(editedRow).not.toBeVisible();
    });
  });

  // ── Warehouses CRUD ─────────────────────────────────────────────────

  test.describe("warehouses CRUD", () => {
    test("warehouse lifecycle and defect protection", async ({ page }) => {
      const whName = uniqueName("WH");
      const whNameEdited = `${whName}-edited`;

      await page.goto("/warehouses");
      await expect(
        page.getByRole("heading", { name: "Warehouses" })
      ).toBeVisible();

      // Verify default defect warehouse exists and its delete button is disabled
      const defectRow = page
        .locator("tbody tr")
        .filter({ hasText: "Default Defect" })
        .first();
      await expect(defectRow).toBeVisible();
      await expect(defectRow.getByRole("button").nth(1)).toBeDisabled();

      // Create
      await page.getByRole("button", { name: "New Warehouse" }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await page.getByLabel("Warehouse name").fill(whName);
      await page.getByLabel("Description").fill("Test description");
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify
      const row = page.getByRole("row").filter({ hasText: whName });
      await expect(row).toBeVisible();

      // Edit
      await row.getByRole("button").first().click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await expect(
        page.getByRole("heading", { name: "Edit Warehouse" })
      ).toBeVisible();
      await page.getByLabel("Warehouse name").clear();
      await page.getByLabel("Warehouse name").fill(whNameEdited);
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify edited
      const editedRow = page
        .getByRole("row")
        .filter({ hasText: whNameEdited });
      await expect(editedRow).toBeVisible();

      // Delete
      page.once("dialog", (dialog) => dialog.accept());
      await editedRow.getByRole("button").nth(1).click();
      await expect(editedRow).not.toBeVisible();
    });
  });

  // ── Suppliers CRUD ──────────────────────────────────────────────────

  test.describe("suppliers CRUD", () => {
    test("create, edit, and delete a supplier", async ({ page }) => {
      const supName = uniqueName("Sup");
      const supNameEdited = `${supName}-edited`;

      await page.goto("/suppliers");
      await expect(
        page.getByRole("heading", { name: "Suppliers" })
      ).toBeVisible();

      // Create
      await page.getByRole("button", { name: "New Supplier" }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await page.getByLabel("Supplier name").fill(supName);
      await page.getByLabel("Address").fill("123 Test St");
      await page.getByLabel("Contact info").fill("test@example.com");
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify
      const row = page.getByRole("row").filter({ hasText: supName });
      await expect(row).toBeVisible();
      await expect(row).toContainText("123 Test St");
      await expect(row).toContainText("test@example.com");

      // Edit
      await row.getByRole("button").first().click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await expect(
        page.getByRole("heading", { name: "Edit Supplier" })
      ).toBeVisible();
      await page.getByLabel("Supplier name").clear();
      await page.getByLabel("Supplier name").fill(supNameEdited);
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify edited
      const editedRow = page
        .getByRole("row")
        .filter({ hasText: supNameEdited });
      await expect(editedRow).toBeVisible();

      // Delete
      page.once("dialog", (dialog) => dialog.accept());
      await editedRow.getByRole("button").nth(1).click();
      await expect(editedRow).not.toBeVisible();
    });
  });

  // ── Products CRUD ───────────────────────────────────────────────────

  test.describe("products CRUD", () => {
    test("create and delete a product with SKU", async ({ page }) => {
      const prodName = uniqueName("Prod");
      const skuCode = `SKU-${RUN_ID}`;

      await page.goto("/products");
      await expect(
        page.getByRole("heading", { name: "Products" })
      ).toBeVisible();

      // Verify search input exists
      await expect(
        page.getByPlaceholder("Search products...")
      ).toBeVisible();

      // Create
      await page.getByRole("button", { name: "New Product" }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await page.getByLabel("Product name").fill(prodName);
      await page.getByLabel("SKU Code").fill(skuCode);
      await page.getByRole("button", { name: "Save" }).click();
      await expect(page.getByRole("dialog")).not.toBeVisible();

      // Verify created
      const row = page.getByRole("row").filter({ hasText: prodName });
      await expect(row).toBeVisible();
      await expect(row).toContainText(skuCode);

      // Delete
      page.once("dialog", (dialog) => dialog.accept());
      await row.getByRole("button").nth(1).click();
      await expect(row).not.toBeVisible();
    });
  });

  // ── Operations ──────────────────────────────────────────────────────

  test.describe("operations", () => {
    test("operations page renders with header filters and pagination", async ({ page }) => {
      await page.goto("/operations");
      await expect(
        page.getByRole("heading", { name: "Operations" })
      ).toBeVisible();

      // "New Operation" link
      await expect(
        page.getByRole("link", { name: "New Operation" })
      ).toBeVisible();

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible();
      await expect(main.getByRole("table")).toBeVisible();

      // Header filters are compact icon buttons and open controls on demand.
      await expect(
        main.getByRole("button", { name: /Filter/ })
      ).toHaveCount(5);
      await main.getByRole("button", { name: "Filter Date" }).click();
      await expect(page.locator('input[type="date"]')).toHaveCount(2);
      await page.keyboard.press("Escape");
      await expect(
        page.getByRole("button", { name: "Previous", exact: true })
      ).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Next", exact: true })
      ).toBeVisible();

      await expect(
        main.getByRole("columnheader", { name: /Quantity/ })
      ).toBeVisible();
      await expect(
        main.getByRole("columnheader", { name: /Price/ })
      ).toBeVisible();
    });

    test("operations type filter changes displayed results", async ({ page }) => {
      await page.goto("/operations");

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible();

      await main.getByRole("button", { name: "Filter Type" }).click();
      await page.getByRole("combobox", { name: "Type" }).fill("pur");
      await page.getByRole("option", { name: "Purchase" }).click();

      await expect(main.getByText("Loading...")).not.toBeVisible();
      await expect(main.getByText("Purchase").first()).toBeVisible();
    });

    test("operations rows separate product, quantity, and currency values", async ({
      page,
    }) => {
      await page.goto("/operations");

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible();
      await expect(main.getByRole("table")).toBeVisible();

      const firstDataRow = main.locator("tbody tr").first();
      if ((await firstDataRow.count()) > 0) {
        await expect(firstDataRow.locator("td").nth(2)).not.toContainText(
          /\(\d/
        );
        await expect(firstDataRow.locator("td").nth(3)).toBeVisible();
      }

      const currencyCells = main.locator("td").filter({ hasText: /[$€£₽]/ });
      if ((await currencyCells.count()) > 0) {
        await expect(currencyCells.first()).toBeVisible();
      }
    });

    test("new operation form shows correct fields per type", async ({
      page,
    }) => {
      await page.goto("/operations/new");
      await expect(
        page.getByRole("heading", { name: "New Operation" })
      ).toBeVisible();

      // Wait for reference data to load
      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible();

      // Common fields always visible
      await expect(main.getByText("Date")).toBeVisible();
      await expect(main.getByText("Comment")).toBeVisible();

      // Default group is Incoming and Purchase should show supplier + items
      await expect(page.getByRole("tab", { name: "Incoming" })).toBeVisible();
      await expect(
        page.getByRole("tab", { name: "Internal Movement" })
      ).toBeVisible();
      await expect(page.getByRole("tab", { name: "Outgoing" })).toBeVisible();
      await expect(page.getByRole("tab", { name: "Adjustments" })).toBeVisible();
      await expect(page.getByRole("tab", { name: "Payments" })).toBeVisible();
      await expect(main.getByText("Supplier", { exact: true })).toBeVisible();
      await expect(main.getByText("Add item")).toBeVisible();

      // Switch to Adjustments — unit cost field, no supplier
      await page.getByRole("tab", { name: "Adjustments" }).click();
      await expect(main.getByText("Inventory Adjustment")).toBeVisible();
      await expect(main.getByText("Unit cost")).toBeVisible();
      await expect(
        main.getByText("Supplier", { exact: true })
      ).not.toBeVisible();

      // Switch to Payments — amount field, no items
      await page.getByRole("tab", { name: "Payments" }).click();
      await expect(main.getByText("Payment amount")).toBeVisible();

      // Switch to Transfer — source/dest warehouse
      await page.getByRole("tab", { name: "Internal Movement" }).click();
      await main.getByText("Transfer", { exact: true }).click();
      await expect(main.getByText("Source warehouse")).toBeVisible();
      await expect(main.getByText("Destination warehouse")).toBeVisible();

      // Switch to Defect — product + source warehouse + quantity
      await main.getByText("Defect", { exact: true }).click();
      await expect(main.getByText("Source warehouse")).toBeVisible();
      await expect(main.getByText("Quantity")).toBeVisible();
    });

    test("cancel returns to operations list", async ({ page }) => {
      await page.goto("/operations/new");
      await expect(
        page.getByRole("heading", { name: "New Operation" })
      ).toBeVisible();

      await page.getByRole("button", { name: "Cancel" }).click();
      await expect(page).toHaveURL(/\/operations$/);
    });
  });
});

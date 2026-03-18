import { expect, test } from "@playwright/test";

function hasAuthCredentials(): boolean {
  return Boolean(process.env.E2E_EMAIL && process.env.E2E_PASSWORD);
}

/** Unique run ID to avoid collisions across parallel/repeated runs */
const RUN_ID = Date.now().toString(36);

function uniqueName(prefix: string): string {
  return `E2E-${prefix}-${RUN_ID}`;
}

test.describe("inventory management", () => {
  test.beforeEach(() => {
    test.skip(
      !hasAuthCredentials(),
      "Set E2E_EMAIL and E2E_PASSWORD to run authenticated tests"
    );
  });

  // ── Sidebar navigation ──────────────────────────────────────────────

  test.describe("sidebar navigation", () => {
    test("sidebar shows all navigation items", async ({ page }) => {
      await page.goto("/");

      // Top-level links
      await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Operations" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Team" })).toBeVisible();

      // Master Data group items (open by default)
      await expect(page.getByRole("link", { name: "Products" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Warehouses" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Suppliers" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Categories" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Stores" })).toBeVisible();

      // Log out button
      await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();

      // Reports group (collapsible nav group)
      await expect(page.getByRole("button", { name: "Reports" })).toBeVisible();
    });

    test("Master Data group is collapsible", async ({ page }) => {
      await page.goto("/");

      // Products link should be visible initially (group open by default)
      const productsLink = page.getByRole("link", { name: "Products" });
      await expect(productsLink).toBeVisible();

      // Click the Master Data toggle button to collapse
      await page.getByRole("button", { name: "Master Data" }).click();
      await expect(productsLink).not.toBeVisible();

      // Click again to expand
      await page.getByRole("button", { name: "Master Data" }).click();
      await expect(productsLink).toBeVisible();
    });

    test("navigate to each entity page via sidebar", async ({ page }) => {
      await page.goto("/");

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
        .getByRole("row")
        .filter({ hasText: "Default Defect" });
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
    test("operations page renders with filter", async ({ page }) => {
      await page.goto("/operations");
      await expect(
        page.getByRole("heading", { name: "Operations" })
      ).toBeVisible();

      // "New Operation" link
      await expect(
        page.getByRole("link", { name: "New Operation" })
      ).toBeVisible();

      // Type filter combobox
      await expect(page.getByRole("combobox")).toBeVisible();
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

      // Default tab is Purchase — supplier and items should be visible
      await expect(main.getByText("Supplier", { exact: true })).toBeVisible();
      await expect(main.getByText("Add item")).toBeVisible();

      // Switch to Payment — amount field, no items
      await page.getByRole("tab", { name: "Payment" }).click();
      await expect(main.getByText("Payment amount")).toBeVisible();

      // Switch to Transfer — source/dest warehouse
      await page.getByRole("tab", { name: "Transfer" }).click();
      await expect(main.getByText("Source warehouse")).toBeVisible();
      await expect(main.getByText("Destination warehouse")).toBeVisible();

      // Switch to Defect — product + source warehouse + quantity
      await page.getByRole("tab", { name: "Defect" }).click();
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

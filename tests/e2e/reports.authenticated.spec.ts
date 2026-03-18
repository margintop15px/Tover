import { expect, test } from "@playwright/test";

function hasAuthCredentials(): boolean {
  return Boolean(process.env.E2E_EMAIL && process.env.E2E_PASSWORD);
}

test.describe("reports", () => {
  test.beforeEach(() => {
    test.skip(
      !hasAuthCredentials(),
      "Set E2E_EMAIL and E2E_PASSWORD to run authenticated tests"
    );
  });

  // ── Sidebar navigation ──────────────────────────────────────────────

  test.describe("sidebar navigation", () => {
    test("Reports group is collapsible and contains all report links", async ({
      page,
    }) => {
      await page.goto("/");

      const reportsBtn = page.getByRole("button", { name: "Reports" });
      await expect(reportsBtn).toBeVisible();

      // Expand the Reports group
      await reportsBtn.click();

      // All 4 report links should be visible
      await expect(
        page.getByRole("link", { name: "Inventory Balances" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Product Movement" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Supplier Debt" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Operations Log" })
      ).toBeVisible();

      // Collapse the Reports group
      await reportsBtn.click();

      // Links should be hidden
      await expect(
        page.getByRole("link", { name: "Inventory Balances" })
      ).not.toBeVisible();
      await expect(
        page.getByRole("link", { name: "Operations Log" })
      ).not.toBeVisible();
    });

    test("navigate to each report page via sidebar", async ({ page }) => {
      await page.goto("/");

      // Expand Reports group
      await page.getByRole("button", { name: "Reports" }).click();

      // Operations Log
      await page.getByRole("link", { name: "Operations Log" }).click();
      await expect(page).toHaveURL(/\/reports\/operations$/);
      await expect(
        page.getByRole("heading", { name: "Operations Log" })
      ).toBeVisible();

      // Supplier Debt
      await page.getByRole("link", { name: "Supplier Debt" }).click();
      await expect(page).toHaveURL(/\/reports\/supplier-debt$/);
      await expect(
        page.getByRole("heading", { name: "Supplier Debt" })
      ).toBeVisible();

      // Product Movement
      await page.getByRole("link", { name: "Product Movement" }).click();
      await expect(page).toHaveURL(/\/reports\/movement$/);
      await expect(
        page.getByRole("heading", { name: "Product Movement" })
      ).toBeVisible();

      // Inventory Balances
      await page.getByRole("link", { name: "Inventory Balances" }).click();
      await expect(page).toHaveURL(/\/reports\/inventory$/);
      await expect(
        page.getByRole("heading", { name: "Inventory Balances" })
      ).toBeVisible();
    });
  });

  // ── Operations Log ──────────────────────────────────────────────────

  test.describe("operations log", () => {
    test("page renders with filters", async ({ page }) => {
      await page.goto("/reports/operations");

      await expect(
        page.getByRole("heading", { name: "Operations Log" })
      ).toBeVisible();

      const main = page.locator("main");

      // Date inputs
      await expect(main.getByText("From")).toBeVisible();
      await expect(main.getByText("To")).toBeVisible();

      // Filter comboboxes (type, product, warehouse, supplier)
      const comboboxes = main.getByRole("combobox");
      await expect(comboboxes).toHaveCount(4);

      // Data table or loading state
      await expect(
        main.getByRole("table").or(main.getByText("Loading..."))
      ).toBeVisible();
    });

    test("type filter changes displayed results", async ({ page }) => {
      await page.goto("/reports/operations");

      const main = page.locator("main");

      // Wait for data to load
      await expect(main.getByText("Loading...")).not.toBeVisible();

      // Open the first combobox (Type filter) and select Purchase
      const typeCombobox = main.getByRole("combobox").first();
      await typeCombobox.click();
      await page.getByRole("option", { name: "Purchase" }).click();

      // Wait for table to update and verify Purchase badge is visible
      await expect(main.getByText("Loading...")).not.toBeVisible();
      await expect(main.getByText("Purchase").first()).toBeVisible();
    });

    test("pagination controls are visible", async ({ page }) => {
      await page.goto("/reports/operations");

      const main = page.locator("main");
      // Wait for table to load before checking pagination
      await expect(main.getByRole("table")).toBeVisible({ timeout: 15000 });

      await expect(
        page.getByRole("button", { name: "Previous", exact: true })
      ).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Next", exact: true })
      ).toBeVisible();
    });
  });

  // ── Supplier Debt ───────────────────────────────────────────────────

  test.describe("supplier debt", () => {
    test("page renders with KPI cards", async ({ page }) => {
      await page.goto("/reports/supplier-debt");

      await expect(
        page.getByRole("heading", { name: "Supplier Debt" })
      ).toBeVisible();

      const main = page.locator("main");

      // KPI cards (wait for data to load)
      await expect(main.getByText("Total Purchased")).toBeVisible({ timeout: 15000 });
      await expect(main.getByText("Total Paid")).toBeVisible();
      await expect(main.getByText("Total Debt")).toBeVisible();

      // Filter controls: 3 date inputs + 1 combobox
      await expect(main.getByText("As of date")).toBeVisible();
      await expect(main.getByText("From", { exact: true })).toBeVisible();
      await expect(main.getByText("To", { exact: true })).toBeVisible();
      await expect(main.getByRole("combobox")).toBeVisible();
    });

    test("debt type filter works", async ({ page }) => {
      await page.goto("/reports/supplier-debt");

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible();

      // Open debt type combobox and select Creditor
      await main.getByRole("combobox").click();
      await page.getByRole("option", { name: "Creditor" }).click();

      // Page should re-render without crashing
      await expect(main.getByText("Loading...")).not.toBeVisible();
      await expect(
        page.getByRole("heading", { name: "Supplier Debt" })
      ).toBeVisible();
    });

    test("row click opens drill-down sheet", async ({ page }) => {
      await page.goto("/reports/supplier-debt");

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible();

      // Click the first data row if table has rows
      const rows = main.getByRole("row");
      const rowCount = await rows.count();

      // Skip header row — if there are data rows, click the first one
      if (rowCount > 1) {
        await rows.nth(1).click();

        // Verify sheet opens with supplier transactions
        await expect(
          page.getByText("Supplier Transactions")
        ).toBeVisible();
      }
    });
  });

  // ── Product Movement ────────────────────────────────────────────────

  test.describe("product movement", () => {
    test("page renders with date range and group-by tabs", async ({
      page,
    }) => {
      await page.goto("/reports/movement");

      await expect(
        page.getByRole("heading", { name: "Product Movement" })
      ).toBeVisible();

      const main = page.locator("main");

      // Date inputs
      await expect(main.getByText("From")).toBeVisible();
      await expect(main.getByText("To")).toBeVisible();

      // Group-by tabs
      await expect(
        page.getByRole("tab", { name: "By Product" })
      ).toBeVisible();
      await expect(
        page.getByRole("tab", { name: "By Warehouse" })
      ).toBeVisible();

      // Data table or loading state
      await expect(
        main.getByRole("table").or(main.getByText("Loading..."))
      ).toBeVisible();
    });

    test("group-by toggle switches view", async ({ page }) => {
      await page.goto("/reports/movement");

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible({ timeout: 15000 });

      // Set date range to cover seed data (Jan 2025)
      await main.locator('input[type="date"]').first().fill("2025-01-01");
      await main.locator('input[type="date"]').nth(1).fill("2025-01-31");
      await expect(main.getByRole("table")).toBeVisible({ timeout: 15000 });

      // Default tab is "By Product" — Product column header visible
      await expect(
        main.getByRole("columnheader", { name: "Product", exact: true })
      ).toBeVisible();
      await expect(
        main.getByRole("columnheader", { name: "SKU" })
      ).toBeVisible();

      // Switch to "By Warehouse"
      await page.getByRole("tab", { name: "By Warehouse" }).click();
      await expect(main.getByRole("table")).toBeVisible({ timeout: 15000 });

      // Warehouse column header should be visible, SKU should not
      await expect(
        main.getByRole("columnheader", { name: "Warehouse", exact: true })
      ).toBeVisible();
      await expect(
        main.getByRole("columnheader", { name: "SKU" })
      ).not.toBeVisible();
    });

    test("movement table shows expected columns", async ({ page }) => {
      await page.goto("/reports/movement");

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible({ timeout: 15000 });

      // Set date range to cover seed data (Jan 2025)
      await main.locator('input[type="date"]').first().fill("2025-01-01");
      await main.locator('input[type="date"]').nth(1).fill("2025-01-31");
      await expect(main.getByRole("table")).toBeVisible({ timeout: 15000 });

      // Verify key column headers
      await expect(
        main.getByRole("columnheader", { name: "Purchase In" })
      ).toBeVisible();
      await expect(
        main.getByRole("columnheader", { name: "Sale Out" })
      ).toBeVisible();
      await expect(
        main.getByRole("columnheader", { name: "Net" })
      ).toBeVisible();
    });
  });

  // ── Inventory Balances ──────────────────────────────────────────────

  test.describe("inventory balances", () => {
    test("page renders with toggles", async ({ page }) => {
      await page.goto("/reports/inventory");

      await expect(
        page.getByRole("heading", { name: "Inventory Balances" })
      ).toBeVisible();

      const main = page.locator("main");

      // Mode tabs
      await expect(
        page.getByRole("tab", { name: "Current" })
      ).toBeVisible();
      await expect(
        page.getByRole("tab", { name: "Historical" })
      ).toBeVisible();

      // Display tabs
      await expect(page.getByRole("tab", { name: "Units" })).toBeVisible();
      await expect(page.getByRole("tab", { name: "Cost" })).toBeVisible();

      // Filter controls
      await expect(
        main.getByPlaceholder("Search products...")
      ).toBeVisible();
      const comboboxes = main.getByRole("combobox");
      await expect(comboboxes).toHaveCount(3); // category, warehouse, store

      // Checkboxes
      await expect(main.getByLabel("Hide zeros")).toBeVisible();
      await expect(main.getByLabel("Negatives only")).toBeVisible();
    });

    test("historical mode shows date picker", async ({ page }) => {
      await page.goto("/reports/inventory");

      const main = page.locator("main");

      // In Current mode, count date inputs as baseline
      const dateInputsBefore = main.locator('input[type="date"]');
      const countBefore = await dateInputsBefore.count();

      // Switch to Historical mode
      await page.getByRole("tab", { name: "Historical" }).click();

      // A new date input should appear
      const dateInputsAfter = main.locator('input[type="date"]');
      await expect(dateInputsAfter).toHaveCount(countBefore + 1);

      // Switch back to Current mode
      await page.getByRole("tab", { name: "Current" }).click();

      // Date input should disappear
      await expect(main.locator('input[type="date"]')).toHaveCount(
        countBefore
      );
    });

    test("inventory table renders product data", async ({ page }) => {
      await page.goto("/reports/inventory");

      const main = page.locator("main");
      await expect(main.getByText("Loading...")).not.toBeVisible();

      // Verify table headers
      const table = main.getByRole("table");
      await expect(table).toBeVisible();

      await expect(
        table.getByRole("columnheader", { name: "Product", exact: true })
      ).toBeVisible();
      await expect(
        table.getByRole("columnheader", { name: "SKU" })
      ).toBeVisible();
      await expect(
        table.getByRole("columnheader", { name: "Total" })
      ).toBeVisible();

      // If rows exist, verify at least one has content
      const rows = table.getByRole("row");
      const rowCount = await rows.count();
      if (rowCount > 1) {
        // At least one data row (beyond header)
        await expect(rows.nth(1)).not.toBeEmpty();
      }
    });
  });
});

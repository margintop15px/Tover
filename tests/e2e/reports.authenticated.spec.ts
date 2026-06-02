import { expect, test } from "@playwright/test";
import { authSkipReason, hasAuthCredentials } from "./auth-helpers";

const RUN_ID = Date.now().toString(36);

function uniqueName(prefix: string): string {
  return `E2E-${prefix}-${RUN_ID}-${Math.random().toString(36).slice(2, 8)}`;
}

test.describe("reports", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
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

      // All report links should be visible
      await expect(
        page.getByRole("link", { name: "Inventory Balances" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Product Movement" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Sales Volume" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Turnover" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Defects" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Supplier Debt" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Saved Reports" })
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Operations Log" })
      ).not.toBeVisible();

      // Collapse the Reports group
      await reportsBtn.click();

      // Links should be hidden
      await expect(
        page.getByRole("link", { name: "Inventory Balances" })
      ).not.toBeVisible();
    });

    test("navigate to each report page via sidebar", async ({ page }) => {
      await page.goto("/");

      // Expand Reports group
      await page.getByRole("button", { name: "Reports" }).click();

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

      // Sales Volume
      await page.getByRole("link", { name: "Sales Volume" }).click();
      await expect(page).toHaveURL(/\/reports\/sales$/);
      await expect(page.getByRole("heading", { name: "Sales Volume" })).toBeVisible();

      // Turnover
      await page.getByRole("link", { name: "Turnover" }).click();
      await expect(page).toHaveURL(/\/reports\/turnover$/);
      await expect(page.getByRole("heading", { name: "Inventory Turnover" })).toBeVisible();

      // Defects
      await page.getByRole("link", { name: "Defects" }).click();
      await expect(page).toHaveURL(/\/reports\/defects$/);
      await expect(page.getByRole("heading", { name: "Defects" })).toBeVisible();
    });
  });

  // ── Saved Report Constructor ────────────────────────────────────────

  test.describe("saved report constructor", () => {
    test("creates, opens, exports, edits, and deletes a saved report", async ({ page }) => {
      const reportName = uniqueName("Report");
      const editedReportName = `${reportName}-Edited`;
      const templatesResponse = await page.request.get("/api/report-templates");
      const templatesPayload = await templatesResponse.json().catch(() => ({}));
      test.skip(
        templatesResponse.status() === 500 &&
          /report_templates/.test(String(templatesPayload.error || "")),
        "Local Supabase schema is missing migration 011_operation_reporting_ledger.sql"
      );
      page.on("dialog", (dialog) => dialog.accept());

      await page.goto("/reports/templates/new");
      await expect(
        page.getByRole("heading", { name: "Create report" })
      ).toBeVisible();
      await expect(page.getByText("Live preview")).toBeVisible();

      await page.locator("main input").first().fill(reportName);
      await expect(page.getByRole("button", { name: "Save" })).toBeEnabled();
      await page.getByRole("button", { name: "Save" }).click();

      await expect(page).toHaveURL(/\/reports\/templates$/);
      let row = page.getByRole("row").filter({ hasText: reportName });
      await expect(row).toBeVisible();

      await row.getByRole("link", { name: "Open" }).click();
      await expect(page).toHaveURL(/\/reports\/templates\/[^/]+$/);
      await expect(page.getByRole("heading", { name: reportName })).toBeVisible();
      await expect(page.getByRole("button", { name: "Export CSV" })).toBeVisible();

      const savedReportDownload = page.waitForEvent("download");
      await page.getByRole("button", { name: "Export CSV" }).click();
      expect((await savedReportDownload).suggestedFilename()).toMatch(/\.csv$/);

      await page.getByRole("link", { name: "Edit" }).click();
      await expect(
        page.getByRole("heading", { name: "Edit report" })
      ).toBeVisible();
      await page.locator("main input").first().fill(editedReportName);
      await page.getByRole("button", { name: "Save" }).click();

      await expect(page).toHaveURL(/\/reports\/templates\/[^/]+$/);
      await expect(page.getByRole("heading", { name: editedReportName })).toBeVisible();

      await page.goto("/reports/templates");
      row = page.getByRole("row").filter({ hasText: editedReportName });
      await expect(row).toBeVisible();
      await row.getByRole("button", { name: "Delete" }).click();
      await expect(row).not.toBeVisible();
    });

    test("source changes constrain measures in the builder", async ({ page }) => {
      await page.goto("/reports/templates/new");
      const main = page.locator("main");
      const sourceSelect = main.getByRole("combobox").first();

      await sourceSelect.click();
      await page.getByRole("option", { name: "Sales Volume" }).click();
      await expect(main.getByText("Units", { exact: true })).toBeVisible();
      await expect(main.getByText("Invoice", { exact: true })).not.toBeVisible();

      await sourceSelect.click();
      await page.getByRole("option", { name: "Supplier Debt" }).click();
      await expect(main.getByText("Total Purchased", { exact: true })).toBeVisible();
      await expect(main.getByText("Total Paid", { exact: true })).toBeVisible();
      await expect(main.getByText("Total Debt", { exact: true })).toBeVisible();
    });

    test("date mode and selected measures update the live preview", async ({
      page,
    }) => {
      await page.goto("/reports/templates/new");
      const main = page.locator("main");
      const sourceSelect = main.getByRole("combobox").first();

      await sourceSelect.click();
      await page.getByRole("option", { name: "Supplier Debt" }).click();

      await main.getByRole("tab", { name: "As of date" }).click();
      await expect(main.getByText("As of date:", { exact: false })).toBeVisible();

      await main.getByRole("checkbox", { name: "Total Paid" }).click();
      await expect(
        main.getByText("Supplier · Total Purchased, Total Debt", {
          exact: true,
        })
      ).toBeVisible();
      await expect(
        main.getByText("Supplier · Total Purchased, Total Paid, Total Debt", {
          exact: true,
        })
      ).not.toBeVisible();
    });
  });

  // ── Predefined Report APIs ──────────────────────────────────────────

  test.describe("predefined report APIs", () => {
    test("invalid saved report config returns 400", async ({ page }) => {
      const response = await page.request.post("/api/report-templates", {
        data: {
          name: uniqueName("Invalid Report"),
          source: "sales_volume",
          dateMode: "period",
          rowDimensions: ["category"],
          columnDimensions: [],
          measures: ["quantity"],
          filters: {},
        },
      });
      const payload = await response.json().catch(() => ({}));

      expect(response.status()).toBe(400);
      expect(String(payload.error || "")).toContain("Row dimension");
    });

    test("sales volume API returns report JSON for store grouping", async ({
      page,
    }) => {
      const response = await page.request.get(
        "/api/reports/sales-volume?from=2026-04-20&to=2026-05-20&groupBy=store"
      );
      const payload = await response.json().catch(() => ({}));

      test.skip(
        response.status() === 500 &&
          /inventory_movements/.test(String(payload.error || "")),
        "Local Supabase schema is missing migration 011_operation_reporting_ledger.sql"
      );

      expect(response.status()).toBe(200);
      expect(payload).toMatchObject({
        from: "2026-04-20",
        to: "2026-05-20",
        groupBy: "store",
      });
      expect(Array.isArray(payload.rows)).toBe(true);
    });

    test("sales volume page exports CSV", async ({ page }) => {
      await page.goto("/reports/sales");
      await expect(page.getByRole("heading", { name: "Sales Volume" })).toBeVisible();
      await expect(page.getByText("Loading...")).not.toBeVisible({ timeout: 15000 });

      const downloadPromise = page.waitForEvent("download");
      await page.getByRole("button", { name: "Export CSV" }).click();
      expect((await downloadPromise).suggestedFilename()).toMatch(/\.csv$/);
    });
  });

  // ── Removed Operations Log ──────────────────────────────────────────

  test.describe("operations log route", () => {
    test("old report route returns 404", async ({ page }) => {
      const response = await page.goto("/reports/operations");
      expect(response?.status()).toBe(404);
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
      await expect(main.getByText("From", { exact: true })).toBeVisible();
      await expect(main.getByText("To", { exact: true })).toBeVisible();

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
      await expect(comboboxes).toHaveCount(4); // category, warehouse, store, quality

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

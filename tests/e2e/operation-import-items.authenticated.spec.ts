import { expect, test, type Page } from "@playwright/test";
import { normalizeAiDrafts, normalizeAndValidateDraft } from "../../src/lib/operation-imports/pipeline";
import type { OperationImportCandidateRecord, OperationImportDraft, RefData } from "../../src/lib/operation-imports/types";

const sourceItems = [
  ["Ноутбук Lenovo IdeaPad 3", "LEN-IP3-15", 5, 45000],
  ["Мышь беспроводная Logitech M170", "LOG-M170", 10, 1200],
  ["Кабель HDMI 2 метра", "CAB-HDMI-2", 20, 450],
  ['Монитор 27" Samsung S27R500', "SAM-S27R500", 3, 28500],
  ["Клавиатура OKLICK 930", "OKL-930", 15, 680],
  ["Розетка-удлинитель 5 гнёзд", "EXT-5SOCK", 8, 350],
] as const;

async function mockInvoice(page: Page, locale: "en" | "ru" = "en") {
  const ref: RefData = {
    categories: [], stores: [], products: [],
    suppliers: [{ id: "supplier", name: "ТЕХНОСНАБ", address: null, contactInfo: null, createdAt: "" }],
    warehouses: [
      { id: "main", name: "Main", description: null, purpose: null, isDefaultDefect: false, createdAt: "" },
      { id: "other", name: "Other", description: null, purpose: null, isDefaultDefect: false, createdAt: "" },
    ],
  };
  const draft: OperationImportDraft = {
    type: "purchase", operationDate: "2024-10-15", supplierId: "supplier",
    items: sourceItems.map(([productName, skuCode, quantity, unitPrice], index) => ({
      productName, skuCode, quantity, unitPrice,
      warehouseId: index === 0 ? "main" : index === 1 ? "other" : undefined,
    })),
  };
  const built = normalizeAiDrafts([draft], ref, [], "image")[0];
  const candidate = {
    id: "candidate", import_id: "invoice", row_index: 0,
    operation: built.operation, normalized_operation: built.normalizedOperation,
    validation_errors: built.validationErrors, status: built.status, raw: built.raw,
    source: built.source,
  } as OperationImportCandidateRecord;
  ref.products = sourceItems.slice(0, 5).map(([name, skuCode], index) => ({
    id: `product-${index}`, name, skuCode, categoryId: null, categoryName: null,
    storeId: null, storeName: null, isDefectCopy: false, createdAt: "",
  }));
  const writes: OperationImportDraft[] = [];
  let committed: OperationImportDraft | undefined;
  const summary = () => ({
    total: 1, ready: Number(candidate.status === "ready"), needsReview: Number(candidate.validation_errors.length > 0),
    approved: Number(candidate.status === "approved"), committed: Number(candidate.status === "committed"),
  });
  const loadPreview = () => ({
    rows: Number(candidate.status === "approved"),
    amount: candidate.status === "approved" ? candidate.normalized_operation.items!.reduce((sum, item) => sum + item.quantity! * item.unitPrice!, 0) : 0,
    missingAmountRows: 0, typeCounts: [],
  });
  const snapshot = () => ({
    import: { id: "invoice", file_name: "invoice.jpg", status: candidate.status === "committed" ? "completed" : "needs_review", summary: summary() },
    candidates: [candidate], candidatePage: { limit: 50, offset: 0, total: 1 }, loadPreview: loadPreview(),
  });
  const patch = (operation: OperationImportDraft) => {
    writes.push(structuredClone(operation));
    const validation = normalizeAndValidateDraft(operation, ref, []);
    candidate.operation = operation;
    candidate.normalized_operation = validation.normalized;
    candidate.validation_errors = validation.validationErrors;
    candidate.status = validation.status;
  };
  await page.addInitScript((value) => localStorage.setItem("tover-locale", value), locale);
  // Only auth reads use the real server. Every import/master-data write is intercepted.
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/auth/me") return route.continue();
    const body = request.postData() && (path.startsWith("/api/operation-imports") || path === "/api/products") ? request.postDataJSON() : {};
    let data: unknown;
    if (path === "/api/settings") data = { currency: "RUB", categoryRequired: false, storeRequired: false, defaultCategoryId: null, defaultStoreId: null };
    else if (path === "/api/products" && request.method() === "POST") {
      const product = { ...ref.products[0], ...body, id: "product-5" };
      ref.products.push(product);
      data = product;
    } else if (path === "/api/products") data = { items: ref.products };
    else if (path === "/api/warehouses") data = { items: ref.warehouses };
    else if (path === "/api/suppliers") data = { items: ref.suppliers };
    else if (path === "/api/operation-imports/invoice/reprocess") data = body.createdEntity ? { updatedCandidateIds: [candidate.id] } : snapshot();
    else if (path === "/api/operation-imports/invoice/commit") {
      expect(candidate.status).toBe("approved");
      expect(candidate.validation_errors).toEqual([]);
      committed = structuredClone(candidate.normalized_operation);
      candidate.status = "committed";
      data = {};
    } else if (path === "/api/operation-imports/invoice" && request.method() === "PATCH") {
      if (body.operation) patch(body.operation);
      if (body.candidateUpdates) body.candidateUpdates.forEach((update: { operation: OperationImportDraft }) => patch(update.operation));
      if (body.approveCandidateId || body.approveAll) {
        expect(candidate.validation_errors).toEqual([]);
        candidate.status = "approved";
      }
      data = { candidate, candidates: [candidate], summary: summary(), loadPreview: loadPreview() };
    } else if (path === "/api/operation-imports/invoice") data = snapshot();
    else data = { items: [], page: { total: 0, offset: 0, limit: 10 } };
    await route.fulfill({ json: data });
  });
  await page.goto("/operations/import?id=invoice");
  await expect(page.getByTestId("import-item-row")).toHaveCount(6);
  return { candidate, writes, committed: () => committed };
}

test("reviews every invoice item, creates the sixth product, fills blank warehouses and approves all six", async ({ page }) => {
  const state = await mockInvoice(page);
  const rows = page.getByTestId("import-item-row");
  for (let index = 0; index < 6; index++) {
    await expect(rows.nth(index)).toContainText(`Item ${index + 1} of 6`);
    await expect(rows.nth(index)).toContainText(`Item ${index + 1} product: Product is required`);
    await expect(rows.nth(index).getByRole("combobox").first()).toBeVisible();
  }
  await expect(page.getByRole("button", { name: "Approve row", exact: true })).toBeDisabled();

  await rows.nth(5).getByRole("spinbutton", { name: "Quantity", exact: true }).fill("9");
  await expect.poll(() => state.writes.length).toBe(1);
  expect(state.writes[0].items?.map(item => item.quantity)).toEqual([5, 10, 20, 3, 15, 9]);
  await expect(rows.nth(5).getByRole("spinbutton", { name: "Quantity", exact: true })).toBeEnabled();
  await rows.nth(5).getByRole("spinbutton", { name: "Quantity", exact: true }).fill("8");
  await expect.poll(() => state.writes.length).toBe(2);
  await expect(rows.nth(5).getByRole("combobox").first()).toBeEnabled();

  for (let index = 0; index < 5; index++) {
    const productSelect = rows.nth(index).getByTestId("import-product-cell").getByRole("combobox");
    await productSelect.click();
    await page.getByRole("option", { name: `${sourceItems[index][0]} (${sourceItems[index][1]})`, exact: true }).click();
    await expect(productSelect).toBeEnabled();
  }
  await rows.nth(5).getByRole("combobox").first().click();
  await page.getByRole("option", { name: 'Create from "Розетка-удлинитель 5 гнёзд"', exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(rows.nth(5)).toContainText("EXT-5SOCK");
  await expect(rows.nth(0)).toContainText("LEN-IP3-15");
  expect(state.candidate.operation.items?.[0].productId).toBe("product-0");
  expect(state.candidate.operation.items?.[5].productId).toBe("product-5");

  const warehouseCell = rows.nth(0).getByTestId("import-warehouse-cell");
  await warehouseCell.getByRole("button", { name: "Apply value to rows" }).click();
  await page.getByRole("button", { name: "Fill blanks", exact: true }).click();
  await expect.poll(() => state.candidate.normalized_operation.items?.map(item => item.warehouseId)).toEqual(["main", "other", "main", "main", "main", "main"]);
  await expect(page.getByRole("button", { name: "Approve row", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "Approve row", exact: true }).click();
  await expect(page.getByTestId("operation-import-load-summary")).toContainText("344");
  await page.getByRole("button", { name: "Load approved rows", exact: true }).click();
  await expect.poll(() => state.committed()?.items?.length).toBe(6);
  expect(state.committed()?.items?.reduce((sum, item) => sum + item.quantity! * item.unitPrice!, 0)).toBe(344500);
  await expect(rows.nth(5).getByRole("spinbutton", { name: "Quantity", exact: true })).toBeDisabled();
});

for (const width of [1440, 390]) {
  test(`six-item review fits viewport at ${width}px in Russian`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await mockInvoice(page, "ru");
    await expect(page.getByText("Позиция 6 из 6", { exact: true })).toBeVisible();
    await expect(page.getByTestId("import-item-row").last()).toContainText("Товар в позиции 6");
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
    expect(overflow).toBeLessThanOrEqual(1);
    await page.screenshot({ path: `test-results/import-six-items-${width}.png`, fullPage: true });
  });
}

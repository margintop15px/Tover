import { test, expect, type Page, type Route } from "@playwright/test";
import { createHash } from "node:crypto";
import { en } from "../../src/i18n/en";
import { ru } from "../../src/i18n/ru";
import { buildSupplyTransferCandidates } from "../../src/lib/ozon/sync";

const appUrl = "http://127.0.0.1:3410";
const mockUrl = "http://127.0.0.1:3411";
const alpha = "20000000-0000-4000-8000-000000000001";
const settings = { currency: "USD", categoryRequired: false, storeRequired: false, defaultCategoryId: null, defaultStoreId: null };
const ozon = { connection: null, connected: false, counts: {}, recovery: null };
const reset = { canReset: true, role: "owner", confirmation: "RESET", total: 0, groups: {}, counts: {} };
const operation = { id: "row-1", operationId: "operation-1", type: "purchase", operationDate: "2026-09-18", productName: "Retained product", quantity: 1, unitPrice: 42, comment: "Retained operation" };
const [supplyCandidate] = buildSupplyTransferCandidates(
  { workspace_id: alpha, connection_id: "ozon", ozon_supply_order_id: "order" },
  [{ external_id: "item", supply_state: "COMPLETED", completed_at_ozon: "2026-09-18", quantity: "2", ozon_storage_warehouse_id: "ozon-warehouse", storage_warehouse_name: "Ozon warehouse", name: "Unmapped supply product" }],
);
const candidates = {
  page: { limit: 50, offset: 0, total: 1 },
  summary: { total: 1, needsMapping: 1, ready: 0, approved: 0, committing: 0, ignored: 0, committed: 0 },
  items: [{ ...supplyCandidate, id: "candidate-1" }],
};

function collectErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  return errors;
}

async function fail(route: Route, kind: string) {
  if (kind === "network") return route.abort("failed");
  if (kind === "http") return route.fulfill({ status: 503, json: { error: "Unavailable" } });
  if (kind === "shape") return route.fulfill({ json: {} });
  return route.fulfill({ status: 200, contentType: "text/html", body: "<h1>Gateway failure</h1>" });
}

test.beforeEach(async ({ context, page, request, baseURL }) => {
  // This test suite can only use the isolated local Auth/PostgREST fixture.
  expect(baseURL).toBe(appUrl);
  expect((await request.post(`${mockUrl}/__test`, { data: {} })).ok()).toBeTruthy();
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const user = { id: "10000000-0000-4000-8000-000000000001", email: "workspace@example.test", aud: "authenticated", role: "authenticated", app_metadata: {}, user_metadata: {}, created_at: "2026-01-01" };
  const exp = Math.floor(Date.now() / 1000) + 3600;
  const access_token = `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ sub: user.id, aud: "authenticated", exp })}.test-signature`;
  await context.addCookies([{ name: "sb-127-auth-token", value: `base64-${encode({ access_token, refresh_token: "mock-refresh", token_type: "bearer", expires_in: 3600, expires_at: exp, user })}`, url: appUrl }]);
  await page.route(/\/api\/integrations\/ozon(?:\?|$)/, (route) => route.fulfill({ json: ozon }));
  await page.route(/\/api\/settings\/reset-data(?:\?|$)/, (route) => route.fulfill({ json: reset }));
});

for (const kind of ["network", "http", "html", "shape"]) {
  test(`initial workspace settings ${kind} failure blocks content and recovers`, async ({ page }) => {
    const errors = collectErrors(page);
    let broken = true;
    let reads = 0;
    await page.route("**/api/settings", (route) => {
      reads++;
      return broken ? fail(route, kind) : route.fulfill({ json: settings });
    });
    await page.goto("/operations/marketplaces");
    const alert = page.getByRole("alert").filter({ hasText: en.settingsLoadFailed });
    await expect(alert).toContainText(en.settingsLoadFailed);
    await expect(page.getByRole("heading", { name: en.marketplacesTitle })).toHaveCount(0);
    const before = reads;
    broken = false;
    await alert.getByRole("button", { name: en.workspaceRetry }).click();
    await expect(page.getByRole("heading", { name: en.marketplacesTitle })).toBeVisible();
    expect(reads).toBe(before + 1);
    expect(errors).toEqual([]);
  });
}

for (const locale of ["en", "ru"] as const) {
  test(`settings refresh preserves the form and last settings (${locale}, mobile)`, async ({ page }) => {
    const t = locale === "ru" ? ru : en;
    const errors = collectErrors(page);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.addInitScript((value) => localStorage.setItem("tover-locale", value), locale);
    let broken = false;
    let writes = 0;
    await page.route("**/api/settings", (route) => {
      if (route.request().method() === "PATCH") {
        writes++;
        broken = true;
        return route.fulfill({ json: settings });
      }
      return broken ? fail(route, "network") : route.fulfill({ json: settings });
    });
    await page.goto("/settings");
    await page.getByRole("button", { name: t.save, exact: true }).click();
    const alert = page.getByRole("alert").filter({ hasText: t.settingsRefreshFailed });
    await expect(alert).toBeVisible();
    await expect(page.getByRole("tabpanel").getByRole("combobox")).toContainText("USD");
    await expect(page.getByRole("button", { name: t.save, exact: true })).toBeEnabled();
    expect(writes).toBe(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect((await alert.locator("p").boundingBox())!.width).toBeGreaterThan(200);
    await page.screenshot({ path: `test-results/network-settings-${locale}-mobile.png`, fullPage: true });
    await page.setViewportSize({ width: 1280, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/network-settings-${locale}-desktop.png`, fullPage: true });
    broken = false;
    await alert.getByRole("button", { name: t.workspaceRetry }).click();
    await expect(alert).toHaveCount(0);
    expect(writes).toBe(1);
    expect(errors).toEqual([]);
  });
}

for (const scenario of [
  { name: "operations list", path: "/operations", url: /\/api\/operations\?/, json: { items: [operation], page: { totalEstimate: 1 } }, kind: "http" },
  { name: "operations references", path: "/operations", url: /\/api\/warehouses(?:\?|$)/, json: { items: [] }, kind: "network" },
  { name: "new operation references", path: "/operations/new", url: /\/api\/warehouses(?:\?|$)/, json: { items: [] }, kind: "html" },
  { name: "settings references", path: "/settings?tab=products", url: /\/api\/categories\?/, json: { items: [] }, kind: "shape" },
  { name: "settings Ozon", path: "/settings?tab=integrations", url: /\/api\/integrations\/ozon(?:\?|$)/, json: ozon, kind: "network" },
  { name: "reset summary", path: "/settings", url: /\/api\/settings\/reset-data(?:\?|$)/, json: reset, kind: "html" },
  { name: "marketplace summary", path: "/operations/marketplaces", url: /\/api\/integrations\/ozon(?:\?|$)/, json: ozon, kind: "shape" },
]) {
  test(`${scenario.name} read failure has a manual recovery`, async ({ page }) => {
    const errors = collectErrors(page);
    let broken = true;
    await page.route(scenario.url, (route) => broken ? fail(route, scenario.kind) : route.fulfill({ json: scenario.json }));
    await page.goto(scenario.path);
    const alert = page.getByRole("alert").filter({ hasText: en.dataLoadFailed });
    await expect(alert).toBeVisible();
    if (scenario.name === "operations list") await expect(page.getByText(en.noData, { exact: true })).toHaveCount(0);
    if (scenario.name.includes("Ozon") || scenario.name === "marketplace summary") await expect(page.getByText(en.ozonNoConnection, { exact: true })).toHaveCount(0);
    broken = false;
    await alert.getByRole("button", { name: en.workspaceRetry }).click();
    await expect(alert).toHaveCount(0);
    if (scenario.name === "new operation references") await expect(page.getByRole("button", { name: en.save, exact: true })).toBeVisible();
    expect(errors).toEqual([]);
  });
}

test("operations keep previous rows after a failed refresh; details retry safely", async ({ page }) => {
  const errors = collectErrors(page);
  let broken = false;
  await page.route(/\/api\/operations\?/, (route) => broken ? fail(route, "html") : route.fulfill({ json: { items: [operation], page: { totalEstimate: 1 } } }));
  let detailBroken = true;
  await page.route("**/api/operations/operation-1", (route) => detailBroken ? fail(route, "network") : route.fulfill({ json: { ...operation, id: operation.operationId, items: [] } }));
  await page.goto("/operations");
  await expect(page.getByText("Retained product", { exact: true })).toBeVisible();
  broken = true;
  await page.getByRole("button", { name: `Sort ${en.operationDate}`, exact: true }).click();
  const alert = page.getByRole("alert").filter({ hasText: en.dataLoadFailed });
  await expect(alert).toBeVisible();
  await expect(page.getByText("Retained product", { exact: true })).toBeVisible();
  broken = false;
  await alert.getByRole("button").click();
  await expect(alert).toHaveCount(0);
  await page.getByRole("button", { name: en.viewOperation, exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(dialog.getByText(en.noData, { exact: true })).toHaveCount(0);
  detailBroken = false;
  await dialog.getByRole("button", { name: en.workspaceRetry }).click();
  await expect(dialog.getByRole("alert")).toHaveCount(0);
  await expect(dialog.getByText("Retained operation", { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

for (const action of ["general", "products", "connect", "validate", "disconnect", "reset"] as const) {
  test(`${action} write failure preserves state and never retries`, async ({ page }) => {
    const errors = collectErrors(page);
    const connected = { ...ozon, connected: true, connection: { id: "connection", name: "Ozon", status: "connected", clientIdHint: "client", apiKeyHint: "***", lastValidatedAt: null, lastSyncAt: null, lastSyncStatus: null, lastSyncError: null } };
    let writes = 0;
    await page.route(/\/api\/integrations\/ozon(?:\/validate)?(?:\?|$)/, (route) => {
      if (route.request().method() === "GET") return route.fulfill({ json: connected });
      writes++;
      return fail(route, "network");
    });
    await page.route("**/api/settings", (route) => {
      if (route.request().method() === "GET") return route.fulfill({ json: settings });
      writes++;
      return fail(route, action === "products" ? "html" : "network");
    });
    await page.route(/\/api\/settings\/reset-data(?:\?|$)/, (route) => {
      if (route.request().method() === "GET") return route.fulfill({ json: reset });
      writes++;
      return fail(route, "network");
    });
    await page.goto(`/settings?tab=${["connect", "validate", "disconnect"].includes(action) ? "integrations" : action === "products" ? "products" : "general"}`);
    let button;
    if (action === "connect") {
      await page.getByRole("tabpanel").locator('input:not([type="password"])').fill("draft-client");
      await page.locator('input[type="password"]').fill("draft-secret");
      button = page.getByRole("button", { name: en.ozonUpdateCredentials, exact: true });
    } else if (action === "validate") button = page.getByRole("button", { name: en.ozonValidate, exact: true });
    else if (action === "disconnect") button = page.getByRole("button", { name: en.ozonDisconnect, exact: true });
    else if (action === "reset") {
      await page.getByRole("button", { name: en.removeAllAccountData, exact: true }).click();
      await page.getByLabel(en.resetAccountDataTypeReset).fill("RESET");
      button = page.getByRole("dialog").getByRole("button", { name: en.removeAllAccountData, exact: true });
    } else button = page.getByRole("button", { name: en.save, exact: true });
    await button.click();
    await expect(page.getByRole("alert").filter({ hasText: en.actionUnconfirmed })).toBeVisible();
    await expect(button).toBeEnabled();
    await expect(page.getByText(en.settingsSaved, { exact: true })).toHaveCount(0);
    await expect(page.getByText(en.ozonConnectedMessage, { exact: true })).toHaveCount(0);
    if (action === "connect") {
      await expect(page.locator('input[type="password"]')).toHaveValue("draft-secret");
      await expect(page.getByRole("tabpanel").locator('input:not([type="password"])')).toHaveValue("draft-client");
    }
    if (action === "reset") await expect(page.getByLabel(en.resetAccountDataTypeReset)).toHaveValue("RESET");
    expect(writes).toBe(1);
    expect(errors).toEqual([]);
  });
}

test("malformed uploads return 400 before import storage is accessed", async ({ context, request }) => {
  const unauthorized = await request.post(`${appUrl}/api/operation-imports`, { data: {} });
  expect(unauthorized.status()).toBe(401);
  const multipart = "--demo\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.csv\"\r\nContent-Type: text/csv\r\n\r\na,b";
  for (const [contentType, data] of [
    ["application/json", "{}"],
    ["multipart/form-data", "file=x"],
    ["multipart/form-data; boundary=demo", multipart],
    ["multipart/form-data; boundary=demo", "--demo--\r\n"],
    ["multipart/form-data; boundary=demo", "--demo\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nnot-a-file\r\n--demo--\r\n"],
  ]) {
    const response = await context.request.post("/api/operation-imports", { headers: { "content-type": contentType, "x-workspace-id": alpha }, data });
    expect(response.status(), await response.text()).toBe(400);
    expect(await response.json()).toEqual({ error: expect.any(String) });
  }
  const state = await (await request.get(`${mockUrl}/__test`)).json();
  expect(state.log.filter((item: { path: string }) => item.path.includes("operation_import"))).toEqual([]);
});

test("reset preview can recover inside the open dialog without losing confirmation", async ({ page }) => {
  const errors = collectErrors(page);
  let broken = false;
  await page.route(/\/api\/settings\/reset-data(?:\?|$)/, (route) => {
    expect(route.request().method()).toBe("GET");
    return broken ? fail(route, "network") : route.fulfill({ json: reset });
  });
  await page.goto("/settings");
  const open = page.getByRole("button", { name: en.removeAllAccountData, exact: true });
  await expect(open).toBeEnabled();
  broken = true;
  await open.click();
  const dialog = page.getByRole("dialog");
  await page.getByLabel(en.resetAccountDataTypeReset).fill("RESET");
  await expect(dialog.getByRole("alert")).toContainText(en.dataLoadFailed);
  await expect(dialog.getByRole("button", { name: en.removeAllAccountData, exact: true })).toBeDisabled();
  broken = false;
  await dialog.getByRole("button", { name: en.workspaceRetry }).click();
  await expect(dialog.getByRole("alert")).toHaveCount(0);
  await expect(page.getByLabel(en.resetAccountDataTypeReset)).toHaveValue("RESET");
  await expect(dialog.getByRole("button", { name: en.removeAllAccountData, exact: true })).toBeEnabled();
  expect(errors).toEqual([]);
});

test("upload storage failures remain server errors", async ({ context, request }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "import_storage_failure" } });
  const response = await context.request.post("/api/operation-imports", {
    headers: { "x-workspace-id": alpha },
    multipart: { file: { name: "example.csv", mimeType: "text/csv", buffer: Buffer.from("Date,Type\n2026-09-18,Purchase\n") } },
  });
  expect(response.status()).toBe(500);
  expect(await response.json()).toMatchObject({ error: "Failed to create import job" });
});

test("a real browser multipart upload reaches the route and import storage", async ({ page, request }) => {
  const errors = collectErrors(page);
  const csv = Buffer.from("Date,Type,Product,Quantity,Price\n2026-09-18,Purchase,Example product,2,10\n");
  await page.goto("/operations/import");
  await page.locator('input[type="file"]').setInputFiles({ name: "network-regression.csv", mimeType: "text/csv", buffer: csv });
  const uploaded = page.waitForResponse((response) => response.url().endsWith("/api/operation-imports") && response.request().method() === "POST");
  await page.getByRole("button", { name: en.uploadAndImport, exact: true }).click();
  const response = await uploaded;
  expect(response.status(), await response.text()).toBe(200);
  expect(response.request().headers()["content-type"]).toContain("multipart/form-data; boundary=");
  const data = await response.json();
  expect(data.import).toMatchObject({ file_name: "network-regression.csv", file_size: csv.length, file_hash: createHash("sha256").update(csv).digest("hex"), status: "needs_review" });
  await expect(page).toHaveURL(new RegExp(`/operations/import\\?id=${data.import.id}`));
  const state = await (await request.get(`${mockUrl}/__test`)).json();
  expect(state.log.filter((item: { path: string; method: string }) => item.path === "/rest/v1/operation_imports" && item.method === "POST")).toHaveLength(1);
  expect(errors).toEqual([]);
});

for (const kind of ["network", "http", "html", "shape"]) {
  test(`Ozon candidate ${kind} failure is recoverable and never shown as an empty result`, async ({ page }) => {
    const errors = collectErrors(page);
    let broken = true;
    let reads = 0;
    await page.route("**/api/integrations/ozon/candidates?*", (route) => {
      reads++;
      return broken ? fail(route, kind) : route.fulfill({ json: candidates });
    });
    await page.goto("/operations/marketplace/ozon");
    const alert = page.getByRole("alert").filter({ hasText: en.dataLoadFailed });
    await expect(alert).toBeVisible();
    await expect(page.getByText(en.ozonNoCandidates, { exact: true })).toHaveCount(0);
    broken = false;
    const before = reads;
    await alert.getByRole("button", { name: en.workspaceRetry }).click();
    const row = page.getByRole("row").filter({ hasText: "Unmapped supply product" });
    await expect(row).toContainText(en.ozonCandidateNeedsMapping);
    expect(reads).toBe(before + 1);
    await row.getByRole("button", { name: en.review, exact: true }).click();
    await expect(page.getByRole("dialog")).toContainText("Ozon warehouse");
    expect(errors).toEqual([]);
  });
}

test("Ozon candidate reference failure recovers without discarding loaded candidates", async ({ page }) => {
  const errors = collectErrors(page);
  let broken = true;
  await page.route("**/api/integrations/ozon/candidates?*", (route) => route.fulfill({ json: candidates }));
  await page.route("**/api/products?*", (route) => broken ? fail(route, "network") : route.fulfill({ json: { items: [] } }));
  await page.goto("/operations/marketplace/ozon");
  await expect(page.getByRole("row").filter({ hasText: "Unmapped supply product" })).toBeVisible();
  const alert = page.getByRole("alert").filter({ hasText: en.dataLoadFailed });
  await expect(alert).toBeVisible();
  broken = false;
  await alert.getByRole("button", { name: en.workspaceRetry }).click();
  await expect(alert).toHaveCount(0);
  await expect(page.getByRole("row").filter({ hasText: "Unmapped supply product" })).toBeVisible();
  expect(errors).toEqual([]);
});

test("unmapped supplies are listed by the real API but cannot be approved or committed", async ({ page, context, request }) => {
  const foreign = { ...candidates.items[0], id: "foreign-candidate", workspace_id: "20000000-0000-4000-8000-000000000002" };
  await request.post(`${mockUrl}/__test`, { data: { marketplaceCandidates: [...candidates.items, foreign] } });
  const headers = { "x-workspace-id": alpha };
  const list = await context.request.get("/api/integrations/ozon/candidates?status=all", { headers });
  expect(list.status()).toBe(200);
  const listed = await list.json();
  expect(listed.items.map((item: { id: string }) => item.id)).toEqual(["candidate-1"]);
  expect(listed.summary).toMatchObject({ needsMapping: 1, ready: 0 });

  await page.goto("/operations/marketplace/ozon");
  const row = page.getByRole("row").filter({ hasText: "Unmapped supply product" });
  await expect(row).toContainText(en.ozonCandidateNeedsMapping);
  await row.getByRole("button", { name: en.review, exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("Ozon warehouse");

  const approved = await context.request.post("/api/integrations/ozon/candidates/candidate-1/approve", { headers });
  expect(approved.status()).toBe(400);
  expect(await approved.json()).toMatchObject({
    error: "Resolve validation errors before approval",
    validationErrors: expect.arrayContaining([expect.objectContaining({ field: "items[1].warehouseId" })]),
  });
  const bulk = await context.request.post("/api/integrations/ozon/candidates/approve-ready", { headers });
  expect(bulk.status()).toBe(200);
  expect(await bulk.json()).toEqual({ approved: 0, blocked: 1 });
  const committed = await context.request.post("/api/integrations/ozon/candidates/commit", { headers, data: { candidateIds: ["candidate-1"] } });
  expect(committed.status()).toBe(200);
  expect(await committed.json()).toMatchObject({ committedCount: 0, failedCount: 1, failed: [{ candidateId: "candidate-1", error: "Candidate must be approved before commit" }] });
  const initialState = await (await request.get(`${mockUrl}/__test`)).json();

  // Even a stale approved status cannot bypass validation at the commit endpoint.
  await request.post(`${mockUrl}/__test`, { data: { marketplaceCandidates: [{ ...candidates.items[0], status: "approved" }] } });
  const stale = await context.request.post("/api/integrations/ozon/candidates/commit", { headers, data: { candidateIds: ["candidate-1"] } });
  expect(await stale.json()).toMatchObject({ committedCount: 0, failedCount: 1, failed: [{ candidateId: "candidate-1", error: "Candidate has validation errors" }] });
  const state = await (await request.get(`${mockUrl}/__test`)).json();
  expect([...initialState.log, ...state.log].filter((entry: { path: string }) => entry.path.includes("commit_ozon_operation_candidate") || entry.path === "/rest/v1/operations")).toEqual([]);
});

test("a late Ozon candidate response cannot overwrite a newer filter result", async ({ page }) => {
  let releaseOlder!: () => void;
  const olderResponse = new Promise<void>((resolve) => { releaseOlder = resolve; });
  let olderStarted!: () => void;
  const started = new Promise<void>((resolve) => { olderStarted = resolve; });
  await page.route("**/api/integrations/ozon/candidates?*", async (route) => {
    const from = new URL(route.request().url()).searchParams.get("from");
    if (from === "2026-09-17") {
      olderStarted();
      await olderResponse;
      return route.fulfill({ json: candidates });
    }
    return route.fulfill({ json: from ? { ...candidates, items: [], page: { ...candidates.page, total: 0 } } : candidates });
  });
  await page.goto("/operations/marketplace/ozon");
  const row = page.getByRole("row").filter({ hasText: "Unmapped supply product" });
  await expect(row).toBeVisible();
  const from = page.locator('input[type="date"]').first();
  await from.fill("2026-09-17");
  await started;
  await from.fill("2026-09-18");
  await expect(page.getByText(en.ozonNoCandidates, { exact: true })).toBeVisible();
  const olderFinished = page.waitForResponse((response) => response.url().includes("from=2026-09-17"));
  releaseOlder();
  await (await olderFinished).finished();
  // Let the response handler and React render finish before asserting absence.
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(row).toHaveCount(0);
  await expect(page.getByText(en.ozonNoCandidates, { exact: true })).toBeVisible();
});

for (const locale of ["en", "ru"] as const) {
  test(`Ozon candidate refresh retains rows and a true empty result explains eligibility (${locale})`, async ({ page }) => {
    const t = locale === "ru" ? ru : en;
    await page.setViewportSize({ width: locale === "ru" ? 320 : 1280, height: 900 });
    await page.addInitScript((value) => localStorage.setItem("tover-locale", value), locale);
    let response: "rows" | "error" | "empty" = "rows";
    await page.route("**/api/integrations/ozon/candidates?*", (route) => response === "error" ? fail(route, "http") : route.fulfill({
      json: response === "rows" ? candidates : { ...candidates, items: [], page: { ...candidates.page, total: 0 }, summary: { ...candidates.summary, total: 0, needsMapping: 0 } },
    }));
    await page.goto("/operations/marketplace/ozon");
    const row = page.getByRole("row").filter({ hasText: "Unmapped supply product" });
    await expect(row).toBeVisible();
    response = "error";
    await page.locator('input[type="date"]').first().fill("2026-09-18");
    const alert = page.getByRole("alert").filter({ hasText: t.dataLoadFailed });
    await expect(alert).toBeVisible();
    await expect(row).toBeVisible();
    await expect(page.getByText(t.ozonNoCandidates, { exact: true })).toHaveCount(0);
    await page.screenshot({ path: `test-results/ozon-candidates-${locale}-error.png`, fullPage: true });
    response = "empty";
    await alert.getByRole("button", { name: t.workspaceRetry }).click();
    await expect(row).toHaveCount(0);
    await expect(page.getByText(t.ozonNoCandidatesHint)).toBeVisible();
  });
}

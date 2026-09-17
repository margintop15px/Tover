import { test, expect, type BrowserContext, type Page } from "@playwright/test";
import { en } from "../../src/i18n/en";
import { ru } from "../../src/i18n/ru";

const appUrl = "http://127.0.0.1:3410";
const mockUrl = "http://127.0.0.1:3411";
const userId = "10000000-0000-4000-8000-000000000001";
const alpha = "20000000-0000-4000-8000-000000000001";
const beta = "20000000-0000-4000-8000-000000000002";
const foreign = "20000000-0000-4000-8000-000000000003";
const cookieName = `tover-workspace-${userId}`;

async function signIn(context: BrowserContext) {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const user = { id: userId, email: "workspace@example.test", aud: "authenticated", role: "authenticated", app_metadata: {}, user_metadata: {}, created_at: "2026-01-01" };
  const exp = Math.floor(Date.now() / 1000) + 3600;
  const access_token = `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ sub: userId, aud: "authenticated", exp })}.test-signature`;
  await context.addCookies([{ name: "sb-127-auth-token", value: `base64-${encode({ access_token, refresh_token: "mock-refresh", token_type: "bearer", expires_in: 3600, expires_at: exp, user })}`, url: appUrl }]);
}

async function selectWorkspace(page: Page, workspaceId: string) {
  await page.getByRole("combobox", { name: "Workspace", exact: true }).selectOption(workspaceId);
  await expect(page).toHaveURL(`${appUrl}/operations`);
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(workspaceId);
}

test.beforeEach(async ({ context, request, baseURL }) => {
  // This suite must never use the normal authenticated project or real Supabase.
  expect(baseURL).toBe(appUrl);
  expect((await request.post(`${mockUrl}/__test`, { data: {} })).ok()).toBeTruthy();
  await signIn(context);
});

for (const scenario of [
  { name: "network failure", locale: "en", t: en, failure: "network", message: en.operationSaveUnconfirmed },
  { name: "network failure in Russian", locale: "ru", t: ru, failure: "network", message: ru.operationSaveUnconfirmed },
  { name: "non-JSON server failure", locale: "en", t: en, failure: "html", message: en.operationSaveUnconfirmed },
  { name: "HTTP 500", locale: "en", t: en, failure: "server", message: "Operation could not be created" },
  { name: "validation failure", locale: "en", t: en, failure: "validation", message: "items: At least one item is required" },
]) {
  test(`operation submission handles ${scenario.name} without losing input or retrying`, async ({ page }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.addInitScript((locale) => localStorage.setItem("tover-locale", locale), scenario.locale);
    let submissions = 0;
    await page.route("**/api/operations", async (route) => {
      if (route.request().method() !== "POST") return route.continue();
      submissions++;
      expect(route.request().postDataJSON()).toMatchObject({ type: "inventory_adjustment", comment: "Keep this draft" });
      if (scenario.failure === "network") return route.abort("failed");
      if (scenario.failure === "html") return route.fulfill({ status: 502, contentType: "text/html", body: "<h1>Bad Gateway</h1>" });
      if (scenario.failure === "validation") return route.fulfill({ status: 400, json: { errors: [{ field: "items", message: "At least one item is required" }] } });
      return route.fulfill({ status: 500, json: { error: scenario.message } });
    });

    await page.goto("/operations/new");
    await page.getByRole("tab", { name: scenario.t.operationGroupAdjustments, exact: true }).click();
    await page.locator("textarea").fill("Keep this draft");
    const save = page.getByRole("button", { name: scenario.t.save, exact: true });
    await save.click();

    await expect(page.getByRole("alert").filter({ hasText: scenario.message })).toHaveText(scenario.message);
    await expect(save).toBeEnabled();
    await expect(page.locator("textarea")).toHaveValue("Keep this draft");
    await expect(page).toHaveURL(`${appUrl}/operations/new`);
    expect(submissions).toBe(1);
    expect(pageErrors).toEqual([]);
  });
}

test("operation submission navigates to the list after success", async ({ page }) => {
  let submissions = 0;
  await page.route("**/api/operations", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    submissions++;
    return route.fulfill({ status: 201, json: { id: "test-operation" } });
  });
  await page.goto("/operations/new");
  await page.getByRole("button", { name: en.save, exact: true }).click();
  await expect(page).toHaveURL(`${appUrl}/operations`);
  expect(submissions).toBe(1);
});

test("switches all tabs, clears deep links, persists selection, and refreshes settings", async ({ page, context }) => {
  await page.goto("/operations?productId=old-product&offset=50");
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(alpha);
  const other = await context.newPage();
  await other.goto("/categories");
  await expect(other.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(alpha);
  await page.bringToFront();
  await selectWorkspace(page, beta);
  await expect(other).toHaveURL(`${appUrl}/operations`);
  await expect(other.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(beta);
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(beta);
  expect(await (await context.request.get("/api/settings")).json()).toMatchObject({ currency: "USD" });
  const cookie = (await context.cookies()).find((item) => item.name === cookieName)!;
  expect(cookie).toMatchObject({ value: beta, httpOnly: true, sameSite: "Lax", path: "/", secure: false });
  expect(cookie.expires - Date.now() / 1000).toBeGreaterThan(29 * 86400);
  await page.screenshot({ path: "test-results/workspace-desktop.png", fullPage: true });
});

test("validates real API routing, roles, overrides, unknown membership and stale writes", async ({ context, request }) => {
  const api = context.request;
  expect((await (await api.get("/api/auth/me")).json()).activeWorkspaceId).toBe(alpha);
  expect((await api.post("/api/categories", { data: { name: "Only Alpha" } })).status()).toBe(201);
  expect((await api.post("/api/auth/invite", { data: { email: "invitee@example.test" } })).status()).toBe(200);
  expect((await api.post("/api/imports", { multipart: {
    import_type: "orders_csv", file: { name: "empty.csv", mimeType: "text/csv", buffer: Buffer.from("external_order_id,ordered_at,currency,status\n") },
  } })).status()).toBe(200);
  expect((await api.post("/api/auth/workspace", { data: { workspaceId: beta } })).status()).toBe(200);
  expect((await (await api.get("/api/categories")).json()).items).toEqual([]);
  expect((await (await api.get(`/api/categories?workspaceId=${alpha}`)).json()).items).toHaveLength(1);
  expect((await api.post("/api/categories", { data: { name: "Denied" } })).status()).toBe(403);
  for (const workspaceId of [foreign, "invalid", null]) {
    expect((await api.post("/api/auth/workspace", { data: { workspaceId } })).status()).toBe(workspaceId === foreign ? 403 : 400);
  }
  const stale = await api.post("/api/categories", { headers: { "x-tover-workspace-id": alpha }, data: { name: "Stale" } });
  expect(stale.status()).toBe(409);
  expect(await stale.json()).toMatchObject({ code: "WORKSPACE_CHANGED" });
  for (const path of ["/api/operations", "/api/operation-imports", "/api/integrations/ozon"]) {
    expect((await api.post(path, { headers: { "x-tover-workspace-id": alpha }, data: {} })).status()).toBe(409);
  }
  expect((await api.patch("/api/settings", { headers: { "x-tover-workspace-id": alpha }, data: { currency: "GBP" } })).status()).toBe(409);
  expect((await api.post("/api/auth/invite", { headers: { "x-tover-workspace-id": alpha }, data: { email: "invitee@example.test" } })).status()).toBe(409);
  for (const path of ["/api/operations", "/api/reports/inventory-balances", "/api/operation-imports", "/api/integrations/ozon"]) {
    expect((await api.get(path)).status(), path).toBe(200);
  }
  const { log } = await (await request.get(`${mockUrl}/__test`)).json();
  expect(log.filter((entry: { path: string; method: string }) => entry.path === "/rest/v1/categories" && entry.method === "POST")).toHaveLength(1);
  expect(log.find((entry: { path: string; method: string }) => entry.path === "/rest/v1/organization_invites" && entry.method === "POST").body.organization_id).toBe(alpha);
  expect(log.find((entry: { path: string; method: string }) => entry.path === "/rest/v1/imports" && entry.method === "POST").body.workspace_id).toBe(alpha);
  for (const path of ["operations", "operation_imports", "marketplace_connections"]) {
    expect(log.some((entry: { path: string; workspace: string }) => entry.path === `/rest/v1/${path}` && entry.workspace === beta), path).toBeTruthy();
  }
  expect(log.some((entry: { path: string; body: { p_workspace_id?: string } }) => entry.path.includes("report_inventory_balances") && entry.body.p_workspace_id === beta)).toBeTruthy();
});

test("a stale browser form gets one 409 and reloads without retrying the write", async ({ page, context, request }) => {
  await page.goto("/categories");
  await page.getByRole("button", { name: "New Category", exact: true }).click();
  await page.getByRole("dialog").getByLabel("Category name", { exact: true }).fill("Stale form");
  // A cookie change without a storage event models a suspended tab missing the notification.
  expect((await context.request.post("/api/auth/workspace", { data: { workspaceId: beta } })).status()).toBe(200);
  const denied = page.waitForResponse((response) => response.url().endsWith("/api/categories") && response.request().method() === "POST");
  await page.getByRole("dialog").getByRole("button", { name: "Save", exact: true }).click();
  expect((await denied).status()).toBe(409);
  await expect(page).toHaveURL(`${appUrl}/operations`);
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(beta);
  const { log } = await (await request.get(`${mockUrl}/__test`)).json();
  expect(log.filter((entry: { path: string; method: string }) => entry.path === "/rest/v1/categories" && entry.method === "POST")).toEqual([]);
});

test("mobile selector fits long names, supports keyboard use and updates invitation permissions", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/settings?tab=team");
  await page.getByRole("button", { name: "Navigation", exact: true }).click();
  const selector = page.getByRole("dialog").getByRole("combobox", { name: "Workspace", exact: true });
  await selector.focus();
  // Native Chromium/macOS selects support type-ahead; synthetic arrow keys
  // do not operate the OS popup in a headless browser.
  await selector.press("b");
  await page.keyboard.press("Tab");
  await expect(page).toHaveURL(`${appUrl}/operations`);
  await page.getByRole("button", { name: "Navigation", exact: true }).click();
  await expect(page.getByRole("dialog").getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(beta);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: "test-results/workspace-mobile.png", fullPage: true });
  await page.goto("/settings?tab=team");
  await expect(page.getByRole("button", { name: "Send invite", exact: true })).toBeDisabled();
  await expect(page.locator("#invite-workspace-id")).toHaveCount(0);
});

test("handles switch/load failures, revoked selection, one membership and no membership", async ({ page, request, context }) => {
  await page.goto("/categories");
  await page.route("**/api/auth/workspace", (route) => route.fulfill({ status: 503, json: { error: "Unavailable" } }));
  await page.getByRole("combobox", { name: "Workspace", exact: true }).selectOption(beta);
  await expect(page.getByRole("alert").filter({ hasText: "Could not switch workspace" })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(alpha);
  await page.unroute("**/api/auth/workspace");
  await context.addCookies([{ name: cookieName, value: foreign, url: appUrl, httpOnly: true }]);
  expect((await context.request.post("/api/categories", { data: { name: "Invalid saved workspace" } })).status()).toBe(403);
  await request.post(`${mockUrl}/__test`, { data: { mode: "single" } });
  await page.reload();
  await expect(page.getByText("Alpha workspace", { exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveCount(0);
  await request.post(`${mockUrl}/__test`, { data: { mode: "failure" } });
  await page.reload();
  await expect(page.getByRole("alert").filter({ hasText: "Could not load your workspaces." })).toBeVisible();
  await request.post(`${mockUrl}/__test`, { data: { mode: "none" } });
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "do not have access" })).toBeVisible();
});

test("focus catches missed tab notifications; unnamed workspaces and Russian labels work", async ({ page, context, request, browser }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "unnamed" } });
  await page.goto("/categories");
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(alpha);
  expect((await context.request.post("/api/auth/workspace", { data: { workspaceId: beta } })).status()).toBe(200);
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect(page).toHaveURL(`${appUrl}/operations`);
  await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(beta);
  await expect(page.getByRole("option", { name: beta, exact: true })).toHaveCount(1);
  await page.getByRole("button", { name: "RU", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Рабочее пространство", exact: true })).toHaveValue(beta);

  const anonymous = await browser.newContext({ baseURL: appUrl });
  try {
    expect((await anonymous.request.post("/api/auth/workspace", { data: { workspaceId: alpha } })).status()).toBe(401);
    expect((await anonymous.cookies()).some((cookie) => cookie.name.startsWith("tover-workspace-"))).toBeFalsy();
  } finally {
    await anonymous.close();
  }
});

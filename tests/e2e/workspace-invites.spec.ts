import { test, expect, type BrowserContext } from "@playwright/test";
import { en } from "../../src/i18n/en";
import { ru } from "../../src/i18n/ru";

const appUrl = "http://127.0.0.1:3410";
const mockUrl = "http://127.0.0.1:3411";
const alpha = "20000000-0000-4000-8000-000000000001";
const beta = "20000000-0000-4000-8000-000000000002";
const foreign = "20000000-0000-4000-8000-000000000003";
const user = { id: "10000000-0000-4000-8000-000000000001", email: "workspace@example.test", aud: "authenticated", role: "authenticated", app_metadata: {}, user_metadata: {}, created_at: "2026-01-01" };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");

function session() {
  const exp = Math.floor(Date.now() / 1000) + 3600;
  return {
    access_token: `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ sub: user.id, aud: "authenticated", exp })}.test-signature`,
    refresh_token: "mock-refresh", token_type: "bearer", expires_in: 3600, expires_at: exp, user,
  };
}

async function signIn(context: BrowserContext) {
  await context.addCookies([{ name: "sb-127-auth-token", value: `base64-${encode(session())}`, url: appUrl }]);
}

interface LogEntry {
  path: string;
  method: string;
  body: Record<string, unknown>;
  redirectTo?: string;
}

test.beforeEach(async ({ request, context, baseURL }) => {
  expect(baseURL).toBe(appUrl);
  expect((await request.post(`${mockUrl}/__test`, { data: {} })).ok()).toBeTruthy();
  await signIn(context);
});

const legacyId = "10000000-0000-4000-8000-000000000002";
const pendingId = "30000000-0000-4000-8000-000000000001";
const expiredId = "30000000-0000-4000-8000-000000000002";

test("team recovery targets the stored member address and preserves the manager session", async ({ page, context, request }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "team" } });
  await page.goto("/settings?tab=team");
  await expect(page.getByText("Legacy recipient", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Invitations (2)" })).toBeVisible();
  const before = (await context.cookies()).find((cookie) => cookie.name === "sb-127-auth-token")?.value;
  await page.getByRole("button", { name: en.teamSendRecovery }).click();
  await expect(page.getByRole("status").filter({ hasText: en.recoveryEmailSent })).toBeVisible();
  await expect(page.getByRole("button", { name: en.teamSendRecovery })).toBeDisabled();
  const { log } = await (await request.get(`${mockUrl}/__test`)).json();
  expect(log.filter((entry: LogEntry) => entry.path === "/auth/v1/recover")).toHaveLength(1);
  const recovery = log.find((entry: LogEntry) => entry.path === "/auth/v1/recover");
  expect(recovery.body).toMatchObject({ email: "legacy@example.test" });
  expect(recovery.body.code_challenge).toBeFalsy();
  expect(recovery.redirectTo).toBe(`${appUrl}/auth/callback?next=/reset-password`);
  expect((await context.cookies()).find((cookie) => cookie.name === "sb-127-auth-token")?.value).toBe(before);
  expect((await context.request.post(`/api/auth/members/${legacyId}/recovery`, { data: { email: "attacker@example.test" } })).status()).toBe(429);
});

test("resend renews expired invitations, cancel withdraws the group, and conflicts guide recovery", async ({ page, context, request }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "team" } });
  await page.goto("/settings?tab=team");
  await page.getByLabel("User email", { exact: true }).fill("legacy@example.test");
  await page.getByRole("button", { name: "Send invite", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: en.teamMemberExists })).toBeVisible();
  await expect(page.getByRole("link", { name: en.teamMembers, exact: true })).toHaveAttribute("href", "#team-members");
  await page.getByLabel("User email", { exact: true }).fill("pending@example.test");
  await page.getByRole("button", { name: "Send invite", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: en.teamInviteExists })).toBeVisible();
  const expired = page.locator("article").filter({ hasText: "expired@example.test" });
  await expired.getByRole("button", { name: en.teamResend, exact: true }).click();
  await expect(expired.getByRole("button", { name: en.teamResend, exact: true })).toBeDisabled();
  await expect(expired.getByText("Pending", { exact: true })).toBeVisible();
  await expired.getByRole("button", { name: en.teamCancelInvitation }).click();
  await expect(expired).toHaveCount(0);
  const state = await (await request.get(`${mockUrl}/__test`)).json();
  expect(state.invites.filter((invite: { email: string }) => invite.email === "expired@example.test").every((invite: { status: string; role: string }) => invite.status === "revoked" && invite.role === "admin")).toBe(true);
  expect((await context.request.post(`/api/auth/invites/${expiredId}/resend`)).status()).toBe(409);
});

for (const mode of ["team_failure", "team_timeout", "cancel_during_send"]) {
  test(`${mode} does not lose old invitations or restore canceled access`, async ({ context, request }) => {
    await request.post(`${mockUrl}/__test`, { data: { mode } });
    const response = await context.request.post(`/api/auth/invites/${pendingId}/resend`);
    expect(response.status()).toBe(mode === "cancel_during_send" ? 409 : 502);
    expect((await response.json()).code).toBe(mode === "team_timeout" ? "DELIVERY_UNCONFIRMED" : mode === "team_failure" ? "DELIVERY_FAILED" : "INVITE_CHANGED");
    const state = await (await request.get(`${mockUrl}/__test`)).json();
    const sameEmail = state.invites.filter((invite: { email: string }) => invite.email === "pending@example.test");
    expect(sameEmail).toHaveLength(2);
    expect(sameEmail.map((invite: { status: string }) => invite.status)).toEqual(
      mode === "team_failure" ? ["pending", "revoked"] : mode === "team_timeout" ? ["pending", "pending"] : ["revoked", "revoked"],
    );
    expect(state.log.filter((entry: LogEntry) => entry.path === "/auth/v1/invite")).toHaveLength(1);
  });
}

test("new endpoints reject foreign records, members, and stale workspace snapshots", async ({ context, request }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "team" } });
  const paths = [`/api/auth/invites/${pendingId}/resend`, `/api/auth/invites/${pendingId}/cancel`, `/api/auth/members/${legacyId}/recovery`];
  expect((await context.request.post(`/api/auth/members/${foreign}/recovery`)).status()).toBe(404);
  expect((await context.request.post(`/api/auth/invites/${foreign}/resend`)).status()).toBe(404);
  await context.request.post("/api/auth/workspace", { data: { workspaceId: beta } });
  expect((await context.request.get("/api/auth/team")).status()).toBe(403);
  for (const path of paths) {
    expect((await context.request.post(path)).status()).toBe(403);
    expect((await context.request.post(path, { headers: { "x-tover-workspace-id": alpha } })).status()).toBe(409);
  }
  const state = await (await request.get(`${mockUrl}/__test`)).json();
  expect(state.log.some((entry: LogEntry) => ["/auth/v1/invite", "/auth/v1/recover"].includes(entry.path))).toBe(false);
});

test("simultaneous resends reserve only one email and reconciliation failures stay retryable", async ({ context, request, page }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "team" } });
  const responses = await Promise.all([1, 2].map(() => context.request.post(`/api/auth/invites/${pendingId}/resend`)));
  expect(responses.map((response) => response.status()).sort()).toEqual([200, 429]);
  await request.post(`${mockUrl}/__test`, { data: { mode: "reconcile_failure" } });
  await page.goto("/settings?tab=team");
  await expect(page.getByRole("alert").filter({ hasText: en.workspaceLoadFailed })).toHaveText(en.workspaceLoadFailed);
  await request.post(`${mockUrl}/__test`, { data: { mode: "team" } });
  await page.getByRole("button", { name: en.workspaceRetry }).click();
  await expect(page.getByText("Legacy recipient", { exact: true })).toBeVisible();
});

for (const locale of ["en", "ru"] as const) {
  test(`team layout and actions fit mobile and desktop in ${locale}`, async ({ page, request }) => {
    await request.post(`${mockUrl}/__test`, { data: { mode: "team" } });
    await page.addInitScript((value) => localStorage.setItem("tover-locale", value), locale);
    const t = locale === "en" ? en : ru;
    await page.goto("/settings?tab=team");
    for (const width of [320, 375, 768, 1200]) {
      await page.setViewportSize({ width, height: 900 });
      await expect(page.getByRole("button", { name: t.teamSendRecovery })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
      for (const button of await page.locator("article button").all()) {
        const box = await button.boundingBox();
        expect(box).not.toBeNull();
        expect(box!.x).toBeGreaterThanOrEqual(0);
        expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      }
      await page.screenshot({ path: `test-results/team-${locale}-${width}.png`, fullPage: true });
    }
  });
}

test("existing account receives a fresh cross-browser link and keeps the workspace invitation pending", async ({ page, context, request }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "existing" } });
  await page.goto("/settings?tab=team");
  const authBefore = (await context.cookies()).find((cookie) => cookie.name === "sb-127-auth-token")?.value;
  await page.getByLabel("User email", { exact: true }).fill("EXISTING@example.test");
  await page.getByRole("button", { name: "Send invite", exact: true }).click();
  await expect(page.getByText("Sign-in link sent. After signing in, they can select this workspace.", { exact: true })).toBeVisible();
  const { log }: { log: LogEntry[] } = await (await request.get(`${mockUrl}/__test`)).json();
  expect(log.filter((entry) => entry.path === "/rest/v1/rpc/manage_workspace_invitation").map((entry) => entry.body)).toEqual([
    expect.objectContaining({ p_email: "existing@example.test", p_workspace_id: alpha, p_role: "member", p_action: "create" }),
  ]);
  const otp = log.find((entry) => entry.path === "/auth/v1/otp")!;
  expect(otp.body).toMatchObject({ email: "existing@example.test", create_user: false, code_challenge: null, code_challenge_method: null });
  expect(otp.redirectTo).toBe(log.find((entry) => entry.path === "/auth/v1/invite")?.redirectTo);
  // Next normalizes loopback request origins to localhost in the dev server.
  const redirect = new URL(otp.redirectTo!);
  expect(redirect.pathname).toBe("/auth/callback");
  expect(redirect.port).toBe("3410");
  expect(["localhost", "127.0.0.1"]).toContain(redirect.hostname);
  expect((await context.cookies()).find((cookie) => cookie.name === "sb-127-auth-token")?.value).toBe(authBefore);
  expect((await context.cookies()).some((cookie) => cookie.name.includes("code-verifier"))).toBeFalsy();
});

test("new or unconfirmed accounts keep the original invite email flow", async ({ context, request }) => {
  const response = await context.request.post("/api/auth/invite", { data: { email: "new@example.test", role: "admin" } });
  expect(response.status()).toBe(200);
  expect(await response.json()).toMatchObject({ ok: true, delivery: "invite" });
  const { log }: { log: LogEntry[] } = await (await request.get(`${mockUrl}/__test`)).json();
  expect(log.filter((entry) => entry.path === "/auth/v1/invite")).toHaveLength(1);
  expect(log.some((entry) => entry.path === "/auth/v1/otp")).toBeFalsy();
  expect(log.find((entry) => entry.path === "/rest/v1/rpc/manage_workspace_invitation")?.body).toMatchObject({ p_workspace_id: alpha, p_role: "admin" });
});

for (const mode of ["email_failure", "otp_failure"]) {
  test(`${mode} reports failure and revokes only the newly created invitation`, async ({ context, request }) => {
    await request.post(`${mockUrl}/__test`, { data: { mode } });
    const response = await context.request.post("/api/auth/invite", { data: { email: "existing@example.test" } });
    expect(response.status()).toBe(mode === "otp_failure" ? 429 : 502);
    expect(await response.json()).toMatchObject({ error: expect.any(String) });
    const { log }: { log: LogEntry[] } = await (await request.get(`${mockUrl}/__test`)).json();
    expect(log.filter((entry) => entry.path === "/rest/v1/organization_invites" && entry.method === "PATCH").map((entry) => entry.body)).toEqual([{ status: "revoked" }]);
    expect(log.filter((entry) => entry.path === "/auth/v1/otp")).toHaveLength(mode === "otp_failure" ? 1 : 0);
  });
}

test("members, outsiders, and stale tabs cannot trigger the existing-user fallback", async ({ context, request }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "existing" } });
  await context.request.get("/api/auth/me");
  await context.request.post("/api/auth/workspace", { data: { workspaceId: beta } });
  expect((await context.request.post("/api/auth/invite", { data: { email: "existing@example.test" } })).status()).toBe(403);
  expect((await context.request.post("/api/auth/invite", { data: { email: "existing@example.test", workspaceId: foreign } })).status()).toBe(403);
  expect((await context.request.post("/api/auth/invite", { headers: { "x-tover-workspace-id": alpha }, data: { email: "existing@example.test" } })).status()).toBe(409);
  const { log }: { log: LogEntry[] } = await (await request.get(`${mockUrl}/__test`)).json();
  expect(log.some((entry) => entry.path === "/auth/v1/invite" || entry.path === "/auth/v1/otp" || entry.path === "/rest/v1/organization_invites")).toBeFalsy();
});

test("existing recipient opens a magic link in a fresh browser without a PKCE verifier", async ({ browser }) => {
  const recipient = await browser.newContext({ baseURL: appUrl });
  try {
    expect(await recipient.cookies()).toEqual([]);
    const page = await recipient.newPage();
    const tokens = session();
    const fragment = new URLSearchParams({ access_token: tokens.access_token, refresh_token: tokens.refresh_token, expires_in: "3600", token_type: "bearer", type: "magiclink" });
    // Local mock Auth verifies the session; no email or token goes to a project.
    let passwordSetupVisited = false;
    page.on("request", (req) => { if (new URL(req.url()).pathname === "/reset-password") passwordSetupVisited = true; });
    await page.goto(`/auth/callback#${fragment}`);
    await expect(page).toHaveURL(`${appUrl}/operations`);
    await expect(page.getByRole("combobox", { name: "Workspace", exact: true })).toHaveValue(alpha);
    expect(passwordSetupVisited).toBeFalsy();
    expect((await recipient.cookies()).some((cookie) => cookie.name === "sb-127-auth-token")).toBeTruthy();
    expect((await recipient.cookies()).some((cookie) => cookie.name.includes("code-verifier"))).toBeFalsy();
  } finally {
    await recipient.close();
  }
});

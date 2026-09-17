import { test, expect, type BrowserContext } from "@playwright/test";

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

test("existing account receives a fresh cross-browser link and keeps the workspace invitation pending", async ({ page, context, request }) => {
  await request.post(`${mockUrl}/__test`, { data: { mode: "existing" } });
  await page.goto("/settings?tab=team");
  const authBefore = (await context.cookies()).find((cookie) => cookie.name === "sb-127-auth-token")?.value;
  await page.getByLabel("User email", { exact: true }).fill("EXISTING@example.test");
  await page.getByRole("button", { name: "Send invite", exact: true }).click();
  await expect(page.getByText("Sign-in link sent. After signing in, they can select this workspace.", { exact: true })).toBeVisible();
  const { log }: { log: LogEntry[] } = await (await request.get(`${mockUrl}/__test`)).json();
  expect(log.filter((entry) => entry.path === "/rest/v1/organization_invites").map((entry) => entry.body)).toEqual([
    expect.objectContaining({ email: "existing@example.test", organization_id: alpha, role_id: "member", status: "pending" }),
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
  expect(log.find((entry) => entry.path === "/rest/v1/organization_invites")?.body).toMatchObject({ organization_id: alpha, role_id: "admin" });
});

for (const mode of ["email_failure", "otp_failure"]) {
  test(`${mode} reports failure and revokes only the newly created invitation`, async ({ context, request }) => {
    await request.post(`${mockUrl}/__test`, { data: { mode } });
    const response = await context.request.post("/api/auth/invite", { data: { email: "existing@example.test" } });
    expect(response.status()).toBe(mode === "otp_failure" ? 429 : 500);
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

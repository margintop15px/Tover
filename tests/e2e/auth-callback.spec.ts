import { expect, test, type Page } from "@playwright/test";
import { loadLocalEnv } from "./auth-helpers";

loadLocalEnv();

const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const storageKey = `sb-${new URL(supabaseUrl).hostname.split(".")[0]}-auth-token`;
const user = {
  id: "00000000-0000-0000-0000-000000000001",
  aud: "authenticated",
  role: "authenticated",
  email: "invitee@example.test",
  app_metadata: {},
  user_metadata: {},
  created_at: "2026-01-01T00:00:00Z",
};

function inviteFragment() {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const accessToken = [
    encode({ alg: "HS256", typ: "JWT" }),
    encode({ sub: user.id, aud: user.aud, exp: Math.floor(Date.now() / 1000) + 3600 }),
    Buffer.from("test-signature").toString("base64url"),
  ].join(".");
  return new URLSearchParams({
    access_token: accessToken,
    refresh_token: "test-refresh-token",
    expires_in: "3600",
    token_type: "bearer",
    type: "invite",
  }).toString();
}

async function mockAuth(page: Page) {
  // Use the real Supabase browser SDK, but never send test tokens to Supabase.
  await page.route(`${supabaseUrl}/auth/v1/**`, async (route) => {
    if (new URL(route.request().url()).pathname.endsWith("/user")) {
      await route.fulfill({ json: user });
    } else {
      await route.abort();
    }
  });
}

async function expectPasswordSetup(page: Page, startPath: string) {
  await mockAuth(page);
  // Stop at the next document request so the server need not validate a fake JWT.
  let sessionCookieSent = false;
  await page.route((url) => url.pathname === "/reset-password", async (route) => {
    const headers = await route.request().allHeaders();
    sessionCookieSent = (headers.cookie || "").includes(storageKey);
    await route.fulfill({ contentType: "text/html", body: "<h1>Password setup</h1>" });
  });
  await page.goto(`${startPath}#${inviteFragment()}`);
  await expect(page.getByRole("heading", { name: "Password setup" })).toBeVisible();
  await expect(page).toHaveURL(/\/reset-password$/);
  expect(sessionCookieSent).toBe(true);
}

test("new invitee establishes a cookie session before password setup", async ({ page }) => {
  expect(await page.context().cookies()).toEqual([]);
  await expectPasswordSetup(page, "/auth/callback");
});

test("invite sent to the site root survives the redirect through login", async ({ page }) => {
  await expectPasswordSetup(page, "/");
});

for (const startPath of ["/auth/callback", "/"]) {
  test(`PKCE signup at ${startPath} exchanges once and saves cookies before navigation`, async ({ page, baseURL }) => {
    await mockAuth(page);
    await page.context().addCookies([{
      name: `${storageKey}-code-verifier`,
      value: JSON.stringify("test-code-verifier"),
      url: baseURL!,
    }]);
    let exchanges = 0;
    await page.route(`${supabaseUrl}/auth/v1/token?grant_type=pkce`, async (route) => {
      exchanges += 1;
      expect(route.request().postDataJSON()).toEqual({
        auth_code: "test-auth-code",
        code_verifier: "test-code-verifier",
      });
      const tokens = Object.fromEntries(new URLSearchParams(inviteFragment()));
      await route.fulfill({ json: { ...tokens, expires_in: 3600, user } });
    });
    let cookieSent = false;
    await page.route((url) => url.pathname === "/operations" || url.pathname === "/", async (route) => {
      if (!exchanges) return route.continue();
      cookieSent = ((await route.request().allHeaders()).cookie || "").includes(storageKey);
      await route.fulfill({ contentType: "text/html", body: "<h1>Operations</h1>" });
    });
    // For the root fallback, middleware wraps the code inside login's `next`.
    // Let the root page perform its normal redirect to /operations after completion.
    await page.goto(`${startPath}?code=test-auth-code&next=/operations`);
    await expect(page.getByRole("heading", { name: "Operations", exact: true })).toBeVisible();
    expect(exchanges).toBe(1);
    expect(cookieSent).toBe(true);
  });
}

test("PKCE link opened in another browser explains how to continue", async ({ page }) => {
  await mockAuth(page);
  await page.goto("/auth/callback?code=test-auth-code");
  await expect(page.getByRole("alert").filter({ hasText: "same browser" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Back to login" })).toBeVisible();
  expect(new URL(page.url()).searchParams.has("code")).toBe(false);
});

test("expired email link shows the provider error instead of silently going to login", async ({ page }) => {
  await mockAuth(page);
  await page.goto("/auth/callback#error=access_denied&error_description=Email+link+is+invalid+or+has+expired");
  await expect(page.getByRole("alert").filter({ hasText: "Email link is invalid or has expired" })).toBeVisible();
  expect(new URL(page.url()).hash).toBe("");
});

test("incomplete invite does not proceed without a refresh token", async ({ page }) => {
  await mockAuth(page);
  const fragment = new URLSearchParams(inviteFragment());
  fragment.delete("refresh_token");
  await page.goto(`/auth/callback#${fragment}`);
  await expect(page.getByRole("alert").filter({ hasText: "link is incomplete" })).toBeVisible();
});

test("recipient in a fresh browser sets a password and continues into the app", async ({ page }) => {
  expect(await page.context().cookies()).toEqual([]);
  await mockAuth(page);
  let passwordUpdated = false;
  await page.route(`${supabaseUrl}/auth/v1/user`, async (route) => {
    if (route.request().method() === "PUT") {
      expect(route.request().postDataJSON().password).toBe("Invitee-password-123");
      passwordUpdated = true;
    }
    await route.fulfill({ json: user });
  });
  await page.route((url) => url.pathname === "/reset-password", async (route) => {
    // Render the real public form without asking the server to validate our fake JWT.
    const headers = { ...await route.request().allHeaders(), cookie: "" };
    const response = await route.fetch({ headers });
    await route.fulfill({ response });
  });
  await page.route((url) => url.pathname === "/", async (route) => {
    expect(passwordUpdated).toBe(true);
    expect((await route.request().allHeaders()).cookie).toContain(storageKey);
    await route.fulfill({ contentType: "text/html", body: "<h1>Workspace ready</h1>" });
  });
  await page.goto(`/auth/callback#${inviteFragment()}`);
  await expect(page.getByRole("heading", { name: "Set new password" })).toBeVisible();
  await page.getByLabel("New password", { exact: true }).fill("Invitee-password-123");
  await page.getByLabel("Confirm new password", { exact: true }).fill("Invitee-password-123");
  await page.getByRole("button", { name: "Update password", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Workspace ready" })).toBeVisible();
});

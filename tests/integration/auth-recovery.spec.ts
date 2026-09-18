import { expect, test, type APIRequestContext, type BrowserContext } from "@playwright/test";
import { createClient } from "@supabase/supabase-js";

const app = "http://127.0.0.1:3420";
const localApi = "http://127.0.0.1:3421";
const mail = "http://127.0.0.1:3424";
const password = "Recovered-password-123!";
const clientOptions = { auth: { persistSession: false, autoRefreshToken: false } };
const admin = () => createClient(localApi, process.env.SUPABASE_SERVICE_ROLE_KEY!, clientOptions);
const publicClient = () => createClient(localApi, process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!, clientOptions);
let managerId: string;
let workspaceId: string;
let managerToken: string;

async function emailLink(request: APIRequestContext, email: string, subject: string) {
  let id = "";
  await expect.poll(async () => {
    const inbox = await (await request.get(`${mail}/api/v1/messages`)).json();
    const message = inbox.messages.find((item: { To: { Address: string }[]; Subject: string }) =>
      item.To.some((to) => to.Address === email) && item.Subject.toLowerCase().includes(subject));
    id = message?.ID || "";
    return id;
  }).not.toBe("");
  const message = await (await request.get(`${mail}/api/v1/message/${id}`)).json();
  const links: string[] = [...message.HTML.matchAll(/href="([^"]+)"/g)].map((match: RegExpMatchArray) => match[1].replaceAll("&amp;", "&"));
  const link = links.find((value) => value.startsWith(`${localApi}/auth/v1/verify`));
  expect(link).toBeTruthy();
  return link!;
}

async function loginManager(context: BrowserContext) {
  const page = await context.newPage();
  await page.goto(`${app}/login`);
  await page.getByLabel("Email", { exact: true }).fill("manager@tover.test");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Log in", exact: true }).click();
  await expect(page).toHaveURL(`${app}/operations`);
  return page;
}

async function legacyInvite(request: APIRequestContext, email: string) {
  const client = admin();
  const invite = await client.from("organization_invites").insert({ organization_id: workspaceId, email, role_id: "member", invited_by: managerId }).select("id").single();
  expect(invite.error).toBeNull();
  const invited = await client.auth.admin.inviteUserByEmail(email, { redirectTo: `${app}/auth/callback` });
  expect(invited.error).toBeNull();
  // Reproduce the old callback: Auth accepts the email but the app never asks for a password.
  const verified = await request.get(await emailLink(request, email, "invited"), { maxRedirects: 0 });
  expect(verified.status()).toBe(303);
  expect(verified.headers().location).toContain("access_token=");
  const userId = invited.data.user!.id;
  expect((await client.from("organization_memberships").insert({ organization_id: workspaceId, user_id: userId, role_id: "member" })).error).toBeNull();
  expect((await client.from("organization_invites").update({ status: "accepted" }).eq("id", invite.data!.id)).error).toBeNull();
  const confirmed = await client.auth.admin.getUserById(userId);
  expect(confirmed.data.user?.email_confirmed_at).toBeTruthy();
  expect((await publicClient().auth.signInWithPassword({ email, password })).error).toBeTruthy();
  return userId;
}

test.beforeAll(async () => {
  expect(process.env.TOVER_LOCAL_AUTH).toBe("1");
  let session = await publicClient().auth.signInWithPassword({ email: "manager@tover.test", password });
  if (!session.data.session) {
    const manager = await admin().auth.admin.createUser({ email: "manager@tover.test", password, email_confirm: true, user_metadata: { organization_name: "Recovery test workspace", name: "Recovery Manager" } });
    expect(manager.error).toBeNull();
    session = await publicClient().auth.signInWithPassword({ email: "manager@tover.test", password });
  }
  expect(session.error).toBeNull();
  managerId = session.data.user!.id;
  const membership = await admin().from("organization_memberships").select("organization_id").eq("user_id", managerId).single();
  expect(membership.error).toBeNull();
  workspaceId = membership.data!.organization_id;
  managerToken = session.data.session!.access_token;
});

test("legacy member recovers from a real manager email in another browser, then logs in with their chosen password", async ({ browser, context, request }) => {
  const email = "legacy@tover.test";
  const userId = await legacyInvite(request, email);
  const page = await loginManager(context);
  await page.goto(`${app}/settings?tab=team`);
  const member = page.locator("article").filter({ hasText: email });
  await member.getByRole("button", { name: "Send password reset" }).click();
  await expect(page.getByRole("status").filter({ hasText: "recovery link has been sent" })).toBeVisible();
  const recipient = await browser.newContext();
  try {
    expect(await recipient.cookies()).toEqual([]);
    const recovery = await recipient.newPage();
    await recovery.goto(await emailLink(request, email, "reset"));
    await expect(recovery.getByRole("heading", { name: "Set new password" })).toBeVisible();
    await expect(recovery.getByText(email, { exact: true })).toBeVisible();
    await recovery.getByLabel("New password", { exact: true }).fill(password);
    await recovery.getByLabel("Confirm new password", { exact: true }).fill(password);
    await recovery.getByRole("button", { name: "Update password", exact: true }).click();
    await expect(recovery).toHaveURL(`${app}/operations`);
    await recovery.getByRole("button", { name: "Log out", exact: true }).click();
    await expect(recovery).toHaveURL(`${app}/login`);
    await recovery.getByLabel("Email", { exact: true }).fill(email);
    await recovery.getByLabel("Password", { exact: true }).fill(password);
    await recovery.getByRole("button", { name: "Log in", exact: true }).click();
    await expect(recovery).toHaveURL(`${app}/operations`);
    const memberAfter = await admin().from("organization_memberships").select("role_id,status").eq("user_id", userId).single();
    expect(memberAfter.data).toEqual({ role_id: "member", status: "active" });
    expect((await context.request.get(`${app}/api/auth/me`)).ok()).toBe(true);
  } finally { await recipient.close(); }
});

test("self-service recovery opens in a fresh browser and canceled or expired invitations cannot grant access", async ({ browser, request }) => {
  for (const status of ["revoked", "expired"]) {
    const email = `${status}@tover.test`;
    const account = await admin().auth.admin.createUser({ email, email_confirm: false });
    expect(account.error).toBeNull();
    const userId = account.data.user!.id;
    expect((await admin().from("organization_invites").insert({ organization_id: workspaceId, email, role_id: "member", status,
      expires_at: new Date(Date.now() - 86400_000).toISOString() })).error).toBeNull();
    const sender = await browser.newContext();
    const recipient = await browser.newContext();
    try {
      const requestPage = await sender.newPage();
      await requestPage.goto(`${app}/forgot-password`);
      await requestPage.getByLabel("Email", { exact: true }).fill(email);
      await requestPage.getByRole("button", { name: "Send reset email" }).click();
      await expect(requestPage.getByRole("status")).toContainText("If an account exists");
      expect((await sender.cookies()).some((cookie) => cookie.name.includes("code-verifier"))).toBe(false);
      const page = await recipient.newPage();
      await page.goto(await emailLink(request, email, "reset"));
      await expect(page.getByRole("heading", { name: "Set new password" })).toBeVisible();
      await page.getByLabel("New password", { exact: true }).fill(password);
      await page.getByLabel("Confirm new password", { exact: true }).fill(password);
      await page.getByRole("button", { name: "Update password", exact: true }).click();
      await expect(page.getByRole("alert").filter({ hasText: "do not have access" })).toBeVisible();
      expect((await admin().from("organization_memberships").select("id").eq("user_id", userId)).data).toEqual([]);
      expect((await publicClient().auth.signInWithPassword({ email, password })).error).toBeNull();
    } finally { await sender.close(); await recipient.close(); }
  }
});

test("real concurrent database reservations send once and cancellation prevents acceptance", async () => {
  const manager = createClient(localApi, process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!, {
    ...clientOptions, global: { headers: { Authorization: `Bearer ${managerToken}` } },
  });
  const email = "concurrent@tover.test";
  const created = await manager.rpc("manage_workspace_invitation", { p_workspace_id: workspaceId, p_action: "create", p_email: email });
  expect(created.error).toBeNull();
  const id = created.data.id;
  expect((await admin().from("organization_invites").update({ created_at: new Date(Date.now() - 120_000).toISOString() }).eq("id", id)).error).toBeNull();
  const results = await Promise.all([1, 2].map(() => manager.rpc("manage_workspace_invitation", { p_workspace_id: workspaceId, p_action: "resend", p_invite_id: id })));
  results.forEach((result) => expect(result.error).toBeNull());
  expect(results.filter((result) => result.data.id)).toHaveLength(1);
  expect(results.filter((result) => result.data.code === "RATE_LIMITED")).toHaveLength(1);
  const canceled = await manager.rpc("manage_workspace_invitation", { p_workspace_id: workspaceId, p_action: "cancel", p_invite_id: id });
  expect(canceled.error).toBeNull();
  expect(canceled.data).toEqual({ ok: true });
  expect((await admin().from("organization_invites").select("status").eq("email", email)).data?.every((invite) => invite.status === "revoked")).toBe(true);
});

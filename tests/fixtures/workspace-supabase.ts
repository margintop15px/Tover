// Local-only Auth/PostgREST fixture. No connection to a Supabase project.
import { createServer } from "node:http";

const user = {
  id: "10000000-0000-4000-8000-000000000001", email: "workspace@example.test",
  aud: "authenticated", role: "authenticated", app_metadata: {}, user_metadata: {},
  created_at: "2026-01-01T00:00:00Z", email_confirmed_at: "2026-01-01T00:00:00Z",
};
const alpha = "20000000-0000-4000-8000-000000000001";
const beta = "20000000-0000-4000-8000-000000000002";
const longName = "Beta workspace with a very long name that must fit inside the sidebar";
let mode = "normal";
let log: { path: string; method: string; body: Record<string, unknown>; workspace: string | null; redirectTo?: string | null }[] = [];
let categories: Record<string, unknown>[] = [];
interface Invite { id: string; email: string; role: string; status: string; createdAt: string; expiresAt: string; lastRequestedAt: string }
let invites: Invite[] = [];
let recoveryRequestedAt: string | null = null;
const legacyId = "10000000-0000-4000-8000-000000000002";
const pendingId = "30000000-0000-4000-8000-000000000001";
const expiredId = "30000000-0000-4000-8000-000000000002";
function seedTeam() {
  const old = new Date(Date.now() - 86400_000).toISOString();
  invites = [
    { id: pendingId, email: "pending@example.test", role: "member", status: "pending", createdAt: old, expiresAt: new Date(Date.now() + 86400_000).toISOString(), lastRequestedAt: old },
    { id: expiredId, email: "expired@example.test", role: "admin", status: "expired", createdAt: old, expiresAt: old, lastRequestedAt: old },
  ];
}

createServer(async (request, response) => {
  const url = new URL(request.url!, "http://127.0.0.1:3411");
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  const rawBody = Buffer.concat(chunks).toString();
  const body = rawBody ? JSON.parse(rawBody) : {};
  const json = (data: unknown, status = 200) => {
    response.writeHead(status, {
      "Content-Type": "application/json", "Access-Control-Allow-Origin": "*",
      "X-Supabase-Api-Version": "2024-01-01",
      "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS",
      "Content-Range": "0-0/1",
    });
    response.end(JSON.stringify(data));
  };
  if (request.method === "OPTIONS") return json({});
  if (url.pathname === "/__test") {
    if (request.method === "POST") { mode = body.mode ?? "normal"; log = []; categories = []; invites = []; recoveryRequestedAt = null; if (mode.startsWith("team") || mode === "cancel_during_send") seedTeam(); }
    return json({ mode, log, invites, recoveryRequestedAt });
  }
  if (url.pathname === "/auth/v1/user") return json(user);
  if (url.pathname === "/auth/v1/logout") return json({});
  if (url.pathname === "/auth/v1/invite") {
    log.push({ path: url.pathname, method: request.method!, body, workspace: null, redirectTo: url.searchParams.get("redirect_to") });
    if (mode === "existing" || mode === "otp_failure") {
      return json({ code: "email_exists", msg: "A user with this email address has already been registered" }, 422);
    }
    if (mode === "team_timeout") return json({ msg: "Gateway Timeout" }, 504);
    if (mode === "cancel_during_send") invites.forEach((invite) => { invite.status = "revoked"; });
    if (mode === "email_failure" || mode === "team_failure") return json({ code: "unexpected_failure", msg: "Email delivery unavailable" }, 500);
    return json({ ...user, id: "10000000-0000-4000-8000-000000000002", email: body.email });
  }
  if (url.pathname === "/auth/v1/otp") {
    log.push({ path: url.pathname, method: request.method!, body, workspace: null, redirectTo: url.searchParams.get("redirect_to") });
    if (mode === "otp_failure") return json({ code: "over_email_send_rate_limit", msg: "Please wait before requesting another email" }, 429);
    return json({});
  }
  if (url.pathname === "/auth/v1/recover") {
    log.push({ path: url.pathname, method: request.method!, body, workspace: null, redirectTo: url.searchParams.get("redirect_to") });
    if (mode === "team_timeout") return json({ msg: "Gateway Timeout" }, 504);
    if (mode === "team_failure") return json({ code: "unexpected_failure", msg: "Email unavailable" }, 500);
    return json({});
  }
  if (!url.pathname.startsWith("/rest/v1/")) return json({ error: "Unknown mock route" }, 404);
  const table = url.pathname.slice("/rest/v1/".length);
  const workspace = url.searchParams.get("workspace_id")?.replace(/^eq\./, "") ?? null;
  log.push({ path: url.pathname, method: request.method!, body, workspace });
  if (table === "organization_memberships") {
    if (mode === "failure") return json({ message: "Mock membership failure" }, 500);
    const memberships = mode === "none" ? [] : [
      { organization_id: alpha, role_id: "admin", status: "active", created_at: "2026-01-01", organizations: { name: "Alpha workspace" } },
      ...(mode === "single" ? [] : [
        { organization_id: beta, role_id: "member", status: "active", created_at: "2026-01-01", organizations: { name: mode === "unnamed" ? "" : longName } },
      ]),
    ];
    // Equal creation dates deliberately require the production secondary sort.
    const ordered = url.searchParams.get("order")?.includes("organization_id.asc") ? memberships : memberships.reverse();
    return json(ordered);
  }
  if (table === "profiles") return json({ display_name: "Workspace Tester" });
  if (table === "rpc/accept_my_organization_invites") return mode === "reconcile_failure" ? json({ message: "Reconciliation failed" }, 500) : json(0);
  if (table === "rpc/workspace_team") {
    const grouped = new Map<string, Invite>();
    for (const invite of invites) if (["pending", "expired"].includes(invite.status)) grouped.set(invite.email, invite);
    return json({ members: [{ userId: legacyId, name: "Legacy recipient", email: "legacy@example.test", role: "member", status: "active", emailConfirmedAt: "2026-01-01", lastSignInAt: "2026-01-01", recoveryRequestedAt }], invitations: [...grouped.values()] });
  }
  if (table === "rpc/request_member_recovery") {
    if (body.p_user_id !== legacyId) return json({ code: "NOT_FOUND" });
    if (recoveryRequestedAt && Date.now() - Date.parse(recoveryRequestedAt) < 60_000) return json({ code: "RATE_LIMITED", retryAfter: 60 });
    recoveryRequestedAt = new Date().toISOString();
    return json({ email: "legacy@example.test" });
  }
  if (table === "rpc/manage_workspace_invitation") {
    const original = invites.find((invite) => invite.id === body.p_invite_id);
    const email = body.p_action === "create" ? body.p_email : original?.email;
    if (!email) return json({ code: "NOT_FOUND" });
    if (body.p_action === "cancel") {
      if (original?.status === "accepted") return json({ code: "INVITE_CHANGED" });
      invites.filter((invite) => invite.email === email && ["pending", "expired"].includes(invite.status)).forEach((invite) => { invite.status = "revoked"; });
      return json({ ok: true });
    }
    if (body.p_action === "resend" && !["pending", "expired"].includes(original!.status)) return json({ code: "INVITE_CHANGED" });
    if (email === "legacy@example.test") return json({ code: "MEMBER_EXISTS", userId: legacyId });
    if (body.p_action === "create" && invites.some((invite) => invite.email === email && ["pending", "expired"].includes(invite.status))) return json({ code: "INVITE_EXISTS" });
    if (invites.some((invite) => invite.email === email && Date.now() - Date.parse(invite.createdAt) < 60_000)) return json({ code: "RATE_LIMITED", retryAfter: 60 });
    const now = new Date().toISOString();
    const invite = { id: crypto.randomUUID(), email, role: original?.role || body.p_role, status: "pending", createdAt: now, expiresAt: new Date(Date.now() + 7 * 86400_000).toISOString(), lastRequestedAt: now };
    invites.push(invite);
    return json({ id: invite.id, email, role: invite.role });
  }
  if (table === "organization_invites") {
    const id = url.searchParams.get("id")?.replace(/^eq\./, "");
    const invite = invites.find((item) => item.id === id);
    if (request.method === "PATCH") {
      if (invite?.status === "pending") invite.status = body.status;
      return json([]);
    }
    if (request.method === "GET") return invite ? json({ status: invite.status }) : json({ message: "Not found" }, 404);
  }
  if (table.startsWith("rpc/")) return json([]);
  if (table === "workspace_settings") return json([{
    currency: workspace === beta ? "USD" : "EUR", category_required: false, store_required: false,
    default_category_id: null, default_store_id: null,
  }]);
  if (table === "categories") {
    if (request.method === "POST") {
      const row = { ...body, id: crypto.randomUUID(), created_at: new Date().toISOString() };
      categories.push(row);
      return json(row, 201);
    }
    return json(categories.filter((item) => item.workspace_id === workspace));
  }
  if (table === "organization_invites" && request.method === "POST") return json({ ...body, id: crypto.randomUUID() }, 201);
  if (table === "imports" && request.method === "POST") return json({ ...body, id: crypto.randomUUID() }, 201);
  return json([]);
}).listen(3411, "127.0.0.1", () => console.log("Local workspace Supabase fixture ready"));

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
    if (request.method === "POST") { mode = body.mode ?? "normal"; log = []; categories = []; }
    return json({ mode, log });
  }
  if (url.pathname === "/auth/v1/user") return json(user);
  if (url.pathname === "/auth/v1/logout") return json({});
  if (url.pathname === "/auth/v1/invite") {
    log.push({ path: url.pathname, method: request.method!, body, workspace: null, redirectTo: url.searchParams.get("redirect_to") });
    if (mode === "existing" || mode === "otp_failure") {
      return json({ code: "email_exists", msg: "A user with this email address has already been registered" }, 422);
    }
    if (mode === "email_failure") return json({ code: "unexpected_failure", msg: "Email delivery unavailable" }, 500);
    return json({ ...user, id: "10000000-0000-4000-8000-000000000002", email: body.email });
  }
  if (url.pathname === "/auth/v1/otp") {
    log.push({ path: url.pathname, method: request.method!, body, workspace: null, redirectTo: url.searchParams.get("redirect_to") });
    if (mode === "otp_failure") return json({ code: "over_email_send_rate_limit", msg: "Please wait before requesting another email" }, 429);
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
  if (table === "rpc/accept_my_organization_invites") return json(0);
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

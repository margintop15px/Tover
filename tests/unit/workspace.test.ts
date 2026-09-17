import assert from "node:assert/strict";
import test from "node:test";
import { initialWorkspaceId, resolveWorkspaceMembership, workspaceCookieName, workspaceCookieOptions, WorkspaceAccessError, WORKSPACE_HEADER } from "../../src/lib/workspace";
import { setRequestWorkspace, workspaceFetch } from "../../src/lib/workspace-fetch";

const memberships = [
  { organization_id: "alpha", role_id: "admin" },
  { organization_id: "beta", role_id: "member" },
];

test("initial loading chooses an active saved membership or the first ordered membership", () => {
  assert.equal(initialWorkspaceId(memberships, "beta"), "beta");
  assert.equal(initialWorkspaceId(memberships, "revoked"), "alpha");
  assert.equal(initialWorkspaceId(memberships), "alpha");
  assert.equal(initialWorkspaceId([], "alpha"), null);
  assert.notEqual(workspaceCookieName("user-1"), workspaceCookieName("user-2"));
  assert.deepEqual(workspaceCookieOptions(false), {
    httpOnly: true, sameSite: "lax", path: "/", maxAge: 2592000, secure: false,
  });
  assert.equal(workspaceCookieOptions(true).secure, true);
});

test("requests honor selection, preserve explicit overrides, and check the selected role", () => {
  assert.equal(resolveWorkspaceMembership(memberships, {}).organization_id, "alpha");
  assert.equal(resolveWorkspaceMembership(memberships, { savedId: "beta" }).role_id, "member");
  assert.equal(resolveWorkspaceMembership(memberships, { savedId: "beta", requestedId: "alpha", requireManager: true }).organization_id, "alpha");
  for (const options of [
    { savedId: "revoked" },
    { requestedId: "foreign" },
    { savedId: "beta", requireManager: true },
  ]) {
    assert.throws(() => resolveWorkspaceMembership(memberships, options),
      (error: unknown) => error instanceof WorkspaceAccessError && error.status === 403);
  }
  assert.throws(() => resolveWorkspaceMembership([], {}), /No active organization/);
});

test("stale page requests fail before explicit overrides or write permissions are considered", () => {
  for (const requestedId of [undefined, "alpha", "beta"]) {
    assert.throws(() => resolveWorkspaceMembership(memberships, {
      savedId: "beta", pageWorkspaceId: "alpha", requestedId,
    }), (error: unknown) => error instanceof WorkspaceAccessError && error.status === 409 && error.code === "WORKSPACE_CHANGED");
  }
  assert.equal(resolveWorkspaceMembership(memberships, {
    savedId: "alpha", pageWorkspaceId: "alpha", requireManager: true,
  }).organization_id, "alpha");
});

test("browser helper preserves uploads, headers and cancellation; stale writes are never retried", async () => {
  const originalFetch = globalThis.fetch;
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  const navigations: string[] = [];
  Object.defineProperty(globalThis, "window", { configurable: true, value: { location: { assign: (url: string) => navigations.push(url) } } });
  const calls: RequestInit[] = [];
  let status = 200;
  let code = "";
  globalThis.fetch = async (_input, init) => {
    calls.push(init!);
    return Response.json({ code }, { status });
  };
  try {
    await assert.rejects(workspaceFetch("/api/imports"), /not loaded/);
    setRequestWorkspace("alpha");
    const body = new FormData();
    body.set("file", new Blob(["example"]), "example.csv");
    const signal = new AbortController().signal;
    await workspaceFetch("/api/imports", { method: "POST", body, signal, headers: { "x-example": "kept" } });
    assert.equal(calls[0].body, body);
    assert.equal(calls[0].signal, signal);
    assert.equal(new Headers(calls[0].headers).get("x-example"), "kept");
    assert.equal(new Headers(calls[0].headers).get("Content-Type"), null);
    assert.equal(new Headers(calls[0].headers).get(WORKSPACE_HEADER), "alpha");
    status = 409;
    code = "DUPLICATE";
    await workspaceFetch("/api/categories", { method: "POST" });
    assert.deepEqual(navigations, []);
    code = "WORKSPACE_CHANGED";
    const response = await workspaceFetch("/api/categories", { method: "POST" });
    assert.equal(response.status, 409);
    assert.deepEqual(navigations, ["/operations"]);
    assert.equal(calls.length, 3);
    assert.equal(new Headers(calls[2].headers).get(WORKSPACE_HEADER), "alpha");
    await assert.rejects(workspaceFetch("https://example.com/api/data"), /same-origin/);
  } finally {
    globalThis.fetch = originalFetch;
    if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow);
    else Reflect.deleteProperty(globalThis, "window");
    setRequestWorkspace(null);
  }
});

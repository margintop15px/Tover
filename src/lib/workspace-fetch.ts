import { WORKSPACE_CHANGED, WORKSPACE_HEADER } from "./workspace";

// This snapshot belongs to the rendered page, not the mutable browser cookie.
let pageWorkspaceId: string | null = null;

export function setRequestWorkspace(workspaceId: string | null) {
  pageWorkspaceId = workspaceId;
}

export async function workspaceFetch(input: string, init?: RequestInit): Promise<Response> {
  if (!pageWorkspaceId) throw new Error("Workspace is not loaded");
  if (!input.startsWith("/api/")) throw new Error("Expected a same-origin API path");
  const headers = new Headers(init?.headers);
  headers.set(WORKSPACE_HEADER, pageWorkspaceId);
  const response = await fetch(input, { cache: "no-store", ...init, headers });
  if (response.status === 409) {
    const body = await response.clone().json().catch(() => null);
    if (body?.code === WORKSPACE_CHANGED) window.location.assign("/operations");
  }
  return response;
}

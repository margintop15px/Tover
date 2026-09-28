import { WORKSPACE_CHANGED, WORKSPACE_HEADER } from "./workspace";

// This snapshot belongs to the rendered page, not the mutable browser cookie.
let pageWorkspaceId: string | null = null;

export function setRequestWorkspace(workspaceId: string | null) {
  pageWorkspaceId = workspaceId;
}

function waitForRetry(ms: number, signal?: AbortSignal | null): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    function onAbort() {
      clearTimeout(timer);
      reject(signal?.reason);
    }
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

export async function workspaceFetch(input: string, init?: RequestInit): Promise<Response> {
  if (!pageWorkspaceId) throw new Error("Workspace is not loaded");
  if (!input.startsWith("/api/")) throw new Error("Expected a same-origin API path");
  const headers = new Headers(init?.headers);
  headers.set(WORKSPACE_HEADER, pageWorkspaceId);
  let response: Response;
  for (let attempt = 0; ; attempt++) {
    try {
      response = await fetch(input, { cache: "no-store", ...init, headers });
      break;
    } catch (error) {
      if ((init?.method ?? "GET").toUpperCase() !== "GET" || !(error instanceof TypeError) || attempt === 2) throw error;
      await waitForRetry(500 * 2 ** attempt, init?.signal);
    }
  }
  if (response.status === 409) {
    const body = await response.clone().json().catch(() => null);
    if (body?.code === WORKSPACE_CHANGED) window.location.assign("/operations");
  }
  return response;
}

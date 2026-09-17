export const WORKSPACE_HEADER = "x-tover-workspace-id";
export const WORKSPACE_CHANGED = "WORKSPACE_CHANGED";

export function workspaceCookieName(userId: string) {
  return `tover-workspace-${userId}`;
}

export function workspaceCookieOptions(secure: boolean) {
  return { httpOnly: true, sameSite: "lax" as const, path: "/", maxAge: 30 * 24 * 60 * 60, secure };
}

export interface MembershipRow {
  organization_id: string;
  role_id: string;
}

export class WorkspaceAccessError extends Error {
  constructor(public status: number, message: string, public code?: string) {
    super(message);
  }
}

// Callers order memberships by created_at, then organization_id.
export function initialWorkspaceId(memberships: MembershipRow[], savedId?: string) {
  return memberships.find((item) => item.organization_id === savedId)?.organization_id
    ?? memberships[0]?.organization_id
    ?? null;
}

export function resolveWorkspaceMembership(
  memberships: MembershipRow[],
  { savedId, requestedId, pageWorkspaceId, requireManager = false }: {
    savedId?: string;
    requestedId?: string | null;
    pageWorkspaceId?: string | null;
    requireManager?: boolean;
  }
) {
  if (!memberships.length) {
    throw new WorkspaceAccessError(403, "No active organization membership");
  }

  // Never retarget a stale page's request when another tab changes the cookie.
  const browserWorkspaceId = savedId ?? memberships[0].organization_id;
  if (pageWorkspaceId && pageWorkspaceId !== browserWorkspaceId) {
    throw new WorkspaceAccessError(409, "Workspace changed. Reload the page.", WORKSPACE_CHANGED);
  }

  const workspaceId = requestedId || browserWorkspaceId;
  const membership = memberships.find((item) => item.organization_id === workspaceId);
  if (!membership) throw new WorkspaceAccessError(403, "Organization access denied");
  if (requireManager && !["owner", "admin"].includes(membership.role_id)) {
    throw new WorkspaceAccessError(403, "Insufficient permissions");
  }
  return membership;
}

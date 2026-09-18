import { NextRequest, NextResponse } from "next/server";
import * as Sentry from "@sentry/nextjs";
import type { SupabaseClient, User } from "@supabase/supabase-js";
import { createUserServerClient } from "@/lib/supabase-server";
import { resolveWorkspaceMembership, workspaceCookieName, WorkspaceAccessError, WORKSPACE_HEADER } from "@/lib/workspace";

export interface RouteContext {
  supabase: SupabaseClient;
  user: User;
  workspaceId: string;
  role: string;
}

export class RouteAuthError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

interface GetRouteContextOptions {
  requireManager?: boolean;
  workspaceId?: string | null;
}

export async function getRouteContext(
  request: NextRequest,
  options: GetRouteContextOptions = {}
): Promise<RouteContext> {
  const supabase = await createUserServerClient();

  const {
    data: { user },
    error: userError,
  } = await supabase.auth.getUser();

  if (userError || !user) {
    throw new RouteAuthError(401, "Unauthorized");
  }

  const { error: inviteError } = await supabase.rpc("accept_my_organization_invites");
  if (inviteError) throw new RouteAuthError(503, "Could not load your workspaces. Please try again.");

  const searchParams = new URL(request.url).searchParams;
  const requestedWorkspaceId = options.workspaceId ?? searchParams.get("workspaceId");

  const { data, error } = await supabase
    .from("organization_memberships")
    .select("organization_id, role_id")
    .eq("user_id", user.id)
    .eq("status", "active")
    .order("created_at", { ascending: true })
    .order("organization_id", { ascending: true });

  if (error) {
    throw new RouteAuthError(500, error.message);
  }

  const membership = resolveWorkspaceMembership(data || [], {
    savedId: request.cookies.get(workspaceCookieName(user.id))?.value,
    requestedId: requestedWorkspaceId,
    pageWorkspaceId: request.headers.get(WORKSPACE_HEADER),
    requireManager: options.requireManager,
  });

  return {
    supabase,
    user,
    workspaceId: membership.organization_id,
    role: membership.role_id,
  };
}

export function toRouteErrorResponse(error: unknown): NextResponse {
  if (error instanceof WorkspaceAccessError) {
    return NextResponse.json({ error: error.message, code: error.code }, { status: error.status });
  }
  if (error instanceof RouteAuthError) {
    return NextResponse.json({ error: error.message }, { status: error.status });
  }

  console.error("Route context error:", error);
  Sentry.captureException(error, { tags: { handled_by: "toRouteErrorResponse" } });
  if (process.env.NODE_ENV !== "production") {
    return NextResponse.json(
      {
        error: "Internal server error",
        detail: error instanceof Error ? error.message : String(error),
      },
      { status: 500 }
    );
  }

  return NextResponse.json(
    { error: "Internal server error" },
    { status: 500 }
  );
}

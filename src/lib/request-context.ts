import { NextRequest, NextResponse } from "next/server";
import type { SupabaseClient, User } from "@supabase/supabase-js";
import { createUserServerClient } from "@/lib/supabase-server";

const MANAGER_ROLES = new Set(["owner", "admin"]);

interface MembershipRow {
  organization_id: string;
  role_id: string;
}

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

  // Best-effort sync of pending invites for existing accounts.
  await supabase.rpc("accept_my_organization_invites");

  const searchParams = new URL(request.url).searchParams;
  const requestedWorkspaceId = options.workspaceId ?? searchParams.get("workspaceId");

  const { data, error } = await supabase
    .from("organization_memberships")
    .select("organization_id, role_id")
    .eq("user_id", user.id)
    .eq("status", "active");

  if (error) {
    throw new RouteAuthError(500, error.message);
  }

  const memberships = (data || []) as MembershipRow[];

  if (memberships.length === 0) {
    throw new RouteAuthError(403, "No active organization membership");
  }

  let membership = memberships[0];

  if (requestedWorkspaceId) {
    const requestedMembership = memberships.find(
      (item) => item.organization_id === requestedWorkspaceId
    );

    if (!requestedMembership) {
      throw new RouteAuthError(403, "Organization access denied");
    }

    membership = requestedMembership;
  }

  if (options.requireManager && !MANAGER_ROLES.has(membership.role_id)) {
    throw new RouteAuthError(403, "Insufficient permissions");
  }

  return {
    supabase,
    user,
    workspaceId: membership.organization_id,
    role: membership.role_id,
  };
}

export function toRouteErrorResponse(error: unknown): NextResponse {
  if (error instanceof RouteAuthError) {
    return NextResponse.json({ error: error.message }, { status: error.status });
  }

  console.error("Route context error:", error);
  return NextResponse.json(
    { error: "Internal server error" },
    { status: 500 }
  );
}

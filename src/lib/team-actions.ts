import { NextRequest, NextResponse } from "next/server";
import { isAuthRetryableFetchError, type AuthError } from "@supabase/supabase-js";
import { createServiceRoleClient } from "@/lib/supabase-server";
import { getRouteContext } from "@/lib/request-context";

export const isUuid = (value: unknown): value is string =>
  typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

const errors: Record<string, [number, string]> = {
  INVALID_INPUT: [400, "Enter a valid email and an admin or member role."],
  NOT_FOUND: [404, "This team record was not found."],
  MEMBER_EXISTS: [409, "This person is already a member. Use their password recovery action."],
  MEMBER_INACTIVE: [409, "Password recovery is only available for active members."],
  INVITE_EXISTS: [409, "An invitation already exists. Use Resend in the invitations list."],
  INVITE_CHANGED: [409, "This invitation changed. Refresh the team list."],
  RATE_LIMITED: [429, "Please wait before requesting another email."],
};

export function teamActionError(result: { code?: string; retryAfter?: number }) {
  if (!result.code) return null;
  const [status, error] = errors[result.code] || [500, "Could not complete the team action."];
  return NextResponse.json({ ...result, error }, {
    status,
    headers: status === 429 ? { "Retry-After": String(result.retryAfter || 60) } : undefined,
  });
}

export function deliveryErrorResponse(error?: AuthError | null) {
  const unconfirmed = !error || isAuthRetryableFetchError(error);
  return NextResponse.json({
    error: unconfirmed ? "Email delivery could not be confirmed. Check your inbox before trying again." : "Could not send the email. Please try again later.",
    code: unconfirmed ? "DELIVERY_UNCONFIRMED" : error.status === 429 ? "RATE_LIMITED" : "DELIVERY_FAILED",
    retryAfter: 60,
  }, { status: error?.status === 429 ? 429 : 502, headers: { "Retry-After": "60" } });
}

export function emailCallbackUrl(request: NextRequest, recovery = false) {
  const origin = process.env.NEXT_PUBLIC_SITE_URL || request.nextUrl.origin;
  return new URL(recovery ? "/auth/callback?next=/reset-password" : "/auth/callback", origin).toString();
}

export async function runInvitationAction(
  request: NextRequest,
  action: "create" | "resend" | "cancel",
  input: { inviteId?: string; email?: string; role?: string; workspaceId?: string } = {},
) {
  const { supabase, workspaceId } = await getRouteContext(request, {
    requireManager: true, workspaceId: input.workspaceId,
  });
  const { data: attempt, error } = await supabase.rpc("manage_workspace_invitation", {
    p_workspace_id: workspaceId, p_action: action, p_invite_id: input.inviteId || null,
    p_email: input.email || null, p_role: input.role ?? "member",
  });
  if (error) throw error;
  if (!attempt) throw new Error("Missing invitation result");
  const conflict = teamActionError(attempt);
  if (conflict) return conflict;
  if (action === "cancel") return NextResponse.json({ ok: true });

  // No session storage or PKCE verifier is shared with the initiating manager.
  const admin = createServiceRoleClient(15_000);
  let delivery: "invite" | "magiclink" = "invite";
  let deliveryError: AuthError | null;
  try {
    ({ error: deliveryError } = await admin.auth.admin.inviteUserByEmail(attempt.email, {
      redirectTo: emailCallbackUrl(request),
    }));
    if (deliveryError?.code === "email_exists") {
      delivery = "magiclink";
      ({ error: deliveryError } = await admin.auth.signInWithOtp({
        email: attempt.email,
        options: { shouldCreateUser: false, emailRedirectTo: emailCallbackUrl(request) },
      }));
    }
  } catch {
    // A lost response does not prove that the provider failed to send.
    return deliveryErrorResponse();
  }
  if (deliveryError) {
    if (!isAuthRetryableFetchError(deliveryError)) {
      const { error: revokeError } = await supabase.from("organization_invites")
        .update({ status: "revoked" }).eq("id", attempt.id)
        .eq("organization_id", workspaceId).eq("status", "pending");
      if (revokeError) throw revokeError;
    }
    return deliveryErrorResponse(deliveryError);
  }
  const { data: current, error: stateError } = await supabase.from("organization_invites")
    .select("status").eq("organization_id", workspaceId).eq("id", attempt.id).single();
  if (stateError) return deliveryErrorResponse();
  if (!current || current.status === "revoked" || current.status === "expired") {
    return teamActionError({ code: "INVITE_CHANGED" })!;
  }
  return NextResponse.json({ ok: true, inviteId: attempt.id, delivery, retryAfter: 60 });
}

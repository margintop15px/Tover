import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { createServiceRoleClient } from "@/lib/supabase-server";
import { deliveryErrorResponse, emailCallbackUrl, isUuid, teamActionError } from "@/lib/team-actions";

export async function POST(request: NextRequest, { params }: { params: Promise<{ userId: string }> }) {
  try {
    const { userId } = await params;
    if (!isUuid(userId)) return teamActionError({ code: "NOT_FOUND" })!;
    const { supabase, workspaceId } = await getRouteContext(request, { requireManager: true });
    const { data, error } = await supabase.rpc("request_member_recovery", {
      p_workspace_id: workspaceId, p_user_id: userId,
    });
    if (error) throw error;
    if (!data) throw new Error("Missing recovery result");
    const conflict = teamActionError(data);
    if (conflict) return conflict;
    try {
      const { error: deliveryError } = await createServiceRoleClient(15_000).auth.resetPasswordForEmail(
        data.email, { redirectTo: emailCallbackUrl(request, true) },
      );
      if (deliveryError) return deliveryErrorResponse(deliveryError);
    } catch {
      return deliveryErrorResponse();
    }
    return NextResponse.json({ ok: true, retryAfter: 60 });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

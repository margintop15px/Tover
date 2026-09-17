import { NextRequest, NextResponse } from "next/server";
import { createServiceRoleClient } from "@/lib/supabase-server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

const ALLOWED_ROLES = new Set(["admin", "member"]);

export async function POST(request: NextRequest) {
  try {
    const payload = (await request.json()) as {
      email?: string;
      role?: string;
      workspaceId?: string;
    };

    const email = payload.email?.trim().toLowerCase();
    const role = payload.role?.trim().toLowerCase() || "member";

    if (!email) {
      return NextResponse.json({ error: "email is required" }, { status: 400 });
    }

    if (!ALLOWED_ROLES.has(role)) {
      return NextResponse.json(
        { error: "role must be one of: admin, member" },
        { status: 400 }
      );
    }

    const { supabase, user, workspaceId } = await getRouteContext(request, {
      requireManager: true,
      workspaceId: payload.workspaceId || null,
    });

    const { data: inviteRecord, error: inviteDbError } = await supabase
      .from("organization_invites")
      .insert({
        organization_id: workspaceId,
        email,
        role_id: role,
        invited_by: user.id,
        status: "pending",
      })
      .select("id")
      .single();

    if (inviteDbError || !inviteRecord) {
      return NextResponse.json(
        { error: inviteDbError?.message || "Failed to create invite" },
        { status: 500 }
      );
    }

    const serviceRoleClient = createServiceRoleClient();
    const redirectTo = `${request.nextUrl.origin}/auth/callback`;

    const { error: inviteEmailError } = await serviceRoleClient.auth.admin.inviteUserByEmail(
      email,
      {
        redirectTo,
        data: {
          organization_id: workspaceId,
          organization_role: role,
        },
      }
    );

    let delivery: "invite" | "magiclink" = "invite";
    let deliveryError = inviteEmailError;
    if (inviteEmailError?.code === "email_exists") {
      // Auth accounts are global; workspace membership comes from the pending
      // invitation after the recipient signs in with their verified email.
      delivery = "magiclink";
      const { error } = await serviceRoleClient.auth.signInWithOtp({
        email,
        options: { shouldCreateUser: false, emailRedirectTo: redirectTo },
      });
      deliveryError = error;
    }

    if (deliveryError) {
      await supabase
        .from("organization_invites")
        .update({ status: "revoked" })
        .eq("id", inviteRecord.id);

      return NextResponse.json(
        { error: deliveryError.message },
        { status: deliveryError.status === 429 ? 429 : 500 }
      );
    }

    return NextResponse.json({ ok: true, inviteId: inviteRecord.id, delivery });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

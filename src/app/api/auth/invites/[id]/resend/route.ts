import { NextRequest } from "next/server";
import { toRouteErrorResponse } from "@/lib/request-context";
import { isUuid, runInvitationAction, teamActionError } from "@/lib/team-actions";

export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  try {
    const { id } = await params;
    if (!isUuid(id)) return teamActionError({ code: "NOT_FOUND" })!;
    return await runInvitationAction(request, "resend", { inviteId: id });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

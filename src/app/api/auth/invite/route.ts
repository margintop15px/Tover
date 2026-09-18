import { NextRequest } from "next/server";
import { toRouteErrorResponse } from "@/lib/request-context";
import { isUuid, runInvitationAction, teamActionError } from "@/lib/team-actions";

export async function POST(request: NextRequest) {
  try {
    const body = await request.json().catch(() => null);
    if (!body || typeof body.email !== "string" ||
      (body.role !== undefined && typeof body.role !== "string") ||
      (body.workspaceId !== undefined && !isUuid(body.workspaceId))) {
      return teamActionError({ code: "INVALID_INPUT" })!;
    }
    return await runInvitationAction(request, "create", {
      email: body.email.trim().toLowerCase(), role: body.role?.trim().toLowerCase() ?? "member",
      workspaceId: body.workspaceId,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

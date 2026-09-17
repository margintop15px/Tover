import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { workspaceCookieName, workspaceCookieOptions } from "@/lib/workspace";

export async function POST(request: NextRequest) {
  try {
    const payload = await request.json().catch(() => null);
    const workspaceId = payload?.workspaceId;
    if (typeof workspaceId !== "string" || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(workspaceId)) {
      return NextResponse.json({ error: "A valid workspaceId is required" }, { status: 400 });
    }

    const { user } = await getRouteContext(request, { workspaceId });
    const response = NextResponse.json({ activeWorkspaceId: workspaceId }, {
      headers: { "Cache-Control": "private, no-store" },
    });
    response.cookies.set(workspaceCookieName(user.id), workspaceId, workspaceCookieOptions(
      request.nextUrl.protocol === "https:" || request.headers.get("x-forwarded-proto") === "https"
    ));
    return response;
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

import { NextRequest, NextResponse } from "next/server";
import { createUserServerClient } from "@/lib/supabase-server";
import { initialWorkspaceId, workspaceCookieName, workspaceCookieOptions } from "@/lib/workspace";
import type { AuthMeResponse } from "@/types/auth";

interface MembershipRow {
  organization_id: string;
  role_id: string;
  status: string;
  organizations: { name: string } | Array<{ name: string }> | null;
}

export async function GET(request: NextRequest) {
  try {
    const supabase = await createUserServerClient();
    const {
      data: { user },
      error: userError,
    } = await supabase.auth.getUser();

    if (userError || !user) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    await supabase.rpc("accept_my_organization_invites");

    const [profileResult, membershipsResult] = await Promise.all([
      supabase
        .from("profiles")
        .select("display_name")
        .eq("user_id", user.id)
        .single(),
      supabase
        .from("organization_memberships")
        .select("organization_id, role_id, status, organizations(name)")
        .eq("user_id", user.id)
        .eq("status", "active")
        .order("created_at", { ascending: true })
        .order("organization_id", { ascending: true }),
    ]);

    if (membershipsResult.error) {
      return NextResponse.json(
        { error: membershipsResult.error.message },
        { status: 500 }
      );
    }

    const memberships = (membershipsResult.data || []) as MembershipRow[];
    const cookieName = workspaceCookieName(user.id);
    const savedId = request.cookies.get(cookieName)?.value;
    const activeWorkspaceId = initialWorkspaceId(memberships, savedId);

    const payload: AuthMeResponse = {
      user: {
        id: user.id,
        email: user.email ?? null,
      },
      profile: {
        displayName: profileResult.data?.display_name || null,
      },
      memberships: memberships.map((item) => ({
        organizationId: item.organization_id,
        organizationName: (Array.isArray(item.organizations) ? item.organizations[0] : item.organizations)?.name || "",
        role: item.role_id,
      })),
      activeWorkspaceId,
    };
    const response = NextResponse.json(payload, { headers: { "Cache-Control": "private, no-store" } });
    if (activeWorkspaceId && activeWorkspaceId !== savedId) {
      response.cookies.set(cookieName, activeWorkspaceId, workspaceCookieOptions(
        request.nextUrl.protocol === "https:" || request.headers.get("x-forwarded-proto") === "https"
      ));
    } else if (!activeWorkspaceId && savedId) {
      response.cookies.delete(cookieName);
    }
    return response;
  } catch (error) {
    console.error("Auth me error:", error);
    return NextResponse.json(
      { error: "Internal server error" },
      { status: 500 }
    );
  }
}

export async function PATCH(request: NextRequest) {
  try {
    const supabase = await createUserServerClient();
    const {
      data: { user },
      error: userError,
    } = await supabase.auth.getUser();

    if (userError || !user) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const body = (await request.json()) as { displayName?: string };
    const displayName = body.displayName?.trim();

    if (!displayName) {
      return NextResponse.json(
        { error: "displayName is required" },
        { status: 400 }
      );
    }

    const { error } = await supabase.from("profiles").upsert({
      user_id: user.id,
      display_name: displayName,
      updated_at: new Date().toISOString(),
    });

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({ ok: true });
  } catch (error) {
    console.error("Update profile error:", error);
    return NextResponse.json(
      { error: "Internal server error" },
      { status: 500 }
    );
  }
}

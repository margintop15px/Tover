import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type { TeamResponse } from "@/types/team";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, { requireManager: true });
    const { data, error } = await supabase.rpc("workspace_team", { p_workspace_id: workspaceId });
    if (error) throw error;
    return NextResponse.json(data as TeamResponse, { headers: { "Cache-Control": "private, no-store" } });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

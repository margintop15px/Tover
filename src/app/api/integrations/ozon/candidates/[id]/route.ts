import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import {
  applyItemMapping,
  getOzonCandidateOperation,
  normalizeOzonCandidateOperation,
  revalidateAndUpdateCandidate,
  type MarketplaceCandidateRow,
  type OzonCandidateOperation,
} from "@/lib/ozon/candidates";

export const dynamic = "force-dynamic";

export async function PATCH(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params;
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });
    const body = (await request.json()) as {
      action?: "ignore" | "unignore";
      operationDate?: string | null;
      itemIndex?: number;
      productId?: string | null;
      warehouseId?: string | null;
    };

    const candidate = await loadCandidate(supabase, workspaceId, id);
    if (!candidate) {
      return NextResponse.json({ error: "Candidate not found" }, { status: 404 });
    }

    if (candidate.status === "committed") {
      return NextResponse.json(
        { error: "Committed candidates cannot be edited" },
        { status: 409 }
      );
    }
    if (candidate.status === "committing") {
      return NextResponse.json(
        { error: "Committing candidates cannot be edited" },
        { status: 409 }
      );
    }

    if (body.action === "ignore") {
      const { data, error } = await supabase
        .from("marketplace_operation_candidates")
        .update({ status: "ignored" })
        .eq("id", candidate.id)
        .eq("workspace_id", workspaceId)
        .select("*")
        .single();
      if (error) {
        return NextResponse.json({ error: error.message }, { status: 500 });
      }
      return NextResponse.json({ candidate: data });
    }

    if (body.action === "unignore") {
      const updated = await revalidateAndUpdateCandidate(
        supabase,
        candidate,
        getOzonCandidateOperation(candidate)
      );
      return NextResponse.json({ candidate: updated });
    }

    let updatedCandidate = candidate;

    if (body.productId !== undefined || body.warehouseId !== undefined) {
      if (typeof body.itemIndex !== "number") {
        return NextResponse.json(
          { error: "itemIndex is required for item mapping updates" },
          { status: 400 }
        );
      }

      if (body.productId) {
        const validProduct = await ensureProduct(supabase, workspaceId, body.productId);
        if (!validProduct) {
          return NextResponse.json({ error: "Product not found" }, { status: 404 });
        }
      }
      if (body.warehouseId) {
        const validWarehouse = await ensureWarehouse(
          supabase,
          workspaceId,
          body.warehouseId
        );
        if (!validWarehouse) {
          return NextResponse.json({ error: "Warehouse not found" }, { status: 404 });
        }
      }

      const mappingPatch: {
        productId?: string | null;
        warehouseId?: string | null;
      } = {};
      if (body.productId !== undefined) mappingPatch.productId = body.productId;
      if (body.warehouseId !== undefined) {
        mappingPatch.warehouseId = body.warehouseId;
      }

      updatedCandidate = await applyItemMapping(
        supabase,
        updatedCandidate,
        body.itemIndex,
        mappingPatch
      );
    }

    if (body.operationDate !== undefined) {
      const operation = normalizeOzonCandidateOperation(
        getOzonCandidateOperation(updatedCandidate)
      ) as OzonCandidateOperation;
      operation.operationDate = body.operationDate || null;
      updatedCandidate = await revalidateAndUpdateCandidate(
        supabase,
        updatedCandidate,
        operation
      );
    }

    return NextResponse.json({ candidate: updatedCandidate });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

async function loadCandidate(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  workspaceId: string,
  id: string
) {
  const { data, error } = await supabase
    .from("marketplace_operation_candidates")
    .select("*")
    .eq("workspace_id", workspaceId)
    .eq("provider", "ozon")
    .eq("id", id)
    .maybeSingle();

  if (error) throw new Error(error.message);
  return data as MarketplaceCandidateRow | null;
}

async function ensureProduct(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  workspaceId: string,
  productId: string
) {
  const { data, error } = await supabase
    .from("products")
    .select("id")
    .eq("workspace_id", workspaceId)
    .eq("id", productId)
    .maybeSingle();
  if (error) throw new Error(error.message);
  return Boolean(data);
}

async function ensureWarehouse(
  supabase: Awaited<ReturnType<typeof getRouteContext>>["supabase"],
  workspaceId: string,
  warehouseId: string
) {
  const { data, error } = await supabase
    .from("warehouses")
    .select("id")
    .eq("workspace_id", workspaceId)
    .eq("id", warehouseId)
    .maybeSingle();
  if (error) throw new Error(error.message);
  return Boolean(data);
}

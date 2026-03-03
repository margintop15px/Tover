import type { SupabaseClient } from "@supabase/supabase-js";

export async function updateProductBalance(
  supabase: SupabaseClient,
  workspaceId: string,
  productId: string,
  warehouseId: string,
  qtyDelta: number,
  newUnitCost?: number
) {
  const { data, error } = await supabase.rpc("update_product_balance", {
    p_workspace_id: workspaceId,
    p_product_id: productId,
    p_warehouse_id: warehouseId,
    p_qty_delta: qtyDelta,
    p_new_unit_cost: newUnitCost ?? null,
  });

  if (error) throw new Error(`Balance update failed: ${error.message}`);
  return data;
}

export async function processPurchaseBalance(
  supabase: SupabaseClient,
  workspaceId: string,
  productId: string,
  warehouseId: string,
  purchaseQty: number,
  purchaseUnitPrice: number
) {
  const { data, error } = await supabase.rpc("process_purchase_balance", {
    p_workspace_id: workspaceId,
    p_product_id: productId,
    p_warehouse_id: warehouseId,
    p_purchase_qty: purchaseQty,
    p_purchase_unit_price: purchaseUnitPrice,
  });

  if (error)
    throw new Error(`Purchase balance update failed: ${error.message}`);
  return data;
}

export async function processProductionBalances(
  supabase: SupabaseClient,
  workspaceId: string,
  sources: { product_id: string; warehouse_id: string; quantity: number }[],
  output: { product_id: string; warehouse_id: string; quantity: number }
) {
  const { data, error } = await supabase.rpc("process_production_balances", {
    p_workspace_id: workspaceId,
    p_sources: sources,
    p_output: output,
  });

  if (error)
    throw new Error(`Production balance update failed: ${error.message}`);
  return data;
}

export async function getProductBalance(
  supabase: SupabaseClient,
  workspaceId: string,
  productId: string,
  warehouseId: string
): Promise<{ quantity: number; unit_cost: number } | null> {
  const { data } = await supabase
    .from("product_balances")
    .select("quantity, unit_cost")
    .eq("workspace_id", workspaceId)
    .eq("product_id", productId)
    .eq("warehouse_id", warehouseId)
    .single();

  return data;
}

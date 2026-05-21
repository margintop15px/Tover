import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";

export async function processInventoryAdjustment(
  supabase: SupabaseClient,
  workspaceId: string,
  data: ValidatedOperation
) {
  const { data: operation, error: opError } = await supabase
    .from("operations")
    .insert({
      workspace_id: workspaceId,
      type: data.type,
      operation_date: data.operationDate,
      comment: data.comment || null,
      supplier_id: null,
    })
    .select()
    .single();

  if (opError) throw new Error(`Failed to create operation: ${opError.message}`);

  const itemInserts = data.items.map((item) => ({
    operation_id: operation.id,
    product_id: item.productId,
    warehouse_id: item.warehouseId,
    quantity: item.quantity,
    unit_price: item.unitPrice || null,
    direction: "in" as const,
    store_id: item.storeId || null,
    quality_status: item.qualityStatus || "ordinary",
  }));

  const { error: itemError } = await supabase
    .from("operation_items")
    .insert(itemInserts);

  if (itemError)
    throw new Error(`Failed to create operation items: ${itemError.message}`);

  return operation;
}

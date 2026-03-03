import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";
import { updateProductBalance } from "./update-balances";

/**
 * Shared processor for sale, return, and write_off operations.
 * They all follow the same pattern: insert operation + items, then update balances.
 * The direction and sign are already set in the validated data.
 */
export async function processSimpleItemOperation(
  supabase: SupabaseClient,
  workspaceId: string,
  data: ValidatedOperation
) {
  // Insert operation
  const { data: operation, error: opError } = await supabase
    .from("operations")
    .insert({
      workspace_id: workspaceId,
      type: data.type,
      operation_date: data.operationDate,
      comment: data.comment || null,
      supplier_id: data.supplierId || null,
    })
    .select()
    .single();

  if (opError) throw new Error(`Failed to create operation: ${opError.message}`);

  // Insert items
  const itemInserts = data.items.map((item) => ({
    operation_id: operation.id,
    product_id: item.productId,
    warehouse_id: item.warehouseId,
    quantity: item.quantity,
    unit_price: item.unitPrice || null,
    direction: item.direction!,
    store_id: item.storeId || null,
  }));

  const { error: itemError } = await supabase
    .from("operation_items")
    .insert(itemInserts);

  if (itemError)
    throw new Error(`Failed to create operation items: ${itemError.message}`);

  // Update balances
  for (const item of data.items) {
    const delta = item.direction === "in" ? item.quantity : -item.quantity;
    await updateProductBalance(
      supabase,
      workspaceId,
      item.productId,
      item.warehouseId,
      delta
    );
  }

  return operation;
}

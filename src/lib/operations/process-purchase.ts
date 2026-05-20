import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";
import { processPurchaseBalance } from "./update-balances";

export async function processPurchase(
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
    direction: "in" as const,
    store_id: item.storeId || null,
  }));

  const { error: itemError } = await supabase
    .from("operation_items")
    .insert(itemInserts);

  if (itemError)
    throw new Error(`Failed to create operation items: ${itemError.message}`);

  // Cost is a per-warehouse balance value, not a product attribute.
  // Purchases recalculate it from the current balance and purchase unit price.
  for (const item of data.items) {
    await processPurchaseBalance(
      supabase,
      workspaceId,
      item.productId,
      item.warehouseId,
      item.quantity,
      item.unitPrice!
    );
  }

  return operation;
}

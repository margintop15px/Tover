import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";
import { getProductBalance } from "./update-balances";

export async function processDefect(
  supabase: SupabaseClient,
  workspaceId: string,
  data: ValidatedOperation
) {
  const outItem = data.items[0]; // The source item

  // Read source cost
  const sourceBalance = await getProductBalance(
    supabase,
    workspaceId,
    outItem.productId,
    outItem.warehouseId,
    "ordinary"
  );
  const unitCost = sourceBalance?.unit_cost ?? 0;

  // Insert operation
  const { data: operation, error: opError } = await supabase
    .from("operations")
    .insert({
      workspace_id: workspaceId,
      type: data.type,
      operation_date: data.operationDate,
      comment: data.comment || null,
    })
    .select()
    .single();

  if (opError) throw new Error(`Failed to create operation: ${opError.message}`);

  // Insert items: ordinary stock out, same product enters defect quality status.
  const { error: itemError } = await supabase
    .from("operation_items")
    .insert([
      {
        operation_id: operation.id,
        product_id: outItem.productId,
        warehouse_id: outItem.warehouseId,
        quantity: outItem.quantity,
        unit_price: unitCost,
        direction: "out",
        store_id: outItem.storeId || null,
        quality_status: "ordinary",
      },
      {
        operation_id: operation.id,
        product_id: outItem.productId,
        warehouse_id: outItem.warehouseId,
        quantity: outItem.quantity,
        unit_price: unitCost,
        direction: "in",
        store_id: outItem.storeId || null,
        quality_status: "defect",
      },
    ]);

  if (itemError)
    throw new Error(`Failed to create operation items: ${itemError.message}`);

  return operation;
}

import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";
import { updateProductBalance, getProductBalance } from "./update-balances";

export async function processTransfer(
  supabase: SupabaseClient,
  workspaceId: string,
  data: ValidatedOperation
) {
  const outItem = data.items.find((i) => i.direction === "out")!;
  const inItem = data.items.find((i) => i.direction === "in")!;

  // Read source cost before updating
  const sourceBalance = await getProductBalance(
    supabase,
    workspaceId,
    outItem.productId,
    outItem.warehouseId
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

  // Insert items
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
      },
      {
        operation_id: operation.id,
        product_id: inItem.productId,
        warehouse_id: inItem.warehouseId,
        quantity: inItem.quantity,
        unit_price: unitCost,
        direction: "in",
      },
    ]);

  if (itemError)
    throw new Error(`Failed to create operation items: ${itemError.message}`);

  // Decrease source, increase destination (same cost)
  await updateProductBalance(
    supabase,
    workspaceId,
    outItem.productId,
    outItem.warehouseId,
    -outItem.quantity
  );
  await updateProductBalance(
    supabase,
    workspaceId,
    inItem.productId,
    inItem.warehouseId,
    inItem.quantity,
    unitCost
  );

  return operation;
}

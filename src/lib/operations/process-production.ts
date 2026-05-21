import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";

export async function processProduction(
  supabase: SupabaseClient,
  workspaceId: string,
  data: ValidatedOperation
) {
  const outItems = data.items.filter((i) => i.direction === "out");
  const inItem = data.items.find((i) => i.direction === "in")!;

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

  // Insert all items
  const allItems = [
    ...outItems.map((item) => ({
      operation_id: operation.id,
      product_id: item.productId,
      warehouse_id: item.warehouseId,
      quantity: item.quantity,
      unit_price: item.unitPrice || null,
      direction: "out" as const,
      store_id: item.storeId || null,
      quality_status: item.qualityStatus || "ordinary",
    })),
    {
      operation_id: operation.id,
      product_id: inItem.productId,
      warehouse_id: inItem.warehouseId,
      quantity: inItem.quantity,
      unit_price: inItem.unitPrice || null,
      direction: "in" as const,
      store_id: inItem.storeId || null,
      quality_status: inItem.qualityStatus || "ordinary",
    },
  ];

  const { error: itemError } = await supabase
    .from("operation_items")
    .insert(allItems);

  if (itemError)
    throw new Error(`Failed to create operation items: ${itemError.message}`);

  // Optionally update output product's store_id
  if (inItem.storeId) {
    await supabase
      .from("products")
      .update({ store_id: inItem.storeId })
      .eq("id", inItem.productId)
      .eq("workspace_id", workspaceId);
  }

  return operation;
}

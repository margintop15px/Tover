import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";
import { updateProductBalance, getProductBalance } from "./update-balances";

export async function processDefect(
  supabase: SupabaseClient,
  workspaceId: string,
  data: ValidatedOperation
) {
  const outItem = data.items[0]; // The source item

  // Find or create defect product copy
  const { data: originalProduct } = await supabase
    .from("products")
    .select("id, name, sku_code")
    .eq("id", outItem.productId)
    .single();

  if (!originalProduct)
    throw new Error("Original product not found");

  const defectName = `.${originalProduct.name}`;

  // Check if defect copy already exists
  const defectProduct = await supabase
    .from("products")
    .select("id")
    .eq("workspace_id", workspaceId)
    .eq("is_defect_copy", true)
    .eq("original_product_id", originalProduct.id)
    .single();

  let defectProductId: string;

  if (defectProduct.data) {
    defectProductId = defectProduct.data.id;
  } else {
    // Create defect copy
    const { data: newDefect, error: defectErr } = await supabase
      .from("products")
      .insert({
        workspace_id: workspaceId,
        name: defectName,
        sku_code: originalProduct.sku_code
          ? `.${originalProduct.sku_code}`
          : null,
        is_defect_copy: true,
        original_product_id: originalProduct.id,
      })
      .select()
      .single();

    if (defectErr)
      throw new Error(`Failed to create defect product: ${defectErr.message}`);
    defectProductId = newDefect.id;
  }

  // Find default defect warehouse
  const { data: defectWarehouse } = await supabase
    .from("warehouses")
    .select("id")
    .eq("workspace_id", workspaceId)
    .eq("is_default_defect", true)
    .single();

  if (!defectWarehouse)
    throw new Error("Default defect warehouse not found");

  // Read source cost
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

  // Insert items: out from source, in to defect warehouse
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
        product_id: defectProductId,
        warehouse_id: defectWarehouse.id,
        quantity: outItem.quantity,
        unit_price: unitCost,
        direction: "in",
      },
    ]);

  if (itemError)
    throw new Error(`Failed to create operation items: ${itemError.message}`);

  // Update balances
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
    defectProductId,
    defectWarehouse.id,
    outItem.quantity,
    unitCost
  );

  return operation;
}

import type { SupabaseClient } from "@supabase/supabase-js";
import type { ValidatedOperation } from "./validate-operation";

export async function processPayment(
  supabase: SupabaseClient,
  workspaceId: string,
  data: ValidatedOperation
) {
  const { data: operation, error } = await supabase
    .from("operations")
    .insert({
      workspace_id: workspaceId,
      type: data.type,
      operation_date: data.operationDate,
      comment: data.comment || null,
      supplier_id: data.supplierId || null,
      payment_amount: data.paymentAmount || null,
    })
    .select()
    .single();

  if (error) throw new Error(`Failed to create operation: ${error.message}`);

  return operation;
}

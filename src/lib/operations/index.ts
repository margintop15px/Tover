import type { SupabaseClient } from "@supabase/supabase-js";
import type { CreateOperationRequest } from "@/types/inventory";
import { validateOperation } from "./validate-operation";
import { processPayment } from "./process-payment";
import { processSimpleItemOperation } from "./process-simple";
import { processPurchase } from "./process-purchase";
import { processTransfer } from "./process-transfer";
import { processDefect } from "./process-defect";
import { processProduction } from "./process-production";

export { validateOperation } from "./validate-operation";

export async function processOperation(
  supabase: SupabaseClient,
  workspaceId: string,
  body: CreateOperationRequest
) {
  const result = validateOperation(body);

  if (result.errors) {
    return { errors: result.errors, operation: null };
  }

  const data = result.data;

  let operation;

  switch (data.type) {
    case "payment":
      operation = await processPayment(supabase, workspaceId, data);
      break;
    case "purchase":
      operation = await processPurchase(supabase, workspaceId, data);
      break;
    case "sale":
    case "return":
    case "write_off":
      operation = await processSimpleItemOperation(supabase, workspaceId, data);
      break;
    case "transfer":
      operation = await processTransfer(supabase, workspaceId, data);
      break;
    case "defect":
      operation = await processDefect(supabase, workspaceId, data);
      break;
    case "production":
      operation = await processProduction(supabase, workspaceId, data);
      break;
  }

  return { errors: null, operation };
}

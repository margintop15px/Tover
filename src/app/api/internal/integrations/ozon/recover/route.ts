import { NextRequest, NextResponse } from "next/server";
import { recoverOneOzonSyncStep } from "@/lib/ozon/durable-runner";
import { enqueueDueOzonSyncRuns } from "@/lib/ozon/durable-sync";
import {
  handleOzonRecoveryRequest,
  OZON_RECOVERY_STEP_BUDGET_MS,
} from "@/lib/ozon/recovery";
import { createServiceRoleClient } from "@/lib/supabase-server";

export const dynamic = "force-dynamic";
export const maxDuration = 110;

interface OzonRecoveryPostOperations {
  configuredSecret: () => string | undefined;
  enqueueDue: () => Promise<unknown>;
  recoverOne: (deadlineMs: number) => Promise<boolean>;
  now: () => number;
}

const productionOperations: OzonRecoveryPostOperations = {
  configuredSecret: () => process.env.OZON_SYNC_RECOVERY_SECRET,
  enqueueDue: () => enqueueDueOzonSyncRuns(createServiceRoleClient()),
  recoverOne: recoverOneOzonSyncStep,
  now: Date.now,
};

export function createOzonRecoveryPostHandler(
  operations: OzonRecoveryPostOperations = productionOperations
) {
  return async function ozonRecoveryPost(request: NextRequest) {
    const result = await handleOzonRecoveryRequest({
      configuredSecret: operations.configuredSecret(),
      providedSecret:
        request.headers.get("x-tover-recovery-secret") ?? undefined,
      deadlineMs: operations.now() + OZON_RECOVERY_STEP_BUDGET_MS,
      enqueueDue: operations.enqueueDue,
      recoverOne: operations.recoverOne,
    });
    return NextResponse.json(result.body, { status: result.status });
  };
}

const productionPost = createOzonRecoveryPostHandler();

export async function POST(request: NextRequest) {
  return productionPost(request);
}

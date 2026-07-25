import { NextRequest, NextResponse } from "next/server";
import { recoverOneOzonSyncStep } from "@/lib/ozon/durable-runner";
import { handleOzonRecoveryRequest } from "@/lib/ozon/recovery";

export const dynamic = "force-dynamic";
export const maxDuration = 110;

interface OzonRecoveryPostOperations {
  configuredSecret: () => string | undefined;
  recoverOne: () => Promise<boolean>;
}

const productionOperations: OzonRecoveryPostOperations = {
  configuredSecret: () => process.env.OZON_SYNC_RECOVERY_SECRET,
  recoverOne: recoverOneOzonSyncStep,
};

export function createOzonRecoveryPostHandler(
  operations: OzonRecoveryPostOperations = productionOperations
) {
  return async function ozonRecoveryPost(request: NextRequest) {
    const result = await handleOzonRecoveryRequest({
      configuredSecret: operations.configuredSecret(),
      providedSecret:
        request.headers.get("x-tover-recovery-secret") ?? undefined,
      recoverOne: operations.recoverOne,
    });
    return NextResponse.json(result.body, { status: result.status });
  };
}

const productionPost = createOzonRecoveryPostHandler();

export async function POST(request: NextRequest) {
  return productionPost(request);
}

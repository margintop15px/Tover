import { NextRequest, NextResponse } from "next/server";
import { recoverOneOzonSyncStep } from "@/lib/ozon/durable-runner";
import { handleOzonRecoveryRequest } from "@/lib/ozon/recovery";

export const dynamic = "force-dynamic";
export const maxDuration = 110;

export async function POST(request: NextRequest) {
  const result = await handleOzonRecoveryRequest({
    configuredSecret: process.env.OZON_SYNC_RECOVERY_SECRET,
    providedSecret: request.headers.get("x-tover-recovery-secret") ?? undefined,
    recoverOne: recoverOneOzonSyncStep,
  });
  return NextResponse.json(result.body, { status: result.status });
}

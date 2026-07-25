import { createHash, timingSafeEqual } from "node:crypto";

interface RecoveryRequestInput {
  configuredSecret: string | undefined;
  providedSecret: string | undefined;
  recoverOne: () => Promise<boolean>;
}

type RecoveryResponse =
  | { status: 200; body: { processed: boolean } }
  | { status: 401 | 500 | 503; body: { error: string } };

export const OZON_RECOVERY_MAX_DURATION_SECONDS = 110;

export function recoverySecretsMatch(
  providedSecret: string | undefined,
  configuredSecret: string | undefined
) {
  if (!providedSecret || !configuredSecret) return false;
  const providedDigest = createHash("sha256").update(providedSecret).digest();
  const configuredDigest = createHash("sha256").update(configuredSecret).digest();
  return timingSafeEqual(providedDigest, configuredDigest);
}

export async function handleOzonRecoveryRequest(
  input: RecoveryRequestInput
): Promise<RecoveryResponse> {
  if (!input.configuredSecret) {
    return {
      status: 503,
      body: { error: "Recovery unavailable" },
    };
  }
  if (!recoverySecretsMatch(input.providedSecret, input.configuredSecret)) {
    return {
      status: 401,
      body: { error: "Unauthorized" },
    };
  }

  try {
    return {
      status: 200,
      body: { processed: await input.recoverOne() },
    };
  } catch {
    return {
      status: 500,
      body: { error: "Recovery failed" },
    };
  }
}

import type { OzonIntegrationSummary } from "./OzonSummaryShared";

export type OzonRecoveryAction = "sync" | "resume" | "retry_failed";

type TimerHandle = ReturnType<typeof setTimeout>;

interface OzonSummaryPollingOptions {
  loadSummary: (signal: AbortSignal) => Promise<OzonIntegrationSummary>;
  onSummary: (summary: OzonIntegrationSummary) => void;
  onError?: (error: unknown) => void;
  intervalMs?: number;
  setTimer?: (callback: () => void | Promise<void>, delay: number) => TimerHandle;
  clearTimer?: (handle: TimerHandle) => void;
}

export function isOzonRecoveryActive(
  summary: OzonIntegrationSummary | null
) {
  const status = summary?.recovery?.status;
  return status === "running" || status === "retrying";
}

export function getOzonRecoveryAction(
  summary: OzonIntegrationSummary | null
): OzonRecoveryAction {
  if (isOzonRecoveryActive(summary)) return "resume";

  const recovery = summary?.recovery;
  if (
    recovery &&
    recovery.failedStepCount > 0 &&
    (recovery.status === "completed_with_errors" ||
      recovery.status === "failed")
  ) {
    return "retry_failed";
  }
  return "sync";
}

export function getOzonRecoveryRequest(
  action: OzonRecoveryAction,
  runId: string | null
) {
  if (action === "retry_failed" || action === "resume") {
    return {
      endpoint: "/api/integrations/ozon/sync/retry",
      body: { runId },
    };
  }
  return {
    endpoint: "/api/integrations/ozon/sync",
    body: {},
  };
}

export function startOzonSummaryPolling({
  loadSummary,
  onSummary,
  onError = () => {},
  intervalMs = 10_000,
  setTimer = (callback, delay) => setTimeout(callback, delay),
  clearTimer = clearTimeout,
}: OzonSummaryPollingOptions) {
  let stopped = false;
  let timer: TimerHandle | null = null;
  let request: AbortController | null = null;

  const schedule = () => {
    if (stopped) return;
    timer = setTimer(run, intervalMs);
  };

  const run = async () => {
    timer = null;
    if (stopped) return;

    request = new AbortController();
    try {
      const summary = await loadSummary(request.signal);
      if (stopped) return;
      onSummary(summary);
      if (isOzonRecoveryActive(summary)) schedule();
    } catch (error) {
      if (stopped) return;
      onError(error);
      schedule();
    } finally {
      request = null;
    }
  };

  schedule();

  return () => {
    stopped = true;
    if (timer !== null) clearTimer(timer);
    timer = null;
    request?.abort();
    request = null;
  };
}

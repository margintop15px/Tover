import assert from "node:assert/strict";
import test from "node:test";
import {
  getOzonRecoveryAction,
  getOzonRecoveryRequest,
  startOzonSummaryPolling,
} from "../../src/components/ozon/OzonRecoveryUi";
import {
  ozonSyncStatusLabel,
  type OzonIntegrationSummary,
} from "../../src/components/ozon/OzonSummaryShared";
import { en } from "../../src/i18n/en";
import { ru } from "../../src/i18n/ru";

function summary(
  status: NonNullable<OzonIntegrationSummary["recovery"]>["status"],
  failedStepCount = 0
): OzonIntegrationSummary {
  return {
    connected: true,
    connection: {
      id: "connection-1",
      name: "Ozon",
      status: "connected",
      clientIdHint: null,
      apiKeyHint: null,
      lastValidatedAt: null,
      lastSyncAt: null,
      lastSyncStatus: status,
      lastSyncError: null,
    },
    counts: {
      products: 7,
      unmappedProducts: 0,
      warehouses: 2,
      unmappedWarehouses: 0,
      postings: 0,
      returns: 0,
      financeTransactions: 0,
      legalEntitySales: 0,
      unpaidLegalProducts: 0,
      financeReports: 0,
      removals: 0,
      supplies: 0,
      stockAnalytics: 0,
      discountedProducts: 0,
      candidatesReady: 0,
      candidatesNeedsMapping: 0,
    },
    recovery: {
      runId: "run-1",
      status,
      pendingStepCount: 0,
      scheduledRetryCount: status === "retrying" ? 1 : 0,
      failedStepCount,
      nextRetryAt: status === "retrying" ? "2026-07-26T10:00:00.000Z" : null,
      lastError: status === "retrying" ? "finance: Ozon sync step failed (server, HTTP 500)" : null,
    },
  };
}

test("retrying status and recovery controls are localized in English and Russian", () => {
  assert.equal(ozonSyncStatusLabel("retrying", en), "Retrying automatically");
  assert.equal(
    ozonSyncStatusLabel("retrying", ru),
    "Автоматическая повторная попытка"
  );
  assert.equal(en.ozonRetryNow, "Retry now");
  assert.equal(ru.ozonRetryNow, "Повторить сейчас");
  assert.equal(en.ozonRetryFailedSteps, "Retry failed steps");
  assert.equal(ru.ozonRetryFailedSteps, "Повторить неудачные шаги");
});

test("active recovery resumes through the normal sync route", () => {
  const action = getOzonRecoveryAction(summary("retrying"));
  assert.equal(action, "resume");
  assert.deepEqual(getOzonRecoveryRequest(action, "run-1"), {
    endpoint: "/api/integrations/ozon/sync",
    body: {},
  });
});

test("terminal failure retries failed steps in the same run", () => {
  const action = getOzonRecoveryAction(summary("completed_with_errors", 2));
  assert.equal(action, "retry_failed");
  assert.deepEqual(getOzonRecoveryRequest(action, "run-1"), {
    endpoint: "/api/integrations/ozon/sync/retry",
    body: { runId: "run-1" },
  });
});

test("polling waits ten seconds and stops after a terminal summary", async () => {
  const timers: Array<() => void | Promise<void>> = [];
  const delays: number[] = [];
  const received: OzonIntegrationSummary[] = [];

  const stop = startOzonSummaryPolling({
    loadSummary: async () => summary("completed"),
    onSummary: (value) => received.push(value),
    setTimer: (callback, delay) => {
      timers.push(callback);
      delays.push(delay);
      return timers.length;
    },
    clearTimer: () => {},
  });

  assert.deepEqual(delays, [10_000]);
  await timers[0]();
  assert.equal(received.length, 1);
  assert.equal(received[0].counts.products, 7);
  assert.equal(timers.length, 1);
  stop();
});

test("polling cleanup aborts work and ignores a late response", async () => {
  let resolveLoad!: (value: OzonIntegrationSummary) => void;
  const load = new Promise<OzonIntegrationSummary>((resolve) => {
    resolveLoad = resolve;
  });
  let timerCallback!: () => void | Promise<void>;
  let signal: AbortSignal | undefined;
  const received: OzonIntegrationSummary[] = [];

  const stop = startOzonSummaryPolling({
    loadSummary: async (nextSignal) => {
      signal = nextSignal;
      return load;
    },
    onSummary: (value) => received.push(value),
    setTimer: (callback) => {
      timerCallback = callback;
      return "timer-1";
    },
    clearTimer: () => {},
  });

  const tick = timerCallback();
  stop();
  resolveLoad(summary("completed"));
  await tick;

  assert.equal(signal?.aborted, true);
  assert.deepEqual(received, []);
});

test("polling cleanup clears a pending timer", () => {
  const cleared: unknown[] = [];
  const stop = startOzonSummaryPolling({
    loadSummary: async () => summary("completed"),
    onSummary: () => {},
    setTimer: () => "timer-1",
    clearTimer: (handle) => cleared.push(handle),
  });

  stop();
  assert.deepEqual(cleared, ["timer-1"]);
});

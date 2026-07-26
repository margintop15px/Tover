import assert from "node:assert/strict";
import test from "node:test";

import {
  OzonApiError,
  OzonClient,
  OzonInvariantError,
} from "../../src/lib/ozon/client";
import {
  PermanentOzonSyncError,
  classifyOzonSyncError,
  durableOzonRunnerTestSeam,
  durableRetryDelayMs,
  type ClaimedOzonSyncStep,
  type DurableOzonRunnerDependencies,
  type FinishOzonSyncStepInput,
} from "../../src/lib/ozon/durable-runner";
import * as sync from "../../src/lib/ozon/sync";

const TEST_DEADLINE_MS = Date.parse("2026-07-26T10:01:40.000Z");
const executionDeadline = () => ({
  deadlineMs: TEST_DEADLINE_MS,
  signal: new AbortController().signal,
});

test("durable domain registry follows the persisted step order", () => {
  const registry = (sync as unknown as {
    OZON_SYNC_DOMAIN_REGISTRY?: ReadonlyArray<{ key: string }>;
  }).OZON_SYNC_DOMAIN_REGISTRY;

  assert.deepEqual(
    registry?.map(({ key }) => key),
    [
      "warehouses",
      "products",
      "stocks",
      "postings",
      "returns",
      "finance",
      "legalEntities",
      "reports",
      "removals",
      "supplies",
      "analytics",
      "discountedProducts",
    ]
  );
  assert.equal(
    registry?.every(
      (entry) =>
        typeof (entry as { execute?: unknown }).execute === "function"
    ),
    true
  );
});

test("durable retry schedule uses the claimed attempt count exactly", () => {
  assert.deepEqual(
    [1, 2, 3, 4, 5, 6, 7, 8].map((attempt) => durableRetryDelayMs(attempt)),
    [
      60_000,
      5 * 60_000,
      15 * 60_000,
      60 * 60_000,
      3 * 60 * 60_000,
      6 * 60 * 60_000,
      12 * 60 * 60_000,
      null,
    ]
  );
});

test("error classifier marks transport, timeout, exact retryable Ozon statuses, and runtime failures transient", () => {
  const cases: Array<{
    error: unknown;
    kind: string;
    status?: number;
    retryAfterMs?: number;
  }> = [
    { error: new TypeError("fetch failed"), kind: "transport" },
    { error: Object.assign(new Error("timed out"), { name: "TimeoutError" }), kind: "timeout" },
    { error: new OzonApiError("/v2/warehouse/list", 408, {}), kind: "timeout", status: 408 },
    { error: new OzonApiError("/v2/warehouse/list", 425, {}), kind: "client", status: 425 },
    {
      error: new OzonApiError(
        "/v2/warehouse/list",
        429,
        {},
        { requestId: "ignored", retryAfterMs: 12_000, itemRetryAfterMs: null }
      ),
      kind: "rate_limit",
      status: 429,
      retryAfterMs: 12_000,
    },
    {
      error: new OzonApiError("/v2/warehouse/list", 500, {}),
      kind: "server",
      status: 500,
    },
    {
      error: new OzonApiError("/v2/warehouse/list", 503, {}),
      kind: "server",
      status: 503,
    },
    {
      error: new OzonApiError("/v2/warehouse/list", 599, {}),
      kind: "server",
      status: 599,
    },
    { error: new Error("database unavailable"), kind: "unknown" },
  ];

  for (const item of cases) {
    assert.deepEqual(classifyOzonSyncError(item.error), {
      retryable: true,
      persistedError: {
        message: "Ozon sync step failed",
        kind: item.kind,
        ...(item.status === undefined ? {} : { status: item.status }),
        ...(item.retryAfterMs === undefined
          ? {}
          : { retryAfterMs: item.retryAfterMs }),
        retryable: true,
      },
    });
  }
});

test("error classifier marks explicit config errors and every other Ozon HTTP status permanent", () => {
  const cases: unknown[] = [
    new PermanentOzonSyncError("connection disabled"),
    new OzonApiError("/v2/warehouse/list", 400, { response: "ignored" }),
    new OzonApiError("/v2/warehouse/list", 401, { response: "ignored" }),
    new OzonApiError("/v2/warehouse/list", 403, { response: "ignored" }),
    new OzonApiError("/v2/warehouse/list", 404, { response: "ignored" }),
    new OzonApiError("/v2/warehouse/list", 409, { response: "ignored" }),
    new OzonApiError("/v2/warehouse/list", 422, { response: "ignored" }),
    new OzonApiError("/v2/warehouse/list", 499, { response: "ignored" }),
  ];

  assert.deepEqual(
    cases.map((error) => classifyOzonSyncError(error)),
    [
      permanentClassification(),
      permanentClassification(400),
      permanentClassification(401),
      permanentClassification(403),
      permanentClassification(404),
      permanentClassification(409),
      permanentClassification(422),
      permanentClassification(499),
    ]
  );
});

test("typed Ozon client allowlist invariant is classified permanently without matching its message", async () => {
  const client = new OzonClient({ clientId: "client", apiKey: "key" });
  let thrown: unknown;

  try {
    await (
      client.request as unknown as (
        endpoint: string
      ) => Promise<Record<string, unknown>>
    )("/not-allowlisted");
  } catch (error) {
    thrown = error;
  }

  assert.ok(thrown instanceof OzonInvariantError);
  assert.equal(
    (thrown as Error).message,
    "Ozon endpoint is not allowlisted: /not-allowlisted"
  );
  assert.deepEqual(classifyOzonSyncError(thrown), permanentClassification());
});

test("error classifier never persists raw messages, credentials, response bodies, or Error objects", () => {
  const classification = classifyOzonSyncError(
    Object.assign(
      new Error("apiKey=secret caller text with raw response"),
      { apiKey: "secret", responseBody: { customer: "private" } }
    )
  );

  assert.deepEqual(classification, {
    retryable: true,
    persistedError: {
      message: "Ozon sync step failed",
      kind: "unknown",
      retryable: true,
    },
  });
  assert.equal(JSON.stringify(classification).includes("secret"), false);
  assert.equal(JSON.stringify(classification).includes("caller text"), false);
});

test("a successful claimed domain is finished with its live lease", async () => {
  const step = claimedStep({ attempt_count: 1 });
  const harness = runnerHarness({
    claims: [step],
    execute: async () => ({ fetched: 7, inserted: 3 }),
  });

  await durableOzonRunnerTestSeam.recoverOne(
    TEST_DEADLINE_MS,
    harness.dependencies
  );

  assert.deepEqual(harness.finished, [
    {
      p_step_id: "step-1",
      p_lease_token: "lease-1",
      p_state: "completed",
      p_summary: { fetched: 7, inserted: 3 },
      p_last_error: null,
      p_next_attempt_at: null,
    },
  ]);
});

test("a finish RPC failure is propagated without a second finish attempt", async () => {
  const step = claimedStep();
  let finishCalls = 0;
  const dependencies: DurableOzonRunnerDependencies = {
    claimStep: async () => step,
    executeStep: async () => ({ fetched: 1 }),
    finishStep: async () => {
      finishCalls += 1;
      throw new Error("stale lease");
    },
    now: () => Date.parse("2026-07-26T10:00:00.000Z"),
  };

  await assert.rejects(
    durableOzonRunnerTestSeam.recoverOne(TEST_DEADLINE_MS, dependencies),
    /stale lease/
  );
  assert.equal(finishCalls, 1);
});

test("a transient failure schedules the exact next attempt through the live lease", async () => {
  const step = claimedStep({ attempt_count: 2 });
  const harness = runnerHarness({
    claims: [step],
    now: () => Date.parse("2026-07-26T10:00:00.000Z"),
    execute: async () => {
      throw new OzonApiError("/v2/warehouse/list", 503, { secret: "not persisted" });
    },
  });

  await durableOzonRunnerTestSeam.recoverOne(
    TEST_DEADLINE_MS,
    harness.dependencies
  );

  assert.deepEqual(harness.finished, [
    {
      p_step_id: "step-1",
      p_lease_token: "lease-1",
      p_state: "retry_scheduled",
      p_summary: {},
      p_last_error: {
        message: "Ozon sync step failed",
        kind: "server",
        status: 503,
        retryable: true,
      },
      p_next_attempt_at: "2026-07-26T10:05:00.000Z",
    },
  ]);
});

test("attempt eight turns an otherwise transient failure into terminal failure", async () => {
  const harness = runnerHarness({
    claims: [claimedStep({ attempt_count: 8 })],
    execute: async () => {
      throw new Error("database unavailable");
    },
  });

  await durableOzonRunnerTestSeam.recoverOne(
    TEST_DEADLINE_MS,
    harness.dependencies
  );

  assert.deepEqual(harness.finished, [
    {
      p_step_id: "step-1",
      p_lease_token: "lease-1",
      p_state: "failed",
      p_summary: {},
      p_last_error: {
        message: "Ozon sync step failed",
        kind: "unknown",
        retryable: true,
      },
      p_next_attempt_at: null,
    },
  ]);
});

test("manual runner stops claiming when its deadline is reached", async () => {
  let now = 100;
  const harness = runnerHarness({
    claims: [
      claimedStep({ id: "step-1", lease_token: "lease-1" }),
      claimedStep({ id: "step-2", lease_token: "lease-2", step_key: "products" }),
    ],
    now: () => now,
    execute: async () => {
      now = 200;
      return { fetched: 1 };
    },
  });

  const processed = await durableOzonRunnerTestSeam.runManual(
    "run-1",
    2_200,
    harness.dependencies
  );

  assert.equal(processed, 1);
  assert.deepEqual(harness.claimedRunIds, ["run-1"]);
  assert.deepEqual(harness.executedStepIds, ["step-1"]);
});

test("claimed execution is aborted before the finish margin and scheduled for retry", async () => {
  let scheduledAbort: (() => void) | null = null;
  let clearedTimer: unknown = null;
  const step = claimedStep({ attempt_count: 1 });
  const harness = runnerHarness({
    claims: [step],
    now: () => 1_000,
    execute: async (_step, context) =>
      new Promise((_resolve, reject) => {
        context.signal.addEventListener(
          "abort",
          () => reject(context.signal.reason),
          { once: true }
        );
        scheduledAbort?.();
      }),
    setTimer: (callback, milliseconds) => {
      assert.equal(milliseconds, 7_000);
      scheduledAbort = callback;
      return "deadline-timer";
    },
    clearTimer: (timer) => {
      clearedTimer = timer;
    },
  });

  const processed = await durableOzonRunnerTestSeam.runManual(
    "run-1",
    10_000,
    harness.dependencies
  );

  assert.equal(processed, 1);
  assert.equal(clearedTimer, "deadline-timer");
  assert.deepEqual(harness.finished, [
    {
      p_step_id: "step-1",
      p_lease_token: "lease-1",
      p_state: "retry_scheduled",
      p_summary: {},
      p_last_error: {
        message: "Ozon sync step failed",
        kind: "timeout",
        retryable: true,
      },
      p_next_attempt_at: "1970-01-01T00:01:01.000Z",
    },
  ]);
});

test("manual runner stops after claim exhaustion and never executes a claimed step twice", async () => {
  const harness = runnerHarness({
    claims: [claimedStep(), null],
    now: () => 100,
  });

  const processed = await durableOzonRunnerTestSeam.runManual(
    "run-1",
    2_200,
    harness.dependencies
  );

  assert.equal(processed, 1);
  assert.deepEqual(harness.claimedRunIds, ["run-1", "run-1"]);
  assert.deepEqual(harness.executedStepIds, ["step-1"]);
  assert.equal(harness.finished.length, 1);
});

test("recovery runner claims and processes exactly one due step", async () => {
  const harness = runnerHarness({
    claims: [
      claimedStep({ id: "step-1" }),
      claimedStep({ id: "step-2", step_key: "products" }),
    ],
  });

  assert.equal(
    await durableOzonRunnerTestSeam.recoverOne(
      TEST_DEADLINE_MS,
      harness.dependencies
    ),
    true
  );
  assert.deepEqual(harness.claimedRunIds, [null]);
  assert.deepEqual(harness.executedStepIds, ["step-1"]);
});

test("claim executor reloads context, creates a fresh client, and dispatches only the claimed key with original dates", async () => {
  const loads: string[] = [];
  const clients: object[] = [];
  const clientSignals: AbortSignal[] = [];
  const executions: Array<Record<string, unknown>> = [];
  const execute = durableOzonRunnerTestSeam.createClaimExecutor({
    loadExecutionContext: async (step) => {
      loads.push(step.id);
      return {
        connection: {
          status: "connected",
          credential_ciphertext: { encrypted: step.id },
        },
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T23:59:59.000Z",
      };
    },
    decryptCredentials: (ciphertext) => ({
      clientId: String(ciphertext.encrypted),
      apiKey: "safe-test-key",
    }),
    createClient: (_credentials, signal) => {
      const client = {};
      clients.push(client);
      clientSignals.push(signal);
      return client as never;
    },
    executeDomainStep: async (key, input) => {
      executions.push({ key, ...input });
      return { fetched: 1 };
    },
  });

  await execute(
    claimedStep({ id: "step-1", step_key: "warehouses" }),
    executionDeadline()
  );
  await execute(
    claimedStep({ id: "step-2", step_key: "products" }),
    executionDeadline()
  );

  assert.deepEqual(loads, ["step-1", "step-2"]);
  assert.equal(clients.length, 2);
  assert.notEqual(clients[0], clients[1]);
  assert.equal(clientSignals.length, 2);
  assert.equal(clientSignals.every((signal) => !signal.aborted), true);
  assert.deepEqual(
    executions.map(({ key, workspaceId, connectionId, dateFrom, dateTo }) => ({
      key,
      workspaceId,
      connectionId,
      dateFrom,
      dateTo,
    })),
    [
      {
        key: "warehouses",
        workspaceId: "workspace-1",
        connectionId: "connection-1",
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T23:59:59.000Z",
      },
      {
        key: "products",
        workspaceId: "workspace-1",
        connectionId: "connection-1",
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T23:59:59.000Z",
      },
    ]
  );
});

test("claim executor turns missing, disabled, invalid, and unknown connection configuration into permanent errors", async () => {
  const baseServices = {
    decryptCredentials: () => ({
      clientId: "client",
      apiKey: "key",
    }),
    createClient: () => ({}) as never,
    executeDomainStep: async () => ({ fetched: 1 }),
  };

  const cases = [
    durableOzonRunnerTestSeam.createClaimExecutor({
      ...baseServices,
      loadExecutionContext: async () => ({
        connection: null,
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T23:59:59.000Z",
      }),
    }),
    durableOzonRunnerTestSeam.createClaimExecutor({
      ...baseServices,
      loadExecutionContext: async () => ({
        connection: {
          status: "disabled",
          credential_ciphertext: {},
        },
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T23:59:59.000Z",
      }),
    }),
    durableOzonRunnerTestSeam.createClaimExecutor({
      ...baseServices,
      loadExecutionContext: async () => ({
        connection: {
          status: "connected",
          credential_ciphertext: {},
        },
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T23:59:59.000Z",
      }),
      decryptCredentials: () => {
        throw new Error("invalid ciphertext");
      },
    }),
    durableOzonRunnerTestSeam.createClaimExecutor({
      ...baseServices,
      loadExecutionContext: async () => ({
        connection: {
          status: "connected",
          credential_ciphertext: {},
        },
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T23:59:59.000Z",
      }),
    }),
  ];

  for (const [index, execute] of cases.entries()) {
    const step =
      index === cases.length - 1
        ? claimedStep({ step_key: "not-a-domain" })
        : claimedStep();
    await assert.rejects(
      execute(step, executionDeadline()),
      PermanentOzonSyncError
    );
  }
});

test("worker database boundary uses the scoped claim and lease-aware finish RPC contracts", async () => {
  const calls: Array<{
    name: string;
    parameters: Record<string, unknown>;
  }> = [];
  const claimed = claimedStep();
  const rpc = durableOzonRunnerTestSeam.createRpcOperations({
    rpc: async (name, parameters) => {
      calls.push({ name, parameters });
      return {
        data:
          name === "claim_ozon_sync_run_step"
            ? [claimed]
            : { ...claimed, state: "completed" },
        error: null,
      };
    },
  });
  const finish: FinishOzonSyncStepInput = {
    p_step_id: "step-1",
    p_lease_token: "lease-1",
    p_state: "completed",
    p_summary: { fetched: 1 },
    p_last_error: null,
    p_next_attempt_at: null,
  };

  assert.deepEqual(await rpc.claimStep("run-1"), claimed);
  assert.deepEqual(await rpc.claimStep(null), claimed);
  await rpc.finishStep(finish);

  assert.deepEqual(calls, [
    {
      name: "claim_ozon_sync_run_step",
      parameters: { p_run_id: "run-1" },
    },
    {
      name: "claim_ozon_sync_run_step",
      parameters: { p_run_id: null },
    },
    {
      name: "finish_ozon_sync_run_step",
      parameters: finish,
    },
  ]);
});

function permanentClassification(status?: number) {
  return {
    retryable: false,
    persistedError: {
      message: "Ozon sync step failed",
      kind: "client",
      ...(status === undefined ? {} : { status }),
      retryable: false,
    },
  };
}

function claimedStep(
  overrides: Partial<ClaimedOzonSyncStep> = {}
): ClaimedOzonSyncStep {
  return {
    id: "step-1",
    run_id: "run-1",
    workspace_id: "workspace-1",
    connection_id: "connection-1",
    provider: "ozon",
    step_key: "warehouses",
    step_order: 1,
    state: "running",
    attempt_count: 1,
    lease_token: "lease-1",
    lease_expires_at: "2026-07-26T10:10:00.000Z",
    ...overrides,
  };
}

function runnerHarness(options: {
  claims: Array<ClaimedOzonSyncStep | null>;
  execute?: (
    step: ClaimedOzonSyncStep,
    context: { deadlineMs: number; signal: AbortSignal }
  ) => Promise<Record<string, number>>;
  now?: () => number;
  setTimer?: (callback: () => void, milliseconds: number) => unknown;
  clearTimer?: (timer: unknown) => void;
}) {
  const claims = [...options.claims];
  const claimedRunIds: Array<string | null> = [];
  const executedStepIds: string[] = [];
  const finished: FinishOzonSyncStepInput[] = [];

  const dependencies: DurableOzonRunnerDependencies = {
    claimStep: async (runId) => {
      claimedRunIds.push(runId);
      return claims.shift() ?? null;
    },
    executeStep: async (step, context) => {
      executedStepIds.push(step.id);
      return options.execute?.(step, context) ?? { fetched: 1 };
    },
    finishStep: async (input) => {
      finished.push(input);
    },
    now: options.now ?? (() => Date.parse("2026-07-26T10:00:00.000Z")),
    setTimer: options.setTimer,
    clearTimer: options.clearTimer,
  };

  return {
    claimedRunIds,
    dependencies,
    executedStepIds,
    finished,
  };
}

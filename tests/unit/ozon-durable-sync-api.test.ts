import assert from "node:assert/strict";
import test from "node:test";

import {
  createOzonDurableSyncCoordinator,
  createOzonSyncWorkerRpcOperations,
  authorizeOzonRetryTarget,
  deriveOzonIntegrationRecovery,
  derivePublicOzonSyncSummary,
  deriveOzonSyncResult,
  durableSyncHttpStatus,
  parseOzonSyncRunComposite,
  resolveOzonSyncWindow,
  type OzonSyncRunRow,
  type OzonSyncRunStepRow,
} from "../../src/lib/ozon/durable-sync";

const RUN: OzonSyncRunRow = {
  id: "run-1",
  workspace_id: "workspace-1",
  connection_id: "connection-1",
  provider: "ozon",
  status: "running",
  date_from: "2026-06-01T00:00:00.000Z",
  date_to: "2026-06-30T00:00:00.000Z",
};

test("composite run parsing accepts Supabase object and one-row array responses", () => {
  assert.deepEqual(parseOzonSyncRunComposite(RUN), RUN);
  assert.deepEqual(parseOzonSyncRunComposite([RUN]), RUN);
});

test("composite run parsing rejects empty, multiple, and malformed responses without reflecting payloads", () => {
  const cases = [
    [],
    [RUN, { ...RUN, id: "run-2" }],
    { credential_ciphertext: { apiKey: "secret" } },
  ];

  for (const value of cases) {
    assert.throws(
      () => parseOzonSyncRunComposite(value),
      (error: unknown) =>
        error instanceof Error &&
        error.message === "Ozon sync run RPC returned an invalid row"
    );
  }
});

test("worker RPC operations use only the durable begin/resume and failed-step retry contracts", async () => {
  const calls: Array<{ name: string; parameters: Record<string, unknown> }> = [];
  const worker = createOzonSyncWorkerRpcOperations({
    rpc: async (name, parameters) => {
      calls.push({ name, parameters });
      return {
        data:
          name === "begin_or_resume_ozon_sync_run"
            ? RUN
            : [{ ...RUN, status: "running" }],
        error: null,
      };
    },
  });

  assert.equal(
    (
      await worker.beginOrResumeRun({
        connectionId: "connection-1",
        dateFrom: "2026-06-01T00:00:00.000Z",
        dateTo: "2026-06-30T00:00:00.000Z",
      })
    ).id,
    "run-1"
  );
  assert.equal((await worker.retryFailedRun("run-1")).id, "run-1");
  assert.deepEqual(calls, [
    {
      name: "begin_or_resume_ozon_sync_run",
      parameters: {
        p_connection_id: "connection-1",
        p_date_from: "2026-06-01T00:00:00.000Z",
        p_date_to: "2026-06-30T00:00:00.000Z",
      },
    },
    {
      name: "retry_failed_ozon_sync_run_steps",
      parameters: { p_run_id: "run-1" },
    },
  ]);
});

test("public result derives domain summaries and recovery only from step rows", () => {
  const steps: OzonSyncRunStepRow[] = [
    step({
      step_key: "products",
      step_order: 2,
      state: "completed",
      summary: { fetched: 2, inserted: 1, ignored: 999 },
    }),
    step({
      step_key: "warehouses",
      step_order: 1,
      state: "retry_scheduled",
      next_attempt_at: "2026-07-26T11:00:00.000Z",
      updated_at: "2026-07-26T10:02:00.000Z",
      last_error: {
        message: "Ozon sync step failed",
        kind: "server",
        status: 503,
        retryable: true,
        raw: "apiKey=secret",
      },
    }),
    step({
      step_key: "reports",
      step_order: 8,
      state: "failed",
      updated_at: "2026-07-26T10:01:00.000Z",
      last_error: {
        message: "attacker supplied apiKey=secret",
        response: "raw payload",
      },
    }),
    step({
      step_key: "analytics",
      step_order: 11,
      state: "pending",
    }),
  ];

  assert.deepEqual(
    deriveOzonSyncResult({ ...RUN, status: "retrying" }, steps),
    {
      runId: "run-1",
      status: "retrying",
      summary: {
        products: { fetched: 2, inserted: 1 },
        errors: [
          "warehouses: Ozon sync step failed (server, HTTP 503)",
          "reports: Ozon sync step failed (unknown)",
        ],
      },
      recovery: {
        pendingStepCount: 1,
        scheduledRetryCount: 1,
        failedStepCount: 1,
        nextRetryAt: "2026-07-26T11:00:00.000Z",
      },
    }
  );
});

test("public summary never exposes the durable run aggregate and safely preserves legacy domain summaries", () => {
  assert.deepEqual(
    derivePublicOzonSyncSummary(
      {
        totalSteps: 12,
        pendingSteps: 3,
        steps: { warehouses: { lastError: { raw: "secret" } } },
        products: { fetched: 4, updated: 2 },
        errors: ["apiKey=legacy-secret"],
      },
      []
    ),
    {
      products: { fetched: 4, updated: 2 },
      errors: [],
    }
  );
});

test("integration recovery counts persisted states exactly and chooses the earliest retry instant", () => {
  const steps = [
    step({ step_key: "warehouses", step_order: 1, state: "pending" }),
    step({ step_key: "products", step_order: 2, state: "running" }),
    step({
      step_key: "stocks",
      step_order: 3,
      state: "retry_scheduled",
      next_attempt_at: "2026-07-26T10:00:00+02:00",
      updated_at: "2026-07-26T07:00:00.000Z",
      last_error: { kind: "rate_limit", status: 429 },
    }),
    step({
      step_key: "postings",
      step_order: 4,
      state: "retry_scheduled",
      next_attempt_at: "2026-07-26T08:30:00.000Z",
      updated_at: "2026-07-26T07:30:00.000Z",
      last_error: { kind: "server", status: 503 },
    }),
    step({
      step_key: "returns",
      step_order: 5,
      state: "failed",
      updated_at: "2026-07-26T08:00:00.000Z",
      last_error: { kind: "client", status: 400 },
    }),
    step({
      step_key: "not-a-persisted-domain",
      step_order: 99,
      state: "failed",
      updated_at: "2026-07-26T09:00:00.000Z",
      last_error: { kind: "unknown", raw: "apiKey=secret" },
    }),
  ];

  assert.deepEqual(
    deriveOzonIntegrationRecovery(
      { id: "run-1", status: "retrying" },
      steps
    ),
    {
      runId: "run-1",
      status: "retrying",
      pendingStepCount: 1,
      scheduledRetryCount: 2,
      failedStepCount: 1,
      nextRetryAt: "2026-07-26T10:00:00+02:00",
      lastError: "returns: Ozon sync step failed (client, HTTP 400)",
    }
  );
});

test("retry authorization requires the requested run, connection, provider, and workspace to share one scope", () => {
  const run = {
    id: "run-1",
    workspace_id: "workspace-1",
    connection_id: "connection-1",
    provider: "ozon",
  };
  const connection = {
    id: "connection-1",
    workspace_id: "workspace-1",
    provider: "ozon",
    status: "connected",
  };

  assert.deepEqual(
    authorizeOzonRetryTarget(
      { workspaceId: "workspace-1", runId: "run-1" },
      run,
      connection
    ),
    { workspaceId: "workspace-1", connectionId: "connection-1" }
  );

  for (const [candidateRun, candidateConnection] of [
    [{ ...run, id: "another-run" }, connection],
    [{ ...run, workspace_id: "another-workspace" }, connection],
    [{ ...run, provider: "other" }, connection],
    [run, { ...connection, id: "another-connection" }],
    [run, { ...connection, workspace_id: "another-workspace" }],
    [run, { ...connection, provider: "other" }],
  ]) {
    assert.equal(
      authorizeOzonRetryTarget(
        { workspaceId: "workspace-1", runId: "run-1" },
        candidateRun,
        candidateConnection
      ),
      null
    );
  }
});

test("public status maps active runs to 202 and terminal runs to 200", () => {
  assert.deepEqual(
    ["running", "retrying", "completed", "completed_with_errors", "failed"].map(
      (status) => durableSyncHttpStatus(status as OzonSyncRunRow["status"])
    ),
    [202, 202, 200, 200, 200]
  );
});

test("concurrent manual starts naturally execute the same active run returned by the RPC", async () => {
  const executedRunIds: string[] = [];
  const coordinator = createOzonDurableSyncCoordinator({
    beginOrResumeRun: async () => RUN,
    retryFailedRun: async () => {
      throw new Error("retry should not be called");
    },
    executeUntilDeadline: async (runId) => {
      executedRunIds.push(runId);
    },
    loadRunSnapshot: async () => ({ run: RUN, steps: [] }),
    now: () => 1_000,
  });

  const [first, second] = await Promise.all([
    coordinator.beginOrResume({
      connectionId: "connection-1",
      dateFrom: RUN.date_from,
      dateTo: RUN.date_to,
      budgetMs: 25_000,
    }),
    coordinator.beginOrResume({
      connectionId: "connection-1",
      dateFrom: RUN.date_from,
      dateTo: RUN.date_to,
      budgetMs: 25_000,
    }),
  ]);

  assert.equal(first.runId, "run-1");
  assert.equal(second.runId, "run-1");
  assert.deepEqual(executedRunIds, ["run-1", "run-1"]);
});

test("manual retry invokes only the failed-step reset for the authorized run and reloads that run", async () => {
  const calls: string[] = [];
  const coordinator = createOzonDurableSyncCoordinator({
    beginOrResumeRun: async () => {
      throw new Error("begin should not be called");
    },
    retryFailedRun: async (runId) => {
      calls.push(`retry:${runId}`);
      return { ...RUN, id: runId };
    },
    executeUntilDeadline: async (runId, deadlineMs) => {
      calls.push(`execute:${runId}:${deadlineMs}`);
    },
    loadRunSnapshot: async (runId) => {
      calls.push(`load:${runId}`);
      return { run: { ...RUN, id: runId, status: "completed" }, steps: [] };
    },
    now: () => 1_000,
  });

  const result = await coordinator.retryFailed({
    runId: "authorized-run",
    budgetMs: 25_000,
  });

  assert.equal(result.runId, "authorized-run");
  assert.deepEqual(calls, [
    "retry:authorized-run",
    "execute:authorized-run:26000",
    "load:authorized-run",
  ]);
});

test("sync window preserves explicit dates and otherwise uses the preceding thirty days", () => {
  assert.deepEqual(
    resolveOzonSyncWindow(
      {},
      Date.parse("2026-07-26T12:00:00.000Z")
    ),
    {
      dateFrom: "2026-06-26T12:00:00.000Z",
      dateTo: "2026-07-26T12:00:00.000Z",
    }
  );
  assert.deepEqual(
    resolveOzonSyncWindow(
      {
        dateFrom: "2026-07-01T00:00:00.000Z",
        dateTo: "2026-07-05T00:00:00.000Z",
      },
      0
    ),
    {
      dateFrom: "2026-07-01T00:00:00.000Z",
      dateTo: "2026-07-05T00:00:00.000Z",
    }
  );
});

function step(
  overrides: Partial<OzonSyncRunStepRow> = {}
): OzonSyncRunStepRow {
  return {
    run_id: "run-1",
    step_key: "warehouses",
    step_order: 1,
    state: "pending",
    summary: {},
    last_error: null,
    next_attempt_at: null,
    updated_at: "2026-07-26T10:00:00.000Z",
    ...overrides,
  };
}

import assert from "node:assert/strict";
import test from "node:test";
import { NextRequest, NextResponse } from "next/server";

import {
  createOzonSyncPostHandler,
  type OzonSyncPostOperations,
} from "../../src/app/api/integrations/ozon/sync/route";
import {
  createOzonRetryPostHandler,
  type OzonRetryPostOperations,
} from "../../src/app/api/integrations/ozon/sync/retry/route";
import {
  createOzonRecoveryPostHandler,
} from "../../src/app/api/internal/integrations/ozon/recover/route";
import type { OzonSyncResult } from "../../src/lib/ozon/durable-sync";

const RUN_ID = "11111111-1111-4111-8111-111111111111";

test("sync handler authorizes and scopes user reads before service construction, parses dates, and returns 202", async () => {
  const calls: string[] = [];
  const operations = syncOperations(calls, {
    result: syncResult("run-1", "retrying"),
  });
  const handler = createOzonSyncPostHandler(operations);

  const response = await handler(
    request("/api/integrations/ozon/sync", {
      dateFrom: "2026-07-01T00:00:00.000Z",
      dateTo: "2026-07-31T00:00:00.000Z",
    })
  );

  assert.equal(response.status, 202);
  assert.deepEqual(await response.json(), syncResult("run-1", "retrying"));
  assert.deepEqual(calls, [
    "manager",
    "read-connection:workspace-1:ozon",
    "create-service:workspace-1:connection-1",
    "begin:connection-1:2026-07-01T00:00:00.000Z:2026-07-31T00:00:00.000Z:25000",
  ]);
});

test("sync handler returns exact terminal 200 response", async () => {
  const handler = createOzonSyncPostHandler(
    syncOperations([], {
      result: syncResult("run-1", "completed"),
    })
  );

  const response = await handler(
    request("/api/integrations/ozon/sync", {})
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), syncResult("run-1", "completed"));
});

test("sync handler returns fixed 400 for malformed, impossible, and reversed windows before service construction", async () => {
  for (const body of [
    { dateFrom: "malformed" },
    { dateFrom: "" },
    { dateFrom: 123 },
    { dateTo: "2026-02-30T00:00:00.000Z" },
    {
      dateFrom: "2026-08-01T00:00:00.000Z",
      dateTo: "2026-07-01T00:00:00.000Z",
    },
  ]) {
    const calls: string[] = [];
    const handler = createOzonSyncPostHandler(syncOperations(calls));
    const response = await handler(
      request("/api/integrations/ozon/sync", body)
    );

    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), {
      error: "Invalid Ozon sync date window",
    });
    assert.deepEqual(calls, [
      "manager",
      "read-connection:workspace-1:ozon",
    ]);
  }
});

test("retry handler parses runId, authorizes scoped run and connection reads before RPC, and returns 202", async () => {
  const calls: string[] = [];
  const handler = createOzonRetryPostHandler(retryOperations(calls));

  const response = await handler(
    request("/api/integrations/ozon/sync/retry", { runId: ` ${RUN_ID} ` })
  );

  assert.equal(response.status, 202);
  assert.deepEqual(await response.json(), syncResult(RUN_ID, "running"));
  assert.deepEqual(calls, [
    "manager",
    `read-run:workspace-1:${RUN_ID}:ozon`,
    "read-connection:workspace-1:connection-1:ozon",
    "create-service:workspace-1:connection-1",
    `retry:${RUN_ID}:25000`,
  ]);
});

test("retry handler returns exact terminal 200 response with the coordinator body unchanged", async () => {
  const terminalResult = syncResult(RUN_ID, "completed_with_errors");
  const operations = retryOperations([], { result: terminalResult });
  const handler = createOzonRetryPostHandler(operations);

  const response = await handler(
    request("/api/integrations/ozon/sync/retry", { runId: RUN_ID })
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), terminalResult);
});

test("retry handler rejects missing input and mismatched scope before service construction", async () => {
  const missingCalls: string[] = [];
  const missingHandler = createOzonRetryPostHandler(
    retryOperations(missingCalls)
  );
  const missing = await missingHandler(
    request("/api/integrations/ozon/sync/retry", {})
  );
  assert.equal(missing.status, 400);
  assert.deepEqual(await missing.json(), {
    error: "Ozon sync run ID is required",
  });
  assert.deepEqual(missingCalls, ["manager"]);

  const invalidCalls: string[] = [];
  const invalidHandler = createOzonRetryPostHandler(
    retryOperations(invalidCalls)
  );
  const invalid = await invalidHandler(
    request("/api/integrations/ozon/sync/retry", { runId: "not-a-uuid" })
  );
  assert.equal(invalid.status, 400);
  assert.deepEqual(await invalid.json(), {
    error: "Ozon sync run ID must be a UUID",
  });
  assert.deepEqual(invalidCalls, ["manager"]);

  const mismatchCalls: string[] = [];
  const mismatchHandler = createOzonRetryPostHandler(
    retryOperations(mismatchCalls, {
      connection: {
        id: "other-connection",
        workspace_id: "workspace-1",
        provider: "ozon",
        status: "connected",
      },
    })
  );
  const mismatch = await mismatchHandler(
    request("/api/integrations/ozon/sync/retry", { runId: RUN_ID })
  );
  assert.equal(mismatch.status, 404);
  assert.deepEqual(await mismatch.json(), {
    error: "Ozon sync run not found",
  });
  assert.deepEqual(mismatchCalls, [
    "manager",
    `read-run:workspace-1:${RUN_ID}:ozon`,
    "read-connection:workspace-1:connection-1:ozon",
  ]);
});

test("sync and retry lookup failures log raw database diagnostics but return fixed safe errors", async () => {
  const rawMessage = "PostgREST apiKey=secret relation detail";
  const syncLogs: unknown[][] = [];
  const syncOps = syncOperations([]);
  syncOps.findConnection = async () => ({
    connection: null,
    errorMessage: rawMessage,
  });
  syncOps.logError = (...args) => syncLogs.push(args);

  const syncResponse = await createOzonSyncPostHandler(syncOps)(
    request("/api/integrations/ozon/sync", {})
  );
  assert.equal(syncResponse.status, 500);
  const syncBody = await syncResponse.json();
  assert.deepEqual(syncBody, {
    error: "Failed to load Ozon connection",
  });
  assert.equal(JSON.stringify(syncBody).includes("secret"), false);
  assert.deepEqual(syncLogs, [
    ["Failed to load Ozon connection", rawMessage],
  ]);

  for (const target of ["run", "connection"] as const) {
    const retryLogs: unknown[][] = [];
    const retryOps = retryOperations([]);
    if (target === "run") {
      retryOps.findRun = async () => ({
        run: null,
        errorMessage: rawMessage,
      });
    } else {
      retryOps.findConnection = async () => ({
        connection: null,
        errorMessage: rawMessage,
      });
    }
    retryOps.logError = (...args) => retryLogs.push(args);

    const response = await createOzonRetryPostHandler(retryOps)(
      request("/api/integrations/ozon/sync/retry", { runId: RUN_ID })
    );
    assert.equal(response.status, 500);
    assert.deepEqual(await response.json(), {
      error:
        target === "run"
          ? "Failed to load Ozon sync run"
          : "Failed to load Ozon connection",
    });
    assert.deepEqual(retryLogs, [
      [
        target === "run"
          ? "Failed to load Ozon sync run"
          : "Failed to load Ozon connection",
        rawMessage,
      ],
    ]);
  }
});

test("internal recovery handler forwards configuration and exact header, invokes one recovery, and returns minimal output", async () => {
  const calls: string[] = [];
  const handler = createOzonRecoveryPostHandler({
    configuredSecret: () => {
      calls.push("env");
      return "configured-secret";
    },
    recoverOne: async (deadlineMs) => {
      calls.push(`recover:${deadlineMs}`);
      return true;
    },
    now: () => 1_000,
  });

  const response = await handler(
    new NextRequest("http://localhost/api/internal/integrations/ozon/recover", {
      method: "POST",
      headers: { "x-tover-recovery-secret": "configured-secret" },
    })
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { processed: true });
  assert.deepEqual(calls, ["env", "recover:101000"]);
});

test("internal recovery handler returns fixed 503/401 without recovery for missing config or bad header", async () => {
  for (const item of [
    {
      configuredSecret: undefined,
      providedSecret: "configured-secret",
      status: 503,
      body: { error: "Recovery unavailable" },
    },
    {
      configuredSecret: "configured-secret",
      providedSecret: "wrong",
      status: 401,
      body: { error: "Unauthorized" },
    },
  ]) {
    let recoverCalls = 0;
    const handler = createOzonRecoveryPostHandler({
      configuredSecret: () => item.configuredSecret,
      recoverOne: async () => {
        recoverCalls += 1;
        return true;
      },
      now: () => 1_000,
    });
    const response = await handler(
      new NextRequest(
        "http://localhost/api/internal/integrations/ozon/recover",
        {
          method: "POST",
          headers: {
            "x-tover-recovery-secret": item.providedSecret,
          },
        }
      )
    );

    assert.equal(response.status, item.status);
    assert.deepEqual(await response.json(), item.body);
    assert.equal(recoverCalls, 0);
  }
});

function syncOperations(
  calls: string[],
  overrides: { result?: OzonSyncResult } = {}
): OzonSyncPostOperations {
  return {
    getManagerContext: async () => {
      calls.push("manager");
      return { workspaceId: "workspace-1", userClient: {} };
    },
    findConnection: async (_userClient, workspaceId) => {
      calls.push(`read-connection:${workspaceId}:ozon`);
      return {
        connection: { id: "connection-1", status: "connected" },
        errorMessage: null,
      };
    },
    createCoordinator: ({ workspaceId, connectionId }) => {
      calls.push(`create-service:${workspaceId}:${connectionId}`);
      return {
        beginOrResume: async (input) => {
          calls.push(
            `begin:${input.connectionId}:${input.dateFrom}:${input.dateTo}:${input.budgetMs}`
          );
          return overrides.result ?? syncResult("run-1", "running");
        },
      };
    },
    now: () => Date.parse("2026-07-26T12:00:00.000Z"),
    routeError: () =>
      NextResponse.json({ error: "Internal server error" }, { status: 500 }),
    logError: () => {},
  };
}

function retryOperations(
  calls: string[],
  overrides: {
    connection?: {
      id: string;
      workspace_id: string;
      provider: string;
      status: string;
    };
    result?: OzonSyncResult;
  } = {}
): OzonRetryPostOperations {
  return {
    getManagerContext: async () => {
      calls.push("manager");
      return { workspaceId: "workspace-1", userClient: {} };
    },
    findRun: async (_userClient, workspaceId, runId) => {
      calls.push(`read-run:${workspaceId}:${runId}:ozon`);
      return {
        run: {
          id: RUN_ID,
          workspace_id: "workspace-1",
          connection_id: "connection-1",
          provider: "ozon",
          status: "failed",
        },
        errorMessage: null,
      };
    },
    findConnection: async (_userClient, workspaceId, connectionId) => {
      calls.push(
        `read-connection:${workspaceId}:${connectionId}:ozon`
      );
      return {
        connection:
          overrides.connection ?? {
            id: "connection-1",
            workspace_id: "workspace-1",
            provider: "ozon",
            status: "connected",
          },
        errorMessage: null,
      };
    },
    createCoordinator: ({ workspaceId, connectionId }) => {
      calls.push(`create-service:${workspaceId}:${connectionId}`);
      return {
        retryFailed: async (input) => {
          calls.push(`retry:${input.runId}:${input.budgetMs}`);
          return overrides.result ?? syncResult(input.runId, "running");
        },
      };
    },
    routeError: () =>
      NextResponse.json({ error: "Internal server error" }, { status: 500 }),
    logError: () => {},
  };
}

function request(path: string, body: Record<string, unknown>) {
  return new NextRequest(`http://localhost${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

function syncResult(
  runId: string,
  status: OzonSyncResult["status"]
): OzonSyncResult {
  return {
    runId,
    status,
    summary: { errors: [] },
    recovery: {
      pendingStepCount: 0,
      scheduledRetryCount: 0,
      failedStepCount: 0,
      nextRetryAt: null,
    },
  };
}

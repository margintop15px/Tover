import assert from "node:assert/strict";
import test from "node:test";

import {
  handleOzonRecoveryRequest,
  OZON_RECOVERY_MAX_DURATION_SECONDS,
  OZON_RECOVERY_STEP_BUDGET_MS,
  recoverySecretsMatch,
} from "../../src/lib/ozon/recovery";

test("internal recovery duration stays below the scheduler HTTP timeout", () => {
  assert.ok(OZON_RECOVERY_MAX_DURATION_SECONDS > 0);
  assert.ok(OZON_RECOVERY_MAX_DURATION_SECONDS < 120);
  assert.ok(
    OZON_RECOVERY_STEP_BUDGET_MS <
      OZON_RECOVERY_MAX_DURATION_SECONDS * 1_000
  );
});

test("recovery secret validation accepts only the correct configured value including different-length inputs", () => {
  assert.equal(recoverySecretsMatch(undefined, "configured-secret"), false);
  assert.equal(recoverySecretsMatch("", "configured-secret"), false);
  assert.equal(recoverySecretsMatch("wrong", "configured-secret"), false);
  assert.equal(
    recoverySecretsMatch("configured-secret-extra", "configured-secret"),
    false
  );
  assert.equal(
    recoverySecretsMatch("configured-secret", "configured-secret"),
    true
  );
});

test("missing recovery configuration returns 503 without touching the runner", async () => {
  let calls = 0;
  const result = await handleOzonRecoveryRequest({
    configuredSecret: undefined,
    providedSecret: "anything",
    deadlineMs: 101_000,
    recoverOne: async () => {
      calls += 1;
      return true;
    },
  });

  assert.deepEqual(result, {
    status: 503,
    body: { error: "Recovery unavailable" },
  });
  assert.equal(calls, 0);
});

test("missing or bad recovery credentials return the same minimal 401", async () => {
  for (const providedSecret of [undefined, "wrong", "different-length-wrong"]) {
    let calls = 0;
    const result = await handleOzonRecoveryRequest({
      configuredSecret: "configured-secret",
      providedSecret,
      deadlineMs: 101_000,
      recoverOne: async () => {
        calls += 1;
        return true;
      },
    });

    assert.deepEqual(result, {
      status: 401,
      body: { error: "Unauthorized" },
    });
    assert.equal(calls, 0);
  }
});

test("valid recovery passes the absolute worker deadline and returns only a minimal safe result", async () => {
  let calls = 0;
  const result = await handleOzonRecoveryRequest({
    configuredSecret: "configured-secret",
    providedSecret: "configured-secret",
    deadlineMs: 101_000,
    recoverOne: async (deadlineMs) => {
      calls += 1;
      assert.equal(deadlineMs, 101_000);
      return true;
    },
  });

  assert.deepEqual(result, { status: 200, body: { processed: true } });
  assert.equal(calls, 1);
  assert.deepEqual(Object.keys(result.body), ["processed"]);
});

test("valid recovery enqueues due automatic syncs before claiming one step", async () => {
  const calls: string[] = [];
  const result = await handleOzonRecoveryRequest({
    configuredSecret: "configured-secret",
    providedSecret: "configured-secret",
    deadlineMs: 101_000,
    enqueueDue: async () => {
      calls.push("enqueue");
    },
    recoverOne: async () => {
      calls.push("recover");
      return true;
    },
  });

  assert.deepEqual(result, { status: 200, body: { processed: true } });
  assert.deepEqual(calls, ["enqueue", "recover"]);
});

test("internal recovery failures never expose raw errors or credentials", async () => {
  const result = await handleOzonRecoveryRequest({
    configuredSecret: "configured-secret",
    providedSecret: "configured-secret",
    deadlineMs: 101_000,
    recoverOne: async () => {
      throw new Error("apiKey=secret database payload");
    },
  });

  assert.deepEqual(result, {
    status: 500,
    body: { error: "Recovery failed" },
  });
  assert.equal(JSON.stringify(result).includes("secret"), false);
  assert.equal(JSON.stringify(result).includes("payload"), false);
});

import assert from "node:assert/strict";
import test from "node:test";

import { OzonApiError, OzonClient } from "../../src/lib/ozon/client";

const credentials = { clientId: "client", apiKey: "api-key" };

interface RuntimeFixture {
  fetch: typeof fetch;
  now: () => number;
  sleep: (milliseconds: number) => Promise<void>;
  random: () => number;
  timeoutSignal: (milliseconds: number) => AbortSignal;
}

function createRuntime(
  responses: Array<Response | Error>,
  options: Partial<RuntimeFixture> = {}
) {
  let now = 0;
  const sleeps: number[] = [];
  const requestStarts: number[] = [];
  const timeoutCalls: number[] = [];
  let attempts = 0;

  const runtime: RuntimeFixture = {
    fetch: async () => {
      requestStarts.push(now);
      const response = responses[attempts++];
      if (response instanceof Error) throw response;
      return response;
    },
    now: () => now,
    sleep: async (milliseconds) => {
      sleeps.push(milliseconds);
      now += milliseconds;
    },
    random: () => 0,
    timeoutSignal: (milliseconds) => {
      timeoutCalls.push(milliseconds);
      return new AbortController().signal;
    },
    ...options,
  };

  return { runtime, sleeps, requestStarts, timeoutCalls, attempts: () => attempts };
}

function testClient(runtime: RuntimeFixture) {
  return new (OzonClient as unknown as new (
    credentials: typeof credentials,
    runtime: RuntimeFixture
  ) => OzonClient)(credentials, runtime);
}

test("paces request starts by 25 milliseconds and gives each request a 30 second timeout", async () => {
  const fixture = createRuntime([jsonResponse({ first: true }), jsonResponse({ second: true })]);
  const client = testClient(fixture.runtime);

  await client.request("/v2/warehouse/list");
  await client.request("/v2/warehouse/list");

  assert.deepEqual(fixture.requestStarts, [0, 25]);
  assert.deepEqual(fixture.sleeps, [25]);
  assert.deepEqual(fixture.timeoutCalls, [30_000, 30_000]);
});

test("retries transport errors four times total with approved exponential backoff and jitter", async () => {
  const fixture = createRuntime(
    [new TypeError("network down"), new TypeError("network down"), new TypeError("network down"), jsonResponse({ ok: true })],
    { random: () => 0.5 }
  );
  const client = testClient(fixture.runtime);

  assert.deepEqual(await client.request("/v2/warehouse/list"), { ok: true });
  assert.equal(fixture.attempts(), 4);
  assert.deepEqual(fixture.sleeps, [625, 1_125, 2_125]);
});

for (const status of [408, 425, 429, 500]) {
  test(`retries HTTP ${status} four times total`, async () => {
    const fixture = createRuntime([
      jsonResponse({ error: "retry" }, status),
      jsonResponse({ error: "retry" }, status),
      jsonResponse({ error: "retry" }, status),
      jsonResponse({ ok: true }),
    ]);

    assert.deepEqual(await testClient(fixture.runtime).request("/v2/warehouse/list"), { ok: true });
    assert.equal(fixture.attempts(), 4);
  });
}

test("uses Retry-After dates and caps retry delay at 30 seconds", async () => {
  const fixture = createRuntime([
    jsonResponse({ error: "busy" }, 429, { "Retry-After": "Thu, 01 Jan 1970 00:00:45 GMT" }),
    jsonResponse({ ok: true }),
  ]);

  assert.deepEqual(await testClient(fixture.runtime).request("/v2/warehouse/list"), { ok: true });
  assert.deepEqual(fixture.sleeps, [30_000]);
});

test("uses Item-Retry-After minutes for retry delay", async () => {
  const fixture = createRuntime([
    jsonResponse({ error: "busy" }, 503, { "Item-Retry-After": "0.25" }),
    jsonResponse({ ok: true }),
  ]);

  assert.deepEqual(await testClient(fixture.runtime).request("/v2/warehouse/list"), { ok: true });
  assert.deepEqual(fixture.sleeps, [15_000]);
});

test("attaches only safe response metadata, code, message, and retry delay to a final retryable error", async () => {
  const fixture = createRuntime([
    jsonResponse(
      {
        error: {
          code: "RATE_LIMITED",
          message: "Try again later",
          authorization: "must-not-be-retained",
        },
        customer: { email: "sensitive@example.com" },
      },
      429,
      {
        "Retry-After": "60",
        "Item-Retry-After": "5",
        "X-Request-Id": "request-123",
        Authorization: "must-not-be-exposed",
      }
    ),
    jsonResponse({ error: { code: "RATE_LIMITED", message: "Try again later" } }, 429, { "Retry-After": "60", "Item-Retry-After": "5", "X-Request-Id": "request-123" }),
    jsonResponse({ error: { code: "RATE_LIMITED", message: "Try again later" } }, 429, { "Retry-After": "60", "Item-Retry-After": "5", "X-Request-Id": "request-123" }),
    jsonResponse({ error: { code: "RATE_LIMITED", message: "Try again later" } }, 429, { "Retry-After": "60", "Item-Retry-After": "5", "X-Request-Id": "request-123" }),
  ]);

  await assert.rejects(testClient(fixture.runtime).request("/v2/warehouse/list"), (error: unknown) => {
    assert.ok(error instanceof OzonApiError);
    assert.deepEqual(error.responseMetadata, {
      requestId: "request-123",
      retryAfterMs: 30_000,
      itemRetryAfterMs: 30_000,
    });
    assert.equal(error.retryDelayMs, 30_000);
    assert.equal(error.code, "RATE_LIMITED");
    assert.equal(error.apiMessage, "Try again later");
    assert.equal("responseBody" in error, false);
    assert.equal(JSON.stringify(error).includes("sensitive@example.com"), false);
    assert.equal(JSON.stringify(error).includes("must-not-be-retained"), false);
    return true;
  });
});

test("sanitizes the legacy OzonApiError response-body constructor argument", () => {
  const error = new OzonApiError("/v2/warehouse/list", 400, {
    code: "INVALID_REQUEST",
    message: "Invalid input",
    apiKey: "must-not-be-retained",
  });

  assert.equal(error.code, "INVALID_REQUEST");
  assert.equal(error.apiMessage, "Invalid input");
  assert.equal("responseBody" in error, false);
  assert.equal(JSON.stringify(error).includes("must-not-be-retained"), false);
});

function jsonResponse(body: unknown, status = 200, headers?: HeadersInit) {
  return new Response(JSON.stringify(body), { status, headers });
}

import assert from "node:assert/strict";
import test from "node:test";

import { OzonApiError, OzonClient } from "../../src/lib/ozon/client";

const credentials = { clientId: "seller-client-987", apiKey: "seller-api-key-123" };

interface RuntimeFixture {
  fetch: typeof fetch;
  now: () => number;
  sleep: (milliseconds: number, signal?: AbortSignal) => Promise<void>;
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

function durableTestClient(runtime: RuntimeFixture, signal: AbortSignal) {
  return new (OzonClient as unknown as new (
    credentials: typeof credentials,
    runtime: RuntimeFixture,
    options: { maxAttempts: number; signal: AbortSignal }
  ) => OzonClient)(credentials, runtime, { maxAttempts: 1, signal });
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

test("durable client performs one HTTP attempt and leaves retry ownership to the step runner", async () => {
  const fixture = createRuntime([
    jsonResponse({ error: "retry later" }, 503),
    jsonResponse({ ok: true }),
  ]);
  const client = durableTestClient(
    fixture.runtime,
    new AbortController().signal
  );

  await assert.rejects(
    client.request("/v2/warehouse/list"),
    (error: unknown) => error instanceof OzonApiError && error.status === 503
  );
  assert.equal(fixture.attempts(), 1);
  assert.deepEqual(fixture.sleeps, []);
});

test("durable client shares one cancellation signal across requests and pacing waits", async () => {
  const controller = new AbortController();
  const pacingWaits: number[] = [];
  const fixture = createRuntime([
    jsonResponse({ first: true }),
    jsonResponse({ second: true }),
  ], {
    sleep: async (milliseconds, signal) => {
      pacingWaits.push(milliseconds);
      controller.abort(new DOMException("step deadline", "TimeoutError"));
      signal?.throwIfAborted();
    },
  });
  const client = durableTestClient(fixture.runtime, controller.signal);

  await client.request("/v2/warehouse/list");

  await assert.rejects(
    client.request("/v2/warehouse/list"),
    (error: unknown) =>
      error instanceof DOMException && error.name === "TimeoutError"
  );
  assert.equal(fixture.attempts(), 1);
  assert.deepEqual(pacingWaits, [25]);
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
          message: "finance document not found for client seller-client-987 api-key=seller-api-key-123",
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
    jsonResponse({ error: { code: "RATE_LIMITED", message: "finance document not found for client seller-client-987 api-key=seller-api-key-123" } }, 429, { "Retry-After": "60", "Item-Retry-After": "5", "X-Request-Id": "request-123" }),
    jsonResponse({ error: { code: "RATE_LIMITED", message: "finance document not found for client seller-client-987 api-key=seller-api-key-123" } }, 429, { "Retry-After": "60", "Item-Retry-After": "5", "X-Request-Id": "request-123" }),
    jsonResponse({ error: { code: "RATE_LIMITED", message: "finance document not found for client seller-client-987 api-key=seller-api-key-123" } }, 429, { "Retry-After": "60", "Item-Retry-After": "5", "X-Request-Id": "request-123" }),
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
    assert.equal(error.apiMessage, "finance document not found for client [REDACTED] api-key=[REDACTED]");
    assert.equal(error.apiMessage.includes("finance document not found"), true);
    assert.equal(error.apiMessage.includes(credentials.clientId), false);
    assert.equal(error.apiMessage.includes(credentials.apiKey), false);
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

test("redacts common token and PII shapes from legacy Ozon error messages", () => {
  const secrets = [
    "authorization-secret",
    "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature-value",
    "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    "person@example.com",
    "+1 415-555-0123",
    "query-secret-value",
  ];
  const error = new OzonApiError("/v2/warehouse/list", 400, {
    message: `\u0000finance document not found\nAuthorization: Bearer ${secrets[0]}; jwt=${secrets[1]}; token=${secrets[2]}; email=${secrets[3]}; phone=${secrets[4]}; https://example.test/path?api_key=${secrets[5]} ${"x".repeat(600)}`,
  });

  assert.ok(error.apiMessage?.includes("finance document not found"));
  assert.ok(error.apiMessage && error.apiMessage.length <= 500);
  assert.equal(/[\u0000-\u001F\u007F-\u009F]/.test(error.apiMessage || ""), false);
  for (const secret of secrets) {
    assert.equal(error.apiMessage?.includes(secret), false);
  }
});

test("redacts the full Basic authorization value from legacy Ozon error messages", () => {
  const error = new OzonApiError("/v2/warehouse/list", 400, {
    message: "Authorization: Basic YTpi",
  });

  assert.equal(error.apiMessage?.includes("YTpi"), false);
});

function jsonResponse(body: unknown, status = 200, headers?: HeadersInit) {
  return new Response(JSON.stringify(body), { status, headers });
}

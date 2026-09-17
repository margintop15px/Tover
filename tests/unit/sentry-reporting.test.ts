import assert from "node:assert/strict";
import test from "node:test";
import * as Sentry from "@sentry/nextjs";
import { sanitizeErrorEvent, sentryOptions } from "../../src/lib/sentry-options";
import { RouteAuthError, toRouteErrorResponse } from "../../src/lib/request-context";

test("error reports retain stack traces but exclude auth URLs and request data", () => {
  const event = sanitizeErrorEvent({
    exception: { values: [{ type: "NotFoundError", value: "removeChild failed",
      stacktrace: { frames: [{ filename: "https://tover.example/app.js", lineno: 12 }] },
    }] },
    request: {
      url: "https://tover.example/auth/callback?code=secret-code#access_token=secret-token",
      method: "GET",
      headers: { authorization: "Bearer secret-header" },
      cookies: { session: "secret-cookie" },
      data: { password: "secret-password" },
      query_string: "code=secret-code",
    },
    user: { email: "private@example.com" },
    extra: { __serialized__: { apiKey: "secret-key" } },
    breadcrumbs: [
      { category: "console", message: "secret-console" },
      { category: "ui.click", message: "private@example.com" },
      { category: "navigation", data: { from: "/login?token=secret-from", to: "/settings#secret-hash" } },
      { category: "fetch", data: { url: "https://tover.example/api/products?search=secret-search", status_code: 500 } },
    ],
  });
  const serialized = JSON.stringify(event);
  assert.equal(serialized.includes("secret"), false);
  assert.equal(serialized.includes("private@example.com"), false);
  assert.equal(event.exception?.values?.[0].stacktrace?.frames?.[0].lineno, 12);
  assert.deepEqual(event.request, { url: "https://tover.example/auth/callback", method: "GET" });
  assert.equal(event.breadcrumbs?.length, 2);
  assert.equal(event.breadcrumbs?.[1].data?.status_code, 500);
});

test("unexpected route exceptions reach Sentry; expected auth failures do not", async () => {
  const events: Sentry.ErrorEvent[] = [];
  Sentry.init({
    ...sentryOptions,
    dsn: "https://public@sentry.invalid/1",
    defaultIntegrations: false,
    transport: () => ({
      send: async (envelope) => {
        for (const [header, payload] of envelope[1]) {
          if (header.type === "event") events.push(payload as Sentry.ErrorEvent);
        }
        return { statusCode: 200 };
      },
      flush: async () => true,
    }),
  });
  const originalConsoleError = console.error;
  console.error = () => {};
  try {
    assert.equal(toRouteErrorResponse(new RouteAuthError(401, "Unauthorized")).status, 401);
    assert.equal(toRouteErrorResponse(new Error("Unexpected route failure")).status, 500);
    await Sentry.flush(2000);
    assert.equal(events.length, 1);
    assert.equal(events[0].exception?.values?.[0].value, "Unexpected route failure");
    assert.equal(events[0].tags?.handled_by, "toRouteErrorResponse");
  } finally {
    console.error = originalConsoleError;
    await Sentry.close(2000);
  }
});

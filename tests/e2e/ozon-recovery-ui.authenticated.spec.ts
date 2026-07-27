import { expect, test, type Page, type Route } from "@playwright/test";

type RecoveryStatus =
  | "running"
  | "retrying"
  | "completed"
  | "completed_with_errors"
  | "failed";

function ozonSummary(status: RecoveryStatus, failedStepCount = 0) {
  return {
    connected: true,
    connection: {
      id: "connection-1",
      name: "Ozon",
      status: "connected",
      clientIdHint: "1234",
      apiKeyHint: "abcd",
      lastValidatedAt: "2026-07-26T08:00:00.000Z",
      lastSyncAt: "2026-07-26T09:00:00.000Z",
      lastSyncStatus: status,
      lastSyncError: null,
    },
    counts: {
      products: 7,
      unmappedProducts: 0,
      warehouses: 2,
      unmappedWarehouses: 0,
      postings: 3,
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
    recentRuns: [],
    recovery: {
      runId: "run-1",
      status,
      pendingStepCount: status === "running" ? 1 : 0,
      scheduledRetryCount: status === "retrying" ? 1 : 0,
      failedStepCount,
      nextRetryAt:
        status === "retrying" ? "2026-07-26T10:01:00.000Z" : null,
      lastError:
        status === "retrying" || failedStepCount > 0
          ? "finance: Ozon sync step failed (server, HTTP 500)"
          : null,
    },
  };
}

async function useLocale(page: Page, locale: "en" | "ru") {
  await page.addInitScript((selectedLocale) => {
    window.localStorage.setItem("tover-locale", selectedLocale);
  }, locale);
}

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

for (const locale of ["en", "ru"] as const) {
  test(`shows informational retry recovery in ${locale}`, async ({ page }) => {
    await useLocale(page, locale);
    await page.route("**/api/integrations/ozon?*", (route) =>
      fulfillJson(route, ozonSummary("retrying"))
    );

    await page.goto("/operations/marketplaces");

    const banner = page.getByRole("status");
    await expect(banner).toContainText(
      locale === "en"
        ? "Retrying automatically"
        : "Автоматическая повторная попытка"
    );
    await expect(banner).toContainText(
      "finance: Ozon sync step failed (server, HTTP 500)"
    );
    await expect(
      page.getByRole("button", {
        name: locale === "en" ? "Retry now" : "Повторить сейчас",
      })
    ).toBeVisible();
    await expect(page.getByText("7", { exact: true })).toBeVisible();
  });
}

test("polls an active recovery to terminal status and stops", async ({
  page,
}) => {
  await useLocale(page, "en");
  await page.clock.install();
  let getCount = 0;
  let state: RecoveryStatus = "retrying";
  await page.route("**/api/integrations/ozon?*", (route) => {
    getCount += 1;
    return fulfillJson(route, ozonSummary(state));
  });

  await page.goto("/operations/marketplaces");
  await expect(page.getByRole("status")).toContainText(
    "Retrying automatically"
  );

  const initialGetCount = getCount;
  state = "completed";
  await page.clock.fastForward(10_000);
  await expect(
    page.getByText("Completed", { exact: true })
  ).toBeVisible();
  expect(getCount).toBe(initialGetCount + 1);

  await page.clock.fastForward(20_000);
  expect(getCount).toBe(initialGetCount + 1);
});

test("routes retry-now and retry-failed actions to the same run", async ({
  page,
}) => {
  await useLocale(page, "en");
  let state: RecoveryStatus = "retrying";
  const posts: Array<{ pathname: string; body: unknown }> = [];

  await page.route("**/api/integrations/ozon**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === "GET") {
      await fulfillJson(
        route,
        ozonSummary(state, state === "completed_with_errors" ? 1 : 0)
      );
      return;
    }

    posts.push({
      pathname: url.pathname,
      body: request.postDataJSON(),
    });
    state = posts.length === 1 ? "completed_with_errors" : "completed";
    await fulfillJson(route, {
      runId: "run-1",
      status: state,
      summary: { errors: [] },
      recovery: {
        pendingStepCount: 0,
        scheduledRetryCount: 0,
        failedStepCount: state === "completed_with_errors" ? 1 : 0,
        nextRetryAt: null,
      },
    });
  });

  await page.goto("/operations/marketplaces");
  await page.getByRole("button", { name: "Retry now" }).click();
  await expect(
    page.getByRole("button", { name: "Retry failed steps" })
  ).toBeVisible();
  await page.getByRole("button", { name: "Retry failed steps" }).click();

  await expect(page.getByText("Completed", { exact: true })).toBeVisible();
  expect(posts).toEqual([
    {
      pathname: "/api/integrations/ozon/sync/retry",
      body: { runId: "run-1" },
    },
    {
      pathname: "/api/integrations/ozon/sync/retry",
      body: { runId: "run-1" },
    },
  ]);
});

test("shows manual running state without stale automatic retry timing", async ({
  page,
}) => {
  await useLocale(page, "en");
  let releasePost!: () => void;
  const postReleased = new Promise<void>((resolve) => {
    releasePost = resolve;
  });
  let markPostStarted!: () => void;
  const postStarted = new Promise<void>((resolve) => {
    markPostStarted = resolve;
  });

  await page.route("**/api/integrations/ozon**", async (route) => {
    if (route.request().method() === "GET") {
      await fulfillJson(route, ozonSummary("retrying"));
      return;
    }
    markPostStarted();
    await postReleased;
    await fulfillJson(route, {
      runId: "run-1",
      status: "retrying",
      summary: { errors: [] },
      recovery: {
        pendingStepCount: 0,
        scheduledRetryCount: 1,
        failedStepCount: 0,
        nextRetryAt: "2026-07-26T10:01:00.000Z",
      },
    });
  });

  await page.goto("/operations/marketplaces");
  await page.getByRole("button", { name: "Retry now" }).click();
  await postStarted;

  const banner = page.getByRole("status");
  await expect(banner).toContainText("Ozon sync is running");
  await expect(
    page.getByText("Retrying automatically", { exact: true })
  ).toHaveCount(0);
  await expect(page.getByText(/Next retry/)).toHaveCount(0);

  releasePost();
  await expect(
    page.getByRole("button", { name: "Retry now" })
  ).toBeEnabled();
});

test("restores terminal recovery after a rejected failed-step retry", async ({
  page,
}) => {
  await useLocale(page, "ru");
  await page.route("**/api/integrations/ozon**", async (route) => {
    if (route.request().method() === "GET") {
      await fulfillJson(route, ozonSummary("completed_with_errors", 1));
      return;
    }
    await route.fulfill({
      status: 500,
      contentType: "text/plain",
      body: "not-json",
    });
  });

  await page.goto("/operations/marketplaces");
  await page
    .getByRole("button", { name: "Повторить неудачные шаги" })
    .click();

  await expect(
    page.getByText("Не удалось повторить синхронизацию Ozon.")
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Повторить неудачные шаги" })
  ).toBeVisible();
  await expect(
    page.getByText("Завершена с ошибками", { exact: true })
  ).toBeVisible();
});

test("uses a localized summary-load fallback in Russian", async ({ page }) => {
  await useLocale(page, "ru");
  await page.route("**/api/integrations/ozon?*", (route) =>
    fulfillJson(route, {}, 500)
  );

  await page.goto("/operations/marketplaces");
  await expect(
    page.getByText("Не удалось загрузить интеграцию Ozon.")
  ).toBeVisible();
});

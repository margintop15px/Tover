import { test } from "@playwright/test";

const AUTH_STATE_PATH = "playwright/.auth/user.json";

function hasAuthCredentials(): boolean {
  return Boolean(process.env.E2E_EMAIL && process.env.E2E_PASSWORD);
}

test("authenticate once and persist storage state", async ({ page }) => {
  if (!hasAuthCredentials()) {
    await page.context().storageState({ path: AUTH_STATE_PATH });
    test.skip(
      true,
      "Skipping authenticated setup because E2E_EMAIL/E2E_PASSWORD are not set"
    );
    return;
  }

  await page.goto("/login");

  await page.getByLabel("Email").fill(process.env.E2E_EMAIL!);
  await page.getByLabel("Password").fill(process.env.E2E_PASSWORD!);
  await page.getByRole("button", { name: "Log in" }).click();

  await page.waitForURL(/\/$/);
  await page.context().storageState({ path: AUTH_STATE_PATH });
});

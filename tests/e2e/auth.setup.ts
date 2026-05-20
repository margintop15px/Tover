import { test } from "@playwright/test";
import {
  AUTH_STATE_PATH,
  authSkipReason,
  ensureAuthStateDir,
  ensureDevAuthUser,
  getAuthCredentials,
} from "./auth-helpers";

test("authenticate once and persist storage state", async ({ page }) => {
  ensureAuthStateDir();

  const credentials = getAuthCredentials();
  if (!credentials) {
    await page.context().storageState({ path: AUTH_STATE_PATH });
    test.skip(true, `Skipping authenticated setup. ${authSkipReason()}`);
    return;
  }

  await ensureDevAuthUser(credentials);

  await page.goto("/login");

  await page.getByLabel("Email").fill(credentials.email);
  await page.getByLabel("Password").fill(credentials.password);
  await page.getByRole("button", { name: "Log in" }).click();

  const loginError = page.locator("form p").first();
  await Promise.race([
    page.waitForURL(/\/$/, { timeout: 15_000 }),
    loginError.waitFor({ state: "visible", timeout: 15_000 }).then(async () => {
      throw new Error(
        `Login failed: ${(await loginError.textContent()) ?? "unknown error"}`
      );
    }),
  ]);
  await page.context().storageState({ path: AUTH_STATE_PATH });
});

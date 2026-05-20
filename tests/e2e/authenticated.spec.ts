import { expect, test } from "@playwright/test";
import { authSkipReason, hasAuthCredentials } from "./auth-helpers";

test.describe("authenticated area", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
  });

  test("root redirects to operations for authenticated user", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/operations$/);
    await expect(page.getByRole("link", { name: "Settings" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
  });

  test("team page renders and invite form is visible", async ({ page }) => {
    await page.goto("/team");
    await expect(page).toHaveURL(/\/settings\?tab=team$/);
    await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
    await expect(
      page.getByRole("tab", { name: "Team", selected: true })
    ).toBeVisible();
    await expect(page.getByLabel("User email")).toBeVisible();
    await expect(page.getByRole("button", { name: "Send invite" })).toBeVisible();
  });
});

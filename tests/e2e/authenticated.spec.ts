import { expect, test } from "@playwright/test";

function hasAuthCredentials(): boolean {
  return Boolean(process.env.E2E_EMAIL && process.env.E2E_PASSWORD);
}

test.describe("authenticated area", () => {
  test.beforeEach(() => {
    test.skip(
      !hasAuthCredentials(),
      "Set E2E_EMAIL and E2E_PASSWORD to run authenticated tests"
    );
  });

  test("dashboard renders for authenticated user", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole("button", { name: "Team" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
  });

  test("team page renders and invite form is visible", async ({ page }) => {
    await page.goto("/team");
    await expect(page.getByRole("heading", { name: "Team" })).toBeVisible();
    await expect(page.getByLabel("User email")).toBeVisible();
    await expect(page.getByRole("button", { name: "Send invite" })).toBeVisible();
  });
});

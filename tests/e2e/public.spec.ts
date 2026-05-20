import { expect, test } from "@playwright/test";

test("app root redirects to operations", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/operations$/);
});

test("signup page is reachable", async ({ page }) => {
  await page.goto("/signup");
  await expect(page.getByRole("heading", { name: "Create account" })).toBeVisible();
});

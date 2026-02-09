import { expect, test } from "@playwright/test";

test("anonymous user is redirected to login from dashboard", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login\?next=%2F/);
  await expect(page.getByRole("heading", { name: "Log in" })).toBeVisible();
});

test("signup page is reachable", async ({ page }) => {
  await page.goto("/signup");
  await expect(page.getByRole("heading", { name: "Create account" })).toBeVisible();
});

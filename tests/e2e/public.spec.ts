import { expect, test } from "@playwright/test";

test("app root redirects unauthenticated users to login", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login/);
});

test("protected pages redirect unauthenticated users to login", async ({ page }) => {
  await page.goto("/reports/templates/new");
  await expect(page).toHaveURL(/\/login\?next=/);
});

test("signup page is reachable", async ({ page }) => {
  await page.goto("/signup");
  await expect(page.getByRole("heading", { name: "Create account" })).toBeVisible();
});

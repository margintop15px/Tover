import { expect } from '@playwright/test';
const stockGrid = page => page.locator('#stock-grid');

export const signInAction = '/action/tover.identity.sign-in.web/sign-in';

// Sign in, then explicitly select `workspace` (sign-in never selects one) and open Stock.
export async function signIn(page, email = 'owner@example.test', password = 'tover-development', workspace = 'Development workspace') {
  await page.goto('/auth/login');
  const form = page.locator(`form[action="${signInAction}"]`);
  await form.locator('[name="email"]').fill(email);
  await form.locator('[name="password"]').fill(password);
  const [response] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === signInAction && response.request().method() === 'POST'),
    form.locator('button[type="submit"]').click(),
  ]);
  expect(response.status()).toBe(303);
  await selectWorkspace(page, workspace);
  await expect(stockGrid(page)).toBeVisible();
  await expect.poll(() => stockGrid(page).evaluate(grid => Boolean(grid.worksheet))).toBe(true);
}

export async function selectWorkspace(page, name) {
  await page.goto('/workspace');
  await Promise.all([
    page.waitForURL(url => url.pathname === '/'),
    page.locator('.management-table tbody tr').filter({ hasText: name }).getByRole('button', { name: /^(Open|Открыть)$/ }).click(),
  ]);
}

export async function pageContext(page) {
  return page.evaluate(() => ({
    csrf: document.querySelector('meta[name="csrf-token"]').content,
    version: document.querySelector('meta[name="tover-context"]').content,
  }));
}

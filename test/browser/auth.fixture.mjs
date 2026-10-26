import { expect } from '@playwright/test';
import { stockGrid } from './grid.fixture.mjs';

export async function signIn(page, email = 'owner@example.test', password = 'tover-development') {
  await page.goto('/auth/login');
  const form = page.locator('form[action="/action/tover.auth.web/sign-in"]');
  await form.locator('[name="email"]').fill(email);
  await form.locator('[name="password"]').fill(password);
  const [response] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.auth.web/sign-in' && response.request().method() === 'POST'),
    form.locator('button[type="submit"]').click(),
  ]);
  expect(response.status()).toBe(303);
  await page.goto('/');
  await expect(stockGrid(page)).toBeVisible();
  await expect.poll(() => stockGrid(page).evaluate(grid => Boolean(grid.worksheet))).toBe(true);
}

export async function pageContext(page) {
  return page.evaluate(() => ({
    csrf: document.querySelector('meta[name="csrf-token"]').content,
    version: document.querySelector('meta[name="tover-context"]').content,
  }));
}

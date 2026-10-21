import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

test.beforeEach(async ({ page }) => signIn(page));

test('development examples switch to recorded stock without changing it', async ({ page }) => {
  await page.goto('/');
  const grid = page.locator('#stock-revogrid');
  await expect(grid.getByText('Notebook A5', { exact: true })).toBeVisible();
  await expect(page.locator('.demo-label')).toHaveCount(0);
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  const examples = page.getByRole('checkbox', { name: 'Demo data', exact: true });
  await expect(examples).toBeChecked();
  await expect(page.locator('#operations-history')).toContainText('8 desk kits');

  await examples.uncheck();
  await expect(page.locator('#operations-history')).toContainText('Recorded smoke stock');
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await expect(grid.getByText('Recorded smoke stock', { exact: true })).toBeVisible();
  await expect(grid.getByText('Notebook A5', { exact: true })).toHaveCount(0);
  await expect(page.locator('.demo-label')).toHaveCount(0);

  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await examples.check();
  await expect(page.locator('#operations-history')).toContainText('8 desk kits');
  await examples.uncheck();
  await expect(page.locator('#operations-history')).toContainText('Recorded smoke stock');
});

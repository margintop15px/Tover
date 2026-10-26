import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';
import { draftGrid, operationGrid, stockGrid } from './grid.fixture.mjs';

test('development inventory uses the same empty durable data layer without demo fixtures', async ({ page }) => {
  await signIn(page);
  await expect(stockGrid(page)).toBeVisible();
  await expect.poll(() => stockGrid(page).evaluate(grid => grid.model.rows.length)).toBe(0);
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  await expect(draftGrid(page)).toBeHidden();
  await expect(page.locator('#inventory-review')).toBeEmpty();
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(operationGrid(page)).toBeVisible();
  await expect.poll(() => operationGrid(page).evaluate(grid => Boolean(grid.worksheet))).toBe(true);
  await expect.poll(() => operationGrid(page).evaluate(grid => grid.model.rows.length)).toBe(0);
  await expect(page.getByText('No operations yet.', { exact: true })).toBeVisible();
  await expect(page.getByText('Demo data', { exact: true })).toHaveCount(0);
});

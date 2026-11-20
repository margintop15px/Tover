import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

test('development inventory uses the same empty durable data layer without demo fixtures', async ({ page }) => {
  await signIn(page);
  await expect(page.locator('#stock-grid')).toBeVisible();
  await expect.poll(() => page.locator('#stock-grid').evaluate(grid => grid.model.rows.length)).toBe(0);
  await expect(page.getByText('Demo data', { exact: true })).toHaveCount(0);
});

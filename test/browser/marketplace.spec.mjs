import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

// Board 42 · Product Matching: one table; select rows, Confirm, and the mappings survive a reload.
const connection = '/settings/marketplace/browser-ozon';
const grid = page => page.locator('.marketplace-grid');
const workbench = page => page.locator('marketplace-workbench');
const row = (page, name) => grid(page).locator('tbody tr').filter({ hasText: name });

async function ready(page, id) {
  await expect.poll(() => grid(page).evaluate(node => !!node.worksheet)).toBe(true);
  await expect.poll(() => grid(page).evaluate(node => node.model.id)).toBe(id);
}

test('select three rows with the keyboard, confirm, reload: still confirmed (400 px)', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 400, height: 860 });
  await signIn(page);
  await page.goto(connection);
  const unmatched = page.getByRole('link', { name: '5', exact: true });
  await expect(unmatched).toHaveAttribute('href', `${connection}/products`);
  // Keyboard only from here: follow the link, move through the table with arrows, tick with Space.
  await unmatched.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`${connection}/products$`));
  await expect(page.getByRole('heading', { name: 'Product matching' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Back to the connection' })).toHaveAttribute('href', connection);
  await ready(page, 'marketplace-products-unmatched');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(grid(page)).toHaveCount(1);
  await expect(row(page, 'Kettle (Ozon)')).toContainText('Kettle · same barcode');

  // The table starts at its first cell, the first row's box.
  await grid(page).focus();
  await expect.poll(() => workbench(page).evaluate(node => node.current?.selection?.id ?? null)).not.toBeNull();
  const order = await grid(page).evaluate(node => node.model.rows.map(row => row.label));
  for (let index = 0; index < order.length; index++) {
    if (order[index] !== 'Gift card') await page.keyboard.press('Space');
    if (index < order.length - 1) await page.keyboard.press('ArrowDown');
  }
  await expect(page.locator('.marketplace-selected')).toHaveText('3 selected');
  const confirm = page.getByRole('button', { name: 'Confirm', exact: true });
  await expect(confirm).toBeEnabled();
  await confirm.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.map(row => row.label))).toEqual(['Gift card']);
  await expect(page.locator('.catalogue-notice')).toHaveText('');

  await page.reload();
  await ready(page, 'marketplace-products-unmatched');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.map(row => row.label))).toEqual(['Gift card']);
  await page.goto(`${connection}/products?state=confirmed`);
  await ready(page, 'marketplace-products-confirmed');
  for (const name of ['Kettle', 'Teapot', 'Saucer']) await expect(row(page, `${name} (Ozon)`)).toContainText('Mapped');
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(400);
  expect(errors).toEqual([]);
});

test('a rejected row keeps its values and shows why', async ({ page }) => {
  await signIn(page);
  await page.goto(`${connection}/products`);
  await ready(page, 'marketplace-products-unmatched');
  const gift = await grid(page).evaluate(node => node.model.rows.find(row => row.label === 'Gift card').id);
  await workbench(page).evaluate((node, id) => node.select([id]), gift);
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.locator('.catalogue-notice')).toHaveText('Gift card: Choose a stock item.');
  await expect(row(page, 'Gift card').locator('td.marketplace-finding')).toHaveCount(1);
  await expect(page.locator('.marketplace-selected')).toHaveText('1 selected');
});

// Board 43 · Warehouse Matching: the same table; several rows confirmed at once survive a reload.
test('confirm several warehouses, reload: still confirmed', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await page.goto(connection);
  const unmatched = page.getByRole('link', { name: '3', exact: true });
  await expect(unmatched).toHaveAttribute('href', `${connection}/warehouses`);
  await unmatched.click();
  await expect(page.getByRole('heading', { name: 'Warehouse matching' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Back to the connection' })).toHaveAttribute('href', connection);
  await ready(page, 'marketplace-warehouses-unmatched');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(row(page, 'Khorugvino')).toContainText('New warehouse: Khorugvino');

  const ids = await grid(page).evaluate(node => node.model.rows.map(row => row.id));
  expect(ids).toHaveLength(3);
  await workbench(page).evaluate((node, selected) => node.select(selected), ids);
  await expect(page.locator('.marketplace-selected')).toHaveText('3 selected');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await expect(page.locator('.catalogue-notice')).toHaveText('');

  await page.reload();
  await ready(page, 'marketplace-warehouses-unmatched');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await page.goto(`${connection}/warehouses?state=confirmed`);
  await ready(page, 'marketplace-warehouses-confirmed');
  for (const name of ['Khorugvino', 'Tver', 'Seller stock']) await expect(row(page, name)).toContainText('Mapped');
  expect(errors).toEqual([]);
});

// Board 42 · a suspended mapping is reconfirmed in the table, not on a details form.
test('reconfirm a suspended row in the table, reload: confirmed', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await page.goto(`${connection}/products?state=suspended`);
  await ready(page, 'marketplace-products-suspended');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(row(page, 'Ladle (Ozon)')).toContainText('Suspended');
  await expect(row(page, 'Ladle (Ozon)')).toContainText('Ladle');
  const id = await grid(page).evaluate(node => node.model.rows.find(row => row.label === 'Ladle (Ozon)').id);
  await workbench(page).evaluate((node, selected) => node.select([selected]), id);
  await expect(page.locator('.marketplace-selected')).toHaveText('1 selected');
  await page.getByRole('button', { name: 'Reconfirm', exact: true }).click();
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await expect(page.locator('.catalogue-notice')).toHaveText('');

  await page.reload();
  await ready(page, 'marketplace-products-suspended');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await page.goto(`${connection}/products?state=confirmed`);
  await ready(page, 'marketplace-products-confirmed');
  await expect(row(page, 'Ladle (Ozon)')).toContainText('Mapped');
  await page.goto(`${connection}/products/${encodeURIComponent(id)}`);
  await expect(page.locator('section.management-page form')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Open in the matching table' })).toHaveAttribute('href', `${connection}/products?state=confirmed`);
  expect(errors).toEqual([]);
});

import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

// Board 42 · Product Matching: one table; select rows, Confirm, and the mappings survive a reload.
const connection = '/settings/marketplace/browser-ozon';
const views = '/operations/marketplace/browser-ozon';
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
  await expect(unmatched).toHaveAttribute('href', `${views}/products`);
  // Keyboard only from here: follow the link, move through the table with arrows, tick with Space.
  await unmatched.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`${views}/products$`));
  await expect(page.getByRole('heading', { name: 'Product matching' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Back to operations' })).toHaveAttribute('href', '/operations');
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
  await page.goto(`${views}/products?state=confirmed`);
  await ready(page, 'marketplace-products-confirmed');
  for (const name of ['Kettle', 'Teapot', 'Saucer']) await expect(row(page, `${name} (Ozon)`)).toContainText('Mapped');
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(400);
  expect(errors).toEqual([]);
});

test('a rejected row keeps its values and shows why', async ({ page }) => {
  await signIn(page);
  await page.goto(`${views}/products`);
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
  await expect(unmatched).toHaveAttribute('href', `${views}/warehouses`);
  await unmatched.click();
  await expect(page.getByRole('heading', { name: 'Warehouse matching' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Back to operations' })).toHaveAttribute('href', '/operations');
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
  await page.goto(`${views}/warehouses?state=confirmed`);
  await ready(page, 'marketplace-warehouses-confirmed');
  for (const name of ['Khorugvino', 'Tver', 'Seller stock']) await expect(row(page, name)).toContainText('Mapped');
  expect(errors).toEqual([]);
});

// Board 42 · a suspended mapping is reconfirmed in the table, not on a details form.
test('reconfirm a suspended row in the table, reload: confirmed', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await page.goto(`${views}/products?state=suspended`);
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
  await page.goto(`${views}/products?state=confirmed`);
  await ready(page, 'marketplace-products-confirmed');
  await expect(row(page, 'Ladle (Ozon)')).toContainText('Mapped');
  await page.goto(`${views}/products/${encodeURIComponent(id)}`);
  await expect(page.locator('section.management-page form')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Open in the matching table' })).toHaveAttribute('href', `${views}/products?state=confirmed`);
  expect(errors).toEqual([]);
});

// Matching is a view of the Operations page: the switcher lists it, and the Operations tab stays current.
test('the view switcher moves between operations and the matching views', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await page.goto('/operations');
  const switcher = page.getByRole('navigation', { name: 'Views' });
  await expect(switcher.getByRole('link', { name: 'Operations', exact: true })).toHaveAttribute('aria-current', 'page');
  await switcher.getByRole('link', { name: /^Warehouse matching/ }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/warehouses$`));
  await expect(page.getByRole('heading', { name: 'Warehouse matching' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Warehouse matching/ })).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.app-nav-pages a[aria-current=page]')).toHaveText('Operations');
  await page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(page).toHaveURL(/\/operations$/);
  expect(errors).toEqual([]);
});

// Board 44 · Cutover: a view of the Operations page; type the cost of an opening, propose, and the scope waits in the
// shared draft with its observation shown in the Stock page's Preview.
test('propose the cutover of a ready scope, reload: pending and shown in Preview', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const setup = page.locator('#marketplace-cutover-grid');
  const whisk = setup.locator('tbody tr').filter({ hasText: 'Whisk' });
  await signIn(page);
  await page.goto(connection);
  await expect(page.locator(`a[href="${views}/cutover"]`).first()).toBeVisible();
  await page.goto('/operations');
  await page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Cutover/ }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/cutover$`));
  await expect(page.getByRole('heading', { name: 'Cutover', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Cutover/ })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByLabel('Cutover instant')).toHaveValue('2026-11-20T10:00:00Z');
  await expect.poll(() => setup.evaluate(node => !!node.worksheet)).toBe(true);
  await expect(whisk).toContainText('Ready');
  await expect(whisk).toContainText('Opening');
  await expect(whisk.locator('td[data-x="7"]')).toHaveText('7');

  // The cost is typed with a comma, as for any opening.
  await whisk.locator('td[data-x="8"]').dblclick();
  await page.keyboard.type('250,5');
  await page.keyboard.press('Enter');
  await expect(whisk.locator('td[data-x="8"]')).toHaveText('250,5');
  const id = await setup.evaluate(node => node.model.rows.find(row => row.label.startsWith('Whisk')).id);
  await page.locator('marketplace-workbench#marketplace-cutover').evaluate((node, selected) => node.select([selected]), id);
  await expect(page.locator('#marketplace-cutover .marketplace-selected')).toHaveText('1 selected');
  await page.getByRole('button', { name: 'Propose', exact: true }).click();
  await expect(whisk).toContainText('Pending');
  await expect(whisk).toContainText('Proposed 7, difference +7');
  await expect(page.locator('#marketplace-cutover .catalogue-notice')).toHaveText('');

  await page.reload();
  await expect.poll(() => setup.evaluate(node => !!node.worksheet)).toBe(true);
  await expect(whisk).toContainText('Pending');
  await expect(page.locator('#marketplace-cutover-coverage-grid tbody tr').filter({ hasText: 'Whisk' })).toContainText('No cutover baseline');

  // Review and acceptance are the shared draft's: the pending opening shows its observation in Preview.
  await page.getByRole('link', { name: 'Review and accept them on the Stock page.' }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.getByRole('button', { name: 'Preview pending changes' }).click();
  await expect.poll(() => page.locator('#stock-grid').evaluate(node => (node.model?.rows || []).flatMap(row => row.cells.map(cell => cell.title || '')).join('\n')))
    .toContain('Cutover: observed 7, proposed 7, difference +7 from Tover');
  expect(errors).toEqual([]);
});

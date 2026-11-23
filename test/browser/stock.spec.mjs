import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

const pad = value => String(value).padStart(2, '0');
const yesterday = () => {
  const date = new Date(Date.now() - 86_400_000);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T10:00`;
};

async function editCell(page, grid, id, path, value) {
  const input = grid.locator(`td[data-entry-id="${id}"][data-path="${path}"] .inventory-cell-editor > input`);
  await expect(async () => {
    if (!(await input.isVisible())) await grid.evaluate((node, { id, path }) => node.focusField(id, path), { id, path });
    await expect(input).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  await input.fill(value);
  await input.press('Enter');
}

async function registerCatalogueRecord(page, path, name) {
  await page.goto(path);
  const grid = page.locator('.catalogue-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  const id = await page.locator('catalogue-workbench').evaluate(node => node.drafts[0].id);
  await editCell(page, grid, id, 'name', name);
  await page.locator('.catalogue-save-new').click();
  await expect(page.locator('.catalogue-save-new')).toBeHidden();
  await expect(grid).toContainText(name);
}

const shot = (page, name) => process.env.STOCK_SHOTS && page.screenshot({ path: `${process.env.STOCK_SHOTS}/${name}.png`, fullPage: true });

async function cell(page, rowId, column, value) {
  const grid = page.locator('#stock-grid');
  await expect.poll(() => grid.evaluate((node, { rowId, column }) => node.model.rows.some(row => row.id === rowId && row.cells.some(c => c.target?.path === column && !c.readOnly)), { rowId, column })).toBe(true);
  await editCell(page, grid, rowId, column, value);
  await expect(page.locator('.stock-save-status')).toHaveText('');
  await expect.poll(() => page.locator('stock-workbench').evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
}

test('find a product, add a stock item with an opening in the one table, review and accept', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Main ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues', `T-shirt ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues', `Mug ${suffix}`);

  await page.goto('/');
  const workbench = page.locator('stock-workbench');
  const grid = page.locator('#stock-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await shot(page, '1-stock');
  await page.locator('.stock-search-input').fill(`T-shirt ${suffix}`);
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await shot(page, '2-suggestions');
  await page.locator('.stock-search-input').press('Enter');
  await expect(page.locator('.stock-filter-chip')).toHaveText(`T-shirt ${suffix} ×`);
  const productId = await workbench.evaluate(node => node.scope.productId);
  const warehouse = await grid.evaluate((node, name) => node.model.columns.find(column => column.label === name).key, `Main ${suffix}`);
  await shot(page, '3-filtered');

  await cell(page, `new-row:${productId}`, 'item', 'M');
  await cell(page, `new-row:${productId}`, warehouse, '5');
  await expect(grid.locator('td.stock-cell-pending').first()).toContainText('5');
  await cell(page, `new:${productId}:M`, 'unit-cost', '100');
  await cell(page, `new-row:${productId}`, 'item', 'S');
  await cell(page, `new-row:${productId}`, warehouse, '2');
  await shot(page, '4-pending');

  await expect(page.locator('.stock-submit')).toBeDisabled();
  await page.locator('.stock-pending-filter').click();
  await expect(page.locator('.stock-submit')).toBeEnabled();
  // Money previews the same pending rows.
  await page.locator('.stock-mode button[data-mode="money"]').click();
  await expect.poll(() => grid.evaluate(node => node.model.rows.some(row => row.cells.some(cell => cell.prefix === '+' && cell.value === '500.00')))).toBe(true);
  await expect(page.locator('.stock-submit')).toBeEnabled();
  await page.locator('.stock-mode button[data-mode="units"]').click();
  await expect.poll(() => grid.evaluate(node => node.model.rows.some(row => row.cells.some(cell => cell.prefix === '+' && cell.value === '5')))).toBe(true);
  // Leave S for a later round: only M is submitted.
  await grid.locator(`input[data-review-entry="new:${productId}:S"]`).uncheck();
  await page.locator('.stock-submit-time').fill(yesterday());
  await page.locator('.stock-submit-reason').fill('Stocktake');
  await shot(page, '5-preview');
  await page.locator('.stock-submit').click();
  await expect.poll(() => grid.evaluate(node => node.model.rows.map(row => row.id))).toContain(`new:${productId}:S`);
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
  const row = grid.locator('tbody tr').filter({ has: page.locator('td[data-column-key="item"]', { hasText: /^\s*M\s*$/ }) });
  await expect(row.locator(`td[data-column-key="${warehouse}"]`)).toHaveText('5');
  await page.locator('.stock-mode button[data-mode="money"]').click();
  await expect(row.locator(`td[data-column-key="${warehouse}"]`)).toHaveText('500.00');
  await expect(row.locator('td[data-column-key="total"]')).toHaveText('500.00');
  await shot(page, '6-accepted');
  // S is still pending; discard it from the preview.
  page.on('dialog', dialog => dialog.accept());
  await page.locator('.stock-pending-filter').click();
  await grid.locator(`tr[data-row-id="new:${productId}:S"]`).getByRole('button', { name: 'Discard pending changes' }).click();
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.some(row => row.id === id), `new:${productId}:S`)).toBe(false);
  await page.locator('.stock-pending-filter').click();
  await row.getByRole('button', { name: 'History' }).click();
  await expect(page.locator('.stock-history-rows')).toContainText('Opening');
  await expect(page.locator('.stock-history-rows')).toContainText('Stocktake');
  await shot(page, '7-history');

  // "+" opens a new row under the product without filtering the table.
  await page.locator('.stock-filter-chip').click();
  await page.locator('.stock-hide-empty').click();
  // Unfiltered: no new row, and other products besides this one.
  await expect.poll(() => grid.evaluate((node, id) => !node.model.rows.some(row => row.id.startsWith('new-row'))
    && node.model.rows.some(row => row.cells.some(cell => cell.actions?.some(action => action.type === 'stock-add-item' && action.id !== id))), productId)).toBe(true);
  const before = await grid.evaluate(node => node.model.rows.length);
  const productRow = await grid.evaluate((node, id) => node.model.rows.find(row => row.cells.some(cell => cell.actions?.some(action => action.type === 'stock-add-item' && action.id === id))).id, productId);
  await grid.locator(`tr[data-row-id="${productRow}"]`).getByRole('button', { name: 'Add stock item' }).click();
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.some(row => row.id === `new-row:${id}`), productId)).toBe(true);
  expect(await grid.evaluate(node => node.model.rows.length)).toBe(before + 1);
  expect(errors).toEqual([]);
});

test('creating a product opens it at the top of the preview with an editable cost', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Back ${suffix}`);
  await page.goto('/');
  const grid = page.locator('#stock-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-search-input').fill(`Cap ${suffix}`);
  await expect(page.locator('.stock-option-create')).toBeVisible();
  await page.locator('.stock-option-create').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.stock-filter-chip')).toBeHidden();
  await expect.poll(() => grid.evaluate(node => node.model.rows[0]?.id)).toBe('new-row:new');
  const warehouse = await grid.evaluate((node, name) => node.model.columns.find(column => column.label === name).key, `Back ${suffix}`);

  await cell(page, 'new-row:new', 'item', 'Red');
  await cell(page, 'new-row:new', 'unit-cost', '10');
  await cell(page, 'new-row:new', warehouse, '3');
  const row = () => grid.evaluate((node, suffix) => node.model.rows.find(row => row.id.includes(suffix) && row.id.endsWith(':Red'))?.id ?? null, String(suffix));
  await expect.poll(row).not.toBeNull();
  const key = await row();
  await expect(grid.locator(`tr[data-row-id="${key}"] td[data-column-key="unit-cost"]`)).toHaveText('10');
  await expect(grid.locator(`input[data-review-entry="${key}"]`)).toBeChecked();
  await expect(grid.locator('tr[data-row-id="new-row:' + key.split(':').slice(1, 3).join(':') + '"] td[data-column-key="unit-cost"]')).toHaveText('');
  await shot(page, '8-created');
  // Back in preview later, "+" on the new product opens another new row under it.
  await page.locator('.stock-pending-filter').click();
  await page.locator('.stock-pending-filter').click();
  const blank = `new-row:${key.split(':').slice(1, 3).join(':')}`;
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.some(row => row.id === id), blank)).toBe(false);
  await grid.locator(`tr[data-row-id="${key}"]`).getByRole('button', { name: 'Add stock item' }).click();
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.some(row => row.id === id), blank)).toBe(true);
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
  await expect.poll(() => grid.evaluate(node => node.model.rows.some(row => row.id.startsWith('new-row')))).toBe(false);
  expect(errors).toEqual([]);
});

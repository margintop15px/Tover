import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

// Board chapters 18–25: record operations on the operations page, preview, submit, see stock change.

async function registerCatalogueRecord(page, path, name) {
  await page.goto(path);
  const grid = page.locator('.catalogue-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  const id = await page.locator('catalogue-workbench').evaluate(node => node.drafts[0].id);
  const input = grid.locator(`td[data-entry-id="${id}"][data-path="name"] .inventory-cell-editor > input`);
  await expect(async () => {
    if (!(await input.isVisible())) await grid.evaluate((node, id) => node.focusField(id, 'name'), id);
    await expect(input).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  await input.fill(name); await input.press('Enter');
  await page.locator('.catalogue-save-new').click();
  await expect(page.locator('.catalogue-save-new')).toBeHidden();
  await expect(grid).toContainText(name);
}

const shot = (page, name) => process.env.STOCK_SHOTS && page.screenshot({ path: `${process.env.STOCK_SHOTS}/${name}.png`, fullPage: true });

const idle = (page, tag) => expect.poll(() => page.locator(tag).evaluate(node => !node.pending && node.queued.size === 0
  && node.tableRequest && node.handled?.has(node.tableRequest))).toBe(true);

async function editor(grid, id, path, selector) {
  const control = grid.locator(`td[data-entry-id="${id}"][data-path="${path}"] ${selector}`);
  await expect(async () => {
    if (!(await control.isVisible())) await grid.evaluate((node, { id, path }) => node.focusField(id, path), { id, path });
    await expect(control).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  return control;
}

async function type(page, id, path, value) {
  const input = await editor(page.locator('#operations-grid'), id, path, '.inventory-cell-editor > input');
  await input.fill(value); await input.press('Enter');
  await idle(page, 'operations-workbench');
}

async function choose(page, id, path, label, selector = '.inventory-cell-editor > select') {
  const select = await editor(page.locator('#operations-grid'), id, path, selector);
  if (selector !== '.inventory-cell-editor > select') await expect(select.locator('option').filter({ hasText: label })).toHaveCount(1);
  await select.selectOption({ label });
  if (selector === '.inventory-cell-editor > select') await select.press('Enter');
  await idle(page, 'operations-workbench');
}

async function addOperation(page, kind) {
  const grid = page.locator('#operations-grid');
  const before = await grid.evaluate(node => node.model.pending.map(entry => entry['entry-id']));
  await page.locator('.operations-add').selectOption(kind);
  await idle(page, 'operations-workbench');
  const entry = await grid.evaluate((node, before) => node.model.pending.find(entry => !before.includes(entry['entry-id']))['entry-id'], before);
  const line = await grid.evaluate((node, entry) => node.model.rows.find(row => row.groupId === entry).id, entry);
  return { group: `${entry}|`, line };
}

async function submit(page) {
  await page.locator('.stock-pending-filter').click();
  await expect(page.locator('.stock-submit')).toBeEnabled();
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
  await idle(page, 'operations-workbench');
}

test('record a purchase and a sale, preview, submit, and see stock change', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Main ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Back ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues', `T-shirt ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues/suppliers', `Textiles ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues/stores', `Web shop ${suffix}`);

  // Opening stock: 5 at 100.00, through the stock page.
  await page.goto('/');
  const stock = page.locator('#stock-grid');
  await expect.poll(() => stock.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-search-input').fill(`T-shirt ${suffix}`);
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await page.locator('.stock-search-input').press('Enter');
  const productId = await page.locator('stock-workbench').evaluate(node => node.scope.productId);
  const warehouse = await stock.evaluate((node, name) => node.model.columns.find(column => column.label === name).key, `Main ${suffix}`);
  const stockCell = async (rowId, column, value) => {
    const input = await editor(stock, rowId, column, '.inventory-cell-editor > input');
    await input.fill(value); await input.press('Enter');
    await expect.poll(() => page.locator('stock-workbench').evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
  };
  await stockCell(`new-row:${productId}`, 'item', 'M');
  await stockCell(`new-row:${productId}`, warehouse, '5');
  await stockCell(`new:${productId}:M`, 'unit-cost', '100');
  const unit = await editor(stock, `new:${productId}:M`, 'unit', '.inventory-cell-editor > select');
  await unit.selectOption({ label: 'pcs' }); await unit.press('Enter');
  await expect.poll(() => page.locator('stock-workbench').evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
  await page.locator('.stock-pending-filter').click();
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');

  // Purchase 5 at 200.00 from the supplier; the warehouse is prefilled from the item's only stock.
  await page.getByRole('link', { name: 'Operations' }).click();
  const grid = page.locator('#operations-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  const purchase = await addOperation(page, 'purchase');
  await choose(page, purchase.group, 'supplier', `Textiles ${suffix}`, '.inventory-goods-choice');
  await choose(page, purchase.line, 'item', `T-shirt ${suffix} / M`, '.inventory-goods-choice');
  await expect(grid.locator(`td[data-entry-id="${purchase.line}"][data-path="warehouse"]`)).toHaveText(`Main ${suffix}`);
  await type(page, purchase.line, 'quantity', '5');
  await type(page, purchase.line, 'price', '200');
  await expect(grid.locator(`tr[data-row-id="${purchase.line}"] td[data-column-key="amount"]`)).toHaveText('1000.00');
  await shot(page, 'operations-1-purchase');
  await submit(page);
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(0);
  await expect(grid).toContainText(`Textiles ${suffix}`);

  // Sale of 3 from the store.
  const sale = await addOperation(page, 'sale');
  await choose(page, sale.group, 'store', `Web shop ${suffix}`);
  await choose(page, sale.line, 'item', `T-shirt ${suffix} / M`, '.inventory-goods-choice');
  await type(page, sale.line, 'quantity', '3');
  await type(page, sale.line, 'price', '250');
  await page.locator('.stock-pending-filter').click();
  await shot(page, 'operations-2-preview');
  await page.locator('.stock-pending-filter').click();
  await idle(page, 'operations-workbench');
  await submit(page);
  await shot(page, 'operations-3-accepted');
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(0);

  // Transfer 2 from Main to Back: a red leaving row and a green entering row; the cost moves with the units.
  const transfer = await addOperation(page, 'transfer');
  await choose(page, transfer.line, 'item', `T-shirt ${suffix} / M`, '.inventory-goods-choice');
  await choose(page, transfer.line, 'from-warehouse', `Main ${suffix}`);
  await choose(page, transfer.line, 'to-warehouse', `Back ${suffix}`);
  await type(page, transfer.line, 'quantity', '2');
  await expect(grid.locator(`tr[data-row-id="${transfer.line}|in"] td[data-column-key="quantity"]`)).toHaveText('2');
  await shot(page, 'operations-5-transfer');
  await submit(page);
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(0);

  // A payment made to the supplier: one row, no stock effect.
  const payment = await addOperation(page, 'payment');
  await choose(page, payment.group, 'direction', 'Payment made');
  await choose(page, payment.group, 'counterparty', `Textiles ${suffix}`);
  await type(page, payment.group, 'amount', '1000');
  await submit(page);
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(0);
  await expect(grid).toContainText('Payment made');

  // Stock: 5 + 5 − 3 = 7, valued 7 × 150.00.
  await page.getByRole('link', { name: 'Stock' }).click();
  await expect.poll(() => stock.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-search-input').fill(`T-shirt ${suffix}`);
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await page.locator('.stock-search-input').press('Escape');
  await page.locator('.stock-mode button[data-mode="money"]').click();
  await expect.poll(() => stock.evaluate((node, name) => node.model.rows.some(row => row.cells.some(cell => cell.value === '1050.00')), warehouse)).toBe(true);
  expect(await stock.evaluate(node => node.model.rows.some(row => row.cells.some(cell => cell.value === '300.00')))).toBe(true);
  expect(errors).toEqual([]);
});


test('smart input: add a purchase with Done, keep it across a reload, cancel with Escape', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/suppliers', `Cotton ${suffix}`);

  // From the stock search, "add" opens the operations page with the addition started.
  await page.goto('/');
  await page.locator('.stock-search-input').fill('add');
  await page.locator('.stock-option').filter({ hasText: 'Add operation: Purchase' }).click();
  await expect(page).toHaveURL(/\/operations$/);
  const chips = page.locator('.operations-smart-chips');
  await expect(chips).toBeVisible();
  await expect(page.locator('.operations-smart-done')).toBeDisabled();
  await chips.locator('.operations-chip-party').fill(`Cotton ${suffix}`);
  await chips.locator('.operations-chip-item').fill(`Tote ${suffix}`);
  await chips.locator('.operations-chip-quantity').fill('4');
  await page.reload();
  await expect(chips.locator('.operations-chip-quantity')).toHaveValue('4');
  await expect(page.locator('.operations-smart-done')).toBeEnabled();
  await shot(page, 'operations-4-smart');
  const grid = page.locator('#operations-grid');
  const before = await grid.evaluate(node => node.model.pending.length);
  await chips.locator('.operations-chip-quantity').press('Control+Enter');
  await expect(chips).toBeHidden();
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(before + 1);
  await expect(grid).toContainText(`Tote ${suffix}`);
  await expect(grid).toContainText(`Cotton ${suffix}`);

  // A write-off needs a reason before Done.
  await page.locator('.operations-smart-input').fill('add write');
  await page.locator('.operations-smart-input').press('Enter');
  await expect(chips.locator('.operations-chip-kind')).toHaveText('Write-off');
  await expect(chips.locator('.operations-chip-party')).toHaveCount(0);
  await chips.locator('.operations-chip-item').fill(`Tote ${suffix}`);
  await chips.locator('.operations-chip-quantity').fill('1');
  await expect(page.locator('.operations-smart-done')).toBeDisabled();
  await chips.locator('.operations-chip-reason').fill('Damaged');
  await expect(page.locator('.operations-smart-done')).toBeEnabled();
  await page.locator('.operations-smart-cancel').click();
  await expect(chips).toBeHidden();

  // Cancel: Escape on an empty field drops the addition only.
  await page.locator('.operations-smart-input').fill('add s');
  await page.locator('.operations-smart-input').press('Enter');
  await expect(chips.locator('.operations-chip-kind')).toHaveText('Sale');
  await chips.locator('.operations-chip-item').press('Escape');
  await expect(chips).toBeHidden();
  await page.reload();
  await expect(chips).toBeHidden();
  expect(await grid.evaluate(node => node.model.pending.length)).toBe(before + 1);
  expect(errors).toEqual([]);
});

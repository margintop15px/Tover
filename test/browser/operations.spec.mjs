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
  // The opening takes its own time, before the operations below.
  const opened = new Date(Date.now() - 2 * 86_400_000);
  const pad = value => String(value).padStart(2, '0');
  await stockCell(`new:${productId}:M`, 'occurred-at', `${opened.getFullYear()}-${pad(opened.getMonth() + 1)}-${pad(opened.getDate())}T10:00`);
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
  await shot(page, 'operations-2-preview');
  // Outside Preview the operation is read-only, in units or money; Edit returns to it.
  await page.locator('.stock-pending-filter').click();
  await idle(page, 'operations-workbench');
  expect(await grid.evaluate(node => node.model.rows.every(row => row.cells.every(cell => !cell.editor)))).toBe(true);
  await page.locator('.stock-mode button[data-mode="money"]').click();
  await idle(page, 'operations-workbench');
  await expect(grid.locator(`tr[data-row-id="${sale.line}"] td[data-column-key="quantity"]`)).toHaveText('750.00');
  await page.locator('.stock-mode button[data-mode="units"]').click();
  await idle(page, 'operations-workbench');
  await grid.locator(`tr[data-row-id="${sale.line}"]`).getByRole('button', { name: 'Edit' }).click();
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
  await expect(grid.locator(`tr[data-row-id="${transfer.line}|in"] td[data-column-key="quantity"]`)).toHaveText('+2');
  await shot(page, 'operations-5-transfer');
  await submit(page);
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(0);

  // A payment made to the supplier: one row, no stock effect.
  const payment = await addOperation(page, 'payment');
  await choose(page, payment.group, 'direction', 'Payment made');
  await choose(page, payment.group, 'supplier', `Textiles ${suffix}`);
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
  await page.locator('.stock-entry-option').filter({ hasText: 'Purchase' }).first().click();
  await expect(page).toHaveURL(/\/operations$/);
  const smart = page.locator('smart-entry');
  const input = smart.locator('.stock-entry-input');
  const chip = key => smart.locator(`.stock-entry-chip[data-key="${key}"]`);
  const option = text => smart.locator('.stock-entry-option').filter({ hasText: text });
  const fill = async (field, value) => {
    await input.fill(field);
    await option(field).first().click();
    await expect(smart.locator('.stock-entry-field')).toHaveText(`${field}:`);
    await input.fill(value);
    await input.press('Enter');
  };
  await expect(input).toBeVisible();
  await fill('Supplier', `Cotton ${suffix}`);
  await expect(chip('supplier')).toContainText(`Cotton ${suffix}`);
  await fill('Stock item', `Tote ${suffix}`);
  // A quantity must be a whole number of 1 or more.
  await fill('Quantity', '0');
  await expect(smart.locator('.stock-entry-feedback')).toBeVisible();
  await input.fill('4');
  await expect(smart.locator('.stock-entry-option').first()).toContainText('Done');
  await input.press('Enter');
  await page.reload();
  await expect(chip('lines/0/quantity')).toContainText('4');
  await shot(page, 'operations-4-smart');
  const grid = page.locator('#operations-grid');
  const before = await grid.evaluate(node => node.model.rows.length && node.closest('operations-workbench').model.pending.length);
  const pending = () => page.locator('operations-workbench').evaluate(node => node.model.pending.length);
  await input.press('Control+Enter');
  await expect(smart.locator('.stock-entry-chip')).toHaveCount(0);
  await expect.poll(pending).toBe(before + 1);
  // Done opens the new operation in Preview.
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'true');
  await expect(grid).toContainText(`Tote ${suffix}`);
  await expect(grid).toContainText(`Cotton ${suffix}`);

  // A write-off needs a reason before Done, and an existing item.
  await input.fill('add write'); await input.press('Enter');
  await expect(smart.locator('.stock-entry-chip').first()).toContainText('Write-off');
  await fill('Stock item', `Missing ${suffix}`);
  await expect(smart.locator('.stock-entry-feedback')).toBeVisible();
  await smart.locator('.stock-entry-cancel').click();
  await expect(smart.locator('.stock-entry-chip')).toHaveCount(0);

  // Cancel: Escape on an empty input drops the addition only.
  await input.fill('add s'); await input.press('Enter');
  await expect(smart.locator('.stock-entry-chip').first()).toContainText('Sale');
  await input.press('Escape');
  await expect(smart.locator('.stock-entry-chip')).toHaveCount(0);
  await page.reload();
  expect(await pending()).toBe(before + 1);

  // Typing filters the table; the filter shows below the input and can be removed.
  await input.fill('Supplier'); await option('Supplier').first().click();
  await input.fill(`Cotton ${suffix}`); await input.press('Enter');
  await idle(page, 'operations-workbench');
  expect(await page.locator('operations-workbench').evaluate(node => new Set(node.model.rows.map(row => row.groupId)).size)).toBe(1);
  await page.locator('.smart-filters .stock-entry-chip-remove').click();
  await idle(page, 'operations-workbench');
  await expect(page.locator('.smart-filters')).toBeHidden();

  // Columns can be hidden; the choice survives a reload.
  await page.locator('.column-picker summary').click();
  await page.locator('.column-picker-list').getByLabel('Article').uncheck();
  await expect(grid.locator('thead td[data-column-key="article"]')).toHaveCount(0);
  await page.reload();
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await expect(grid.locator('thead td[data-column-key="article"]')).toHaveCount(0);
  await expect(grid.locator('thead td[data-column-key="item"]')).toHaveCount(1);
  expect(errors).toEqual([]);
});

test('production: blank rows add inputs and outputs in Preview', async ({ page }) => {
  await signIn(page);
  await page.goto('/operations');
  const grid = page.locator('#operations-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-pending-filter').click();
  await idle(page, 'operations-workbench');
  const made = await addOperation(page, 'production');
  const entry = made.group.slice(0, -1);
  const output = `${entry}|${entry}-1`;
  const input = await editor(grid, output, 'item', '.inventory-cell-editor > input');
  await input.fill(`Pack ${Date.now()}`); await input.press('Enter');
  await idle(page, 'operations-workbench');
  await expect(grid).toContainText('+ Add output');
  await expect(grid).toContainText('Choose an input');
  await shot(page, 'operations-6-production');
});

test('edit, copy and cancel an accepted operation', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Depot ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues', `Mug ${suffix}`);

  // Opening stock: 1 at 100.00, through the stock page.
  await page.goto('/');
  const stock = page.locator('#stock-grid');
  await expect.poll(() => stock.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-search-input').fill(`Mug ${suffix}`);
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await page.locator('.stock-search-input').press('Enter');
  const productId = await page.locator('stock-workbench').evaluate(node => node.scope.productId);
  const warehouse = await stock.evaluate((node, name) => node.model.columns.find(column => column.label === name).key, `Depot ${suffix}`);
  const stockCell = async (rowId, column, value) => {
    const input = await editor(stock, rowId, column, '.inventory-cell-editor > input');
    await input.fill(value); await input.press('Enter');
    await expect.poll(() => page.locator('stock-workbench').evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
  };
  await stockCell(`new-row:${productId}`, 'item', 'M');
  await stockCell(`new-row:${productId}`, warehouse, '1');
  await stockCell(`new:${productId}:M`, 'unit-cost', '100');
  const unit = await editor(stock, `new:${productId}:M`, 'unit', '.inventory-cell-editor > select');
  await unit.selectOption({ label: 'pcs' }); await unit.press('Enter');
  await expect.poll(() => page.locator('stock-workbench').evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
  // The opening takes its own time, before the operations below.
  const opened = new Date(Date.now() - 2 * 86_400_000);
  const pad = value => String(value).padStart(2, '0');
  await stockCell(`new:${productId}:M`, 'occurred-at', `${opened.getFullYear()}-${pad(opened.getMonth() + 1)}-${pad(opened.getDate())}T10:00`);
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');

  await page.goto('/operations');
  const grid = page.locator('#operations-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  const rowsOf = entry => grid.evaluate((node, entry) => node.model.rows.filter(row => row.entry === entry).map(row => row.id), entry);
  const pendingEntries = () => page.locator('operations-workbench').evaluate(node => node.model.pending.map(entry => entry['entry-id']));
  const cell = (row, key) => grid.locator(`tr[data-row-id="${row}"] td[data-column-key="${key}"]`);
  // Leaves what other tests left pending out of the submission.
  const submitOnly = async entries => {
    for (const id of (await pendingEntries()).filter(id => !entries.includes(id))) await grid.locator(`input[data-review-entry="${id}"]`).uncheck();
    await page.locator('.stock-submit').click();
    await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
    await idle(page, 'operations-workbench');
    await expect.poll(async () => (await pendingEntries()).filter(id => entries.includes(id))).toEqual([]);
  };
  const newEntry = async action => {
    const before = await pendingEntries();
    const added = async () => (await pendingEntries()).filter(id => !before.includes(id));
    await action();
    await expect.poll(async () => (await added()).length).toBe(1);
    await idle(page, 'operations-workbench');
    return (await added())[0];
  };
  const press = (row, name) => cell(row, 'actions').getByRole('button', { name, exact: true }).click();
  const acceptedRow = id => grid.evaluate((node, id) => node.model.rows.find(row => row.groupId === id).id, id);

  const quantity = row => cell(row, 'quantity');

  // A purchase of 10 at 60.00.
  const { group: g, line } = await addOperation(page, 'purchase');
  const purchase = g.slice(0, -1);
  const supplier = await editor(grid, g, 'supplier', '.inventory-cell-editor > input');
  await supplier.fill(`Acme ${suffix}`); await supplier.press('Enter');
  await idle(page, 'operations-workbench');
  await choose(page, line, 'item', `Mug ${suffix} / M`, '.inventory-goods-choice');
  await type(page, line, 'quantity', '10');
  await type(page, line, 'price', '60');
  await expect(cell(line, 'warehouse')).toHaveText(`Depot ${suffix}`);
  await submitOnly([purchase]);
  const row = `${purchase}|${purchase}#1-0`;

  // 26: Edit opens the accepted operation in Preview; the first changed cell makes a pending revision, a second edit lands in it.
  const edit = async () => { await press(await acceptedRow(purchase), 'Edit'); await idle(page, 'operations-workbench'); };
  await edit();
  expect(await grid.evaluate((node, id) => node.model.rows.filter(row => row.groupId === id).length, purchase)).toBe(2);
  expect(await page.locator('operations-workbench').evaluate(node => node.model.pending.filter(entry => entry['entry-id'] === node.scope.editing[0]).length)).toBe(0);
  const revision = await newEntry(() => type(page, row, 'quantity', '8'));
  await expect(quantity(row)).toHaveText('10 → 8');
  await type(page, row, 'price', '55');
  await expect(cell(row, 'price')).toContainText('→ 55');
  expect((await rowsOf(revision))[0]).toBe(row);
  await shot(page, 'operations-6-amended');

  // Discarding it restores the accepted values; editing again makes a new one.
  await press(row, 'Discard pending changes');
  await expect(quantity(row)).toHaveText('10');
  await idle(page, 'operations-workbench');
  const again = await newEntry(() => type(page, row, 'quantity', '8'));
  // Outside Preview the change shows in the operation's place, read-only.
  await page.locator('.stock-pending-filter').click();
  await idle(page, 'operations-workbench');
  await expect(quantity(row)).toHaveText('10 → 8');
  expect(await grid.evaluate(node => node.model.rows.every(row => row.cells.every(cell => !cell.editor)))).toBe(true);
  await edit();
  await submitOnly([again]);
  const amended = await acceptedRow(purchase);
  await expect(quantity(amended)).toHaveText('8');
  await press(amended, 'Revisions');
  await expect(page.locator('#history-panel tbody tr')).toHaveCount(2);
  await expect(page.locator('#history-panel')).toContainText('+10 × 60.00');
  await shot(page, 'operations-7-revisions');
  await press(amended, 'Revisions');
  await expect(grid.locator('.jss_worksheet #history-panel')).toHaveCount(0);

  // 28: a copy opens in Preview as a new pending operation; outside it, it is right below its source.
  const copy = await newEntry(() => press(amended, 'Copy'));
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'true');
  const copied = (await rowsOf(copy))[0];
  await expect(quantity(copied)).toHaveText('8');
  await type(page, copied, 'quantity', '3');
  await page.locator('.stock-pending-filter').click();
  await idle(page, 'operations-workbench');
  const order = await grid.evaluate(node => [...new Set(node.model.rows.map(row => row.groupId))]);
  expect(order.indexOf(copy)).toBe(order.indexOf(purchase) + 1);
  await expect(quantity(amended)).toHaveText('8');
  await page.locator('.stock-pending-filter').click();
  await idle(page, 'operations-workbench');
  await submitOnly([copy]);

  // 27: Cancel opens the operation struck through in Preview; after Submit only Revisions and Copy remain.
  const cancellation = await newEntry(() => press(amended, 'Cancel operation'));
  const struck = (await rowsOf(cancellation))[0];
  await expect(cell(struck, 'kind')).toContainText('(will be cancelled)');
  await type(page, `${purchase}|`, 'reason', 'Entered twice');
  await shot(page, 'operations-8-cancelling');
  await submitOnly([cancellation]);
  expect(await grid.evaluate((node, id) => node.model.rows.find(row => row.groupId === id).cells.at(-1).actions.map(action => action.label), purchase))
    .toEqual(['Revisions', 'Copy']);
  await shot(page, 'operations-9-cancelled');

  // The stock counts the opening and the copy.
  await page.goto('/');
  await expect.poll(() => stock.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-search-input').fill(`Mug ${suffix}`);
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await page.locator('.stock-search-input').press('Enter');
  await expect.poll(() => stock.evaluate((node, key) => node.model.rows.map(row => String(row.cells[node.model.columns.findIndex(column => column.key === key)]?.value)), warehouse)).toContain('4');
  expect(errors).toEqual([]);
});

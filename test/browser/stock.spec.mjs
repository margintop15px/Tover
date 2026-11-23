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
  return id;
}

// A response that arrives while an editor is open rebuilds the grid, so wait until the workbench is idle.
async function catalogueSaved(page) {
  await expect(page.locator('.catalogue-save-status')).toHaveText('');
  await expect.poll(() => page.locator('catalogue-workbench').evaluate(node => !node.pending && node.grid._attempts.size === 0
    && (!node.requestId || node.requestId === node.lastResponseId))).toBe(true);
}

async function chooseInCatalogue(page, id, path, label) {
  const grid = page.locator('.catalogue-grid');
  const select = grid.locator('.inventory-goods-choice');
  await catalogueSaved(page);
  await expect(async () => {
    if (!(await select.isVisible())) await grid.evaluate((node, { id, path }) => node.focusField(id, path), { id, path });
    await expect(select).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  await expect(select).toHaveAttribute('aria-busy', 'false');
  await expect(select.locator('option').filter({ hasText: label })).toHaveCount(1);
  await select.selectOption({ label });
  await catalogueSaved(page);
  await expect(grid.locator(`td[data-entry-id="${id}"][data-path="${path}"]`)).toContainText(label);
}

async function chooseCell(page, rowId, column, label) {
  const grid = page.locator('#stock-grid');
  const select = grid.locator(`td[data-entry-id="${rowId}"][data-path="${column}"] .inventory-cell-editor > select`);
  await expect(async () => {
    if (!(await select.isVisible())) await grid.evaluate((node, { rowId, column }) => node.focusField(rowId, column), { rowId, column });
    await expect(select).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  await select.selectOption({ label });
  await select.press('Enter');
  await expect(page.locator('.stock-save-status')).toHaveText('');
  await expect.poll(() => page.locator('stock-workbench').evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
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
  await chooseCell(page, `new:${productId}:M`, 'unit', 'pcs');
  await cell(page, `new-row:${productId}`, 'item', 'S');
  await cell(page, `new-row:${productId}`, warehouse, '2');
  await chooseCell(page, `new:${productId}:S`, 'unit', 'pcs');
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
  const findM = () => grid.evaluate(node => node.model.rows.find(row => row.cells.some(c => c.target?.field === 'variant' && c.value === 'M'))?.id);
  await expect.poll(findM).toBeTruthy();
  const itemM = await findM();
  const row = grid.locator(`tr[data-row-id="${itemM}"]`);
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
  await chooseCell(page, key, 'unit', 'pcs');
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

test('count a stock item and change its article from its row, then submit both', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Main ${suffix}`);
  const cup = await registerCatalogueRecord(page, '/catalogues', `Cup ${suffix}`);
  await chooseInCatalogue(page, cup, 'default-warehouse-id', `Main ${suffix}`);
  await page.goto('/');
  const workbench = page.locator('stock-workbench');
  const grid = page.locator('#stock-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-search-input').fill(`Cup ${suffix}`);
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await page.locator('.stock-search-input').press('Enter');
  const productId = await workbench.evaluate(node => node.scope.productId);
  const warehouse = await grid.evaluate((node, name) => node.model.columns.find(column => column.label === name).key, `Main ${suffix}`);
  // The product's default warehouse is marked on the new row and takes focus once the variant is typed.
  expect(await workbench.evaluate(node => node.scope.warehouseId)).toBe(warehouse);
  await expect(grid.locator(`td[data-entry-id="new-row:${productId}"][data-path="${warehouse}"]`)).toHaveClass(/stock-default-warehouse/);
  await cell(page, `new-row:${productId}`, 'item', 'blue');
  await expect.poll(() => page.evaluate(() => document.activeElement?.closest('td')?.dataset.path ?? document.querySelector('#stock-grid td.highlight, #stock-grid td.jss_cursor')?.dataset.path)).toBe(warehouse);
  await cell(page, `new-row:${productId}`, warehouse, '5');
  await chooseCell(page, `new:${productId}:blue`, 'unit', 'pcs');
  await page.locator('.stock-pending-filter').click();
  await page.locator('.stock-submit-time').fill(yesterday());
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');

  // The accepted item's cell now takes a count, and its article a change: one Correction entry.
  const findItem = () => grid.evaluate(node => node.model.rows.find(row => row.cells.some(c => c.target?.values?.kind === 'correction'))?.id);
  await expect.poll(findItem).toBeTruthy();
  const itemId = await findItem();
  await cell(page, itemId, warehouse, '7');
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.find(row => row.id === id).cells.some(c => c.prefix === '5 → ' && c.value === '7'), itemId)).toBe(true);
  await cell(page, itemId, 'article', 'C-1');
  expect(await grid.evaluate(node => new Set(node.model.pending.map(entry => entry['entry-id'])).size)).toBe(1);
  // Archiving an item that keeps stock shows the blocker on its cell before Submit; Keep withdraws it.
  const itemCell = id => grid.evaluate((node, id) => node.model.rows.find(row => row.id === id).cells.find(c => c.target?.field === 'variant'), id);
  await grid.locator(`tr[data-row-id="${itemId}"]`).getByRole('button', { name: 'Archive', exact: true }).click();
  await expect.poll(async () => (await itemCell(itemId))?.className ?? '').toContain('stock-finding-error');
  expect((await itemCell(itemId)).title).toContain(`Cannot archive: stock in Main ${suffix}.`);
  await shot(page, '8-correction');
  await grid.locator(`tr[data-row-id="${itemId}"]`).getByRole('button', { name: 'Keep', exact: true }).click();
  await expect.poll(async () => (await itemCell(itemId))?.className ?? '').not.toContain('stock-finding');

  await page.locator('.stock-pending-filter').click();
  await page.locator('.stock-submit-reason').fill('Recount');
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
  const row = grid.locator(`tr[data-row-id="${itemId}"]`);
  await expect(row.locator(`td[data-column-key="${warehouse}"]`)).toHaveText('7');
  await expect(row.locator('td[data-column-key="article"]')).toHaveText('C-1');
  await row.getByRole('button', { name: 'History' }).click();
  await expect(page.locator('.stock-history-rows')).toContainText('Correction');
  await expect(page.locator('.stock-history-rows')).toContainText('Recount');
  expect(errors).toEqual([]);
});

test('a failed suggestion lookup offers Retry by mouse and keyboard, never "no match"', async ({ page }) => {
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues', `Lamp ${suffix}`);
  await page.goto('/');
  const grid = page.locator('#stock-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  let failing = true; let aborted = 0;
  await page.route(url => /suggest/.test(url.href), route => {
    if (!failing) return route.continue();
    aborted += 1; return route.abort();
  });
  const search = page.locator('.stock-search-input');
  const retry = page.locator('.stock-option-failed button');
  await search.fill(`Lamp ${suffix}`);
  await expect(retry).toBeVisible();
  await expect(page.locator('.stock-option-create')).toHaveCount(0);
  // A slow press outlasts the blur timer and must still reach the button.
  const before = aborted;
  await retry.click({ delay: 400 });
  await expect.poll(() => aborted).toBeGreaterThan(before);
  await expect(retry).toBeVisible();
  // Keyboard: the button stays while it has focus.
  failing = false;
  await search.press('Tab');
  await expect.poll(() => page.evaluate(() => Boolean(document.activeElement.closest('.stock-option-failed')))).toBe(true);
  await page.waitForTimeout(300);
  await expect(retry).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await expect(search).toBeFocused();
  // Focus that leaves the search and its list closes the list.
  await page.locator('.stock-pending-filter').focus();
  await expect(page.locator('#stock-suggestions')).toBeHidden();
});

test('correct an item: bad counts refused, findings read on focus, move dialog, discard text, tie order, restore and retry', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const dialogs = []; page.on('dialog', dialog => { dialogs.push(dialog.message()); dialog.accept(); });
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Main ${suffix}`);
  const pen = await registerCatalogueRecord(page, '/catalogues', `Pen ${suffix}`);
  await chooseInCatalogue(page, pen, 'default-warehouse-id', `Main ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues', `Box ${suffix}`);
  await page.goto('/');
  const workbench = page.locator('stock-workbench');
  const grid = page.locator('#stock-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.locator('.stock-search-input').fill(`Pen ${suffix}`);
  await expect(page.locator('.stock-option-product')).toHaveCount(1);
  await page.locator('.stock-search-input').press('Enter');
  const productId = await workbench.evaluate(node => node.scope.productId);
  const warehouse = await grid.evaluate((node, name) => node.model.columns.find(column => column.label === name).key, `Main ${suffix}`);
  await cell(page, `new-row:${productId}`, 'item', 'red');
  await cell(page, `new-row:${productId}`, warehouse, '5');
  await chooseCell(page, `new:${productId}:red`, 'unit', 'pcs');
  await page.locator('.stock-pending-filter').click();
  await page.locator('.stock-submit-time').fill(yesterday());
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
  const findItem = () => grid.evaluate(node => node.model.rows.find(row => row.cells.some(c => c.target?.values?.kind === 'correction'))?.id);
  await expect.poll(findItem).toBeTruthy();
  const itemId = await findItem();
  const row = grid.locator(`tr[data-row-id="${itemId}"]`);
  const count = row.locator(`td[data-column-key="${warehouse}"]`);

  // A count the rules refuse stays in its cell with the reason; the accepted balance is not shown instead.
  // Wait for the table reads the acceptance set off; a read rebuilds the grid under an open editor.
  await expect.poll(async () => {
    const before = await workbench.evaluate(node => node.tableRequest);
    await page.waitForTimeout(400);
    return workbench.evaluate((node, before) => !node.pending && node.tableRequest === before && node.handled.has(before), before);
  }).toBe(true);
  // A count the rules refuse is refused while typing, with the reason; nothing is saved.
  await workbench.evaluate(node => { node.saveCount = 0; node.addEventListener('stock-save', () => { node.saveCount += 1; }); });
  for (const bad of ['-1', 'abc']) {
    await editCell(page, grid, itemId, warehouse, bad);
    await expect(grid.locator('.inventory-grid-notice, [role=status], [role=alert]').filter({ hasText: 'Enter a whole-unit quantity' }).first()).toBeVisible();
  }
  // The next edit starts without the old refusal.
  await grid.evaluate((node, { itemId, warehouse }) => node.focusField(itemId, warehouse), { itemId, warehouse });
  await expect(grid.locator('.inventory-grid-notice').first()).toHaveText('');
  await count.locator('.inventory-cell-editor > input').press('Escape');
  expect(await workbench.evaluate(node => node.saveCount)).toBe(0);
  await expect(count).toHaveText('5');

  // Archive is blocked by stock: selecting the item cell reads the finding out, no mouse needed.
  await row.getByRole('button', { name: 'Archive', exact: true }).click();
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.find(row => row.id === id).cells.find(c => c.target?.field === 'variant')?.className ?? '', itemId)).toContain('stock-finding-error');
  // Select the cell as arrow keys do; a click would open its editor.
  await row.locator('td[data-column-key="item"]').evaluate(td => document.querySelector('#stock-grid').worksheet
    .updateSelectionFromCoords(Number(td.dataset.x), Number(td.dataset.y), Number(td.dataset.x), Number(td.dataset.y)));
  await expect(page.locator('.stock-finding-note')).toContainText(`Cannot archive: stock in Main ${suffix}.`);
  await row.getByRole('button', { name: 'Keep', exact: true }).click();
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.find(row => row.id === id).cells.find(c => c.target?.field === 'variant')?.className ?? '', itemId)).not.toContain('stock-finding');

  // Move: a dialog lists the other active products and takes an optional new label.
  await row.getByRole('button', { name: 'Move to another product', exact: true }).click();
  const dialog = page.locator('.stock-move-dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('.stock-move-product option', { hasText: `Pen ${suffix}` })).toHaveCount(0);
  await dialog.locator('.stock-move-product').selectOption({ label: `Box ${suffix}` });
  await dialog.locator('.stock-move-variant').fill('red-2');
  await shot(page, '9-move');
  await dialog.locator('.stock-move-confirm').click();
  await expect(dialog).toHaveCount(0);
  // The row stays under Pen, marked.
  await expect(row.locator('td[data-column-key="item"]')).toContainText('move pending');
  // Discarding names the item and what is lost.
  await page.locator('.stock-pending-filter').click();
  await row.getByRole('button', { name: 'Discard pending changes' }).click();
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(0);
  expect(dialogs.at(-1)).toContain(`«Pen ${suffix} / red`);
  await page.locator('.stock-pending-filter').click();

  // Two counts of one item at one second: the member puts them in order, then both are accepted.
  await cell(page, itemId, warehouse, '4');
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(1);
  await workbench.evaluate((node, { itemId, warehouse }) => node.emit('stock-save', {
    workspaceId: node.workspaceId, entryId: crypto.randomUUID(), revision: 0, commandId: crypto.randomUUID(),
    values: { kind: 'correction', 'stock-item-id': itemId, 'warehouse-id': warehouse, quantity: '3', 'occurred-at': new Date().toISOString() },
  }), { itemId, warehouse });
  await expect.poll(async () => { await workbench.evaluate(node => node.refreshTable()); return grid.evaluate(node => new Set(node.model.pending.map(entry => entry['entry-id'])).size); }).toBe(2);
  await page.locator('.stock-pending-filter').click();
  await page.locator('.stock-submit-time').fill(yesterday());
  await page.locator('.stock-submit').click();
  const order = page.locator('.stock-order li');
  await expect(order).toHaveCount(2);
  const last = (await order.nth(1).textContent()).includes('· 4') ? '4' : '3';
  await shot(page, '10-order');
  await order.nth(1).getByRole('button', { name: /Move up/ }).click();
  await page.locator('.stock-order-accept').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
  // The entry moved up is accepted first, so the other one sets the balance.
  await expect(count).toHaveText(last === '4' ? '3' : '4');

  // Count to zero and archive in one correction; then a pending Restore stays visible without "Show archived items".
  await cell(page, itemId, warehouse, '0');
  await row.getByRole('button', { name: 'Archive', exact: true }).click();
  await expect(row.locator('td[data-column-key="item"]')).toContainText('archive pending');
  await page.locator('.stock-pending-filter').click();
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');
  await expect.poll(() => grid.evaluate((node, id) => node.model.rows.some(row => row.id === id), itemId)).toBe(false);
  await page.locator('.stock-show-archived').click();
  await row.getByRole('button', { name: 'Restore', exact: true }).click();
  await page.locator('.stock-show-archived').click();
  await expect(row.locator('td[data-column-key="item"]')).toContainText('restore pending');

  // A failed table read after a discard offers Retry.
  await page.locator('.stock-pending-filter').click();
  await expect(row.getByRole('button', { name: 'Discard pending changes' })).toBeVisible();
  const table = url => /table/.test(url.pathname);
  await page.route(table, route => route.abort());
  await row.getByRole('button', { name: 'Discard pending changes' }).click();
  const retry = page.getByRole('button', { name: 'Retry', exact: true });
  await expect(retry).toBeVisible();
  await page.unroute(table);
  await retry.click();
  await expect(retry).toBeHidden();
  await expect.poll(() => grid.evaluate(node => node.model.pending.length)).toBe(0);
  expect(errors).toEqual([]);
});

import { expect, test } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';
import {
  acceptDrafts, addStockRow, draftCell, draftGrid, draftIds, editDraftCell,
  editStockCell, gridHeader, openReview, operationGrid, revertPending, signals,
  stockGrid, stockRow,
} from './grid.fixture.mjs';

const errors = new WeakMap();
const restores = new WeakMap();
const stockKeys = ['name', 'quantity', 'unit', 'location', 'article', 'barcode'];
const headerKeys = grid => grid.evaluate(element => element._owned('thead td[data-column-key]').map(header => header.dataset.columnKey));
const columnWidth = (grid, key) => gridHeader(grid, key).evaluate(header => header.getBoundingClientRect().width);
const savedLayout = async (page, id) => JSON.parse(await signals(page, '_columnLayoutsJson') || '{}')[id];

test.beforeEach(async ({ page }) => {
  const messages = [];
  errors.set(page, messages);
  restores.set(page, new Map());
  page.on('pageerror', error => messages.push(error.message));
  await signIn(page);
  await revertPending(page);
});
test.afterEach(async ({ page }) => {
  for (const baseline of restores.get(page).values()) expect((await action(page, 'save-layout', baseline))._layoutStatus).toBe('Saved');
  await revertPending(page);
  expect(errors.get(page)).toEqual([]);
});

function actionSignals(body) {
  return Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals '))
    .map(line => JSON.parse(line.slice('data: signals '.length))));
}

async function action(page, name, payload) {
  const { csrf, version } = await pageContext(page);
  const response = await page.context().request.post(`/action/tover.stock.web/${name}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
    data: { view: 'stock', ...payload },
  });
  expect(response.status()).toBe(200);
  return actionSignals(await response.text());
}

async function expectLayoutSaved(grid) {
  await expect(grid).toHaveAttribute('data-layout-status', 'Saved');
  await expect.poll(() => grid.evaluate(element => Boolean(element._layoutPending))).toBe(false);
}

async function rememberLayout(page, grid) {
  const baseline = await grid.evaluate(element => ({ gridId: element.model.id,
    widths: Object.fromEntries(element.model.columns.filter(column => !['select', 'actions'].includes(column.key)).map(column => [column.key, element.model.savedWidths?.[column.key] || column.width])),
    order: element.model.columns.map(column => column.key).filter(key => !['select', 'actions'].includes(key)),
  }));
  if (!restores.get(page).has(baseline.gridId)) restores.get(page).set(baseline.gridId, baseline);
}

async function resize(page, grid, key, delta) {
  await rememberLayout(page, grid);
  const header = gridHeader(grid, key);
  await header.scrollIntoViewIfNeeded();
  const before = await columnWidth(grid, key);
  const box = await header.boundingBox();
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/save-layout');
  await page.mouse.move(box.x + box.width - 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 2 + delta, box.y + box.height / 2, { steps: 8 });
  await page.mouse.up();
  expect((await saved).status()).toBe(200);
  await expectLayoutSaved(grid);
  await expect.poll(() => columnWidth(grid, key)).toBeCloseTo(before + delta, 0);
  return Math.round(before + delta);
}

async function dragColumn(page, grid, sourceKey, targetKey, side = 'before') {
  const source = gridHeader(grid, sourceKey).locator('.inventory-column-label');
  const target = gridHeader(grid, targetKey);
  await expect.poll(() => signals(page, '_reviewPending')).toBe(false);
  await expect.poll(() => grid.evaluate(element => element.busy)).toBe(false);
  await expect(source).toHaveAttribute('draggable', 'true');
  await rememberLayout(page, grid);
  const before = await headerKeys(grid);
  const expected = before.filter(key => key !== sourceKey);
  expected.splice(expected.indexOf(targetKey) + (side === 'after' ? 1 : 0), 0, sourceKey);
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/save-layout');
  await source.dragTo(target, { targetPosition: { x: side === 'before' ? 8 : (await target.boundingBox()).width - 8, y: 17 } });
  expect((await saved).status()).toBe(200);
  await expectLayoutSaved(grid);
  await expect.poll(() => headerKeys(grid)).toEqual(expected);
  return expected;
}

test('real column resizing and header dragging follow the account across refresh and browsers', async ({ page, browser }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const grid = stockGrid(page);
  const width = await resize(page, grid, 'name', 57);
  const order = await dragColumn(page, grid, 'quantity', 'name');
  expect((await savedLayout(page, 'stock')).widths.name).toBe(width);
  await page.reload();
  await expect.poll(() => headerKeys(grid)).toEqual(order);
  await expect.poll(() => columnWidth(grid, 'name')).toBeCloseTo(width, 0);

  const otherContext = await browser.newContext({ baseURL: new URL(page.url()).origin, viewport: { width: 1440, height: 900 } });
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin, viewport: { width: 1440, height: 900 } });
  try {
    const other = await otherContext.newPage();
    other.on('pageerror', error => errors.get(page).push(error.message));
    await signIn(other);
    await expect.poll(() => headerKeys(stockGrid(other))).toEqual(order);
    await expect.poll(() => columnWidth(stockGrid(other), 'name')).toBeCloseTo(width, 0);

    const member = await memberContext.newPage();
    member.on('pageerror', error => errors.get(page).push(error.message));
    await signIn(member, 'member@example.test');
    await expect.poll(() => headerKeys(stockGrid(member))).toEqual(stockKeys);
    expect((await savedLayout(member, 'stock'))?.widths?.name).toBeUndefined();
    const ownerId = await grid.evaluate(element => element.model.layoutScope);
    const forged = await action(member, 'save-layout', { gridId: 'stock', widths: { name: 417 }, userId: ownerId, 'user-id': ownerId });
    expect(forged._layoutStatus).toBe('Saved');
    await member.reload();
    await expect.poll(() => columnWidth(stockGrid(member), 'name')).toBeCloseTo(417, 0);
    await page.reload();
    await expect.poll(() => columnWidth(grid, 'name')).toBeCloseTo(width, 0);
    await expect.poll(() => headerKeys(grid)).toEqual(order);
    expect((await action(member, 'save-layout', { gridId: 'stock', widths: { name: 310 }, order: stockKeys }))._layoutStatus).toBe('Saved');
  } finally { await otherContext.close(); await memberContext.close(); }
});

test('reordered columns retain server filters, sort, edits, history and independent merged draft layouts', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const name = `Layout workflow ${Date.now()}`;
  const opening = await addStockRow(page, name, 'Layout shelf', '6');
  await acceptDrafts(page, [opening]);
  const grid = stockGrid(page);
  const initial = await headerKeys(grid);
  const moved = initial.indexOf('quantity') < initial.indexOf('name') ? await dragColumn(page, grid, 'name', 'quantity') : await dragColumn(page, grid, 'quantity', 'name');
  await gridHeader(grid, 'name').getByRole('button', { name: 'Filter Goods', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Filter Goods', exact: true });
  await dialog.getByLabel('Add condition', { exact: true }).selectOption('begins');
  await dialog.getByLabel('Value 1', { exact: true }).fill(name);
  await dialog.getByLabel('Value 1', { exact: true }).press('Enter');
  await expect(stockRow(page, name)).toBeVisible();
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await gridHeader(grid, 'quantity').getByRole('button', { name: 'Sort by Quantity', exact: true }).click();
  await expect(gridHeader(grid, 'quantity')).toHaveAttribute('aria-sort', 'ascending');
  await editStockCell(page, stockRow(page, name), 'article', 'Layout article');
  await stockRow(page, name).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  const history = page.locator('#history-panel');
  await expect(history).toBeVisible();
  expect(await history.evaluate(panel => panel.closest('tr').previousElementSibling.dataset.rowId)).toBe(await stockRow(page, name).getAttribute('data-row-id'));
  await expect.poll(() => headerKeys(grid)).toEqual(moved);
  await history.getByRole('button', { name: 'Close history', exact: true }).click();
  await revertPending(page);

  const added = await action(page, 'add-entry', { type: 'purchase' });
  let draft = added._inventorySnapshot.draftSelection.at(-1);
  const withLine = await action(page, 'draft-command', { ...draft, command: 'add-line', path: 'lines' });
  draft = withLine._inventorySnapshot.draftSelection.find(entry => entry.id === draft.id);
  for (const [path, value] of [['party', 'Layout supplier'], ['lines/0/name', 'First layout line'], ['lines/0/quantity', '2'], ['lines/0/location', 'Layout shelf'], ['lines/1/name', 'Second layout line'], ['lines/1/quantity', '3'], ['lines/1/location', 'Layout shelf']]) {
    const saved = await action(page, 'save-field', { ...draft, path, value });
    draft = saved._inventorySnapshot.draftSelection.find(entry => entry.id === draft.id);
  }
  await page.reload(); await openReview(page);
  const draftOrder = await dragColumn(page, draftGrid(page), 'type', 'occurred-at', 'after');
  await expect(draftCell(page, draft.id, 'party')).toHaveAttribute('rowspan', '2');
  await editDraftCell(page, draft.id, 'lines/1/quantity', '4');
  await expect(draftCell(page, draft.id, 'lines/0/quantity')).toHaveText('2');
  await page.reload(); await openReview(page);
  await expect.poll(() => headerKeys(draftGrid(page))).toEqual(draftOrder);
  await expect(draftCell(page, draft.id, 'lines/1/quantity')).toHaveText('4');
  await expect.poll(() => headerKeys(stockGrid(page))).toEqual(moved);
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect.poll(() => headerKeys(operationGrid(page))).toEqual(expect.arrayContaining(['type', 'occurred-at']));
  const operationsOrder = await dragColumn(page, operationGrid(page), 'type', 'occurred-at', 'after');
  await page.reload();
  await expect.poll(() => headerKeys(operationGrid(page))).toEqual(operationsOrder);
  await openReview(page);
  await expect.poll(() => headerKeys(draftGrid(page))).toEqual(draftOrder);
});

test('invalid layouts cannot change inventory, and a lost save response retains a retryable layout', async ({ page }) => {
  const baseline = await page.evaluate(async () => {
    const { getPath } = await import('/datastar.js');
    return { ledger: getPath('_ledgerRevision'), drafts: JSON.stringify(getPath('_draftIds')) };
  });
  const before = await savedLayout(page, 'stock');
  for (const payload of [{ gridId: 'unknown', widths: { name: 200 } }, { gridId: 'stock', widths: { unknown: 200 } }, { gridId: 'stock', order: ['name', 'name'] }, { gridId: 'stock', widths: { name: 20 } }]) {
    expect((await action(page, 'save-layout', payload))._layoutStatus).toBe('Failed');
  }
  await page.reload();
  expect(await savedLayout(page, 'stock')).toEqual(before);
  expect(await signals(page, '_ledgerRevision')).toBe(baseline.ledger);
  expect(JSON.stringify(await draftIds(page))).toBe(baseline.drafts);

  const grid = stockGrid(page);
  const original = await columnWidth(grid, 'name');
  await rememberLayout(page, grid);
  let committed = false;
  await page.route('**/action/tover.stock.web/save-layout', async route => {
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    committed = true;
    await route.abort('failed');
  }, { times: 1 });
  const header = gridHeader(grid, 'name');
  const box = await header.boundingBox();
  await page.mouse.move(box.x + box.width - 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width + 39, box.y + box.height / 2, { steps: 8 });
  await page.mouse.up();
  await expect.poll(() => committed).toBe(true);
  await expect(grid).toHaveAttribute('data-layout-status', 'Failed');
  await expect.poll(() => columnWidth(grid, 'name')).toBeCloseTo(original + 41, 0);
  await grid.getByRole('button', { name: 'Retry saving table layout', exact: true }).click();
  await expectLayoutSaved(grid);
  await page.reload();
  await expect.poll(() => columnWidth(grid, 'name')).toBeCloseTo(original + 41, 0);
  expect(await signals(page, '_ledgerRevision')).toBe(baseline.ledger);
  expect(JSON.stringify(await draftIds(page))).toBe(baseline.drafts);
});

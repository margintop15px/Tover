import { expect, test } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';
import { chooseSmart, draftCell, draftGrid, draftIds, editDraftCell, editStockCell, openReview, revertPending, smartField, stockRow } from './grid.fixture.mjs';

const smartInput = page => page.locator('stock-entry input');
const smartOptions = page => page.locator('stock-entry [role="option"]');
const cellValue = (page, id, path) => draftCell(page, id, path).locator('.inventory-cell-value');

const responseSignals = body => Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals '))
  .map(line => JSON.parse(line.slice('data: signals '.length))));

async function action(page, name, payload = {}) {
  const { csrf, version } = await pageContext(page);
  const response = await page.context().request.post(`/action/tover.stock.web/${name}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
    data: { view: 'stock', ...payload },
  });
  expect(response.status()).toBe(200);
  const body = await response.text(), result = responseSignals(body);
  expect(result.saveStatus, body).not.toBe('Failed');
  return result;
}

async function seed(page, kind, type, fields) {
  let snapshot = await action(page, 'add-entry', { kind, type });
  const id = snapshot._inventorySnapshot.draftSelection.at(-1).id;
  for (const [path, value] of fields) {
    const current = snapshot._inventorySnapshot.draftSelection.find(entry => entry.id === id);
    snapshot = await action(page, 'save-field', { ...current, path, value });
  }
  return id;
}

async function accept(page, id) {
  const preview = await action(page, 'preview', { selected: [id] });
  expect(preview._review).toBeTruthy();
  return action(page, 'submit', { review: preview._review });
}

async function seedItem(page, name, { unit = 'pcs', article = '', barcode = '', location = 'Main' } = {}) {
  const id = await seed(page, 'opening', undefined, [
    ['lines/0/name', name], ['lines/0/quantity', '10'], ['lines/0/location', location],
    ['lines/0/unit', unit], ['lines/0/article', article], ['lines/0/barcode', barcode], ['reason', 'Browser opening count'],
  ]);
  return accept(page, id);
}

async function chooseGridItem(page, id, label) {
  await expect.poll(() => draftGrid(page).evaluate(grid => !grid.busy)).toBe(true);
  await draftCell(page, id, 'lines/0/name').click();
  const dropdown = draftGrid(page).getByRole('combobox', { name: 'Choose existing item', exact: true });
  await expect(dropdown).toBeVisible();
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/save-field'
    && response.request().postDataJSON().path === 'lines/0/item-id');
  await dropdown.selectOption({ label });
  const response = await saved;
  expect(response.status()).toBe(200);
  await expect.poll(() => draftGrid(page).evaluate(grid => !grid.busy && grid._attempts.size === 0)).toBe(true);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
}

const errors = new WeakMap();
test.beforeEach(async ({ page }) => {
  errors.set(page, []);
  page.on('pageerror', error => errors.get(page).push(error.message));
  await signIn(page); await revertPending(page);
});
test.afterEach(async ({ page }) => expect(errors.get(page)).toEqual([]));

test('empty smart suggestions show only Add operation while typed column search remains available', async ({ page }) => {
  await smartInput(page).click();
  await expect(smartOptions(page)).toHaveCount(1);
  await expect(smartOptions(page)).toContainText('Add operation');
  await smartInput(page).fill('Quantity');
  await chooseSmart(page, 'Quantity');
  await expect(smartInput(page)).toHaveAttribute('aria-label', 'Quantity filter');
  await page.getByRole('button', { name: 'Clear unfinished field', exact: true }).click();
  await expect(smartOptions(page)).toHaveCount(1);
  await chooseSmart(page, 'Add operation');
  await expect(smartOptions(page)).toHaveCount(8);
  await expect(smartOptions(page).filter({ hasText: /^Goods/ })).toHaveCount(0);
  await chooseSmart(page, 'Production');
  await expect(page.getByRole('button', { name: 'Edit Add: Production', exact: true })).toBeVisible();
  await expect(smartOptions(page).filter({ has: page.locator('.stock-entry-option-field', { hasText: /^Input Goods$/ }) })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel addition', exact: true }).click();
});

test('actual smart item choice prefills known chips and one location while preserving quantity across refresh', async ({ page }) => {
  const name = `Smart prefill ${Date.now()}`;
  await seedItem(page, name, { article: 'SMART-1', barcode: '123456789', unit: 'box', location: 'Smart shelf' });
  await page.reload();
  await smartInput(page).fill('ass'); await chooseSmart(page, 'Sale');
  await smartField(page, 'Party', 'Customer'); await smartField(page, 'Quantity', '2');
  await smartInput(page).fill('Goods'); await chooseSmart(page, 'Goods');
  await smartInput(page).fill(name);
  const existing = smartOptions(page).filter({ has: page.locator('.stock-entry-option-field', { hasText: name }) })
    .filter({ has: page.locator('.stock-entry-option-description', { hasText: /^SMART-1$/ }) });
  await expect(existing).toBeVisible();
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.inventory.smart-web/complete'
    && response.request().postDataJSON().option?.itemId);
  await existing.click(); await saved;
  for (const [field, value] of [['Goods', name], ['Quantity', '2'], ['Unit', 'box'], ['Location', 'Smart shelf'], ['Article', 'SMART-1'], ['Barcode', '123456789']]) {
    await expect(page.getByRole('button', { name: `Edit ${field}: ${value}`, exact: true })).toBeVisible();
  }
  await expect(smartOptions(page).filter({ has: page.locator('.stock-entry-option-field', { hasText: /^Article$/ }) })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole('button', { name: `Edit Goods: ${name}`, exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit Quantity: 2', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit Location: Smart shelf', exact: true })).toBeVisible();
  await smartInput(page).press('Meta+Enter');
  await expect.poll(() => draftIds(page)).toHaveLength(1);
  const [id] = await draftIds(page); await openReview(page);
  await expect(cellValue(page, id, 'lines/0/article')).toHaveText('SMART-1');
  await expect(cellValue(page, id, 'lines/0/location')).toHaveText('Smart shelf');
});

test('actual grid item choice fills all columns, then clears absent metadata without replacing entered values', async ({ page }) => {
  const prefix = `Grid prefill ${Date.now()}`;
  await seedItem(page, `${prefix} A`, { article: 'GRID-1', barcode: '987654321', unit: 'box', location: 'Known shelf' });
  await seedItem(page, `${prefix} B`, { unit: 'pcs', location: 'Other shelf' });
  const id = await seed(page, undefined, 'sale', [['party', 'Customer'], ['lines/0/quantity', '2']]);
  await page.reload(); await openReview(page);
  await chooseGridItem(page, id, `${prefix} A · GRID-1`);
  for (const [path, value] of [['name', `${prefix} A`], ['quantity', '2'], ['unit', 'box'], ['location', 'Known shelf'], ['article', 'GRID-1'], ['barcode', '987654321']]) {
    await expect(cellValue(page, id, `lines/0/${path}`)).toHaveText(value);
  }
  await editDraftCell(page, id, 'lines/0/location', 'Entered shelf');
  await chooseGridItem(page, id, `${prefix} B`);
  await expect(cellValue(page, id, 'lines/0/name')).toHaveText(`${prefix} B`);
  await expect(cellValue(page, id, 'lines/0/unit')).toHaveText('pcs');
  await expect(cellValue(page, id, 'lines/0/quantity')).toHaveText('2');
  await expect(cellValue(page, id, 'lines/0/location')).toHaveText('Entered shelf');
  await expect(cellValue(page, id, 'lines/0/article')).toHaveText('');
  await expect(cellValue(page, id, 'lines/0/barcode')).toHaveText('');
  await page.reload(); await openReview(page);
  await expect(cellValue(page, id, 'lines/0/location')).toHaveText('Entered shelf');
  await expect(cellValue(page, id, 'lines/0/quantity')).toHaveText('2');
  const actions = draftCell(page, id, 'lines/0/name').locator('xpath=..').locator('td[data-column-key="actions"]');
  await expect(actions.getByRole('button', { name: 'Add item', exact: true })).toBeVisible();
  await expect(actions.getByRole('button', { name: 'Remove item', exact: true })).toBeVisible();
  await expect(actions.getByRole('button', { name: 'Discard operation', exact: true })).toBeVisible();
  await expect(draftGrid(page).locator('td.inventory-section-cell').filter({ hasText: /^Items$/ })).toHaveCount(0);
  await actions.getByRole('button', { name: 'Add item', exact: true }).click();
  await expect(draftCell(page, id, 'lines/1/name')).toBeVisible();
  await expect.poll(() => draftGrid(page).evaluate(grid => !grid.busy)).toBe(true);
  await draftCell(page, id, 'lines/1/name').locator('xpath=..').getByRole('button', { name: 'Remove item', exact: true }).click();
  await expect(draftCell(page, id, 'lines/1/name')).toHaveCount(0);
  await expect(cellValue(page, id, 'lines/0/name')).toHaveText(`${prefix} B`);
  await expect.poll(() => draftGrid(page).evaluate(grid => !grid.busy)).toBe(true);
  await actions.getByRole('button', { name: 'Discard operation', exact: true }).click();
  await expect(draftCell(page, id, 'lines/0/name')).toHaveCount(0);
});

test('manual stock corrections start with a usable reason and preserve an edited reason during later cell changes', async ({ page }) => {
  const name = `Correction reason ${Date.now()}`;
  await seedItem(page, name, { location: 'Count shelf' });
  await page.reload();
  const row = stockRow(page, name);
  await editStockCell(page, row, 'quantity', '11');
  const [id] = await draftIds(page); await openReview(page);
  await expect(cellValue(page, id, 'reason')).toHaveText('Manual quantity correction');
  await expect(page.getByRole('button', { name: 'Submit selected', exact: true })).toBeEnabled();
  await editDraftCell(page, id, 'reason', 'Counted shelf after delivery');
  await editStockCell(page, row, 'article', 'COUNTED');
  await expect(cellValue(page, id, 'reason')).toHaveText('Counted shelf after delivery');
  await expect(cellValue(page, id, 'lines/0/article')).toHaveText('COUNTED');
  await expect(page.getByRole('button', { name: 'Submit selected', exact: true })).toBeEnabled();
  await page.reload(); await openReview(page);
  await expect(cellValue(page, id, 'reason')).toHaveText('Counted shelf after delivery');
});

import { expect, test } from '@playwright/test';
import { signIn, pageContext } from './auth.fixture.mjs';
import { draftCell, draftGrid, openDraftCell, openReview, selectDrafts, signals, stockGrid } from './grid.fixture.mjs';

const grid = page => page.locator('.catalogue-grid');
const cell = (page, id, field) => grid(page).locator(`td[data-entry-id="${id}"][data-path="${field}"]`);

async function open(page, kind) {
  if (await page.locator('.catalogue-tabs').count()) await page.locator(`.catalogue-tabs a[href="/catalogues/${kind}"]`).click();
  else await page.goto(`/catalogues/${kind}`);
  await expect(page).toHaveURL(new RegExp(`/catalogues/${kind}$`));
  await expect(grid(page)).toBeVisible();
  await expect.poll(() => grid(page).evaluate(node => !!node.worksheet)).toBe(true);
  await expect.poll(() => grid(page).evaluate(node => node.model.id)).toBe(`catalogue-${kind}`);
}

async function create(page, name) {
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  const input = page.locator('.catalogue-new-row .inventory-cell-editor > input');
  await expect(input).toBeVisible();
  const id = await page.locator('.catalogue-new-row').evaluate(node => node.model.rows[0].id);
  await input.fill(name);
  await input.press('Enter');
  await expect(page.locator('.catalogue-new-row')).toBeHidden();
  await expect(cell(page, id, 'name')).toBeVisible();
  await page.locator('.catalogue-search').click();
  await expect(cell(page, id, 'name')).toContainText(name);
  return id;
}

async function edit(page, id, field, value, key = 'Enter') {
  const input = await catalogueInput(page, id, field);
  await input.fill(value);
  if (key === 'blur') await page.locator('.catalogue-search').click();
  else await input.press(key);
  await expect(page.locator('.catalogue-save-status')).toHaveText('Saved');
  await expect(cell(page, id, field)).toContainText(value);
}

async function reference(page, id, field, option) {
  await catalogueInput(page, id, field);
  const select = grid(page).locator('.inventory-goods-choice');
  await expect(select).toHaveAttribute('aria-busy', 'false');
  await select.selectOption(option);
  await expect(page.locator('.catalogue-save-status')).toHaveText('Saved');
  await expect.poll(() => grid(page).evaluate(node => node._attempts.size)).toBe(0);
}

async function catalogueInput(page, id, field) {
  const input = cell(page, id, field).locator('.inventory-cell-editor > input');
  await expect(async () => {
    if (!(await input.isVisible())) await grid(page).evaluate((node, { id, field }) => node.focusField(id, field), { id, field });
    await expect(input).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  return input;
}

test('all five tables share inline saves, references, filters, layouts and translations', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await expect(page.getByRole('link', { name: 'Catalogues', exact: true })).toBeVisible();
  const suffix = Date.now();
  await open(page, 'warehouses');
  const warehouse = await create(page, `Warehouse ${suffix}`);
  await edit(page, warehouse, 'description', 'Main storage', 'Tab');
  await open(page, 'categories');
  const category = await create(page, `Category ${suffix}`);
  await create(page, `Another category ${suffix}`);
  await expect.poll(() => page.locator('.catalogue-new-row').evaluate(node => node._attempts.size)).toBe(0);
  await page.goBack();
  await expect.poll(() => grid(page).evaluate(node => node.model.id)).toBe('catalogue-warehouses');
  await page.goForward();
  await expect(cell(page, category, 'name')).toContainText(`Category ${suffix}`);
  await open(page, 'suppliers');
  const supplier = await create(page, `Supplier ${suffix}`);
  await edit(page, supplier, 'contact', 'Contact details', 'blur');
  await open(page, 'stores');
  const store = await create(page, `Store ${suffix}`);
  await reference(page, store, 'warehouse-id', warehouse);
  await expect(cell(page, store, 'warehouse-id')).toContainText(`Warehouse ${suffix}`);
  await open(page, 'goods');
  const goods = await create(page, `Goods ${suffix}`);
  await edit(page, goods, 'article', 'ART-1', 'Tab');
  await reference(page, goods, 'category-id', category);
  await reference(page, goods, 'store-id', store);
  await expect(cell(page, goods, 'unit')).toHaveText('pcs');
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await expect(page.locator('#stock-grid')).toBeVisible();
  await page.getByRole('link', { name: 'Catalogues', exact: true }).click();
  await expect(cell(page, goods, 'name')).toContainText(`Goods ${suffix}`);
  await page.reload();
  await expect(cell(page, goods, 'category-id')).toContainText(`Category ${suffix}`);
  await page.locator('.catalogue-search').fill(`Missing ${suffix}`);
  await expect(cell(page, goods, 'name')).toHaveCount(0);
  await page.getByRole('button', { name: 'Reset all', exact: true }).click();
  await expect(cell(page, goods, 'name')).toBeVisible();
  await grid(page).getByRole('button', { name: 'Filter Article', exact: true }).click();
  await grid(page).getByRole('combobox', { name: 'Add condition', exact: true }).selectOption('eq');
  const filter = grid(page).getByRole('textbox', { name: 'Value 1', exact: true });
  await filter.fill('Missing'); await filter.press('Enter');
  await expect(cell(page, goods, 'name')).toHaveCount(0);
  await grid(page).getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: 'Reset all', exact: true }).click();
  await expect(cell(page, goods, 'name')).toBeVisible();
  await grid(page).getByRole('button', { name: 'Sort by Name', exact: true }).click();
  await expect(grid(page).locator('thead td[data-column-key="name"]')).toHaveAttribute('aria-sort', 'descending');

  const header = grid(page).locator('thead td[data-column-key="name"]');
  const box = await header.boundingBox();
  const saved = page.waitForResponse(response => response.url().endsWith('/action/tover.stock.web/save-layout'));
  await page.mouse.move(box.x + box.width - 2, box.y + box.height / 2);
  await page.mouse.down(); await page.mouse.move(box.x + box.width + 65, box.y + box.height / 2, { steps: 8 }); await page.mouse.up();
  expect((await saved).status()).toBe(200);
  await expect(grid(page)).toHaveAttribute('data-layout-status', 'Saved');
  const width = await grid(page).evaluate(node => node.model.savedWidths?.name || node._columnLayout?.widths.name);
  await page.reload();
  await expect.poll(() => grid(page).evaluate(node => node.model.savedWidths?.name)).toBe(width);

  await page.getByRole('button', { name: 'Russian', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Справочники' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Склады', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Добавить строку', exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/catalogues/catalogues-ru.png', fullPage: true });
  await page.getByRole('button', { name: 'Английский', exact: true }).click();
  await page.screenshot({ path: 'test-results/catalogues/catalogues-en.png', fullPage: true });
  await page.getByRole('button', { name: 'Switch to dark mode', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/catalogues/catalogues-mobile-dark.png', fullPage: true });
  expect(errors).toEqual([]);
});

test('member updates preserve active input and stale saves require a choice', async ({ page, browser }) => {
  await signIn(page);
  await open(page, 'goods');
  const id = await create(page, `Shared ${Date.now()}`);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await other.newPage();
    await signIn(member, 'member@example.test');
    await open(member, 'goods');
    await grid(page).evaluate((node, id) => node.focusField(id, 'name'), id);
    const input = grid(page).locator('.inventory-cell-editor > input');
    await input.fill('My unsaved name');
    await edit(member, id, 'name', 'Member saved name');
    await expect(input).toHaveValue('My unsaved name');
    await input.press('Enter');
    await expect(page.locator('.catalogue-save-status')).toHaveText('Failed');
    await expect(grid(page).getByRole('button', { name: 'Use saved value', exact: true })).toBeVisible();
    await grid(page).getByRole('button', { name: 'Use saved value', exact: true }).click();
    await expect(cell(page, id, 'name')).toHaveText('Member saved name');
    await page.reload();
    await expect(cell(page, id, 'name')).toHaveText('Member saved name');
  } finally { await other.close(); }
});

test('save retries are idempotent and cross-workspace relationships are rejected', async ({ page, browser }) => {
  await signIn(page); await open(page, 'categories');
  const category = await create(page, `Private category ${Date.now()}`);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const outsider = await other.newPage();
    await signIn(outsider, 'other@example.test'); await open(outsider, 'goods');
    const { csrf, version } = await pageContext(outsider);
    const save = async data => {
      const response = await outsider.request.post('/action/tover.catalogues.web/save', {
        headers: { origin: new URL(outsider.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version }, data,
      });
      expect(response.status()).toBe(200);
      const body = await response.text();
      return JSON.parse(JSON.parse(body.split('\n').find(line => line.startsWith('data: signals ')).slice(14))._catalogueSaveJson);
    };
    const id = `retry-${Date.now()}`;
    const request = { kind: 'goods', id, revision: 0, path: 'name', value: 'Retry goods', commandId: `${id}-create` };
    const first = await save(request), second = await save(request);
    expect(first.status).toBe('ok'); expect(second.row).toEqual(first.row);
    const rejected = await save({ ...request, revision: 1, path: 'category-id', value: category, commandId: `${id}-link` });
    expect(rejected.status).toBe('failed');
  } finally { await other.close(); }
});

test('rejected names remain editable and a lost save response retries the same request', async ({ page }) => {
  await signIn(page); await open(page, 'goods');
  const id = await create(page, `Recovery ${Date.now()}`);
  await expect.poll(() => grid(page).evaluate(node => !!node.busy)).toBe(false);
  await grid(page).evaluate((node, id) => node.focusField(id, 'name'), id);
  const input = grid(page).locator('.inventory-cell-editor > input');
  await input.fill(''); await input.press('Enter');
  await expect(page.locator('.catalogue-save-status')).toHaveText('Failed');
  await expect(cell(page, id, 'name').getByRole('button', { name: 'Use saved value', exact: true })).toBeVisible();
  await expect.poll(() => grid(page).evaluate(node => [...node._attempts.values()][0]?.value)).toBe('');
  await edit(page, id, 'name', 'Corrected name');

  const requests = [];
  await page.route('**/action/tover.catalogues.web/save', async route => {
    requests.push(route.request().postDataJSON());
    if (requests.length === 1) { await route.fetch(); await route.abort('failed'); }
    else await route.continue();
  });
  await expect.poll(() => grid(page).evaluate(node => !!node.busy)).toBe(false);
  await grid(page).evaluate((node, id) => node.focusField(id, 'article'), id);
  await input.fill(' RETRY '); await input.press('Enter');
  await expect(page.locator('.catalogue-save-status')).toHaveText('Failed');
  await cell(page, id, 'article').getByRole('button', { name: 'Retry saving', exact: true }).click();
  await expect(page.locator('.catalogue-save-status')).toHaveText('Saved');
  expect(requests).toHaveLength(2); expect(requests[1]).toEqual(requests[0]);
  await expect.poll(() => grid(page).evaluate(node => node._attempts.size)).toBe(0);
  await page.reload();
  await expect(cell(page, id, 'article')).toHaveText('RETRY');
  await expect(cell(page, id, 'article')).toHaveAttribute('data-revision', '3');
});

test('typed catalogue relationships create, reuse, clear and retry without duplicate records', async ({ page }) => {
  await signIn(page); await open(page, 'goods');
  const suffix = Date.now(), categoryName = `Typed category ${suffix}`, storeName = `Typed store ${suffix}`;
  const id = await create(page, `Related goods ${suffix}`);
  await edit(page, id, 'category-id', categoryName, 'Tab');
  await edit(page, id, 'store-id', storeName, 'blur');
  const row = await grid(page).evaluate((node, id) => node.model.rows.find(row => row.id === id), id);
  const category = row.cells[4].value, store = row.cells[5].value;
  expect(category).not.toBe(categoryName); expect(store).not.toBe(storeName);
  await open(page, 'stores');
  await edit(page, store, 'warehouse-id', `Typed warehouse ${suffix}`);
  await open(page, 'categories');
  await expect(cell(page, category, 'name')).toContainText(categoryName);
  await create(page, categoryName);
  await open(page, 'goods');
  await grid(page).evaluate((node, id) => node.focusField(id, 'category-id'), id);
  await grid(page).locator('.inventory-cell-editor > input').fill(`${categoryName} missing`);
  await grid(page).locator('.inventory-cell-editor > input').press('Escape');
  await expect(cell(page, id, 'category-id')).toContainText(categoryName);
  await reference(page, id, 'category-id', '');
  await catalogueInput(page, id, 'category-id');
  await grid(page).locator('.inventory-cell-editor > input').fill(categoryName);
  await grid(page).locator('.inventory-cell-editor > input').press('Enter');
  await expect(page.locator('.catalogue-save-status')).toHaveText('Failed');
  await expect(page.locator('.catalogue-notice')).toContainText('Several records');
  await reference(page, id, 'category-id', category);
  await expect(cell(page, id, 'category-id')).toContainText(categoryName);

  const attempts = [];
  await page.route('**/action/tover.catalogues.web/save', async route => {
    attempts.push(route.request().postDataJSON());
    if (attempts.length === 1) { await route.fetch(); await route.abort('failed'); }
    else await route.continue();
  });
  await catalogueInput(page, id, 'store-id');
  await grid(page).locator('.inventory-cell-editor > input').fill(`Retry store ${suffix}`);
  await grid(page).locator('.inventory-cell-editor > input').press('Enter');
  await expect(page.locator('.catalogue-save-status')).toHaveText('Failed');
  await cell(page, id, 'store-id').getByRole('button', { name: 'Retry saving', exact: true }).click();
  await expect(page.locator('.catalogue-save-status')).toHaveText('Saved');
  expect(attempts).toHaveLength(2); expect(attempts[1]).toEqual(attempts[0]);
  await expect.poll(() => grid(page).evaluate(node => node._attempts.size)).toBe(0);
  await page.unroute('**/action/tover.catalogues.web/save');
  await open(page, 'stores');
  await page.locator('.catalogue-search').fill(`Retry store ${suffix}`);
  await expect.poll(() => grid(page).evaluate(node => node.model.viewport.totalRows)).toBe(1);
  const retryStore = await grid(page).evaluate(node => node.model.rows[0].id);
  await page.getByRole('button', { name: 'Russian', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
  const russianInput = await catalogueInput(page, retryStore, 'warehouse-id');
  await expect(grid(page).getByLabel('Выберите запись', { exact: true })).toBeVisible();
  await russianInput.fill(`Новый склад ${suffix}`); await russianInput.press('Tab');
  await expect(page.locator('.catalogue-save-status')).toHaveText('Сохранено');
  await expect(cell(page, retryStore, 'warehouse-id')).toContainText(`Новый склад ${suffix}`);
  await page.getByRole('button', { name: 'Английский', exact: true }).click();
});

test('a member-created category is reused without interrupting another member’s active cell', async ({ page, browser }) => {
  await signIn(page); await open(page, 'goods');
  const suffix = Date.now(), name = `Concurrent category ${suffix}`;
  const goods = await create(page, `Concurrent goods ${suffix}`);
  const input = await catalogueInput(page, goods, 'category-id'); await input.fill(name);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await other.newPage(); await signIn(member, 'member@example.test');
    await open(member, 'categories'); const category = await create(member, name);
    await expect(input).toHaveValue(name); await input.press('Enter');
    await expect(page.locator('.catalogue-save-status')).toHaveText('Saved');
    await expect.poll(() => grid(page).evaluate((node, id) => node.model.rows.find(row => row.id === id).cells.find(cell => cell.target.path === 'category-id').value, goods)).toBe(category);
    await open(page, 'categories'); await page.locator('.catalogue-search').fill(name);
    await expect.poll(() => grid(page).evaluate(node => node.model.viewport.totalRows)).toBe(1);
  } finally { await other.close(); }
});

async function inventoryAction(page, action, data = {}) {
  const { csrf, version } = await pageContext(page);
  const response = await page.context().request.post(`/action/tover.stock.web/${action}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
    data: { view: 'stock', ...data },
  });
  expect(response.status()).toBe(200);
  const body = await response.text();
  const result = Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals ')).map(line => JSON.parse(line.slice(14))));
  expect(result.saveStatus, body).not.toBe('Failed');
  return result;
}

async function stockEdit(page, rowId, field, value, key = 'Enter') {
  await expect.poll(() => stockGrid(page).evaluate(node => !!node.busy)).toBe(false);
  await stockGrid(page).evaluate((node, { rowId, field }) => node.focusField(rowId, field), { rowId, field });
  const input = stockGrid(page).locator('.inventory-cell-editor > input');
  await expect(input).toBeVisible(); await input.fill(value); await input.press(key);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect.poll(() => stockGrid(page).evaluate(node => node._attempts.size)).toBe(0);
}

test('new Stock cells resolve goods, suggest warehouses and register manual entries only on Submit', async ({ page }) => {
  await signIn(page); await open(page, 'goods');
  const suffix = Date.now(), name = `Picker goods ${suffix}`, fresh = `Manual goods ${suffix}`;
  const goods = await create(page, name);
  await edit(page, goods, 'article', 'PICK-1');
  await open(page, 'warehouses');
  const warehouseName = `Picker warehouse ${suffix}`, warehouse = await create(page, warehouseName);
  await edit(page, warehouse, 'name', `${warehouseName} renamed`);
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await expect(stockGrid(page)).toBeVisible();
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  await expect.poll(() => stockGrid(page).evaluate(node => node.model.rows.filter(row => row.id.startsWith('draft:')).length)).toBeGreaterThan(0);
  const rowId = await stockGrid(page).evaluate(node => node.model.rows.filter(row => row.id.startsWith('draft:')).at(-1).id);
  await stockEdit(page, rowId, 'name', name, 'Tab');
  await expect.poll(() => stockGrid(page).evaluate((node, id) => node.model.rows.find(row => row.id === id).cells[0].itemValue, rowId)).toBe(goods);
  await stockEdit(page, rowId, 'name', '');
  await expect.poll(() => stockGrid(page).evaluate(node => !!node.busy)).toBe(false);
  await stockGrid(page).evaluate((node, rowId) => node.focusField(rowId, 'name'), rowId);
  await stockGrid(page).locator('.inventory-cell-editor > input').fill(name);
  const goodsChoices = stockGrid(page).getByLabel('Choose existing item', { exact: true });
  await expect(goodsChoices.locator(`option[value="${goods}"]`)).toBeAttached();
  await goodsChoices.selectOption(goods);
  await expect.poll(() => stockGrid(page).evaluate(node => node._attempts.size)).toBe(0);
  await expect.poll(() => stockGrid(page).evaluate((node, id) => node.model.rows.find(row => row.id === id).cells[0].itemValue, rowId)).toBe(goods);
  await stockEdit(page, rowId, 'quantity', '2');
  await stockGrid(page).evaluate((node, rowId) => node.focusField(rowId, 'location'), rowId);
  const location = stockGrid(page).locator('.inventory-cell-editor > input');
  await location.fill('Picker warehouse');
  const choices = stockGrid(page).getByLabel('Choose record', { exact: true });
  await expect(choices.locator(`option[value="${warehouseName}"]`)).toBeAttached();
  await location.press('ArrowDown'); await expect(choices).toBeFocused();
  await choices.selectOption(warehouseName);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect.poll(() => stockGrid(page).evaluate(node => node._attempts.size)).toBe(0);
  await expect(stockGrid(page).locator(`tr[data-row-id="${rowId}"] td[data-column-key="location"]`)).toContainText(`${warehouseName} renamed`);
  await stockEdit(page, rowId, 'name', fresh);
  const provisional = await stockGrid(page).evaluate((node, id) => node.model.rows.find(row => row.id === id).cells[0].itemValue, rowId);
  expect(provisional).not.toBe(goods);
  await open(page, 'goods'); await page.locator('.catalogue-search').fill(fresh);
  await expect.poll(() => grid(page).evaluate(node => node.model.viewport.totalRows)).toBe(0);
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  const draftId = await stockGrid(page).evaluate((node, id) => node.model.rows.find(row => row.id === id).cells[0].target.draftId, rowId);
  await selectDrafts(page, [draftId]);
  await page.getByRole('button', { name: 'Submit selected', exact: true }).click();
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await open(page, 'goods'); await page.locator('.catalogue-search').fill(fresh);
  await expect(cell(page, provisional, 'name')).toContainText(fresh);
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  const accepted = await stockGrid(page).evaluate((node, name) => node.model.rows.find(row => row.cells[0].value === name), fresh);
  expect(accepted.cells[0].itemTarget).toBeUndefined();
  expect(accepted.cells[1].value).toBe(2);
});

test('operation cells suggest catalogue-only suppliers and Transfer warehouses', async ({ page }) => {
  await signIn(page); await open(page, 'suppliers');
  const suffix = Date.now(), supplier = `Choice supplier ${suffix}`;
  await create(page, supplier);
  await open(page, 'warehouses');
  const place = `Choice warehouse ${suffix}`; await create(page, place);
  await page.goto('/');
  const before = await signals(page, '_draftIds');
  const added = await inventoryAction(page, 'add-entry', { kind: 'operation', type: 'purchase' });
  const purchase = added._inventorySnapshot.draftIds.find(id => !before.includes(id));
  await page.reload(); await openReview(page);
  let input = await openDraftCell(page, purchase, 'party'); await input.fill('Choice supplier');
  let picker = draftGrid(page).getByLabel('Choose record', { exact: true });
  await expect(picker.locator(`option[value="${supplier}"]`)).toBeAttached();
  await picker.selectOption(supplier);
  await expect(draftCell(page, purchase, 'party')).toContainText(supplier);
  const pending = await signals(page, '_draftIds');
  const transferred = await inventoryAction(page, 'add-entry', { kind: 'operation', type: 'transfer' });
  const transfer = transferred._inventorySnapshot.draftIds.find(id => !pending.includes(id));
  await page.reload(); await openReview(page);
  for (const field of ['from-location', 'to-location']) {
    input = await openDraftCell(page, transfer, field); await input.fill('Choice warehouse');
    picker = draftGrid(page).getByLabel('Choose record', { exact: true });
    await expect(picker.locator(`option[value="${place}"]`)).toBeAttached();
    await picker.selectOption(place);
    await expect(draftCell(page, transfer, field)).toContainText(place);
  }
  await expect.poll(() => draftGrid(page).evaluate(node => node._attempts.size)).toBe(0);
});

import { expect, test } from '@playwright/test';
import { signIn, pageContext } from './auth.fixture.mjs';

const saveAction = '**/action/tover.catalogues.grid/save';
const grid = page => page.locator('.catalogue-grid');
const workbench = page => page.locator('catalogue-workbench');
const status = page => page.locator('.catalogue-save-status');
const cell = (page, id, field) => grid(page).locator(`td[data-entry-id="${id}"][data-path="${field}"]`);
const path = kind => (kind === 'products' ? '/catalogues' : `/catalogues/${kind}`);
const cellValue = (page, id, field) => grid(page).evaluate((node, { id, field }) =>
  node.model.rows.find(row => row.id === id).cells.find(cell => cell.target?.path === field).value, { id, field });

async function open(page, kind) {
  const link = page.locator(`.catalogue-kinds a[href="${path(kind)}"]`);
  if (await link.count()) await link.click();
  else await page.goto(path(kind));
  await expect(page).toHaveURL(new RegExp(`${path(kind)}$`));
  await expect.poll(() => grid(page).evaluate(node => !!node.worksheet)).toBe(true);
  await expect.poll(() => grid(page).evaluate(node => node.model.id)).toBe(`catalogue-${kind}`);
}

// Status is empty once saved; the refresh a save triggers must also land, or it rebuilds the grid under the next edit.
async function saved(page) {
  await expect(status(page)).toHaveText('');
  await expect.poll(() => workbench(page).evaluate(node => !node.pending && node.grid._attempts.size === 0
    && (!node.requestId || node.requestId === node.lastResponseId))).toBe(true);
}

async function catalogueInput(page, id, field) {
  const input = cell(page, id, field).locator('.inventory-cell-editor > input');
  await expect(async () => {
    if (!(await input.isVisible())) await grid(page).evaluate((node, { id, field }) => node.focusField(id, field), { id, field });
    await expect(input).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  return input;
}

// New rows are drafts at the top of the grid; one Save registers them all.
async function create(page, ...names) {
  const ids = [];
  for (const name of names) {
    await page.getByRole('button', { name: 'Add row', exact: true }).click();
    const id = await workbench(page).evaluate(node => node.drafts[0].id);
    const input = await catalogueInput(page, id, 'name');
    await input.fill(name); await input.press('Enter');
    await expect.poll(() => workbench(page).evaluate((node, id) => node.drafts.find(draft => draft.id === id)?.values.name, id)).toBe(name);
    ids.push(id);
  }
  await page.locator('.catalogue-save-new').click();
  await expect(page.locator('.catalogue-save-new')).toBeHidden();
  await saved(page);
  for (const [index, id] of ids.entries()) await expect(cell(page, id, 'name')).toContainText(names[index]);
  return names.length === 1 ? ids[0] : ids;
}

async function edit(page, id, field, value, key = 'Enter') {
  const input = await catalogueInput(page, id, field);
  await input.fill(value);
  if (key === 'blur') await page.locator('.catalogue-search').click();
  else await input.press(key);
  await saved(page);
  await expect(cell(page, id, field)).toContainText(value);
}

// Relationships are chosen from the active records the server offers; `label` '' picks None.
async function choose(page, id, field, label, { wait = true } = {}) {
  await catalogueInput(page, id, field);
  const select = grid(page).locator('.inventory-goods-choice');
  await expect(select).toHaveAttribute('aria-busy', 'false');
  if (label) await expect(select.locator('option').filter({ hasText: new RegExp(`^${label}$`) })).toHaveCount(1);
  await select.selectOption(label ? { label } : '');
  if (wait) await saved(page);
}

test('all five tables share drafts, inline saves, references, search and translations', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await expect(page.getByRole('link', { name: 'Catalogues', exact: true })).toBeVisible();
  const suffix = Date.now();
  await open(page, 'warehouses');
  const warehouse = await create(page, `Warehouse ${suffix}`);
  await edit(page, warehouse, 'description', 'Main storage', 'Tab');
  await open(page, 'categories');
  const [category, another] = await create(page, `Category ${suffix}`, `Another category ${suffix}`);
  await expect(cell(page, another, 'name')).toContainText(`Another category ${suffix}`);
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  await expect(grid(page).locator('.catalogue-draft-row')).not.toHaveCount(0);
  await page.locator('.catalogue-discard-new').click();
  await expect(page.locator('.catalogue-save-new')).toBeHidden();
  await expect.poll(() => workbench(page).evaluate(node => node.drafts.length)).toBe(0);
  await page.goBack();
  await expect.poll(() => grid(page).evaluate(node => node.model.id)).toBe('catalogue-warehouses');
  await page.goForward();
  await expect(cell(page, category, 'name')).toContainText(`Category ${suffix}`);
  await open(page, 'suppliers');
  const supplier = await create(page, `Supplier ${suffix}`);
  await edit(page, supplier, 'contact', 'Contact details', 'blur');
  await open(page, 'stores');
  const store = await create(page, `Store ${suffix}`);
  await choose(page, store, 'default-warehouse-id', `Warehouse ${suffix}`);
  await expect(cell(page, store, 'default-warehouse-id')).toContainText(`Warehouse ${suffix}`);
  await open(page, 'products');
  const product = await create(page, `Product ${suffix}`);
  await choose(page, product, 'category-id', `Category ${suffix}`);
  await choose(page, product, 'default-warehouse-id', `Warehouse ${suffix}`);
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await expect(page.locator('#stock-grid')).toBeVisible();
  await page.getByRole('link', { name: 'Catalogues', exact: true }).click();
  await expect(cell(page, product, 'name')).toContainText(`Product ${suffix}`);
  await page.reload();
  await expect(cell(page, product, 'category-id')).toContainText(`Category ${suffix}`);
  await page.locator('.catalogue-search').fill(`Missing ${suffix}`);
  await expect(cell(page, product, 'name')).toHaveCount(0);
  await page.getByRole('button', { name: 'Reset all', exact: true }).click();
  await expect(cell(page, product, 'name')).toBeVisible();
  await grid(page).getByRole('button', { name: 'Sort by Name', exact: true }).click();
  await expect(grid(page).locator('thead td[data-column-key="name"]')).toHaveAttribute('aria-sort', 'descending');

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
  await open(page, 'products');
  const id = await create(page, `Shared ${Date.now()}`);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await other.newPage();
    await signIn(member, 'member@example.test');
    await open(member, 'products');
    const input = await catalogueInput(page, id, 'name');
    await input.fill(`My unsaved name ${id}`);
    await edit(member, id, 'name', `Member saved name ${id}`);
    await expect(input).toHaveValue(`My unsaved name ${id}`);
    await input.press('Enter');
    await expect(status(page)).toHaveText('Failed');
    await expect(cell(page, id, 'name').getByRole('button', { name: 'Use saved value', exact: true })).toBeVisible();
    await cell(page, id, 'name').getByRole('button', { name: 'Use saved value', exact: true }).click();
    await expect(cell(page, id, 'name')).toHaveText(`Member saved name ${id}`);
    await page.reload();
    await expect(cell(page, id, 'name')).toHaveText(`Member saved name ${id}`);
  } finally { await other.close(); }
});

test('save retries are idempotent and cross-workspace relationships are rejected', async ({ page, browser }) => {
  await signIn(page); await open(page, 'categories');
  const category = await create(page, `Private category ${Date.now()}`);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const outsider = await other.newPage();
    await signIn(outsider, 'other@example.test', undefined, 'Other workspace'); await open(outsider, 'products');
    const { csrf, version } = await pageContext(outsider);
    const save = async data => {
      const response = await outsider.request.post(saveAction.replace('**', ''), {
        headers: { origin: new URL(outsider.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version }, data,
      });
      expect(response.status()).toBe(200);
      const body = await response.text();
      return JSON.parse(JSON.parse(body.split('\n').find(line => line.startsWith('data: signals ')).slice(14))._catalogueSaveJson);
    };
    const id = crypto.randomUUID();
    const request = { kind: 'products', id, revision: 0, path: 'name', value: `Retry product ${id}`, values: { name: `Retry product ${id}` }, commandId: `${id}-create` };
    const first = await save(request), second = await save(request);
    expect(first.status).toBe('ok'); expect(second.row).toEqual(first.row);
    const rejected = await save({ kind: 'products', id, revision: first.row.revision, path: 'category-id', value: category, commandId: `${id}-link` });
    expect(rejected.status).toBe('failed');
  } finally { await other.close(); }
});

test('a late save acknowledgement preserves a newer displayed row', async ({ page }) => {
  await signIn(page); await open(page, 'products');
  const id = await create(page, `Ordering ${Date.now()}`);
  const result = await workbench(page).evaluate((workbench, id) => {
    const current = workbench.grid.model.rows.find(row => row.id === id);
    const rowAt = (revision, name) => ({ ...current, cells: current.cells.map(cell => ({
      ...cell, value: cell.target.path === 'name' ? name : cell.value,
      target: { ...cell.target, revision },
    })) });
    const latest = rowAt(6, 'Latest name');
    workbench.grid._attempts.set(`${id}:name`, { target: { ...current.cells[0].target, revision: 4 },
      value: 'Earlier name', state: 'saving' });
    workbench.grid.model = { ...workbench.grid.model,
      rows: workbench.grid.model.rows.map(row => row.id === id ? latest : row) };
    workbench.pending = { kind: 'products', id, revision: 4, path: 'name', value: 'Earlier name', commandId: 'delayed' };
    workbench.setStatus('Saving');
    // Hold the subsequent refresh so it cannot hide a stale acknowledgement patch.
    const refresh = workbench.refresh;
    workbench.refresh = () => {};
    try {
      workbench.catalogueSave = { commandId: 'delayed', status: 'ok',
        row: { id, revision: 5 }, gridRow: rowAt(5, 'Earlier name') };
      return { row: workbench.grid.model.rows.find(row => row.id === id),
        pending: workbench.pending, status: workbench.status.textContent, attempts: workbench.grid._attempts.size };
    } finally { workbench.refresh = refresh; }
  }, id);
  expect(result.row.cells[0].value).toBe('Latest name');
  expect(result.row.cells[0].target.revision).toBe(6);
  expect(result.pending).toBeNull();
  expect(result.status).toBe('');
  expect(result.attempts).toBe(0);
});

test('rejected names remain editable and a lost save response retries the same request', async ({ page }) => {
  await signIn(page); await open(page, 'products');
  const id = await create(page, `Recovery ${Date.now()}`);
  const input = await catalogueInput(page, id, 'name');
  await input.fill(''); await input.press('Enter');
  await expect(status(page)).toHaveText('Failed');
  await expect(cell(page, id, 'name').getByRole('button', { name: 'Use saved value', exact: true })).toBeVisible();
  await expect.poll(() => grid(page).evaluate(node => [...node._attempts.values()][0]?.value)).toBe('');
  await edit(page, id, 'name', `Corrected name ${id}`);

  const requests = [];
  await page.route(saveAction, async route => {
    requests.push(route.request().postDataJSON());
    if (requests.length === 1) { await route.fetch(); await route.abort('failed'); }
    else await route.continue();
  });
  const retry = await catalogueInput(page, id, 'name');
  await retry.fill(`Retried name ${id}`); await retry.press('Enter');
  await expect(status(page)).toHaveText('Failed');
  await cell(page, id, 'name').getByRole('button', { name: 'Retry saving', exact: true }).click();
  await saved(page);
  expect(requests).toHaveLength(2); expect(requests[1]).toEqual(requests[0]);
  await page.reload();
  await expect(cell(page, id, 'name')).toHaveText(`Retried name ${id}`);
  await expect(cell(page, id, 'name')).toHaveAttribute('data-revision', '3');
});

test('typed relationships are chosen, cleared and retried, never created from typed names', async ({ page }) => {
  await signIn(page);
  const suffix = Date.now(), categoryName = `Typed category ${suffix}`, warehouseName = `Typed warehouse ${suffix}`;
  await open(page, 'warehouses'); await create(page, warehouseName);
  await open(page, 'categories'); const category = await create(page, categoryName);
  await open(page, 'products');
  const id = await create(page, `Related product ${suffix}`);

  const typed = await catalogueInput(page, id, 'category-id');
  await typed.fill(`Unknown category ${suffix}`); await typed.press('Enter');
  await expect(status(page)).toHaveText('Failed');
  await expect(page.locator('.catalogue-notice')).toContainText('Choose an active record of this workspace.');
  await open(page, 'categories'); await page.locator('.catalogue-search').fill(`Unknown category ${suffix}`);
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await open(page, 'products');

  await choose(page, id, 'category-id', categoryName);
  expect(await cellValue(page, id, 'category-id')).toBe(category);
  const escaped = await catalogueInput(page, id, 'category-id');
  await escaped.fill(`${categoryName} missing`); await escaped.press('Escape');
  await expect(cell(page, id, 'category-id')).toContainText(categoryName);
  await choose(page, id, 'category-id', '');
  await expect.poll(() => cellValue(page, id, 'category-id')).toBe('');

  const attempts = [];
  await page.route(saveAction, async route => {
    attempts.push(route.request().postDataJSON());
    if (attempts.length === 1) { await route.fetch(); await route.abort('failed'); }
    else await route.continue();
  });
  await choose(page, id, 'category-id', categoryName, { wait: false });
  await expect(status(page)).toHaveText('Failed');
  await cell(page, id, 'category-id').getByRole('button', { name: 'Retry saving', exact: true }).click();
  await saved(page);
  expect(attempts).toHaveLength(2); expect(attempts[1]).toEqual(attempts[0]);
  await page.unroute(saveAction);
  await expect.poll(() => cellValue(page, id, 'category-id')).toBe(category);
  await open(page, 'categories'); await page.locator('.catalogue-search').fill(categoryName);
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(1);

  await open(page, 'stores');
  const store = await create(page, `Typed store ${suffix}`);
  await page.getByRole('button', { name: 'Russian', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
  await catalogueInput(page, store, 'default-warehouse-id');
  await expect(grid(page).getByLabel('Выберите запись', { exact: true })).toBeVisible();
  await choose(page, store, 'default-warehouse-id', warehouseName);
  await expect(cell(page, store, 'default-warehouse-id')).toContainText(warehouseName);
  await page.getByRole('button', { name: 'Английский', exact: true }).click();
});

test('a member-created category is offered without interrupting another member’s active cell', async ({ page, browser }) => {
  await signIn(page); await open(page, 'products');
  const suffix = Date.now(), name = `Concurrent category ${suffix}`;
  const product = await create(page, `Concurrent product ${suffix}`);
  const input = await catalogueInput(page, product, 'category-id'); await input.fill(name);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await other.newPage(); await signIn(member, 'member@example.test');
    await open(member, 'categories'); const category = await create(member, name);
    await expect(input).toHaveValue(name);
    // Typing again asks the server for the current choices, which now include the member's category.
    await input.fill(name);
    const select = grid(page).locator('.inventory-goods-choice');
    await expect(select.locator('option').filter({ hasText: new RegExp(`^${name}$`) })).toHaveCount(1);
    await select.selectOption({ label: name });
    await saved(page);
    await expect.poll(() => cellValue(page, product, 'category-id')).toBe(category);
    await open(page, 'categories'); await page.locator('.catalogue-search').fill(name);
    await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(1);
  } finally { await other.close(); }
});

test('Bel snapshot signals replace omitted fields, shorter arrays and explicit nulls', async ({ page }) => {
  await signIn(page);
  await open(page, 'products');
  await page.evaluate(() => {
    const probe = document.createElement('div');
    probe.id = 'goods-snapshot-probe';
    probe.dataset.signals__ifmissing = JSON.stringify({ _goodsSnapshotJson: 'null' });
    probe.dataset.effect = 'el.snapshot=JSON.parse($_goodsSnapshotJson)';
    document.body.append(probe);
  });
  const probe = page.locator('#goods-snapshot-probe');
  await expect.poll(() => probe.evaluate(node => node.snapshot)).toBe(null);
  for (const snapshot of [
    { row: { id: 'G', name: 'Nut"☃', category: 'Old', labels: { category: 'Old', store: 'Main' } }, rows: [1, 2] },
    { row: { id: 'G', name: 'Bolt', labels: {} }, rows: [] },
    { row: null },
  ]) {
    await page.evaluate(async snapshot => {
      const { mergePatch } = await import('/datastar.js');
      mergePatch({ _goodsSnapshotJson: JSON.stringify(snapshot) });
    }, snapshot);
    await expect.poll(() => probe.evaluate(node => node.snapshot)).toEqual(snapshot);
  }
});

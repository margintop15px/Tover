import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';
import { acceptDrafts as accept, addStockRow, draftCell, draftGrid, draftIds, editDraftCell, gridEditor, gridHeader, openDraftCell, openReview as review, revertPending as clean, signals, stockGrid, stockRow } from './grid.fixture.mjs';

const smartInput = page => page.locator('stock-entry input');
const smartOption = (page, label) => page.getByRole('option').filter({
  has: page.locator('.stock-entry-option-field').filter({ hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }),
});

async function choose(page, label) {
  const options = smartOption(page, label);
  try {
    await expect(options.first()).toBeVisible();
  } catch (error) {
    const state = await page.locator('stock-entry').evaluate(element => ({
      open: element.open, pending: element.pending, request: element._request,
      model: element.model, input: element.input.value,
    }));
    throw new Error(`${error.message}\nSmart renderer state: ${JSON.stringify(state)}`);
  }
  const fieldOption = options.filter({ has: page.locator('.stock-entry-option-description', { hasText: /^Choose field$/ }) });
  await (await fieldOption.count() ? fieldOption : options).click();
}

async function field(page, label, value) {
  await smartInput(page).fill(label);
  await choose(page, label);
  await smartInput(page).fill(value);
  await smartInput(page).press('Enter');
  await expect(page.getByRole('button', { name: `Edit ${label}: ${value}`, exact: true })).toBeVisible();
}

async function addOpening(page, name, location = 'Main') { return addStockRow(page, name, location); }

const browserErrors = new WeakMap();
test.beforeEach(async ({ page }) => {
  const errors = [];
  browserErrors.set(page, errors);
  page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await clean(page);
});
test.afterEach(async ({ page }) => expect(browserErrors.get(page)).toEqual([]));

test('smart composition hides filled fields, clears partial values and makes incomplete Done actionable', async ({ page }) => {
  const completions = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/action/tover.inventory.smart-web/complete') completions.push(request.postDataJSON());
  });
  await smartInput(page).fill('add');
  await expect(smartOption(page, 'Purchase')).toBeVisible();
  await expect(smartOption(page, 'Goods')).toHaveCount(0);
  await expect(smartOption(page, 'Party')).toHaveCount(0);
  await choose(page, 'Payment');
  await expect(page.getByRole('button', { name: 'Edit Add: Payment', exact: true })).toBeVisible();
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  await expect(page.locator('.sheet-count')).toHaveCount(0);
  await expect(smartOption(page, 'Done')).toHaveCount(0);
  await expect(smartOption(page, 'Direction')).toHaveCount(0);
  await field(page, 'Party', 'Private payment party');
  await expect(smartOption(page, 'Party')).toHaveCount(0);
  await smartInput(page).fill('Amount');
  await choose(page, 'Amount');
  await smartInput(page).fill('unfinished decimal');
  await page.getByRole('button', { name: 'Clear unfinished field', exact: true }).click();
  await expect(smartInput(page)).toHaveValue('');
  await expect(smartInput(page)).toHaveAttribute('aria-label', 'Search or add');
  await expect(page.getByRole('button', { name: 'Edit Party: Private payment party', exact: true })).toBeVisible();
  await field(page, 'Amount', '15.25');
  await field(page, 'Currency', 'EUR');
  await expect(smartOption(page, 'Done')).toBeVisible();
  await page.getByRole('button', { name: 'Remove Party: Private payment party', exact: true }).click();
  await expect(smartOption(page, 'Party')).toBeVisible();
  await expect(smartOption(page, 'Done')).toHaveCount(0);
  await smartInput(page).press('Meta+Enter');
  await expect(page.locator('stock-entry .stock-entry-feedback')).toContainText('party is required');
  await page.locator('stock-entry .stock-entry-feedback').getByRole('button', { name: 'Fix field', exact: true }).first().click();
  await expect(smartInput(page)).toHaveAttribute('aria-label', 'Party for operation');
  await smartInput(page).fill('Corrected payment party');
  await smartInput(page).press('Meta+Enter');
  await expect(page.getByRole('img', { name: '1 entries added', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit Add: Payment', exact: true })).toHaveCount(0);
  await review(page);
  await expect(draftGrid(page)).toContainText('Corrected payment party');
  expect(completions.length).toBeGreaterThan(1);
  expect(completions.every(input => input.state.conflict == null || typeof input.state.conflict === 'object')).toBe(true);
  await clean(page);
});

test('smart renderer snapshots acknowledged models and refuses older responses', async ({ page }) => {
  const saved = await page.evaluate(() => {
    const element = document.createElement('stock-entry');
    document.body.append(element);
    element.complete({ kind: 'add' });
    const pendingRequest = element._request;
    element.model = null;
    const initialNullPreservesRequest = element._request === pendingRequest && element.pending;
    const acknowledged = { request: 8, state: { mode: 'filter', marker: 'saved' }, options: [], chips: [] };
    element.model = acknowledged;
    acknowledged.state.marker = 'changed outside the renderer';
    element.model = { request: 7, state: { mode: 'filter', marker: 'stale response' }, options: [], chips: [] };
    const result = { marker: element.model.state.marker, initialNullPreservesRequest };
    element.remove();
    return result;
  });
  expect(saved).toEqual({ marker: 'saved', initialNullPreservesRequest: true });
});

test('cancel addition exits every stage without clearing filters or shared drafts', async ({ page }) => {
  const shared = await addOpening(page, `Shared before cancel ${Date.now()}`);
  await smartInput(page).fill('Location');
  await choose(page, 'Location');
  await smartInput(page).fill('Main');
  await smartInput(page).press('Enter');
  const filter = page.getByRole('group', { name: 'Location filters', exact: true });
  const cancel = page.getByRole('button', { name: 'Cancel addition', exact: true });
  await expect(filter).toContainText('Main');
  await expect.poll(() => page.locator('stock-entry').evaluate(element => element.busy)).toBe(false);

  await smartInput(page).click();
  await smartInput(page).press('ArrowDown');
  await choose(page, 'Add operation');
  await expect(smartOption(page, 'Production')).toBeVisible();
  await cancel.click();
  await expect(cancel).toBeHidden();
  await expect(smartInput(page)).toHaveValue('');
  await expect(filter).toContainText('Main');

  await smartInput(page).fill('add');
  await expect(cancel).toBeVisible();
  await cancel.click();
  await expect(cancel).toBeHidden();
  await smartInput(page).fill('add');
  await choose(page, 'Payment');
  await field(page, 'Party', 'Discard my private party');
  await smartInput(page).fill('Amount');
  await choose(page, 'Amount');
  await smartInput(page).fill('unfinished amount');
  await smartInput(page).press('Escape');
  await expect(smartInput(page)).toHaveValue('');
  await expect(smartInput(page)).toHaveAttribute('aria-label', 'Search or add');
  await expect(page.getByRole('button', { name: 'Edit Party: Discard my private party', exact: true })).toBeVisible();
  await expect.poll(() => page.locator('stock-entry').evaluate(element => element.busy)).toBe(false);
  await smartInput(page).press('Escape');
  await expect(cancel).toBeHidden();
  await expect(filter).toContainText('Main');
  expect(await draftIds(page)).toEqual([shared]);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Edit Party: Discard my private party', exact: true })).toHaveCount(0);
  await expect(cancel).toBeHidden();
  expect(await draftIds(page)).toEqual([shared]);
});

test('Escape and cancel preserve composition while its save is pending or conflicting', async ({ page }) => {
  await smartInput(page).fill('add');
  await choose(page, 'Payment');
  await smartInput(page).fill('Party');
  await choose(page, 'Party');
  await smartInput(page).fill('Keep acknowledged party');
  let release;
  const held = new Promise(resolve => { release = resolve; });
  let responseReady;
  const ready = new Promise(resolve => { responseReady = resolve; });
  await page.route('**/action/tover.inventory.smart-web/complete', async route => {
    const response = await route.fetch();
    responseReady();
    await held;
    await route.fulfill({ response });
  });
  try {
    await smartInput(page).press('Enter');
    await ready;
    await expect(page.getByRole('button', { name: 'Cancel addition', exact: true })).toBeDisabled();
    await smartInput(page).press('Escape');
    await expect(smartInput(page)).toHaveValue('Keep acknowledged party');
    release();
    await expect(page.getByRole('button', { name: 'Edit Party: Keep acknowledged party', exact: true })).toBeVisible();
  } finally {
    release();
    await page.unroute('**/action/tover.inventory.smart-web/complete');
  }
  const retained = await page.locator('stock-entry').evaluate(element => {
    const model = structuredClone(element.model);
    model.request = element._request + 1;
    model.text = 'Attempted party';
    model.field = { key: 'party', label: 'Party' };
    model.conflict = { key: 'party', value: 'Attempted party', saved: 'Keep acknowledged party' };
    element.model = model;
    element.input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    return { text: element.input.value, conflict: element.model.conflict, pending: element.pending };
  });
  expect(retained.text).toBe('Attempted party');
  expect(retained.conflict.saved).toBe('Keep acknowledged party');
  expect(retained.pending).toBe(false);
});

test('global smart-input shortcut preserves composition and commits one active cell edit', async ({ page, browser }) => {
  const id = await addOpening(page, `Shortcut item ${Date.now()}`);
  await smartInput(page).fill('add');
  await choose(page, 'Payment');
  await field(page, 'Party', 'Keep shortcut party');
  const writes = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/action/tover.stock.web/save-field') writes.push(request.postDataJSON());
  });
  await page.getByRole('link', { name: 'Stock', exact: true }).focus();
  await openDraftCell(page, id, 'lines/0/quantity');
  await gridEditor(draftGrid(page)).fill('7');
  await gridEditor(draftGrid(page)).press('Control+k');
  await expect(smartInput(page)).toBeFocused();
  await expect(draftCell(page, id, 'lines/0/quantity')).toContainText('7');
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect(smartInput(page)).toBeFocused();
  await expect(page.getByRole('button', { name: 'Edit Party: Keep shortcut party', exact: true })).toBeVisible();
  expect(writes).toHaveLength(1);
  await smartInput(page).fill('unfinished smart field');
  await page.getByRole('link', { name: 'Stock', exact: true }).focus();
  await page.keyboard.press('Meta+k');
  await expect(smartInput(page)).toBeFocused();
  await expect(smartInput(page)).toHaveValue('unfinished smart field');
  await expect(smartInput(page)).toHaveAttribute('aria-keyshortcuts', 'Meta+K Control+K');
  await expect(smartInput(page)).toHaveAccessibleDescription(/Command or Control plus K/);
  expect(writes).toHaveLength(1);

  await page.route('**/action/tover.stock.web/save-field', route => route.abort());
  await page.getByRole('link', { name: 'Stock', exact: true }).focus();
  await openDraftCell(page, id, 'lines/0/quantity');
  await gridEditor(draftGrid(page)).fill('8');
  await gridEditor(draftGrid(page)).press('Meta+k');
  await expect(smartInput(page)).toBeFocused();
  await expect(draftCell(page, id, 'lines/0/quantity')).toHaveAttribute('data-save-state', 'failed');
  await expect(draftCell(page, id, 'lines/0/quantity')).toContainText('8');
  await expect(page.getByRole('button', { name: 'Edit Party: Keep shortcut party', exact: true })).toBeVisible();
  expect(writes).toHaveLength(2);
  await page.unroute('**/action/tover.stock.web/save-field');
  const expectedNetworkFailure = error => error.startsWith('FetchFailed') && error.includes('/action/tover.stock.web/save-field');
  await expect.poll(() => browserErrors.get(page).filter(expectedNetworkFailure).length).toBe(1);
  browserErrors.get(page).splice(browserErrors.get(page).findIndex(expectedNetworkFailure), 1);

  await draftCell(page, id, 'lines/0/quantity').getByRole('button', { name: 'Use saved value', exact: true }).click();
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    member.on('pageerror', error => browserErrors.get(page).push(error.message));
    await signIn(member, 'member@example.test');
    await page.getByRole('link', { name: 'Stock', exact: true }).focus();
    await openDraftCell(page, id, 'lines/0/quantity');
    await gridEditor(draftGrid(page)).fill('9');
    await editDraftCell(member, id, 'lines/0/quantity', '10');
    await expect.poll(() => draftGrid(page).evaluate((grid, id) => grid.model.rows.flatMap(row => row.cells)
      .find(cell => cell.target?.id === id && cell.target.path === 'lines/0/quantity').value, id)).toBe('10');
    await gridEditor(draftGrid(page)).press('Control+k');
    await expect(smartInput(page)).toBeFocused();
    await expect(draftCell(page, id, 'lines/0/quantity')).toHaveAttribute('data-save-state', 'conflict');
    await expect(draftCell(page, id, 'lines/0/quantity')).toContainText('9');
    await expect(draftCell(page, id, 'lines/0/quantity')).toContainText('Saved: 10');
    await expect(page.getByRole('button', { name: 'Edit Party: Keep shortcut party', exact: true })).toBeVisible();
    expect(writes).toHaveLength(3);
    expect(writes.at(-1)).toMatchObject({ value: '9', revision: writes[1].revision });
  } finally { await memberContext.close(); }
});

test('private composition survives refresh and is shared with another member only after Done', async ({ page, browser }) => {
  const party = `Private supplier ${Date.now()}`;
  const goods = `Private goods ${Date.now()}`;
  await smartInput(page).fill('ass');
  await choose(page, 'Purchase');
  await field(page, 'Party', party);
  await field(page, 'Goods', goods);
  await field(page, 'Location', 'Private shelf');
  expect(await draftIds(page)).toEqual([]);
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    await signIn(member, 'member@example.test');
    await expect(member.getByRole('button', { name: `Edit Party: ${party}`, exact: true })).toHaveCount(0);
    expect(await draftIds(member)).toEqual([]);
    await expect(member.getByText('No changes', { exact: true })).toBeVisible();
    await member.locator('stock-entry input').fill('Goods');
    await choose(member, 'Goods');
    await member.locator('stock-entry input').fill(goods);
    await expect.poll(() => member.locator('stock-entry').evaluate(element => element.model?.text)).toBe(goods);
    await expect(member.getByRole('option').filter({ hasText: goods }).filter({
      has: member.locator('.stock-entry-option-description', { hasText: /^Existing item$/ }),
    })).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('button', { name: `Edit Party: ${party}`, exact: true })).toBeVisible();
    await expect(smartInput(page)).not.toBeFocused();
    await expect(page.getByText('No changes', { exact: true })).toBeVisible();
    await expect(gridHeader(stockGrid(page), 'name')).toBeVisible();
    await page.screenshot({ path: '/tmp/tover-smart-private-composition.png' });
    await smartInput(page).fill('Quantity');
    await choose(page, 'Quantity');
    await smartInput(page).fill('4');
    await smartInput(page).press('Meta+Enter');
    await expect(page.getByRole('img', { name: '1 entries added', exact: true })).toBeVisible();
    await expect(member.getByRole('img', { name: '1 entries added', exact: true })).toBeVisible();
    await review(member);
    await expect(draftGrid(member)).toContainText(party);
    await expect(draftGrid(member)).toContainText(goods);
    const pending = (await draftIds(member))[0];
    await accept(member, [pending]);
    await expect(stockRow(page, goods)).toBeVisible();
    await expect(stockRow(member, goods)).toBeVisible();
    await expect(stockRow(page, goods).locator('td[data-column-key="quantity"] .inventory-cell-value')).toHaveText('4');
    await expect(stockRow(member, goods).locator('td[data-column-key="quantity"] .inventory-cell-value')).toHaveText('4');
  } finally { await memberContext.close(); }
});

test('Goods is the default begins-with filter on both inventory pages with external reset controls', async ({ page }) => {
  const prefix = `Find${Date.now()}`;
  const first = await addOpening(page, `${prefix} alpha`);
  const second = await addOpening(page, `Different ${Date.now()}`, `${prefix} location`);
  await accept(page, [first, second]);
  for (const destination of ['Stock', 'Operations']) {
    if (destination === 'Operations') {
      await page.getByRole('link', { name: 'Operations', exact: true }).click();
      await expect(page).toHaveURL(/\/operations$/);
      await expect(page.locator('#operations-history')).toBeVisible();
    }
    const reset = page.getByRole('button', { name: 'Reset all', exact: true });
    if (await reset.isVisible()) await reset.click();
    await smartInput(page).fill(prefix.toLowerCase());
    await smartInput(page).press('Enter');
    const group = page.getByRole('group', { name: 'Goods filters', exact: true });
    await expect(group).toContainText('begins with');
    await expect(group).toContainText(prefix.toLowerCase());
    await expect(page.locator('stock-entry .stock-entry-chips')).toBeEmpty();
    const grid = destination === 'Stock' ? stockGrid(page) : page.locator('#operations-history inventory-grid');
    await expect.poll(() => grid.evaluate(element => new Set(element.model.rows.map(row => row.groupId)).size)).toBe(1);
    await expect(grid).toContainText(`${prefix} alpha`);
    await reset.click();
    await expect(group).toHaveCount(0);
    await expect.poll(() => grid.evaluate(element => new Set(element.model.rows.map(row => row.groupId)).size)).toBeGreaterThan(1);
  }
});

test('native complex header filters stay synchronized and smart input requires explicit replacement', async ({ page }) => {
  const header = gridHeader(stockGrid(page), 'name');
  await header.getByRole('button', { name: 'Filter Goods', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Filter Goods', exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Add condition', { exact: true }).selectOption('notEq');
  await dialog.getByLabel('Value 1', { exact: true }).fill('Excluded goods');
  await dialog.getByLabel('Value 1', { exact: true }).press('Enter');
  await dialog.getByLabel('Add condition', { exact: true }).selectOption('contains');
  await dialog.getByLabel('Value 2', { exact: true }).fill('Find');
  await dialog.getByLabel('Value 2', { exact: true }).press('Enter');
  await dialog.getByLabel('Join condition 1', { exact: true }).selectOption('or');
  const group = page.getByRole('group', { name: 'Goods filters', exact: true });
  await expect(group).toContainText('does not equal');
  await expect(group).toContainText('contains');
  await expect(group).toContainText('OR');
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(header.locator('.inventory-column-filter.is-active')).toBeVisible();
  await page.screenshot({ path: '/tmp/tover-synchronized-complex-filters.png' });
  await smartInput(page).fill('Goods'); await choose(page, 'Goods');
  await smartInput(page).fill('Replaced');
  const replacement = smartOption(page, 'Replaced').filter({ hasText: /Replace.*filter.*begins/ });
  await expect(replacement).toBeVisible();
  await expect(group.locator('.inventory-filter-condition')).toHaveCount(2);
  await replacement.click();
  await expect(group.locator('.inventory-filter-condition')).toHaveCount(1);
  await expect(group).toContainText('begins with');
  await expect(group).toContainText('Replaced');
  await expect(group).not.toContainText('Excluded goods');
  await header.getByRole('button', { name: 'Filter Goods', exact: true }).click();
  await expect(dialog.getByLabel('Value 1', { exact: true })).toHaveValue('Replaced');
  await dialog.getByRole('button', { name: 'Reset', exact: true }).click();
  await expect(group).toHaveCount(0);
  await expect(header.locator('.inventory-column-filter.is-active')).toHaveCount(0);
});

test('preview errors focus an editable field and the result lists an item only once', async ({ page }) => {
  const baseline = await addOpening(page, `Accepted preview baseline ${Date.now()}`);
  await accept(page, [baseline]);
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  await review(page);
  await expect(page.locator('#inventory-review')).toContainText('name is required');
  await page.locator('#inventory-review').getByRole('button', { name: 'Edit field', exact: true }).first().click();
  await expect(gridEditor(draftGrid(page))).toBeFocused();
  await gridEditor(draftGrid(page)).press('Escape');
  const id = (await draftIds(page))[0];
  const goods = `Unique preview ${Date.now()}`;
  for (const [path, value] of [['lines/0/name', goods], ['lines/0/quantity', '2'], ['lines/0/location', 'Preview shelf']]) await editDraftCell(page, id, path, value);
  await expect(stockRow(page, goods)).toBeVisible();
  const result = page.locator('#inventory-review inventory-grid');
  await expect(result).toBeVisible();
  expect((await result.textContent()).split(goods)).toHaveLength(2);
  const snapshot = await signals(page, '_inventorySnapshot');
  expect(snapshot.rows.map(row => row.name)).toContain(goods);
  const dark = page.getByRole('button', { name: 'Switch to dark mode', exact: true });
  if (await dark.isVisible()) await dark.click();
  await stockRow(page, goods).scrollIntoViewIfNeeded();
  await expect(stockRow(page, goods)).toBeInViewport();
  await page.screenshot({ path: '/tmp/tover-single-change-preview.png' });
  await expect(stockRow(page, goods).getByRole('button', { name: /^History for / })).toHaveCount(0);
  await clean(page);
});

test('a realtime draft notification cannot discard a filter whose response is still pending', async ({ page, browser }) => {
  const prefix = `Concurrent${Date.now()}`;
  const opening = await addOpening(page, `${prefix} goods`);
  await accept(page, [opening]);
  await smartInput(page).fill('Location');
  await choose(page, 'Location');
  await smartInput(page).fill('Main');
  await smartInput(page).press('Enter');
  await expect(page.getByRole('group', { name: 'Location filters', exact: true })).toContainText('Main');
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  let releaseResponse;
  let releaseResetResponse;
  const blocked = new Promise(resolve => { releaseResponse = resolve; });
  let responseReady;
  const ready = new Promise(resolve => { responseReady = resolve; });
  try {
    const member = await memberContext.newPage();
    member.on('pageerror', error => browserErrors.get(page).push(error.message));
    await signIn(member, 'member@example.test');
    await page.route('**/action/tover.inventory.smart-web/complete', async route => {
      if (route.request().postDataJSON().text !== prefix) return route.continue();
      const response = await route.fetch();
      responseReady();
      await blocked;
      await route.fulfill({ response });
    });
    await smartInput(page).fill(prefix);
    await smartInput(page).press('Enter');
    await ready;
    await expect(page.locator('#inventory-save-status')).toHaveText('Saving');
    await expect(page.getByRole('button', { name: 'Reset all', exact: true })).toBeDisabled();
    await expect(smartInput(page)).toHaveJSProperty('readOnly', true);
    await member.getByRole('button', { name: 'Add row', exact: true }).click();
    await expect(member.getByRole('img', { name: '1 entries added', exact: true })).toBeVisible();
    await expect.poll(() => page.evaluate(async () => {
      const { getPath } = await import('/datastar.js');
      return Boolean(getPath('inventoryChanged'));
    })).toBe(true);
    releaseResponse();
    await expect(page.getByRole('group', { name: 'Goods filters', exact: true })).toContainText(prefix);
    await expect(page.getByRole('img', { name: '1 entries added', exact: true })).toBeVisible();
    const rows = stockGrid(page).locator('tbody tr');
    await expect(rows).toHaveCount(1);
    await expect(rows).toContainText(`${prefix} goods`);
    await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
    let resetResponseReady;
    const resetReady = new Promise(resolve => { resetResponseReady = resolve; });
    const resetBlocked = new Promise(resolve => { releaseResetResponse = resolve; });
    await page.route('**/action/tover.stock.web/update-filters', async route => {
      const response = await route.fetch();
      resetResponseReady();
      await resetBlocked;
      await route.fulfill({ response });
    });
    await page.getByRole('button', { name: 'Reset all', exact: true }).click();
    await resetReady;
    await expect(page.locator('stock-entry')).toHaveJSProperty('pending', false);
    await expect(smartInput(page)).toHaveJSProperty('readOnly', true);
    await expect(page.getByRole('button', { name: 'Reset all', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Add row', exact: true })).toBeDisabled();
    await smartInput(page).press('a');
    await expect(smartInput(page)).toHaveValue('');
    releaseResetResponse();
    await expect(page.getByRole('group', { name: 'Goods filters', exact: true })).toHaveCount(0);
    await expect(smartInput(page)).toHaveJSProperty('readOnly', false);
    await clean(page);
  } finally {
    releaseResponse?.();
    releaseResetResponse?.();
    await memberContext.close();
  }
});

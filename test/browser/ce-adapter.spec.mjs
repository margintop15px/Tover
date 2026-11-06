import { expect, test } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';
import { draftCell, draftGrid, gridEditor, openDraftCell, openReview, operationGrid, selectDrafts, signals, stockCell, stockGrid } from './grid.fixture.mjs';

const actionSignals = body => Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals '))
  .map(line => JSON.parse(line.slice('data: signals '.length))));
async function action(page, name, payload = {}) {
  const { csrf, version } = await pageContext(page);
  const response = await page.context().request.post(`/action/tover.stock.web/${name}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
    data: { view: 'stock', ...payload },
  });
  expect(response.status()).toBe(200);
  const body = await response.text(), signals = actionSignals(body);
  expect(signals.saveStatus, body).not.toBe('Failed');
  return signals;
}
async function seed(page, kind, type, fields, extraLines = []) {
  if (kind === 'opening') fields = [...fields, ['reason', 'Inventory count for browser regression']];
  let signals = await action(page, 'add-entry', { kind, type });
  const id = signals._inventorySnapshot.draftSelection.at(-1).id;
  let current = signals._inventorySnapshot.draftSelection.find(entry => entry.id === id);
  for (const path of extraLines) {
    signals = await action(page, 'draft-command', { ...current, command: 'add-line', path });
    current = signals._inventorySnapshot.draftSelection.find(entry => entry.id === id);
  }
  for (const [path, value] of fields) {
    signals = await action(page, 'save-field', { ...current, path, value });
    current = signals._inventorySnapshot.draftSelection.find(entry => entry.id === id);
  }
  return id;
}
async function accept(page, id) {
  const preview = await action(page, 'preview', { selected: [id] });
  expect(preview._review).toBeTruthy();
  return action(page, 'submit', { review: preview._review });
}
async function ready(grid) { await expect.poll(() => grid.evaluate(element => Boolean(element.worksheet) && !element.busy)).toBe(true); }
function saving(page, path, route = 'save-field') {
  return page.waitForResponse(response => new URL(response.url()).pathname === `/action/tover.stock.web/${route}`
    && (route !== 'save-field' || response.request().postDataJSON().path === path), { timeout: 10_000 });
}
async function chooseItem(page, id, itemId) {
  await ready(draftGrid(page)); await draftCell(page, id, 'lines/0/name').click();
  const select = draftGrid(page).getByRole('combobox', { name: 'Choose existing item', exact: true });
  await expect(select).toBeVisible(); const saved = saving(page, 'lines/0/item-id');
  await select.selectOption(itemId); return saved;
}

for (const [originalType, invalidDirection, validDirection, englishError, russianError] of [
  ['purchase', 'incoming', 'outgoing', 'A return linked to a purchase must be outgoing.', 'Возврат, связанный с закупкой, должен быть исходящим.'],
  ['sale', 'outgoing', 'incoming', 'A return linked to a sale must be incoming.', 'Возврат, связанный с продажей, должен быть входящим.'],
]) test(`a Return linked to a ${originalType} requires the matching direction in English and Russian`, async ({ page }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const name = `Return direction ${originalType} ${Date.now()}`;
  const purchase = await seed(page, undefined, 'purchase', [
    ['party', 'Return supplier'], ['lines/0/name', name], ['lines/0/quantity', '5'], ['lines/0/location', 'Return shelf'],
  ]);
  const accepted = await accept(page, purchase);
  const item = accepted._inventorySnapshot.rows.find(row => row.name === name);
  let original = purchase;
  if (originalType === 'sale') {
    original = await seed(page, undefined, 'sale', [
      ['party', 'Return customer'], ['lines/0/item-id', item['item-id']], ['lines/0/quantity', '2'], ['lines/0/location', 'Return shelf'],
    ]);
    await accept(page, original);
  }
  const returned = await seed(page, undefined, 'return', [
    ['direction', invalidDirection], ['original-operation-id', original], ['lines/0/item-id', item['item-id']],
    ['lines/0/quantity', '1'], ['lines/0/location', 'Return shelf'],
  ]);
  await page.reload(); await openReview(page);
  await expect(page.locator('#inventory-review')).toContainText(englishError);
  await expect(page.locator('#inventory-toolbar .is-submit')).toBeDisabled();
  try {
    await Promise.all([page.waitForNavigation(), page.locator('.language-toggle[value=ru]').click()]);
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    await page.getByRole('button', { name: 'Предпросмотр изменений', exact: true }).click();
    await expect(page.locator('#inventory-review')).toContainText(russianError);
    const submit = page.locator('#inventory-toolbar .is-submit');
    await expect(submit).toBeDisabled();
    await ready(draftGrid(page));
    const editor = await openDraftCell(page, returned, 'direction');
    const saved = saving(page, 'direction');
    await editor.selectOption(validDirection); await editor.press('Enter'); await saved;
    await expect(submit).toBeEnabled();
    await expect(page.locator('#inventory-review')).not.toContainText(russianError);
    const submitted = saving(page, null, 'submit');
    await submit.click(); await submitted;
    await expect.poll(() => signals(page, '_draftIds')).toEqual([]);
    await expect(stockCell(page, name, 'quantity').locator('.inventory-cell-value')).toHaveText('4');
  } finally {
    if (await page.locator('html').getAttribute('lang') === 'ru') {
      await Promise.all([page.waitForNavigation(), page.locator('.language-toggle[value=en]').click()]);
    }
  }
});

test('Copy opens an editable shared draft and preserves filters, selection and other drafts through failures', async ({ page }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const supplier = `Copy supplier ${Date.now()}`;
  const source = await seed(page, undefined, 'purchase', [
    ['occurred-at', '2026-10-20T12:00:00Z'], ['party', supplier], ['amount', '12345678901234567890.1234'], ['currency', 'EUR'],
    ['reason', 'Repeat this purchase'], ['lines/0/name', `${supplier} A`], ['lines/0/quantity', '3'], ['lines/0/location', 'Shelf'],
    ['lines/1/name', `${supplier} B`], ['lines/1/quantity', '2'], ['lines/1/location', 'Shop'],
  ], ['lines']);
  const accepted = await accept(page, source);
  const itemIds = accepted._inventorySnapshot.rows.filter(row => row.name.startsWith(supplier)).map(row => row['item-id']);
  const unrelated = await seed(page, undefined, 'payment', [['party', 'Other pending payment'], ['amount', '7.25'], ['currency', 'USD']]);
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await ready(operationGrid(page));
  await operationGrid(page).getByRole('button', { name: 'Filter Party', exact: true }).click();
  await operationGrid(page).getByRole('combobox', { name: 'Add condition', exact: true }).selectOption('begins');
  const filter = operationGrid(page).getByRole('textbox', { name: 'Value 1', exact: true });
  await filter.fill(supplier); await filter.press('Enter');
  await expect.poll(() => signals(page, '_acceptedFilters')).toMatchObject({ context: [{ type: 'begins', value: supplier }] });
  await operationGrid(page).getByRole('button', { name: 'Close', exact: true }).click();
  await selectDrafts(page, [unrelated]);
  await page.getByRole('button', { name: 'Preview changes', exact: true }).click();
  await expect(draftGrid(page)).toBeHidden();
  const row = operationGrid(page).locator('table.jss_worksheet > tbody > tr').filter({ hasText: supplier }).first();
  await row.getByRole('button', { name: 'Show purchase details', exact: true }).click();
  const details = page.locator('#operations-history .operation-details');
  await expect(details.getByRole('button')).toHaveText(['Correct', 'Cancel', 'Copy']);
  const copy = details.getByRole('button', { name: 'Copy', exact: true });
  const beforeFilters = await signals(page, '_acceptedFilters'), beforeSelection = await signals(page, '_selectedEntries');
  const path = '**/action/tover.stock.web/copy-entry';
  const requests = [];
  page.on('request', request => { if (new URL(request.url()).pathname === '/action/tover.stock.web/copy-entry') requests.push(request.postDataJSON()); });
  let release;
  const held = new Promise(resolve => { release = resolve; });
  await page.route(path, async route => { await held; await route.continue(); });
  await copy.click();
  await expect(page.locator('#inventory-save-status')).toHaveText('Saving');
  await expect(copy).toBeDisabled();
  await copy.evaluate(button => button.click());
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0].targetId).toBe(source);
  release();
  await expect.poll(async () => (await signals(page, '_draftIds')).length).toBe(2);
  await expect.poll(() => signals(page, 'saveStatus')).toBe('Saved');
  await page.unroute(path);
  await expect(draftGrid(page)).toBeVisible(); await ready(draftGrid(page));
  const copied = (await signals(page, '_draftIds')).find(id => id !== unrelated);
  expect(copied).not.toBe(source);
  for (const [field, value] of [['party', supplier], ['amount', '12345678901234567890.1234'], ['currency', 'EUR'],
    ['reason', 'Repeat this purchase'], ['lines/0/quantity', '3'], ['lines/1/quantity', '2']]) {
    await expect(draftCell(page, copied, field).locator('.inventory-cell-value')).toHaveText(value);
  }
  const copiedDate = await draftGrid(page).evaluate((grid, id) => grid.model.rows.flatMap(row => row.cells)
    .find(cell => cell.target?.id === id && cell.target.path === 'occurred-at').value, copied);
  expect(Date.now() - Date.parse(copiedDate)).toBeLessThan(60_000);
  expect(Date.parse(copiedDate)).toBeLessThanOrEqual(Date.now());
  const copiedItems = await draftGrid(page).evaluate((grid, id) => grid.model.rows.flatMap(row => row.cells)
    .filter(cell => cell.target?.id === id && cell.editor?.type === 'goods').map(cell => cell.itemValue), copied);
  expect(copiedItems.sort()).toEqual(itemIds.sort());
  expect(await signals(page, '_acceptedFilters')).toEqual(beforeFilters);
  expect(await signals(page, '_selectedEntries')).toEqual(beforeSelection);
  await expect(draftCell(page, unrelated, 'party')).toContainText('Other pending payment');
  const editor = await openDraftCell(page, copied, 'party'); await expect(editor).toHaveValue(supplier); await editor.press('Escape');
  await page.reload(); await openReview(page); await ready(draftGrid(page));
  await expect(draftCell(page, copied, 'party')).toContainText(supplier);
  const sourceRow = operationGrid(page).locator('table.jss_worksheet > tbody > tr').filter({ hasText: supplier }).first();
  await sourceRow.getByRole('button', { name: 'Show purchase details', exact: true }).click();
  await expect(copy).toBeEnabled();
  await page.route(path, route => route.abort('failed'));
  await copy.click();
  await expect(page.locator('#inventory-save-status')).toContainText('Failed');
  await expect(copy).toBeEnabled();
  expect(requests.length).toBe(2);
  expect(await signals(page, '_draftIds')).toEqual(expect.arrayContaining([unrelated, copied]));
  expect((await signals(page, '_draftIds')).length).toBe(2);
  await page.unroute(path); await copy.click();
  await expect.poll(async () => (await signals(page, '_draftIds')).length).toBe(3);
  await expect.poll(() => signals(page, 'saveStatus')).toBe('Saved');
  expect(requests.length).toBe(3);
  expect(new Set(await signals(page, '_draftIds')).size).toBe(3);
});

test('known-item choices acknowledge IDs and preserve human labels through a stale choice', async ({ page, browser }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const prefix = `Picker ${Date.now()}`;
  const opening = await seed(page, 'opening', undefined, [
    ['lines/0/name', `${prefix} A`], ['lines/0/quantity', '10'], ['lines/0/location', 'Shelf'],
    ['lines/1/name', `${prefix} B`], ['lines/1/quantity', '10'], ['lines/1/location', 'Shelf'],
  ], ['lines']);
  const accepted = await accept(page, opening), rows = accepted._inventorySnapshot.rows;
  const a = rows.find(row => row.name === `${prefix} A`), b = rows.find(row => row.name === `${prefix} B`);
  expect(a).toBeTruthy(); expect(b).toBeTruthy();
  const sale = await seed(page, undefined, 'sale', [
    ['party', 'Customer'], ['lines/0/item-id', a['item-id']], ['lines/0/quantity', '1'], ['lines/0/location', 'Shelf'],
  ]);
  await page.reload(); await openReview(page); await ready(draftGrid(page));
  const cell = draftCell(page, sale, 'lines/0/name');
  const first = await chooseItem(page, sale, b['item-id']);
  expect(first.request().postDataJSON()).toMatchObject({ path: 'lines/0/item-id', value: b['item-id'] });
  await expect(cell.locator('.inventory-cell-value')).toHaveText(`${prefix} B`);
  await expect.poll(() => draftGrid(page).evaluate(grid => grid._attempts.size)).toBe(0);
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage(); await signIn(member, 'member@example.test'); await openReview(member);
    await ready(draftGrid(page)); await cell.click();
    const choice = draftGrid(page).getByRole('combobox', { name: 'Choose existing item', exact: true });
    await expect(choice).toBeVisible(); const originalRevision = await cell.getAttribute('data-revision');
    await chooseItem(member, sale, a['item-id']);
    await expect.poll(() => draftGrid(page).evaluate((grid, id) => grid.model.rows.flatMap(row => row.cells)
      .find(cell => cell.target?.id === id && cell.target.path === 'lines/0/name').itemValue, sale)).toBe(a['item-id']);
    const staleResponse = saving(page, 'lines/0/item-id'); await choice.selectOption(b['item-id']);
    const stale = await staleResponse;
    expect(stale.request().postDataJSON().revision).toBe(Number(originalRevision));
    await expect(cell).toHaveAttribute('data-save-state', 'conflict');
    await expect(cell).toContainText(`${prefix} B`); await expect(cell).toContainText(`Saved: ${prefix} A`);
    await expect(cell).not.toContainText(b['item-id']);
    const retryResponse = saving(page, 'lines/0/item-id');
    await cell.getByRole('button', { name: 'Save my value', exact: true }).click();
    const retry = await retryResponse;
    expect(retry.request().postDataJSON()).toMatchObject({ path: 'lines/0/item-id', value: b['item-id'] });
    expect(retry.request().postDataJSON().revision).toBeGreaterThan(Number(originalRevision));
    await expect(cell.locator('.inventory-cell-value')).toHaveText(`${prefix} B`);
    await expect.poll(() => draftGrid(page).evaluate(grid => grid._attempts.size)).toBe(0);
    await expect(draftCell(member, sale, 'lines/0/name').locator('.inventory-cell-value')).toHaveText(`${prefix} B`);
    await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  } finally { await memberContext.close(); }
});

test('stock acknowledges a new correction with a lower draft revision and preserves keyboard editing', async ({ page }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const prefix = `Revision ${Date.now()}`;
  for (let index = 0; index < 3; index++) {
    const id = await seed(page, 'opening', undefined, [
      ['lines/0/name', `${prefix} ${index}`], ['lines/0/quantity', '5'], ['lines/0/location', 'Shelf'],
    ]);
    await accept(page, id);
  }
  await page.reload(); await ready(stockGrid(page));
  const cell = stockCell(page, `${prefix} 0`, 'quantity');
  const ledgerRevision = Number(await cell.getAttribute('data-revision')); expect(ledgerRevision).toBeGreaterThan(1);
  await cell.click(); const input = gridEditor(stockGrid(page)); await expect(input).toBeVisible(); await input.fill('6');
  const response = saving(page, null, 'edit-stock'); await input.press('Enter');
  const saved = await response, payload = saved.request().postDataJSON();
  expect(payload.ledgerRevision).toBe(ledgerRevision); expect(payload.draftRevision).not.toBeNull(); expect(payload.draftKind).not.toBeNull();
  // A consumed SSE stream can abort after acknowledgment, making its CDP body unavailable.
  expect(saved.status()).toBe(200);
  await expect.poll(() => signals(page, 'saveStatus')).toBe('Saved');
  await expect(cell.locator('.inventory-cell-value')).toHaveText('6');
  await expect.poll(() => stockGrid(page).evaluate(grid => grid._attempts.size)).toBe(0);
  expect(Number(await cell.getAttribute('data-revision'))).toBeLessThan(ledgerRevision);
  await ready(stockGrid(page)); await page.keyboard.press('Enter');
  await expect(input).toBeVisible(); await expect(input).toHaveValue('5');
  expect(await input.locator('xpath=ancestor::td').getAttribute('data-row-id')).toBe(await stockCell(page, `${prefix} 1`, 'quantity').getAttribute('data-row-id'));
  await input.fill('99');
  let writes = 0;
  const count = request => { if (new URL(request.url()).pathname === '/action/tover.stock.web/edit-stock') writes++; };
  page.on('request', count); await input.press('Escape');
  await expect(input).toHaveCount(0); await expect(cell.locator('.inventory-cell-value')).toHaveText('6');
  expect(writes).toBe(0); page.off('request', count);
});

for (const loseResponse of [false, true]) test(`a filtered-out stock edit is acknowledged${loseResponse ? ' after its response is lost' : ''}`, async ({ page }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const name = `Filtered ACK ${Date.now()}`;
  const id = await seed(page, 'opening', undefined, [
    ['lines/0/name', name], ['lines/0/quantity', '5'], ['lines/0/location', 'Shelf'], ['lines/0/article', 'OLD'],
  ]);
  await accept(page, id); await page.reload(); await ready(stockGrid(page));
  await stockGrid(page).getByRole('button', { name: 'Filter Article', exact: true }).click();
  await stockGrid(page).getByRole('combobox', { name: 'Add condition', exact: true }).selectOption('begins');
  const pattern = stockGrid(page).getByRole('textbox', { name: 'Value 1', exact: true });
  await pattern.fill('OLD'); await pattern.press('Enter');
  await expect.poll(async () => await signals(page, '_acceptedFilters')).toMatchObject({ article: [{ type: 'begins', value: 'OLD' }] });
  await stockGrid(page).getByRole('button', { name: 'Close', exact: true }).click();
  await stockCell(page, name, 'article').click();
  const input = gridEditor(stockGrid(page)); await input.fill('NEW');
  let committed;
  if (loseResponse) await page.route('**/action/tover.stock.web/edit-stock', async route => {
    const result = await route.fetch(); committed = actionSignals(await result.text());
    await route.abort('failed');
  });
  const response = loseResponse ? null : saving(page, null, 'edit-stock'); await input.press('Enter');
  if (loseResponse) await expect.poll(() => committed?.saveStatus).toBe('Saved');
  else {
    expect((await response).status()).toBe(200);
    await expect.poll(() => signals(page, 'saveStatus')).toBe('Saved');
  }
  await expect(stockCell(page, name, 'article')).toHaveCount(0);
  await expect.poll(() => stockGrid(page).evaluate(grid => grid._attempts.size)).toBe(0);
  await expect(stockGrid(page).locator('.inventory-grid-orphans > div')).toHaveCount(0);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await page.getByRole('button', { name: 'Reset all', exact: true }).click();
  await expect(stockCell(page, name, 'article').locator('.inventory-cell-value')).toHaveText('NEW');
});

test('local validation failure blocks Submit and restoring the saved value resolves it without saving', async ({ page }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const id = await seed(page, undefined, 'purchase', [
    ['party', 'Validation supplier'], ['amount', '12.00'], ['currency', 'EUR'],
    ['lines/0/name', `Validation goods ${Date.now()}`], ['lines/0/quantity', '2'], ['lines/0/location', 'Validation shelf'],
  ]);
  await page.reload(); await openReview(page); await ready(draftGrid(page));
  const submit = page.getByRole('button', { name: 'Submit selected', exact: true });
  await expect(submit).toBeEnabled();
  const cell = draftCell(page, id, 'amount'), revision = await cell.getAttribute('data-revision');
  const requests = [];
  page.on('request', request => { if (new URL(request.url()).pathname === '/action/tover.stock.web/save-field') requests.push(request); });
  const input = await openDraftCell(page, id, 'amount');
  await input.fill('12.'); await draftGrid(page).focus();
  await expect(cell).toHaveAttribute('data-save-state', 'failed');
  await expect(submit).toBeDisabled();
  await expect(page.locator('#inventory-save-status')).toHaveText('Failed');
  await expect(cell).toHaveAttribute('data-revision', revision);
  await cell.locator('.inventory-cell-value').click(); await gridEditor(draftGrid(page)).fill('12.00'); await gridEditor(draftGrid(page)).press('Enter');
  await expect(cell).not.toHaveAttribute('data-save-state');
  await expect(submit).toBeEnabled();
  await expect(cell).toHaveAttribute('data-revision', revision);
  expect(requests).toEqual([]);
  await cell.locator('.inventory-cell-value').click(); await gridEditor(draftGrid(page)).fill('12.'); await draftGrid(page).focus();
  await expect(submit).toBeDisabled();
  const party = await openDraftCell(page, id, 'party');
  await party.fill('Updated validation supplier');
  const saved = saving(page, 'party'); await party.press('Enter'); await saved;
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect(submit).toBeDisabled();
  await cell.getByRole('button', { name: 'Use saved value', exact: true }).click();
  await expect(submit).toBeEnabled();
});

test('shared pending purchases remain selectable references with labels after refresh', async ({ page }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const supplier = `Pending reference ${Date.now()}`;
  const purchase = await seed(page, undefined, 'purchase', [['party', supplier]]);
  const payment = await seed(page, undefined, 'payment', [['party', supplier]]);
  await page.reload(); await openReview(page); await ready(draftGrid(page));
  const editor = await openDraftCell(page, payment, 'original-operation-id');
  await editor.fill(supplier);
  const choice = draftGrid(page).getByRole('combobox', { name: 'Choose operation', exact: true });
  await expect(choice.locator(`option[value="${purchase}"]`)).toHaveCount(1);
  const saved = saving(page, 'original-operation-id');
  await choice.selectOption(purchase); await saved;
  await expect.poll(() => draftGrid(page).evaluate(grid => grid._attempts.size)).toBe(0);
  await page.reload(); await openReview(page); await ready(draftGrid(page));
  await expect(draftCell(page, payment, 'original-operation-id').locator('.inventory-cell-value')).toContainText(supplier);
});

test('Return and Payment references query real purchase choices and survive refresh', async ({ page }) => {
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const supplier = `Reference supplier ${Date.now()}`;
  const purchase = await seed(page, undefined, 'purchase', [
    ['party', supplier], ['lines/0/name', `${supplier} goods`], ['lines/0/quantity', '10'], ['lines/0/location', 'Reference shelf'],
  ]);
  await accept(page, purchase);
  for (const type of ['return', 'payment']) {
    const id = await seed(page, undefined, type, [['party', supplier], ['direction', 'outgoing']]);
    await page.reload(); await openReview(page); await ready(draftGrid(page));
    const cell = draftCell(page, id, 'original-operation-id');
    const editor = await openDraftCell(page, id, 'original-operation-id');
    await expect(editor).toHaveValue('');
    const lookup = page.waitForRequest(request => {
      if (new URL(request.url()).pathname !== '/view/tover.stock.web/reference-options') return false;
      return request.postDataJSON()?.text === supplier;
    });
    await editor.fill(supplier); await lookup;
    const choice = draftGrid(page).getByRole('combobox', { name: 'Choose operation', exact: true });
    await expect(choice.locator(`option[value="${purchase}"]`)).toHaveCount(1);
    const saved = saving(page, 'original-operation-id');
    await choice.selectOption(purchase);
    expect((await saved).request().postDataJSON()).toMatchObject({ id, path: 'original-operation-id', value: purchase });
    await expect(cell.locator('.inventory-cell-value')).toContainText(supplier);
    await expect.poll(() => draftGrid(page).evaluate(grid => grid._attempts.size)).toBe(0);
    await page.reload(); await openReview(page); await ready(draftGrid(page));
    await expect(draftCell(page, id, 'original-operation-id').locator('.inventory-cell-value')).toContainText(supplier);
    expect(await draftGrid(page).evaluate((grid, id) => grid.model.rows.flatMap(row => row.cells)
      .find(cell => cell.target?.id === id && cell.target.path === 'original-operation-id').value, id)).toBe(purchase);
  }
});

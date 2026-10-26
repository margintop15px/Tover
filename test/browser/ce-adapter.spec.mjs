import { expect, test } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';
import { draftCell, draftGrid, gridEditor, openDraftCell, openReview, signals, stockCell, stockGrid } from './grid.fixture.mjs';

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

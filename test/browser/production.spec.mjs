import { expect, test } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';
import {
  acceptDrafts, addStockRow, chooseSmart, draftCell, draftGrid, draftIds, editDraftCell,
  editStockCell, gridEditor, gridHeader, openDraftCell, openReview, operationGrid, revertPending,
  selectDrafts, smartField, stockGrid, stockRow,
} from './grid.fixture.mjs';

const browserErrors = new WeakMap();
test.beforeEach(async ({ page }) => {
  const errors = [];
  browserErrors.set(page, errors);
  page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await revertPending(page);
});
test.afterEach(async ({ page }) => expect(browserErrors.get(page)).toEqual([]));

const operationRow = (page, text) => operationGrid(page).locator('table.jss_worksheet > tbody > tr:not([data-row-id$="/details"])').filter({ hasText: text });
const chooseTheme = async (page, dark) => {
  const button = page.getByRole('button', { name: dark ? 'Switch to dark mode' : 'Switch to light mode', exact: true });
  if (await button.isVisible()) await button.click();
};

async function newSmartDraft(page, type, fields) {
  const before = await draftIds(page);
  await page.locator('stock-entry input').fill('ass');
  await chooseSmart(page, type);
  for (const [field, value] of fields) await smartField(page, field, value);
  await chooseSmart(page, 'Done');
  await expect.poll(async () => (await draftIds(page)).length).toBe(before.length + 1);
  await openReview(page);
  return (await draftIds(page)).find(id => !before.includes(id));
}

test('inventory pages have real shared drafts and server operations without demo controls', async ({ page }) => {
  await expect(page.getByRole('combobox', { name: 'Search or add', exact: true })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'New operation type' })).toHaveCount(0);
  await expect(page.getByText('Demo data', { exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Accepted operations', exact: true })).toBeVisible();
  await expect.poll(() => operationGrid(page).evaluate(grid => grid.model.columns.map(column => column.label).filter(Boolean)))
    .toEqual(expect.arrayContaining(['Operation', 'Date', 'Reason']));
  await expect(page.locator('revo-grid')).toHaveCount(0);
  await expect(page.locator('#shared-draft')).toBeHidden();
  await expect(page.getByText('Production timeline', { exact: true })).toHaveCount(0);
  const pending = await draftIds(page);
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  await expect(page.getByRole('option').filter({ has: page.locator('.stock-entry-option-field', { hasText: /^Purchase$/ }) })).toBeVisible();
  expect(await draftIds(page)).toEqual(pending);
});

test('clean stock keeps the compact smart input, sortable spreadsheet and no empty draft panels', async ({ page }) => {
  await expect(page.getByRole('combobox', { name: 'Search or add', exact: true })).toBeVisible();
  await expect(page.locator('stock-entry .stock-entry-line button:visible')).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: /Sort/ })).toHaveCount(0);
  await expect(page.getByText(/Accepted quantities/)).toHaveCount(0);
  await expect(page.locator('#shared-draft')).toBeHidden();
  await expect(page.locator('.inventory-draft-editor,.inventory-draft')).toHaveCount(0);
  await expect(page.locator('#inventory-review')).toBeEmpty();
  expect(await page.locator('.stock-sheet').evaluate(element => element.nextElementSibling)).toBeNull();
  await expect.poll(() => stockGrid(page).evaluate(grid => !!grid.worksheet)).toBe(true);
  const geometry = await stockGrid(page).evaluate(grid => ({
    header: grid.querySelector('thead tr').getBoundingClientRect().height,
    font: getComputedStyle(grid.querySelector('table')).fontSize,
    rowHeader: grid.querySelector('tbody tr td')?.getBoundingClientRect().width,
  }));
  expect(geometry.header).toBeGreaterThanOrEqual(32);
  expect(geometry.header).toBeLessThanOrEqual(36);
  expect(geometry.font).toBe('13px');
});

test('Stock Add row edits a durable green spreadsheet row and toolbar previews above the table', async ({ page }) => {
  const revert = page.getByRole('button', { name: 'Revert all changes', exact: true });
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  const row = stockGrid(page).locator('tbody tr.sheet-row-added');
  await expect(row).toBeVisible();
  await expect(revert).toBeDisabled();
  await expect(row.getByRole('button', { name: /^History for / })).toHaveCount(0);
  const name = `Inline opening ${Date.now()}`;
  let releaseFirstSave, firstSaveStarted;
  const blocked = new Promise(resolve => { releaseFirstSave = resolve; });
  const started = new Promise(resolve => { firstSaveStarted = resolve; });
  const editedFields = [];
  await page.route('**/action/tover.stock.web/edit-stock', async route => {
    editedFields.push(route.request().postDataJSON().field);
    if (editedFields.length === 1) { firstSaveStarted(); await blocked; }
    await route.continue();
  });
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect.poll(() => stockGrid(page).evaluate(grid => grid.busy)).toBe(false);
  await row.locator('td[data-column-key="name"]').click();
  await gridEditor(stockGrid(page)).fill(name);
  const saved = page.waitForResponse('**/action/tover.stock.web/edit-stock');
  await gridEditor(stockGrid(page)).press('Enter');
  try {
    await started;
    await expect.poll(() => stockGrid(page).evaluate(grid => grid.busy)).toBe(true);
    await row.locator('td[data-column-key="quantity"]').click();
    await expect(gridEditor(stockGrid(page))).toHaveCount(0);
  } finally { releaseFirstSave(); }
  expect((await saved).status()).toBe(200);
  await expect(stockRow(page, name)).toBeVisible();
  await editStockCell(page, stockRow(page, name), 'quantity', '5');
  await editStockCell(page, stockRow(page, name), 'location', 'Inline shelf');
  expect(editedFields).toEqual(['name', 'quantity', 'location']);
  await expect(page.getByRole('img', { name: '1 entries added', exact: true })).toBeVisible();
  await expect(page.locator('#shared-draft')).toBeHidden();
  await expect(revert).toBeDisabled();
  await page.reload();
  await expect(stockRow(page, name)).toHaveClass(/sheet-row-added/);
  await expect(stockRow(page, name).locator('td[data-column-key="quantity"] .inventory-cell-value')).toHaveText('5');
  await expect(revert).toBeDisabled();
  await openReview(page);
  const preview = page.getByRole('region', { name: 'Selected changes review', exact: true });
  const toggle = page.getByRole('button', { name: 'Preview changes', exact: true });
  await expect(preview).toContainText(name);
  await expect(revert).toBeEnabled();
  await expect(toggle).toHaveAttribute('aria-pressed', 'true');
  await expect(preview).not.toContainText(/Change preview|complete entries|ledger revision|Saved\s*·\s*revision/i);
  await expect(page.getByRole('button', { name: 'Close preview', exact: true })).toHaveCount(0);
  await toggle.click();
  await expect(preview).toBeHidden();
  await expect(draftGrid(page)).toBeHidden();
  await expect(toggle).toHaveAttribute('aria-pressed', 'false');
  await expect(revert).toBeDisabled();
  await toggle.click();
  await expect(preview).toContainText(name);
  await expect(revert).toBeEnabled();
  const reviewBounds = await page.locator('#inventory-review').boundingBox();
  const tableBounds = await stockGrid(page).boundingBox();
  expect(reviewBounds.y + reviewBounds.height).toBeLessThanOrEqual(tableBounds.y);
  await revertPending(page);
  await expect(stockRow(page, name)).toHaveCount(0);
});

test('smart fields filter table values on the server and removing a chip restores rows', async ({ page }) => {
  const suffix = Date.now();
  const first = `Filter first ${suffix}`, second = `Filter second ${suffix}`, location = `Aisle ${suffix}`;
  await addStockRow(page, first, location);
  await addStockRow(page, second);
  const input = page.locator('stock-entry input');
  await input.fill('loc'); await chooseSmart(page, 'Location');
  await expect(input).toHaveAttribute('aria-label', 'Location filter');
  await input.fill(location); await input.press('Enter');
  const remove = page.getByRole('button', { name: 'Remove Location filter 1', exact: true });
  await expect(remove).toBeVisible();
  await expect(page.locator('stock-entry .stock-entry-chips')).toBeEmpty();
  await expect(page.getByRole('group', { name: 'Location filters', exact: true })).toContainText('begins with');
  await expect(stockRow(page, first)).toBeVisible();
  await expect(stockRow(page, second)).toHaveCount(0);
  await remove.click();
  await expect(stockRow(page, second)).toBeVisible();
});

test('ass suggests operations and smart fields save a real payment for review and Submit', async ({ page }) => {
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(page).toHaveURL(/\/operations$/);
  const id = await newSmartDraft(page, 'Payment', [['Party', 'Smart payment party'], ['Amount', '19.45'], ['Currency', 'EUR'], ['Reason', 'Smart payment test']]);
  await expect(draftCell(page, id, 'party')).toContainText('Smart payment party');
  await expect(draftCell(page, id, 'amount')).toHaveText('19.45');
  await page.reload(); await openReview(page);
  await expect(draftCell(page, id, 'currency')).toHaveText('EUR');
  await selectDrafts(page, [id]);
  await expect(page.locator('#inventory-review')).toContainText('No stock changes');
  await acceptDrafts(page, [id]);
  await expect(page).toHaveURL(/\/operations$/);
  const operation = operationRow(page, 'Smart payment party');
  await operation.getByRole('button', { name: 'Show payment details', exact: true }).click();
  const details = page.locator('.operation-details');
  await expect(operation).toContainText('19.45');
  await expect(details).toContainText('Smart payment test');
  await expect(details).toContainText('Submitted by');
  await expect(details.locator('table,details,h3')).toHaveCount(0);
  await expect(details).not.toContainText(/Revision|19\.45|Smart payment party/);
  await expect(page.getByText('Lineage', { exact: true })).toHaveCount(0);
  const sorted = page.waitForRequest(request => new URL(request.url()).pathname === '/view/tover.stock.web/stock');
  await gridHeader(operationGrid(page), 'occurred-at').getByRole('button', { name: 'Sort by Date', exact: true }).click();
  expect((await sorted).postDataJSON().view).toBe('operations');
  await details.getByRole('button', { name: 'Cancel', exact: true }).click();
  await openReview(page);
  const cancellation = (await draftIds(page))[0];
  await editDraftCell(page, cancellation, 'reason', 'Cancel browser payment');
  await acceptDrafts(page, [cancellation]);
  await expect(operation).toHaveClass(/inventory-cancelled-row/);
  const show = operation.getByRole('button', { name: /Show payment.*details/ });
  if (await show.count()) await show.click();
  await expect(page.locator('.operation-details')).toContainText('Cancelled');
  await expect(operation).toContainText('19.45');
  await expect(page.locator('.operation-details')).toContainText('Cancel browser payment');
});

test('sortable spreadsheet headers request server ordering and display the saved direction', async ({ page }) => {
  const header = gridHeader(stockGrid(page), 'quantity');
  const request = page.waitForRequest(request => new URL(request.url()).pathname === '/view/tover.stock.web/stock');
  await header.getByRole('button', { name: 'Sort by Quantity', exact: true }).click();
  const body = (await request).postDataJSON();
  expect(body.sort).toBe('quantity'); expect(body.direction).toBe('asc');
  await expect(header).toHaveAttribute('aria-sort', 'ascending');
  const descending = page.waitForRequest(request => new URL(request.url()).pathname === '/view/tover.stock.web/stock');
  await header.getByRole('button', { name: 'Sort by Quantity', exact: true }).click();
  expect((await descending).postDataJSON().direction).toBe('desc');
  await expect(header).toHaveAttribute('aria-sort', 'descending');
});

test('shared drafts survive reload and another member can review and submit selected entries', async ({ page, browser }) => {
  const name = `Durable stock ${Date.now()}`;
  const id = await addStockRow(page, name);
  const unselected = await addStockRow(page, `Unselected ${Date.now()}`);
  await page.reload(); await openReview(page);
  await expect(draftCell(page, id, 'lines/0/name')).toContainText(name);
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    member.on('pageerror', error => browserErrors.get(page).push(error.message));
    await signIn(member, 'member@example.test'); await openReview(member);
    await expect(draftCell(member, id, 'lines/0/name')).toContainText(name);
    const before = await draftCell(member, unselected, 'lines/0/name').getAttribute('data-revision');
    await acceptDrafts(member, [id]);
    await expect(stockRow(page, name)).toBeVisible();
    await expect(stockRow(member, name)).toBeVisible();
    await openReview(member);
    await expect(draftCell(member, unselected, 'lines/0/name')).toHaveAttribute('data-revision', before);
    expect(await draftIds(member)).toEqual([unselected]);
  } finally { await memberContext.close(); }
});
test('authentication cookie is opaque and protected pages require sign-in', async ({ page, browser }) => {
  const cookies = await page.context().cookies();
  const session = cookies.find(cookie => cookie.name === 'tover-session');
  expect(session).toBeDefined();
  expect(session.httpOnly).toBe(true);
  expect(session.sameSite).toBe('Lax');
  expect(session.value).not.toContain('access-token');
  expect(session.value).not.toContain('refresh-token');
  const anonymous = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const outsider = await anonymous.newPage();
    await outsider.goto('/');
    await expect(outsider).toHaveURL(/\/auth\/login$/);
    await expect(outsider.locator('#stock-grid')).toHaveCount(0);
  } finally {
    await anonymous.close();
  }
});

test('workspace switching refreshes other tabs, hides workspace drafts and rejects stale requests', async ({ page, context }) => {
  const stale = await pageContext(page);
  await addStockRow(page, 'Workspace-specific draft');
  const other = await context.newPage();
  await other.goto('/workspace');
  const row = other.locator('.management-table tbody tr').filter({ hasText: 'Other workspace' });
  await row.getByRole('button', { name: 'Open', exact: true }).click();
  await expect(other.locator('#stock-grid')).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'Goods', exact: true })).toHaveCount(0);
  await expect.poll(async () => (await pageContext(page)).version).not.toBe(stale.version);
  const staleResponse = await context.request.post('/action/tover.stock.web/edit-stock', {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true',
      'x-csrf-token': stale.csrf, 'x-tover-context': stale.version },
    data: { rowId: 'missing', quantity: '9', reason: 'Old page', query: '' },
  });
  expect(staleResponse.status()).toBe(409);
  await other.close();
});

test('logout invalidates the session and refreshes sibling tabs', async ({ page, context }) => {
  const other = await context.newPage();
  await other.goto('/');
  await expect(other.locator('#stock-grid')).toBeVisible();
  await page.goto('/workspace');
  await page.locator('form[action="/action/tover.auth.web/sign-out"]').getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page).toHaveURL(/\/auth\/login$/);
  await expect(other).toHaveURL(/\/auth\/login$/);
  const status = await context.request.post('/action/tover.stock.web/edit-stock', {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true' },
    data: { rowId: 'missing', quantity: '9', reason: 'After logout', query: '' },
  });
  expect([401, 403]).toContain(status.status());
  await other.close();
});

test('Datastar writes reject missing CSRF tokens and foreign origins', async ({ page, context }) => {
  const { csrf, version } = await pageContext(page);
  const data = { rowId: 'missing', quantity: '9', reason: 'Rejected request', query: '' };
  const origin = new URL(page.url()).origin;
  const missing = await context.request.post('/action/tover.stock.web/edit-stock', {
    headers: { origin, 'datastar-request': 'true', 'x-tover-context': version }, data,
  });
  expect(missing.status()).toBe(403);
  const foreign = await context.request.post('/action/tover.stock.web/edit-stock', {
    headers: { origin: 'http://attacker.invalid', 'datastar-request': 'true',
      'x-csrf-token': csrf, 'x-tover-context': version }, data,
  });
  expect(foreign.status()).toBe(403);
});

test('email links require an explicit confirmation and invalid links show an error', async ({ page }) => {
  const verifications = [];
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/action/tover.auth.web/verify-email') verifications.push(request);
  });
  await page.goto('/auth/verify?token_hash=expired-link&type=recovery');
  await expect(page.getByRole('heading', { name: 'Confirm email link' })).toBeVisible();
  expect(verifications).toHaveLength(0);
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  expect(verifications).toHaveLength(1);
});


test('stock cell corrections persist and item history is fetched when opened', async ({ page }) => {
  const name = `Counted stock ${Date.now()}`;
  const opening = await addStockRow(page, name);
  await acceptDrafts(page, [opening]);
  const row = stockRow(page, name);
  await expect(row).not.toHaveClass(/sheet-row-added/);
  await editStockCell(page, row, 'quantity', '7');
  const correction = (await draftIds(page))[0];
  await openReview(page);
  await expect(draftGrid(page)).toContainText('Correction');
  await editDraftCell(page, correction, 'reason', 'Shelf count');
  await acceptDrafts(page, [correction]);
  await expect(row).not.toHaveClass(/sheet-row-modified/);
  await expect(row.locator('td[data-column-key="quantity"]')).toHaveText('7');
  await editStockCell(page, row, 'article', 'COUNT-ARTICLE');
  await openReview(page);
  await editDraftCell(page, (await draftIds(page))[0], 'reason', 'Article correction');
  const resulting = page.locator('#inventory-review inventory-grid');
  await expect(resulting).toContainText('COUNT-ARTICLE');
  const proposedQuantity = resulting.locator('tbody td[data-column-key="quantity"]');
  await expect(proposedQuantity).toHaveText('7');
  await expect(proposedQuantity).not.toHaveClass(/changed/);
  await revertPending(page);
  const history = page.waitForRequest('**/action/tover.stock.web/open-history');
  await row.getByRole('button', { name: `History for ${name}` }).click();
  await history;
  const panel = page.locator('#history-panel');
  await expect(panel).toContainText('0 → 3');
  await expect(panel).toContainText('3 → 7');
  await expect(panel).toContainText('owner@example.test');
  await expect(panel).not.toContainText('00000000-0000-4000-8000-000000000001');
  const records = panel.locator('inventory-grid');
  await expect(records.locator('table.jss_worksheet')).toHaveCount(1);
  await expect.poll(() => records.evaluate(grid => grid.model.rows.length)).toBe(2);
  for (const label of ['Occurred at', 'Recorded at', 'Record', 'Status', 'Actor', 'Reason', 'Quantity / location']) {
    await expect(records.locator('thead')).toContainText(label);
  }
  await expect(panel).not.toContainText(/Snapshot · accepted ledger revision|Pooled quantities|Include parent items|All item revisions and cancellations/);
  await expect(panel).not.toContainText(/\d{4}-\d{2}-\d{2}T\d{2}:/);
  for (const name of ['Refresh history', 'Close history']) {
    const button = panel.getByRole('button', { name, exact: true });
    await expect(button).toBeVisible();
    await expect(button.locator('svg')).toBeVisible();
  }
  await expect(panel.locator('button.lineage-bar')).toHaveCount(0);
  await panel.getByRole('tab', { name: 'Lineage', exact: true }).click();
  await expect(panel.locator('#item-history-grid')).toHaveCount(0);
  await expect(panel.locator('button.lineage-bar').first()).toBeVisible();
  await panel.locator('button.lineage-bar').first().click();
  await expect(panel).toContainText('Initial count');
  await chooseTheme(page, true);
  await page.screenshot({ path: '/tmp/tover-combined-item-history.png', fullPage: true });
  await page.reload();
  await expect(stockRow(page, name).locator('td[data-column-key="quantity"]')).toHaveText('7');
});

test('a shared update preserves unfinished input and stale edits show a conflict', async ({ page, browser }) => {
  const id = await addStockRow(page, `Conflict stock ${Date.now()}`);
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    await signIn(member, 'member@example.test'); await openReview(member);
    await draftCell(page, id, 'lines/0/name').click();
    const original = gridEditor(draftGrid(page));
    await original.fill('Unfinished local name');
    await editDraftCell(member, id, 'lines/0/name', 'Saved by member');
    await expect(original).toHaveValue('Unfinished local name');
    await original.press('Enter');
    await expect(page.locator('#inventory-notice')).toContainText('changed');
    await expect(page.locator('#inventory-notice')).toContainText('Saved by member');
    const conflict = draftCell(page, id, 'lines/0/name');
    await expect(conflict).toContainText('Unfinished local name');
    await expect(conflict).toContainText('Saved by member');
    await expect(conflict.getByRole('button', { name: 'Use saved value', exact: true })).toBeVisible();
    await page.reload(); await openReview(page);
    await expect(draftCell(page, id, 'lines/0/name')).toHaveText('Saved by member');
  } finally { await memberContext.close(); }
});

test('changing a completed pending operation type replaces role fields and submits a real payment', async ({ page }) => {
  const id = await newSmartDraft(page, 'Purchase', [['Party', 'Browser purchase party'], ['Goods', 'Browser purchase goods'], ['Quantity', '1'], ['Location', 'Main']]);
  await chooseTheme(page, true);
  await page.screenshot({ path: '/tmp/tover-purchase-form-desktop.png', fullPage: true });
  await expect(draftGrid(page).getByRole('button', { name: 'Add item', exact: true })).toHaveText('+');
  await expect(draftGrid(page).getByRole('button', { name: 'Add arrival', exact: true })).toHaveCount(0);
  await openDraftCell(page, id, 'occurred-at');
  await expect(gridEditor(draftGrid(page))).toHaveAttribute('type', 'datetime-local');
  await gridEditor(draftGrid(page)).press('Escape');
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await draftGrid(page).locator('.jss_content').evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/tover-purchase-form-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
  await editDraftCell(page, id, 'type', 'production');
  await expect(draftGrid(page)).not.toContainText('Inputs consumed');
  await expect(draftGrid(page)).not.toContainText('Outputs created');
  await expect(draftCell(page, id, 'inputs/0/name')).toHaveClass(/inventory-production-input/);
  await expect(draftCell(page, id, 'outputs/0/name')).toHaveClass(/inventory-production-output/);
  await expect(draftCell(page, id, 'receipts/0/occurred-at')).toHaveCount(0);
  await page.screenshot({ path: '/tmp/tover-production-form-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/tover-production-form-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
  await editDraftCell(page, id, 'type', 'payment');
  await expect(draftCell(page, id, 'amount')).toBeVisible();
  await expect(draftCell(page, id, 'lines/0/quantity')).toHaveCount(0);
  for (const [path, value] of [['party', 'Browser payment party'], ['amount', '12.34'], ['currency', 'EUR'], ['reason', 'Payment test']]) await editDraftCell(page, id, path, value);
  await acceptDrafts(page, [id]);
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(operationRow(page, 'Browser payment party')).toBeVisible();
});

test.describe('local date presentation', () => {
  test.use({ timezoneId: 'Europe/Madrid', locale: 'en-GB' });
  test('smart, draft, review and operation dates stay readable while edited instants persist in UTC', async ({ page }) => {
    const party = `Local date payment ${Date.now()}`;
    const input = page.locator('stock-entry input');
    await input.fill('add'); await chooseSmart(page, 'Payment');
    for (const [label, value] of [['Party', party], ['Amount', '8.75'], ['Currency', 'EUR']]) await smartField(page, label, value);
    await page.getByRole('button', { name: /^Edit Occurred at:/ }).click();
    await expect(input).toHaveAttribute('type', 'datetime-local');
    await input.fill('2026-07-01T12:30'); await input.press('Enter');
    await expect.poll(() => page.locator('stock-entry').evaluate(element => {
      const date = element.model?.chips?.find(chip => chip.key === 'occurred-at')?.value;
      return date && new Date(date).toISOString();
    })).toBe('2026-07-01T10:30:00.000Z');
    await expect(page.getByRole('button', { name: /^Edit Occurred at:/ })).toContainText('12:30');
    await chooseSmart(page, 'Done'); await openReview(page);
    const id = (await draftIds(page))[0];
    const date = draftCell(page, id, 'occurred-at');
    await date.click();
    const occurred = gridEditor(draftGrid(page));
    await expect(occurred).toHaveAttribute('type', 'datetime-local');
    await expect(occurred).toHaveValue('2026-07-01T12:30');
    const savedDate = page.waitForRequest(request => new URL(request.url()).pathname === '/action/tover.stock.web/save-field' && request.postDataJSON().path === 'occurred-at');
    await occurred.fill('2026-07-02T14:45'); await occurred.press('Enter');
    expect((await savedDate).postDataJSON().value).toBe('2026-07-02T12:45:00.000Z');
    await expect(date).toHaveText('2 Jul 2026, 14:45');
    await expect(draftGrid(page)).not.toContainText(/\d{4}-\d{2}-\d{2}T\d{2}:/);
    await page.screenshot({ path: '/tmp/tover-local-date-preview.png' });
    await acceptDrafts(page, [id]);
    await page.getByRole('link', { name: 'Operations', exact: true }).click();
    await expect(page).toHaveURL(/\/operations$/);
    await input.fill('Locations / party'); await chooseSmart(page, 'Locations / party');
    await input.fill(party); await input.press('Enter');
    const operation = operationRow(page, party);
    await expect(operation.locator('td[data-column-key="occurred-at"]')).toHaveText('2 Jul 2026, 14:45');
    await operation.getByRole('button', { name: 'Show payment details', exact: true }).click();
    const details = page.locator('.operation-details');
    await expect(details.locator('.operation-meta-field').filter({ hasText: 'Recorded at' }).locator('dd'))
      .toHaveText(/\d{1,2} [A-Za-z]{3} \d{4}, \d{2}:\d{2}/);
    await expect(details).not.toContainText(/\d{4}-\d{2}-\d{2}T\d{2}:/);
  });
});

test('returning online reloads accepted changes missed by failed snapshot requests', async ({ page, browser }) => {
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    member.on('pageerror', error => browserErrors.get(page).push(error.message));
    let streams = 0;
    member.on('request', request => { if (new URL(request.url()).pathname === '/_bel/realtime/stream') streams += 1; });
    await signIn(member, 'member@example.test');
    await expect.poll(() => streams).toBeGreaterThan(0);
    await memberContext.setOffline(true);
    const name = `Reconnected stock ${Date.now()}`;
    const id = await addStockRow(page, name);
    await acceptDrafts(page, [id]);
    await expect(stockRow(page, name)).toBeVisible();
    await memberContext.setOffline(false);
    await expect(stockRow(member, name)).toBeVisible({ timeout: 15000 });
  } finally { await memberContext.close(); }
});

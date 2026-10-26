import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';
import {
  acceptDrafts, addStockRow, draftCell, draftGrid, draftIds, gridEditor, gridHeader,
  openReview, revertPending, selectDrafts, signals, stockGrid, stockRow,
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

test('a committed Submit waits for its receipt before applying notifications or reopening review', async ({ page }) => {
  const acceptedName = `Receipt accepted ${Date.now()}`;
  const pendingName = `Receipt pending ${Date.now()}`;
  const accepted = await addStockRow(page, acceptedName);
  const pending = await addStockRow(page, pendingName);
  await selectDrafts(page, [accepted]);
  await expect.poll(() => signals(page, 'inventoryChanged')).toBe('');
  const pendingRevision = await draftCell(page, pending, 'lines/0/name').getAttribute('data-revision');
  let releaseResponse, committed;
  const release = new Promise(resolve => { releaseResponse = resolve; });
  const serverCommitted = new Promise(resolve => { committed = resolve; });
  const previewRequests = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/action/tover.stock.web/preview') previewRequests.push(request);
  });
  await page.route('**/action/tover.stock.web/submit', async route => {
    const response = await route.fetch();
    committed(response.status());
    await release;
    await route.fulfill({ response });
  });
  const receipt = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/submit');
  await page.getByRole('button', { name: 'Submit selected', exact: true }).click();
  try {
    expect(await serverCommitted).toBe(200);
    await expect(page.locator('#inventory-save-status')).toHaveText('Saving');
    await expect.poll(() => signals(page, 'inventoryChanged')).not.toBe('');
    await expect(page.getByRole('button', { name: 'Submit selected', exact: true })).toBeDisabled();
    expect(previewRequests).toHaveLength(0);
    expect(await signals(page, '_selectedEntries')).toEqual([accepted]);
  } finally { releaseResponse(); }
  expect((await receipt).status()).toBe(200);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect.poll(() => signals(page, 'inventoryChanged')).toBe('');
  await expect.poll(() => draftIds(page)).toEqual([pending]);
  await expect(page.locator('#inventory-review')).toBeEmpty();
  await expect(draftGrid(page)).toBeHidden();
  expect(await signals(page, '_draftPreview')).toBe(false);
  expect(await signals(page, '_selectedEntries')).toEqual([]);
  expect(previewRequests).toHaveLength(0);
  await expect(draftCell(page, pending, 'lines/0/name')).toHaveText(pendingName);
  await expect(draftCell(page, pending, 'lines/0/name')).toHaveAttribute('data-revision', pendingRevision);
  await expect(draftGrid(page).locator('input[data-review-entry]')).not.toBeChecked();
  await expect(stockRow(page, acceptedName)).toBeVisible();
});

test('an active header filter keeps focus after its server acknowledgment and member broadcasts', async ({ page, browser }) => {
  const prefix = `Focus ${Date.now()}`;
  const first = await addStockRow(page, `${prefix} first`);
  const second = await addStockRow(page, `Unrelated ${Date.now()}`);
  await acceptDrafts(page, [first, second]);
  await gridHeader(stockGrid(page), 'name').getByRole('button', { name: 'Filter Goods', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Filter Goods', exact: true });
  await dialog.getByLabel('Add condition', { exact: true }).selectOption('begins');
  const input = dialog.getByLabel('Value 1', { exact: true });
  await expect(input).toBeEnabled();
  await input.fill(prefix);
  const acknowledgment = page.waitForResponse(response => {
    if (new URL(response.url()).pathname !== '/view/tover.stock.web/stock') return false;
    return response.request().postDataJSON().filters?.name?.[0]?.value === prefix;
  });
  await input.press('Enter');
  expect((await acknowledgment).status()).toBe(200);
  await expect(stockRow(page, `Unrelated`)).toHaveCount(0);
  await expect(input).toBeFocused();
  await expect(input).toHaveValue(prefix);

  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    member.on('pageerror', error => browserErrors.get(page).push(error.message));
    await signIn(member, 'member@example.test');
    await addStockRow(member, `${prefix} shared`);
    await expect(stockRow(page, `${prefix} shared`)).toBeVisible();
    await expect(input).toBeFocused();
    await expect(input).toHaveValue(prefix);
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  } finally { await memberContext.close(); }
});

test('discarding the last shared entry preserves another member’s unfinished cell value until dismissed', async ({ page, browser }) => {
  const name = `Discarded draft ${Date.now()}`;
  const id = await addStockRow(page, name);
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    member.on('pageerror', error => browserErrors.get(page).push(error.message));
    await signIn(member, 'member@example.test');
    await openReview(member);
    await expect.poll(() => draftGrid(member).evaluate(grid => grid.busy)).toBe(false);
    await draftCell(page, id, 'lines/0/name').click();
    const input = gridEditor(draftGrid(page));
    const attempted = `Unsent value ${Date.now()}`;
    await input.fill(attempted);
    const discarded = member.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/draft-command'
      && response.request().postDataJSON().command === 'discard');
    await draftGrid(member).locator(`tr[data-row-id="${id}/0"]`).getByRole('button', { name: 'Discard operation', exact: true }).click();
    expect((await discarded).status()).toBe(200);
    await expect.poll(() => draftIds(page)).toEqual([]);
    await expect(draftGrid(page)).toBeVisible();
    await expect(input).toHaveValue(attempted);
    let writes = 0;
    const countWrite = request => { if (new URL(request.url()).pathname === '/action/tover.stock.web/save-field') writes += 1; };
    page.on('request', countWrite);
    await input.press('Enter');
    await expect(draftGrid(page)).toContainText(attempted);
    expect(writes).toBe(0);
    page.off('request', countWrite);
    await expect(draftGrid(page)).toContainText(/no longer|removed|discarded/i);
    await expect(page.getByRole('button', { name: 'Submit changes', exact: true })).toBeDisabled();
    await expect(page.locator('#inventory-notice')).not.toHaveClass(/error/);
    await draftGrid(page).getByRole('button', { name: 'Dismiss retained edit', exact: true }).click();
    await expect(draftGrid(page)).toBeHidden();
    await expect(page.locator('#inventory-review')).toBeEmpty();
    await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  } finally { await memberContext.close(); }
});

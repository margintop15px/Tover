import { expect, test } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';

test.beforeEach(async ({ page }) => signIn(page));

const stockGrid = page => page.locator('#stock-revogrid');
const stockRow = (page, name) => stockGrid(page).locator('.rgRow').filter({ hasText: name });

async function addStockRow(page, name) {
  const entry = page.locator('#stock-entry');
  for (const [field, value] of [
    ['Goods', name], ['Quantity', '3'], ['Unit', 'pcs'],
    ['Location', 'Smoke shelf'], ['Article', 'SMOKE'],
  ]) {
    await entry.getByRole('combobox', { name: 'Add stock row', exact: true }).fill(field);
    await entry.getByRole('option', { name: new RegExp(`\\b${field}\\b`) }).click();
    const input = entry.getByRole('combobox', { name: `${field} for new row`, exact: true });
    await input.fill(value);
    await input.press('Enter');
  }
  await entry.getByRole('button', { name: 'Add row', exact: true }).click();
  await expect(stockRow(page, name)).toBeVisible();
}

test('fresh production has empty stock and operations without demo controls', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveTitle('Stock · Tover');
  await expect(page.getByRole('combobox', { name: 'Add stock row', exact: true })).toBeVisible();
  await expect(stockGrid(page).locator('.sheet-goods-name')).toHaveCount(0);
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  await expect(page.getByText('Demo data', { exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(page.getByText('No operations yet.', { exact: true })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'Demo data', exact: true })).toHaveCount(0);
});

test('grid drafts support editing, preview, revert and local Submit until reload', async ({ page }) => {
  await page.goto('/');
  await addStockRow(page, 'Browser smoke item');
  await stockRow(page, 'Browser smoke item').locator('.rgCell[data-rgcol="1"]').dblclick();
  const editor = stockGrid(page).locator('revogr-edit input');
  await editor.fill('7');
  await editor.press('Enter');
  await expect(editor).toHaveCount(0);
  await expect(stockRow(page, 'Browser smoke item').locator('.rgCell[data-rgcol="1"]')).toHaveText('7');
  await page.getByRole('button', { name: 'Preview changes', exact: true }).click();
  const preview = page.locator('#stock-draft-preview');
  await expect(preview).toBeVisible();
  await expect(preview).toContainText('Browser smoke item');
  await expect(preview).toContainText('7 pcs');
  await page.getByRole('button', { name: 'Revert all changes', exact: true }).click();
  await expect(stockGrid(page).locator('.sheet-goods-name')).toHaveCount(0);
  await expect(preview).toBeHidden();
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();

  await addStockRow(page, 'Browser submitted item');
  await page.getByRole('button', { name: 'Preview changes', exact: true }).click();
  await preview.getByRole('button', { name: 'Submit changes', exact: true }).click();
  await expect(stockRow(page, 'Browser submitted item')).toBeVisible();
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  const operations = page.locator('#operations-history');
  await expect(operations).toContainText('Browser submitted item');
  await expect(operations).toContainText('Row added');
  await expect(operations).toContainText('Local');

  await page.reload();
  await expect(page.locator('#operations-history')).toBeVisible();
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await expect(stockGrid(page)).toBeVisible();
  await expect(stockGrid(page).locator('.sheet-goods-name')).toHaveCount(0);
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(page.getByText('No operations yet.', { exact: true })).toBeVisible();
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
    await expect(outsider.locator('#stock-revogrid')).toHaveCount(0);
  } finally {
    await anonymous.close();
  }
});

test('workspace switching refreshes other tabs, discards old drafts and rejects stale requests', async ({ page, context }) => {
  const stale = await pageContext(page);
  await addStockRow(page, 'Workspace-specific draft');
  const other = await context.newPage();
  await other.goto('/workspace');
  const form = other.locator('form[action="/action/tover.workspaces.web/select-workspace"]');
  await form.locator('[name="workspace-id"]').selectOption({ label: 'Other workspace' });
  await form.getByRole('button', { name: 'Open workspace', exact: true }).click();
  await expect(other.locator('#stock-revogrid')).toBeVisible();
  await expect(stockRow(page, 'Workspace-specific draft')).toHaveCount(0);
  await expect.poll(async () => (await pageContext(page)).version).not.toBe(stale.version);
  const staleResponse = await context.request.post('/action/tover.stock.web/accept', {
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
  await expect(other.locator('#stock-revogrid')).toBeVisible();
  await page.goto('/workspace');
  await page.locator('form[action="/action/tover.auth.web/sign-out"]').getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page).toHaveURL(/\/auth\/login$/);
  await expect(other).toHaveURL(/\/auth\/login$/);
  const status = await context.request.post('/action/tover.stock.web/accept', {
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
  const missing = await context.request.post('/action/tover.stock.web/accept', {
    headers: { origin, 'datastar-request': 'true', 'x-tover-context': version }, data,
  });
  expect(missing.status()).toBe(403);
  const foreign = await context.request.post('/action/tover.stock.web/accept', {
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

import { test, expect } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';
import { editStockCell, gridHeader, operationGrid, signals, stockGrid, stockRow } from './grid.fixture.mjs';

const responseSignals = body => Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals ')).map(line => JSON.parse(line.slice(14))));
async function view(page, name, payload) {
  const { csrf, version } = await pageContext(page);
  const response = await page.context().request.post(`/view/tover.stock.web/${name}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version }, data: payload,
  });
  expect(response.status()).toBe(200); return responseSignals(await response.text());
}
const content = grid => grid.locator(':scope > .inventory-grid-sheet .jss_content').first();
async function jump(grid, fraction) {
  await content(grid).evaluate((node, fraction) => { node.scrollTop = (node.scrollHeight - node.clientHeight) * fraction; node.dispatchEvent(new Event('scroll')); }, fraction);
}
async function visibleAnchor(grid) {
  return grid.evaluate(node => {
    const bounds = node.querySelector('.jss_content').getBoundingClientRect();
    const row = node._owned('tbody > tr[data-row-id]').find(row => !row.dataset.spacer && row.getBoundingClientRect().bottom > bounds.top + 34);
    return row && { id: row.dataset.rowId, offset: Math.round(row.getBoundingClientRect().top - bounds.top) };
  });
}
const errors = new WeakMap();
test.beforeEach(async ({ page }) => {
  const messages = []; errors.set(page, messages); page.on('pageerror', error => messages.push(error.message));
  await page.setViewportSize({ width: 1200, height: 850 }); await signIn(page);
});
test.afterEach(async ({ page }) => { expect(errors.get(page)).toEqual([]); });

test('real durable tables load complete windows and operation details only on expansion', async ({ page }) => {
  const stock = stockGrid(page);
  await expect.poll(() => stock.evaluate(grid => grid.model.viewport?.totalRows)).toBe(261);
  expect(await stock.evaluate(grid => grid.model.rows.length)).toBe(200);
  const snapshot = await signals(page, '_inventorySnapshot');
  expect(snapshot.rows.length).toBe(200);
  expect(snapshot.operationRows.every(row => Object.keys(row).every(key => ['id', 'operation'].includes(key)))).toBe(true);
  expect(await stock.evaluate(grid => grid.model.details)).toBeUndefined();
  await jump(stock, 1); await expect.poll(() => stock.evaluate(grid => grid.model.viewport.end)).toBe(261);
  expect(await stock.evaluate(grid => grid.model.rows.length)).toBeLessThanOrEqual(200);
  await expect(stock.locator('tr[data-row-id] td[data-column-key="name"]').last()).toContainText('Viewport produced');
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  const operations = operationGrid(page);
  await expect.poll(() => operations.evaluate(grid => grid.model.viewport?.totalGroups)).toBe(232);
  expect(await operations.evaluate(grid => grid.model.rows.length)).toBe(200);
  expect(await operations.evaluate(grid => grid.model.details)).toBeUndefined();
  const queryId = await operations.evaluate(grid => grid.model.viewport.queryId);
  const data = await view(page, 'grid-window', { queryId, requestId: 'integration-last', view: 'operations', start: 230, count: 200 });
  const window = JSON.parse(data._gridWindowJson); expect(window.status).toBe('ok');
  expect(window.model.rows.filter(row => row.groupId === 'viewport-opening')).toHaveLength(260);
  expect(window.model.rows.filter(row => row.groupId === 'viewport-production')).toHaveLength(3);
  expect(window.model.merges.every(merge => merge.row >= 0 && merge.row < window.model.rows.length)).toBe(true);
  expect(window.model.details).toBeUndefined();
  await jump(operations, 0.3); const before = await visibleAnchor(operations);
  const clickableId = await operations.evaluate(grid => {
    const bounds = grid.querySelector('.jss_content').getBoundingClientRect();
    return grid._owned('tbody > tr[data-row-id]').find(row => row.querySelector('.inventory-cell-action') && row.getBoundingClientRect().top >= bounds.top + 34)?.dataset.rowId;
  });
  const button = operations.locator(`tr[data-row-id="${clickableId}"]`).getByRole('button').first();
  const read = page.waitForResponse(response => new URL(response.url()).pathname === '/view/tover.stock.web/operation-details');
  await button.click(); expect((await read).status()).toBe(200);
  await expect(operations.locator('.operation-details')).toContainText('owner@example.test');
  await expect(operations.locator('.operation-details')).toContainText('Viewport payment fixture');
  await expect.poll(() => visibleAnchor(operations)).toEqual(before);
  expect(await operations.locator('.operation-details table,.operation-details details').count()).toBe(0);
  await button.click(); await expect(operations.locator('.operation-details')).toHaveCount(0);
  await expect.poll(() => visibleAnchor(operations)).toEqual(before);
});

test('server sorting and complex filters cover all records rather than the current window', async ({ page }) => {
  const stock = stockGrid(page);
  await jump(stock, 1); await expect.poll(() => stock.evaluate(grid => grid.model.viewport.start)).toBeGreaterThan(0);
  const oldQuery = await stock.evaluate(grid => grid.model.viewport.queryId);
  await gridHeader(stock, 'name').getByRole('button', { name: 'Sort by Goods', exact: true }).click();
  await expect(gridHeader(stock, 'name')).toHaveAttribute('aria-sort', 'descending');
  await expect.poll(() => stock.evaluate(grid => grid.model.viewport.start)).toBe(0);
  expect(await stock.evaluate(grid => grid.model.viewport.queryId)).not.toBe(oldQuery);
  await expect(stock.locator('tbody td[data-column-key="name"]').first()).toContainText('Viewport produced');
  await gridHeader(stock, 'name').getByRole('button', { name: 'Filter Goods', exact: true }).click();
  const dialog = stock.getByRole('dialog', { name: 'Filter Goods' });
  await dialog.getByRole('combobox', { name: 'Add condition', exact: true }).selectOption('begins');
  await dialog.getByRole('textbox', { name: 'Value 1', exact: true }).fill('Viewport goods 25');
  await dialog.getByRole('textbox', { name: 'Value 1', exact: true }).press('Enter');
  await expect.poll(() => stock.evaluate(grid => grid.model.viewport.totalRows)).toBe(10);
  await dialog.getByRole('combobox', { name: 'Add condition', exact: true }).selectOption('notEq');
  await dialog.getByRole('textbox', { name: 'Value 2', exact: true }).fill('Viewport goods 255');
  await dialog.getByRole('textbox', { name: 'Value 2', exact: true }).press('Enter');
  await expect.poll(() => stock.evaluate(grid => grid.model.viewport.totalRows)).toBe(9);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  const names = await stock.locator('tbody td[data-column-key="name"] .inventory-cell-value').allTextContents();
  expect(names).toHaveLength(9); expect(names[0]).toBe('Viewport goods 259'); expect(names).not.toContain('Viewport goods 255');
  await expect(page.locator('#inventory-filters')).toContainText('Viewport goods 25');
  await expect(page.locator('#inventory-filters')).toContainText('Viewport goods 255');
});

test('item-scoped history keeps production parents inline and only opens relevant records', async ({ page, browser }, testInfo) => {
  const stock = stockGrid(page);
  await jump(stock, 1); await expect.poll(() => stock.evaluate(grid => grid.model.viewport.end)).toBe(261);
  const produced = stockRow(page, 'Viewport produced');
  await produced.getByRole('button', { name: 'History for Viewport produced', exact: true }).click();
  const panel = page.locator('#history-panel');
  await expect(panel).toBeVisible();
  const rowId = await panel.getAttribute('data-row-id');
  const placement = () => panel.evaluate(node => node.closest('tr').previousElementSibling.dataset.rowId);
  expect(await placement()).toBe(rowId);
  const history = panel.locator('#item-history-grid');
  await expect.poll(() => history.evaluate(grid => grid.model.rows.length)).toBe(1);
  await expect(history).toContainText('Production');
  await expect(history).not.toContainText('Opening');
  await expect(history).not.toContainText('Viewport payment');
  await expect(panel.locator('.inventory-timeline')).toHaveCount(0);

  await panel.getByRole('tab', { name: 'Lineage', exact: true }).click();
  await expect(history).toHaveCount(0);
  await expect(panel.locator('.lineage-lane')).toHaveCount(1);
  const parents = panel.locator('button.lineage-parent-strip');
  await expect(parents).toHaveCount(2);
  await expect(parents).toContainText(['Viewport goods 000 × 2 pcs', 'Viewport goods 001 × 1 pcs']);
  expect(await parents.evaluateAll(strips => strips.every(strip => {
    const chart = strip.closest('.lineage-chart');
    const production = [...chart.querySelectorAll('.lineage-bar')].find(bar => bar.dataset.operationId === strip.dataset.operationId);
    const parent = strip.getBoundingClientRect(), own = production.getBoundingClientRect();
    return strip.closest('.lineage-parent-plot') && parent.bottom < own.top && Math.abs(parent.right - own.left) < 2;
  }))).toBe(true);
  await parents.first().click();
  const details = panel.locator('#history-operation-details');
  await expect(details).toHaveAttribute('data-operation-id', 'viewport-production');
  await expect(details).toContainText('Viewport goods 000');
  await expect(details).toContainText('Viewport goods 001');
  await expect(details).toContainText('Viewport produced');
  await expect(details).toContainText('owner@example.test');
  await expect(details.locator('tbody tr')).toHaveCount(3);
  expect(await panel.innerText()).not.toMatch(/\brevision\s+\d+\b/i);

  const requestId = await panel.getAttribute('data-request-id');
  const readDetail = async (client, operationId) => {
    const { csrf, version } = await pageContext(client);
    return client.context().request.post('/action/tover.stock.web/open-history-operation', {
      headers: { origin: new URL(client.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
      data: { itemId: 'viewport-produced', rowId, requestId, operationId, revision: 1 },
    });
  };
  const denied = await readDetail(page, 'viewport-payment-000');
  expect(denied.status()).toBe(200);
  expect(await denied.text()).toContain('This history record is unavailable.');
  await expect(details).toHaveAttribute('data-operation-id', 'viewport-production');
  const otherContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const other = await otherContext.newPage(); await signIn(other, 'other@example.test');
    const privateRead = await readDetail(other, 'viewport-production');
    expect(privateRead.status()).toBe(200);
    const body = await privateRead.text();
    expect(body).toContain('This item has no accepted inventory history.');
    expect(body).not.toContain('Viewport merged production');
  } finally { await otherContext.close(); }

  const previousPanel = await panel.elementHandle();
  const refreshed = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/open-history');
  await panel.getByRole('button', { name: 'Refresh history', exact: true }).click();
  expect((await refreshed).status()).toBe(200);
  await expect.poll(() => previousPanel.evaluate(node => node.isConnected)).toBe(false);
  await expect(panel).toHaveAttribute('data-history-mode', 'lineage');
  await expect(details).toHaveAttribute('data-operation-id', 'viewport-production');
  expect(await placement()).toBe(rowId);
  await page.screenshot({ path: testInfo.outputPath('item-scoped-production-lineage.png') });
  await details.getByRole('button', { name: 'Close operation details', exact: true }).click();
  await expect(parents.first()).toBeFocused();
  await panel.getByRole('tab', { name: 'History', exact: true }).click();
  await expect.poll(() => history.evaluate(grid => grid.model.rows.length)).toBe(1);
  expect(await placement()).toBe(rowId);
});

test('user and tenant tokens are isolated and shared edits preserve another member’s visible anchor', async ({ page, browser }) => {
  const stock = stockGrid(page), queryId = await stock.evaluate(grid => grid.model.viewport.queryId);
  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin, viewport: { width: 1200, height: 850 } });
  const otherContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage(); await signIn(member, 'member@example.test');
    const memberRead = await view(member, 'grid-window', { queryId, requestId: 'member-forgery', view: 'stock', start: 200, count: 200 });
    expect(JSON.parse(memberRead._gridWindowJson).status).toBe('expired');
    const other = await otherContext.newPage(); await signIn(other, 'other@example.test');
    const otherRead = await view(other, 'grid-window', { queryId, requestId: 'other-forgery', view: 'stock', start: 0, count: 200 });
    expect(JSON.parse(otherRead._gridWindowJson).status).toBe('expired');
    const detail = await view(other, 'operation-details', { queryId, operationId: 'viewport-opening', requestId: 'other-detail-forgery' });
    expect(JSON.parse(detail._operationDetailJson).status).toBe('expired');
    await expect(stockGrid(other)).toContainText('Other tenant private goods'); await expect(stockGrid(other)).not.toContainText('Viewport goods');
    await jump(stock, 1); await expect.poll(() => stock.evaluate(grid => grid.model.viewport.end)).toBe(261);
    const before = await visibleAnchor(stock);
    await editStockCell(member, stockRow(member, 'Viewport goods 000'), 'article', 'VIEWPORT-SHARED-ARTICLE');
    await expect.poll(() => stock.evaluate(grid => grid.model.viewport.queryId)).not.toBe(queryId);
    await expect.poll(() => visibleAnchor(stock)).toEqual(before);
    await jump(stock, 0); await expect(stockRow(page, 'Viewport goods 000')).toContainText('VIEWPORT-SHARED-ARTICLE');
    const current = await stock.evaluate(grid => grid.model.viewport.queryId);
    const authorized = await view(page, 'grid-window', { queryId: current, requestId: 'current-authorized', view: 'stock', start: 200, count: 200 });
    expect(JSON.parse(authorized._gridWindowJson).status).toBe('ok');
  } finally { await memberContext.close(); await otherContext.close(); }
});

test('accepted viewports fit desktop and narrow screens with sticky headers in both themes', async ({ page }, testInfo) => {
  for (const kind of ['stock', 'operations']) {
    await page.goto(kind === 'stock' ? '/' : '/operations');
    const table = kind === 'stock' ? stockGrid(page) : operationGrid(page);
    await expect.poll(() => table.evaluate(grid => Boolean(grid.worksheet && grid.model.viewport))).toBe(true);
    for (const width of [1200, 390]) for (const theme of ['light', 'dark']) {
      await page.setViewportSize({ width, height: 850 });
      const toggle = page.getByRole('button', { name: theme === 'dark' ? 'Switch to dark mode' : 'Switch to light mode', exact: true });
      if (await toggle.isVisible()) await toggle.click();
      await expect.poll(() => content(table).evaluate(node => node.clientHeight)).toBeGreaterThan(200);
      const dimensions = await content(table).evaluate(node => {
        const bounds = node.getBoundingClientRect(); return { bottom: bounds.bottom, height: node.clientHeight, innerHeight };
      });
      expect(dimensions.bottom).toBeLessThanOrEqual(dimensions.innerHeight + 1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      const headerTop = await table.locator('thead').evaluate(node => node.getBoundingClientRect().top);
      await jump(table, 0.2);
      await expect.poll(() => table.locator('thead').evaluate(node => Math.abs(node.getBoundingClientRect().top - node.closest('.jss_content').getBoundingClientRect().top))).toBeLessThanOrEqual(2);
      expect(Math.abs(await table.locator('thead').evaluate(node => node.getBoundingClientRect().top) - headerTop)).toBeLessThanOrEqual(2);
      await page.screenshot({ path: testInfo.outputPath(`${kind}-${width}-${theme}.png`) });
    }
  }
});

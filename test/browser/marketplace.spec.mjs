import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

// Board 42 · Product Matching: one table; select rows, Confirm, and the mappings survive a reload.
const connection = '/settings/marketplace/browser-ozon';
const views = '/operations/marketplace/browser-ozon';
const grid = page => page.locator('.marketplace-grid');
const workbench = page => page.locator('marketplace-workbench');
const row = (page, name) => grid(page).locator('tbody tr').filter({ hasText: name });

async function ready(page, id) {
  await expect.poll(() => grid(page).evaluate(node => !!node.worksheet)).toBe(true);
  await expect.poll(() => grid(page).evaluate(node => node.model.id)).toBe(id);
}

test('select three rows with the keyboard, confirm, reload: still confirmed (400 px)', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 400, height: 860 });
  await signIn(page);
  await page.goto(connection);
  const unmatched = page.getByRole('link', { name: '5', exact: true });
  await expect(unmatched).toHaveAttribute('href', `${views}/products`);
  // Keyboard only from here: follow the link, move through the table with arrows, tick with Space.
  await unmatched.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`${views}/products$`));
  await expect(page.getByRole('heading', { name: 'Product matching' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Back to operations' })).toHaveAttribute('href', '/operations');
  await ready(page, 'marketplace-products-unmatched');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(grid(page)).toHaveCount(1);
  await expect(row(page, 'Kettle (Ozon)')).toContainText('Kettle · same barcode');

  // The table starts at its first cell, the first row's box.
  await grid(page).focus();
  await expect.poll(() => workbench(page).evaluate(node => node.current?.selection?.id ?? null)).not.toBeNull();
  const order = await grid(page).evaluate(node => node.model.rows.map(row => row.label));
  for (let index = 0; index < order.length; index++) {
    if (order[index] !== 'Gift card') await page.keyboard.press('Space');
    if (index < order.length - 1) await page.keyboard.press('ArrowDown');
  }
  await expect(page.locator('.marketplace-selected')).toHaveText('3 selected');
  const confirm = page.getByRole('button', { name: 'Confirm', exact: true });
  await expect(confirm).toBeEnabled();
  await confirm.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.map(row => row.label))).toEqual(['Gift card']);
  await expect(page.locator('.catalogue-notice')).toHaveText('');

  await page.reload();
  await ready(page, 'marketplace-products-unmatched');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.map(row => row.label))).toEqual(['Gift card']);
  await page.goto(`${views}/products?state=confirmed`);
  await ready(page, 'marketplace-products-confirmed');
  for (const name of ['Kettle', 'Teapot', 'Saucer']) await expect(row(page, `${name} (Ozon)`)).toContainText('Mapped');
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(400);
  expect(errors).toEqual([]);
});

test('a rejected row keeps its values and shows why', async ({ page }) => {
  await signIn(page);
  await page.goto(`${views}/products`);
  await ready(page, 'marketplace-products-unmatched');
  const gift = await grid(page).evaluate(node => node.model.rows.find(row => row.label === 'Gift card').id);
  await workbench(page).evaluate((node, id) => node.select([id]), gift);
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.locator('.catalogue-notice')).toHaveText('Gift card: Choose a stock item.');
  await expect(row(page, 'Gift card').locator('td.marketplace-finding')).toHaveCount(1);
  await expect(page.locator('.marketplace-selected')).toHaveText('1 selected');
});

// Board 43 · Warehouse Matching: the same table; several rows confirmed at once survive a reload.
test('confirm several warehouses, reload: still confirmed', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await page.goto(connection);
  // The fixture also has three cutover baselines; this is the count of unmatched warehouses.
  const unmatched = page.locator('dt:text-is("Unmatched warehouses") + dd').getByRole('link', { name: '3', exact: true });
  await expect(unmatched).toHaveAttribute('href', `${views}/warehouses`);
  await unmatched.click();
  await expect(page.getByRole('heading', { name: 'Warehouse matching' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Back to operations' })).toHaveAttribute('href', '/operations');
  await ready(page, 'marketplace-warehouses-unmatched');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(row(page, 'Khorugvino')).toContainText('New warehouse: Khorugvino');

  const ids = await grid(page).evaluate(node => node.model.rows.map(row => row.id));
  expect(ids).toHaveLength(3);
  await workbench(page).evaluate((node, selected) => node.select(selected), ids);
  await expect(page.locator('.marketplace-selected')).toHaveText('3 selected');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await expect(page.locator('.catalogue-notice')).toHaveText('');

  await page.reload();
  await ready(page, 'marketplace-warehouses-unmatched');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await page.goto(`${views}/warehouses?state=confirmed`);
  await ready(page, 'marketplace-warehouses-confirmed');
  for (const name of ['Khorugvino', 'Tver', 'Seller stock']) await expect(row(page, name)).toContainText('Mapped');
  expect(errors).toEqual([]);
});

// Board 42 · a suspended mapping is reconfirmed in the table, not on a details form.
test('reconfirm a suspended row in the table, reload: confirmed', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await page.goto(`${views}/products?state=suspended`);
  await ready(page, 'marketplace-products-suspended');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(row(page, 'Ladle (Ozon)')).toContainText('Suspended');
  await expect(row(page, 'Ladle (Ozon)')).toContainText('Ladle');
  const id = await grid(page).evaluate(node => node.model.rows.find(row => row.label === 'Ladle (Ozon)').id);
  await workbench(page).evaluate((node, selected) => node.select([selected]), id);
  await expect(page.locator('.marketplace-selected')).toHaveText('1 selected');
  await page.getByRole('button', { name: 'Reconfirm', exact: true }).click();
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await expect(page.locator('.catalogue-notice')).toHaveText('');

  await page.reload();
  await ready(page, 'marketplace-products-suspended');
  await expect.poll(() => grid(page).evaluate(node => node.model.rows.length)).toBe(0);
  await page.goto(`${views}/products?state=confirmed`);
  await ready(page, 'marketplace-products-confirmed');
  await expect(row(page, 'Ladle (Ozon)')).toContainText('Mapped');
  await page.goto(`${views}/products/${encodeURIComponent(id)}`);
  await expect(page.locator('section.management-page form')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Open in the matching table' })).toHaveAttribute('href', `${views}/products?state=confirmed`);
  expect(errors).toEqual([]);
});

// Matching is a view of the Operations page: the switcher lists it, and the Operations tab stays current.
test('the view switcher moves between operations and the matching views', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await page.goto('/operations');
  const switcher = page.getByRole('navigation', { name: 'Views' });
  await expect(switcher.getByRole('link', { name: 'Operations', exact: true })).toHaveAttribute('aria-current', 'page');
  await switcher.getByRole('link', { name: /^Warehouse matching/ }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/warehouses$`));
  await expect(page.getByRole('heading', { name: 'Warehouse matching' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Warehouse matching/ })).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.app-nav-pages a[aria-current=page]')).toHaveText('Operations');
  await page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(page).toHaveURL(/\/operations$/);
  expect(errors).toEqual([]);
});

// Board 44 · Cutover: a view of the Operations page; type the cost of an opening, propose, and the scope waits in the
// shared draft with its observation shown in the Stock page's Preview.
test('propose the cutover of a ready scope, reload, review it filtered and accept: baseline', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const setup = page.locator('#marketplace-cutover-grid');
  const whisk = setup.locator('tbody tr').filter({ hasText: 'Whisk' });
  await signIn(page);
  await page.goto(connection);
  await expect(page.locator(`a[href="${views}/cutover"]`).first()).toBeVisible();
  await page.goto('/operations');
  await page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Cutover/ }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/cutover$`));
  await expect(page.getByRole('heading', { name: 'Cutover', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Cutover/ })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByLabel('Cutover instant')).toHaveValue('2026-11-20T10:00:00Z');
  await expect.poll(() => setup.evaluate(node => !!node.worksheet)).toBe(true);
  await expect(whisk).toContainText('Ready');
  await expect(whisk).toContainText('Opening');
  await expect(whisk.locator('td[data-x="7"]')).toHaveText('7');

  // The cost is typed with a comma, as for any opening.
  await whisk.locator('td[data-x="8"]').dblclick();
  await page.keyboard.type('250,5');
  await page.keyboard.press('Enter');
  await expect(whisk.locator('td[data-x="8"]')).toHaveText('250,5');
  const id = await setup.evaluate(node => node.model.rows.find(row => row.label.startsWith('Whisk')).id);
  await page.locator('marketplace-workbench#marketplace-cutover').evaluate((node, selected) => node.select([selected]), id);
  await expect(page.locator('#marketplace-cutover .marketplace-selected')).toHaveText('1 selected');
  await page.getByRole('button', { name: 'Propose', exact: true }).click();
  await expect(whisk).toContainText('Pending');
  await expect(whisk).toContainText('Proposed 7, difference +7');
  await expect(page.locator('#marketplace-cutover .catalogue-notice')).toHaveText('');

  await page.reload();
  await expect.poll(() => setup.evaluate(node => !!node.worksheet)).toBe(true);
  await expect(whisk).toContainText('Pending');
  await expect(page.locator('#marketplace-cutover-coverage-grid tbody tr').filter({ hasText: 'Whisk' })).toContainText('No cutover baseline');

  // Cutover Review is the shared draft's Preview narrowed to this connection's entries, with its observation.
  await page.getByRole('link', { name: 'Review and accept the proposed entries' }).click();
  await expect(page).toHaveURL(/\/\?cutover=/);
  await expect(page.locator('.stock-filter-chip')).toHaveText('Cutover entries ×');
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(() => page.locator('#stock-grid').evaluate(node => (node.model?.rows || []).flatMap(row => row.cells.map(cell => cell.title || '')).join('\n')))
    .toContain('Cutover: observed 7, proposed 7, difference +7 from Tover');
  await expect.poll(() => page.locator('#stock-grid').evaluate(node => node.model.pending.map(entry => entry['entry-id'])))
    .toEqual([expect.stringMatching(/^cutover:/)]);
  await page.locator('.stock-submit').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'false');

  // The accepted opening is the scope's baseline.
  await page.goto(`${views}/cutover`);
  await expect(page.locator('#marketplace-cutover-coverage-grid tbody tr').filter({ hasText: 'Whisk' })).toContainText('Baseline at 2026-11-20T10:00:00Z');
  expect(errors).toEqual([]);
});

// Board 45 · Inventory Review: imported operations on the Operations page, grouped by what unblocks them; a
// selection is set aside, hidden until its next step is filtered, and brought back.
test('an imported operation waits under its decision; set aside and bring back a selection, reload', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const sheet = page.locator('#operations-grid');
  const checkbox = sheet.locator('input[data-review-entry="import:browser-ozon:fbs-posting:P1"]');
  const header = sheet.locator('td.operations-decision').filter({ hasText: 'Match Gift card' });
  await signIn(page);
  await page.goto(connection);
  await page.locator('a[href="/operations?source=browser-ozon"]').click();
  await expect(page).toHaveURL(/\/operations\?source=browser-ozon$/);
  await expect(page.locator('select.operations-source')).toHaveValue('browser-ozon');
  await expect(header).toContainText('Match Gift card – 1 operation');
  await expect(sheet.locator('td.operations-source').first()).toHaveText('Ozon · Browser shop');
  await checkbox.check();
  await page.getByLabel('Reason (optional)').fill('Gift card is not stock');
  await page.getByRole('button', { name: 'Set aside', exact: true }).click();
  await expect(checkbox).toHaveCount(0);
  await page.reload();
  await expect.poll(() => sheet.evaluate(node => !!node.worksheet)).toBe(true);
  await expect(header).toHaveCount(0);

  // Filtered by next step, the set-aside row shows again and comes back.
  await page.locator('operations-workbench').evaluate(node => node.setFilters({ 'next-step': [{ type: 'begins', value: 'Set' }] }));
  await checkbox.check();
  await page.getByRole('button', { name: 'Bring back', exact: true }).click();
  await expect(checkbox).toHaveCount(0);
  await page.locator('operations-workbench').evaluate(node => node.setFilters({}));
  await expect(header).toContainText('Match Gift card – 1 operation');
  await expect(checkbox).toHaveCount(1);
  // The group opens matching with its listing.
  await header.getByRole('button', { name: 'Open' }).click();
  await expect(page).toHaveURL(/\/operations\/marketplace\/browser-ozon\/products\?listing=/);
  expect(errors).toEqual([]);
});

// Board 45 · Inventory Review: in Preview the proposed correction sits under its own columns, and Set aside never
// takes Preview's "Include in submit" (every row by default) as its selection.
test('in Preview the proposed correction fits its columns and Set aside waits for an explicit selection', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const sheet = page.locator('#operations-grid');
  const header = sheet.locator('td.operations-decision').filter({ hasText: 'Fix shortage of Whisk' });
  const setAside = page.getByRole('button', { name: 'Set aside', exact: true });
  await signIn(page);
  // The fixture's intake loop (each second) proposes the sale once the cutover test has accepted the Whisk
  // baseline; without that baseline this poll fails well inside the test's own timeout.
  await expect.poll(async () => {
    await page.goto('/operations?source=browser-ozon');
    await expect.poll(() => sheet.evaluate(node => !!node.worksheet)).toBe(true);
    return page.locator('operations-workbench').evaluate(node => node.model?.imported?.['import:browser-ozon:fbo-posting:W1']?.entryRevision ?? null);
  }, { message: 'W1 is proposed after the Whisk baseline is accepted', timeout: 20000, intervals: [1000] }).not.toBeNull();
  await expect(header).toContainText('Fix shortage of Whisk – 1 operation');
  await expect(setAside).toBeDisabled();

  await page.locator('.stock-pending-filter').click();
  await expect(page.locator('.stock-pending-filter')).toHaveAttribute('aria-pressed', 'true');
  // Preview has come back: only operations with an entry, so not the Gift card sale.
  await expect.poll(() => page.locator('operations-workbench').evaluate(node => node.model.rows.filter(row => row.header).map(row => row.cells[0].value)))
    .toEqual([expect.stringContaining('Fix shortage of Whisk')]);
  expect(errors).toEqual([]);
  const columns = await page.locator('operations-workbench').evaluate(node => node.model.columns.map(column => column.key));
  expect(columns[0]).toBe('select');
  const suggested = 'suggested|import:browser-ozon:fbo-posting:W1';
  const cell = key => sheet.locator(`td[data-row-id="${suggested}"][data-column-key="${key}"]`);
  await expect(cell('kind')).toHaveText('Correction');
  await expect(cell('source')).toHaveText('Ozon · Browser shop');
  await expect(cell('warehouse')).toHaveText('Cutover hub');
  await expect(cell('quantity')).toHaveText(/^[1-9][0-9]*$/);
  await expect(sheet.locator(`td[data-row-id="${suggested}"][data-x="${columns.length - 1}"]`).getByRole('button', { name: 'Add the proposed correction' })).toHaveCount(1);
  // Every pending row is included in submit, and none of them is chosen for Set aside.
  await expect(sheet.locator('td[data-row-id^="import:browser-ozon:fbo-posting:W1|"] input[type=checkbox]').first()).toBeChecked();
  await expect(setAside).toBeHidden();
  expect(errors).toEqual([]);
});

// Board 45 slice 9 · an accepted imported record carries its source badge on the Operations page, and its Revisions
// panel shows the provenance: source operation and revision, how it was accepted, the mapping and later revisions.
test('an accepted imported record shows its badge and its provenance in the Revisions panel', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const sheet = page.locator('#operations-grid');
  const record = 'import:browser-ozon:fbs-posting:K1';
  await signIn(page);
  await page.goto('/operations');
  await expect.poll(() => sheet.evaluate(node => !!node.worksheet)).toBe(true);
  const rowId = await page.locator('operations-workbench').evaluate((node, id) => node.model.rows.find(row => row.groupId === id)?.id, record);
  expect(rowId).toBeTruthy();
  await expect(sheet.locator(`td[data-row-id="${rowId}"][data-column-key="source"]`)).toHaveText('Ozon · Browser shop');
  await sheet.locator(`td[data-row-id="${rowId}"]`).getByRole('button', { name: 'Revisions and source' }).click();
  const panel = page.locator('#history-panel .operations-provenance');
  await expect(panel).toContainText('fbs-posting:K1');
  await expect(panel).toContainText('Accepted automatically');
  await expect(panel).toContainText('Whisk (Ozon) → Whisk · mapping revision 1');
  await expect(panel).toContainText('customer_received');
  await expect(page.locator('#history-panel tbody tr')).toHaveCount(1);
  expect(errors).toEqual([]);
});

// Board 45 slices b and c · Stock differences: a view of the Operations page; one read-only table of the newest
// snapshot's differences, and the detected move waits as a pending Transfer on the Operations table.
test('the stock differences view shows the detected move, and its Transfer waits on Operations', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const sieve = page.locator('#marketplace-differences-grid tbody tr').filter({ hasText: 'Sieve' });
  const transfer = 'move:browser-ozon|browser-item-7|browser-m0|browser-hub-a|browser-hub-b';
  await page.setViewportSize({ width: 400, height: 860 });
  await signIn(page);
  await page.goto('/operations');
  await page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Stock differences/ }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/differences$`));
  await expect(page.getByRole('heading', { name: 'Stock differences', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Stock differences/ })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('link', { name: 'Back to operations' })).toHaveAttribute('href', '/operations');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(page.locator('.marketplace-page [data-command]')).toHaveCount(0);
  await ready(page, 'marketplace-differences');
  // The fixture's detection loop (each second) proposes the move; reload until the Transfer is listed.
  await expect.poll(async () => {
    await page.reload();
    await ready(page, 'marketplace-differences');
    return grid(page).evaluate(node => node.model.rows.flatMap(row => row.cells.flatMap(cell => (cell.lines || []).map(line => line.value))));
  }, { timeout: 20000, intervals: [1000] }).toContain('5 from Hub A to Hub B · Pending');
  await expect(sieve.first()).toContainText('Moved');
  await expect(sieve.first()).toContainText('2026-11-19T10:00:00Z');
  await expect(sieve).toHaveCount(2);
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(400);

  // Each row links to its pending Transfer, which Operations opens with its connection and evidence.
  await expect(sieve.first().getByRole('button', { name: 'Open the pending transfer' })).toHaveCount(1);
  await sieve.first().getByRole('button', { name: 'Open the pending transfer' }).click();
  await expect(page).toHaveURL(/\/operations$/);
  const workbench = page.locator('operations-workbench');
  await expect.poll(() => workbench.evaluate((node, id) => (node.model?.rows || []).some(row => row.groupId === id), transfer)).toBe(true);
  const row = page.locator('#operations-grid tbody tr').filter({ has: page.locator('td.operations-next-step', { hasText: 'Detected move' }) }).first();
  await expect(row.locator('td.operations-source')).toHaveText('Ozon · Browser shop');
  await expect(row.locator('td.operations-next-step')).toHaveAttribute('title', /^Last agreeing snapshot 2026-11-19T10:00:00Z · current snapshot /);

  await page.goBack();
  await page.getByRole('link', { name: 'Review and accept them on Operations' }).click();
  await expect(page).toHaveURL(/\/operations$/);
  await expect.poll(() => workbench.evaluate((node, id) => (node.model?.rows || []).some(row => row.groupId === id), transfer))
    .toBe(true);
  expect(errors).toEqual([]);
});

// Board 46 · Transit Overview and Shipment Details: a view of the Operations page; one read-only table of shipments in
// transit with state and connection filters, each opening its read-only timeline and the accepted Sale it produced.
test('the transit view lists the travelling posting, and its details link to the accepted sale', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const colander = page.locator('.marketplace-grid tbody tr').filter({ hasText: 'Colander' });
  const sale = 'import:browser-ozon:fbo-posting:T1';
  await page.setViewportSize({ width: 400, height: 860 });
  await signIn(page);
  await page.goto('/operations');
  await page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Transit/ }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/transit$`));
  await expect(page.getByRole('heading', { name: 'Transit', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Views' }).getByRole('link', { name: /^Transit/ })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('navigation', { name: 'Connection' }).getByRole('link', { name: 'Ozon · Browser shop' })).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.marketplace-page form')).toHaveCount(0);
  await expect(page.locator('.marketplace-page [data-command]')).toHaveCount(0);
  await expect(page.locator('.marketplace-page .management-secondary')).toContainText('Travelling 2');
  await ready(page, 'marketplace-transit-all');
  await expect(colander).toHaveCount(1);
  await expect(colander).toContainText('Posting T1');
  await expect(colander).toContainText('Travelling');
  await expect(colander).toContainText('2026-11-21T09:00:00Z');
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(400);

  // The state filter: nothing of the Colander is returning.
  await page.getByRole('navigation', { name: 'Filter' }).getByRole('link', { name: 'Returning', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/transit\\?state=returning$`));
  await ready(page, 'marketplace-transit-returning');
  await expect(colander).toHaveCount(0);
  await page.getByRole('navigation', { name: 'Filter' }).getByRole('link', { name: 'Travelling', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/transit\\?state=travelling$`));
  await ready(page, 'marketplace-transit-travelling');
  await expect(colander).toHaveCount(1);

  // Shipment Details: the timeline, its actual time apart from Tover's observation time, and the accepted Sale.
  await colander.getByRole('button', { name: 'Open details' }).click();
  await expect(page).toHaveURL(new RegExp(`${views}/transit/${encodeURIComponent('browser-ozon:fbo-posting:T1')}$`));
  await expect(page.getByRole('heading', { name: 'Posting T1', exact: true })).toBeVisible();
  const timeline = page.locator('section[aria-labelledby="shipment-timeline"] tbody tr');
  await expect(timeline).toHaveCount(1);
  await expect(timeline.locator('td')).toHaveText(['1', 'delivering', '2026-11-21T09:00:00Z', '2026-11-20T10:00:00Z']);
  await expect(page.locator('.management-content').locator('form, input, button')).toHaveCount(0);
  await page.getByRole('link', { name: 'Sale', exact: true }).click();
  await expect(page).toHaveURL(/\/operations$/);
  const workbench = page.locator('operations-workbench');
  await expect.poll(() => workbench.evaluate((node, id) => (node.model?.rows || []).some(row => row.groupId === id), sale)).toBe(true);
  expect(errors).toEqual([]);
});

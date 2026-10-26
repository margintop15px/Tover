import { expect, test } from '@playwright/test';
import { pageContext, signIn } from './auth.fixture.mjs';
import { openDraftCell, operationGrid, revertPending, stockRow } from './grid.fixture.mjs';

const draftGrid = page => page.locator('#pending-draft-grid');
const draftCell = (page, id, path) => draftGrid(page).locator(`td[data-entry-id="${id}"][data-path="${path}"]`);
const editor = page => draftGrid(page).locator('.inventory-cell-editor > input, .inventory-cell-editor > select:not(.inventory-goods-choice)');

function actionSignals(body) {
  return Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals '))
    .map(line => JSON.parse(line.slice('data: signals '.length))));
}

async function action(page, name, payload) {
  const { csrf, version } = await pageContext(page);
  const response = await page.context().request.post(`/action/tover.stock.web/${name}`, {
    headers: {
      origin: new URL(page.url()).origin,
      'datastar-request': 'true',
      'x-csrf-token': csrf,
      'x-tover-context': version,
    },
    data: { view: 'stock', ...payload },
  });
  expect(response.status()).toBe(200);
  const body = await response.text();
  const signals = actionSignals(body);
  expect(signals.saveStatus, body).not.toBe('Failed');
  return signals;
}

async function seedDraft(page, type, fields, extraLines = []) {
  const before = await page.evaluate(async () => {
    const { getPath } = await import('/datastar.js');
    return JSON.parse(JSON.stringify(getPath('_draftIds')));
  });
  let signals = await action(page, 'add-entry', { type });
  let current = signals._inventorySnapshot.draftSelection.find(entry => !before.includes(entry.id));
  expect(current).toBeTruthy();
  for (const path of extraLines) {
    signals = await action(page, 'draft-command', { ...current, command: 'add-line', path });
    current = signals._inventorySnapshot.draftSelection.find(entry => entry.id === current.id);
  }
  for (const [path, value] of fields) {
    signals = await action(page, 'save-field', { ...current, path, value });
    current = signals._inventorySnapshot.draftSelection.find(entry => entry.id === current.id);
  }
  await page.reload();
  return current.id;
}

async function openReview(page) {
  await page.locator('stock-entry input').press('Escape');
  if (!(await draftGrid(page).isVisible())) {
    await page.getByRole('button', { name: 'Preview changes', exact: true }).click();
  }
  await expect(draftGrid(page)).toBeVisible();
  await expect.poll(() => draftGrid(page).evaluate(grid => Boolean(grid.worksheet))).toBe(true);
}

async function editCell(page, id, path, value, key = 'Enter') {
  const cell = draftCell(page, id, path);
  const revision = await cell.getAttribute('data-revision');
  await expect.poll(() => draftGrid(page).evaluate(grid => grid.busy)).toBe(false);
  const input = await openDraftCell(page, id, path);
  try { await expect(input).toBeVisible(); } catch (error) {
    const state = await draftGrid(page).evaluate(async grid => {
      const { getPath } = await import('/datastar.js');
      return { busy: grid.busy, saveStatus: grid.saveStatus, pending: getPath('_reviewPending'), dirty: getPath('_reviewDirty'),
        active: grid._active && { target: grid._active.target, original: grid._active.original },
        selected: grid.worksheet?.selectedCell, editor: grid.querySelector('.inventory-cell-editor')?.outerHTML };
    });
    throw new Error(`${error.message}\nCE editor state: ${JSON.stringify(state)}`);
  }
  if (await input.evaluate(element => element.tagName === 'SELECT', null, { timeout: 5000 })) await input.selectOption(value);
  else await input.fill(value, { timeout: 5000 });
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/save-field'
    && response.request().postDataJSON().path === path, { timeout: 10_000 });
  await input.press(key);
  expect((await saved).status()).toBe(200);
  await expect(cell).not.toHaveAttribute('data-revision', revision);
  await expect(cell).toContainText(value);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect(page.getByRole('button', { name: 'Submit selected', exact: true })).toBeEnabled();
}

async function expectNativeMerge(page, id, path, minimumRows) {
  const cell = draftCell(page, id, path);
  await expect(cell).toBeVisible();
  await expect.poll(() => cell.evaluate(element => element.rowSpan)).toBeGreaterThanOrEqual(minimumRows);
  expect(await cell.evaluate(element => element.closest('table').className)).toMatch(/jss|jexcel/);
}

async function screenshot(page, name, { mobile = false, dark = false } = {}) {
  await page.setViewportSize(mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 });
  const toggle = page.getByRole('button', { name: dark ? 'Switch to dark mode' : 'Switch to light mode', exact: true });
  if (await toggle.isVisible()) await toggle.click();
  await page.screenshot({ path: `/tmp/tover-ce-${name}.png`, fullPage: true });
}

test('CE gate: merged purchase and production cells preserve real saves, refresh, realtime and conflicts', async ({ page, browser }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  await revertPending(page);
  const prefix = `CE gate ${Date.now()}`;
  const purchaseId = await seedDraft(page, 'purchase', [
    ['party', `${prefix} supplier`],
    ['lines/0/name', `${prefix} input A`], ['lines/0/quantity', '20'], ['lines/0/location', 'CE shelf'],
    ['lines/1/name', `${prefix} input B`], ['lines/1/quantity', '10'], ['lines/1/location', 'CE shelf'],
  ], ['lines']);
  await openReview(page);
  await expectNativeMerge(page, purchaseId, 'party', 2);
  await expect(draftGrid(page).locator('td[colspan], td.inventory-section-cell')).toHaveCount(0);
  await expect(draftGrid(page).getByRole('button', { name: 'Add item', exact: true })).toHaveText('+');
  await expect(draftGrid(page).getByRole('button', { name: 'Add arrival', exact: true })).toHaveCount(0);
  await expect(draftCell(page, purchaseId, 'lines/0/name')).toContainText(`${prefix} input A`);
  await expect(draftCell(page, purchaseId, 'lines/1/name')).toContainText(`${prefix} input B`);
  await editCell(page, purchaseId, 'lines/0/quantity', '24', 'Tab');
  await editCell(page, purchaseId, 'party', `${prefix} saved supplier`);
  const cancelledInput = await openDraftCell(page, purchaseId, 'lines/1/quantity');
  await cancelledInput.fill('999');
  let cancelledRequests = 0;
  const countCancelled = request => {
    if (new URL(request.url()).pathname === '/action/tover.stock.web/save-field'
      && request.postDataJSON().path === 'lines/1/quantity') cancelledRequests += 1;
  };
  page.on('request', countCancelled);
  await editor(page).press('Escape');
  await expect(editor(page)).toHaveCount(0);
  await expect(draftCell(page, purchaseId, 'lines/1/quantity')).toHaveText('10');
  expect(cancelledRequests).toBe(0);
  page.off('request', countCancelled);
  await page.reload();
  await openReview(page);
  await expect(draftCell(page, purchaseId, 'party')).toContainText(`${prefix} saved supplier`);
  await expect(draftCell(page, purchaseId, 'lines/0/quantity')).toHaveText('24');

  const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const member = await memberContext.newPage();
    member.on('pageerror', error => errors.push(error.message));
    await signIn(member, 'member@example.test');
    await openReview(member);
    await editCell(member, purchaseId, 'lines/1/quantity', '12');
    await expect(draftCell(page, purchaseId, 'lines/1/quantity')).toHaveText('12');
    const ownerInput = await openDraftCell(page, purchaseId, 'party');
    await ownerInput.fill(`${prefix} unfinished owner value`);
    await editCell(member, purchaseId, 'party', `${prefix} member supplier`);
    await expect(editor(page)).toHaveValue(`${prefix} unfinished owner value`);
    const stale = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/save-field');
    await editor(page).press('Enter');
    await stale;
    await expect(page.locator('#inventory-notice')).toContainText(`${prefix} member supplier`);
    await expect(draftGrid(page)).toContainText(`${prefix} unfinished owner value`);
    await draftGrid(page).getByRole('button', { name: 'Use saved value', exact: true }).click();
    await expect(draftCell(page, purchaseId, 'party')).toContainText(`${prefix} member supplier`);
    await expect(page.getByRole('button', { name: 'Submit selected', exact: true })).toBeEnabled();
    await screenshot(page, 'purchase-dark', { dark: true });
    await screenshot(page, 'purchase-light');
    await screenshot(page, 'purchase-mobile', { mobile: true, dark: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('button', { name: 'Submit selected', exact: true }).click();
    await expect(draftCell(page, purchaseId, 'party')).toHaveCount(0);
    const items = await page.evaluate(async () => {
      const { getPath } = await import('/datastar.js');
      return JSON.parse(JSON.stringify(getPath('_inventorySnapshot').rows));
    });
    const inputA = items.find(item => item.name === `${prefix} input A`);
    const inputB = items.find(item => item.name === `${prefix} input B`);
    expect(inputA.quantity).toBe(24);
    expect(inputB.quantity).toBe(12);

    const productionId = await seedDraft(page, 'production', [
      ['inputs/0/item-id', inputA['item-id']], ['inputs/0/quantity', '4'], ['inputs/0/location', 'CE shelf'],
      ['inputs/1/item-id', inputB['item-id']], ['inputs/1/quantity', '2'], ['inputs/1/location', 'CE shelf'],
      ['outputs/0/name', `${prefix} output A`], ['outputs/0/quantity', '3'], ['outputs/0/location', 'CE result'],
      ['outputs/1/name', `${prefix} output B`], ['outputs/1/quantity', '1'], ['outputs/1/location', 'CE result'],
      ['reason', 'CE real production gate'],
    ], ['inputs', 'outputs']);
    await openReview(page);
    await expectNativeMerge(page, productionId, 'occurred-at', 4);
    await expect(draftGrid(page).locator('td[colspan]')).toHaveCount(0);
    await expect.poll(() => draftGrid(page).evaluate(grid => grid.model.rows.length)).toBe(4);
    await expect(draftGrid(page).getByRole('button', { name: 'Add input', exact: true })).toHaveCount(1);
    await expect(draftGrid(page).getByRole('button', { name: 'Add output', exact: true })).toHaveCount(1);
    const consumedColor = await draftCell(page, productionId, 'inputs/0/quantity').evaluate(cell => getComputedStyle(cell).backgroundColor);
    const createdColor = await draftCell(page, productionId, 'outputs/0/quantity').evaluate(cell => getComputedStyle(cell).backgroundColor);
    expect(consumedColor).not.toBe(createdColor);
    await editCell(page, productionId, 'outputs/1/quantity', '2');
    await expect(draftCell(member, productionId, 'outputs/1/quantity')).toHaveText('2');
    await screenshot(page, 'production-dark', { dark: true });
    await screenshot(page, 'production-light');
    await screenshot(page, 'production-mobile', { mobile: true, dark: true });
    const overflow = await draftGrid(page).evaluate(grid => {
      const container = grid.querySelector('.jss_content,.jexcel_content');
      if (!container) return null;
      container.scrollLeft = container.scrollWidth;
      return { scrollLeft: container.scrollLeft, scrollWidth: container.scrollWidth, clientWidth: container.clientWidth };
    });
    expect(overflow).not.toBeNull();
    expect(overflow.scrollWidth).toBeGreaterThan(overflow.clientWidth);
    expect(overflow.scrollLeft).toBeGreaterThan(0);
    await page.setViewportSize({ width: 1600, height: 900 });
    await expectNativeMerge(page, productionId, 'occurred-at', 4);
    await expect(page.getByRole('button', { name: 'Submit selected', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Submit selected', exact: true }).click();
    const output = stockRow(page, `${prefix} output A`);
    await expect(output).toBeVisible();
    await output.getByRole('button', { name: `History for ${prefix} output A`, exact: true }).click();
    const history = page.locator('#history-panel');
    await expect.poll(() => history.locator('#item-history-grid').evaluate(grid => grid.model.rows.length)).toBe(1);
    await history.getByRole('tab', { name: 'Lineage', exact: true }).click();
    await expect(history.locator('#item-history-grid')).toHaveCount(0);
    await expect(history.locator('.lineage-lane-label')).toHaveCount(1);
    await expect(history.locator('.lineage-lane-label')).toContainText([`${prefix} output A`]);
    await expect(history.locator('button.lineage-parent-strip')).toHaveCount(2);
    await expect(history.locator('button.lineage-parent-strip')).toContainText([`${prefix} input A × 4 pcs`, `${prefix} input B × 2 pcs`]);
    expect(await history.locator('button.lineage-parent-strip').evaluateAll(strips => strips.every(strip => strip.closest('.lineage-chart') && strip.closest('.lineage-parent-plot')))).toBe(true);
    const expectParentAlignment = async () => {
      const alignment = await history.locator('.lineage-chart').evaluate(chart => {
        const ownLane = chart.querySelector('.lineage-lane').getBoundingClientRect();
        const bars = [...chart.querySelectorAll('.lineage-bar')];
        return [...chart.querySelectorAll('.lineage-parent-strip')].map(strip => {
          const parent = strip.getBoundingClientRect();
          const production = bars.find(bar => bar.dataset.operationId === strip.dataset.operationId).getBoundingClientRect();
          const lane = strip.closest('.lineage-parent-lane');
          const label = lane.querySelector('.lineage-parent-label').getBoundingClientRect();
          return { edgeDifference: Math.abs(parent.right - production.left),
            aboveProduction: parent.bottom < ownLane.top, insidePlot: parent.left >= label.right - 1 };
        });
      });
      for (const parent of alignment) {
        expect(parent.edgeDifference).toBeLessThan(2);
        expect(parent.aboveProduction).toBe(true);
        expect(parent.insidePlot).toBe(true);
      }
    };
    await expectParentAlignment();
    await expect(history.getByRole('button', { name: 'Include parent items', exact: true })).toHaveCount(0);
    await expect(history.locator('.lineage-legend,.lineage-productions')).toHaveCount(0);
    const production = history.locator('button.lineage-parent-strip', { hasText: `${prefix} input A` }).first();
    await production.click();
    const details = history.locator('#history-operation-details');
    await expect(details).toContainText('Production');
    await expect(details).toContainText(`${prefix} input A`);
    await expect(details).toContainText(`${prefix} input B`);
    await expect(details).toContainText(`${prefix} output B`);
    await expect(details).toContainText('owner@example.test');
    await details.getByRole('button', { name: 'Close operation details', exact: true }).click();
    await expect(production).toBeFocused();
    await screenshot(page, 'automatic-parent-history-dark', { dark: true });
    await screenshot(page, 'automatic-parent-history-light');
    await screenshot(page, 'automatic-parent-history-mobile', { mobile: true, dark: true });
    await expectParentAlignment();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('link', { name: 'Operations', exact: true }).click();
    const accepted = operationGrid(page);
    await expect.poll(() => accepted.evaluate(grid => Boolean(grid.worksheet))).toBe(true);
    await expect(accepted.locator('.inventory-section-cell')).toHaveCount(0);
    const productionRows = await accepted.evaluate((grid, id) => grid.model.rows.filter(row => row.groupId === id), productionId);
    expect(productionRows).toHaveLength(4);
    expect(productionRows.filter(row => row.cells.some(cell => cell.className === 'inventory-production-input'))).toHaveLength(2);
    expect(productionRows.filter(row => row.cells.some(cell => cell.className === 'inventory-production-output'))).toHaveLength(2);
    expect(await accepted.evaluate((grid, id) => grid.model.rows.filter(row => row.groupId === id).length, purchaseId)).toBe(2);
    const typeCell = accepted.locator(`td[data-row-id="${productionId}/0"][data-column-key="type"]`);
    await expect(typeCell.locator('.inventory-cell-value')).toHaveText('Production');
    await expect(typeCell.locator('xpath=../td[contains(@class,"jss_row")]')).toHaveAttribute('rowspan', '4');
    await typeCell.getByRole('button', { name: 'Show production details', exact: true }).click();
    const compact = accepted.locator('.operation-details');
    await expect(compact).toContainText('owner@example.test');
    await expect(compact).toContainText('CE real production gate');
    await expect(compact.getByRole('button', { name: 'Correct', exact: true })).toBeVisible();
    await expect(compact.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
    await expect(compact.locator('table,details')).toHaveCount(0);
    await expect(accepted.locator(`tr[data-row-id="${productionId}/details"] > td.jss_row`)).toBeEmpty();
    expect(await accepted.locator('td[data-column-key="type"],.operation-details dt').allTextContents())
      .not.toEqual(expect.arrayContaining([expect.stringMatching(/\bRevision\s+\d+\b/)]));
    for (const [name, options] of [['operations-dark', { dark: true }], ['operations-light', {}], ['operations-mobile', { mobile: true, dark: true }]]) {
      await screenshot(page, name, options);
      expect(await typeCell.locator('.inventory-cell-value').evaluate(label => label.getBoundingClientRect().height)).toBeLessThan(24);
      for (const label of ['Correct', 'Cancel']) {
        expect(await compact.getByRole('button', { name: label, exact: true }).evaluate(button => {
          const bounds = button.getBoundingClientRect();
          return bounds.left >= 0 && bounds.right <= innerWidth;
        })).toBe(true);
      }
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(errors).toEqual([]);
  } finally { await memberContext.close(); }
});

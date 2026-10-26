import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

let server, origin;
const publicRoot = new URL('../../resources/public/', import.meta.url);
test.beforeAll(async () => {
  server = createServer(async (request, response) => {
    if (request.url === '/') {
      response.setHeader('content-type', 'text/html');
      response.end('<!doctype html><html><head><link rel="stylesheet" href="/inventory-grid.css"><style>:root{--iw-bg:#1d2125;--iw-soft:#252a30;--iw-line:#3a4149;--iw-ink:#eceff4;--iw-muted:#a0a7b1;--iw-blue:#82a5cc}body{margin:20px;background:var(--iw-bg);color:var(--iw-ink)}#history-panel{padding:12px}#history-parking{display:none}</style><script type="module" src="/inventory-grid.js"></script></head><body></body></html>');
      return;
    }
    try {
      const path = new URL(`.${request.url}`, publicRoot);
      const body = await readFile(fileURLToPath(path));
      response.setHeader('content-type', path.pathname.endsWith('.css') ? 'text/css' : 'text/javascript'); response.end(body);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => { await new Promise(resolve => server.close(resolve)); });

async function mount(page) {
  await page.goto(origin);
  await page.evaluate(async () => {
    await customElements.whenDefined('inventory-grid');
    const grid = document.createElement('inventory-grid'); grid.id = 'inline-stock'; grid.setAttribute('aria-label', 'Stock');
    const cell = (id, key, value) => ({ value, target: { id, path: key, revision: 1 }, editor: { type: key === 'quantity' ? 'quantity' : 'text' } });
    grid.model = { id: 'stock', columns: [{ key: 'name', label: 'Goods', width: 220, sortKey: 'name' }, { key: 'quantity', label: 'Quantity', width: 120 }],
      rows: ['A', 'B', 'C'].map((location, index) => ({ id: `stock-${location}`, cells: [
        { ...cell(`stock-${location}`, 'name', `Same item · ${location}`), action: { type: 'item-history', label: `History ${location}`, icon: '›', rowId: `stock-${location}`, itemId: 'same-item' } },
        cell(`stock-${location}`, 'quantity', index + 4),
      ] })), merges: [] };
    const parking = document.createElement('div'); parking.id = 'history-parking'; parking.hidden = true;
    const panel = document.createElement('section'); panel.id = 'history-panel'; panel.dataset.rowId = 'stock-A';
    panel.innerHTML = '<button type="button" id="history-mode">History</button><button type="button" id="lineage-mode">Lineage</button><div id="lineage-view" hidden>Production details</div>';
    panel.querySelector('#lineage-mode').onclick = () => { panel.dataset.mode = 'lineage'; panel.querySelector('#lineage-view').hidden = false; };
    const history = document.createElement('inventory-grid'); history.id = 'nested-history'; history.setAttribute('aria-label', 'Item history');
    history.model = { editable: false, rowHeaders: false, columns: [{ key: 'record', label: 'Record', width: 240 }, { key: 'actor', label: 'Actor', width: 220 }], rows: [{ id: 'revision-1', cells: [{ value: 'Purchase' }, { value: 'Tester' }] }], merges: [] };
    panel.append(history); parking.append(panel); document.body.append(grid, parking);
    await grid.whenReady; await history.whenReady;
    window.stockWrites = [];
    grid.addEventListener('draft-edit', event => window.stockWrites.push(event.detail));
    grid.addEventListener('inventory-row-detail-close', event => window.closedDetail = event.detail);
    grid.rowDetail = { rowId: 'stock-A', node: panel };
  });
  await expect(page.locator('#inline-stock .inventory-grid-sheet tr.inventory-row-detail')).toHaveCount(1);
}
const stockBody = page => page.locator('#inline-stock > .inventory-grid-sheet table.jss_worksheet').first().locator(':scope > tbody');
const stockCell = (page, row, column) => stockBody(page).locator(`:scope > tr[data-row-id="stock-${row}"] > td[data-column-key="${column}"]`);

test('inline history follows its stable location row and isolates nested keyboard and paste', async ({ page }) => {
  await mount(page);
  await expect(stockBody(page).locator(':scope > tr').nth(1)).toHaveAttribute('data-detail-for', 'stock-A');
  await expect(page.getByRole('button', { name: 'History A', exact: true })).toHaveAttribute('aria-expanded', 'true');
  await page.getByRole('button', { name: 'Lineage', exact: true }).click();
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock'), panel = document.querySelector('#history-panel');
    window.savedPanel = panel; window.savedHistory = document.querySelector('#nested-history').worksheet;
    grid.model = { ...grid.model, rows: [grid.model.rows[2], grid.model.rows[0], grid.model.rows[1]] };
  });
  await expect(stockBody(page).locator(':scope > tr').nth(2)).toHaveAttribute('data-detail-for', 'stock-A');
  expect(await page.evaluate(() => document.querySelector('#history-panel') === window.savedPanel && document.querySelector('#nested-history').worksheet === window.savedHistory)).toBe(true);
  await expect(page.locator('#history-panel')).toHaveAttribute('data-mode', 'lineage');
  await expect(page.locator('#lineage-view')).toBeVisible();
  const nested = page.locator('#nested-history');
  await nested.locator('tbody td[data-column-key="actor"]').click();
  await page.keyboard.press('Delete'); await page.keyboard.press('Enter');
  await nested.evaluate(grid => {
    const clipboardData = new DataTransfer(); clipboardData.setData('text/plain', 'forbidden');
    grid.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData }));
  });
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock');
    grid.busy = true; grid.acceptedSort = { column: 'name', direction: 'desc' };
  });
  await expect(nested.getByRole('columnheader', { name: 'Actor', exact: true })).toBeVisible();
  await expect(nested.locator('td[data-column-key="actor"] .inventory-cell-value')).toHaveText('Tester');
  expect(await page.evaluate(() => window.stockWrites)).toEqual([]);
  expect(await page.evaluate(() => document.querySelector('#inline-stock')._attempts.size)).toBe(0);
  await page.evaluate(() => { document.querySelector('#inline-stock').busy = false; });
  await stockCell(page, 'B', 'quantity').click();
  const editor = page.locator('#inline-stock .inventory-cell-editor > input');
  await editor.fill('9');
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock');
    grid.model = { ...grid.model, rows: grid.model.rows.map(row => row.id === 'stock-C' ? { ...row, label: 'Changed by member' } : row) };
  });
  await expect(editor).toHaveValue('9'); await expect(editor).toBeFocused();
  await editor.press('Enter');
  expect(await page.evaluate(() => window.stockWrites)).toEqual([{ id: 'stock-B', path: 'quantity', revision: 1, value: '9' }]);
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock');
    grid.model = { ...grid.model, rows: grid.model.rows.filter(row => row.id !== 'stock-A') };
  });
  await expect(stockBody(page).locator(':scope > tr.inventory-row-detail')).toHaveCount(0);
  expect(await page.evaluate(() => window.closedDetail)).toEqual({ rowId: 'stock-A' });
  await expect(page.locator('#history-parking #history-panel')).toHaveCount(1);
});

test('panel replacement, nested focus restoration and arrow navigation preserve Stock coordinates', async ({ page }) => {
  await mount(page);
  await page.locator('#nested-history tbody td[data-column-key="record"]').click();
  await page.locator('#nested-history').focus();
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock');
    grid.model = { ...grid.model, rows: grid.model.rows.map(row => ({ ...row, label: 'Snapshot' })) };
  });
  await expect(page.locator('#nested-history')).toBeFocused();
  expect(await page.evaluate(() => window.jspreadsheet.current === document.querySelector('#nested-history').worksheet)).toBe(true);
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock'), old = document.querySelector('#history-panel');
    const replacement = document.createElement('section'); replacement.id = 'history-panel'; replacement.dataset.rowId = 'stock-A'; replacement.textContent = 'Refreshed history';
    old.replaceWith(replacement); grid.rowDetail = { rowId: 'stock-A', node: replacement };
  });
  await expect(stockBody(page).locator(':scope > tr').nth(1)).toContainText('Refreshed history');
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock'); grid.focus(); grid.worksheet.updateSelectionFromCoords(1, 0, 1, 0);
  });
  await page.keyboard.press('ArrowDown');
  expect(await page.locator('#inline-stock').evaluate(grid => grid.worksheet.selectedCell.map(Number))).toEqual([1, 2, 1, 2]);
  await page.keyboard.press('Enter');
  const input = page.locator('#inline-stock .inventory-cell-editor > input');
  await expect(input).toHaveValue('5'); await input.press('Escape');
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock'), placeholder = document.createElement('section');
    placeholder.id = 'history-panel'; document.querySelector('#history-panel').replaceWith(placeholder);
    grid.rowDetail = null; grid.focusRowAction('stock-A');
  });
  await expect(page.getByRole('button', { name: 'History A', exact: true })).toBeFocused();
  await expect(stockBody(page).locator(':scope > tr.inventory-row-detail')).toHaveCount(0);
  await expect(page.locator('#history-parking #history-panel')).toHaveCount(1);
  expect(await page.evaluate(() => window.stockWrites)).toEqual([]);
});

for (const kind of ['stock', 'operations']) test(`${kind} grows with every row and bottom-row details while retaining horizontal scrolling`, async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 640 });
  await mount(page);
  await page.evaluate(kind => {
    const grid = document.querySelector('#inline-stock'), panel = document.querySelector('#history-panel');
    const workspace = document.createElement('main'); workspace.id = 'workspace';
    Object.assign(workspace.style, { height: 'calc(100vh - 40px)', overflowY: 'auto', overflowX: 'hidden' });
    grid.before(workspace); workspace.append(grid); grid.rowDetail = null;
    const columns = [{ key: 'name', label: 'Goods', width: 280 }, ...['quantity', 'unit', 'location', 'article', 'barcode'].map(key => ({ key, label: key, width: 200 }))];
    grid.model = { ...grid.model, id: kind, columns, rows: Array.from({ length: 30 }, (_, index) => ({ id: `stock-${index}`, cells: columns.map((column, position) => ({
      value: position ? `${column.label} inventory reference for item ${index}` : `Stock item ${index}`,
      ...(position ? {} : { action: { type: 'item-history', label: `History row ${index}`, rowId: `stock-${index}`, itemId: `item-${index}` } }),
    })) })) };
    grid.addEventListener('item-history', event => { grid.rowDetail = event.detail.expanded ? null : { rowId: event.detail.rowId, node: panel }; });
  }, kind);
  const grid = page.locator('#inline-stock'), workspace = page.locator('#workspace');
  const dimensions = () => grid.evaluate(node => {
    const content = node.querySelector('.jss_content');
    return { height: node.clientHeight, overflow: content.scrollHeight - content.clientHeight,
      widthOverflow: content.scrollWidth - content.clientWidth, scrollLeft: content.scrollLeft };
  });
  await expect.poll(async () => (await dimensions()).height).toBeGreaterThan(950);
  await expect.poll(async () => (await dimensions()).overflow).toBeLessThanOrEqual(1);
  expect((await dimensions()).widthOverflow).toBeGreaterThan(300);
  const collapsedHeight = (await dimensions()).height;
  await page.getByRole('button', { name: 'History row 29', exact: true }).click();
  await expect(stockBody(page).locator(':scope > tr').last()).toHaveAttribute('data-detail-for', 'stock-29');
  await page.evaluate(() => { document.querySelector('#history-panel').style.minHeight = '420px'; });
  await expect.poll(async () => (await dimensions()).height).toBeGreaterThan(collapsedHeight + 400);
  await expect.poll(async () => (await dimensions()).overflow).toBeLessThanOrEqual(1);
  await workspace.evaluate(node => { node.scrollTop = node.scrollHeight; });
  expect(await workspace.evaluate(node => node.scrollTop)).toBeGreaterThan(700);
  await expect(page.locator('#history-panel')).toBeInViewport();
  await grid.evaluate(node => { node.querySelector('.jss_content').scrollLeft = 180; });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(async () => (await dimensions()).height).toBeGreaterThan(1350);
  await expect.poll(async () => (await dimensions()).overflow).toBeLessThanOrEqual(1);
  expect((await dimensions()).scrollLeft).toBe(180);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.setViewportSize({ width: 900, height: 640 });
  await grid.evaluate(node => { node.querySelector('.jss_content').scrollLeft = 0; });
  await page.getByRole('button', { name: 'History row 29', exact: true }).click();
  await expect(stockBody(page).locator(':scope > tr')).toHaveCount(30);
  await expect.poll(async () => (await dimensions()).height).toBe(collapsedHeight);
  await page.getByRole('button', { name: 'History row 29', exact: true }).click();
  await expect(stockBody(page).locator(':scope > tr').last()).toHaveAttribute('data-detail-for', 'stock-29');
  expect(await page.evaluate(() => window.stockWrites)).toEqual([]);
});

test('operation gutter merges each group and keeps detail rows unnumbered through snapshots', async ({ page }) => {
  await page.goto(origin);
  await page.evaluate(async () => {
    await import('/stock-operations.js');
    const operations = document.createElement('stock-operations'); operations.id = 'gutter-operations';
    const groups = [
      { id: 'make-a', operation: 'Production', lines: ['A', 'B', 'C'] },
      { id: 'make-b', operation: 'Production', lines: ['D', 'E'] },
      { id: 'opening', operation: 'Opening stock', lines: ['F'] },
    ];
    operations.entries = groups;
    operations.model = { id: 'operations', editable: false, columns: [
      { key: 'type', label: 'Operation', width: 180 }, { key: 'name', label: 'Goods', width: 240 },
    ], rows: groups.flatMap(group => group.lines.map((name, index) => ({ id: `${group.id}/${index}`, groupId: group.id, cells: [
      index ? {} : { value: group.operation, action: { type: 'operation-details', id: group.id, icon: '›' } }, { value: name },
    ] }))), merges: [{ row: 0, col: 0, rowspan: 3 }, { row: 3, col: 0, rowspan: 2 }],
      details: { 'make-a': { 'summary-metadata': [{ label: 'Submitted by', value: 'Tester' }] } },
    };
    document.body.append(operations); await operations.grid.whenReady;
  });
  const grid = page.locator('#gutter-operations inventory-grid');
  const header = id => grid.locator(`tr[data-row-id="${id}"] > td.jss_row`);
  const numbers = () => grid.locator('tbody > tr > td.jss_row').evaluateAll(headers => headers
    .filter(header => getComputedStyle(header).display !== 'none' && header.textContent)
    .map(header => ({ number: header.textContent, span: header.rowSpan, y: header.dataset.y })));
  await expect.poll(numbers).toEqual([{ number: '1', span: 3, y: '0' }, { number: '2', span: 2, y: '3' }, { number: '3', span: 1, y: '5' }]);
  await expect(header('make-a/1')).toBeHidden();
  await expect(header('make-a/1')).toHaveAttribute('data-y', '1');
  await expect(header('make-b/1')).toBeHidden();
  const gutterBounds = await header('make-a/0').boundingBox();
  await header('make-a/0').click({ position: { x: 20, y: gutterBounds.height - 5 } });
  await expect(grid.locator('tr[data-row-id="make-a/2"] > td[data-column-key="name"]')).toHaveClass(/highlight/);
  await grid.locator('tr[data-row-id="make-a/1"] > td[data-column-key="name"]').click();
  await grid.focus(); await page.keyboard.press('ArrowDown');
  expect(await grid.evaluate(grid => grid.worksheet.selectedCell.map(Number))).toEqual([1, 2, 1, 2]);
  const toggle = grid.locator('tr[data-row-id="make-a/0"]').getByRole('button');
  await toggle.click();
  await expect(header('make-a/details')).toBeEmpty();
  await expect(header('make-a/0')).toHaveAttribute('rowspan', '3');
  await expect(header('make-b/0')).toHaveText('2');
  await page.locator('#gutter-operations').evaluate(operations => {
    const rows = operations.model.rows;
    operations.model = { ...operations.model, rows: [...rows.slice(5), ...rows.slice(3, 5), ...rows.slice(0, 3)],
      merges: [{ row: 1, col: 0, rowspan: 2 }, { row: 3, col: 0, rowspan: 3 }] };
  });
  await expect.poll(numbers).toEqual([{ number: '1', span: 1, y: '0' }, { number: '2', span: 2, y: '1' }, { number: '3', span: 3, y: '3' }]);
  await expect(header('make-a/details')).toBeEmpty();
  await toggle.click();
  await expect(header('make-a/details')).toHaveCount(0);
  await expect(header('make-a/0')).toHaveText('3');
});

test('native resize follows the pointer without changing neighbours and retains widths through snapshots', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 800 });
  await mount(page);
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock'); grid.rowDetail = null; grid.style.width = '480px';
    grid.model = { ...grid.model, id: 'operations', editable: false, columns: [
      { key: 'type', label: 'Operation', width: 180 }, { key: 'date', label: 'Date', width: 200 }, { key: 'party', label: 'Party', width: 160 },
    ], rows: [{ id: 'operation', cells: [
      { value: 'Opening stock', action: { type: 'operation-details', label: 'Open operation', icon: '›' } },
      { value: '22 Oct 2026, 14:33' }, { value: 'Customer' },
    ] }, { id: 'second-line', cells: [{}, { value: 'Second line' }, {}] }], merges: [{ col: 0, row: 0, rowspan: 2 }] };
  });
  const grid = page.locator('#inline-stock');
  const header = key => grid.locator(`thead td[data-column-key="${key}"]`).first();
  const widths = () => grid.evaluate(node => [...node.querySelectorAll(':scope > .inventory-grid-sheet thead td[data-x]')].map(cell => cell.getBoundingClientRect().width));
  await expect.poll(widths).toEqual([180, 200, 160]);
  const drag = async (key, delta, snapshot = false) => {
    await header(key).scrollIntoViewIfNeeded();
    const before = await widths(), box = await header(key).boundingBox();
    await page.mouse.move(box.x + box.width - 2, box.y + 15); await page.mouse.down();
    expect(await grid.evaluate(node => node.worksheet.resizing?.column)).toBe(String(['type', 'date', 'party'].indexOf(key)));
    await page.mouse.move(box.x + box.width - 2 + delta, box.y + 15, { steps: 6 });
    if (snapshot) {
      await grid.evaluate(node => {
        window.resizingSheet = node.worksheet;
        node.model = { ...node.model, label: 'First broadcast' };
        node.model = { ...node.model, label: 'Latest broadcast', rows: node.model.rows.map((row, index) => index ? row : { ...row, cells: row.cells.map((cell, x) => x === 2 ? { ...cell, value: 'Latest saved party' } : cell) }) };
      });
      expect(await grid.evaluate(node => node.worksheet === window.resizingSheet)).toBe(true);
      await expect(grid.locator('tbody td[data-column-key="party"]').first()).toHaveText('Customer');
    }
    await page.mouse.up();
    const index = ['type', 'date', 'party'].indexOf(key);
    await expect.poll(async () => (await widths())[index]).toBeCloseTo(before[index] + delta, 0);
    for (const other of [0, 1, 2].filter(value => value !== index)) expect((await widths())[other]).toBe(before[other]);
  };
  await drag('type', 75, true);
  await expect(grid.locator('tbody td[data-column-key="party"]').first()).toHaveText('Latest saved party');
  await drag('type', -165);
  await grid.evaluate(node => { node.worksheet.updateSelectionFromCoords(1, 0, 2, 1); });
  await drag('date', 40);
  expect(await grid.evaluate(node => node.worksheet.selectedCell.map(Number))).toEqual([1, 0, 2, 1]);
  const saved = await widths();
  expect(saved[0]).toBe(90);
  const label = grid.locator('tbody td[data-column-key="type"]').first();
  expect(await label.evaluate(cell => {
    const value = cell.querySelector('.inventory-cell-value'), icon = cell.querySelector('.inventory-cell-action');
    return value.offsetHeight < 25 && Math.abs(value.getBoundingClientRect().top - icon.getBoundingClientRect().top) < 8 && value.scrollWidth > value.clientWidth;
  })).toBe(true);
  await grid.evaluate(node => {
    node.acceptedSort = { column: 'date', direction: 'asc' }; node.acceptedFilters = { party: [{ type: 'begin', value: 'Latest' }] };
    node.model = { ...node.model, rows: [...node.model.rows].reverse(), label: 'Ordered snapshot' };
  });
  await expect.poll(widths).toEqual(saved);
  await grid.evaluate(async node => {
    const replacement = document.createElement('inventory-grid'); replacement.id = node.id; replacement.model = node.model;
    replacement.style.width = node.style.width;
    node.replaceWith(replacement); await replacement.whenReady;
  });
  await expect.poll(widths).toEqual(saved);
  expect(await grid.evaluate(node => {
    const content = node.querySelector('.jss_content'); return content.scrollWidth > content.clientWidth;
  })).toBe(true);
});

test('all grid kinds fill their container while utility columns remain compact', async ({ page }) => {
  await page.setViewportSize({ width: 1200, height: 900 });
  await page.goto(origin);
  await page.evaluate(async () => {
    await customElements.whenDefined('inventory-grid');
    for (const id of ['stock', 'operations', 'shared-draft', 'resulting-stock', 'item-history']) {
      const grid = document.createElement('inventory-grid'); grid.id = `width-${id}`;
      if (id === 'item-history') grid.style.display = 'none';
      grid.model = { id, editable: false, rowHeaders: ['stock', 'operations'].includes(id), columns: [
        { key: 'select', label: 'Selected', width: 36 }, { key: 'name', label: 'Goods', width: 180 },
        { key: 'quantity', label: 'Quantity', width: 120 }, { key: 'actions', label: 'Actions', width: 96 },
      ], rows: [{ id: `${id}-row`, cells: [{ value: '' }, { value: 'Goods' }, { value: 4 }, { value: '' }] }], merges: [] };
      document.body.append(grid); await grid.whenReady;
    }
    document.querySelector('#width-item-history').style.display = '';
  });
  for (const id of ['stock', 'operations', 'shared-draft', 'resulting-stock', 'item-history']) {
    const grid = page.locator(`#width-${id}`);
    await expect.poll(() => grid.evaluate(node => {
      const content = node.querySelector('.jss_content'), table = node.querySelector('table.jss_worksheet');
      return Math.abs(table.getBoundingClientRect().width - content.clientWidth);
    })).toBeLessThanOrEqual(2);
    expect(await grid.evaluate(node => ['select', 'actions'].map(key => node.querySelector(`thead td[data-column-key="${key}"]`).getBoundingClientRect().width))).toEqual([36, 96]);
  }
  await page.setViewportSize({ width: 1500, height: 900 });
  for (const id of ['stock', 'operations', 'shared-draft', 'resulting-stock', 'item-history']) {
    await expect.poll(() => page.locator(`#width-${id}`).evaluate(node => Math.abs(node.querySelector('table.jss_worksheet').getBoundingClientRect().width - node.querySelector('.jss_content').clientWidth))).toBeLessThanOrEqual(2);
  }
});

test('width fitting preserves the active editor, selection and unacknowledged input', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 800 });
  await mount(page);
  await stockCell(page, 'B', 'quantity').click();
  const editor = page.locator('#inline-stock .inventory-cell-editor > input');
  await editor.fill('17');
  await page.locator('#inline-stock').evaluate(grid => {
    window.widthEditor = document.activeElement; window.widthSheet = grid.worksheet;
    window.widthSelection = grid.worksheet.selectedCell.slice();
  });
  for (const width of [1450, 700, 1200]) {
    await page.setViewportSize({ width, height: 800 });
    await expect.poll(() => page.locator('#inline-stock').evaluate(node => Math.abs(node.querySelector('table.jss_worksheet').getBoundingClientRect().width - node.querySelector('.jss_content').clientWidth))).toBeLessThanOrEqual(2);
    await expect(editor).toHaveValue('17'); await expect(editor).toBeFocused();
    expect(await page.locator('#inline-stock').evaluate(grid => grid.worksheet === window.widthSheet && document.activeElement === window.widthEditor && JSON.stringify(grid.worksheet.selectedCell) === JSON.stringify(window.widthSelection))).toBe(true);
    expect(await page.evaluate(() => window.stockWrites)).toEqual([]);
  }
  await editor.press('Escape');
  expect(await page.evaluate(() => window.stockWrites)).toEqual([]);
});

test('full-width fitting respects native resize and saved widths without caching automatic expansion', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 800 });
  await mount(page);
  const grid = page.locator('#inline-stock');
  await grid.evaluate(node => {
    node.rowDetail = null;
    node.model = { id: 'operations', editable: false, columns: [
      { key: 'type', label: 'Operation', width: 180 }, { key: 'date', label: 'Date', width: 200 }, { key: 'party', label: 'Party', width: 160 },
    ], rows: [{ id: 'operation', cells: [{ value: 'Purchase' }, { value: '22 Oct 2026' }, { value: 'Customer' }] }], merges: [] };
  });
  const widths = () => grid.evaluate(node => [...node.querySelectorAll(':scope > .inventory-grid-sheet thead td[data-x]')].map(cell => cell.getBoundingClientRect().width));
  const fills = () => grid.evaluate(node => Math.abs(node.querySelector('table.jss_worksheet').getBoundingClientRect().width - node.querySelector('.jss_content').clientWidth));
  await expect.poll(fills).toBeLessThanOrEqual(2);
  const drag = async (key, delta) => {
    const header = grid.locator(`thead td[data-column-key="${key}"]`), before = await widths(), box = await header.boundingBox();
    await page.mouse.move(box.x + box.width - 2, box.y + 15); await page.mouse.down();
    await page.mouse.move(box.x + box.width - 2 + delta, box.y + 15, { steps: 6 });
    const index = ['type', 'date', 'party'].indexOf(key), during = await widths();
    for (const other of [0, 1, 2].filter(value => value !== index)) expect(during[other]).toBe(before[other]);
    await page.mouse.up();
    await expect.poll(async () => (await widths())[index]).toBeCloseTo(before[index] + delta, 0);
    await expect.poll(fills).toBeLessThanOrEqual(2);
    return index;
  };
  await drag('type', -60);
  const manualType = (await widths())[0];
  await page.setViewportSize({ width: 1400, height: 800 });
  await expect.poll(fills).toBeLessThanOrEqual(2);
  expect((await widths())[0]).toBe(manualType);
  await drag('date', -50);
  const manualDate = (await widths())[1];
  expect((await widths())[0]).toBe(manualType);
  await drag('party', -60);
  const manualParty = (await widths())[2];
  await page.setViewportSize({ width: 1550, height: 800 });
  await expect.poll(fills).toBeLessThanOrEqual(2);
  expect((await widths())[2]).toBe(manualParty);
  expect((await widths())[0] + (await widths())[1]).toBeGreaterThan(manualType + manualDate);
  const saved = await widths();
  await grid.evaluate(node => { node.model = { ...node.model, label: 'Authoritative broadcast' }; });
  await expect.poll(widths).toEqual(saved);
  await page.setViewportSize({ width: 480, height: 800 });
  await expect.poll(() => grid.evaluate(node => node.querySelector('.jss_content').scrollWidth - node.querySelector('.jss_content').clientWidth)).toBeGreaterThan(200);
  expect(await page.evaluate(() => window.stockWrites)).toEqual([]);
});

test('manual widths save once, retain failed saves and isolate authoritative layouts by user', async ({ page }) => {
  await mount(page);
  const grid = page.locator('#inline-stock');
  await grid.evaluate(node => {
    node.rowDetail = null; node.style.width = '480px';
    node.model = { id: 'operations', layoutScope: 'user-a', savedWidths: {}, columnOrder: [], layoutVersion: 0, editable: false,
      columns: [{ key: 'type', label: 'Operation', width: 180 }, { key: 'date', label: 'Date', width: 200 }, { key: 'party', label: 'Party', width: 160 }],
      rows: [{ id: 'operation', cells: [{ value: 'Purchase' }, { value: '22 Oct 2026' }, { value: 'Customer' }] }], merges: [] };
    window.layoutWrites = []; node.addEventListener('inventory-layout', event => window.layoutWrites.push(event.detail));
  });
  const width = () => grid.locator('thead td[data-column-key=type]').evaluate(td => td.offsetWidth);
  await expect.poll(width).toBe(180);
  await grid.evaluate(node => { node.style.width = '700px'; });
  await expect.poll(width).toBeGreaterThan(180);
  expect(await page.evaluate(() => window.layoutWrites)).toEqual([]);
  await grid.evaluate(node => { node.style.width = '480px'; });
  await expect.poll(width).toBe(180);
  const box = await grid.locator('thead td[data-column-key=type]').boundingBox();
  await page.mouse.move(box.x + box.width - 2, box.y + 15); await page.mouse.down();
  await page.mouse.move(box.x + box.width + 38, box.y + 15, { steps: 6 });
  expect(await page.evaluate(() => window.layoutWrites)).toEqual([]);
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.layoutWrites)).toEqual([{ gridId: 'operations', widths: { type: 220 } }]);
  await expect(grid).toHaveAttribute('data-layout-status', 'Saving');
  await grid.evaluate(node => { node.model = { ...node.model, label: 'Inventory broadcast', savedWidths: {} }; });
  await expect.poll(width).toBe(220);
  await grid.evaluate(node => { node.layoutStatus = 'Failed'; });
  await expect(grid.getByText('Could not save table layout.')).toBeVisible();
  await grid.getByRole('button', { name: 'Retry saving table layout' }).click();
  expect(await page.evaluate(() => window.layoutWrites)).toEqual(Array(2).fill({ gridId: 'operations', widths: { type: 220 } }));
  await grid.evaluate(node => {
    node.columnLayout = { widths: { type: 220 }, order: ['type', 'date', 'party'], version: 1 }; node.layoutStatus = 'Saved';
    node.model = { ...node.model, label: 'Delayed old snapshot', savedWidths: {}, columnOrder: [], layoutVersion: 0 };
  });
  await expect.poll(width).toBe(220);
  await grid.evaluate(node => {
    window.userLayoutModel = node.model;
    node.model = { ...node.model, layoutScope: 'user-b', savedWidths: {}, columnOrder: [], layoutVersion: 0 };
  });
  await expect.poll(width).toBe(180);
  const savedModel = await page.evaluate(() => window.userLayoutModel);
  await page.reload();
  await page.evaluate(async model => {
    await customElements.whenDefined('inventory-grid');
    const node = document.createElement('inventory-grid'); node.id = 'inline-stock'; node.style.width = '480px'; node.model = model;
    document.body.append(node); await node.whenReady;
  }, savedModel);
  await expect.poll(width).toBe(220);
  await grid.evaluate(node => {
    node.style.width = '800px';
    node.columnLayout = { widths: { type: 160, date: 180, party: 140 }, order: [], version: 2, lastColumn: 'type' };
  });
  await expect.poll(width).toBe(160);
  const fitted = await grid.locator('thead td[data-column-key]').evaluateAll(headers => headers.map(td => td.offsetWidth));
  const allManual = await grid.evaluate(node => node.model);
  await page.reload();
  await page.evaluate(async model => {
    await customElements.whenDefined('inventory-grid');
    const node = document.createElement('inventory-grid'); node.id = 'inline-stock'; node.style.width = '800px'; node.model = model;
    document.body.append(node); await node.whenReady;
  }, allManual);
  await expect.poll(() => grid.locator('thead td[data-column-key]').evaluateAll(headers => headers.map(td => td.offsetWidth))).toEqual(fitted);
});

test('header drag waits for an authoritative layout and preserves merged edit targets and stale snapshot ordering', async ({ page }) => {
  await mount(page);
  const grid = page.locator('#inline-stock');
  await grid.evaluate(node => {
    node.rowDetail = null;
    const quantity = (line, value) => ({ value, target: { id: 'draft-a', path: `lines/${line}/quantity`, revision: 3 }, editor: { type: 'quantity' } });
    node.model = { id: 'shared-draft', layoutScope: 'user-a', savedWidths: {}, columnOrder: [], layoutVersion: 0,
      columns: [{ key: 'select', label: 'Selected', width: 36 }, { key: 'type', label: 'Operation', width: 180, sortKey: 'type' },
        { key: 'name', label: 'Goods', width: 220 }, { key: 'quantity', label: 'Quantity', width: 120 }, { key: 'actions', label: 'Actions', width: 96 }],
      rows: [{ id: 'first', cells: [{ selection: { id: 'draft-a' } }, { value: 'Production' }, { value: 'Input' }, quantity(0, 2), {}] },
        { id: 'second', cells: [{}, {}, { value: 'Output' }, quantity(1, 1), {}] },
        { id: 'details', cells: [{ value: 'Compact operation details' }, {}, {}, {}, {}] }],
      merges: [{ row: 0, col: 0, rowspan: 2 }, { row: 0, col: 1, rowspan: 2 }, { row: 2, col: 0, colspan: 5 }] };
    window.layoutWrites = []; window.layoutSorts = [];
    node.addEventListener('inventory-layout', event => window.layoutWrites.push(event.detail));
    node.addEventListener('inventory-sort', event => window.layoutSorts.push(event.detail));
  });
  const order = () => grid.locator(':scope > .inventory-grid-sheet thead td[data-column-key]').evaluateAll(headers => headers.map(td => td.dataset.columnKey));
  await expect.poll(order).toEqual(['select', 'type', 'name', 'quantity', 'actions']);
  const source = await grid.locator('thead td[data-column-key=quantity] .inventory-column-label').boundingBox();
  const target = await grid.locator('thead td[data-column-key=type] .inventory-column-label').boundingBox();
  await page.mouse.move(source.x + source.width / 2, source.y + 10); await page.mouse.down();
  await grid.evaluate(node => {
    window.pressedHeader = node.querySelector('thead td[data-column-key=quantity] .inventory-column-label');
    window.pressedSheet = node.worksheet;
    node.model = { ...node.model, label: 'Snapshot between mousedown and dragstart' };
    node.acceptedFilters = {};
  });
  expect(await grid.evaluate(node => window.pressedHeader.isConnected && node.worksheet === window.pressedSheet && !node._dragging)).toBe(true);
  await page.mouse.move(source.x + source.width / 2 + 8, source.y + 10, { steps: 2 });
  await page.mouse.move(target.x + 2, target.y + 10, { steps: 5 });
  await page.mouse.move(target.x + 3, target.y + 10); await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.layoutWrites)).toEqual([{ gridId: 'shared-draft', order: ['quantity', 'type', 'name'] }]);
  await grid.locator('thead td[data-column-key=type] .inventory-column-label').dispatchEvent('click', { detail: 1 });
  expect(await order()).toEqual(['select', 'type', 'name', 'quantity', 'actions']);
  expect(await page.evaluate(() => window.layoutSorts)).toEqual([]);
  await grid.evaluate(node => { node.columnLayout = { widths: {}, order: ['quantity', 'type', 'name'], version: 1 }; node.layoutStatus = 'Saved'; });
  await expect.poll(order).toEqual(['select', 'quantity', 'type', 'name', 'actions']);
  const sort = await grid.getByRole('button', { name: 'Sort by Operation', exact: true }).boundingBox();
  await page.mouse.move(sort.x + sort.width / 2, sort.y + 10); await page.mouse.down();
  await grid.evaluate(node => { node.model = { ...node.model, label: 'Snapshot during an ordinary sort press' }; });
  await page.mouse.up();
  expect(await page.evaluate(() => window.layoutSorts)).toEqual([{ column: 'type', direction: 'asc' }]);
  await expect(grid.locator('tbody tr[data-row-id=first] td[data-column-key=type]')).toHaveAttribute('rowspan', '2');
  await expect(grid.locator('tbody tr[data-row-id=details] td[data-x="0"]')).toHaveText('Compact operation details');
  await expect(grid.locator('tbody tr[data-row-id=details] td[data-x="0"]')).toHaveAttribute('colspan', '5');
  expect(await grid.locator('thead td[data-column-key=select] .inventory-column-label').evaluate(label => label.draggable)).toBe(false);
  expect(await grid.locator('thead td[data-column-key=actions] .inventory-column-label').evaluate(label => label.draggable)).toBe(false);
  const utility = await grid.locator('thead td[data-column-key=select]').boundingBox();
  await page.mouse.move(utility.x + utility.width - 2, utility.y + 15); await page.mouse.down();
  await page.mouse.move(utility.x + utility.width + 30, utility.y + 15, { steps: 4 }); await page.mouse.up();
  expect(await grid.locator('thead td[data-column-key=select]').evaluate(td => td.offsetWidth)).toBe(36);
  expect(await page.evaluate(() => window.layoutWrites.length)).toBe(1);
  await grid.locator('tbody tr[data-row-id=second] td[data-column-key=quantity]').click();
  const editor = grid.locator('.inventory-cell-editor > input');
  await editor.fill('7');
  await grid.evaluate(node => {
    node.columnLayout = { widths: { quantity: 170 }, order: ['name', 'quantity', 'type'], version: 2 };
    node.model = { ...node.model, layoutVersion: 0, columnOrder: [], savedWidths: {}, label: 'Delayed inventory broadcast' };
  });
  await expect(editor).toHaveValue('7'); await expect(editor).toBeFocused();
  await editor.press('Enter');
  expect(await page.evaluate(() => window.stockWrites)).toEqual([{ id: 'draft-a', path: 'lines/1/quantity', revision: 3, value: '7' }]);
  await expect.poll(order).toEqual(['select', 'name', 'quantity', 'type', 'actions']);
  await expect(grid.locator('tbody tr[data-row-id=second] td[data-column-key=quantity]')).toHaveAttribute('data-save-state', 'saving');
});

test('external input keeps focus while an edited cell saves and receives its acknowledgment', async ({ page }) => {
  await mount(page);
  await page.evaluate(() => { const input = document.createElement('input'); input.id = 'smart-input'; document.body.prepend(input); });
  await stockCell(page, 'B', 'quantity').click();
  const editor = page.locator('#inline-stock .inventory-cell-editor > input');
  await editor.fill('11');
  await page.locator('#smart-input').focus();
  await expect(page.locator('#smart-input')).toBeFocused();
  await page.evaluate(() => {
    const grid = document.querySelector('#inline-stock');
    grid.model = { ...grid.model, rows: grid.model.rows.map(row => row.id !== 'stock-B' ? row : {
      ...row, cells: row.cells.map(cell => cell.target?.path !== 'quantity' ? cell : { ...cell, value: '11', target: { ...cell.target, revision: 2 } }),
    }) };
  });
  await expect.poll(() => page.locator('#inline-stock').evaluate(grid => grid._attempts.size)).toBe(0);
  await expect(page.locator('#smart-input')).toBeFocused();
  expect(await page.evaluate(() => window.stockWrites)).toEqual([{ id: 'stock-B', path: 'quantity', revision: 1, value: '11' }]);
});

test('a snapshot received while saving does not permanently lock editable cells', async ({ page }) => {
  await mount(page);
  await page.locator('#inline-stock').evaluate(grid => {
    grid.saveStatus = 'Saving';
    grid.model = { ...grid.model, label: 'Snapshot during save', rows: grid.model.rows.map(row => row.id !== 'stock-C' ? row : {
      ...row, cells: row.cells.map(cell => cell.target?.path === 'quantity' ? { ...cell, readOnly: true } : cell),
    }) };
  });
  await expect(page.locator('#inline-stock')).toHaveAttribute('aria-busy', 'true');
  await expect(stockCell(page, 'B', 'quantity')).toHaveClass(/readonly/);
  await page.locator('#inline-stock').evaluate(grid => { grid.saveStatus = 'Saved'; });
  await expect(stockCell(page, 'B', 'quantity')).not.toHaveClass(/readonly/);
  await stockCell(page, 'B', 'quantity').click();
  const editor = page.locator('#inline-stock .inventory-cell-editor > input');
  await expect(editor).toHaveValue('5'); await editor.fill('12'); await editor.press('Enter');
  expect(await page.evaluate(() => window.stockWrites)).toEqual([{ id: 'stock-B', path: 'quantity', revision: 1, value: '12' }]);
  await stockCell(page, 'C', 'quantity').click();
  await expect(editor).toHaveCount(0);
  await expect(stockCell(page, 'C', 'quantity')).toHaveClass(/readonly/);
});

test('merged operation details fit the visible grid while the spreadsheet scrolls horizontally', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 640 });
  await mount(page);
  await page.locator('#inline-stock').evaluate(grid => {
    grid.rowDetail = null;
    grid.cellRenderer = cell => {
      if (!cell.operationDetail) return;
      const panel = document.createElement('div'); panel.className = 'operation-details';
      Object.assign(panel.style, { display: 'flex', justifyContent: 'space-between', width: '100%', boxSizing: 'border-box' });
      panel.innerHTML = '<span>Recorded today · Tester</span><span><button>Correct</button><button>Cancel</button></span>';
      return panel;
    };
    const columns = Array.from({ length: 8 }, (_, index) => ({ key: `field-${index}`, label: `Field ${index}`, width: 250 }));
    grid.model = { id: 'operations', columns, editable: false, rows: [
      { id: 'operation', cells: columns.map((_, index) => ({ value: `Operation field ${index}` })) },
      { id: 'details', cells: columns.map((_, index) => index ? {} : { operationDetail: true, className: 'inventory-operation-details-cell' }) },
    ], merges: [{ col: 0, row: 1, colspan: columns.length }] };
  });
  const panel = page.locator('#inline-stock .operation-details');
  const dimensions = () => panel.evaluate(node => {
    const grid = node.closest('inventory-grid'), content = grid.querySelector('.jss_content');
    return { width: node.getBoundingClientRect().width, grid: grid.clientWidth, cell: node.parentElement.offsetWidth, overflow: content.scrollWidth - content.clientWidth };
  });
  await expect.poll(async () => (await dimensions()).width).toBeLessThanOrEqual(860 - 44 - 32);
  expect((await dimensions()).cell).toBeGreaterThan(1800);
  expect((await dimensions()).overflow).toBeGreaterThan(1000);
  await expect(panel.getByRole('button', { name: 'Correct', exact: true })).toBeInViewport();
  await expect(panel.getByRole('button', { name: 'Cancel', exact: true })).toBeInViewport();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(async () => (await dimensions()).width).toBeLessThanOrEqual(350 - 44 - 32);
  await expect(panel.getByRole('button', { name: 'Cancel', exact: true })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

// The same adapter also runs against the real authenticated server in the browser suite.
import { pageContext, signIn } from './auth.fixture.mjs';
import { signals } from './grid.fixture.mjs';
const actionSignals = body => Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals ')).map(line => JSON.parse(line.slice(14))));
async function action(page, name, payload = {}) {
  const { csrf, version } = await pageContext(page);
  const response = await page.context().request.post(`/action/tover.stock.web/${name}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
    data: { view: 'stock', ...payload },
  });
  expect(response.status()).toBe(200);
  const body = await response.text(), result = actionSignals(body);
  expect(result.saveStatus, body).not.toBe('Failed'); return result;
}
async function opening(page, fields) {
  let result = await action(page, 'add-entry', { kind: 'opening' });
  let entry = result._inventorySnapshot.draftSelection.at(-1);
  for (const [path, value] of [...fields, ['reason', 'Inline history browser inventory']]) {
    result = await action(page, 'save-field', { ...entry, path, value });
    entry = result._inventorySnapshot.draftSelection.find(item => item.id === entry.id);
  }
  const preview = await action(page, 'preview', { selected: [entry.id] });
  return action(page, 'submit', { review: preview._review });
}

test('server history expands the chosen location row with exclusive views, refresh and realtime', async ({ page, browser, baseURL }) => {
  test.skip(!baseURL, 'Requires the application browser fixture');
  await signIn(page);
  const pending = await signals(page, '_draftSelection') || [];
  if (pending.length) await action(page, 'revert-drafts', { selected: pending });
  const name = `Inline row ${Date.now()}`;
  let result = await opening(page, [['lines/0/name', name], ['lines/0/quantity', '8'], ['lines/0/location', 'History A']]);
  const first = result._inventorySnapshot.rows.find(row => row.name === name);
  result = await opening(page, [['lines/0/item-id', first['item-id']], ['lines/0/quantity', '5'], ['lines/0/location', 'History B']]);
  const second = result._inventorySnapshot.rows.find(row => row.name === name && row.location === 'History B');
  expect(second['item-id']).toBe(first['item-id']);
  await page.reload();
  const grid = page.locator('#stock-grid'), body = grid.locator('table.jss_worksheet').first().locator(':scope > tbody');
  const row = id => body.locator(`:scope > tr[data-row-id="${id}"]`);
  await row(first.id).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  const panel = page.locator('#history-panel');
  const expectPlacement = async id => {
    // Responses can replace the panel while its owning grid briefly parks it before rendering.
    await expect.poll(() => panel.evaluate(node => node.closest('tr.inventory-row-detail')?.previousElementSibling?.dataset.rowId ?? null)).toBe(id);
  };
  await expect(panel).toBeVisible(); await expect(panel).toHaveAttribute('data-row-id', first.id);
  await row(first.id).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  await expect(body.locator(':scope > tr.inventory-row-detail')).toHaveCount(0); await expect(panel).toBeHidden();
  await expect(row(first.id).getByRole('button', { name: `History for ${name}`, exact: true })).toBeFocused();
  await row(first.id).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  await expect(panel).toBeVisible(); await expect(panel).toHaveAttribute('data-row-id', first.id);
  await expectPlacement(first.id);
  await expect(page.locator('#item-history-grid')).toBeVisible(); await expect(page.locator('.inventory-timeline')).toHaveCount(0);
  const acceptedDrafts = await signals(page, '_draftIds');
  await page.locator('#item-history-grid tbody td[data-column-key="actor"]').first().click();
  await page.keyboard.press('Delete'); await page.keyboard.press('Enter');
  expect(await signals(page, '_draftIds')).toEqual(acceptedDrafts);
  await panel.getByRole('tab', { name: 'Lineage', exact: true }).click();
  await expect(panel.getByRole('tab', { name: 'Lineage', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#item-history-grid')).toHaveCount(0); await expect(page.locator('.inventory-timeline')).toBeVisible();
  const bar = panel.locator('button.lineage-bar').last();
  await expect(bar).toContainText('Opening');
  const operationId = await bar.getAttribute('data-operation-id');
  await bar.click(); await expect(page.locator('#history-operation-details h3')).toContainText('Opening');
  const previousPanel = await panel.elementHandle();
  const refreshed = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/open-history');
  await panel.getByRole('button', { name: 'Refresh history', exact: true }).click();
  expect((await refreshed).status()).toBe(200);
  await expect.poll(() => previousPanel.evaluate(node => node.isConnected)).toBe(false);
  await expect(panel).toHaveAttribute('data-history-mode', 'lineage');
  await expect(page.locator('#history-operation-details')).toHaveAttribute('data-operation-id', operationId);
  await page.locator('#history-operation-details').getByRole('button', { name: 'Close operation details', exact: true }).click();
  await expect(panel.locator(`button.lineage-bar[data-operation-id="${operationId}"]`).last()).toBeFocused();
  await page.screenshot({ path: '/tmp/tover-refine-history-lineage-light.png', fullPage: true });
  const dark = page.getByRole('button', { name: 'Switch to dark mode', exact: true });
  if (await dark.isVisible()) await dark.click();
  await page.screenshot({ path: '/tmp/tover-refine-history-lineage-dark.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => panel.evaluate(node => parseFloat(node.style.maxWidth))).toBeLessThan(390);
  await page.screenshot({ path: '/tmp/tover-refine-history-lineage-mobile.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await grid.getByRole('button', { name: 'Sort by Goods', exact: true }).click();
  await expect(panel).toBeVisible(); await expectPlacement(first.id);
  await row(second.id).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  await expect(panel).toHaveAttribute('data-row-id', second.id); await expect(panel).toHaveAttribute('data-history-mode', 'history');
  await expectPlacement(second.id);
  let releaseDelayed, delayedReady, delayedDone;
  const delayed = new Promise(resolve => { releaseDelayed = resolve; });
  const ready = new Promise(resolve => { delayedReady = resolve; });
  const delivered = new Promise(resolve => { delayedDone = resolve; });
  const intercept = async route => {
    if (route.request().postDataJSON().rowId !== first.id) return route.continue();
    const response = await route.fetch(); delayedReady(); await delayed;
    await route.fulfill({ response }); delayedDone();
  };
  await page.route('**/action/tover.stock.web/open-history', intercept);
  await row(first.id).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  await ready;
  await row(second.id).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  await expect(panel).toBeHidden(); await expect(body.locator(':scope > tr.inventory-row-detail')).toHaveCount(0);
  releaseDelayed(); await delivered;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(panel).toBeHidden(); await expect(body.locator(':scope > tr.inventory-row-detail')).toHaveCount(0);
  await row(second.id).getByRole('button', { name: `History for ${name}`, exact: true }).click();
  await expect(panel).toBeVisible();
  await expect(panel).toHaveAttribute('data-row-id', second.id);
  await expectPlacement(second.id);
  await page.unroute('**/action/tover.stock.web/open-history', intercept);
  const memberContext = await browser.newContext({ baseURL });
  try {
    const member = await memberContext.newPage(); await signIn(member, 'member@example.test');
    const snapshot = await signals(member, '_inventorySnapshot');
    const target = snapshot.rows.find(item => item.id === first.id);
    await action(member, 'edit-stock', { rowId: first.id, field: 'quantity', value: '9', ledgerRevision: target['ledger-revision'] });
    await expect(row(first.id).locator('td[data-column-key="quantity"] .inventory-cell-value')).toHaveText('9');
    await expect(panel).toBeVisible(); await expectPlacement(second.id);
  } finally { await memberContext.close(); }
  await panel.getByRole('button', { name: 'Close history', exact: true }).click();
  await expect(body.locator(':scope > tr.inventory-row-detail')).toHaveCount(0);
  await expect(row(second.id).getByRole('button', { name: `History for ${name}`, exact: true })).toBeFocused();
  await expect(page.locator('#history-parking #history-panel')).toHaveCount(1);
  await row(first.id).getByRole('button', { name: `History for ${name}`, exact: true }).click(); await expect(panel).toBeVisible();
  await grid.getByRole('button', { name: 'Filter Location', exact: true }).click();
  await grid.getByRole('combobox', { name: 'Add condition', exact: true }).selectOption('eq');
  const filter = grid.getByRole('textbox', { name: 'Value 1', exact: true }); await filter.fill('History B'); await filter.press('Enter');
  await expect(row(first.id)).toHaveCount(0); await expect(body.locator(':scope > tr.inventory-row-detail')).toHaveCount(0);
});

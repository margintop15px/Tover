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
      response.end(`<!doctype html><html><head><link rel="stylesheet" href="/inventory-grid.css"><style>
        :root{--iw-bg:#1d2125;--iw-soft:#252a30;--iw-line:#3a4149;--iw-ink:#eceff4;--iw-muted:#a0a7b1;--iw-blue:#82a5cc}
        *{box-sizing:border-box}body{margin:0;background:var(--iw-bg);color:var(--iw-ink);font:13px system-ui}header{height:100px;padding:20px}
        main{width:100%;min-width:0}#history-parking{display:none}#history-panel{padding:16px;background:var(--iw-bg)}
        .operation-details{padding:10px}.operation-details-meta{display:flex;gap:12px;flex-wrap:wrap}.operation-meta-field{max-width:260px}dd{margin:0}
        </style><script type="module" src="/stock-operations.js"></script></head><body><header>Viewport regression fixture</header><main></main><div id="history-parking" hidden></div></body></html>`);
      return;
    }
    try {
      const path = new URL(`.${request.url}`, publicRoot), body = await readFile(fileURLToPath(path));
      response.setHeader('content-type', path.pathname.endsWith('.css') ? 'text/css' : 'text/javascript'); response.end(body);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => { await new Promise(resolve => server.close(resolve)); });

async function mount(page, kind = 'stock', start = 0) {
  await page.setViewportSize({ width: 1050, height: 760 });
  await page.goto(origin);
  await page.evaluate(async ({ kind, start }) => {
    await customElements.whenDefined('stock-operations');
    const operations = kind === 'operations';
    const host = document.createElement(operations ? 'stock-operations' : 'inventory-grid'); host.id = 'viewport-host';
    const editable = (id, path, value) => ({ value, target: { id, path, revision: 1 }, editor: { type: path === 'quantity' ? 'quantity' : 'text' } });
    const columns = operations ? [
      { key: 'type', label: 'Operation', width: 180, sortKey: 'type' }, { key: 'date', label: 'Date', width: 200, sortKey: 'date' },
      { key: 'party', label: 'Party', width: 160 }, { key: 'name', label: 'Goods', width: 240 },
      { key: 'quantity', label: 'Quantity', width: 120 }, { key: 'reason', label: 'Reason', width: 260 },
    ] : [
      { key: 'name', label: 'Goods', width: 260, sortKey: 'name' }, { key: 'quantity', label: 'Quantity', width: 120 },
      { key: 'location', label: 'Location', width: 220 }, { key: 'article', label: 'Article', width: 220 }, { key: 'barcode', label: 'Barcode', width: 240 },
    ];
    const groups = [], allRows = [];
    for (let groupIndex = 0; groupIndex < 10000; groupIndex++) {
      const id = `${kind}-${groupIndex}`, count = operations ? groupIndex % 3 + 1 : 1;
      const type = count > 1 ? 'Production' : 'Purchase';
      const group = { id, groupIndex, start: allRows.length, count, operation: type }; groups.push(group);
      for (let line = 0; line < count; line++) {
        const rowIndex = allRows.length, rowId = operations ? `${id}/${line}` : id;
        allRows.push({ id: rowId, groupId: id, rowIndex, groupIndex, cells: operations ? [
          line ? {} : { value: type, action: { type: 'operation-details', id, label: `Show operation ${groupIndex} details`, icon: '›' } },
          line ? {} : { value: '23 Oct 2026, 12:00' }, line ? {} : { value: `Party ${groupIndex}` },
          { value: `Goods ${groupIndex} line ${line}`, className: count > 1 ? (line ? 'inventory-production-output' : 'inventory-production-input') : '' },
          { value: line + 1 }, line ? {} : { value: `Reason ${groupIndex}` },
        ] : [
          { ...editable(rowId, 'name', `Goods ${groupIndex}`), action: { type: 'item-history', rowId, itemId: `item-${groupIndex}`, label: `History ${groupIndex}`, icon: '›' } },
          editable(rowId, 'quantity', groupIndex + 1), { value: `Location ${groupIndex % 3}` }, { value: `ART-${groupIndex}` }, { value: `BAR-${groupIndex}` },
        ] });
      }
    }
    window.fixture = { kind, host, groups, allRows, writes: [], requests: [], details: [], automatic: true, holdDetails: false, responses: [], queryId: `${kind}-query` };
    const makeWindow = (offset, count = 200) => {
      offset = Math.max(0, Math.min(Number(offset), allRows.length - 1));
      const first = allRows[offset].groupIndex, from = groups[first].start;
      let last = first;
      while (last < groups.length - 1 && groups[last].start + groups[last].count < from + count) last++;
      const end = groups[last].start + groups[last].count, rows = structuredClone(allRows.slice(from, end)), merges = [];
      if (operations) for (const group of groups.slice(first, last + 1)) if (group.count > 1) {
        for (const col of [0, 1, 2, 5]) merges.push({ row: group.start - from, col, rowspan: group.count });
      }
      return { id: kind, editable: !operations, columns, rows, merges,
        viewport: { queryId: window.fixture.queryId, queryKey: `${kind}-filtered`, totalRows: allRows.length, totalGroups: groups.length,
          start: from, end, groupStart: first, windowSize: 200 } };
    };
    window.fixture.makeWindow = makeWindow;
    const reply = (request, options = {}) => {
      const target = operations ? host : host;
      const anchorIndex = request.anchorId ? allRows.findIndex(row => row.id === request.anchorId || row.groupId === request.anchorId) : -1;
      const model = options.model || makeWindow(anchorIndex >= 0 ? anchorIndex : request.start, request.count);
      model.editStates = structuredClone(allRows.filter(row => (request.pendingRows || []).includes(row.id)).flatMap(row => row.cells.filter(cell => cell.target)));
      const value = { status: 'ok', queryId: request.queryId, requestId: request.requestId, model, ...options };
      window.fixture.responses.push(value); target.windowResponse = value;
    };
    window.fixture.reply = reply;
    host.addEventListener('inventory-window', event => {
      const request = structuredClone(event.detail); window.fixture.requests.push(request);
      if (window.fixture.automatic) queueMicrotask(() => reply(request));
    });
    host.addEventListener('operation-detail-request', event => {
      const request = structuredClone(event.detail); window.fixture.details.push(request);
      if (!window.fixture.holdDetails) queueMicrotask(() => {
        const group = groups.find(group => group.id === request.operationId);
        host.operationDetailResponse = { ...request, entry: { id: group.id, operation: group.operation },
          detail: { 'summary-metadata': [{ label: 'Recorded at', value: '23 Oct 2026, 12:01' }, { label: 'Submitted by', value: 'Tester' },
            { label: 'Reason', value: `Accepted reason for ${group.id}` }] } };
      });
    });
    host.addEventListener('draft-edit', event => window.fixture.writes.push(structuredClone(event.detail)));
    host.addEventListener('inventory-resnapshot', event => window.fixture.resnapshot = event.detail);
    host.model = makeWindow(start); document.querySelector('main').append(host);
    if (operations) host.entries = groups.filter(group => group.start >= host.model.viewport.start && group.start < host.model.viewport.end);
    const grid = operations ? host.grid : host; window.fixture.grid = grid;
    await grid.whenReady;
  }, { kind, start });
  await expect.poll(() => page.evaluate(() => Boolean(window.fixture.grid.worksheet))).toBe(true);
}
const grid = page => page.locator('#viewport-host').locator('inventory-grid').or(page.locator('inventory-grid#viewport-host')).first();
const body = page => grid(page).locator(':scope > .inventory-grid-sheet table.jss_worksheet > tbody');
const content = page => grid(page).locator(':scope > .inventory-grid-sheet .jss_content').first();
const cell = (page, id, key) => body(page).locator(`:scope > tr[data-row-id="${id}"] > td[data-column-key="${key}"]`);
async function jump(page, fraction) {
  await content(page).evaluate((node, fraction) => { node.scrollTop = (node.scrollHeight - node.clientHeight) * fraction; node.dispatchEvent(new Event('scroll')); }, fraction);
}
async function rowsAt(page) { return grid(page).evaluate(node => node.model.rows.filter(row => row.rowIndex != null).map(row => row.rowIndex)); }
async function anchor(page) {
  return grid(page).evaluate(node => {
    const viewport = node.querySelector('.jss_content').getBoundingClientRect();
    const row = [...node.querySelectorAll(':scope > .inventory-grid-sheet tbody > tr[data-row-id]')]
      .find(row => row.getBoundingClientRect().bottom > viewport.top + 34 && row.getBoundingClientRect().top < viewport.bottom);
    return row && { id: row.dataset.rowId, offset: row.getBoundingClientRect().top - viewport.top };
  });
}

for (const kind of ['stock', 'operations']) test(`${kind}: 10,000 records load bounded complete windows at beginning, middle, end and reverse`, async ({ page }) => {
  await mount(page, kind);
  await expect.poll(() => content(page).evaluate(node => node.scrollHeight)).toBeGreaterThan(300000);
  expect(await content(page).evaluate(node => node.clientHeight)).toBeLessThanOrEqual(660);
  const inspect = async fraction => {
    await jump(page, fraction);
    await expect.poll(async () => {
      const rows = await rowsAt(page), total = await page.evaluate(() => window.fixture.allRows.length);
      return rows.length && (fraction === 1 ? rows.at(-1) === total - 1 : fraction === 0 ? rows[0] === 0 : rows.some(row => Math.abs(row - fraction * total) < 230));
    }).toBe(true);
    const state = await page.evaluate(() => {
      const { grid, groups } = window.fixture;
      return { count: grid.model.rows.length, first: grid.model.rows[0], last: grid.model.rows.at(-1),
        firstGroup: groups[grid.model.rows[0].groupIndex], lastGroup: groups[grid.model.rows.at(-1).groupIndex] };
    });
    expect(state.count).toBeLessThanOrEqual(204);
    expect(state.first.rowIndex).toBe(state.firstGroup.start);
    expect(state.last.rowIndex).toBe(state.lastGroup.start + state.lastGroup.count - 1);
    expect(await body(page).locator(':scope > tr').count()).toBeLessThanOrEqual(207);
    const gutter = body(page).locator(`:scope > tr[data-row-id="${state.first.id}"] > td.jss_row`);
    await expect(gutter).toHaveText(String(state.first.groupIndex + 1));
    if (kind === 'operations') {
      expect(await gutter.evaluate(node => node.rowSpan)).toBe(state.firstGroup.count);
      if (state.firstGroup.count > 1) await expect(cell(page, state.first.id, 'type')).toHaveAttribute('rowspan', String(state.firstGroup.count));
    }
    const dimensions = await content(page).evaluate(node => ({ top: node.scrollTop, height: node.scrollHeight, client: node.clientHeight }));
    if (fraction === 1) expect(dimensions.height - dimensions.client - dimensions.top).toBeLessThan(80);
  };
  for (const fraction of [0, 0.5, 1, 0.25, 0]) await inspect(fraction);
  const requests = await page.evaluate(() => window.fixture.requests);
  expect(requests.length).toBeGreaterThanOrEqual(3);
  for (const request of requests) expect(request).toMatchObject({ queryId: `${kind}-query`, count: 200, requestId: expect.anything() });
});

test('actual Operations expansion and collapse preserve the scrolled row, horizontal offset and viewport height', async ({ page }) => {
  await mount(page, 'operations'); await jump(page, 0.5);
  await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(9000);
  await content(page).evaluate(node => { node.scrollLeft = 20; });
  const before = await anchor(page), height = await content(page).evaluate(node => node.clientHeight);
  const clickableId = await grid(page).evaluate(node => {
    const bounds = node.querySelector('.jss_content').getBoundingClientRect();
    return node._owned('tbody > tr[data-row-id]').find(row => row.querySelector('.inventory-cell-action') && row.getBoundingClientRect().top >= bounds.top + 34)?.dataset.rowId;
  });
  const button = cell(page, clickableId, 'type').getByRole('button');
  await button.click({ position: { x: 12, y: 12 } });
  await expect.poll(() => page.evaluate(() => window.fixture.details.length)).toBe(1);
  await expect(body(page).locator('td.inventory-operation-details-cell')).toHaveCount(1);
  await expect(body(page).locator('td.inventory-operation-details-cell')).toContainText('Accepted reason');
  await expect.poll(() => anchor(page)).toEqual(before);
  expect(await content(page).evaluate(node => node.scrollLeft)).toBe(20);
  expect(await content(page).evaluate(node => node.clientHeight)).toBe(height);
  await button.click({ position: { x: 12, y: 12 } });
  await expect(body(page).locator('td.inventory-operation-details-cell')).toHaveCount(0);
  await expect.poll(() => anchor(page)).toEqual(before);
  expect(await content(page).evaluate(node => node.scrollLeft)).toBe(20);
  expect(await page.evaluate(() => window.fixture.details.length)).toBe(1);
});

test('late windows cannot replace the current position; failed reads retain rows and retry, expired queries reopen', async ({ page }) => {
  await mount(page);
  await page.evaluate(() => { window.fixture.automatic = false; });
  await jump(page, 0.35);
  await expect.poll(() => page.evaluate(() => window.fixture.requests.length)).toBe(1);
  const first = await page.evaluate(() => window.fixture.requests.at(-1));
  await jump(page, 0.8);
  await expect.poll(() => page.evaluate(() => window.fixture.requests.length)).toBe(2);
  const second = await page.evaluate(() => window.fixture.requests.at(-1));
  await page.evaluate(second => window.fixture.reply(second), second);
  await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(7500);
  const current = await rowsAt(page);
  await page.evaluate(first => window.fixture.reply(first), first);
  expect(await rowsAt(page)).toEqual(current);
  await jump(page, 0.1);
  await expect.poll(() => page.evaluate(() => window.fixture.requests.length)).toBe(3);
  await page.evaluate(() => window.fixture.reply(window.fixture.requests.at(-1), { status: 'failed' }));
  expect(await rowsAt(page)).toEqual(current);
  await grid(page).getByRole('button', { name: /retry/i }).click();
  await expect.poll(() => page.evaluate(() => window.fixture.requests.length)).toBe(4);
  await page.evaluate(() => window.fixture.reply(window.fixture.requests.at(-1)));
  await expect.poll(async () => (await rowsAt(page))[0]).toBeLessThan(1500);
  await jump(page, 0.5);
  await expect.poll(() => page.evaluate(() => window.fixture.requests.length)).toBe(5);
  await page.evaluate(() => window.fixture.reply(window.fixture.requests.at(-1), { status: 'expired' }));
  await expect.poll(() => page.evaluate(() => Boolean(window.fixture.resnapshot))).toBe(true);
});

test('inline history keeps its panel and view while unloaded, restores beneath its row and measures growing details', async ({ page }) => {
  await mount(page);
  await page.evaluate(() => {
    const { grid } = window.fixture, panel = document.createElement('section'); panel.id = 'history-panel'; panel.style.height = '260px';
    panel.innerHTML = '<button id="mode">Lineage</button><input id="history-note" value="Focused history" />';
    panel.dataset.mode = 'lineage'; document.querySelector('#history-parking').append(panel);
    window.fixture.panel = panel;
    grid.rowDetail = { rowId: 'stock-4', node: panel };
    grid.addEventListener('inventory-row-detail-close', event => { window.fixture.closed = event.detail; });
  });
  await expect(body(page).locator(':scope > tr[data-detail-for="stock-4"]')).toHaveCount(1);
  const initialScroll = await content(page).evaluate(node => node.scrollHeight);
  await page.locator('#history-panel').evaluate(panel => { panel.style.height = '460px'; });
  await expect.poll(() => content(page).evaluate(node => node.scrollHeight)).toBeGreaterThan(initialScroll + 190);
  await jump(page, 0.7); await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(6500);
  expect(await page.evaluate(() => window.fixture.closed)).toBeUndefined();
  await expect(page.locator('#history-parking #history-panel')).toHaveCount(1);
  await jump(page, 0); await expect.poll(async () => (await rowsAt(page))[0]).toBe(0);
  await expect(body(page).locator(':scope > tr[data-detail-for="stock-4"]')).toHaveCount(1);
  expect(await page.evaluate(() => document.querySelector('#history-panel') === window.fixture.panel)).toBe(true);
  await expect(page.locator('#history-panel')).toHaveAttribute('data-mode', 'lineage');
});

test('active input survives window responses and offscreen writes remain retained until an authoritative acknowledgment', async ({ page }) => {
  await mount(page);
  await cell(page, 'stock-3', 'quantity').click();
  const editor = grid(page).locator('.inventory-cell-editor > input');
  await editor.fill('41');
  await page.evaluate(() => {
    const { grid, makeWindow } = window.fixture; window.fixture.editor = document.activeElement;
    grid.model = { ...makeWindow(0), label: 'Broadcast during typing' };
  });
  await expect(editor).toHaveValue('41'); await expect(editor).toBeFocused();
  expect(await page.evaluate(() => document.activeElement === window.fixture.editor)).toBe(true);
  await editor.press('Enter');
  expect(await page.evaluate(() => window.fixture.writes)).toEqual([{ id: 'stock-3', path: 'quantity', revision: 1, value: '41' }]);
  await jump(page, 0.6); await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(5500);
  expect(await grid(page).evaluate(node => node._attempts.size)).toBe(1);
  await page.evaluate(() => {
    const row = window.fixture.allRows[3]; row.cells[1] = { ...row.cells[1], value: 41, target: { ...row.cells[1].target, revision: 2 } };
    window.fixture.queryId = 'stock-query-ack';
    window.fixture.grid.model = window.fixture.makeWindow(0);
  });
  await expect.poll(() => grid(page).evaluate(node => node._attempts.size)).toBe(0);
  await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(5500);
  await jump(page, 0); await expect.poll(async () => (await rowsAt(page))[0]).toBe(0);
  await expect(cell(page, 'stock-3', 'quantity')).toHaveText('41');
  await expect.poll(() => grid(page).evaluate(node => node._attempts.size)).toBe(0);
});

test('Changes to submit and nested history stay natural height when accepted tables use a viewport', async ({ page }) => {
  await mount(page);
  await page.evaluate(async () => {
    for (const kind of ['shared-draft', 'item-history']) {
      const table = document.createElement('inventory-grid'); table.id = kind;
      table.model = { id: kind, editable: false, columns: [{ key: 'name', label: 'Record', width: 300 }],
        rows: Array.from({ length: 60 }, (_, index) => ({ id: `${kind}-${index}`, cells: [{ value: `Record ${index}` }] })), merges: [] };
      document.body.append(table); await table.whenReady;
    }
  });
  for (const kind of ['shared-draft', 'item-history']) {
    const table = page.locator(`inventory-grid#${kind}`);
    await expect.poll(() => table.evaluate(node => node.clientHeight)).toBeGreaterThan(1900);
    expect(await table.locator('.jss_content').evaluate(node => node.scrollHeight - node.clientHeight)).toBeLessThanOrEqual(1);
    await expect(table.locator('tr.inventory-virtual-spacer')).toHaveCount(0);
  }
});

test('keyboard navigation loads adjacent windows and preserves the logical column', async ({ page }) => {
  await mount(page);
  await grid(page).evaluate(node => {
    const y = node._rows.findIndex(row => row.id === 'stock-199');
    node.focus({ preventScroll: true }); node.worksheet.updateSelectionFromCoords(1, y, 1, y);
  });
  await page.keyboard.press('ArrowDown');
  await expect.poll(() => grid(page).evaluate(node => {
    const [x, y] = node.worksheet.selectedCell.map(Number); return [node._rows[y]?.id, node.model.columns[x]?.key];
  })).toEqual(['stock-200', 'quantity']);
  await page.keyboard.press('Enter');
  const editor = grid(page).locator('.inventory-cell-editor > input');
  await expect(editor).toHaveValue('201'); await editor.press('Escape');
  const firstIndex = await grid(page).evaluate(node => node.model.rows[0].rowIndex);
  await grid(page).evaluate(node => {
    const y = node._rows.findIndex(row => row.id === node.model.rows[0].id);
    node.focus({ preventScroll: true }); node.worksheet.updateSelectionFromCoords(1, y, 1, y);
  });
  await page.keyboard.press('ArrowUp');
  await expect.poll(() => grid(page).evaluate(node => {
    const [x, y] = node.worksheet.selectedCell.map(Number); return [node._rows[y]?.id, node.model.columns[x]?.key];
  })).toEqual([`stock-${firstIndex - 1}`, 'quantity']);
});

test('Operations expansion uses the current cached window after reverse scrolling and rejects a late detail response', async ({ page }) => {
  await mount(page, 'operations');
  await jump(page, 0.5); await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(9000);
  await jump(page, 0); await expect.poll(async () => (await rowsAt(page))[0]).toBe(0);
  await page.evaluate(() => { window.fixture.holdDetails = true; });
  const button = cell(page, 'operations-1/0', 'type').getByRole('button');
  await button.click();
  await expect.poll(() => page.evaluate(() => window.fixture.details.length)).toBe(1);
  expect((await rowsAt(page))[0]).toBe(0);
  await button.click();
  const request = await page.evaluate(() => window.fixture.details[0]);
  await page.evaluate(request => {
    window.fixture.host.operationDetailResponse = { ...request, entry: { id: request.operationId, operation: 'Production' },
      detail: { 'summary-metadata': [{ label: 'Reason', value: 'Late hidden details' }] } };
  }, request);
  await expect(body(page).locator('td.inventory-operation-details-cell')).toHaveCount(0);
  expect((await rowsAt(page))[0]).toBe(0);
});

test('same-query initial rebinding cannot reset loaded windows and the window cache stays bounded', async ({ page }) => {
  await mount(page, 'operations');
  for (const fraction of [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7]) {
    await jump(page, fraction);
    await expect.poll(async () => (await rowsAt(page)).some(row => Math.abs(row - fraction * 19999) < 230)).toBe(true);
  }
  const beforeRows = await rowsAt(page), before = await anchor(page);
  await page.evaluate(() => { window.fixture.host.model = window.fixture.makeWindow(0); });
  expect(await rowsAt(page)).toEqual(beforeRows);
  expect(await anchor(page)).toEqual(before);
  expect(await grid(page).evaluate(node => node._windowCache.size)).toBeLessThanOrEqual(5);
});

test('failed operation details retry and expired details request a new authorized snapshot', async ({ page }) => {
  await mount(page, 'operations');
  await page.evaluate(() => { window.fixture.holdDetails = true; });
  await cell(page, 'operations-1/0', 'type').getByRole('button').click();
  await expect.poll(() => page.evaluate(() => window.fixture.details.length)).toBe(1);
  await page.evaluate(() => {
    const request = window.fixture.details.at(-1); window.fixture.host.operationDetailResponse = { ...request, status: 'failed' };
  });
  await expect(grid(page).getByText('Could not load operation.')).toBeVisible();
  await grid(page).getByRole('button', { name: 'Retry loading operation', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.fixture.details.length)).toBe(2);
  await page.evaluate(() => {
    const request = window.fixture.details.at(-1); window.fixture.host.operationDetailResponse = { ...request, status: 'expired' };
  });
  await expect.poll(() => page.evaluate(() => window.fixture.resnapshot)).toMatchObject({ queryId: 'operations-query' });
});

test('retained failed edits can locate their row and explicit removal preserves the attempted value', async ({ page }) => {
  await mount(page);
  await cell(page, 'stock-3', 'quantity').click();
  const input = grid(page).locator('.inventory-cell-editor > input');
  await input.fill('55'); await input.press('Enter');
  await grid(page).evaluate(node => { node.saveStatus = 'Failed'; });
  await jump(page, 0.7); await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(6500);
  await expect(grid(page).locator('.inventory-grid-orphans')).toContainText('55');
  await grid(page).getByRole('button', { name: 'Show retained edit', exact: true }).click();
  await expect(cell(page, 'stock-3', 'quantity')).toHaveAttribute('data-save-state', 'failed');
  await expect(input).toHaveValue('55'); await input.press('Escape');
  await jump(page, 0.7); await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(6500);
  await grid(page).evaluate(node => { node.model = { ...node.model, missingRows: ['stock-3'], editStates: (node.model.editStates || []).filter(cell => cell.target.id !== 'stock-3') }; });
  await expect(grid(page).locator('.inventory-grid-orphans')).toContainText('no longer in the current view');
  await expect(grid(page).locator('.inventory-grid-orphans')).toContainText('55');
  await expect(grid(page).getByRole('button', { name: 'Show retained edit', exact: true })).toHaveCount(0);
  expect(await grid(page).evaluate(node => node._attempts.size)).toBe(1);
});

test('a live snapshot waits until native resize finishes without changing other columns or scroll position', async ({ page }) => {
  await mount(page); await jump(page, 0.5);
  await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(4500);
  const before = await anchor(page), table = grid(page);
  const headers = table.locator('thead td[data-column-key]');
  const widths = () => headers.evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().width));
  const previous = await widths(), header = table.locator('thead td[data-column-key="quantity"]'), bounds = await header.boundingBox();
  await page.mouse.move(bounds.x + bounds.width - 2, bounds.y + 15); await page.mouse.down();
  await page.mouse.move(bounds.x + bounds.width + 35, bounds.y + 15, { steps: 5 });
  await table.evaluate(node => {
    window.fixture.resizingWorksheet = node.worksheet;
    node.model = { ...node.model, label: 'Latest broadcast while resizing', rows: node.model.rows.map((row, index) => index ? row : { ...row, cells: row.cells.map((cell, col) => col ? cell : { ...cell, value: 'Latest server goods' }) }) };
  });
  expect(await table.evaluate(node => node.worksheet === window.fixture.resizingWorksheet)).toBe(true);
  await page.mouse.up();
  await expect.poll(async () => (await widths())[1]).toBeCloseTo(previous[1] + 37, 0);
  for (const index of [0, 2, 3, 4]) expect((await widths())[index]).toBe(previous[index]);
  expect(await anchor(page)).toEqual(before);
  await expect(table.locator('tbody td[data-column-key="name"]').filter({ hasText: 'Latest server goods' })).toHaveCount(1);
});

test('real wheel scrolling crosses and reverses the buffered window edge', async ({ page }) => {
  await mount(page);
  expect(await cell(page, 'stock-0', 'quantity').evaluate(node => node.parentElement.getBoundingClientRect().height)).toBe(32);
  const bounds = await content(page).boundingBox();
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + 120);
  await page.mouse.wheel(0, 32 * 195);
  await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(100);
  await expect.poll(() => content(page).evaluate(node => node.scrollTop)).toBeGreaterThan(32 * 180);
  const middle = await anchor(page);
  expect(Number(middle.id.split('-').at(-1))).toBeGreaterThanOrEqual(190);
  await page.mouse.wheel(0, -32 * 170);
  await expect.poll(async () => (await rowsAt(page))[0]).toBe(0);
  await expect.poll(() => content(page).evaluate(node => node.scrollTop)).toBeLessThan(32 * 35);
  expect(await page.evaluate(() => window.fixture.requests.length)).toBeGreaterThanOrEqual(1);
});

test('a header drag keeps its pressed header through broadcasts and reorders stable edit targets', async ({ page }) => {
  await mount(page); await jump(page, 0.5);
  await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(4500);
  const table = grid(page), before = await anchor(page);
  await table.evaluate(node => {
    window.fixture.layouts = [];
    node.addEventListener('inventory-layout', event => window.fixture.layouts.push(structuredClone(event.detail)));
  });
  const source = await table.locator('thead td[data-column-key="quantity"] .inventory-column-label').boundingBox();
  const target = await table.locator('thead td[data-column-key="name"]').boundingBox();
  await page.mouse.move(source.x + source.width / 2, source.y + 10); await page.mouse.down();
  await table.evaluate(node => {
    window.fixture.pressedWorksheet = node.worksheet;
    window.fixture.pressedHeader = node.querySelector('thead td[data-column-key="quantity"] .inventory-column-label');
    node.model = { ...node.model, label: 'Broadcast during header press' };
  });
  expect(await table.evaluate(node => node.worksheet === window.fixture.pressedWorksheet && window.fixture.pressedHeader.isConnected)).toBe(true);
  await page.mouse.move(source.x + source.width / 2 + 8, source.y + 10, { steps: 2 });
  await page.mouse.move(target.x + 4, target.y + 15, { steps: 6 });
  await page.mouse.move(target.x + 5, target.y + 15); await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.fixture.layouts)).toEqual([{ gridId: 'stock', order: ['quantity', 'name', 'location', 'article', 'barcode'] }]);
  await table.evaluate(node => { node.columnLayout = { widths: {}, order: ['quantity', 'name', 'location', 'article', 'barcode'], version: 1 }; });
  await expect.poll(() => table.locator('thead td[data-column-key]').evaluateAll(nodes => nodes.map(node => node.dataset.columnKey))).toEqual(['quantity', 'name', 'location', 'article', 'barcode']);
  expect(await anchor(page)).toEqual(before);
  const rowId = await table.evaluate(node => {
    const bounds = node.querySelector('.jss_content').getBoundingClientRect();
    return node._owned('tbody > tr[data-row-id]').find(row => !row.dataset.spacer && row.getBoundingClientRect().top >= bounds.top + 34)?.dataset.rowId;
  });
  await cell(page, rowId, 'quantity').click();
  const editor = table.locator('.inventory-cell-editor > input'); await editor.fill('72'); await editor.press('Enter');
  expect(await page.evaluate(() => window.fixture.writes)).toEqual([{ id: rowId, path: 'quantity', revision: 1, value: '72' }]);
});

test('Operations keyboard navigation skips the final merged group and its details when loading the next window', async ({ page }) => {
  await mount(page, 'operations');
  const boundary = await page.evaluate(() => {
    const { host } = window.fixture, last = host.model.rows.at(-1);
    const nextGroup = last.groupIndex + 1;
    host.expanded.add(last.groupId); host.renderRows();
    return { groupId: last.groupId, nextGroup };
  });
  await expect(body(page).locator('td.inventory-operation-details-cell')).toContainText('Accepted reason');
  await grid(page).evaluate((node, groupId) => {
    const y = node._rows.findIndex(row => row.groupId === groupId);
    node.focus({ preventScroll: true }); node.worksheet.updateSelectionFromCoords(0, y, 0, y);
  }, boundary.groupId);
  await page.keyboard.press('ArrowDown');
  await expect.poll(() => grid(page).evaluate(node => {
    const [x, y] = node.worksheet.selectedCell.map(Number);
    return [node._rows[y]?.groupId, node.model.columns[x]?.key];
  })).toEqual([`operations-${boundary.nextGroup}`, 'type']);
});

test('a delayed window response preserves continued real wheel movement within the pending request', async ({ page }) => {
  await mount(page); await page.evaluate(() => { window.fixture.automatic = false; });
  const bounds = await content(page).boundingBox();
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + 120);
  await page.mouse.wheel(0, 32 * 170);
  await expect.poll(() => page.evaluate(() => window.fixture.requests.length)).toBe(1);
  const request = await page.evaluate(() => window.fixture.requests[0]);
  const start = await content(page).evaluate(node => node.scrollTop);
  await page.mouse.wheel(0, 12);
  await expect.poll(() => content(page).evaluate(node => node.scrollTop)).toBeGreaterThanOrEqual(start + 12);
  const continued = await content(page).evaluate(node => node.scrollTop);
  expect(await page.evaluate(() => window.fixture.requests.length)).toBe(1);
  await page.evaluate(request => window.fixture.reply(request), request);
  await expect.poll(async () => (await rowsAt(page))[0]).toBeGreaterThan(0);
  await expect.poll(() => content(page).evaluate(node => node.scrollTop)).toBe(continued);
});

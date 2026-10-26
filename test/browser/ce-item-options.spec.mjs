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
      response.end('<!doctype html><html><head><link rel="stylesheet" href="/inventory-grid.css"><style>:root{--iw-bg:white;--iw-soft:#eee;--iw-line:#ccc;--iw-ink:#222;--iw-muted:#777;--iw-blue:#82a5cc}body{margin:20px}</style><script type="module" src="/inventory-grid.js"></script></head><body><button id="outside">Outside</button></body></html>');
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

async function mount(page, known = false) {
  await page.goto(origin);
  await page.evaluate(async known => {
    await customElements.whenDefined('inventory-grid');
    const grid = document.createElement('inventory-grid'); grid.id = 'item-options';
    const row = number => ({ id: `row-${number}`, cells: [{ value: known ? `Known ${number}` : '',
      target: { id: `draft-${number}`, path: 'lines/line-1/name', revision: 4 },
      itemTarget: { id: `draft-${number}`, path: 'lines/line-1/item-id', revision: 4 },
      editor: { type: 'goods', options: known ? [{ value: `known-${number}`, label: `Known ${number}` }] : [] } },
    { value: number, target: { id: `draft-${number}`, path: 'lines/line-1/quantity', revision: 4 }, editor: { type: 'quantity' } }] });
    grid.model = { id: 'shared-draft', columns: [{ key: 'name', label: 'Goods', width: 350 }, { key: 'quantity', label: 'Quantity', width: 120 }], rows: [row(1), row(2)], merges: [] };
    window.fixture = { grid, requests: [], writes: [] };
    grid.addEventListener('inventory-item-options', event => window.fixture.requests.push(structuredClone(event.detail)));
    grid.addEventListener('draft-edit', event => window.fixture.writes.push(structuredClone(event.detail)));
    document.body.append(grid); await grid.whenReady;
  }, known);
}
const goods = (page, row = 1) => page.locator(`#item-options tr[data-row-id="row-${row}"] td[data-column-key="name"]`);
const input = page => page.locator('#item-options .inventory-cell-editor > input');
const picker = page => page.getByLabel('Choose existing item', { exact: true });
const requests = page => page.evaluate(() => window.fixture.requests);
const writes = page => page.evaluate(() => window.fixture.writes);
const reply = (page, request, options, status = 'ok') => page.evaluate(({ request, options, status }) => {
  window.fixture.grid.itemOptionsResponse = { requestId: request.requestId, options, status };
}, { request, options, status });

test('empty goods picker fetches bounded choices and selecting an item saves exactly one stable target', async ({ page }) => {
  await mount(page); await goods(page).click();
  await expect(picker(page)).toBeVisible();
  await expect.poll(async () => (await requests(page)).length).toBe(1);
  const [request] = await requests(page);
  expect(request.text).toBe('');
  expect(request.target).toEqual({ id: 'draft-1', path: 'lines/line-1/item-id', revision: 4 });
  expect(await writes(page)).toEqual([]);
  await reply(page, request, Array.from({ length: 40 }, (_, number) => ({ value: `item-${number}`, label: `Item ${number}` })));
  await expect(picker(page).locator('option')).toHaveCount(31);
  expect(await writes(page)).toEqual([]);
  await picker(page).click(); await picker(page).selectOption('item-2');
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  expect(await writes(page)).toEqual([{ ...request.target, value: 'item-2' }]);
  await expect(input(page)).toHaveCount(0);
});

test('typing requests server prefixes and rejects replies for earlier text or a closed editor', async ({ page }) => {
  await mount(page); await goods(page).click();
  const [opening] = await requests(page);
  await input(page).fill('Al');
  await reply(page, opening, [{ value: 'stale', label: 'Stale result' }]);
  await expect(picker(page).locator('option[value="stale"]')).toHaveCount(0);
  await expect.poll(async () => (await requests(page)).length).toBe(2);
  const current = (await requests(page))[1]; expect(current.text).toBe('Al');
  await reply(page, current, [{ value: 'alpha', label: 'Alpha' }]);
  await expect(picker(page).locator('option[value="alpha"]')).toHaveCount(1);
  await expect(input(page)).toHaveValue('Al');
  expect(await writes(page)).toEqual([]);
  await input(page).fill('Free item'); await input(page).press('Enter');
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  expect(await writes(page)).toEqual([{ id: 'draft-1', path: 'lines/line-1/name', revision: 4, value: 'Free item' }]);
  await page.waitForTimeout(200);
  expect((await requests(page)).length).toBe(2);
  await goods(page, 2).click();
  await expect(input(page)).toBeVisible();
  await reply(page, current, [{ value: 'late', label: 'Late old editor' }]);
  await expect(picker(page).locator('option[value="late"]')).toHaveCount(0);
  await expect(input(page)).toHaveValue('');
  expect((await writes(page)).length).toBe(1);
});

test('known choices survive failed queries, Escape cancels and leaving the picker saves free text once', async ({ page }) => {
  await mount(page, true); await goods(page).click();
  await expect(picker(page).locator('option[value="known-1"]')).toHaveCount(1);
  const [opening] = await requests(page);
  await reply(page, opening, [], 'failed');
  await expect(picker(page)).toHaveAttribute('aria-busy', 'false');
  await expect(picker(page).locator('option[value="known-1"]')).toHaveCount(1);
  await input(page).fill('Cancelled'); await input(page).press('Escape');
  await page.waitForTimeout(200);
  expect(await writes(page)).toEqual([]); expect((await requests(page)).length).toBe(1);
  await goods(page).click(); await input(page).fill('New free item');
  await picker(page).focus();
  expect(await writes(page)).toEqual([]);
  await page.locator('#outside').click();
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  expect(await writes(page)).toEqual([{ id: 'draft-1', path: 'lines/line-1/name', revision: 4, value: 'New free item' }]);
  await expect(input(page)).toHaveCount(0);
});

async function mountReference(page) {
  await mount(page);
  await page.evaluate(() => {
    const fixture = window.fixture;
    fixture.grid.addEventListener('inventory-reference-options', event => fixture.requests.push(structuredClone(event.detail)));
    fixture.grid.model = { id: 'shared-draft', columns: [{ key: 'reference', label: 'Original operation', width: 460 }],
      rows: [
        { id: 'return-row', label: 'Return', cells: [{ value: 'sale-old', display: 'Sale · Existing buyer',
          target: { id: 'return-draft', path: 'original-operation-id', revision: 4 },
          editor: { type: 'operation-reference', options: [{ value: 'sale-old', label: 'Sale · Existing buyer' }] } }] },
        { id: 'payment-row', label: 'Payment', cells: [{ value: '', target: { id: 'payment-draft', path: 'original-operation-id', revision: 2 },
          editor: { type: 'operation-reference', options: [] } }] },
      ], merges: [] };
  });
  await expect(referenceCell(page)).toHaveCount(1);
}
const referenceCell = (page, row = 'return-row') => page.locator(`#item-options tr[data-row-id="${row}"] td[data-column-key="reference"]`);
const referencePicker = page => page.getByLabel('Choose operation', { exact: true });
const referenceReply = (page, request, options, status = 'ok') => page.evaluate(({ request, options, status }) => {
  window.fixture.grid.referenceOptionsResponse = { requestId: request.requestId, options, status };
}, { request, options, status });

test('operation references display the known label and save one selected operation ID', async ({ page }) => {
  await mountReference(page); await referenceCell(page).click();
  await expect(input(page)).toHaveValue('Sale · Existing buyer');
  await expect(referencePicker(page)).toBeVisible();
  const [opening] = await requests(page);
  expect(opening.target).toEqual({ id: 'return-draft', path: 'original-operation-id', revision: 4 });
  expect(await writes(page)).toEqual([]);
  await referenceReply(page, opening, Array.from({ length: 40 }, (_, number) => ({ value: `sale-${number}`, label: `Sale ${number} · Buyer` })));
  expect(opening.text).toBe('');
  await expect(referencePicker(page).locator('option')).toHaveCount(33);
  await expect(referencePicker(page).locator('option[value="sale-old"]')).toHaveCount(1);
  await referencePicker(page).click(); await referencePicker(page).selectOption('sale-2');
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  expect(await writes(page)).toEqual([{ ...opening.target, value: 'sale-2' }]);
  expect(await page.evaluate(() => [...window.fixture.grid._attempts.values()][0].item)).toBeUndefined();
  expect(await page.evaluate(() => [...window.fixture.grid._attempts.values()][0].display)).toBe('Sale 2 · Buyer');
});

test('reference text is search-only and stale results cannot cross text changes or editors', async ({ page }) => {
  await mountReference(page); await referenceCell(page).click();
  const [opening] = await requests(page);
  await input(page).fill('Supplier');
  await referenceReply(page, opening, [{ value: 'stale-id', label: 'Stale operation' }]);
  await expect(referencePicker(page).locator('option[value="stale-id"]')).toHaveCount(0);
  await expect.poll(async () => (await requests(page)).length).toBe(2);
  const current = (await requests(page))[1]; expect(current.text).toBe('Supplier');
  await referenceReply(page, current, [{ value: 'purchase-1', label: 'Purchase · Supplier' }]);
  await page.locator('#outside').click();
  await expect(input(page)).toHaveCount(0);
  expect(await writes(page)).toEqual([]);
  await expect(referenceCell(page).locator('.inventory-cell-value')).toHaveText('Sale · Existing buyer');
  await referenceCell(page, 'payment-row').click();
  await expect(input(page)).toHaveValue('');
  await referenceReply(page, current, [{ value: 'late-id', label: 'Late return lookup' }]);
  await expect(referencePicker(page).locator('option[value="late-id"]')).toHaveCount(0);
  await input(page).fill('Supplier name is not an operation ID'); await input(page).press('Enter');
  await expect(input(page)).toHaveCount(0); await page.waitForTimeout(200);
  expect(await writes(page)).toEqual([]);
});

test('Enter accepts an exact offered operation label and None explicitly clears a reference', async ({ page }) => {
  await mountReference(page); await referenceCell(page, 'payment-row').click();
  const [opening] = await requests(page);
  await input(page).fill('Purchase · Supplier');
  await expect.poll(async () => (await requests(page)).length).toBe(2);
  await referenceReply(page, (await requests(page))[1], [{ value: 'purchase-1', label: 'Purchase · Supplier' }]);
  await input(page).press('Enter');
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  expect(await writes(page)).toEqual([{ id: 'payment-draft', path: 'original-operation-id', revision: 2, value: 'purchase-1' }]);
  expect(opening.target.id).toBe('payment-draft');
  await referenceCell(page).click();
  await referencePicker(page).selectOption({ label: 'None' });
  await expect.poll(async () => (await writes(page)).length).toBe(2);
  expect((await writes(page))[1]).toEqual({ id: 'return-draft', path: 'original-operation-id', revision: 4, value: '' });
});

test('failed reference queries retain the selected label and Escape cancels without a write', async ({ page }) => {
  await mountReference(page); await referenceCell(page).click();
  const [opening] = await requests(page);
  await referenceReply(page, opening, [], 'failed');
  await expect(referencePicker(page).locator('option[value="sale-old"]')).toHaveCount(1);
  await expect(referencePicker(page)).toHaveAttribute('aria-busy', 'false');
  await input(page).fill('Abandoned lookup'); await input(page).press('Escape');
  await expect(input(page)).toHaveCount(0); await page.waitForTimeout(200);
  expect(await writes(page)).toEqual([]); expect((await requests(page)).length).toBe(1);
  await expect(referenceCell(page).locator('.inventory-cell-value')).toHaveText('Sale · Existing buyer');
});

test('opening a filled goods editor offers replacements and retains the saved item beyond the first matches', async ({ page }) => {
  await mount(page, true); await goods(page).click();
  await expect(input(page)).toHaveValue('Known 1');
  const [opening] = await requests(page); expect(opening.text).toBe('');
  await reply(page, opening, [{ value: 'replacement', label: 'Replacement goods' }]);
  await expect(picker(page).locator('option[value="known-1"]')).toHaveCount(1);
  await expect(picker(page).locator('option[value="replacement"]')).toHaveCount(1);
  await picker(page).selectOption('replacement');
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  expect(await writes(page)).toEqual([{ id: 'draft-1', path: 'lines/line-1/item-id', revision: 4, value: 'replacement' }]);
});

async function mountGeneration(page) {
  await mount(page);
  await page.evaluate(() => {
    const fixture = window.fixture, model = structuredClone(fixture.grid.model);
    model.id = 'stock';
    model.columns[0].filterKey = 'name';
    model.rows.forEach((row, index) => {
      row.rowIndex = index; row.groupIndex = index;
      row.cells.forEach(cell => { if (cell.target) cell.target.revision = 1; });
    });
    model.viewport = { queryId: 'generation-one', queryKey: 'same-filter', totalRows: 2, totalGroups: 2, start: 0, end: 2, groupStart: 0, windowSize: 200 };
    fixture.windows = [];
    fixture.grid.addEventListener('inventory-window', event => fixture.windows.push(structuredClone(event.detail)));
    fixture.grid.model = model;
    fixture.nextModel = () => {
      const next = structuredClone(model); next.viewport.queryId = 'generation-two';
      next.rows.forEach(row => row.cells.forEach(cell => { if (cell.target) cell.target.revision = 2; }));
      return next;
    };
  });
  await expect(page.locator('#item-options')).toHaveAttribute('aria-busy', 'false');
}
const quantity = (page, row = 1) => page.locator(`#item-options tr[data-row-id="row-${row}"] td[data-column-key="quantity"]`);

test('a delayed generation preserves active filter focus and publishes unfinished conditions', async ({ page }) => {
  await mountGeneration(page);
  await page.evaluate(() => {
    window.fixture.filters = [];
    window.fixture.grid.addEventListener('inventory-filter', event => window.fixture.filters.push(structuredClone(event.detail.filters)));
  });
  await page.getByRole('button', { name: 'Filter Goods', exact: true }).click();
  await page.getByLabel('Add condition', { exact: true }).selectOption('begins');
  const value = page.getByRole('textbox', { name: 'Value 1', exact: true });
  await value.fill('unsent');
  await page.evaluate(() => {
    const fixture = window.fixture;
    fixture.grid.acceptedFilters = fixture.filters.at(-1);
    fixture.responseModel = fixture.nextModel(); fixture.grid.model = fixture.responseModel;
  });
  await expect(page.locator('#item-options')).toHaveAttribute('aria-busy', 'true');
  await expect(value).toBeEnabled(); await expect(value).toBeFocused(); await expect(value).toHaveValue('unsent');
  await expect.poll(() => page.evaluate(() => window.fixture.filters.at(-1)?.name?.[0]?.value)).toBe('unsent');
  await value.fill('unsent-next');
  await page.evaluate(() => {
    const fixture = window.fixture;
    fixture.grid.acceptedFilters = fixture.filters.at(-1);
    fixture.grid.windowResponse = { ...fixture.windows.at(-1), status: 'ok', model: fixture.responseModel };
  });
  await expect(page.locator('#item-options')).toHaveAttribute('aria-busy', 'false');
  await expect(value).toBeEnabled(); await expect(value).toBeFocused(); await expect(value).toHaveValue('unsent-next');
  await expect.poll(() => page.evaluate(() => window.fixture.filters.at(-1)?.name?.[0]?.value)).toBe('unsent-next');
  expect(await writes(page)).toEqual([]);
});

test('a delayed new generation blocks the next cell until its current revision is rendered', async ({ page }) => {
  await mountGeneration(page); await quantity(page).click();
  await input(page).fill('9'); await input(page).press('Enter');
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  await page.evaluate(() => {
    const fixture = window.fixture, next = fixture.nextModel(); next.rows[0].cells[1].value = '9';
    fixture.responseModel = next; fixture.grid.model = next; fixture.grid.saveStatus = 'Saved';
  });
  await expect(page.locator('#item-options')).toHaveAttribute('aria-busy', 'true');
  await quantity(page, 2).click(); await expect(input(page)).toHaveCount(0);
  expect((await writes(page)).length).toBe(1);
  await page.evaluate(() => {
    const fixture = window.fixture, request = fixture.windows.at(-1);
    fixture.grid.windowResponse = { ...request, status: 'ok', model: fixture.responseModel };
  });
  await expect(page.locator('#item-options')).toHaveAttribute('aria-busy', 'false');
  await quantity(page, 2).click();
  expect(await page.evaluate(() => window.fixture.grid._active.target.revision)).toBe(2);
  await input(page).fill('8'); await input(page).press('Enter');
  await expect.poll(async () => (await writes(page)).length).toBe(2);
  expect((await writes(page))[1]).toEqual({ id: 'draft-2', path: 'lines/line-1/quantity', revision: 2, value: '8' });
});

test('a new generation preserves an active unacknowledged edit until it can be resolved', async ({ page }) => {
  await mountGeneration(page); await quantity(page, 2).click(); await input(page).fill('77');
  await page.evaluate(() => {
    const fixture = window.fixture, next = fixture.nextModel(); next.rows[1].cells[1].value = '6';
    fixture.grid.model = next;
    fixture.grid.windowResponse = { ...fixture.windows.at(-1), status: 'ok', model: next };
  });
  await expect(input(page)).toHaveValue('77'); await expect(input(page)).toBeFocused();
  await expect(page.locator('#item-options')).toHaveAttribute('aria-busy', 'true');
  expect(await writes(page)).toEqual([]);
  await input(page).press('Enter');
  await expect(page.locator('#item-options')).toHaveAttribute('aria-busy', 'false');
  await expect(quantity(page, 2).locator('.inventory-cell-value')).toHaveText('77');
  const attempted = await page.evaluate(() => [...window.fixture.grid._attempts.values()][0]);
  expect(attempted.value).toBe('77'); expect(attempted.state).toBe('conflict'); expect(attempted.saved).toBe('6');
});

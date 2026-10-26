import { expect } from '@playwright/test';

export const stockGrid = page => page.locator('#stock-grid');
export const operationGrid = page => page.locator('#operations-history inventory-grid');
export const draftGrid = page => page.locator('#pending-draft-grid');
export const stockRow = (page, name) => stockGrid(page).locator('tbody tr').filter({ has: page.locator('td[data-column-key="name"]').filter({ hasText: name }) });
export const draftCell = (page, id, path) => draftGrid(page).locator(`td[data-entry-id="${id}"][data-path="${path}"]`);
export const gridEditor = grid => grid.locator('.inventory-cell-editor > input,.inventory-cell-editor > select:not(.inventory-goods-choice)');
export const stockCell = (page, name, key) => stockRow(page, name).locator(`td[data-column-key="${key}"]`);
export const gridHeader = (grid, key) => grid.locator(`thead td[data-column-key="${key}"],thead th[data-column-key="${key}"]`);

export async function signals(page, name) {
  return page.evaluate(async name => {
    const { getPath } = await import('/datastar.js');
    return JSON.parse(JSON.stringify(getPath(name) ?? null));
  }, name);
}

export async function draftIds(page) { return await signals(page, '_draftIds') || []; }

export async function openReview(page) {
  await expect(page.locator('#inventory-save-status')).not.toHaveText('Saving');
  await page.locator('stock-entry input').focus();
  await expect(page.locator('#inventory-save-status')).not.toHaveText('Saving');
  await page.locator('stock-entry input').press('Escape');
  if (!(await draftGrid(page).isVisible())) await page.getByRole('button', { name: 'Preview changes', exact: true }).click();
  await expect(draftGrid(page)).toBeVisible();
  await expect.poll(() => draftGrid(page).evaluate(grid => !!grid.worksheet)).toBe(true);
}

export async function editDraftCell(page, id, path, value, key = 'Enter') {
  await openReview(page);
  const cell = draftCell(page, id, path);
  const input = await openDraftCell(page, id, path);
  const before = await cell.getAttribute('data-revision');
  if (await input.evaluate(element => element.tagName === 'SELECT')) await input.selectOption(value);
  else await input.fill(value);
  await input.press(key);
  await expect(cell).not.toHaveAttribute('data-revision', before);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect.poll(() => draftGrid(page).evaluate(grid => grid.busy)).toBe(false);
}

export async function openDraftCell(page, id, path) {
  const cell = draftCell(page, id, path);
  const input = cell.locator('.inventory-cell-editor > input,.inventory-cell-editor > select:not(.inventory-goods-choice)');
  // Auto-preview can briefly lock cells between the readiness check and the click.
  await expect(async () => {
    if (!(await input.isVisible())) await cell.click();
    await expect(input).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  return input;
}

export async function editStockCell(page, row, key, value, commit = 'Enter') {
  const grid = stockGrid(page);
  await expect.poll(() => grid.evaluate(element => element.busy)).toBe(false);
  await row.locator(`td[data-column-key="${key}"]`).click();
  const input = gridEditor(grid);
  await expect(input).toBeVisible();
  await input.fill(value);
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/edit-stock');
  await input.press(commit);
  expect((await saved).status()).toBe(200);
  await expect(page.locator('#inventory-save-status')).toHaveText('Saved');
  await expect(row.locator(`td[data-column-key="${key}"]`)).toContainText(value);
}

export async function revertPending(page) {
  const cancel = page.getByRole('button', { name: 'Cancel addition', exact: true });
  if (await cancel.isVisible()) await cancel.click();
  else await page.locator('stock-entry input').press('Escape');
  await expect(cancel).toBeHidden();
  const reset = page.getByRole('button', { name: 'Reset all', exact: true });
  if (await reset.isVisible()) await reset.click();
  const revert = page.getByRole('button', { name: 'Revert all changes', exact: true });
  if ((await draftIds(page)).length) {
    await openReview(page);
    await expect(revert).toBeEnabled();
    await revert.click();
  }
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  await expect(page.locator('#inventory-review')).toBeEmpty();
}

export async function addStockRow(page, name, location = 'Smoke shelf', quantity = '3') {
  const before = await draftIds(page);
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  await expect.poll(async () => (await draftIds(page)).length).toBe(before.length + 1);
  const id = (await draftIds(page)).find(id => !before.includes(id));
  for (const [path, value] of [['lines/0/name', name], ['lines/0/quantity', quantity], ['lines/0/location', location], ['reason', 'Initial count']]) {
    await editDraftCell(page, id, path, value);
  }
  return id;
}

export async function selectDrafts(page, ids) {
  await openReview(page);
  for (const input of await draftGrid(page).locator('input[data-review-entry]').all()) {
    const id = await input.getAttribute('data-review-entry');
    await input.setChecked(ids.includes(id));
    await expect.poll(() => draftGrid(page).evaluate(grid => grid.busy)).toBe(false);
  }
  await expect.poll(async () => {
    const review = await signals(page, '_review');
    return review?.selected?.map(entry => entry.id).sort() || [];
  }).toEqual([...ids].sort());
  await expect(page.getByRole('button', { name: 'Submit selected', exact: true })).toBeEnabled();
}

export async function acceptDrafts(page, ids) {
  await selectDrafts(page, ids);
  await page.getByRole('button', { name: 'Submit selected', exact: true }).click();
  await expect.poll(async () => (await draftIds(page)).filter(id => ids.includes(id))).toEqual([]);
  await expect(page.locator('#inventory-review')).toBeEmpty();
}

export async function chooseSmart(page, label) {
  const options = page.getByRole('option').filter({ has: page.locator('.stock-entry-option-field').filter({ hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }) });
  await expect(options.first()).toBeVisible();
  const field = options.filter({ has: page.locator('.stock-entry-option-description', { hasText: /^Choose field$/ }) });
  await (await field.count() ? field : options).click();
}

export async function smartField(page, field, value) {
  const input = page.locator('stock-entry input');
  await input.fill(field);
  await chooseSmart(page, field);
  await input.fill(value);
  await input.press('Enter');
  await expect(page.getByRole('button', { name: `Edit ${field}: ${value}`, exact: true })).toBeVisible();
}

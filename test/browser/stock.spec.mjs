import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';

const pad = value => String(value).padStart(2, '0');
const yesterday = () => {
  const date = new Date(Date.now() - 86_400_000);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T10:00`;
};

async function editCell(page, grid, id, path, value) {
  const input = grid.locator(`td[data-entry-id="${id}"][data-path="${path}"] .inventory-cell-editor > input`);
  await expect(async () => {
    if (!(await input.isVisible())) await grid.evaluate((node, { id, path }) => node.focusField(id, path), { id, path });
    await expect(input).toBeVisible({ timeout: 250 });
  }).toPass({ timeout: 5000 });
  await input.fill(value);
  await input.press('Enter');
}

async function registerCatalogueRecord(page, path, name) {
  await page.goto(path);
  const grid = page.locator('.catalogue-grid');
  await expect.poll(() => grid.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.getByRole('button', { name: 'Add row', exact: true }).click();
  const id = await page.locator('catalogue-workbench').evaluate(node => node.drafts[0].id);
  await editCell(page, grid, id, 'name', name);
  await page.locator('.catalogue-save-new').click();
  await expect(page.locator('.catalogue-save-new')).toBeHidden();
  await expect(grid).toContainText(name);
}

test('declare, review and accept an opening; stock, money and history follow', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page);
  const suffix = Date.now();
  await registerCatalogueRecord(page, '/catalogues/warehouses', `Main ${suffix}`);
  await registerCatalogueRecord(page, '/catalogues', `T-shirt ${suffix}`);

  await page.goto('/');
  const workbench = page.locator('stock-workbench');
  const drafts = page.locator('.stock-drafts');
  await expect.poll(() => drafts.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.getByRole('button', { name: 'Add opening', exact: true }).click();
  const id = await workbench.evaluate(node => node.newRows[0].id);
  const saved = async () => {
    await expect(page.locator('.stock-save-status')).toHaveText('');
    await expect.poll(() => workbench.evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
  };
  for (const [path, value] of [['product', `T-shirt ${suffix}`], ['variant', 'M'], ['warehouse', `Main ${suffix}`], ['quantity', '5'], ['unit-cost', '100'], ['occurred-at', yesterday()]]) {
    await editCell(page, drafts, id, path, value);
    await saved();
  }
  const select = drafts.locator(`input[data-review-entry="${id}"]`);
  await expect(select).toBeVisible();
  await select.check();
  await page.getByRole('button', { name: 'Review', exact: true }).click();
  const review = page.locator('.stock-review');
  await expect(review.locator('.stock-review-quantity')).toHaveText('0 → 5');
  await expect(review).toContainText('Stock item');
  await review.getByRole('button', { name: 'Accept', exact: true }).click();
  await expect(review).toBeHidden();

  const stock = page.locator('#stock-grid');
  const row = stock.locator('tbody tr').filter({ has: page.locator('td[data-column-key="item"]', { hasText: /^\s*M\s*$/ }) });
  await expect(row).toContainText('5');
  await page.locator('.stock-mode button[data-mode="money"]').click();
  await expect(row).toContainText('500.00');
  await row.locator('button.inventory-cell-action').click();
  await expect(page.locator('.stock-history-rows')).toContainText('Opening');
  expect(errors).toEqual([]);
});

test('edits pasted into a saved entry while a save is in flight keep every value', async ({ page }) => {
  await signIn(page);
  const workbench = page.locator('stock-workbench');
  const drafts = page.locator('.stock-drafts');
  const saved = async () => {
    await expect(page.locator('.stock-save-status')).toHaveText('');
    await expect.poll(() => workbench.evaluate(node => !node.pending && node.queued.size === 0)).toBe(true);
  };
  await expect.poll(() => drafts.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await page.getByRole('button', { name: 'Add opening', exact: true }).click();
  const id = await workbench.evaluate(node => node.newRows[0].id);
  await editCell(page, drafts, id, 'product', `Quick ${Date.now()}`);
  await saved();
  await page.reload();
  await expect.poll(() => drafts.evaluate(node => Boolean(node.worksheet))).toBe(true);
  await expect(drafts.locator(`td[data-entry-id="${id}"][data-path="product"]`)).toBeVisible();

  // A paste across one row emits its edits together; the first saves while the rest queue.
  await drafts.evaluate((grid, id) => {
    const target = path => grid.model.rows.find(row => row.id === id).cells.find(cell => cell.target?.path === path).target;
    for (const [path, value] of [['quantity', '7'], ['unit-cost', '30'], ['reason', 'count']]) {
      grid.dispatchEvent(new CustomEvent('stock-draft', { bubbles: true, detail: { ...target(path), value } }));
    }
  }, id);
  await saved();
  await page.reload();
  await expect.poll(() => drafts.evaluate(node => Boolean(node.worksheet))).toBe(true);
  for (const [path, value] of [['quantity', '7'], ['unit-cost', '30'], ['reason', 'count']]) {
    await expect(drafts.locator(`td[data-entry-id="${id}"][data-path="${path}"]`)).toContainText(value);
  }
});

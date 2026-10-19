import { expect, test } from '@playwright/test';

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
  await expect(page).toHaveTitle('Inventory · Tover v2');
  await expect(page.getByRole('combobox', { name: 'Add stock row', exact: true })).toBeVisible();
  await expect(stockGrid(page).locator('.sheet-goods-name')).toHaveCount(0);
  await expect(page.getByText('No changes', { exact: true })).toBeVisible();
  await expect(page.getByText('Demo data', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Operations', exact: true }).click();
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
  await page.getByRole('button', { name: 'Operations', exact: true }).click();
  const operations = page.locator('#operations-history');
  await expect(operations).toContainText('Browser submitted item');
  await expect(operations).toContainText('Row added');
  await expect(operations).toContainText('Local');

  await page.reload();
  await expect(stockGrid(page)).toBeVisible();
  await expect(stockGrid(page).locator('.sheet-goods-name')).toHaveCount(0);
  await page.getByRole('button', { name: 'Operations', exact: true }).click();
  await expect(page.getByText('No operations yet.', { exact: true })).toBeVisible();
});

import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { pageContext, signIn } from './auth.fixture.mjs';

const messages = JSON.parse(readFileSync(new URL('../../resources/i18n/messages.json', import.meta.url), 'utf8'));
const translate = locale => (key, params = {}) => {
  expect(messages[locale][key], key).toBeDefined();
  return messages[locale][key].replace(/\{([\w-]+)\}/g, (_, name) => params[name]);
};
const operationTypes = ['purchase', 'return', 'transfer', 'production', 'defect', 'sale', 'write-off', 'payment'];
const operationNames = ['Purchase', 'Return', 'Transfer', 'Production', 'Defect', 'Sale', 'Write-off', 'Payment'];

async function language(page, locale) {
  if (await page.locator('html').getAttribute('lang') !== locale) {
    await Promise.all([page.waitForNavigation(), page.locator('.language-toggle').click()]);
  }
  await expect(page.locator('html')).toHaveAttribute('lang', locale);
}

async function translatedInterface(page, locale) {
  if (locale !== 'ru') return;
  const strings = await page.evaluate(() => {
    const result = [];
    for (const element of document.body.querySelectorAll('*')) {
      if (!element.checkVisibility() && element.tagName !== 'OPTION') continue;
      if (['SCRIPT', 'STYLE', 'TEXTAREA'].includes(element.tagName)) continue;
      for (const attribute of ['aria-label', 'title', 'placeholder']) {
        if (element.hasAttribute(attribute)) result.push(element.getAttribute(attribute).trim());
      }
      for (const child of element.childNodes) if (child.nodeType === Node.TEXT_NODE && child.textContent.trim()) result.push(child.textContent.trim());
    }
    return [...new Set(result)];
  });
  const english = new Set(Object.entries(messages.en).filter(([key, value]) => typeof value === 'string' && messages.ru[key] !== value).map(([, value]) => value));
  expect(strings.filter(text => english.has(text)), 'Untranslated visible text or accessible labels').toEqual([]);
}

async function action(page, name, input = {}) {
  const { csrf, version } = await pageContext(page);
  const response = await page.request.post(`/action/tover.stock.web/${name}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
    data: { view: 'stock', ...input },
  });
  expect(response.status()).toBe(200);
  const body = await response.text();
  const signals = Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals ')).map(line => JSON.parse(line.slice(14))));
  expect(signals.saveStatus, body).not.toBe('Failed');
  return signals;
}

async function fields(page, draft, values) {
  for (const [path, value] of values) {
    const result = await action(page, 'save-field', { ...draft, path, value });
    draft = result._inventorySnapshot.draftSelection.find(entry => entry.id === draft.id);
  }
  return draft;
}

async function freshWorkspace(page, locale) {
  await signIn(page); await language(page, locale);
  await page.goto('/workspace');
  const form = page.locator('form[action$="/create-workspace"]');
  await form.locator('xpath=ancestor::details').locator('summary').click();
  await form.locator('input[name=name]').fill(`I18n ${locale} ${randomUUID()}`);
  await form.locator('button').click();
  await expect(page.locator('#stock-grid')).toBeVisible();
}

async function submit(page, t) {
  await page.reload();
  await page.getByRole('button', { name: t('Preview changes'), exact: true }).click();
  await expect(page.locator('#inventory-review').getByRole('button', { name: t('Submit selected'), exact: true })).toBeEnabled();
  await page.locator('#inventory-review').getByRole('button', { name: t('Submit selected'), exact: true }).click();
  await expect(page.locator('#inventory-review')).toBeEmpty();
  await expect(page.getByText(t('No changes'), { exact: true })).toBeVisible();
}

for (const locale of ['en', 'ru']) {
  const t = translate(locale);
  test(`${locale}: a three-item purchase directly updates stock, correction, sale and production`, async ({ page }) => {
    test.setTimeout(120_000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await freshWorkspace(page, locale);
    let draft = (await action(page, 'add-entry', { kind: 'opening' }))._inventorySnapshot.draftSelection.at(-1);
    await fields(page, draft, [['lines/0/name', 'Previously stocked goods'], ['lines/0/quantity', '9'], ['lines/0/location', 'Main'], ['reason', 'Initial count']]);
    await submit(page, t);
    const itemIds = async () => page.locator('#stock-grid').evaluate(grid => Object.fromEntries(grid.model.rows.map(row => {
      const name = row.cells.find((_, index) => grid.model.columns[index].key === 'name');
      return [name.value, name.action.itemId];
    })));
    const existingId = (await itemIds())['Previously stocked goods'];
    draft = (await action(page, 'add-entry', { type: 'purchase' }))._inventorySnapshot.draftSelection.at(-1);
    draft = await fields(page, draft, [['party', 'Supplier'], ['amount', '199'], ['currency', 'EUR'], ['lines/0/item-id', existingId], ['lines/0/quantity', '5'], ['lines/0/location', 'Main']]);
    for (const [index, name, quantity] of [[1, 'Completely new item', '3'], [2, 'New production material', '2']]) {
      draft = (await action(page, 'draft-command', { ...draft, command: 'add-line', path: 'lines' }))._inventorySnapshot.draftSelection.find(entry => entry.id === draft.id);
      draft = await fields(page, draft, [[`lines/${index}/name`, name], [`lines/${index}/quantity`, quantity], [`lines/${index}/location`, 'Main']]);
    }
    const grid = page.locator('#pending-draft-grid');
    const row = (table, name) => table.locator('tbody tr').filter({ has: page.locator('td[data-column-key=name]').filter({ hasText: name }) });
    const diff = name => row(page.locator('#resulting-stock-grid'), name).locator('[data-column-key=quantity]');
    const stock = name => row(page.locator('#stock-grid'), name).locator('[data-column-key=quantity]');
    const open = async () => {
      await page.goto('/');
      await page.getByRole('button', { name: t('Preview changes'), exact: true }).click();
      await expect(grid).toBeVisible();
    };
    const acceptReview = async () => {
      await page.locator('#inventory-review').getByRole('button', { name: t('Submit selected'), exact: true }).click();
      await expect(page.locator('#inventory-review')).toBeEmpty();
    };
    const simplePurchase = async () => {
      await expect(page.getByRole('button', { name: t('Add arrival'), exact: true })).toHaveCount(0);
      await expect(page.locator('[data-column-key=awaiting-quantity],[data-column-key=line-kind],[data-column-key=stock-effect],[data-path*="receipts/"]')).toHaveCount(0);
      await translatedInterface(page, locale);
    };
    await open();
    await expect(diff('Previously stocked goods')).toHaveText('9 → 14');
    await expect(diff('Completely new item')).toHaveText('0 → 3');
    await expect(diff('New production material')).toHaveText('0 → 2');
    const controls = grid.locator('td[data-path="lines/0/name"]').locator('..').locator('[data-column-key=actions]');
    await expect(controls.getByRole('button', { name: t('Add {type}', { type: t('item') }), exact: true })).toHaveText('+');
    expect(await controls.evaluate(cell => cell.clientWidth)).toBeLessThanOrEqual(100);
    await simplePurchase();
    await page.setViewportSize({ width: 2400, height: 950 });
    await page.screenshot({ path: `/tmp/tover-simple-purchase-${locale}.png`, fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `/tmp/tover-simple-purchase-${locale}-mobile.png`, fullPage: true });
    await page.setViewportSize({ width: 1280, height: 720 });
    await acceptReview();
    await expect(stock('Previously stocked goods')).toHaveText('14');
    await expect(stock('Completely new item')).toHaveText('3');
    await expect(stock('New production material')).toHaveText('2');
    await expect(page.locator('#stock-grid thead [data-column-key=quantity]')).toContainText(t('Quantity'));
    await simplePurchase();

    await page.goto('/operations');
    const purchase = page.locator('#operations-history td[data-column-key=type]').filter({ hasText: t('Purchase') });
    await purchase.getByRole('button').click();
    await simplePurchase();
    await page.locator('.operation-details').getByRole('button', { name: t('Correct'), exact: true }).click();
    await expect(grid).toBeVisible();
    draft = await page.evaluate(async () => (await import('/datastar.js')).getPath('_draftSelection').at(-1));
    await fields(page, draft, [['reason', 'Correct purchase quantities'], ['replacement/lines/0/quantity', '6'], ['replacement/lines/1/quantity', '4'], ['replacement/lines/2/quantity', '3']]);
    await open();
    await expect(diff('Previously stocked goods')).toHaveText('14 → 15');
    await expect(diff('Completely new item')).toHaveText('3 → 4');
    await expect(diff('New production material')).toHaveText('2 → 3');
    await simplePurchase();
    await acceptReview();
    await expect(stock('Previously stocked goods')).toHaveText('15');
    await expect(stock('Completely new item')).toHaveText('4');
    await expect(stock('New production material')).toHaveText('3');

    const purchasedIds = await itemIds();
    draft = (await action(page, 'add-entry', { type: 'sale' }))._inventorySnapshot.draftSelection.at(-1);
    await fields(page, draft, [['party', 'Buyer'], ['lines/0/item-id', purchasedIds['Completely new item']], ['lines/0/quantity', '1'], ['lines/0/location', 'Main']]);
    await open();
    await expect(diff('Completely new item')).toHaveText('4 → 3');
    await acceptReview();
    await expect(stock('Completely new item')).toHaveText('3');
    draft = (await action(page, 'add-entry', { type: 'production' }))._inventorySnapshot.draftSelection.at(-1);
    await fields(page, draft, [['inputs/0/item-id', purchasedIds['New production material']], ['inputs/0/quantity', '2'], ['inputs/0/location', 'Main'], ['outputs/0/name', 'Purchased material output'], ['outputs/0/quantity', '1'], ['outputs/0/location', 'Main']]);
    await open();
    await expect(diff('New production material')).toHaveText('3 → 1');
    await expect(diff('Purchased material output')).toHaveText('0 → 1');
    await translatedInterface(page, locale);
    await acceptReview();
    await expect(stock('New production material')).toHaveText('1');
    await expect(stock('Purchased material output')).toHaveText('1');
    await page.goto('/operations');
    await expect(page.locator('#operations-history td[data-column-key=amount]').filter({ hasText: '199' })).toHaveCount(1);
    await simplePurchase();
    expect(errors).toEqual([]);
  });

  test(`${locale}: signed-in screens, filter operators, workspace and team errors`, async ({ page, browser, baseURL }) => {
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await freshWorkspace(page, locale);
    await translatedInterface(page, locale);
    const languageBox = await page.locator('.language-toggle').boundingBox();
    const themeBox = await page.locator('#theme-toggle').boundingBox();
    expect(languageBox.height).toBe(themeBox.height);
    expect(languageBox.width).toBeLessThan(60);
    for (const route of ['/', '/operations']) {
      await page.goto(route);
      await expect(page).toHaveTitle(t(route === '/' ? 'Stock · Tover' : 'Operations · Tover'));
      for (const button of await page.locator('.inventory-column-filter').all()) {
        await button.click();
        const dialog = page.getByRole('dialog');
        expect(await dialog.getByLabel(t('Add condition'), { exact: true }).locator('option').evaluateAll(options => options.map(option => option.value)))
          .toEqual(['', 'empty', 'notEmpty', 'eq', 'notEq', 'begins', 'contains', 'notContains']);
        await translatedInterface(page, locale);
        await dialog.getByRole('button', { name: t('Close'), exact: true }).click();
      }
    }
    await page.goto('/');
    await page.getByRole('button', { name: t('Filter {p0}', { p0: t('Goods') }), exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(t('Add condition'), { exact: true }).selectOption('contains');
    await dialog.locator('input').fill('Needle'); await dialog.locator('input').press('Enter');
    await expect(page.locator('.inventory-filter-strip')).toContainText(t('contains'));
    await dialog.getByRole('button', { name: t('Close'), exact: true }).click();
    await page.getByRole('button', { name: t('Reset all'), exact: true }).click();

    await page.getByRole('link', { name: t('Team'), exact: true }).click();
    await expect(page).toHaveTitle(t('Team · Tover'));
    await expect(page.getByRole('heading', { name: t('Members'), exact: true })).toBeVisible();
    const email = `i18n-${randomUUID()}@example.test`;
    await page.locator('details').filter({ has: page.locator('form[action$="/invite"]') }).locator('summary').click();
    await page.getByLabel(t('Email'), { exact: true }).fill(email);
    await page.getByRole('button', { name: t('Send invitation'), exact: true }).click();
    const invitation = page.locator('.management-table tbody tr').filter({ hasText: email });
    await expect(invitation).toContainText(t('Pending'));
    await translatedInterface(page, locale);
    await invitation.getByRole('button', { name: t('Resend'), exact: true }).click();
    await expect(page.getByRole('alert')).toContainText(t('Too many requests. Please try again later.'));
    await invitation.getByRole('button', { name: t('Cancel'), exact: true }).click();
    await expect(invitation).toHaveCount(0);
    await translatedInterface(page, locale);

    await page.goto('/workspace');
    await expect(page).toHaveTitle(t('Workspaces · Tover'));
    await translatedInterface(page, locale);
    const form = page.locator('form[action$="/select-workspace"]').first();
    await form.locator('[name="workspace-id"]').evaluate(input => { input.value = '00000000-0000-0000-0000-000000000000'; });
    await form.locator('button').click();
    await expect(page.getByRole('alert')).toContainText(t('The request was rejected. Refresh the page before trying again.'));
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `/tmp/tover-i18n-${locale}-header.png`, fullPage: true });

    const context = await browser.newContext({ baseURL });
    try {
      const member = await context.newPage(); await signIn(member, 'member@example.test'); await language(member, locale);
      const forbidden = await member.goto('/team');
      expect(forbidden.status()).toBe(403);
      await expect(member.locator('body')).toHaveText(t('Workspace administration requires an owner or admin.'));
      await member.goto('/'); await language(member, 'en');
    } finally { await context.close(); }
    expect(errors).toEqual([]);
  });

  test(`${locale}: all eight operations, validation, review, history, correction and cancellation`, async ({ page }) => {
    test.setTimeout(120_000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await freshWorkspace(page, locale);
    let draft = (await action(page, 'add-entry', { kind: 'opening' }))._inventorySnapshot.draftSelection.at(-1);
    await fields(page, draft, [['lines/0/name', 'Bilingual material'], ['lines/0/quantity', '100'], ['lines/0/location', 'Main'], ['reason', 'Initial count']]);
    await submit(page, t);
    const itemId = await page.locator('#stock-grid').evaluate(grid => grid.model.rows[0].cells.find(cell => cell.action?.itemId).action.itemId);
    for (const [index, type] of operationTypes.entries()) {
      await test.step(type, async () => {
        draft = (await action(page, 'add-entry', { type }))._inventorySnapshot.draftSelection.at(-1);
        await page.reload();
        await page.getByRole('button', { name: t('Preview changes'), exact: true }).click();
        await expect(page.locator('#pending-draft-grid')).toContainText(t(operationNames[index]));
        await expect(page.locator('#inventory-review')).toContainText(t('Resolve these conflicts before submitting.'));
        await translatedInterface(page, locale);
        const values = [['reason', `Check ${type}`]];
        if (['purchase', 'sale', 'payment'].includes(type)) values.push(['party', `Partner ${type}`]);
        if (type === 'payment') values.push(['amount', '4.25'], ['currency', 'EUR']);
        else if (type === 'production') {
          values.push(['inputs/0/item-id', itemId], ['inputs/0/quantity', '2'], ['inputs/0/location', 'Main'],
            ['outputs/0/name', 'Bilingual output'], ['outputs/0/quantity', '1'], ['outputs/0/location', 'Main']);
        } else {
          if (type === 'purchase') values.push(['lines/0/name', 'Bilingual purchased']);
          else values.push(['lines/0/item-id', itemId]);
          values.push(['lines/0/quantity', '1']);
          if (type === 'transfer') values.push(['from-location', 'Main'], ['to-location', 'Secondary']);
          else values.push(['lines/0/location', 'Main']);
        }
        await fields(page, draft, values);
        await page.reload();
        await page.getByRole('button', { name: t('Preview changes'), exact: true }).click();
        await expect(page.locator('#inventory-review').getByRole('button', { name: t('Submit selected'), exact: true }), `Completed ${type}: ${await page.locator('#inventory-review').innerText()}`).toBeEnabled();
      });
    }
    await submit(page, t);
    await translatedInterface(page, locale);
    await page.getByRole('button', { name: t('History for {name}', { name: 'Bilingual output' }), exact: true }).click();
    await expect(page.locator('#item-history-grid')).toContainText(t('Production'));
    await translatedInterface(page, locale);
    await page.getByRole('tab', { name: t('Lineage'), exact: true }).click();
    await expect(page.locator('.inventory-timeline')).toBeVisible();
    await translatedInterface(page, locale);
    await page.locator('.inventory-timeline button').first().click();
    await expect(page.locator('#history-operation-details')).toContainText(t('Production'));
    await translatedInterface(page, locale);
    await page.locator('#history-operation-details').getByRole('button', { name: t('Close operation details'), exact: true }).click();
    await page.getByRole('button', { name: t('Close history'), exact: true }).click();
    await page.getByRole('link', { name: t('Operations'), exact: true }).click();
    for (const name of operationNames) {
      const cell = page.locator('stock-operations td[data-column-key=type]').filter({ hasText: new RegExp(`^${t(name)}$`) });
      await cell.getByRole('button').click();
      await expect(page.locator('.operation-details').last()).toContainText(t('Submitted by'));
      await translatedInterface(page, locale);
      await cell.getByRole('button').click();
    }
    const payment = page.locator('stock-operations td[data-column-key=type]').filter({ hasText: new RegExp(`^${t('Payment')}$`) });
    await payment.getByRole('button').click();
    await page.locator('.operation-details').getByRole('button', { name: t('Correct'), exact: true }).click();
    await expect(page.locator('#pending-draft-grid')).toContainText(t('Correction · '));
    draft = await page.evaluate(async () => (await import('/datastar.js')).getPath('_draftSelection').at(-1));
    await fields(page, draft, [['reason', 'Bilingual correction'], ['replacement/amount', '5.25']]);
    await submit(page, t); await page.goto('/operations');
    await payment.getByRole('button').click();
    await expect(page.locator('.operation-details')).toContainText('Bilingual correction');
    await expect(payment.locator('..').locator('td[data-column-key=amount]')).toHaveText('5.25');
    await translatedInterface(page, locale);
    await page.locator('.operation-details').getByRole('button', { name: t('Cancel'), exact: true }).click();
    await expect.poll(() => page.evaluate(async () => (await import('/datastar.js')).getPath('_draftSelection').length)).toBe(1);
    draft = await page.evaluate(async () => (await import('/datastar.js')).getPath('_draftSelection').at(-1));
    await fields(page, draft, [['reason', 'Bilingual cancellation']]);
    await submit(page, t); await page.goto('/operations');
    const cancelled = page.locator('stock-operations td[data-column-key=type]').filter({ hasText: t('Payment') + t(' · cancelled') });
    await cancelled.getByRole('button').click();
    await expect(page.locator('.operation-details')).toContainText(t('Cancelled'));
    await translatedInterface(page, locale);
    expect(errors).toEqual([]);
  });

  test(`${locale}: smart input fields and errors, date editor, load failure`, async ({ page }) => {
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await freshWorkspace(page, locale);
    const input = page.locator('stock-entry input');
    await page.locator('stock-entry').evaluate(element => element.beginAdd());
    const ready = () => expect.poll(() => page.locator('stock-entry').evaluate(element => element.busy)).toBe(false);
    const choose = async label => {
      await page.getByRole('option').filter({ has: page.locator('.stock-entry-option-field', { hasText: new RegExp(`^${label}$`) }) }).click();
      await ready();
    };
    await choose(t('Purchase'));
    await expect.poll(() => page.locator('stock-entry').evaluate(element => element.pending)).toBe(false);
    await input.press('Meta+Enter');
    await expect(page.locator('.stock-entry-feedback')).toContainText(t('{field} is required (maximum 500 characters).', { field: locale === 'ru' ? t('Party') : 'party' }));
    await translatedInterface(page, locale);
    await page.locator('.stock-entry-feedback').getByRole('button', { name: t('Fix field'), exact: true }).first().click();
    await expect(input).toHaveAttribute('aria-label', t('{p0} {p1}', { p0: t('Party'), p1: t('for operation') }));
    await input.fill('Supplier'); await input.press('Enter'); await ready();
    await page.getByRole('button', { name: t('Edit {p0}: {p1}', { p0: t('Unit'), p1: t('pcs') }), exact: true }).click();
    await ready(); await expect(input).toHaveValue('pcs');
    await input.fill('pcs'); await input.press('Enter'); await ready();
    await expect(page.getByRole('button', { name: t('Edit {p0}: {p1}', { p0: t('Unit'), p1: t('pcs') }), exact: true })).toBeVisible();
    await translatedInterface(page, locale);
    await page.locator('.stock-entry-chip-edit').filter({ has: page.locator('.stock-entry-chip-label', { hasText: t('Occurred at') }) }).click();
    await expect(input).toHaveAttribute('type', 'datetime-local');
    await input.press('Escape');
    await page.getByRole('button', { name: t('Cancel addition'), exact: true }).click();
    await page.route('**/vendor/jspreadsheet-ce/jspreadsheet.js', route => route.fulfill({ contentType: 'text/javascript', body: 'window.jspreadsheet = () => { throw new Error("Internal spreadsheet initialization failure"); };' }));
    await page.reload();
    await expect(page.locator('#stock-grid .inventory-grid-notice:not(.inventory-window-notice)').first()).toHaveText(t('Could not load inventory spreadsheet. Reload the page and try again.'));
    await translatedInterface(page, locale);
    expect(errors).toEqual([]);
  });
}

test('compact language button works without JavaScript and persists on mobile', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL, javaScriptEnabled: false, locale: 'en-US', viewport: { width: 390, height: 844 } });
  try {
    const page = await context.newPage(); await page.goto('/auth/login');
    await expect(page.locator('.language-toggle')).toHaveAccessibleName('Switch to Russian');
    await page.locator('.language-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    await expect(page.locator('.language-toggle')).toHaveAccessibleName('Переключить на английский');
    await page.reload(); await expect(page.locator('.language-toggle')).toHaveText('RU');
    await page.screenshot({ path: '/tmp/tover-language-switch-mobile.png', fullPage: true });
  } finally { await context.close(); }
});

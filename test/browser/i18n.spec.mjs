import { expect, test } from '@playwright/test';
import { signIn, pageContext } from './auth.fixture.mjs';

async function language(page, value) {
  if (await page.locator('html').getAttribute('lang') === value) return;
  await Promise.all([page.waitForNavigation(), page.locator('.language-toggle').click()]);
  await expect(page.locator('html')).toHaveAttribute('lang', value);
}
async function action(page, name, payload) {
  const { csrf, version } = await pageContext(page);
  const response = await page.request.post(`/action/tover.stock.web/${name}`, {
    headers: { origin: new URL(page.url()).origin, 'datastar-request': 'true', 'x-csrf-token': csrf, 'x-tover-context': version },
    data: { view: 'stock', ...payload },
  });
  expect(response.status()).toBe(200);
  const body = await response.text();
  const signals = Object.assign({}, ...body.split('\n').filter(line => line.startsWith('data: signals ')).map(line => JSON.parse(line.slice(14))));
  expect(signals.saveStatus, body).not.toBe('Failed');
  return signals;
}

for (const locale of ['en', 'ru']) test(`grid filter icons render valid SVG in ${locale}`, async ({ page }) => {
  const errors = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await signIn(page); await language(page, locale);
  for (const url of ['/', '/operations']) {
    await page.goto(url);
    const filters = page.locator('.inventory-column-filter');
    await expect(filters.first()).toBeVisible();
    for (const path of await filters.locator('path').all()) await expect(path).toHaveAttribute('d', /^[Mm]/);
    await filters.first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByRole('button', { name: locale === 'ru' ? 'Закрыть' : 'Close', exact: true }).click();
  }
  expect(errors).toEqual([]);
});

test('guest language covers every authentication screen and persists after reload', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL, locale: 'ru-RU' });
  const page = await context.newPage();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  for (const [url, heading] of [['/auth/login', 'Войти'], ['/auth/signup', 'Создать аккаунт'], ['/auth/recover', 'Восстановить доступ'], ['/auth/verify?type=email', 'Подтвердить ссылку из письма']]) {
    await page.goto(url);
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    await expect(page.locator('.language-toggle')).toHaveText('RU');
    await expect(page.locator('.language-toggle')).toHaveAccessibleName('Переключить на английский');
    await expect(page.locator('#theme-toggle')).toHaveAttribute('aria-label', /тему/);
  }
  await language(page, 'en'); await page.reload();
  await expect(page.getByRole('heading', { name: 'Confirm email link' })).toBeVisible();
  await page.goto('/auth/login'); await language(page, 'ru');
  await page.locator('form[action$="/sign-in"] button').click();
  expect(await page.locator('input[name=email]').evaluate(input => input.validationMessage)).toBe('Заполните это поле.');
  await page.locator('input[name=email]').fill('invalid');
  await page.locator('form[action$="/sign-in"] button').click();
  expect(await page.locator('input[name=email]').evaluate(input => input.validationMessage)).toBe('Введите корректный адрес электронной почты.');
  await page.locator('input[name=email]').fill('missing@example.test');
  await page.locator('input[name=password]').fill('wrong');
  await page.locator('form[action$="/sign-in"] button').click();
  await expect(page.getByRole('alert')).toContainText('Неверная почта или пароль.');
  expect(errors).toEqual([]); await context.close();
});

test('account language follows devices; inventory, review, history, filters and team translate', async ({ page, browser, baseURL }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await signIn(page); await language(page, 'ru');
  await expect(page.locator('nav')).toContainText('Остатки');
  await expect(page.locator('nav')).toContainText('Операции');
  await expect(page.locator('#theme-toggle')).toHaveAttribute('aria-label', /тему/);
  await expect(page.locator('stock-entry input')).toHaveAttribute('aria-label', 'Поиск или добавление');
  await expect(page.getByRole('button', { name: 'Добавить строку', exact: true })).toBeVisible();
  await expect(page.locator('#stock-grid')).toContainText('Количество');
  await expect(page.locator('#stock-grid [data-column-key=awaiting-quantity]')).toHaveCount(0);
  const secondContext = await browser.newContext({ baseURL, locale: 'en-US' });
  const second = await secondContext.newPage();
  await signIn(second);
  await expect(second.locator('html')).toHaveAttribute('lang', 'ru');
  await secondContext.close();
  const memberContext = await browser.newContext({ baseURL, locale: 'en-US' });
  const member = await memberContext.newPage(); await signIn(member, 'member@example.test');
  await expect(member.locator('html')).toHaveAttribute('lang', 'en');

  await expect.poll(() => page.locator('stock-entry').evaluate(element => element.busy)).toBe(false);
  await page.locator('stock-entry').evaluate(element => element.beginAdd());
  await expect(page.locator('.stock-entry-option-field').filter({ hasText: /^Закупка$/ })).toBeVisible();
  await page.locator('stock-entry input').press('Escape');

  let result = await action(page, 'add-entry', { type: 'purchase' });
  let draft = result._inventorySnapshot.draftSelection.at(-1);
  await page.reload();
  await page.getByRole('button', { name: 'Предпросмотр изменений', exact: true }).click();
  await expect(page.locator('#inventory-review')).toContainText('Заполните поле «Контрагент»');
  for (const [path, value] of [['party', 'Admin'], ['lines/0/name', 'Purchase'], ['lines/0/quantity', '12'], ['lines/0/location', 'Stock'], ['lines/0/article', 'Operations']]) {
    result = await action(page, 'save-field', { ...draft, path, value });
    draft = result._inventorySnapshot.draftSelection.find(row => row.id === draft.id);
  }
  await page.reload();
  await page.getByRole('button', { name: 'Предпросмотр изменений', exact: true }).click();
  await expect(page.locator('#pending-draft-grid')).toContainText('Закупка');
  await expect(page.locator('#pending-draft-grid')).toContainText('Purchase');
  await expect(page.getByRole('button', { name: 'Добавить: товар', exact: true })).toHaveText('+');
  await expect(page.getByRole('button', { name: 'Добавить поступление', exact: true })).toHaveCount(0);
  await expect(page.locator('#inventory-review')).toContainText('Итоговые остатки');
  const submitted = page.waitForResponse(response => new URL(response.url()).pathname === '/action/tover.stock.web/submit');
  await page.locator('#inventory-review').getByRole('button', { name: 'Принять изменения', exact: true }).click();
  expect((await submitted).status()).toBe(200);
  await expect(page.locator('#stock-grid')).toContainText('Purchase');
  await expect(page.locator('#stock-grid')).toContainText('Operations');
  await expect(member.locator('#stock-grid')).toContainText('Purchase');
  await expect(member.locator('#stock-grid')).toContainText('Quantity');

  await page.getByRole('button', { name: 'Фильтр: Товар', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Начинается с');
  await page.getByRole('button', { name: 'Закрыть', exact: true }).click();
  await page.getByRole('button', { name: 'История товара «Purchase»', exact: true }).click();
  await expect(page.locator('#history-panel')).toContainText('Дата записи');
  await expect(page.locator('#history-panel')).toContainText('Закупка');
  await page.screenshot({ path: '/tmp/tover-i18n-russian-history.png', fullPage: true });
  await page.getByRole('link', { name: 'Операции', exact: true }).click();
  await expect(page).toHaveTitle('Операции · Tover');
  await expect(page.locator('stock-operations')).toContainText('Закупка');
  await member.goto('/operations');
  await expect(member.locator('stock-operations tbody td[data-column-key=type]').first()).toHaveText('Purchase');
  await expect(page.locator('stock-operations tbody td[data-column-key=type]').first()).toHaveText('Закупка');
  await page.getByRole('button', { name: 'Фильтр: Операция', exact: true }).click();
  const typeFilter = page.getByRole('dialog');
  await typeFilter.getByLabel('Добавить условие', { exact: true }).selectOption('begins');
  await typeFilter.locator('input').fill('Прод');
  await expect(page.locator('.operations-empty')).toBeVisible();
  await typeFilter.getByRole('button', { name: 'Сбросить', exact: true }).click();
  await expect(page.locator('stock-operations tbody td[data-column-key=type]').first()).toHaveText('Закупка');
  await typeFilter.getByRole('button', { name: 'Закрыть', exact: true }).click();
  await page.getByRole('button', { name: /Показать сведения об операции/ }).first().click();
  await expect(page.locator('.operation-details')).toContainText('Принял');
  await expect(page.locator('.operation-details')).toContainText('Скорректировать');
  await page.getByRole('link', { name: 'Команда', exact: true }).click();
  await expect(page).toHaveTitle('Команда · Tover');
  await expect(page.getByRole('heading', { name: 'Участники', exact: true })).toBeVisible();
  await page.locator('details').filter({ has: page.locator('form[action$="/invite"]') }).locator('summary').click();
  await expect(page.getByRole('button', { name: 'Отправить приглашение', exact: true })).toBeVisible();
  await page.getByLabel('Электронная почта', { exact: true }).fill('owner@example.test');
  await page.getByRole('button', { name: 'Отправить приглашение', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Пользователь уже состоит в рабочем пространстве.');
  await expect(page.getByLabel('Электронная почта', { exact: true })).toHaveValue('owner@example.test');
  await page.goto('/workspace');
  await expect(page.getByRole('heading', { name: 'Рабочие пространства', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: '/tmp/tover-i18n-russian-mobile.png', fullPage: true });
  await page.evaluate(async () => {
    const grid = document.createElement('inventory-grid'); grid.id = 'i18n-conflict-grid'; document.body.append(grid);
    grid.model = { id: 'i18n-test', columns: [{ key: 'quantity', label: 'Количество' }], merges: [], editable: true,
      rows: [{ id: 'row', cells: [{ value: '2', target: { id: 'draft', path: 'lines/0/quantity', revision: 2 }, editor: { type: 'quantity' } }] }] };
    await grid.whenReady;
    grid._closedTarget = { id: 'draft', path: 'lines/0/quantity', revision: 1 }; grid._save(0, 0, '3');
  });
  await expect(page.locator('#i18n-conflict-grid').getByRole('button', { name: 'Использовать сохранённое значение', exact: true })).toBeVisible();
  await expect(page.locator('#i18n-conflict-grid')).toContainText('Сохранено: 2');
  await page.locator('#i18n-conflict-grid').evaluate(grid => grid.remove());
  await language(page, 'en');
  expect(errors).toEqual([]); await memberContext.close();
});

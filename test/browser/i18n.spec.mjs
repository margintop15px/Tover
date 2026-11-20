import { expect, test } from '@playwright/test';

async function language(page, value) {
  if (await page.locator('html').getAttribute('lang') === value) return;
  await Promise.all([page.waitForNavigation(), page.locator(`.language-toggle[value="${value}"]`).click()]);
  await expect(page.locator('html')).toHaveAttribute('lang', value);
}

test('guest language covers every authentication screen and persists after reload', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL, locale: 'ru-RU' });
  const page = await context.newPage();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  for (const [url, heading] of [['/auth/login', 'Войти'], ['/auth/signup', 'Создать аккаунт'], ['/auth/recover', 'Восстановить доступ'], ['/auth/confirm?type=email', 'Подтвердить ссылку из письма']]) {
    await page.goto(url);
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    await expect(page.getByRole('group', { name: 'Язык', exact: true }).getByRole('button')).toHaveCount(2);
    await expect(page.locator('.language-toggle[aria-pressed=true]')).toHaveText('RU');
    await expect(page.locator('.language-toggle[value=en]')).toHaveAccessibleName('Английский');
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


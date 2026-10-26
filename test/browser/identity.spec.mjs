import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';

const mailURL = process.env.IDENTITY_TEST_MAIL_URL || 'http://127.0.0.1:58025';
if (!['127.0.0.1', 'localhost', '::1'].includes(new URL(mailURL).hostname)) {
  throw new Error('These email tests require the local captured-mail profile');
}

async function emailLink(request, email, type, baseURL) {
  let link;
  await expect.poll(async () => {
    const listing = await (await request.get(`${mailURL}/api/v1/messages`)).json();
    for (const message of listing.messages || []) {
      if (!(message.To || []).some(recipient => recipient.Address === email)) continue;
      const body = await (await request.get(`${mailURL}/api/v1/message/${message.ID}`)).json();
      for (const [, href] of (body.HTML || '').matchAll(/href="([^"]+)"/g)) {
        const url = new URL(href.replaceAll('&amp;', '&'));
        if (url.searchParams.get('type') === type && url.searchParams.has('token_hash')) {
          // The existing mail profile targets port3030; this test app has disposable RDF storage.
          link = `${baseURL}${url.pathname}${url.search}`;
          return true;
        }
      }
    }
    return false;
  }, { timeout: 25_000, message: `Captured ${type} email is delivered` }).toBe(true);
  return link;
}

async function confirm(page, link) {
  await page.goto(link);
  await expect(page.getByRole('heading', { name: 'Confirm email link' })).toBeVisible();
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
}

async function openWorkspace(page, name) {
  await page.goto('/workspace');
  await page.locator('.management-table tbody tr').filter({ hasText: name }).getByRole('button', { name: 'Open', exact: true }).click();
  await expect(page.locator('#stock-grid')).toBeVisible();
}

async function logout(page) {
  await page.goto('/workspace');
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.goto('/');
  await expect(page).toHaveURL(/\/auth\/login$/);
}

test('real email signup, verification, invite, joining, recovery and logout', async ({ browser, page, request, baseURL }) => {
  const suffix = randomUUID();
  const owner = `browser-owner-${suffix}@example.test`;
  const recipient = `browser-member-${suffix}@example.test`;
  const workspace = `Browser workspace ${suffix}`;
  const password = `Test-${randomUUID()}-aA9`;
  await page.goto('/auth/signup');
  await page.getByLabel('Email', { exact: true }).fill(owner);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByLabel('Workspace name', { exact: true }).fill(workspace);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  const confirmation = await emailLink(request, owner, 'email', baseURL);
  await confirm(page, confirmation);
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(page.locator('.management-table')).toContainText(workspace);
  await openWorkspace(page, workspace);
  await expect(page.locator('#stock-grid')).toBeVisible();
  await page.goto('/team');
  await page.locator('details').filter({ has: page.locator('form[action$="/invite"]') }).locator('summary').click();
  await page.getByLabel('Email', { exact: true }).fill(recipient);
  await page.getByRole('button', { name: 'Send invitation', exact: true }).click();
  await expect(page.locator('#page-content')).toContainText(recipient);

  const memberContext = await browser.newContext({ baseURL });
  const member = await memberContext.newPage();
  try {
    await confirm(member, await emailLink(request, recipient, 'invite', baseURL));
    await expect(member).toHaveURL(/\/auth\/password$/);
    await member.getByLabel('New password', { exact: true }).fill(password);
    await member.getByRole('button', { name: 'Save password', exact: true }).click();
    await expect(member).toHaveURL(/\/workspace$/);
    await expect(member.locator('.management-table')).toContainText(workspace);
    await openWorkspace(member, workspace);
    await member.goto('/team');
    await expect(member.getByText('Workspace administration requires an owner or admin.')).toBeVisible();
    await logout(member);

    await member.goto('/auth/recover');
    await member.getByLabel('Email', { exact: true }).fill(recipient);
    await member.getByRole('button', { name: 'Send recovery email', exact: true }).click();
    await expect(member.getByRole('heading', { name: 'Check your email' })).toBeVisible();
    await confirm(member, await emailLink(request, recipient, 'recovery', baseURL));
    const changedPassword = `Changed-${randomUUID()}-aA9`;
    await member.getByLabel('New password', { exact: true }).fill(changedPassword);
    await member.getByRole('button', { name: 'Save password', exact: true }).click();
    await logout(member);
    await member.getByLabel('Email', { exact: true }).fill(recipient);
    await member.getByLabel('Password', { exact: true }).fill(changedPassword);
    await member.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(member).toHaveURL(/\/workspace$/);
    await logout(member);
  } finally {
    await memberContext.close();
  }
  await logout(page);
  await confirm(page, confirmation);
  await expect(page.getByRole('alert')).toBeVisible();
  await page.goto('/');
  await expect(page).toHaveURL(/\/auth\/login$/);
});

test('Russian signup and recovery preserve account language across real sessions', async ({ browser, request, baseURL }) => {
  const context = await browser.newContext({ baseURL, locale: 'ru-RU' });
  const page = await context.newPage();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const email = `i18n-browser-${randomUUID()}@example.test`;
  const password = `Test-${randomUUID()}-aA9`;
  const selectLanguage = async (target, locale) => {
    await target.waitForLoadState();
    const saved = target.waitForResponse(response => new URL(response.url()).pathname.endsWith('/set-language') && response.request().method() === 'POST');
    await Promise.all([target.waitForNavigation(), target.locator('.language-toggle').click()]);
    expect((await saved).status(), await target.locator('body').innerText()).toBe(303);
    await expect(target.locator('html')).toHaveAttribute('lang', locale);
  };
  try {
    await page.goto('/auth/signup');
    await page.getByLabel('Электронная почта', { exact: true }).fill(email);
    await page.getByLabel('Пароль', { exact: true }).fill(password);
    await page.getByLabel('Название рабочего пространства', { exact: true }).fill('I18n real identity');
    await page.getByRole('button', { name: 'Создать аккаунт', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Проверьте почту' })).toBeVisible();
    await page.goto(await emailLink(request, email, 'email', baseURL));
    await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
    await expect(page).toHaveURL(/\/workspace$/);
    await selectLanguage(page, 'en');
    const otherContext = await browser.newContext({ baseURL, locale: 'ru-RU' });
    try {
      const other = await otherContext.newPage();
      await other.goto('/auth/login');
      await other.locator('[name=email]').fill(email);
      await other.locator('[name=password]').fill(password);
      await other.locator('form[action$="/sign-in"] button').click();
      await expect(other).toHaveURL(/\/workspace$/);
      await expect(other.locator('html')).toHaveAttribute('lang', 'en');
      await selectLanguage(other, 'ru');
    } finally { await otherContext.close(); }
    await page.goto('/workspace');
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    await page.getByRole('button', { name: 'Выйти', exact: true }).click();
    await page.goto('/auth/recover');
    await page.getByLabel('Электронная почта', { exact: true }).fill(email);
    await page.getByRole('button', { name: 'Отправить письмо для восстановления', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Проверьте почту' })).toBeVisible();
    await page.goto(await emailLink(request, email, 'recovery', baseURL));
    await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
    await expect(page).toHaveURL(/\/auth\/password$/);
    await expect(page.getByRole('heading', { name: 'Установить пароль' })).toBeVisible();
    await selectLanguage(page, 'en');
    await selectLanguage(page, 'ru');
    await page.getByLabel('Новый пароль', { exact: true }).fill(`Changed-${randomUUID()}-aA9`);
    await page.getByRole('button', { name: 'Сохранить пароль', exact: true }).click();
    await expect(page).toHaveURL(/\/workspace$/);
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});

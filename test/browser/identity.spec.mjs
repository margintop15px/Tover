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

// Tover's own invitation email (journal worker over the profile's captured SMTP) links to the review page.
async function invitationLink(request, email, baseURL) {
  let link;
  await expect.poll(async () => {
    const listing = await (await request.get(`${mailURL}/api/v1/messages`)).json();
    for (const message of listing.messages || []) {
      if (!(message.To || []).some(recipient => recipient.Address === email)) continue;
      const body = await (await request.get(`${mailURL}/api/v1/message/${message.ID}`)).json();
      const match = (body.HTML || body.Text || '').match(/https?:\/\/[^\s"<]+(\/invitations\/[0-9a-f-]{36})/);
      if (match) { link = `${baseURL}${match[1]}`; return true; }
    }
    return false;
  }, { timeout: 40_000, message: 'Captured invitation email is delivered' }).toBe(true);
  return link;
}

async function confirm(page, link) {
  await page.goto(link);
  await expect(page.getByRole('heading', { name: 'Confirm email link' })).toBeVisible();
  await page.getByRole('button', { name: 'Confirm email', exact: true }).click();
}

// New accounts have no name yet, so profile setup comes before the requested page.
async function completeProfile(page, name) {
  await expect(page).toHaveURL(/\/account\/profile/);
  await page.locator('input[name=display-name]').fill(name);
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
}

async function signUp(page, email, password) {
  await page.goto('/auth/signup');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
}

async function logout(page) {
  await page.goto('/account/profile');
  await page.locator('form.sign-out').getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.goto('/');
  await expect(page).toHaveURL(/\/auth\/login$/);
}

test('real email signup, workspace creation, invitation, joining, recovery and sign-out', async ({ browser, page, request, baseURL }) => {
  const suffix = randomUUID();
  const owner = `browser-owner-${suffix}@example.test`;
  const recipient = `browser-member-${suffix}@example.test`;
  const workspace = `Browser workspace ${suffix}`;
  const password = `Test-${randomUUID()}-aA9`;
  await signUp(page, owner, password);
  const confirmation = await emailLink(request, owner, 'email', baseURL);
  await confirm(page, confirmation);
  await completeProfile(page, 'Browser owner');
  // Confirmation never creates a workspace; the owner creates one and opens it explicitly.
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(page.getByText('No workspaces yet.', { exact: true })).toBeVisible();
  await page.locator('#workspace-creation input[name=name]').fill(workspace);
  await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
  await expect(page.locator('#workspace-creation')).toContainText('Workspace created');
  await page.locator('#workspace-creation').getByRole('button', { name: 'Open workspace', exact: true }).click();
  await expect(page.locator('#stock-grid')).toBeVisible();
  await page.goto('/team');
  const invite = page.locator('form[action="/action/tover.invitations.issue.web/invite"]').first();
  const details = invite.locator('xpath=ancestor::details');
  if (await details.count() && !await details.evaluate(element => element.open)) await details.locator('summary').click();
  await invite.getByLabel('Email', { exact: true }).fill(recipient);
  await invite.getByRole('button', { name: 'Send invitation', exact: true }).click();
  await expect(page.locator('#page-content')).toContainText(recipient);

  const memberContext = await browser.newContext({ baseURL });
  const member = await memberContext.newPage();
  try {
    // The invitation link survives signup, confirmation and profile setup; acceptance stays explicit.
    const invitation = await invitationLink(request, recipient, baseURL);
    await member.goto(invitation);
    await expect(member).toHaveURL(/\/auth\/login$/);
    await signUp(member, recipient, password);
    await confirm(member, await emailLink(request, recipient, 'email', baseURL));
    await completeProfile(member, 'Browser member');
    await expect(member).toHaveURL(new RegExp(`${new URL(invitation).pathname}$`));
    await member.getByRole('button', { name: 'Accept invitation', exact: true }).click();
    await expect(member.getByText(`You joined ${workspace} as Member.`)).toBeVisible();
    await member.getByRole('button', { name: 'Open workspace', exact: true }).click();
    await expect(member.locator('#stock-grid')).toBeVisible();
    expect((await member.goto('/team')).status()).toBe(403);
    await expect(member.getByText('Workspace administration requires an owner or admin.')).toBeVisible();
    await logout(member);

    await member.goto('/auth/recover');
    await member.getByLabel('Email', { exact: true }).fill(recipient);
    await member.getByRole('button', { name: 'Send recovery email', exact: true }).click();
    await expect(member.getByRole('heading', { name: 'Check your email' })).toBeVisible();
    await member.goto(await emailLink(request, recipient, 'recovery', baseURL));
    const changedPassword = `Changed-${randomUUID()}-aA9`;
    await member.getByLabel('New password', { exact: true }).fill(changedPassword);
    await member.getByRole('button', { name: 'Reset password', exact: true }).click();
    await expect(member).toHaveURL(/\/workspace$/);
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
    await Promise.all([target.waitForNavigation(), target.locator(`.language-toggle[value="${locale}"]`).click()]);
    expect((await saved).status(), await target.locator('body').innerText()).toBe(303);
    await expect(target.locator('html')).toHaveAttribute('lang', locale);
  };
  try {
    await page.goto('/auth/signup');
    await page.getByLabel('Электронная почта', { exact: true }).fill(email);
    await page.getByLabel('Пароль', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Создать аккаунт', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Проверьте почту' })).toBeVisible();
    await page.goto(await emailLink(request, email, 'email', baseURL));
    await page.getByRole('button', { name: 'Подтвердить адрес', exact: true }).click();
    // A new account has no name yet, so profile setup comes first and then continues to the workspace chooser.
    await expect(page).toHaveURL(/\/account\/profile/);
    await page.locator('input[name=display-name]').fill('Браузер');
    await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
    await expect(page).toHaveURL(/\/workspace$/);
    await selectLanguage(page, 'en');
    const otherContext = await browser.newContext({ baseURL, locale: 'ru-RU' });
    try {
      const other = await otherContext.newPage();
      await other.goto('/auth/login');
      await other.locator('[name=email]').fill(email);
      await other.locator('[name=password]').fill(password);
      await other.locator('form[action$="/sign-in"] button[type=submit]').click();
      await expect(other).toHaveURL(/\/workspace$/);
      await expect(other.locator('html')).toHaveAttribute('lang', 'en');
      await selectLanguage(other, 'ru');
    } finally { await otherContext.close(); }
    await page.goto('/workspace');
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    await page.goto('/account/profile');
    await page.locator('form.sign-out').getByRole('button', { name: 'Выйти', exact: true }).click();
    await page.goto('/auth/recover');
    await page.getByLabel('Электронная почта', { exact: true }).fill(email);
    await page.getByRole('button', { name: 'Отправить письмо для восстановления', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Проверьте почту' })).toBeVisible();
    await page.goto(await emailLink(request, email, 'recovery', baseURL));
    await expect(page.getByRole('heading', { name: 'Установить пароль' })).toBeVisible();
    await selectLanguage(page, 'en');
    await selectLanguage(page, 'ru');
    await page.getByLabel('Новый пароль', { exact: true }).fill(`Changed-${randomUUID()}-aA9`);
    await page.getByRole('button', { name: 'Сбросить пароль', exact: true }).click();
    await expect(page).toHaveURL(/\/workspace$/);
    await expect(page.locator('html')).toHaveAttribute('lang', 'ru');
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});

test('the password control shows and hides by keyboard without submitting', async ({ page }) => {
  for (const [path, label] of [['/auth/login', 'Password'], ['/auth/signup', 'Password'], ['/auth/confirm?token_hash=x&type=recovery', 'New password']]) {
    await page.goto(path);
    const url = page.url();
    const input = page.getByLabel(label, { exact: true });
    const toggle = page.getByRole('button', { name: 'Show password', exact: true });
    await input.fill('visible secret');
    await expect(input).toHaveAttribute('type', 'password');
    await input.focus();
    await page.keyboard.press('Tab');
    await expect(toggle).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(input).toHaveAttribute('type', 'text');
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await page.keyboard.press('Space');
    await expect(input).toHaveAttribute('type', 'password');
    await expect(input).toHaveValue('visible secret');
    expect(page.url()).toBe(url);
    await toggle.click();
    await page.reload();
    await expect(page.getByLabel(label, { exact: true })).toHaveAttribute('type', 'password');
  }
});

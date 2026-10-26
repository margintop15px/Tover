import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const messages = JSON.parse(readFileSync(new URL('../../resources/i18n/messages.json', import.meta.url), 'utf8'));
const formFor = (page, action) => page.locator(`form[action="/action/${action}"]`);
const disclosureFor = form => form.locator('xpath=ancestor::details');
const rowFor = (page, text) => page.locator('.management-table tbody tr').filter({ hasText: text });

async function login(page, email, locale) {
  await page.goto('/auth/login');
  const form = formFor(page, 'tover.auth.web/sign-in');
  await form.locator('[name=email]').fill(email);
  await form.locator('[name=password]').fill('tover-development');
  await Promise.all([page.waitForNavigation(), form.locator('button').click()]);
  if (await page.locator('html').getAttribute('lang') !== locale) {
    await Promise.all([page.waitForNavigation(), page.locator('.language-toggle').click()]);
  }
  await expect(page.locator('html')).toHaveAttribute('lang', locale);
}

async function reveal(form) {
  const details = disclosureFor(form);
  if (!await details.evaluate(element => element.open)) await details.locator('summary').click();
  await expect(details).toHaveAttribute('open', '');
  return details;
}

async function assertTranslated(page, locale) {
  if (locale !== 'ru') return;
  const untranslated = await page.locator('.management-page').evaluate(root => {
    const strings = [];
    for (const element of [root, ...root.querySelectorAll('*')]) {
      if (!element.checkVisibility()) continue;
      for (const child of element.childNodes) {
        if (child.nodeType === Node.TEXT_NODE && child.textContent.trim()) strings.push(child.textContent.trim());
      }
    }
    return strings;
  });
  const english = new Set(Object.keys(messages.en).filter(key => messages.ru[key] !== messages.en[key]).map(key => messages.en[key]));
  expect(untranslated.filter(text => english.has(text))).toEqual([]);
}

for (const locale of ['en', 'ru']) {
  const t = key => {
    expect(messages[locale][key], `${locale}: ${key}`).toBeDefined();
    return messages[locale][key];
  };

  test(`${locale}: zero, one and multiple workspaces expose roles, current marker and switching`, async ({ page }) => {
    await login(page, 'zero@example.test', locale);
    await page.goto('/workspace');
    const create = formFor(page, 'tover.workspaces.web/create-workspace');
    await expect(disclosureFor(create)).toHaveAttribute('open', '');
    await expect(page.getByText(t('No workspaces yet.'), { exact: true })).toBeVisible();
    await expect(page.locator('.management-table')).toHaveCount(0);
    await assertTranslated(page, locale);

    await formFor(page, 'tover.auth.web/sign-out').locator('button').click();
    await login(page, 'member@example.test', locale);
    await page.goto('/workspace');
    await expect(page.locator('.management-table tbody tr')).toHaveCount(1);
    await expect(page.locator('.management-current')).toHaveText(t('Current workspace'));
    await expect(page.locator('.management-role').last()).toHaveText(t('Member'));
    await expect(disclosureFor(create)).not.toHaveAttribute('open', '');
    await expect(page.getByRole('link', { name: t('Team'), exact: true })).toHaveCount(0);

    await formFor(page, 'tover.auth.web/sign-out').locator('button').click();
    await login(page, 'owner@example.test', locale);
    await page.goto('/workspace');
    await expect(page.locator('.management-table tbody tr')).toHaveCount(2);
    await expect(rowFor(page, 'Development workspace').locator('.management-role')).toHaveText(t('Owner'));
    await rowFor(page, 'Other workspace').getByRole('button', { name: t('Open'), exact: true }).click();
    await expect(page.locator('#stock-grid')).toBeVisible();
    await page.goto('/workspace');
    await expect(rowFor(page, 'Other workspace').locator('.management-current')).toHaveText(t('Current workspace'));
    await expect(rowFor(page, 'Development workspace').locator('.management-current')).toHaveCount(0);
    await assertTranslated(page, locale);
  });

  test(`${locale}: team tables show names, member states and pending/expired invitation actions`, async ({ page }) => {
    await login(page, 'manager@example.test', locale);
    await page.goto('/team');
    await expect(page.locator('.management-subtitle')).toHaveText('Management workspace');
    await expect(page.locator('.management-count')).toHaveText(['4', '2']);
    const owner = rowFor(page, 'manager@example.test');
    await expect(owner.locator('.management-name')).toHaveText('Alexandra Workspace Manager With A Long Name');
    await expect(owner.locator('.management-secondary')).toHaveText('manager@example.test');
    await expect(rowFor(page, 'admin@example.test').locator('.management-secondary')).toHaveCount(0);
    for (const [email, status, recovery] of [
      ['manager@example.test', 'Active', true], ['admin@example.test', 'Active', true],
      ['suspended-member-with-an-intentionally-long-email-address@example.test', 'Suspended', false], ['invited@example.test', 'Invited', false],
    ]) {
      const row = rowFor(page, email);
      await expect(row.locator('.management-status')).toHaveText(t(status));
      await expect(row.getByRole('button', { name: t('Send recovery email'), exact: true })).toHaveCount(recovery ? 1 : 0);
    }
    for (const [email, status] of [['pending@example.test', 'Pending'], ['expired@example.test', 'Expired']]) {
      const row = rowFor(page, email);
      await expect(row.locator('.management-status')).toHaveText(t(status));
      for (const action of ['Resend', 'Cancel']) await expect(row.getByRole('button', { name: t(action), exact: true })).toBeVisible();
    }
    await assertTranslated(page, locale);

    await page.goto('/workspace');
    await formFor(page, 'tover.auth.web/sign-out').locator('button').click();
    await login(page, 'admin@example.test', locale);
    await page.goto('/team');
    await expect(page.getByRole('heading', { name: t('Team'), exact: true })).toBeVisible();
    await expect(formFor(page, 'tover.teams.core/invite')).toHaveCount(1);
    await page.goto('/workspace');
    await formFor(page, 'tover.auth.web/sign-out').locator('button').click();
    await login(page, 'member@example.test', locale);
    expect((await page.goto('/team')).status()).toBe(403);
    await expect(page.locator('body')).toHaveText(t('Workspace administration requires an owner or admin.'));
  });

  test(`${locale}: disclosures retain blank invalid and uncertain input, then collapse on confirmed success`, async ({ page }) => {
    await login(page, 'owner@example.test', locale);
    await page.goto('/workspace');
    const create = formFor(page, 'tover.workspaces.web/create-workspace');
    const createDetails = disclosureFor(create);
    await createDetails.locator('summary').focus();
    await page.keyboard.press('Enter');
    await expect(createDetails).toHaveAttribute('open', '');
    await create.evaluate(element => { element.noValidate = true; element.requestSubmit(); });
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(createDetails).toHaveAttribute('open', '');
    await expect(create.locator('[name=name]')).toHaveValue('');
    await createDetails.locator('summary').click();
    await expect(page.getByRole('alert')).toBeVisible();

    await rowFor(page, 'Development workspace').getByRole('button', { name: t('Open'), exact: true }).click();
    await page.goto('/team');
    await expect(page.getByText(t('No invitations yet.'), { exact: true })).toBeVisible();
    const invite = formFor(page, 'tover.teams.core/invite');
    const details = await reveal(invite);
    await expect(invite.locator('[name=role]')).toHaveValue('member');
    await invite.evaluate(element => { element.noValidate = true; element.requestSubmit(); });
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(details).toHaveAttribute('open', '');
    await expect(invite.locator('[name=email]')).toHaveValue('');
    await invite.locator('[name=email]').fill('owner@example.test');
    await invite.locator('[name=role]').selectOption('admin');
    await invite.locator('button').click();
    await expect(page.getByRole('alert')).toContainText(t('This user is already a workspace member.'));
    await expect(invite.locator('[name=role]')).toHaveValue('admin');
    await details.locator('summary').click();
    await expect(page.getByRole('alert')).toBeVisible();
    await reveal(invite);

    let attempts = 0;
    await page.route('**/action/tover.teams.core/invite', route => { attempts += 1; return route.abort('failed'); });
    const email = `management-${randomUUID()}@example.test`;
    await invite.locator('[name=email]').fill(email);
    await invite.locator('button').click();
    await expect(page.locator('#bel-navigation-notice')).toContainText(t('The request could not be confirmed. Your input has been kept; check the result before trying again.'));
    await expect(details).toHaveAttribute('open', '');
    await expect(invite.locator('[name=email]')).toHaveValue(email);
    await expect(invite.locator('[name=role]')).toHaveValue('admin');
    await page.waitForTimeout(1200);
    expect(attempts).toBe(1);
    await page.unroute('**/action/tover.teams.core/invite');
    await invite.locator('button').click();
    await expect(rowFor(page, email)).toBeVisible();
    await expect(details).not.toHaveAttribute('open', '');
    await reveal(invite);
    await expect(invite.locator('[name=email]')).toHaveValue('');
    await expect(invite.locator('[name=role]')).toHaveValue('member');
    await rowFor(page, email).getByRole('button', { name: t('Cancel'), exact: true }).click();
    await expect(rowFor(page, email)).toHaveCount(0);
  });

  test(`${locale}: narrow management pages preserve actions and wrap content in both themes`, async ({ page }) => {
    await login(page, 'manager@example.test', locale);
    for (const theme of ['light', 'dark']) {
      await page.evaluate(value => localStorage.setItem('tover-v2-theme', value), theme);
      for (const path of ['/team', '/workspace']) {
        await page.setViewportSize({ width: 390, height: 844 });
        await page.goto(path);
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await expect(page.locator('.management-mobile-meta').first()).toBeVisible();
        await expect(page.locator('.management-table .management-role').first()).not.toBeVisible();
        for (const button of await page.locator('.management-actions button:visible').all()) {
          const bounds = await button.boundingBox();
          expect(bounds.height).toBeGreaterThanOrEqual(44);
          expect(bounds.width).toBeGreaterThanOrEqual(44);
        }
        await assertTranslated(page, locale);
      }
    }
  });

  test(`${locale}: native POST fallback creates, opens and invites without JavaScript`, async ({ browser, baseURL }) => {
    const context = await browser.newContext({ baseURL, javaScriptEnabled: false, locale: locale === 'ru' ? 'ru-RU' : 'en-US' });
    const page = await context.newPage();
    try {
      await login(page, `native-${locale}@example.test`, locale);
      const create = formFor(page, 'tover.workspaces.web/create-workspace');
      const workspace = `Native ${locale} ${randomUUID()}`;
      await expect(disclosureFor(create)).toHaveAttribute('open', '');
      await create.locator('[name=name]').fill(workspace);
      await Promise.all([page.waitForNavigation(), create.locator('button').click()]);
      await page.goto('/workspace');
      await expect(disclosureFor(create)).not.toHaveAttribute('open', '');
      await Promise.all([page.waitForNavigation(), rowFor(page, workspace).getByRole('button', { name: t('Open'), exact: true }).click()]);
      await page.goto('/team');
      const invite = formFor(page, 'tover.teams.core/invite');
      await disclosureFor(invite).locator('summary').click();
      const email = `native-invite-${randomUUID()}@example.test`;
      await invite.locator('[name=email]').fill(email);
      await Promise.all([page.waitForNavigation(), invite.locator('button').click()]);
      await expect(rowFor(page, email)).toBeVisible();
      await expect(disclosureFor(invite)).not.toHaveAttribute('open', '');
      await expect(page.getByRole('alert')).toHaveCount(0);
    } finally { await context.close(); }
  });
}

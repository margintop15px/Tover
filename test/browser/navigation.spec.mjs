import { expect, test } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { signIn } from './auth.fixture.mjs';
const stockGrid = page => page.locator('#stock-grid');

test.beforeEach(async ({ page }) => signIn(page));

async function markDocument(page) {
  await page.evaluate(() => { window.navigationDocument = 'original'; });
}

async function unchangedDocument(page) {
  expect(await page.evaluate(() => window.navigationDocument)).toBe('original');
}

for (const failure of ['http', 'network']) {
  test(`failed navigation retains content and URL and supports retry (${failure})`, async ({ page }) => {
    await markDocument(page);
    await page.route('**/team?datastar=*', route => failure === 'http'
      ? route.fulfill({ status: 503, body: 'Unavailable' }) : route.abort('failed'));
    await page.getByRole('link', { name: 'Team', exact: true }).click();
    await expect(page.locator('#bel-navigation-notice')).toContainText('could not be loaded');
    await expect(page).toHaveURL(/\/$/);
    await expect(stockGrid(page)).toBeVisible();
    await unchangedDocument(page);
    await page.unroute('**/team?datastar=*');
    await page.locator('#bel-navigation-notice').getByRole('button', { name: 'Retry' }).click();
    await expect(page).toHaveURL(/\/team$/);
    await expect(page.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
  });
}

test('a superseded navigation cannot replace the latest requested page', async ({ page }) => {
  let release;
  let started;
  const blocked = new Promise(resolve => { release = resolve; });
  const requested = new Promise(resolve => { started = resolve; });
  await page.route('**/team?datastar=*', async route => {
    const response = await route.fetch();
    started();
    await blocked;
    await route.fulfill({ response }).catch(() => {});
  });
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await requested;
  await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  release();
  await expect(page.getByRole('heading', { name: 'Workspaces', exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/workspace$/);
});

test('modified internal clicks retain the native new-tab behavior', async ({ page, context }) => {
  await markDocument(page);
  const opened = context.waitForEvent('page');
  await page.getByRole('link', { name: 'Team', exact: true }).click({ modifiers: ['ControlOrMeta'] });
  const other = await opened;
  await expect(other).toHaveURL(/\/team$/);
  await expect(other.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  await unchangedDocument(page);
  await other.close();
});

test('internal links preserve native exclusions and enhance ordinary clicks', async ({ page }) => {
  await markDocument(page);
  const requests = [];
  page.on('request', request => {
    if (request.headers()['datastar-request'] === 'true' && new URL(request.url()).searchParams.has('nativeLinkProbe')) {
      requests.push(request);
    }
  });
  await page.evaluate(() => {
    const source = document.querySelector('.app-nav a[href="/"]');
    const root = document.createElement('div');
    for (const [name, href, attrs] of [
      ['ready', '/', {}],
      ['hash', '/#native-link-target', {}],
      ['download', '/team?nativeLinkProbe=download', { download: '' }],
      ['blank', '/team?nativeLinkProbe=blank', { target: '_blank' }],
      ['named', '/team?nativeLinkProbe=named', { target: 'native-link-window' }],
      ['external', 'https://native-link.invalid/?nativeLinkProbe=external', {}],
      ['ordinary', '/team?nativeLinkProbe=ordinary', {}],
    ]) {
      const link = source.cloneNode(false);
      link.removeAttribute('id');
      link.dataset.linkProbe = name;
      link.href = href;
      link.textContent = name;
      for (const [key, value] of Object.entries(attrs)) link.setAttribute(key, value);
      root.append(link);
    }
    const target = document.createElement('span');
    target.id = 'native-link-target';
    root.append(target);
    document.body.append(root);
  });
  const probe = name => page.locator(`[data-link-probe="${name}"]`).evaluate(link => {
    let prevented;
    document.addEventListener('click', event => {
      prevented = event.defaultPrevented;
      event.preventDefault(); // Observe the app first, then suppress native downloads/popups.
    }, { once: true });
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
    return prevented;
  });
  await expect.poll(() => probe('ready')).toBe(true);
  for (const name of ['hash', 'download', 'blank', 'named', 'external']) {
    expect(await probe(name), name).toBe(false);
  }
  expect(requests).toHaveLength(0);
  await page.locator('[data-link-probe="hash"]').evaluate(link => link.click());
  await expect(page).toHaveURL(/\/#native-link-target$/);
  await unchangedDocument(page);
  expect(await probe('ordinary')).toBe(true);
  await expect(page).toHaveURL(/\/team\?nativeLinkProbe=ordinary$/);
  await expect(page.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
  await unchangedDocument(page);
  expect(requests).toHaveLength(1);
});

test('query strings, page titles, focus and history scroll positions follow navigation', async ({ page }) => {
  await page.evaluate(() => { document.body.style.minHeight = '3000px'; scrollTo(0, 700); });
  await expect.poll(() => page.evaluate(() => history.state.scroll?.[1])).toBe(700);
  await page.evaluate(async () => {
    const { actions } = await import('/datastar.js');
    await actions.navigate({ el: document.querySelector('#bel-page'), cleanups: new Map(), error: text => new Error(text) },
      '/workspace?from=navigation');
  });
  await expect(page).toHaveURL(/\/workspace\?from=navigation$/);
  await expect(page).toHaveTitle(/Workspaces/);
  await expect(page.getByRole('heading', { name: 'Workspaces', exact: true })).toBeFocused();
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(0);
  await page.evaluate(() => scrollTo(0, 350));
  await expect.poll(() => page.evaluate(() => history.state.scroll?.[1])).toBe(350);
  await page.goBack();
  await expect(page).toHaveURL(/\/$/);
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(700);
  await page.goForward();
  await expect(page).toHaveURL(/\/workspace\?from=navigation$/);
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(350);
});

test('failed Back navigation falls back to the requested document', async ({ page }) => {
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await expect(page).toHaveURL(/\/team$/);
  await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await markDocument(page);
  await page.route('**/team?datastar=*', route => route.fulfill({ status: 503, body: 'Unavailable' }));
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/team$/);
  expect(await page.evaluate(() => window.navigationDocument)).toBeUndefined();
});

test('internal form failures keep input and never retry an uncertain mutation', async ({ page }) => {
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await expect(page).toHaveURL(/\/team$/);
  await markDocument(page);
  const form = page.locator('form[action="/action/tover.invitations.issue.web/invite"]');
  await form.locator('xpath=ancestor::details').locator('summary').click();
  await form.locator('[name="email"]').fill('owner@example.test');
  await form.locator('[name="role"]').selectOption('admin');
  const historyLength = await page.evaluate(() => history.length);
  await form.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.getByRole('alert')).toContainText('That person is already on the team.');
  await expect(form.locator('[name="email"]')).toHaveValue('owner@example.test');
  await expect(form.locator('[name="role"]')).toHaveValue('admin');
  expect(await page.evaluate(() => history.length)).toBe(historyLength);
  await unchangedDocument(page);

  let attempts = 0;
  await page.route('**/action/tover.invitations.issue.web/invite', route => { attempts += 1; return route.abort('failed'); });
  await form.locator('[name="email"]').fill('uncertain@example.test');
  await form.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.locator('#bel-navigation-notice')).toContainText('could not be confirmed');
  await expect(form.locator('[name="email"]')).toHaveValue('uncertain@example.test');
  await page.waitForTimeout(1200);
  expect(attempts).toBe(1);
  await unchangedDocument(page);
});

test('a successful invitation opens its outcome as one new Team history entry', async ({ page }) => {
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await expect(page).toHaveURL(/\/team$/);
  await markDocument(page);
  const form = page.locator('form[action="/action/tover.invitations.issue.web/invite"]');
  await form.locator('xpath=ancestor::details').locator('summary').click();
  const email = `navigation-${Date.now()}@example.test`;
  await form.locator('[name="email"]').fill(email);
  const historyLength = await page.evaluate(() => history.length);
  await form.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.locator('#page-content')).toContainText(email);
  await expect(form.locator('[name="email"]')).toHaveValue('');
  // The outcome names the offer, never the recipient's email, in the URL (05 · Show the accepted invitation immediately).
  await expect(page).toHaveURL(/\/team\?invitation=[0-9a-f-]{36}$/);
  await expect(page).toHaveTitle(/Team/);
  expect(await page.evaluate(() => history.length)).toBe(historyLength + 1);
  await unchangedDocument(page);
});

test('action page effects commit a different destination and support Back navigation', async ({ page }) => {
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await expect(page).toHaveURL(/\/team$/);
  await markDocument(page);
  await page.route('**/action/tover.invitations.issue.web/invite', route => route.fulfill({
    status: 200, contentType: 'text/html',
    body: '<div id="bel-page" data-bel-navigation="true" data-bel-url="/workspace?from=effect" data-bel-title="Workspaces · Tover" data-bel-page="tover.inventory.web/workspaces"><main id="page-content"><h1>Effect destination</h1></main></div>',
  }));
  const historyLength = await page.evaluate(() => history.length);
  const form = page.locator('form[action="/action/tover.invitations.issue.web/invite"]');
  await form.locator('xpath=ancestor::details').locator('summary').click();
  await form.locator('[name="email"]').fill('destination@example.test');
  await page.evaluate(() => { document.body.style.minHeight = '3000px'; scrollTo(0, 500); });
  await form.evaluate(element => element.requestSubmit());
  await expect(page).toHaveURL(/\/workspace\?from=effect$/);
  await expect(page).toHaveTitle('Workspaces · Tover');
  await expect(page.getByRole('heading', { name: 'Effect destination' })).toBeFocused();
  expect(await page.evaluate(() => history.length)).toBe(historyLength + 1);
  await unchangedDocument(page);
  await page.goBack();
  await expect(page).toHaveURL(/\/team$/);
  await expect(page.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(500);
  await unchangedDocument(page);
});

for (const order of ['action-first', 'navigation-first']) {
  test(`a late action page effect cannot replace content after newer failed navigation (${order})`, async ({ page }) => {
    await page.getByRole('link', { name: 'Team', exact: true }).click();
    await expect(page).toHaveURL(/\/team$/);
    let releaseAction;
    let actionStarted;
    const actionBlocked = new Promise(resolve => { releaseAction = resolve; });
    const actionRequested = new Promise(resolve => { actionStarted = resolve; });
    await page.route('**/action/tover.invitations.issue.web/invite', async route => {
      actionStarted();
      await actionBlocked;
      await route.fulfill({ status: 200, contentType: 'text/html',
        body: '<div id="bel-page" data-bel-navigation="true" data-bel-url="/catalogues" data-bel-title="Stale effect" data-bel-page="tover.catalogues.grid/catalogue"><h1>Stale effect</h1></div>' });
    });
    let releaseNavigation;
    let navigationStarted;
    const navigationBlocked = new Promise(resolve => { releaseNavigation = resolve; });
    const navigationRequested = new Promise(resolve => { navigationStarted = resolve; });
    await page.route('**/workspace?datastar=*', async route => {
      navigationStarted();
      await navigationBlocked;
      await route.fulfill({ status: 503, body: 'Unavailable' });
    });
    const form = page.locator('form[action="/action/tover.invitations.issue.web/invite"]');
    await form.locator('xpath=ancestor::details').locator('summary').click();
    await form.locator('[name="email"]').fill('late@example.test');
    await form.getByRole('button', { name: 'Send invitation' }).click();
    await actionRequested;
    await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
    await navigationRequested;
    const actionResponse = page.waitForResponse('**/action/tover.invitations.issue.web/invite');
    if (order === 'navigation-first') {
      releaseNavigation();
      await expect(page.locator('#bel-navigation-notice')).toContainText('could not be loaded');
    }
    releaseAction();
    await actionResponse;
    await page.waitForTimeout(100);
    if (order === 'action-first') releaseNavigation();
    await expect(page.locator('#bel-navigation-notice')).toContainText('could not be loaded');
    await expect(page).toHaveURL(/\/team$/);
    await expect(page.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Stale effect' })).toHaveCount(0);
  });
}


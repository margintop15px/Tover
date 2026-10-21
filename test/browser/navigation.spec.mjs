import { expect, test } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { signIn } from './auth.fixture.mjs';

test.beforeEach(async ({ page }) => signIn(page));

async function markDocument(page) {
  await page.evaluate(() => { window.navigationDocument = 'original'; });
}

async function unchangedDocument(page) {
  expect(await page.evaluate(() => window.navigationDocument)).toBe('original');
}

async function addDraft(page, name) {
  const entry = page.locator('#stock-entry');
  await entry.getByRole('combobox', { name: 'Add stock row', exact: true }).fill(name);
  await entry.getByRole('combobox', { name: 'Add stock row', exact: true }).press('Enter');
  await entry.getByRole('button', { name: 'Add row', exact: true }).click();
  await expect(page.locator('#stock-revogrid')).toContainText(name);
}

test('internal history preserves the workbench and leaving inventory discards drafts', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await markDocument(page);
  await addDraft(page, 'Navigation draft');
  await page.evaluate(() => { document.querySelector('#stock-revogrid').navigationGrid = true; });
  await page.getByRole('link', { name: 'Operations', exact: true }).click();
  await expect(page).toHaveURL(/\/operations$/);
  await expect(page.locator('#operations-history')).toBeVisible();
  await unchangedDocument(page);
  expect(await page.evaluate(() => document.querySelector('#stock-revogrid').navigationGrid)).toBe(true);
  await page.goBack();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.locator('#stock-revogrid')).toContainText('Navigation draft');
  await page.goForward();
  await expect(page.locator('#operations-history')).toBeVisible();
  await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(page.locator('#stock-revogrid')).toHaveCount(0);
  await unchangedDocument(page);
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await expect(page.locator('#stock-revogrid')).toBeVisible();
  await expect(page.locator('#page-content')).toBeFocused();
  await expect(page.locator('#stock-revogrid')).not.toContainText('Navigation draft');
  await addDraft(page, 'Fresh navigation draft');
  await unchangedDocument(page);
  expect(errors).toEqual([]);
});

test('direct internal links render their content and share one realtime connection', async ({ page }) => {
  const streams = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/_bel/realtime/stream') streams.push(request);
  });
  await page.goto('/team');
  await expect(page.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
  await expect.poll(() => streams.length).toBe(1);
  await markDocument(page);
  for (const [name, url] of [['Workspaces', '/workspace'], ['Stock', '/'], ['Operations', '/operations'], ['Team', '/team']]) {
    await page.getByRole('link', { name, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${url === '/' ? '\\/' : url}$`));
    await unchangedDocument(page);
  }
  expect(streams).toHaveLength(1);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Team', exact: true })).toBeVisible();
});

for (const failure of ['http', 'network']) {
  test(`failed navigation retains content and URL and supports retry (${failure})`, async ({ page }) => {
    await markDocument(page);
    await page.route('**/team?datastar=*', route => failure === 'http'
      ? route.fulfill({ status: 503, body: 'Unavailable' }) : route.abort('failed'));
    await page.getByRole('link', { name: 'Team', exact: true }).click();
    await expect(page.locator('#bel-navigation-notice')).toContainText('could not be loaded');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.locator('#stock-revogrid')).toBeVisible();
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
  const form = page.locator('form[action="/action/tover.teams.core/invite"]');
  await form.locator('[name="email"]').fill('owner@example.test');
  await form.locator('[name="role"]').selectOption('admin');
  const historyLength = await page.evaluate(() => history.length);
  await form.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.getByRole('alert')).toContainText('member exists');
  await expect(form.locator('[name="email"]')).toHaveValue('owner@example.test');
  await expect(form.locator('[name="role"]')).toHaveValue('admin');
  expect(await page.evaluate(() => history.length)).toBe(historyLength);
  await unchangedDocument(page);

  let attempts = 0;
  await page.route('**/action/tover.teams.core/invite', route => { attempts += 1; return route.abort('failed'); });
  await form.locator('[name="email"]').fill('uncertain@example.test');
  await form.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.locator('#bel-navigation-notice')).toContainText('could not be confirmed');
  await expect(form.locator('[name="email"]')).toHaveValue('uncertain@example.test');
  await page.waitForTimeout(1200);
  expect(attempts).toBe(1);
  await unchangedDocument(page);
});

test('successful Team actions refresh the page without another history entry', async ({ page }) => {
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await expect(page).toHaveURL(/\/team$/);
  await markDocument(page);
  const form = page.locator('form[action="/action/tover.teams.core/invite"]');
  const email = `navigation-${Date.now()}@example.test`;
  await form.locator('[name="email"]').fill(email);
  const historyLength = await page.evaluate(() => history.length);
  await form.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.locator('#page-content')).toContainText(email);
  await expect(form.locator('[name="email"]')).toHaveValue('');
  await expect(page).toHaveURL(/\/team$/);
  await expect(page).toHaveTitle(/Team/);
  expect(await page.evaluate(() => history.length)).toBe(historyLength);
  await unchangedDocument(page);
});

test('action page effects commit a different destination and support Back navigation', async ({ page }) => {
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await expect(page).toHaveURL(/\/team$/);
  await markDocument(page);
  await page.route('**/action/tover.teams.core/invite', route => route.fulfill({
    status: 200, contentType: 'text/html',
    body: '<div id="bel-page" data-bel-navigation="true" data-bel-url="/workspace?from=effect" data-bel-title="Workspaces · Tover" data-bel-page="tover.stock.web/workspaces"><main id="page-content"><h1>Effect destination</h1></main></div>',
  }));
  const historyLength = await page.evaluate(() => history.length);
  const form = page.locator('form[action="/action/tover.teams.core/invite"]');
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
    await page.route('**/action/tover.teams.core/invite', async route => {
      actionStarted();
      await actionBlocked;
      await route.fulfill({ status: 200, contentType: 'text/html',
        body: '<div id="bel-page" data-bel-navigation="true" data-bel-url="/operations" data-bel-title="Stale effect" data-bel-page="tover.operations-web/page"><h1>Stale effect</h1></div>' });
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
    const form = page.locator('form[action="/action/tover.teams.core/invite"]');
    await form.locator('[name="email"]').fill('late@example.test');
    await form.getByRole('button', { name: 'Send invitation' }).click();
    await actionRequested;
    await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
    await navigationRequested;
    const actionResponse = page.waitForResponse('**/action/tover.teams.core/invite');
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

test('late inventory view responses cannot patch a later workbench instance', async ({ page }) => {
  let release;
  let started;
  const blocked = new Promise(resolve => { release = resolve; });
  const requested = new Promise(resolve => { started = resolve; });
  await page.route('**/view/tover.stock.web/draft-preview', async route => {
    started();
    await blocked;
    await route.fulfill({ status: 200, contentType: 'text/html',
      body: '<div id="stock-draft-preview">Stale inventory response</div>' }).catch(() => {});
  });
  await addDraft(page, 'Pending inventory view');
  await page.getByRole('button', { name: 'Preview changes', exact: true }).click();
  await requested;
  await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await expect(page.locator('#stock-revogrid')).toBeVisible();
  release();
  await expect(page.locator('#stock-draft-preview')).not.toContainText('Stale inventory response');
  await expect(page.locator('#stock-revogrid')).not.toContainText('Pending inventory view');
});

test('shared pages retain consistent appearance and navigation at desktop and narrow widths', async ({ page }) => {
  const directory = '/tmp/tover-navigation-icons';
  mkdirSync(directory, { recursive: true });
  const appearance = () => page.evaluate(() => {
    const style = getComputedStyle(document.body);
    return { background: style.backgroundColor, color: style.color, font: style.fontFamily };
  });
  const checkInternalLayout = async () => {
    await expect(page.locator('body > header, .account-nav, .demo-label')).toHaveCount(0);
    const toggle = await page.locator('#theme-toggle').boundingBox();
    for (const name of ['Workspaces', 'Team']) {
      const link = page.getByRole('link', { name, exact: true });
      await expect(link).toHaveAttribute('aria-label', name);
      await expect(link).toHaveAttribute('title', name);
      const bounds = await link.boundingBox();
      const icon = await link.locator('svg').boundingBox();
      expect(Math.abs(bounds.y + bounds.height / 2 - toggle.y - toggle.height / 2)).toBeLessThan(1);
      expect(icon.width).toBe(16);
      expect(icon.height).toBe(16);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  };
  const checkAuthTheme = async () => {
    const toggle = await page.locator('#theme-toggle').boundingBox();
    const icon = await page.locator('#theme-toggle svg:visible').boundingBox();
    const right = await page.locator('main.identity').evaluate(element =>
      element.getBoundingClientRect().right - parseFloat(getComputedStyle(element).paddingRight));
    expect(toggle.width).toBe(28);
    expect(toggle.height).toBe(28);
    expect(icon.width).toBe(16);
    expect(icon.height).toBe(16);
    expect(Math.abs(toggle.x + toggle.width - right)).toBeLessThan(2);
  };

  await page.setViewportSize({ width: 1280, height: 800 });
  await page.evaluate(() => { localStorage.setItem('tover-v2-theme', 'dark'); });
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  const dark = await appearance();
  await checkInternalLayout();
  await expect(page.locator('#stock-revogrid').getByText('Goods', { exact: true })).toBeVisible();
  await page.screenshot({ path: `${directory}/desktop-stock-dark.png`, fullPage: true });
  await page.locator('.app-nav').screenshot({ path: `${directory}/navigation.png` });
  await page.getByRole('link', { name: 'Team', exact: true }).click();
  await expect(page).toHaveURL(/\/team$/);
  expect(await appearance()).toEqual(dark);
  await checkInternalLayout();
  await expect(page.locator('#theme-toggle')).toHaveAttribute('aria-label', 'Switch to light mode');
  await page.screenshot({ path: `${directory}/desktop-team-dark.png`, fullPage: true });
  await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  expect(await appearance()).toEqual(dark);
  await checkInternalLayout();
  await page.goto('/auth/recover');
  await expect(page.getByRole('heading', { name: 'Recover your account', exact: true })).toBeVisible();
  expect(await appearance()).toEqual(dark);
  await checkAuthTheme();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => { localStorage.setItem('tover-v2-theme', 'light'); });
  await page.goto('/workspace');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  const light = await appearance();
  await checkInternalLayout();
  await page.screenshot({ path: `${directory}/narrow-workspaces-light.png`, fullPage: true });
  for (const name of ['Stock', 'Team']) {
    await page.getByRole('link', { name, exact: true }).click();
    await expect(page.locator('#bel-page')).toHaveAttribute('data-bel-url', name === 'Stock' ? '/' : '/team');
    expect(await appearance()).toEqual(light);
    await checkInternalLayout();
  }
  await page.goto('/auth/recover');
  await expect(page.getByRole('heading', { name: 'Recover your account', exact: true })).toBeVisible();
  expect(await appearance()).toEqual(light);
  await checkAuthTheme();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: `${directory}/narrow-auth-light.png`, fullPage: true });
});

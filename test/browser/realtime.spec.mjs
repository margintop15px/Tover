import { expect, test } from '@playwright/test';
import { signIn } from './auth.fixture.mjs';
import { acceptDrafts, addStockRow, revertPending, stockRow } from './grid.fixture.mjs';

test.beforeEach(async ({ page }) => {
  await page.clock.install();
  await page.addInitScript(() => {
    window.realtimeEvents = [];
    document.addEventListener('datastar-fetch', event => {
      if (event.detail.el === document.body) window.realtimeEvents.push(event.detail);
    });
  });
});

for (const failure of ['network', 'http']) {
  test(`realtime recovers missed and future updates after more than ten failures (${failure})`, async ({ page, browser }) => {
    test.setTimeout(90_000);
    let unavailable = true, requests = 0;
    let releaseFailures;
    const failuresReady = new Promise(resolve => { releaseFailures = resolve; });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await signIn(page);
    await revertPending(page);
    await page.route('**/_bel/realtime/stream*', async route => {
      requests++;
      if (unavailable) await failuresReady;
      return unavailable ? (failure === 'network' ? route.abort('connectionrefused')
        : route.fulfill({ status: 503, body: 'Temporarily unavailable' })) : route.continue();
    });
    await page.reload();
    releaseFailures();
    for (let attempt = 0; attempt < 12; attempt++) {
      await expect.poll(() => page.evaluate(() => realtimeEvents.filter(event => event.type === 'retrying').length))
        .toBe(attempt + 1);
      await page.clock.fastForward(Math.min(1000 * 2 ** attempt, 30_000) + 1);
      await expect.poll(() => requests).toBe(attempt + 2);
    }
    const memberContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
    try {
      const member = await memberContext.newPage();
      await signIn(member, 'member@example.test');
      const missed = `Missed during outage ${failure} ${Date.now()}`;
      await acceptDrafts(member, [await addStockRow(member, missed)]);
      await expect(stockRow(page, missed)).toHaveCount(0);
      unavailable = false;
      await page.clock.fastForward(30_001);
      await expect(stockRow(page, missed)).toBeVisible();
      const connectedRequests = requests;
      const next = `After recovery ${failure} ${Date.now()}`;
      await acceptDrafts(member, [await addStockRow(member, next)]);
      await expect(stockRow(page, next)).toBeVisible();
      expect(requests).toBe(connectedRequests);
      expect(errors).toEqual([]);
    } finally { await memberContext.close(); }
  });
}

for (const wake of ['online', 'focus', 'visibilitychange', 'pageshow', 'watchdog', 'missing heartbeats']) {
  test(`realtime replaces a silent connection after ${wake}`, async ({ page }) => {
    await page.addInitScript(() => {
      const fetch = window.fetch;
      window.holdRealtime = true;
      window.realtimeRequests = 0;
      window.heldStreams = 0;
      window.fetch = (input, options) => {
        if (new URL(input, location.href).pathname === '/_bel/realtime/stream') {
          window.realtimeRequests++;
          if (window.holdRealtime) {
            window.heldStreams++;
            return Promise.resolve(new Response(new ReadableStream({ start(controller) {
              window.sendHeartbeat = () => controller.enqueue(new TextEncoder().encode('event: datastar-patch-signals\ndata: signals {}\n\n'));
              window.sendHeartbeat();
              options.signal.addEventListener('abort', () => {
                window.heldStreams--;
                controller.error(new DOMException('Aborted', 'AbortError'));
              }, { once: true });
            } }), { headers: { 'Content-Type': 'text/event-stream' } }));
          }
        }
        return fetch(input, options);
      };
    });
    await signIn(page);
    await expect.poll(() => page.evaluate(() => heldStreams)).toBe(1);
    await page.evaluate(() => { window.holdRealtime = false; });
    if (wake === 'missing heartbeats') {
      for (let beat = 0; beat < 6; beat++) {
        await page.clock.runFor(10_000);
        await page.evaluate(() => sendHeartbeat());
      }
      expect(await page.evaluate(() => realtimeRequests)).toBe(1);
      await page.clock.runFor(60_001);
    } else {
      // Advancing wall time without running timers models a suspended laptop.
      await page.clock.setSystemTime(new Date(Date.now() + 8 * 60 * 60 * 1000));
      if (wake === 'watchdog') await page.clock.runFor(15_001);
      else await page.evaluate(type => {
        if (type === 'visibilitychange') document.dispatchEvent(new Event(type));
        else dispatchEvent(type === 'pageshow' ? new PageTransitionEvent(type, { persisted: true }) : new Event(type));
      }, wake);
    }
    await expect.poll(() => page.evaluate(() => realtimeRequests)).toBe(2);
    await expect.poll(() => page.evaluate(() => heldStreams)).toBe(0);
    await expect.poll(() => page.evaluate(() => realtimeEvents.some(event =>
      event.type === 'datastar-patch-signals' && event.argsRaw.signals.includes('inventoryChanged')))).toBe(true);
  });
}

test('a closed stream reconnects even when the response ends without a network error', async ({ page }) => {
  let requests = 0;
  await signIn(page);
  await page.route('**/_bel/realtime/stream*', route => ++requests === 1
    ? route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }) : route.continue());
  await page.reload();
  await page.clock.fastForward(1001);
  await expect.poll(() => requests).toBe(2);
  await expect.poll(() => page.evaluate(() => realtimeEvents.some(event =>
    event.type === 'datastar-patch-signals' && event.argsRaw.signals.includes('inventoryChanged')))).toBe(true);
});

for (const status of [204, 400, 403]) {
  test(`a terminal stream response stays stopped after online and wake events (${status})`, async ({ page }) => {
    let requests = 0;
    await signIn(page);
    await page.route('**/_bel/realtime/stream*', route => {
      requests++;
      return route.fulfill({ status, body: '' });
    });
    await page.reload();
    await expect.poll(() => page.evaluate(() => realtimeEvents.some(event => event.type === 'finished'))).toBe(true);
    await page.evaluate(() => {
      dispatchEvent(new Event('online'));
      dispatchEvent(new Event('focus'));
      dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
    });
    await page.clock.fastForward(120_000);
    expect(requests).toBe(1);
  });
}

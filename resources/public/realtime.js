import { action, actions } from './datastar.js';

const heartbeatInterval = 15_000;

action({ name: 'realtime', apply(context, payload) {
  const { el, cleanups } = context;
  const key = '@realtime';
  cleanups.get(key)?.();
  let controller, watchdog, stopped = false, lastActivity = Date.now();

  function stop() {
    if (stopped) return;
    stopped = true;
    controller?.abort();
    clearInterval(watchdog);
    document.removeEventListener('datastar-fetch', activity);
    document.removeEventListener('visibilitychange', resume);
    window.removeEventListener('focus', resume);
    window.removeEventListener('online', connect);
    window.removeEventListener('pageshow', restored);
    cleanups.delete(key);
  }

  function connect() {
    if (stopped) return;
    controller?.abort();
    const attempt = controller = new AbortController();
    lastActivity = Date.now();
    actions.get(context, '/_bel/realtime/stream', {
      payload, openWhenHidden: true, requestCancellation: attempt,
      retry: 'always', retryMaxCount: Infinity, retryMaxWait: 30_000,
    }).catch(console.error).finally(() => {
      // A replaced request must not stop its successor. A terminal response stops the lifecycle.
      if (controller === attempt) stop();
    });
  }

  function activity(event) {
    const { el: source, type, argsRaw } = event.detail;
    if (source !== el) return;
    const status = Number(argsRaw?.status);
    if (type === 'error' && status >= 400 && status < 500 && ![408, 429].includes(status)) {
      const attempt = controller;
      // Datastar schedules its retry after this event; abort once that timer exists.
      setTimeout(() => { if (controller === attempt) stop(); }, 0);
    }
    if (['started', 'retrying', 'datastar-patch-signals'].includes(type)) lastActivity = Date.now();
  }

  function resume() {
    if (!document.hidden && Date.now() - lastActivity >= heartbeatInterval) connect();
  }

  function restored(event) {
    if (event.persisted) connect();
  }

  document.addEventListener('datastar-fetch', activity);
  document.addEventListener('visibilitychange', resume);
  window.addEventListener('focus', resume);
  window.addEventListener('online', connect);
  window.addEventListener('pageshow', restored);
  // A suspended laptop can leave fetch pending without an error or an online event.
  watchdog = setInterval(() => {
    if (Date.now() - lastActivity >= 3 * heartbeatInterval) connect();
  }, heartbeatInterval);
  cleanups.set(key, stop);
  connect();
} });

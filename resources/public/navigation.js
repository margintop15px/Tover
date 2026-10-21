import { action, actions } from './datastar.js';

const page = () => document.querySelector('#bel-page');
const path = url => `${url.pathname}${url.search}`;
let pending = null;
let current = path(new URL(location.href));
let scrollFrame = null;
let navigationGeneration = 0;
const requestGenerations = new WeakMap();

history.scrollRestoration = 'manual';
saveScroll();

function saveScroll() {
  if (pending?.pop) return;
  history.replaceState({ ...history.state, bel: true, scroll: [scrollX, scrollY] }, '');
}

function focusHeading(element) {
  const heading = element.querySelector('h1, [data-page-heading]') || element.querySelector('#page-content');
  if (heading) {
    heading.tabIndex = -1;
    heading.focus({ preventScroll: true });
  }
}

addEventListener('scroll', () => {
  if (scrollFrame !== null) return;
  scrollFrame = requestAnimationFrame(() => { scrollFrame = null; saveScroll(); });
}, { passive: true });

function notice(message, retry) {
  let element = document.querySelector('#bel-navigation-notice');
  if (!element) {
    element = document.createElement('div');
    element.id = 'bel-navigation-notice';
    element.className = 'navigation-notice';
    element.setAttribute('role', 'alert');
    document.body.append(element);
  }
  element.replaceChildren(document.createTextNode(message));
  if (retry) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = 'Retry';
    button.addEventListener('click', retry, { once: true });
    element.append(' ', button);
  }
}

function failed(request) {
  if (pending !== request || request.controller.signal.aborted) return;
  pending = null;
  if (request.pop) location.reload();
  else notice('The page could not be loaded.', () => navigate(request.context, request.url));
}

async function navigate(context, target, { pop = false, scroll = [0, 0] } = {}) {
  const url = new URL(target, location.href);
  const event = context.evt;
  const link = context.el?.closest?.('a');
  if (event && (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey ||
      link?.hasAttribute('download') || (link?.target && link.target !== '_self'))) return;
  if (url.origin !== location.origin || (url.hash && path(url) === current)) return;
  event?.preventDefault();
  navigationGeneration += 1;
  if (!pop && path(url) === current) {
    pending?.controller.abort();
    pending = null;
    return;
  }
  if (!pop) saveScroll();
  pending?.controller.abort();
  const request = { context, url: path(url), hash: url.hash, pop, scroll, controller: new AbortController() };
  pending = request;
  document.querySelector('#bel-navigation-notice')?.remove();
  try {
    await actions.get(context, request.url, {
      payload: {}, requestCancellation: request.controller, retry: 'never', retryMaxCount: 0, openWhenHidden: true,
    });
  } catch {}
  failed(request);
}

action({ name: 'navigate', apply: navigate });

// An action response must not replace the page after the user has started another navigation.
document.addEventListener('datastar-fetch', event => {
  const { type, el, argsRaw } = event.detail;
  if (type === 'started') {
    requestGenerations.set(el, navigationGeneration);
    return;
  }
  if (type !== 'datastar-patch-elements' || !argsRaw.elements?.includes('data-bel-navigation')) return;
  const incoming = new DOMParser().parseFromString(argsRaw.elements || '', 'text/html').querySelector('#bel-page');
  if (incoming?.dataset.belNavigation !== 'true') return;
  if (pending || !el?.isConnected || requestGenerations.get(el) !== navigationGeneration) event.stopImmediatePropagation();
  else if (incoming.dataset.belUrl !== current) saveScroll();
}, { capture: true });

// Datastar's built-in patch listener runs first. A completed fetch can also mean cancellation or an HTTP error.
document.addEventListener('datastar-fetch', event => {
  const { type, el, argsRaw } = event.detail;
  const request = pending;
  if (type === 'datastar-patch-elements') {
    if (request && el !== request.context.el) return;
    const element = page();
    const incoming = new DOMParser().parseFromString(argsRaw.elements || '', 'text/html').querySelector('#bel-page');
    if (!incoming || element?.dataset.belUrl !== incoming.dataset.belUrl) return;
    if (request) {
      if (incoming.dataset.belUrl !== request.url) return;
      pending = null;
      current = request.url;
      document.title = element.dataset.belTitle;
      if (!request.pop) history.pushState({ bel: true, scroll: [0, 0] }, '', request.url + request.hash);
      requestAnimationFrame(() => {
        if (!request.pop) focusHeading(element);
        scrollTo(...request.scroll);
        if (!request.pop && request.hash) document.getElementById(decodeURIComponent(request.hash.slice(1)))?.scrollIntoView();
      });
    } else if (incoming.dataset.belNavigation === 'true') {
      if (incoming.dataset.belResetForm === 'true') el?.closest('form')?.reset();
      const destination = incoming.dataset.belUrl;
      const changed = destination !== current;
      current = destination;
      document.title = element.dataset.belTitle;
      document.querySelector('#bel-navigation-notice')?.remove();
      if (changed) {
        history.pushState({ bel: true, scroll: [0, 0] }, '', destination);
        requestAnimationFrame(() => {
          focusHeading(element);
          scrollTo(0, 0);
        });
      }
    }
  } else if (['error', 'retries-failed'].includes(type) && !request && el?.closest?.('form')) {
    notice(argsRaw.status === '403' ? 'The request was rejected. Refresh the page before trying again.'
      : 'The request could not be confirmed. Your input has been kept; check the result before trying again.');
  }
});

addEventListener('popstate', event => {
  const target = path(new URL(location.href));
  if (target === current) return;
  const element = page();
  if (!element || !event.state?.bel) { location.reload(); return; }
  navigate({ el: element, cleanups: new Map(), error: message => new Error(message) }, target,
    { pop: true, scroll: event.state.scroll || [0, 0] });
});

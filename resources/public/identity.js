// Keep credentials in the server session; attach only page-bound CSRF/context values.
const csrf = document.querySelector('meta[name="csrf-token"]')?.content;
const context = document.querySelector('meta[name="tover-context"]')?.content;
const channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel('tover-identity') : null;
channel?.addEventListener('message', () => location.reload());
if (context && localStorage.getItem('tover-context') !== context) {
  localStorage.setItem('tover-context', context);
  channel?.postMessage('changed');
}

if (csrf) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : input, location.href);
    const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
    if (url.origin !== location.origin || !headers.has('Datastar-Request')) return originalFetch(input, init);
    headers.set('X-CSRF-Token', csrf);
    if (context) headers.set('X-Tover-Context', context);
    const response = await originalFetch(input, { ...init, headers });
    const redirect = response.headers.get('x-tover-redirect');
    if (redirect) location.assign(redirect);
    else if (response.status === 401) location.assign('/auth/login');
    else if (response.status === 409 && response.headers.get('x-tover-error') === 'workspace-changed') location.reload();
    return response;
  };
}

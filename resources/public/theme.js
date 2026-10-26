(() => {
  const key = 'tover-v2-theme';
  const system = window.matchMedia('(prefers-color-scheme: dark)');
  let preference = null;

  try {
    const saved = localStorage.getItem(key);
    if (saved === 'light' || saved === 'dark') preference = saved;
  } catch {}

  function updateButton() {
    const button = document.querySelector('#theme-toggle');
    if (!button) return;
    const target = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    const key = target === 'dark' ? 'Switch to dark mode' : 'Switch to light mode';
    const label = window.toverTranslate?.(key) || key;
    button.setAttribute('aria-label', label);
    button.title = label;
  }

  function apply(mode) {
    document.documentElement.style.colorScheme = mode;
    document.documentElement.dataset.theme = mode;
    updateButton();
  }

  apply(preference || (system.matches ? 'dark' : 'light'));

  function initialize() {
    document.querySelector('#theme-toggle')?.addEventListener('click', () => {
      preference = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem(key, preference); } catch {}
      apply(preference);
    });
    updateButton();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
  else initialize();

  document.addEventListener('tover:language-ready', updateButton);

  system.addEventListener('change', event => {
    if (!preference) apply(event.matches ? 'dark' : 'light');
  });
})();

const locale = typeof document === 'undefined' ? 'en' : document.documentElement.lang || 'en';
const messages = typeof document === 'undefined' ? {} : JSON.parse(document.querySelector('meta[name="tover-messages"]')?.content || '{}');
export { locale };
export function t(key, parameters = {}) {
  let message = messages[key] ?? key;
  if (typeof message === 'object') message = message[new Intl.PluralRules(locale).select(parameters.count)] ?? message.other;
  return message.replace(/\{([\w-]+)\}/g, (placeholder, name) => String(parameters[name] ?? placeholder));
}
if (typeof window !== 'undefined') {
  window.toverTranslate = t;
  document.dispatchEvent(new Event('tover:language-ready'));
}

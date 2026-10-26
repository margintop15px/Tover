import { locale } from './i18n.js';
const formatLocale = typeof navigator === 'undefined' ? locale : navigator.languages?.find(language => language.split('-')[0] === locale) || locale;
const dateTimeFormat = new Intl.DateTimeFormat(formatLocale, { dateStyle: 'medium', timeStyle: 'short' });
const dateFormat = new Intl.DateTimeFormat(formatLocale, { dateStyle: 'medium' });
const pad = value => String(value).padStart(2, '0');
const dateValue = /^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})?)?$/;

export function formatDate(value) {
  if (!value) return '';
  const raw = String(value);
  if (!dateValue.test(raw)) return raw;
  const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(raw);
  const date = dateOnly ? new Date(`${raw}T12:00:00`) : new Date(raw);
  return Number.isNaN(date.valueOf()) ? raw : (dateOnly ? dateFormat : dateTimeFormat).format(date);
}

export function toLocalDateTime(value) {
  if (!value) return '';
  if (!(value instanceof Date) && !dateValue.test(String(value))) return String(value);
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return String(value);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function toISOString(value) {
  if (!value) return '';
  const raw = String(value);
  if (!dateValue.test(raw)) return raw;
  const date = new Date(raw);
  if (Number.isNaN(date.valueOf())) return raw;
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(raw) && toLocalDateTime(date) !== raw) return raw;
  return date.toISOString();
}

export function inputDateValue(input) {
  const original = input.dataset.dateValue;
  return original && input.value === toLocalDateTime(original) ? original : toISOString(input.value);
}

function presentDates(root) {
  const selector = '[data-human-date],[data-human-date-title],input[data-date-value]';
  const nodes = root.matches?.(selector) ? [root, ...root.querySelectorAll(selector)] : root.querySelectorAll?.(selector) || [];
  for (const node of nodes) {
    if (node.matches('input[data-date-value]')) {
      if (!node.hasAttribute('data-unsaved-edit')) {
        const value = toLocalDateTime(node.dataset.dateValue);
        if (node.value !== value) node.value = value;
      }
      continue;
    }
    const raw = node.getAttribute('datetime') || node.dataset.dateValue || node.dataset.humanDateTitle;
    const value = `${node.dataset.datePrefix || ''}${formatDate(raw)}${node.dataset.dateSuffix || ''}`;
    if (node.hasAttribute('data-human-date-title')) {
      if (node.title !== value) node.title = value;
    } else if (node.textContent !== value) node.textContent = value;
  }
}

if (typeof document !== 'undefined') {
  presentDates(document);
  new MutationObserver(changes => {
    for (const change of changes) {
      if (change.type === 'attributes') presentDates(change.target);
      else for (const node of change.addedNodes) if (node.nodeType === Node.ELEMENT_NODE) presentDates(node);
    }
  }).observe(document.documentElement, {
    subtree: true, childList: true, attributes: true,
    attributeFilter: ['datetime', 'data-date-value', 'data-unsaved-edit', 'data-human-date-title', 'data-date-prefix', 'data-date-suffix'],
  });
}

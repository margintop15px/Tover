import { t } from './i18n.js';
import { toISOString } from './dates.js';

const partial = { quantity: /^\d*$/, decimal: /^\d*(?:[.,]\d*)?$/, currency: /^[A-Za-z]{0,3}$/ };

export function normalizeFieldValue(editor, value) {
  const raw = String(value ?? '');
  if (editor?.type === 'quantity') return raw.replace(/^0+(?=\d)/, '');
  if (editor?.type === 'decimal') return raw.replace(',', '.');
  if (editor?.type === 'currency') return raw.toUpperCase();
  if (editor?.type === 'date') return /^\d{4}-\d{2}-\d{2}$/.test(raw) || /(?:Z|[+-]\d{2}:\d{2})$/.test(raw) ? raw : toISOString(raw);
  if (['select', 'operation-reference'].includes(editor?.type)) {
    return editor.options?.find(option => option.value === raw || option.label === raw)?.value ?? raw;
  }
  return raw;
}

export function fieldError(editor, value) {
  const raw = String(value ?? ''), type = editor?.type;
  if (raw === '' && (type !== 'select' || editor.allowEmpty)) return '';
  const normalized = type === 'date' ? raw : normalizeFieldValue(editor, raw);
  if (type === 'quantity' && (!/^\d+$/.test(normalized) || BigInt(normalized) > 9223372036854775807n)) {
    return t('Enter a whole-unit quantity in the supported range.');
  }
  if (type === 'decimal') {
    const digits = normalized.replace('.', '').replace(/^0+/, '');
    if (!/^\d+(?:\.\d+)?$/.test(normalized) || normalized.length > 100 || digits.length > 38 || (normalized.split('.')[1]?.length || 0) > 12) {
      return t('Enter an exact nonnegative decimal amount.');
    }
  }
  if (type === 'currency' && !/^[A-Za-z]{3}$/.test(raw)) return t('Use a three-letter uppercase currency code.');
  if (type === 'date') {
    const date = new Date(`${raw.slice(0, 10)}T00:00:00Z`);
    if (!/^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})?)?$/.test(raw)
      || Number.isNaN(date.valueOf()) || date.toISOString().slice(0, 10) !== raw.slice(0, 10)
      || Number.isNaN(new Date(raw).valueOf()) || !toISOString(raw).endsWith('Z')) {
      return t('Enter an occurrence date or an ISO timestamp.');
    }
  }
  if (['select', 'operation-reference'].includes(type) && !editor.options?.some(option => option.value === normalized)) {
    return type === 'operation-reference' ? t('The referenced purchase or sale is unavailable.')
      : editor.options?.some(option => option.value === 'incoming') ? t('Choose incoming or outgoing.') : t('Choose an available operation type.');
  }
  return '';
}

// Check the prospective text, rather than removing characters that could change a pasted value's meaning.
export function constrainInput(input, getEditor, feedback) {
  let previous = input.value, selection = [0, 0];
  const report = message => {
    input.setCustomValidity(message);
    if (message) input.setAttribute('aria-invalid', 'true'); else input.removeAttribute('aria-invalid');
    feedback(message);
  };
  const message = type => fieldError({ type }, type === 'currency' ? '123' : 'invalid');
  const candidate = text => input.value.slice(0, input.selectionStart) + text + input.value.slice(input.selectionEnd);
  input.addEventListener('beforeinput', event => {
    previous = input.value; selection = [input.selectionStart, input.selectionEnd];
    const type = getEditor()?.type, pattern = partial[type];
    if (pattern && !event.isComposing && event.data != null && !event.inputType.startsWith('delete') && !pattern.test(candidate(event.data))) {
      event.preventDefault(); report(message(type));
    }
  });
  input.addEventListener('paste', event => {
    const type = getEditor()?.type, pattern = partial[type];
    if (pattern && !pattern.test(candidate(event.clipboardData?.getData('text/plain') || ''))) {
      event.preventDefault(); event.stopImmediatePropagation(); report(message(type));
    }
  });
  const changed = event => {
    const type = getEditor()?.type, pattern = partial[type];
    if (event.isComposing) return;
    if (pattern && !pattern.test(input.value) && !event.inputType?.startsWith('delete')) {
      input.value = previous;
      input.setSelectionRange(...selection);
      event.stopImmediatePropagation(); report(message(type)); return;
    }
    if (type === 'currency') {
      const start = input.selectionStart, end = input.selectionEnd;
      input.value = input.value.toUpperCase(); input.setSelectionRange(start, end);
    }
    previous = input.value; report('');
  };
  input.addEventListener('input', changed);
  input.addEventListener('compositionend', changed);
  return () => {
    previous = input.value;
    input.inputMode = { quantity: 'numeric', decimal: 'decimal' }[getEditor()?.type] || '';
    report('');
  };
}

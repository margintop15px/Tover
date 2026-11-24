import { t } from './i18n.js';
import { formatDate, inputDateValue, toLocalDateTime } from './dates.js';
import { constrainInput, fieldError, normalizeFieldValue } from './field-input.js';

const node = (tag, props = {}, ...children) => {
  const made = Object.assign(document.createElement(tag), props);
  made.append(...children.filter(Boolean));
  return made;
};

// One input for every table: it searches and filters, or builds a record from chips. Modes: 'filter' (search), 'types'
// (choose what to add), 'add' (fill fields). `field` is the key being typed, `lines/0/item` for a line. The page gives
// the context (what can be searched and added) and saves the result.
const defaults = {
  placeholder: '', addLabel: '', typeHint: '', searchKey: null, searchHint: '', live: false, lookup: null,
  kinds: () => [], columns: () => [], columnValues: () => [], begin: () => null, fields: () => [], ready: () => true,
  lineKinds: () => [], allowsNew: () => false,
};
class SmartEntry extends HTMLElement {
  constructor() {
    super();
    this.mode = 'filter'; this.field = null; this.draft = null; this.active = 0; this.open = false;
    this._context = defaults; this._items = []; this._found = null;
    this._busy = false; this._error = '';
  }
  set context(value) { this._context = { ...defaults, ...value }; this.render(); }
  // Answer to a lookup of `text`: options the page picks, or a retry.
  set found(value) { this._found = value; this.render(); }
  set items(value) { this._items = value; this.render(); }
  set busy(value) { this._busy = value; if (this.input) this.input.readOnly = value; this.render(); }
  set error(value) { this._error = value; this.render(); }

  connectedCallback() {
    if (this.input) return;
    this.classList.add('stock-entry');
    this.chips = node('div', { className: 'stock-entry-chips' });
    this.label = node('span', { className: 'stock-entry-field' });
    this.input = node('input', { className: `stock-entry-input ${this.dataset.inputClass ?? ''}`, type: 'text', autocomplete: 'off', spellcheck: false, title: t('Focus search or add (⌘/Ctrl+K)') });
    this.clear = node('button', { className: 'stock-entry-clear', type: 'button', tabIndex: -1, textContent: '×', ariaLabel: t('Clear unfinished field') });
    this.cancelButton = node('button', { className: 'stock-entry-cancel', type: 'button', textContent: t('Cancel'), ariaLabel: t('Cancel addition') });
    const line = node('div', { className: 'stock-entry-line' }, this.chips, this.label, this.input, this.clear, this.cancelButton);
    this.feedback = node('div', { className: 'stock-entry-feedback', role: 'alert' });
    this.menu = node('div', { className: 'stock-entry-menu', id: `${this.id}-options` });
    this.menu.setAttribute('role', 'listbox');
    for (const [name, value] of Object.entries({ role: 'combobox', 'aria-autocomplete': 'list', 'aria-controls': this.menu.id, 'aria-keyshortcuts': 'Meta+K Control+K' })) this.input.setAttribute(name, value);
    this.replaceChildren(line, this.feedback, this.menu);
    this.sync = constrainInput(this.input, () => this.editor(), message => { this._error = message; this.render(); });
    this.input.addEventListener('input', () => { this.open = true; this.active = 0; this.navigated = false; this.ask(); this.render(); });
    this.input.addEventListener('focus', () => { if (!this.open) { this.open = true; this.render(); } });
    this.input.addEventListener('keydown', event => this.key(event));
    this.clear.addEventListener('click', () => { this.input.focus(); this.setField(null, this.mode !== 'filter'); });
    this.cancelButton.addEventListener('click', () => this.cancel());
    for (const button of [this.clear, this.cancelButton]) button.addEventListener('pointerdown', event => event.preventDefault());
    line.addEventListener('click', event => { if (!event.target.closest('button')) this.input.focus(); });
    // Leaving a filled field keeps its value.
    this.addEventListener('focusout', event => {
      if (this.rendering || this.contains(event.relatedTarget)) return;
      if (this.mode === 'add' && this.field && this.value().trim()) this.commit(this.value());
      this.open = false; this.render();
    });
    this.render();
  }

  // Fields
  kind() { return this._context.kinds().find(kind => kind.kind === this.draft?.kind); }
  fields() { return this.mode === 'add' ? this._context.fields(this.draft) : this._context.columns(); }
  current() { return this.fields().find(field => field.key === this.field); }
  editor() { return this.mode === 'add' ? this.current()?.editor : null; }
  get(key, draft = this.draft) {
    const [, index, name] = key.match(/^lines\/(\d+)\/(.+)$/) ?? [];
    return (name ? draft.lines[index]?.[name] : draft.values[key]) ?? '';
  }
  put(key, value, label) {
    const [, index, name] = key.match(/^lines\/(\d+)\/(.+)$/) ?? [];
    if (!name) { this.draft.values[key] = value; this.draft.labels = { ...this.draft.labels, [key]: label }; }
    else Object.assign(this.draft.lines[index], { [name]: value }, name === 'item' ? { itemLabel: label ?? value } : {});
    this.keep();
  }
  // What a chip shows for a saved value.
  shown(field) {
    const value = this.get(field.key);
    if (!value) return '';
    if (field.editor.type === 'date') return formatDate(value);
    if (field.editor.type === 'goods') return (field.key.startsWith('lines/') ? this.draft.lines[field.key.split('/')[1]].itemLabel : this.draft.labels?.[field.key]) || value;
    return field.editor.options?.find(option => option.value === value)?.label ?? value;
  }
  allowsNew(key) { return this._context.allowsNew(key, this.draft); }
  complete(draft = this.draft) {
    if (!draft) return false;
    return this.fields().every(field => !field.required || this.get(field.key, draft)) && this._context.ready(draft);
  }
  value() { return this.editor()?.type === 'date' ? inputDateValue(this.input) : this.input.value; }
  // The addition as it would be with the value being typed, so Done is offered before that value is kept.
  candidate() {
    const field = this.current(), text = this.value().trim();
    if (this.mode !== 'add' || !field || !text || fieldError(field.editor, text)) return this.draft;
    if (field.editor.type === 'goods' && !this.allowsNew(field.key) && !this._items.some(item => item.label === text)) return this.draft;
    const draft = structuredClone(this.draft), kept = this.draft, keep = this.keep;
    this.draft = draft; this.keep = () => {};
    this.put(field.key, normalizeFieldValue(field.editor, text));
    this.draft = kept; this.keep = keep;
    return draft;
  }

  // Menu
  options() {
    const term = this.input.value.trim().toLowerCase();
    const matches = text => !term || text.toLowerCase().includes(term);
    const { addLabel, typeHint, searchKey, searchHint } = this._context;
    const kinds = () => this._context.kinds().map(kind => ({ kind: 'type', type: kind.kind, label: kind.label, description: addLabel }));
    const verbs = ['add', addLabel.toLowerCase().split(' ')[0]];
    if (this.mode === 'types') return kinds().filter(option => matches(option.label) || verbs.includes(term));
    if (this.mode === 'filter' && !this.field) {
      const [first = '', ...more] = term.split(/\s+/), rest = more.join(' ');
      const adding = first.length >= 2 && verbs.some(verb => verb.startsWith(first));
      const add = kinds().length ? [{ kind: 'add', label: addLabel, description: typeHint }] : [];
      if (!term) return add;
      if (adding && (rest || verbs.includes(first))) {
        const fits = kinds().filter(option => !rest || option.label.toLowerCase().startsWith(rest) || option.type.startsWith(rest));
        if (fits.length) return fits;
      }
      const found = this._found?.text === this.input.value.trim() ? this._found.options ?? [] : [];
      return [...(adding ? add : []),
        ...found.map(option => ({ kind: 'pick', pick: option, label: option.label, description: option.hint, className: `stock-option-${option.kind}` })),
        ...this._context.columns().filter(column => matches(column.label)).map(column => ({ kind: 'field', key: column.key, label: column.label, description: t('Choose field') })),
        ...(searchKey ? [{ kind: 'value', key: searchKey, value: this.input.value.trim(), label: this.input.value.trim(), description: searchHint }] : [])];
    }
    if (this.mode === 'filter') {
      const typed = this.input.value.trim();
      const values = this._context.columnValues(this.field).filter(value => value.toLowerCase().startsWith(term)).slice(0, 20);
      return [...values.map(value => ({ kind: 'value', value, label: value, description: t('Begins with') })),
        ...(typed && !values.includes(typed) ? [{ kind: 'value', value: typed, label: typed, description: t('Begins with') }] : [])];
    }
    const field = this.current(), done = this.complete(this.candidate()) ? [{ kind: 'done', label: t('Done'), description: t('Add complete operation to pending changes · ⌘/Ctrl Enter') }] : [];
    if (field) {
      const typed = this.input.value.trim();
      const own = { kind: 'value', value: this.value(), label: field.editor.type === 'date' ? formatDate(this.value()) : typed, description: t('Use this value') };
      const choices = (field.editor.type === 'goods' ? this._items : field.suggest ?? field.editor.options ?? []).filter(option => field.editor.type === 'goods' || matches(option.label));
      const free = field.editor.type === 'goods' ? this.allowsNew(field.key) : field.editor.type !== 'select';
      return [...done, ...choices.slice(0, 30).map(option => ({ kind: 'value', value: option.value, label: option.label, description: t('Existing value') })),
        ...(free && typed && !choices.some(option => option.label === typed) ? [own] : [])];
    }
    const sides = this._context.lineKinds(this.draft);
    const count = side => this.draft.lines.filter(line => (line.side ?? null) === side).length;
    return [...done,
      ...this.fields().filter(item => !this.get(item.key) && matches(item.label)).map(item => ({ kind: 'field', key: item.key, label: item.label, description: item.required ? t('Required') : t('Optional') })),
      ...sides.filter(({ label }) => matches(label)).map(({ side, label }) => ({ kind: 'line', side, label, description: t('Another item line') })),
      ...this.draft.lines.map((line, index) => ({ line, index })).filter(({ line }) => count(line.side ?? null) > 1)
        .map(({ line, index }) => ({ kind: 'remove-line', index, label: `${t('Remove line')}: ${line.itemLabel || index + 1}`, description: '' })).filter(option => term && matches(option.label))];
  }
  choose(option) {
    if (this._busy || !option) return;
    if (option.kind === 'add') {
      const kinds = this._context.kinds();
      if (kinds.length === 1) this.begin(kinds[0].kind);
      else { this.mode = 'types'; this.setField(null); }
    } else if (option.kind === 'pick') {
      this.open = false; this.setField(null, false);
      this.dispatchEvent(new CustomEvent('smart-pick', { detail: option.pick })); return;
    }
    else if (option.kind === 'type') this.begin(option.type);
    else if (option.kind === 'field') this.setField(option.key);
    else if (option.kind === 'line') {
      this.draft.lines.push(option.side ? { side: option.side } : {}); this.keep();
      this.setField(`lines/${this.draft.lines.length - 1}/item`);
    } else if (option.kind === 'remove-line') { this.draft.lines.splice(option.index, 1); this.keep(); this.setField(null); }
    else if (option.kind === 'done') { this.done(); return; }
    else if (option.kind === 'value') this.commit(option.value, option.label, option.key);
    this.input.focus();
  }
  // Keeps the typed or chosen value. A bad value stays in the input with its message.
  commit(value, label, key = this.field) {
    if (this._busy) return false;
    const text = String(value).trim();
    if (this.mode !== 'add') {
      if (text) this.dispatchEvent(new CustomEvent('smart-filter', { detail: { key: key ?? this._context.searchKey, value: text } }));
      this.open = false; this.setField(null, false); return true;
    }
    const field = this.fields().find(item => item.key === key);
    if (field.editor.type === 'goods') {
      const known = this._items.find(item => item.value === text || item.label === text);
      if (!known && !label && text && !this.allowsNew(key)) { this._error = t('Choose an existing record.'); this.render(); return false; }
      this.put(key, known?.value ?? text, known?.label ?? label);
    } else {
      const error = (this.input.validity.badInput && field.editor.type === 'date' && !label ? t('Enter an occurrence date or an ISO timestamp.') : fieldError(field.editor, text))
        || (field.editor.type === 'quantity' && text && Number(text) < 1 ? t('Enter a whole number of 1 or more.') : '');
      if (error) { this._error = error; this.render(); return false; }
      this.put(key, normalizeFieldValue(field.editor, text));
    }
    this.setField(null); return true;
  }
  setField(key, open = true) {
    this.field = key; this.active = 0; this.navigated = false; this._error = ''; this._items = [];
    const field = this.current(), date = this.mode === 'add' && field?.editor.type === 'date';
    const value = this.mode === 'add' && field ? this.get(key) : '';
    this.input.type = date ? 'datetime-local' : 'text';
    if (date) this.input.dataset.dateValue = value; else delete this.input.dataset.dateValue;
    this.input.value = date ? toLocalDateTime(value) : field && this.mode === 'add' ? this.shown(field) : '';
    this.sync();
    this.open = open; this.ask(); this.render();
  }
  ask() {
    clearTimeout(this.timer);
    const text = this.input.value.trim(), field = this.mode === 'add' ? this.current() : null;
    if (this._context.live) {
      const search = this.mode === 'filter' && !this.field ? this.input.value : '';
      if (search !== (this.searched ?? '')) { this.searched = search; this.dispatchEvent(new CustomEvent('smart-search', { detail: { text: search } })); }
    }
    if (field?.editor.type === 'goods') this.timer = setTimeout(() => this.dispatchEvent(new CustomEvent('smart-items', { detail: { text, field } })), 120);
    else if (this._context.lookup && this.mode === 'filter' && !this.field && text) this.timer = setTimeout(() => this._context.lookup(text), 120);
  }

  // The addition
  begin(type) {
    if (!this._context.kinds().some(kind => kind.kind === type)) return;
    const draft = this._context.begin(type);
    if (!draft) { this.setField(null, false); return; }
    this.resume(draft);
    this.keep();
    this.input.focus();
  }
  resume(draft) {
    if (!this._context.kinds().some(kind => kind.kind === draft.kind)) return;
    this.draft = draft; this.mode = 'add'; this.setField(null, this.input.matches(':focus'));
  }
  keep() { this.dispatchEvent(new CustomEvent('smart-draft', { detail: this.draft })); }
  done() {
    if (this._busy || this.mode !== 'add') return;
    if (this.field && this.value().trim() && !this.commit(this.value())) return;
    if (!this.complete()) {
      const missing = this.fields().filter(field => field.required && !this.get(field.key)).map(field => field.label);
      this._error = missing.length ? t('Fill in: {p0}', { p0: missing.join(', ') }) : this._context.notReady;
      this.open = true; this.render(); return;
    }
    this.busy = true;
    const { kind, values, lines } = this.draft;
    this.dispatchEvent(new CustomEvent('smart-done', { detail: { values: { kind, ...values },
      lines: lines.map(({ itemLabel, ...line }) => line) } }));
  }
  finish() { this.draft = null; this.mode = 'filter'; this.keep(); this.setField(null, false); }
  cancel() { this.finish(); this.input.focus(); }
  focusInput() { this.input.focus(); }

  key(event) {
    const typed = this.input.value.trim(), options = this.options();
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey) && this.mode === 'add') { event.preventDefault(); this.done(); }
    else if (['ArrowDown', 'ArrowUp'].includes(event.key) && this.input.type !== 'datetime-local') {
      event.preventDefault(); this.open = true; this.navigated = true;
      if (options.length) this.active = (this.active + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
      this.render();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      if (this.field || typed) this.setField(null, false);
      else if (this.mode !== 'filter') this.cancel();
      else { this.open = false; this.render(); }
    } else if (event.key === 'Enter' || (event.key === 'Tab' && typed && !event.shiftKey && (this.field || options.length) && !(this._context.live && this.mode === 'filter'))) {
      event.preventDefault();
      const option = options[this.active];
      if (this.navigated && option) this.choose(option);
      else if (this.mode === 'add' && this.field && typed) {
        // The typed text wins when it is complete; otherwise the first match does.
        const editor = this.current().editor;
        const first = options.find(item => item.kind === 'value');
        if (['select', 'goods'].includes(editor.type) && first && first.description !== t('Use this value')) this.choose(first);
        else this.commit(this.value());
      } else if (this.mode === 'filter' && this.field && typed) this.commit(typed);
      else this.choose(option);
    } else if (event.key === 'Backspace' && !this.input.value && this.field) { event.preventDefault(); this.setField(null); }
  }

  render() {
    if (!this.input || this.rendering) return;
    this.rendering = true;
    try {
      const adding = this.mode === 'add', field = this.current();
      const chips = !adding ? [] : [{ label: t('Add'), value: this.kind().label }, ...this.fields().filter(item => this.get(item.key) && item.key !== this.field).map(item => ({ key: item.key, label: item.label, value: this.shown(item) }))];
      this.chips.replaceChildren(...chips.map(chip => {
        const edit = node('button', { type: 'button', className: 'stock-entry-chip-edit', disabled: this._busy, ariaLabel: t('Edit {p0}: {p1}', { p0: chip.label, p1: chip.value }) },
          node('span', { className: 'stock-entry-chip-label', textContent: `${chip.label}:` }), node('span', { className: 'stock-entry-chip-value', textContent: chip.value }));
        edit.addEventListener('click', () => { this.setField(chip.key ?? null); this.input.focus(); });
        const remove = chip.key && node('button', { type: 'button', className: 'stock-entry-chip-remove', textContent: '×', disabled: this._busy, ariaLabel: t('Remove {p0}: {p1}', { p0: chip.label, p1: chip.value }) });
        remove && remove.addEventListener('click', () => { this.put(chip.key, ''); this.setField(null); this.input.focus(); });
        const made = node('span', { className: 'stock-entry-chip' }, edit, remove);
        if (chip.key) made.dataset.key = chip.key;
        return made;
      }));
      this.label.hidden = !field; this.label.textContent = field ? `${field.label}:` : '';
      this.clear.hidden = !field && !this.input.value; this.cancelButton.hidden = this.mode === 'filter';
      this.clear.disabled = this.cancelButton.disabled = this._busy;
      this.input.placeholder = field ? t('Choose or enter {p0}', { p0: field.label }) : adding ? t('Choose the next field…')
        : this.mode === 'types' ? t('Choose an operation type…') : this._context.placeholder;
      this.input.setAttribute('aria-label', field?.label ?? this._context.placeholder);
      this.feedback.hidden = !this._error; this.feedback.textContent = this._error;
      const options = this.options();
      this.active = Math.min(this.active, Math.max(0, options.length - 1));
      const failed = this.mode === 'filter' && !this.field && this._found?.failed && this._found.text === this.input.value.trim();
      const visible = this.open && (options.length > 0 || failed) && !this._busy;
      this.menu.hidden = !visible;
      this.input.setAttribute('aria-expanded', String(visible));
      this.input.removeAttribute('aria-activedescendant');
      this.menu.replaceChildren(...options.map((option, index) => {
        const button = node('button', { type: 'button', id: `${this.menu.id}-${index}`, tabIndex: -1, className: `stock-entry-option ${option.className ?? ''}${index === this.active ? ' is-active' : ''}` },
          node('span', { className: `stock-entry-option-icon${option.kind === 'done' ? ' is-done' : ''}`, ariaHidden: 'true', textContent: { done: '✓', add: '+', line: '+', type: '↗', 'remove-line': '×' }[option.kind] ?? '≡' }),
          node('span', { className: 'stock-entry-option-content' }, node('span', { className: 'stock-entry-option-field', textContent: option.label })),
          node('span', { className: 'stock-entry-option-description', textContent: option.description }));
        button.dataset.kind = option.kind;
        button.setAttribute('role', 'option'); button.setAttribute('aria-selected', String(index === this.active));
        button.addEventListener('pointerdown', event => event.preventDefault());
        button.addEventListener('click', () => this.choose(option));
        if (visible && index === this.active) this.input.setAttribute('aria-activedescendant', button.id);
        return button;
      }));
      if (failed) {
        const retry = node('button', { type: 'button', textContent: t('Retry') });
        retry.addEventListener('click', () => { this.input.focus(); this._context.lookup(this.input.value.trim()); });
        this.menu.append(node('div', { className: 'stock-option-failed' }, `${t('Could not load stock.')} `, retry));
      }
      if (visible) this.menu.querySelector('.is-active')?.scrollIntoView({ block: 'nearest' });
    } finally { this.rendering = false; }
  }
}

customElements.define('smart-entry', SmartEntry);

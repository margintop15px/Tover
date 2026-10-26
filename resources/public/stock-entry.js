import { t } from './i18n.js';
import { formatDate, inputDateValue, toLocalDateTime } from './dates.js';

class StockEntry extends HTMLElement {
  constructor() {
    super();
    this._model = null;
    this._request = 0;
    this._hasSent = false;
    this._saveStatus = 'Saved';
    this._doneCommandId = null;
    this.activeIndex = 0;
    this.open = false;
    this.pending = false;
  }

  get saveStatus() { return this._saveStatus; }
  set saveStatus(value) {
    this._saveStatus = value;
    if (this.input) {
      this.input.readOnly = this.busy;
      this.render();
    }
  }

  get busy() { return this.pending || this._saveStatus === 'Saving'; }

  set model(value) {
    if (value === null) {
      if (this._model === null) return;
      clearTimeout(this._timer);
      if (this._hasSent) ++this._request;
      this._model = null;
      this._doneCommandId = null;
      this.pending = false;
      this.open = false;
      this.activeIndex = 0;
      if (this.input) {
        this.input.type = 'text';
        delete this.input.dataset.dateValue;
        this.input.removeAttribute('step');
        this.input.value = '';
        this.input.readOnly = this.busy;
        this.render();
      }
      return;
    }
    if (!value || value.request < this._request) return;
    this._request = Math.max(this._request, value.request);
    if (value.state?.id !== this._model?.state?.id || !['add', 'unavailable'].includes(value.state?.mode)) this._doneCommandId = null;
    this._model = JSON.parse(JSON.stringify(value));
    this.pending = false;
    if (value.closeMenu) this.open = false;
    if (!this.input) return;
    this.input.readOnly = this.busy;
    this.input.type = this.isDateField ? 'datetime-local' : 'text';
    if (this.isDateField) {
      this.input.step = '60';
      this.input.dataset.dateValue = value.text || '';
    } else {
      delete this.input.dataset.dateValue;
      this.input.removeAttribute('step');
    }
    this.input.value = this.modelText(value);
    this.render();
  }
  get model() { return this._model; }

  connectedCallback() {
    if (this.input) return;
    this.classList.add('stock-entry');
    this.innerHTML = `
      <div class="stock-entry-line">
        <div class="stock-entry-chips"></div>
        <span class="stock-entry-field" hidden></span>
        <input class="stock-entry-input" type="text" role="combobox"
          autocomplete="off" spellcheck="false" aria-label="${t('Search or add')}"
          aria-keyshortcuts="Meta+K Control+K" title="${t('Focus search or add (⌘/Ctrl+K)')}"
          aria-autocomplete="list" aria-expanded="false" placeholder="${t('Search or add… choose fields or type a value')}">
        <button class="stock-entry-clear" type="button" aria-label="${t('Clear unfinished field')}" hidden>×</button>
        <button class="stock-entry-cancel" type="button" aria-label="${t('Cancel addition')}" title="${t('Cancel addition')}" hidden>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6"/></svg>
        </button>
      </div>
      <span class="stock-entry-shortcut">${t('Press Command or Control plus K to focus search or add.')}</span>
      <div class="stock-entry-feedback" role="alert" hidden></div>
      <div class="stock-entry-menu" role="listbox" hidden></div>`;
    this.input = this.querySelector('input');
    this.chips = this.querySelector('.stock-entry-chips');
    this.fieldLabel = this.querySelector('.stock-entry-field');
    this.menu = this.querySelector('.stock-entry-menu');
    this.feedback = this.querySelector('.stock-entry-feedback');
    this.clearButton = this.querySelector('.stock-entry-clear');
    this.clearButton.addEventListener('click', () => this.choose({ kind: 'clear-field' }));
    this.cancelButton = this.querySelector('.stock-entry-cancel');
    this.cancelButton.addEventListener('pointerdown', event => event.preventDefault());
    this.cancelButton.addEventListener('click', () => this.choose({ kind: 'cancel' }));
    this.menu.id = `${this.id || 'stock-entry'}-options`;
    this.input.setAttribute('aria-controls', this.menu.id);
    this.querySelector('.stock-entry-shortcut').id = `${this.menu.id}-shortcut`;
    this.input.setAttribute('aria-describedby', `${this.menu.id}-shortcut`);
    this.input.addEventListener('input', () => {
      ++this._request;
      this.open = true;
      this.activeIndex = 0;
      this.clearButton.hidden = !this.input.value && !this._model?.field;
      this._navigated = false;
      this.render();
      clearTimeout(this._timer);
      this._timer = setTimeout(() => this.suggest(), 100);
    });
    this.input.addEventListener('focus', () => {
      this.open = true;
      this.suggest();
    });
    this.input.addEventListener('keydown', event => this.onKeydown(event));
    this.addEventListener('focusout', event => {
      if (this._rendering || this.contains(event.relatedTarget)) return;
      if (this._model?.field && this.input.value.trim() && !this.pending) this.complete();
      this.open = false;
      this.render();
    });
    this.querySelector('.stock-entry-line').addEventListener('click', event => {
      if (!event.target.closest('button')) this.input.focus();
    });
    this._fetchFailure = event => {
      if (event.detail?.type !== 'error' || !this.pending) return;
      this.pending = false;
      this.input.readOnly = this.busy;
      this.open = true;
      this.render();
    };
    document.addEventListener('datastar-fetch', this._fetchFailure);
    for (const property of ['model', 'saveStatus']) {
      if (Object.prototype.hasOwnProperty.call(this, property)) {
        const value = this[property];
        delete this[property];
        this[property] = value;
      }
    }
    this.render();
  }

  disconnectedCallback() {
    clearTimeout(this._timer);
    document.removeEventListener('datastar-fetch', this._fetchFailure);
  }

  get isDateField() {
    return !!this._model?.field && this._model.field.key.split('/').at(-1) === 'occurred-at';
  }

  modelText(model = this._model) {
    const date = model?.field?.key.split('/').at(-1) === 'occurred-at';
    return date ? toLocalDateTime(model?.text || '') : model?.text || '';
  }

  currentText() {
    return this.isDateField ? inputDateValue(this.input) : this.input.value;
  }

  send(name, option) {
    clearTimeout(this._timer);
    this._hasSent = true;
    const request = ++this._request;
    const state = { ...(this._model?.state || {}) };
    // Datastar represents removed nullable signal values as empty strings.
    for (const key of ['id', 'revision', 'field', 'replacing', 'conflict']) {
      if (state[key] === '') state[key] = null;
    }
    this.dispatchEvent(new CustomEvent(name, {
      bubbles: true,
      detail: { state, text: this.currentText(), option, request },
    }));
  }

  suggest() {
    if (this.busy) return;
    this.send('smart-suggest');
  }

  complete(option = null) {
    if (this.busy) return;
    if (option?.kind === 'done') {
      this._doneCommandId ||= crypto.randomUUID();
      option = { ...option, commandId: this._doneCommandId };
    }
    this.pending = true;
    this.input.readOnly = true;
    this.open = true;
    this.activeIndex = 0;
    this.render();
    this.send('smart-complete', option);
  }

  choose(option) {
    this.complete(option);
    this.input.focus();
  }

  beginAdd() {
    if (this.busy) return;
    this.input.focus();
    this.complete({ kind: 'add' });
  }

  editFilter(column) {
    if (this.busy) return;
    this.input.focus();
    this.complete({ kind: 'filter', key: column });
  }

  get availableOptions() {
    return this._model?.unavailable && this._doneCommandId
      ? [{ kind: 'done', label: t('Retry Done'), description: t('Recover the existing completion result') }]
      : this._model?.options || [];
  }

  get adding() {
    return ['types', 'add', 'unavailable'].includes(this._model?.state?.mode)
      || this.availableOptions.some(option => option.kind === 'type');
  }

  render() {
    if (!this.input || this._rendering) return;
    this._rendering = true;
    try {
      const model = this._model || {};
      const options = this.availableOptions;
      this.activeIndex = Math.min(this.activeIndex, Math.max(0, options.length - 1));
      this.chips.replaceChildren();
      for (const chip of model.chips || []) {
        const container = document.createElement('span');
        container.className = 'stock-entry-chip';
        const edit = document.createElement('button');
        edit.type = 'button';
        edit.disabled = this.busy;
        edit.className = 'stock-entry-chip-edit';
        const displayedValue = chip.key.split('/').at(-1) === 'occurred-at' ? formatDate(chip.value) : chip.value;
        edit.setAttribute('aria-label', t("Edit {p0}: {p1}", {p0: chip.label, p1: displayedValue}));
        const label = document.createElement('span');
        label.className = 'stock-entry-chip-label';
        label.textContent = `${chip.label}:`;
        const value = document.createElement('span');
        value.className = 'stock-entry-chip-value';
        value.textContent = displayedValue;
        edit.append(label, value);
        edit.addEventListener('click', () => this.choose({ kind: chip.kind, key: chip.key, value: chip.value }));
        container.append(edit);
        if (chip.key) {
          const remove = document.createElement('button');
          remove.type = 'button';
          remove.disabled = this.busy;
          remove.className = 'stock-entry-chip-remove';
          remove.setAttribute('aria-label', t("Remove {p0}: {p1}", {p0: chip.label, p1: displayedValue}));
          remove.textContent = '×';
          remove.addEventListener('click', () => this.choose({ kind: 'remove', key: chip.key, value: chip.value }));
          container.append(remove);
        }
        this.chips.append(container);
      }
      this.clearButton.hidden = !model.field && !this.input.value;
      this.clearButton.disabled = this.busy;
      this.cancelButton.hidden = !this.adding;
      this.cancelButton.disabled = this.busy;
      this.feedback.replaceChildren();
      const addFeedback = (message, label, option) => {
        const row = document.createElement('div');
        row.className = 'stock-entry-error';
        const text = document.createElement('span');
        text.textContent = message;
        row.append(text);
        if (label) {
          const action = document.createElement('button');
          action.type = 'button';
          action.disabled = this.busy;
          action.textContent = label;
          action.addEventListener('pointerdown', event => event.preventDefault());
          action.addEventListener('click', () => this.choose(option));
          row.append(action);
        }
        this.feedback.append(row);
      };
      if (model.conflict) {
        const date = model.conflict.key?.split('/').at(-1) === 'occurred-at';
        const saved = date ? formatDate(model.conflict.saved) : model.conflict.saved;
        const attempted = date ? formatDate(model.conflict.value) : model.conflict.value;
        addFeedback(t("Saved: {p0}. Your value: {p1}.", {p0: saved || t('(empty)'), p1: attempted || t('(empty)')}), t('Use saved'), { kind: 'use-saved' });
        addFeedback(t('Keep your attempted value instead.'), t('Apply mine'), { kind: 'apply-mine' });
      } else if (model.unavailable) {
        if (this._doneCommandId) {
          addFeedback(t('Done may already have completed. Recover its saved result.'), t('Retry Done'), { kind: 'done' });
        } else {
          addFeedback(t('This unfinished operation is no longer available.'), t('Start again'), { kind: 'restart' });
        }
      } else {
        for (const error of model.errors || []) {
          const key = (error.path || []).join('/');
          addFeedback(error.message, key ? t('Fix field') : null, { kind: 'field', key });
        }
      }
      this.feedback.hidden = !this.feedback.childElementCount;
      this.fieldLabel.hidden = !model.field;
      this.fieldLabel.textContent = model.field ? `${model.field.label}:` : '';
      this.input.placeholder = model.placeholder || t('Search or add… choose fields or type a value');
      this.input.setAttribute('aria-label', model.field ? t("{p0} {p1}", {p0: model.field.label, p1: model.state?.mode === 'add' ? t('for operation') : t('filter')}) : t('Search or add'));
      const visible = this.open && options.length > 0 && this.modelText(model) === this.input.value;
      this.menu.hidden = !visible;
      this.input.setAttribute('aria-expanded', String(visible));
      this.input.removeAttribute('aria-activedescendant');
      this.menu.replaceChildren();
      for (const [index, option] of options.entries()) {
        const button = document.createElement('button');
        button.type = 'button';
        button.disabled = this.busy;
        button.id = `${this.menu.id}-${index}`;
        button.className = `stock-entry-option${index === this.activeIndex ? ' is-active' : ''}`;
        button.setAttribute('role', 'option');
        button.setAttribute('aria-selected', String(index === this.activeIndex));
        button.tabIndex = -1;
        const icon = document.createElement('span');
        icon.className = 'stock-entry-option-icon';
        icon.setAttribute('aria-hidden', 'true');
        icon.textContent = option.kind === 'add' || option.kind === 'line' ? '+' : option.kind === 'type' ? '↗' : '≡';
        const content = document.createElement('span');
        content.className = 'stock-entry-option-content';
        const label = document.createElement('span');
        label.className = 'stock-entry-option-field';
        label.textContent = option.value && option.key?.split('/').at(-1) === 'occurred-at'
          ? formatDate(option.value) : option.label;
        content.append(label);
        const description = document.createElement('span');
        description.className = 'stock-entry-option-description';
        description.textContent = option.savedAt ? `${option.description} ${formatDate(option.savedAt)}` : option.description || t('Choose field');
        if (model.conflict?.key?.split('/').at(-1) === 'occurred-at') {
          if (option.kind === 'use-saved') description.textContent = t("Saved value: {p0}", {p0: formatDate(model.conflict.saved)});
          if (option.kind === 'apply-mine') description.textContent = t("Your value: {p0}", {p0: formatDate(model.conflict.value)});
        }
        button.append(icon, content, description);
        button.addEventListener('pointerdown', event => event.preventDefault());
        button.addEventListener('click', () => this.choose(option));
        this.menu.append(button);
        if (visible && index === this.activeIndex) this.input.setAttribute('aria-activedescendant', button.id);
      }
      if (visible) this.menu.querySelector('.is-active')?.scrollIntoView({ block: 'nearest' });
    } finally {
      this._rendering = false;
    }
  }

  onKeydown(event) {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey) && ['add', 'unavailable'].includes(this._model?.state?.mode)) {
      event.preventDefault();
      this.complete({ kind: 'done' });
    } else if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault();
      this.open = true;
      this._navigated = true;
      const options = this.availableOptions;
      if (options.length) this.activeIndex = (this.activeIndex + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
      this.render();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      if (this.busy || this._model?.conflict) return;
      if (this._model?.field || this.input.value) this.complete({ kind: 'clear-field' });
      else if (this.adding) this.complete({ kind: 'cancel' });
      this.open = false;
      this.render();
    } else if (event.key === 'Enter' || (event.key === 'Tab' && this.input.value.trim())) {
      if (event.key === 'Enter') event.preventDefault();
      if (this._model?.unavailable) this.choose(this.availableOptions[0]);
      else if (this._navigated && this.open && this.modelText() === this.input.value && this.availableOptions[this.activeIndex]) this.choose(this.availableOptions[this.activeIndex]);
      else if (this._model?.field && this.input.value.trim()) this.complete();
      else if (this.open && this.modelText() === this.input.value && this.availableOptions[this.activeIndex] && this.availableOptions[this.activeIndex].kind !== 'replace-filter') this.choose(this.availableOptions[this.activeIndex]);
      else if (this.input.value.trim()) this.complete();
    } else if (event.key === 'Backspace' && !this.input.value && this._model?.field) {
      event.preventDefault();
      this.complete({ kind: 'clear-field' });
    }
  }
}

if (!customElements.get('stock-entry')) customElements.define('stock-entry', StockEntry);

document.addEventListener('keydown', event => {
  if (event.key.toLowerCase() !== 'k' || !(event.metaKey || event.ctrlKey) || event.altKey || event.isComposing || event.repeat) return;
  const entry = document.querySelector('#inventory-evidence-workbench stock-entry');
  if (!entry?.input) return;
  event.preventDefault();
  event.stopPropagation();
  entry.input.focus();
}, true);

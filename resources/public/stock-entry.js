const entryFields = [
  { key: 'name', label: 'Goods', description: 'Stock name' },
  { key: 'quantity', label: 'Quantity', description: 'Amount in stock' },
  { key: 'unit', label: 'Unit', description: 'Unit of measure' },
  { key: 'location', label: 'Location', description: 'Storage location' },
  { key: 'article', label: 'Article', description: 'Product code' },
];

class StockEntry extends HTMLElement {
  constructor() {
    super();
    this._rows = [];
    this.values = {};
    this.field = null;
    this.open = false;
    this.activeIndex = 0;
    this.options = [];
  }

  set rows(value) {
    this._rows = Array.isArray(value) ? value : [];
    if (this.input) this.renderMenu();
  }

  get rows() { return this._rows; }

  connectedCallback() {
    if (this.input) return;
    this.classList.add('stock-entry');
    this.innerHTML = `
      <div class="stock-entry-line">
        <div class="stock-entry-chips"></div>
        <span class="stock-entry-field" hidden></span>
        <input class="stock-entry-input" type="text" role="combobox"
          autocomplete="off" spellcheck="false" aria-label="Add stock row"
          aria-autocomplete="list" aria-expanded="false" placeholder="Add stock… choose fields or type a value">
        <button class="stock-entry-add" type="button" aria-label="Add row" title="Add row (⌘/Ctrl + Enter)" disabled>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
        </button>
      </div>
      <div class="stock-entry-menu" role="listbox" hidden></div>`;
    this.input = this.querySelector('input');
    this.chips = this.querySelector('.stock-entry-chips');
    this.fieldLabel = this.querySelector('.stock-entry-field');
    this.menu = this.querySelector('.stock-entry-menu');
    this.addButton = this.querySelector('.stock-entry-add');
    this.menu.id = `${this.id || 'stock-entry'}-options`;
    this.input.setAttribute('aria-controls', this.menu.id);
    this.input.addEventListener('input', () => {
      this.open = true;
      this.activeIndex = 0;
      this.renderMenu();
      this.updateButton();
    });
    this.input.addEventListener('focus', () => { this.open = true; this.renderMenu(); });
    this.input.addEventListener('keydown', event => this.onKeydown(event));
    this.addButton.addEventListener('click', () => this.addRow());
    this.addEventListener('focusout', event => {
      if (!this.contains(event.relatedTarget)) { this.open = false; this.renderMenu(); }
    });
    this.querySelector('.stock-entry-line').addEventListener('click', event => {
      if (!event.target.closest('button')) this.input.focus();
    });
    this.renderChips();
    this.renderMenu();
  }

  suggestions() {
    const query = this.input.value.trim();
    const needle = query.toLocaleLowerCase();
    const available = entryFields.filter(field => !Object.hasOwn(this.values, field.key));
    const known = field => [...new Set(this.rows.map(row => String(row[field.key] ?? '')).filter(Boolean))]
      .filter(value => value.toLocaleLowerCase().includes(needle))
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    if (this.field) {
      if (this.field.key === 'quantity') return [];
      const values = known(this.field).slice(0, 7).map(value => ({ field: this.field, value }));
      if (query && !values.some(option => option.value.toLocaleLowerCase() === needle)) {
        values.push({ field: this.field, value: query, isNew: true });
      }
      return values;
    }
    const fields = available.filter(field => field.label.toLocaleLowerCase().includes(needle))
      .map(field => ({ field }));
    const values = needle ? available.filter(field => field.key !== 'quantity')
      .flatMap(field => known(field).slice(0, 3).map(value => ({ field, value }))) : [];
    if (query && !values.length && !fields.length && available.some(field => field.key === 'name')) {
      values.push({ field: entryFields[0], value: query, isNew: true });
    }
    return [...values, ...fields].slice(0, 10);
  }

  renderMenu() {
    this.options = this.suggestions();
    this.activeIndex = Math.min(this.activeIndex, Math.max(0, this.options.length - 1));
    const visible = this.open && this.options.length > 0;
    this.menu.hidden = !visible;
    this.input.setAttribute('aria-expanded', String(visible));
    this.input.removeAttribute('aria-activedescendant');
    this.menu.replaceChildren();
    let group;
    for (const [index, option] of this.options.entries()) {
      const nextGroup = option.value === undefined ? 'Fields' : 'Suggestions';
      if (group !== nextGroup) {
        const heading = document.createElement('div');
        heading.className = 'stock-entry-menu-heading';
        heading.setAttribute('role', 'presentation');
        heading.textContent = nextGroup;
        this.menu.append(heading);
        group = nextGroup;
      }
      const button = document.createElement('button');
      button.type = 'button';
      button.id = `${this.menu.id}-${index}`;
      button.className = `stock-entry-option${index === this.activeIndex ? ' is-active' : ''}`;
      button.setAttribute('role', 'option');
      button.setAttribute('aria-selected', String(index === this.activeIndex));
      button.tabIndex = -1;
      const icon = document.createElement('span');
      icon.className = 'stock-entry-option-icon';
      icon.setAttribute('aria-hidden', 'true');
      icon.textContent = option.field.key === 'quantity' ? '#' : '≡';
      const content = document.createElement('span');
      content.className = 'stock-entry-option-content';
      const label = document.createElement('span');
      label.className = 'stock-entry-option-field';
      label.textContent = `${option.field.label}${option.value === undefined ? '' : ':'}`;
      content.append(label);
      if (option.value !== undefined) {
        const value = document.createElement('span');
        value.className = 'stock-entry-option-value';
        value.textContent = option.value;
        content.append(value);
      }
      const description = document.createElement('span');
      description.className = 'stock-entry-option-description';
      description.textContent = option.value === undefined ? option.field.description : option.isNew ? 'Use new value' : 'Existing value';
      button.append(icon, content, description);
      button.addEventListener('pointerdown', event => event.preventDefault());
      button.addEventListener('click', () => this.choose(option));
      this.menu.append(button);
      if (visible && index === this.activeIndex) this.input.setAttribute('aria-activedescendant', button.id);
    }
    if (visible) this.menu.querySelector('.is-active')?.scrollIntoView({ block: 'nearest' });
    this.fieldLabel.hidden = !this.field;
    this.fieldLabel.textContent = this.field ? `${this.field.label}:` : '';
    this.input.placeholder = this.field ? (this.field.key === 'quantity' ? 'Enter quantity' : `Choose or enter ${this.field.label.toLocaleLowerCase()}`)
      : Object.keys(this.values).length ? 'Add another field…' : 'Add stock… choose fields or type a value';
    this.input.setAttribute('aria-label', this.field ? `${this.field.label} for new row` : 'Add stock row');
    this.updateButton();
  }

  renderChips() {
    this.chips.replaceChildren();
    for (const field of entryFields) {
      if (!Object.hasOwn(this.values, field.key)) continue;
      const chip = document.createElement('span');
      chip.className = 'stock-entry-chip';
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.className = 'stock-entry-chip-edit';
      edit.setAttribute('aria-label', `Edit ${field.label}: ${this.values[field.key]}`);
      const label = document.createElement('span');
      label.className = 'stock-entry-chip-label';
      label.textContent = `${field.label}:`;
      const value = document.createElement('span');
      value.className = 'stock-entry-chip-value';
      value.textContent = this.values[field.key];
      edit.append(label, value);
      edit.addEventListener('click', () => {
        if (this.field && this.input.value.trim()) this.commit(this.field, this.input.value.trim());
        const previous = this.values[field.key];
        delete this.values[field.key];
        this.field = field;
        this.input.value = previous;
        this.open = true;
        this.activeIndex = 0;
        this.renderChips();
        this.renderMenu();
        this.input.focus();
        this.input.select();
      });
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'stock-entry-chip-remove';
      remove.setAttribute('aria-label', `Remove ${field.label}`);
      remove.textContent = '×';
      remove.addEventListener('click', () => {
        delete this.values[field.key];
        this.renderChips();
        this.renderMenu();
        this.updateButton();
        this.input.focus();
      });
      chip.append(edit, remove);
      this.chips.append(chip);
    }
    this.updateButton();
  }

  choose(option) {
    if (option.value === undefined) {
      this.field = option.field;
      this.input.value = '';
      this.activeIndex = 0;
      this.open = true;
      this.renderMenu();
    } else this.commit(option.field, option.value);
    this.input.focus();
  }

  commit(field, value) {
    if (!value.trim()) return false;
    this.values[field.key] = value.trim();
    this.field = null;
    this.input.value = '';
    this.activeIndex = 0;
    this.open = false;
    this.renderChips();
    this.renderMenu();
    return true;
  }

  updateButton() {
    this.addButton.disabled = !Object.keys(this.values).length && !this.input.value.trim();
  }

  onKeydown(event) {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      event.preventDefault();
      this.addRow();
    } else if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault();
      if (!this.open) this.open = true;
      else if (this.options.length) this.activeIndex = (this.activeIndex + (event.key === 'ArrowDown' ? 1 : -1) + this.options.length) % this.options.length;
      this.renderMenu();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      if (this.open) this.open = false;
      else if (this.field) { this.field = null; this.input.value = ''; }
      this.renderMenu();
      this.updateButton();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (this.open && this.options[this.activeIndex]) this.choose(this.options[this.activeIndex]);
      else if (this.field) this.commit(this.field, this.input.value);
      else if (this.input.value.trim() && !Object.hasOwn(this.values, 'name')) this.commit(entryFields[0], this.input.value);
    } else if (event.key === 'Backspace' && !this.input.value) {
      event.preventDefault();
      if (this.field) {
        this.field = null;
        this.renderMenu();
      } else {
        const last = entryFields.filter(field => Object.hasOwn(this.values, field.key)).at(-1);
        if (last) this.chips.querySelector(`[aria-label^="Edit ${last.label}:"]`)?.click();
      }
    }
  }

  addRow() {
    const pending = this.input.value.trim();
    if (pending) {
      const field = this.field || (!Object.hasOwn(this.values, 'name') ? entryFields[0] : null);
      if (!field) { this.open = true; this.renderMenu(); this.input.focus(); return; }
      this.commit(field, pending);
    }
    if (!Object.keys(this.values).length) return;
    const values = { ...this.values };
    this.values = {};
    this.field = null;
    this.input.value = '';
    this.open = false;
    this.renderChips();
    this.renderMenu();
    this.dispatchEvent(new CustomEvent('row-add', { bubbles: true, detail: { values } }));
    this.input.focus();
    this.open = false;
    this.renderMenu();
  }
}

customElements.define('stock-entry', StockEntry);

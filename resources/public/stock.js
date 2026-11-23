import { t } from './i18n.js';
import { formatDate, toISOString } from './dates.js';
import './inventory-grid.js';

// ponytail: per-browser preferences; per-member preferences move to chapter 30.
const prefs = {
  get(key, fallback) { try { return localStorage.getItem(`tover.inventory.${key}`) ?? fallback; } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem(`tover.inventory.${key}`, value); } catch { /* storage unavailable */ } },
};

const element = (tag, props = {}, ...children) => {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children.filter(child => child !== null && child !== undefined));
  return node;
};

document.addEventListener('datastar-fetch', event => {
  const workbench = event.detail?.el?.closest('stock-workbench');
  if (workbench && ['error', 'retries-failed'].includes(event.detail.type)) workbench.requestFailed(workbench.pending ? 'save' : 'read');
});

// The search box filters the one grid and pending openings live in its cells.
const now = () => new Date().toISOString();

class StockWorkbench extends HTMLElement {
  connectedCallback() { queueMicrotask(() => this.connect()); }
  connect() {
    if (!this.isConnected || this._connected) return;
    this._connected = true;
    this.workspaceId = this.dataset.workspaceId;
    this.mode = prefs.get('mode', 'units');
    this.days = prefs.get('period', '30');
    this.hideEmpty = true;
    this.scope = {};
    this.grid = this.querySelector('.stock-grid');
    this.notice = this.querySelector('.stock-notice');
    this.status = this.querySelector('.stock-save-status');
    this.submitButton = this.querySelector('.stock-submit');
    this.submitDetails = this.querySelector('.stock-submit-details');
    this.submitTime = this.querySelector('.stock-submit-time');
    this.submitReason = this.querySelector('.stock-submit-reason');
    this.pendingButton = this.querySelector('.stock-pending-filter');
    this.search = this.querySelector('.stock-search-input');
    this.chip = this.querySelector('.stock-filter-chip');
    this.list = this.querySelector('.stock-suggestions');
    this.queued = new Map();
    this.revisions = new Map();
    this.values = new Map();
    this.cellEntries = new Map();
    this.showModel(JSON.parse(this.dataset.model));
    if (this.mode !== 'units') this.refreshTable();
    this.showMode();

    const hide = this.querySelector('.stock-hide-empty');
    hide.addEventListener('click', () => { this.hideEmpty = !this.hideEmpty; hide.setAttribute('aria-pressed', String(this.hideEmpty)); this.refreshTable(); });
    for (const button of this.querySelectorAll('.stock-mode button')) {
      button.addEventListener('click', () => { this.mode = button.dataset.mode; prefs.set('mode', this.mode); this.showMode(); this.refreshTable(); });
    }
    this.pendingButton.addEventListener('click', () => (this.scope.pending ? this.leavePreview() : this.setScope({ ...this.scope, pending: true }, this.scopeLabel)));
    this.submitButton.addEventListener('click', () => this.review());
    this.chip.addEventListener('click', () => { this.setScope({}, ''); this.search.focus(); });
    this.search.addEventListener('input', () => { clearTimeout(this.typing); this.typing = setTimeout(() => this.suggest(), 120); });
    this.search.addEventListener('keydown', event => this.searchKey(event));
    this.search.addEventListener('blur', () => setTimeout(() => this.closeSuggestions(), 150));
    this.addEventListener('stock-cell', event => this.edit(event.detail));
    // A new row's details wait in the filter until its first quantity saves them with the opening.
    this.addEventListener('stock-new-field', event => { this.grid.acknowledgeSave(event.detail); this.scope = { ...this.scope, [event.detail.path === 'item' ? 'variant' : event.detail.path]: event.detail.value.trim() }; this.refreshTable(); });
    // Unit cost and a new item's details apply to every pending opening of the row.
    this.addEventListener('stock-item-field', event => {
      const { entries, field, value } = event.detail;
      entries.forEach((entry, index) => this.edit({ ...entry, id: event.detail.id, path: `${field}:${index}`, field, value, target: index ? null : event.detail }));
    });
    this.addEventListener('stock-add-item', event => {
      if (event.detail.name) { this.setScope({ productName: event.detail.name, newRow: true, pending: true }, '', true); return; }
      // "+" adds a row under the product in place; the table keeps its filter.
      this.setScope({ ...this.scope, productName: undefined, addTo: event.detail.id, newRow: true }, this.scopeLabel, true);
    });
    this.addEventListener('item-history', event => this.toggleHistory(event.detail));
    // Rows are included unless unticked, so rows added during the preview are included too.
    this.addEventListener('review-selection', event => {
      this.excluded = [...new Set(this.model.pending.map(entry => entry.rowId))].filter(id => !event.detail.selected.includes(id));
      this.grid.selectedEntries = event.detail.selected;
      this.selected = event.detail.selected; this.showSubmit();
    });
    this.addEventListener('stock-discard-row', event => {
      if (this.pending || !window.confirm(t('Discard the pending changes of this row? This cannot be undone.'))) return;
      this.discards = event.detail.entries.map(({ entry, revision, line, shared, values }) => ({ id: entry, line, shared, values, revision: Math.max(revision, this.revisions.get(entry) ?? 0) }));
      this.discard(this.discards.shift());
    });
    for (const property of ['tableResponse', 'suggestResponse', 'reviewResponse', 'historyResponse', 'saveResponse', 'changed']) {
      if (Object.prototype.hasOwnProperty.call(this, property)) { const value = this[property]; delete this[property]; this[property] = value; }
    }
  }
  // The Datastar effect reassigns every response whenever any signal changes; each answer is handled once.
  fresh({ requestId }) {
    this.handled ||= new Set();
    if (this.handled.has(requestId)) return false;
    this.handled.add(requestId); return true;
  }
  emit(name, detail) { this.dispatchEvent(new CustomEvent(name, { bubbles: true, detail })); }
  setStatus(status, message = '') {
    this.status.textContent = status === 'Saved' ? '' : t(status);
    this.grid.saveStatus = status;
    this.notice.textContent = message;
    this.notice.setAttribute('role', status === 'Failed' ? 'alert' : 'status');
  }
  retryNotice(node, retry) {
    const button = element('button', { type: 'button', textContent: t('Retry') });
    button.addEventListener('click', retry);
    node.replaceChildren(document.createTextNode(`${t('Could not load stock.')} `), button);
  }
  showMode() {
    for (const button of this.querySelectorAll('.stock-mode button')) button.setAttribute('aria-pressed', String(button.dataset.mode === this.mode));
  }

  // Search: suggestions filter the one table; picking a product opens a new stock item row under it.
  suggest() {
    const text = this.search.value.trim();
    if (!text) { this.closeSuggestions(); return; }
    this.suggestRequest = crypto.randomUUID();
    this.emit('stock-suggest', { requestId: this.suggestRequest, text });
  }
  set suggestResponse(response) {
    if (!this.list || !response || response.requestId !== this.suggestRequest || !this.fresh(response)) return;
    const options = [...response.options];
    if (!response.exact && response.text.trim()) options.push({ kind: 'create', label: response.text.trim() });
    this.options = options; this.active = 0;
    this.list.replaceChildren(...options.map((option, index) => {
      const item = element('li', { role: 'option', id: `stock-option-${index}`, className: `stock-option stock-option-${option.kind}` },
        element('span', { className: 'stock-option-label', textContent: option.kind === 'create' ? t('Create product «{p0}»', { p0: option.label }) : option.label }),
        element('span', { className: 'stock-option-kind', textContent: option.kind === 'product' ? t('Product') : option.kind === 'item' ? (option.hint || t('Stock item')) : '' }));
      item.addEventListener('mousedown', event => { event.preventDefault(); this.choose(option); });
      return item;
    }));
    this.list.hidden = !options.length;
    this.search.setAttribute('aria-expanded', String(!this.list.hidden));
    this.highlight();
  }
  highlight() {
    [...this.list.children].forEach((node, index) => node.setAttribute('aria-selected', String(index === this.active)));
    this.search.setAttribute('aria-activedescendant', this.list.hidden ? '' : `stock-option-${this.active}`);
  }
  searchKey(event) {
    if (event.key === 'Escape') { this.closeSuggestions(); return; }
    if (this.list.hidden || !this.options?.length) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.active = (this.active + (event.key === 'ArrowDown' ? 1 : -1) + this.options.length) % this.options.length;
      this.highlight();
    } else if (event.key === 'Enter') { event.preventDefault(); this.choose(this.options[this.active]); }
  }
  closeSuggestions() { this.list.hidden = true; this.search.setAttribute('aria-expanded', 'false'); }
  choose(option) {
    this.closeSuggestions(); this.search.value = '';
    if (option.kind === 'product') this.setScope({ productId: option.id, newRow: true, pending: this.scope.pending }, option.label, true);
    else if (option.kind === 'item') this.setScope({ itemId: option.id, pending: this.scope.pending }, option.label);
    // A new product opens at the top of the preview; the table is not filtered.
    else this.setScope({ productName: option.label, newRow: true, pending: true }, '', true);
  }
  // Leaving the preview keeps a product filter; a product being created only lived in the preview.
  leavePreview() {
    this.submitTime.value = ''; this.submitReason.value = '';
    this.setScope(this.scope.productName ? {} : { ...this.scope, pending: false }, this.scope.productName ? '' : this.scopeLabel);
  }
  setScope(scope, label, focus = false) {
    this.scope = Object.fromEntries(Object.entries(scope).filter(([, value]) => value !== false));
    this.scopeLabel = label;
    this.focusNew = focus;
    this.chip.hidden = !label;
    this.chip.textContent = label ? `${label} ×` : '';
    this.pendingButton.setAttribute('aria-pressed', String(Boolean(scope.pending)));
    this.refreshTable();
  }

  // Stock table
  refreshTable() {
    this.tableRequest = crypto.randomUUID();
    this.emit('stock-query', { requestId: this.tableRequest, mode: this.mode, hideEmpty: this.hideEmpty, filter: this.scope });
  }
  showModel(model) {
    this.model = model;
    this.grid.model = model;
    // A dot says there is something to preview; in preview the table itself shows it.
    this.pendingButton.classList.toggle('has-pending', Boolean(model.pending.length) && !this.scope.pending);
    // Preview starts with every row included; unticked rows wait for a later submit.
    const rows = [...new Set(model.pending.filter(entry => !entry.blocked).map(entry => entry.rowId))];
    if (!this.scope.pending) this.excluded = [];
    this.selected = this.scope.pending ? rows.filter(id => !(this.excluded ?? []).includes(id)) : null;
    this.grid.selectedEntries = this.selected ?? [];
    this.showSubmit();
    const row = this.focusNew && model.rows.find(candidate => candidate.className === 'stock-new-row');
    this.focusNew = false;
    if (row) this.grid.whenReady.then(() => this.grid.focusField(row.id, 'item'));
  }
  showSubmit() {
    const count = this.scope.pending ? (this.selected ?? []).length : 0;
    this.submitButton.disabled = !count;
    this.submitDetails.hidden = !this.scope.pending;
    this.submitButton.title = !this.model.pending.length ? t('No pending changes')
      : !this.scope.pending ? t('Preview pending changes to submit them')
        : count ? t('Submit the selected changes') : t('Select the changes to submit');
  }
  set tableResponse(response) {
    if (!this.grid || !response || response.requestId !== this.tableRequest || !this.fresh(response)) return;
    if (!response.model) { this.retryNotice(this.notice, () => this.refreshTable()); return; }
    // A retry offer goes once the table loads; a save error stays until the next save.
    if (this.notice.querySelector('button')) this.notice.replaceChildren();
    this.showModel(response.model);
    const [id, queued] = this.queued.entries().next().value || [];
    if (id && !this.pending) { this.queued.delete(id); this.save(id, queued); }
  }
  set changed(value) {
    if (!this.grid || !value || value === this.lastChange) return;
    this.lastChange = value;
    if (!this.pending) this.refreshTable();
  }

  // A cell commit saves a one-line opening for that item and warehouse.
  edit(detail) {
    const key = `${detail.id}|${detail.path}|${detail.values.variant ?? ''}|${detail.values.product ?? ''}`;
    const id = detail.entry || this.cellEntries.get(key) || crypto.randomUUID();
    this.cellEntries.set(key, id);
    // The line is the unit of an edit; other lines of its entry have their own.
    const slot = detail.values['line-id'] ?? `${id}-0`;
    if (detail.entry && detail.field === 'quantity' && !detail.value.trim()) { this.discard({ id, line: slot, shared: detail.shared, values: detail.values, revision: Math.max(detail.revision, this.revisions.get(id) ?? 0) }); return; }
    // Build on the newest values this page holds: queued, then in flight, then its own last save, then the read.
    const inFlight = this.pending?.kind === 'save' && this.pending.slot === slot ? this.pending.values : null;
    const own = (this.revisions.get(id) ?? 0) >= detail.revision ? this.values.get(slot) : null;
    const base = this.queued.get(slot)?.values ?? inFlight ?? own ?? detail.values;
    // A new row's details live in the filter; typing can outrun the table read that carries them.
    const typed = detail.id.startsWith('new-row:') ? Object.fromEntries(['variant', 'article', 'barcodes', 'unit', 'unit-cost']
      .filter(key => this.scope[key] !== undefined).map(key => [key, this.scope[key]])) : {};
    const values = { 'occurred-at': now(), ...base, ...typed, [detail.field]: detail.value };
    const request = { id, values, revision: detail.revision, target: 'target' in detail ? detail.target : detail };
    if (this.pending) { this.queued.set(slot, request); return; }
    this.save(slot, request);
  }
  save(slot, { id, values, revision, target }) {
    const current = Math.max(revision, this.revisions.get(id) ?? 0);
    this.pending = { kind: 'save', id, slot, revision: current, values, commandId: crypto.randomUUID(), target };
    this.setStatus('Saving');
    this.emit('stock-save', { workspaceId: this.workspaceId, entryId: id, revision: current, values, commandId: this.pending.commandId });
  }
  discard({ id, revision, line, shared, values }) {
    if (this.pending) return;
    // Other lines of the entry stay.
    if (shared) { this.save(line, { id, revision, values: { ...values, 'line-id': line, remove: 'true' } }); this.pending.kind = 'discard-line'; return; }
    this.pending = { kind: 'discard', id, commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-discard', { workspaceId: this.workspaceId, entryId: id, revision, commandId: this.pending.commandId });
  }
  set saveResponse(response) {
    if (!this.pending || !response || response.commandId !== this.pending.commandId) return;
    const request = this.pending; this.pending = null;
    if (response.status === 'ok') {
      if (['save', 'discard-line', 'describe'].includes(request.kind)) this.revisions.set(request.id, request.revision + 1);
      if (['save', 'discard-line'].includes(request.kind)) this.values.set(request.slot, request.values);
      if (request.kind === 'describe') { this.forget(request.id); this.describe(); return; }
      if (request.target) this.grid.acknowledgeSave(request.target);
      // The new item now has its own row; the blank row starts over.
      if (request.target?.id.startsWith('new-row:')) for (const key of ['variant', 'article', 'barcodes', 'unit', 'unit-cost']) delete this.scope[key];
      if (request.kind === 'accept') {
        this.leavePreview();
      }
      this.setStatus('Saved');
    } else {
      this.setStatus('Failed', response.message);
    }
    // Fanned-out edits save one after another before the table is read again.
    const [id, queued] = this.queued.entries().next().value || [];
    if (id && request.kind === 'save' && response.status === 'ok') { this.queued.delete(id); this.save(id, queued); return; }
    if (request.kind.startsWith('discard') && response.status === 'ok' && this.discards?.length) { this.discard(this.discards.shift()); return; }
    this.refreshTable();
  }

  // Review and accept
  review() {
    if (this.pending || this.submitButton.disabled) return;
    // An entry is submitted whole: every row it touches must be included and complete.
    const left = new Set(this.model.pending.filter(entry => entry.blocked || !this.selected.includes(entry.rowId)).map(entry => entry['entry-id']));
    const rows = [...new Map(this.model.pending.filter(entry => !left.has(entry['entry-id'])).map(entry => [entry['entry-id'], entry])).values()];
    if (!rows.length) { this.setStatus('Failed', t('Include every row of the opening.')); return; }
    const shown = new Set(this.model.rows.map(row => row.id));
    const hidden = this.selected.filter(id => !shown.has(id)).length;
    if (hidden && !window.confirm(t('The filter hides {p0} selected rows. Submit them too?', { p0: hidden }))) return;
    this.reviewed = rows.map(row => ({ 'entry-id': row['entry-id'], revision: row.revision }));
    const details = { occurredAt: toISOString(this.submitTime.value), reason: this.submitReason.value.trim() };
    if (!this.submitTime.checkValidity()) { this.submitTime.reportValidity(); return; }
    this.describing = details.occurredAt || details.reason ? { details, entries: [...this.reviewed] } : null;
    this.describe();
  }
  // The time and reason given at submit are saved on each opening, then the openings are reviewed.
  describe() {
    const entry = this.describing?.entries.shift();
    if (!entry) { this.describing = null; this.requestReview(); return; }
    const id = entry['entry-id'];
    const revision = Math.max(entry.revision, this.revisions.get(id) ?? 0);
    this.pending = { kind: 'describe', id, revision, commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-describe', { workspaceId: this.workspaceId, entryId: id, revision, ...this.describing.details, commandId: this.pending.commandId });
  }
  // A later cell edit must build on the described opening, not on this page's older values.
  forget(id) {
    for (const [slot] of this.values) if (slot.startsWith(id)) this.values.delete(slot);
  }
  requestReview() {
    const rows = this.reviewed;
    this.reviewRequest = crypto.randomUUID();
    this.emit('stock-review', { requestId: this.reviewRequest, selection: rows.map(row => ({ 'entry-id': row['entry-id'], revision: Math.max(row.revision, this.revisions.get(row['entry-id']) ?? 0) })), order: rows.map(row => row['entry-id']) });
  }
  set reviewResponse(response) {
    if (!this.grid || !response || response.requestId !== this.reviewRequest || !this.fresh(response)) return;
    const { review } = response;
    if (!review) { this.setStatus('Failed', t('Could not check the pending changes. Try again.')); return; }
    if (!review['valid?']) { this.setStatus('Failed', review.problems.map(problem => problem.message).join(' ')); return; }
    this.pending = { kind: 'accept', commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-accept', { workspaceId: this.workspaceId, selection: response.selection, order: response.order, fingerprint: review.fingerprint, commandId: this.pending.commandId });
  }

  // History below the item's row
  toggleHistory({ id, expanded }) {
    if (expanded) { this.grid.rowDetail = null; return; }
    const period = element('select', { className: 'stock-history-period', ariaLabel: t('Period') },
      ...[['7', 'Last 7 days'], ['30', 'Last 30 days'], ['90', 'Last 90 days'], ['365', 'Last 365 days'], ['all', 'All time']]
        .map(([value, label]) => element('option', { value, textContent: t(label), selected: value === this.days })));
    period.addEventListener('change', () => { this.days = period.value; prefs.set('period', this.days); this.loadHistory(id); });
    const tabs = element('div', { className: 'stock-history-tabs', role: 'tablist' });
    const body = element('div', { className: 'stock-history-body' });
    for (const [key, label] of [['history', 'History'], ['lineage', 'Lineage']]) {
      const tab = element('button', { type: 'button', role: 'tab', textContent: t(label) });
      tab.dataset.tab = key; tab.setAttribute('aria-selected', String(key === 'history'));
      tab.addEventListener('click', () => {
        for (const other of tabs.children) other.setAttribute('aria-selected', String(other === tab));
        this.showHistory();
      });
      tabs.append(tab);
    }
    this.historyNode = element('div', { className: 'stock-history', id: 'history-panel' }, element('div', { className: 'sheet-actions' }, tabs, period), body);
    this.historyItem = id; this.historyData = null;
    this.grid.rowDetail = { rowId: id, node: this.historyNode };
    this.loadHistory(id);
  }
  loadHistory(id) {
    this.historyRequest = crypto.randomUUID();
    this.historyNode.querySelector('.stock-history-body').replaceChildren(element('p', { className: 'quiet', textContent: t('Loading…') }));
    this.emit('stock-history', { requestId: this.historyRequest, stockItemId: id, days: this.days === 'all' ? null : Number(this.days) });
  }
  set historyResponse(response) {
    if (!this.historyNode || !response || response.requestId !== this.historyRequest || !this.fresh(response)) return;
    this.historyData = response;
    this.showHistory();
  }
  showHistory() {
    const body = this.historyNode.querySelector('.stock-history-body');
    const response = this.historyData;
    if (!response) return;
    if (response.status !== 'ok') { this.retryNotice(body, () => this.loadHistory(this.historyItem)); return; }
    const tab = this.historyNode.querySelector('[aria-selected="true"]').dataset.tab;
    if (tab === 'lineage') { body.replaceChildren(element('p', { textContent: t('No production is recorded for this item in this period.') })); return; }
    if (!response.rows.length) { body.replaceChildren(element('p', { textContent: t('No accepted records in this period.') })); return; }
    body.replaceChildren(element('table', { className: 'stock-history-rows' },
      element('thead', {}, element('tr', {}, ...['Occurred at', 'Kind', 'Warehouse', 'Change', 'Balance', 'Unit cost', 'Reason'].map(label => element('th', { textContent: t(label) })))),
      element('tbody', {}, ...response.rows.map(row => element('tr', { className: row['superseded?'] ? 'stock-history-superseded' : '' },
        element('td', { textContent: formatDate(row['occurred-at']) }),
        element('td', { textContent: row['kind-label'] + (row['superseded?'] ? ` (${t('superseded')})` : '') }),
        element('td', { textContent: row.warehouse }),
        element('td', { textContent: String(row['quantity-change']) }),
        element('td', { textContent: row['balance-after'] ?? '—' }),
        element('td', { textContent: row['unit-cost'] ?? '' }),
        element('td', { textContent: row.reason ?? '' }))))));
  }

  requestFailed(kind) {
    if (kind === 'save') { this.pending = null; this.setStatus('Failed', t('Could not save. Your edit is retained; retry when connected.')); }
    else if (kind === 'table') this.retryNotice(this.notice, () => this.refreshTable());
    else if (kind === 'review') this.setStatus('Failed', t('Could not check the pending changes. Try again.'));
    else if (kind === 'history' && this.historyNode) this.retryNotice(this.historyNode.querySelector('.stock-history-body'), () => this.loadHistory(this.historyItem));
  }
}

customElements.define('stock-workbench', StockWorkbench);

import { t } from './i18n.js';
import './inventory-grid.js';
import { InventoryWorkbench, element } from './inventory-submit.js';

const localNow = () => { const d = new Date(); d.setSeconds(0, 0); return new Date(d - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16); };
// The unfinished smart-input addition lives only in this browser until Done or Cancel (board chapter 18).
const store = {
  key: workspace => `tover.operations.addition.${workspace}`,
  load(workspace) { try { return JSON.parse(localStorage.getItem(this.key(workspace)) ?? 'null'); } catch { return null; } },
  save(workspace, value) { try { if (value) localStorage.setItem(this.key(workspace), JSON.stringify(value)); else localStorage.removeItem(this.key(workspace)); } catch { /* storage unavailable */ } },
};

// Board chapters 18–25: every operation kind as shared drafts, a group of rows per operation. Each cell commit
// saves one field on the server, which keeps the rest of the saved entry; Preview and Submit work as on the stock page.
class OperationsWorkbench extends InventoryWorkbench {
  get saveEvent() { return 'operations-save'; }
  connectedCallback() { queueMicrotask(() => this.connect()); }
  connect() {
    if (!this.isConnected || this._connected) return;
    this._connected = true;
    this.workspaceId = this.dataset.workspaceId;
    this.scope = {};
    this.grid = this.querySelector('.stock-grid');
    this.notice = this.querySelector('.stock-notice');
    this.status = this.querySelector('.stock-save-status');
    this.submitButton = this.querySelector('.stock-submit');
    this.submitDetails = this.querySelector('.stock-submit-details');
    this.submitTime = this.querySelector('.stock-submit-time');
    this.submitReason = this.querySelector('.stock-submit-reason');
    this.pendingButton = this.querySelector('.stock-pending-filter');
    this.queued = new Map();
    this.revisions = new Map();
    this.values = new Map();
    this.showModel(JSON.parse(this.dataset.model));

    this.pendingButton.addEventListener('click', () => { if (this.scope.pending) this.leavePreview(); else { this.scope = { pending: true }; this.pendingButton.setAttribute('aria-pressed', 'true'); this.refreshTable(); } });
    this.submitButton.addEventListener('click', () => this.review());
    const menu = this.querySelector('.operations-add');
    menu.addEventListener('change', () => { if (menu.value) this.add(menu.value); menu.value = ''; });
    this.addEventListener('operation-cell', event => this.edit(event.detail));
    this.addEventListener('operation-add-line', event => this.edit({ ...event.detail, field: 'add-line', value: event.detail.side ?? '', target: null }));
    this.addEventListener('operation-discard', event => {
      if (this.pending || !window.confirm(t('Discard this pending operation? This cannot be undone.'))) return;
      this.discard({ id: event.detail.entry, revision: Math.max(event.detail.revision, this.revisions.get(event.detail.entry) ?? 0) });
    });
    this.addEventListener('inventory-item-options', event => {
      this.itemsRequest = event.detail.requestId;
      this.emit('operations-items', { requestId: event.detail.requestId, text: event.detail.text });
    });
    this.addEventListener('inventory-catalogue-options', event => {
      this.suppliersRequest = event.detail.requestId;
      this.emit('operations-suppliers', { requestId: event.detail.requestId, text: event.detail.text });
    });
    this.addEventListener('review-selection', event => {
      this.excluded = this.model.pending.map(entry => entry.rowId).filter(id => !event.detail.selected.includes(id));
      this.grid.selectedEntries = event.detail.selected;
      this.selected = event.detail.selected; this.showSubmit();
    });
    this.setupSmart();
    for (const property of ['tableResponse', 'itemsResponse', 'suppliersResponse', 'reviewResponse', 'saveResponse', 'changed']) {
      if (Object.prototype.hasOwnProperty.call(this, property)) { const value = this[property]; delete this[property]; this[property] = value; }
    }
  }
  // A new operation saves at once with the current time and one empty line.
  add(kind) {
    this.edit({ entry: crypto.randomUUID(), revision: 0, kind, field: 'create', value: new Date().toISOString().replace(/\.\d+Z$/, 'Z'), target: null });
  }
  edit(detail) {
    const id = detail.entry;
    const values = { kind: detail.kind, field: detail.field, value: detail.value ?? '', 'line-id': detail.line ?? '',
      ...(detail.referenceName ? { referenceName: detail.referenceName } : {}) };
    const request = { id, values, revision: detail.revision, target: 'target' in detail ? detail.target : detail };
    const slot = `${id}|${detail.line ?? ''}|${detail.field}`;
    if (this.pending) { this.queued.set(slot, request); return; }
    this.save(slot, request);
  }
  leavePreview() {
    this.submitTime.value = ''; this.submitReason.value = '';
    this.scope = {};
    this.pendingButton.setAttribute('aria-pressed', 'false');
    this.refreshTable();
  }
  refreshTable() {
    this.tableRequest = crypto.randomUUID();
    this.emit('operations-query', { requestId: this.tableRequest, preview: Boolean(this.scope.pending) });
  }
  showModel(model) {
    this.model = model;
    this.grid.model = model;
    this.pendingButton.classList.toggle('has-pending', Boolean(model.pending.length) && !this.scope.pending);
    if (!this.scope.pending) this.excluded = [];
    this.selected = this.scope.pending ? model.pending.map(entry => entry.rowId).filter(id => !(this.excluded ?? []).includes(id)) : null;
    this.grid.selectedEntries = this.selected ?? [];
    this.showSubmit();
  }
  set itemsResponse(response) {
    if (!this.grid || !response || !this.fresh(response)) return;
    if (response.requestId === this.smartItemsRequest) { this.showSmartItems(response); return; }
    if (response.requestId === this.itemsRequest) this.grid.itemOptionsResponse = response;
  }
  set saveResponse(response) {
    const added = this.pending?.slot === 'smart' && response?.commandId === this.pending.commandId && response.status === 'ok';
    super.saveResponse = response;
    if (added) this.cancelAddition();
  }

  // Smart input: "add" offers the kinds; a kind opens editable chips; Done saves one shared pending operation.
  setupSmart() {
    this.smart = this.querySelector('.operations-smart-input');
    this.smartList = this.querySelector('.operations-smart-list');
    this.chips = this.querySelector('.operations-smart-chips');
    this.smart.addEventListener('input', () => this.smartSuggest());
    this.smart.addEventListener('keydown', event => {
      if (event.key === 'Escape') { this.smart.value = ''; this.smartSuggest(); }
      if (event.key === 'Enter' && this.smartOptions?.length) { event.preventDefault(); this.startAddition({ kind: this.smartOptions[0] }); }
    });
    document.addEventListener('keydown', event => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' && this.isConnected) { event.preventDefault(); (this.chips.querySelector('input') ?? this.smart).focus(); }
    });
    const asked = location.hash.match(/add=([a-z-]+)/)?.[1];
    if (asked) history.replaceState(null, '', location.pathname);
    const kept = store.load(this.workspaceId);
    if (kept && this.kind(kept.kind)) this.startAddition(kept); else if (this.kind(asked)) this.startAddition({ kind: asked });
  }
  // Per kind from the server: label, party ('supplier' | 'store' | 'either' | null), price and reason.
  kind(code) { return (this.model.choices?.kinds ?? []).find(k => k.kind === code); }
  smartSuggest() {
    const text = this.smart.value.trim().toLowerCase();
    const [first = '', ...rest] = text.split(/\s+/);
    const add = first.length >= 2 && ['add', t('Add operation').toLowerCase().split(' ')[0]].some(word => word.startsWith(first) || first.startsWith(word));
    const more = rest.join(' ');
    const kinds = this.model.choices?.kinds ?? [];
    const fits = k => k.label.toLowerCase().startsWith(more) || k.kind.startsWith(more);
    this.smartOptions = add ? kinds.filter(k => !more || fits(k) || !kinds.some(fits)).map(k => k.kind) : [];
    this.smartList.replaceChildren(...this.smartOptions.map((kind, index) => {
      const item = element('li', { role: 'option', id: `operations-smart-${index}`, className: 'stock-option', textContent: `${t('Add operation')}: ${this.kind(kind).label}` });
      item.setAttribute('aria-selected', String(index === 0));
      item.addEventListener('mousedown', event => { event.preventDefault(); this.startAddition({ kind }); });
      return item;
    }));
    this.smartList.hidden = !this.smartOptions.length;
    this.smart.setAttribute('aria-expanded', String(!this.smartList.hidden));
  }
  startAddition(draft) {
    this.smart.value = ''; this.smartSuggest();
    this.addition = { at: localNow(), party: '', item: '', itemId: '', quantity: '', price: '', reason: '', from: '', to: '', direction: '', amount: '', ...draft };
    store.save(this.workspaceId, this.addition);
    const kind = this.kind(this.addition.kind);
    const choices = { suppliers: [], stores: [], warehouses: [], ...this.model.choices };
    const field = (key, props, labelText) => {
      const input = element(props.tag ?? 'input', { className: `operations-chip operations-chip-${key}`, ...props, tag: undefined });
      input.setAttribute('aria-label', labelText);
      input.value = this.addition[key] ?? '';
      input.addEventListener(input.tagName === 'SELECT' ? 'change' : 'input', () => {
        this.addition[key] = input.value;
        if (key === 'item') { this.addition.itemId = this.smartItems?.get(input.value) ?? ''; this.requestSmartItems(input.value); }
        store.save(this.workspaceId, this.addition); this.checkAddition();
      });
      input.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !input.value) { event.preventDefault(); this.cancelAddition(); }
        if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); this.finishAddition(); }
      });
      return input;
    };
    const select = (key, labelText, options) => {
      const input = field(key, { tag: 'select' }, labelText);
      input.append(new Option(labelText, ''), ...options.map(([value, text]) => new Option(text, value)));
      input.value = this.addition[key] ?? '';
      return input;
    };
    const party = {
      supplier: () => this.addition.kind === 'purchase'
        ? field('party', { type: 'text', placeholder: t('Supplier') }, t('Supplier'))
        : select('party', t('Supplier'), choices.suppliers.map(s => [s.id, s.name])),
      store: () => select('party', t('Store'), choices.stores.map(s => [s.id, s.name])),
      either: () => select('party', t('Supplier or store'), [...choices.suppliers.map(s => [`supplier:${s.id}`, s.name]), ...choices.stores.map(s => [`store:${s.id}`, s.name])]),
    }[kind.party]?.() ?? null;
    if (party?.tagName === 'INPUT') party.setAttribute('list', 'operations-smart-suppliers');
    const warehouses = choices.warehouses.map(w => [w.id, w.name]);
    const payment = kind.kind === 'payment';
    this.done = element('button', { type: 'button', className: 'operations-smart-done', textContent: t('Done') });
    this.done.addEventListener('click', () => this.finishAddition());
    const cancel = element('button', { type: 'button', className: 'operations-smart-cancel', textContent: t('Cancel') });
    cancel.addEventListener('click', () => this.cancelAddition());
    this.chips.replaceChildren(...[
      element('span', { className: 'operations-chip-kind', textContent: kind.label }),
      payment && select('direction', t('Received or paid'), [['in', t('Payment received')], ['out', t('Payment made')]]),
      party,
      element('datalist', { id: 'operations-smart-suppliers' }, ...choices.suppliers.map(s => new Option(s.name))),
      kind.kind === 'transfer' && select('from', t('From warehouse'), warehouses),
      kind.kind === 'transfer' && select('to', t('To warehouse'), warehouses),
      field('at', { type: 'datetime-local' }, t('Occurred at')),
      !payment && field('item', { type: 'text', placeholder: t('Stock item') }, t('Stock item')),
      element('datalist', { id: 'operations-smart-items' }),
      !payment && field('quantity', { type: 'text', inputMode: 'numeric', placeholder: t('Quantity'), size: 6 }, t('Quantity')),
      kind.price && field('price', { type: 'text', inputMode: 'decimal', placeholder: t('Unit price'), size: 8 }, t('Unit price')),
      payment && field('amount', { type: 'text', inputMode: 'decimal', placeholder: t('Amount'), size: 10 }, t('Amount')),
      kind.reason && field('reason', { type: 'text', placeholder: t('Reason') }, t('Reason')),
      this.done, cancel].filter(Boolean));
    this.chips.querySelector('.operations-chip-item')?.setAttribute('list', 'operations-smart-items');
    this.chips.hidden = false;
    this.checkAddition();
    (this.chips.querySelector('.operations-chip') ?? this.done).focus();
  }
  requestSmartItems(text) {
    clearTimeout(this.smartTimer);
    this.smartTimer = setTimeout(() => { this.smartItemsRequest = crypto.randomUUID(); this.emit('operations-items', { requestId: this.smartItemsRequest, text }); }, 150);
  }
  showSmartItems(response) {
    this.smartItems = new Map((response.options ?? []).map(option => [option.label, option.value]));
    this.chips.querySelector('#operations-smart-items')?.replaceChildren(...[...this.smartItems.keys()].map(text => new Option(text)));
    const item = this.chips.querySelector('.operations-chip-item');
    if (item) { this.addition.itemId = this.smartItems.get(item.value) ?? this.addition.itemId; this.checkAddition(); }
  }
  checkAddition() {
    const a = this.addition;
    const kind = this.kind(a.kind);
    const needsParty = ['supplier', 'either'].includes(kind.party);
    const ready = a.kind === 'payment'
      ? a.direction && a.party && Number(a.amount.replace(',', '.')) > 0
      : a.item.trim() && /^\d+$/.test(a.quantity.trim()) && Number(a.quantity) >= 1
        && (!needsParty || a.party.trim()) && (!kind.reason || a.reason.trim()) && (a.kind !== 'transfer' || (a.from && a.to && a.from !== a.to));
    this.done.disabled = !ready || Boolean(this.pending);
  }
  finishAddition() {
    if (!this.addition || this.done.disabled || this.pending) return;
    const a = this.addition;
    const values = { kind: a.kind, 'occurred-at': a.at ? new Date(a.at).toISOString().replace(/\.\d+Z$/, 'Z') : '',
      item: a.itemId || a.item.trim(), quantity: a.quantity.trim(), price: a.price.trim(), reason: a.reason.trim(),
      'from-warehouse': a.from, 'to-warehouse': a.to, direction: a.direction, amount: a.amount.trim(),
      ...({ store: { store: a.party }, supplier: { supplier: a.party.trim() }, either: { counterparty: a.party } }[this.kind(a.kind).party] ?? {}) };
    this.pending = { kind: 'save', id: crypto.randomUUID(), slot: 'smart', revision: 0, values, commandId: crypto.randomUUID(), target: null };
    this.setStatus('Saving');
    this.emit('operations-add', { workspaceId: this.workspaceId, entryId: this.pending.id, values, commandId: this.pending.commandId });
  }
  cancelAddition() {
    this.addition = null;
    store.save(this.workspaceId, null);
    this.chips.replaceChildren(); this.chips.hidden = true;
    this.smart.focus();
  }
  set suppliersResponse(response) {
    if (!this.grid || !response || response.requestId !== this.suppliersRequest || !this.fresh(response)) return;
    this.grid.referenceOptionsResponse = response;
  }
  requestFailed(kind) {
    if (kind === 'save') { this.pending = null; this.setStatus('Failed', t('Could not save. Your edit is retained; retry when connected.')); }
    else if (kind === 'table') this.retryNotice(this.notice, () => this.refreshTable());
    else if (kind === 'review') this.setStatus('Failed', t('Could not check the pending changes. Try again.'));
    else if (kind === 'items') this.grid.itemOptionsResponse = { requestId: this.itemsRequest, status: 'failed', options: [] };
    else if (kind === 'suppliers') this.grid.referenceOptionsResponse = { requestId: this.suppliersRequest, status: 'failed', options: [] };
  }
}

customElements.define('operations-workbench', OperationsWorkbench);

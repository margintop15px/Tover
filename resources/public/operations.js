import { t } from './i18n.js';
import './inventory-grid.js';
import './smart-entry.js';
import { InventoryWorkbench, prefs } from './inventory-submit.js';

const now = () => new Date().toISOString().replace(/\.\d+Z$/, 'Z');

// Board chapters 18–25: every operation kind as shared drafts, a group of rows per operation. Each cell commit
// saves one field on the server, which keeps the rest of the saved entry; Preview and Submit work as on the stock page.
class OperationsWorkbench extends InventoryWorkbench {
  get saveEvent() { return 'operations-save'; }
  get prefix() { return 'operations'; }
  get modeKey() { return 'operations-mode'; }
  connectedCallback() { queueMicrotask(() => this.connect()); }
  connect() {
    if (!this.isConnected || this._connected) return;
    this._connected = true;
    this.workspaceId = this.dataset.workspaceId;
    this.scope = {};
    this.grid = this.querySelector('.stock-grid');
    this.notice = this.querySelector('.stock-notice');
    this.setupTable();
    this.status = this.querySelector('.stock-save-status');
    this.submitButton = this.querySelector('.stock-submit');
    this.pendingButton = this.querySelector('.stock-pending-filter');
    this.queued = new Map();
    this.revisions = new Map();
    this.values = new Map();
    this.smart = this.querySelector('smart-entry');
    this.showModel(JSON.parse(this.dataset.model));
    if (this.mode !== 'units') this.refreshTable();
    this.showMode();

    this.pendingButton.addEventListener('click', () => { if (this.scope.pending) this.leavePreview(); else this.enterPreview(); });
    this.addEventListener('stock-edit', event => { this.focusRow = event.detail.id; this.enterPreview(); });
    this.submitButton.addEventListener('click', () => this.review());
    const menu = this.querySelector('.operations-add');
    menu.addEventListener('change', () => { if (menu.value) this.add(menu.value); menu.value = ''; });
    this.addEventListener('operation-cell', event => this.edit(event.detail));
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
  // A new operation opens in Preview and saves at once with the current time and one empty line.
  add(kind) {
    if (!this.scope.pending) { this.scope = { pending: true }; this.showPreview(); }
    this.edit({ entry: crypto.randomUUID(), revision: 0, kind, field: 'create', value: new Date().toISOString().replace(/\.\d+Z$/, 'Z'), target: null });
  }
  edit(detail) {
    const id = detail.entry;
    const values = { kind: detail.kind, field: detail.field, value: detail.value ?? '', 'line-id': detail.line ?? '',
      ...(detail.side ? { side: detail.side } : {}), ...(detail.referenceName ? { referenceName: detail.referenceName } : {}) };
    const request = { id, values, revision: detail.revision, target: 'target' in detail ? detail.target : detail };
    const slot = `${id}|${detail.line ?? ''}|${detail.field}`;
    if (this.pending) { this.queued.set(slot, request); return; }
    this.save(slot, request);
  }
  showPreview() {
    this.pendingButton.setAttribute('aria-pressed', String(Boolean(this.scope.pending)));
    this.showMode();
  }
  enterPreview() {
    this.scope = { pending: true };
    this.showPreview();
    this.refreshTable();
  }
  leavePreview() {
    this.scope = {};
    this.showPreview();
    this.refreshTable();
  }
  refreshTable() {
    this.tableRequest = crypto.randomUUID();
    this.emit('operations-query', { requestId: this.tableRequest, preview: Boolean(this.scope.pending),
      mode: this.scope.pending ? 'units' : this.mode, sort: this.order.sort, direction: this.order.direction, filters: this.order.filters });
  }
  showModel(model) {
    this.model = model;
    const focus = this.focusRow ? model.rows.findIndex(row => row.groupId === this.focusRow) : -1;
    this.presentModel(Math.max(0, focus - 66));
    if (focus >= 0 && this.scope.pending) { this.grid.selectRow(model.rows[focus].id); this.focusRow = null; }
    this.smart.context = this.smartContext();
    this.pendingButton.classList.toggle('has-pending', Boolean(model.pending.length) && !this.scope.pending);
    if (!this.scope.pending) this.excluded = [];
    this.selected = this.scope.pending ? model.pending.map(entry => entry.rowId).filter(id => !(this.excluded ?? []).includes(id)) : null;
    this.grid.selectedEntries = this.selected ?? [];
    this.showSubmit();
  }
  set itemsResponse(response) {
    if (!this.grid || !response || !this.fresh(response)) return;
    if (response.requestId === this.smartItemsRequest) { this.smart.items = response.options ?? []; return; }
    if (response.requestId === this.itemsRequest) this.grid.itemOptionsResponse = response;
  }
  set saveResponse(response) {
    const request = this.pending;
    const answered = request?.slot === 'smart' && response?.commandId === request.commandId;
    if (answered && response.status === 'ok') { this.smart.finish(); this.scope = { pending: true }; this.focusRow = request.id; this.showPreview(); }
    super.saveResponse = response;
    if (answered) { this.smart.busy = false; if (response.status !== 'ok') this.smart.error = response.message; }
  }

  // Smart input: filters the table, or builds one operation field by field; Done saves it as one shared entry.
  // The unfinished addition lives only in this browser until Done or Cancel (board chapter 18).
  smartContext() {
    const choices = () => this.model.choices;
    const kindOf = draft => choices().kinds.find(kind => kind.kind === draft.kind);
    const fields = draft => {
      const kind = kindOf(draft), choose = (rows, prefix = '') => rows.map(row => ({ value: prefix + row.id, label: row.name }));
      const select = options => ({ type: 'select', allowEmpty: true, options });
      const { suppliers, stores } = choices(), warehouses = choose(choices().warehouses);
      const head = [];
      if (kind.kind === 'payment') head.push({ key: 'direction', label: t('Direction'), required: true, editor: select([{ value: 'in', label: t('Payment received') }, { value: 'out', label: t('Payment made') }]) });
      if (kind.kind === 'purchase') head.push({ key: 'supplier', label: t('Supplier'), required: true, editor: { type: 'text' }, suggest: suppliers.map(row => ({ value: row.name, label: row.name })) });
      else if (kind.party === 'supplier') head.push({ key: 'supplier', label: t('Supplier'), required: true, editor: select(choose(suppliers)) });
      if (kind.party === 'store') head.push({ key: 'store', label: t('Store'), editor: select(choose(stores)) });
      if (kind.party === 'either') head.push({ key: 'counterparty', label: t('Supplier or store'), required: true, editor: select([...choose(suppliers, 'supplier:'), ...choose(stores, 'store:')]) });
      if (kind.kind === 'transfer') head.push({ key: 'from-warehouse', label: t('From warehouse'), required: true, editor: select(warehouses) }, { key: 'to-warehouse', label: t('To warehouse'), required: true, editor: select(warehouses) });
      if (kind.kind === 'payment') head.push({ key: 'amount', label: t('Amount'), required: true, editor: { type: 'decimal' } });
      const lines = draft.lines.flatMap((line, index) => {
        const same = draft.lines.slice(0, index).filter(other => other.side === line.side).length;
        const name = text => [line.side ? t(line.side === 'input' ? 'Input' : 'Output') : '', text, same ? same + 1 : ''].filter(Boolean).join(' ');
        return [
          { key: `lines/${index}/item`, label: name(t('Stock item')), required: true, editor: { type: 'goods' } },
          { key: `lines/${index}/quantity`, label: name(t('Quantity')), required: true, editor: { type: 'quantity' } },
          kind.kind !== 'transfer' && { key: `lines/${index}/warehouse`, label: name(t('Warehouse')), editor: select(warehouses) },
          kind.price && { key: `lines/${index}/price`, label: name(t('Unit price')), editor: { type: 'decimal' } },
        ].filter(Boolean);
      });
      return [...head, ...lines, { key: 'occurred-at', label: t('Occurred at'), editor: { type: 'date' } }, { key: 'reason', label: t('Reason'), required: kind.reason, editor: { type: 'text' } }];
    };
    return {
      placeholder: t('Search or add… choose fields or type a value'), addLabel: t('Add operation'), typeHint: t('Choose an operation type'),
      searchKey: 'item', searchHint: t('Stock item begins with'), notReady: t('Check the quantities, amount and warehouses.'),
      kinds: () => choices().kinds, columns: () => this.filterColumns(), columnValues: key => this.columnValues(key), fields,
      begin: kind => ({ kind, values: { 'occurred-at': now() }, lines: kind === 'payment' ? [] : kind === 'production' ? [{ side: 'input' }, { side: 'output' }] : [{}] }),
      lineKinds: draft => (draft.kind === 'production' ? [{ side: 'input', label: t('Add input') }, { side: 'output', label: t('Add output') }]
        : draft.kind === 'payment' ? [] : [{ side: null, label: t('Add line') }]),
      // An item may be new only where the kind creates items.
      allowsNew: (key, draft) => kindOf(draft).newItems === 'all' || (kindOf(draft).newItems === 'outputs' && draft.lines[key.split('/')[1]]?.side === 'output'),
      ready: draft => (draft.kind !== 'payment' || Number(draft.values.amount) > 0)
        && (draft.kind !== 'transfer' || draft.values['from-warehouse'] !== draft.values['to-warehouse'])
        && (draft.kind !== 'production' || ['input', 'output'].every(side => draft.lines.some(line => line.side === side)))
        && draft.lines.every(line => Number(line.quantity) >= 1),
    };
  }
  setupSmart() {
    this.smart.addEventListener('smart-draft', event => prefs.set(`addition.${this.workspaceId}`, JSON.stringify(event.detail)));
    this.smart.addEventListener('smart-filter', event => this.setFilters({ ...this.order.filters, [event.detail.key]: [{ type: 'begins', value: event.detail.value }] }));
    this.smart.addEventListener('smart-items', event => {
      this.smartItemsRequest = crypto.randomUUID();
      this.emit('operations-items', { requestId: this.smartItemsRequest, text: event.detail.text });
    });
    this.smart.addEventListener('smart-done', event => {
      if (this.pending) { this.smart.busy = false; return; }
      this.pending = { kind: 'save', id: crypto.randomUUID(), slot: 'smart', revision: 0, values: event.detail.values, commandId: crypto.randomUUID(), target: null };
      this.setStatus('Saving');
      this.emit('operations-add', { workspaceId: this.workspaceId, entryId: this.pending.id, ...event.detail, commandId: this.pending.commandId });
    });
    document.addEventListener('keydown', event => {
      if ((event.metaKey || event.ctrlKey) && !event.altKey && !event.isComposing && event.key.toLowerCase() === 'k' && this.isConnected) { event.preventDefault(); this.smart.focusInput(); }
    });
    const asked = location.hash.match(/add=([a-z-]+)/)?.[1];
    if (asked) history.replaceState(null, '', location.pathname);
    let kept = null;
    try { kept = JSON.parse(prefs.get(`addition.${this.workspaceId}`, 'null')); } catch { /* unreadable draft */ }
    if (kept?.lines) this.smart.resume(kept); else if (asked) this.smart.begin(asked);
  }
  set suppliersResponse(response) {
    if (!this.grid || !response || response.requestId !== this.suppliersRequest || !this.fresh(response)) return;
    this.grid.referenceOptionsResponse = response;
  }
  requestFailed(kind) {
    if (kind === 'save') { this.pending = null; this.smart.busy = false; this.setStatus('Failed', t('Could not save. Your edit is retained; retry when connected.')); }
    else if (kind === 'table') this.retryNotice(this.notice, () => this.refreshTable());
    else if (kind === 'review') this.setStatus('Failed', t('Could not check the pending changes. Try again.'));
    else if (kind === 'items') { this.smart.items = []; this.grid.itemOptionsResponse = { requestId: this.itemsRequest, status: 'failed', options: [] }; }
    else if (kind === 'suppliers') this.grid.referenceOptionsResponse = { requestId: this.suppliersRequest, status: 'failed', options: [] };
  }
}

customElements.define('operations-workbench', OperationsWorkbench);

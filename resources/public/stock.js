import { t } from './i18n.js';
import { formatDate } from './dates.js';
import './inventory-grid.js';
import { InventoryWorkbench, element, prefs } from './inventory-submit.js';

document.addEventListener('datastar-fetch', event => {
  const workbench = event.detail?.el?.closest('stock-workbench, operations-workbench');
  if (workbench && ['error', 'retries-failed'].includes(event.detail.type)) workbench.requestFailed(workbench.pending ? 'save' : 'read');
});

// The search box filters the one grid and pending openings live in its cells.
const now = () => new Date().toISOString();

class StockWorkbench extends InventoryWorkbench {
  connectedCallback() { queueMicrotask(() => this.connect()); }
  connect() {
    if (!this.isConnected || this._connected) return;
    this._connected = true;
    this.workspaceId = this.dataset.workspaceId;
    this.mode = prefs.get('mode', 'units');
    this.days = prefs.get('period', '30');
    this.hideEmpty = true;
    this.scope = {};
    this.order = { sort: null, direction: 'asc', filters: {} };
    this.grid = this.querySelector('.stock-grid');
    this.notice = this.querySelector('.stock-notice');
    this.findingNote = element('p', { className: 'stock-finding-note', role: 'status' });
    this.grid.after(this.findingNote);
    this.status = this.querySelector('.stock-save-status');
    this.submitButton = this.querySelector('.stock-submit');
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

    const archived = this.querySelector('.stock-show-archived');
    archived.addEventListener('click', () => { this.showArchived = !this.showArchived; archived.setAttribute('aria-pressed', String(this.showArchived)); this.refreshTable(); });
    const hide = this.querySelector('.stock-hide-empty');
    hide.addEventListener('click', () => { this.hideEmpty = !this.hideEmpty; hide.setAttribute('aria-pressed', String(this.hideEmpty)); this.refreshTable(); });
    for (const button of this.querySelectorAll('.stock-mode button')) {
      button.addEventListener('click', () => { if (this.scope.pending) return; this.mode = button.dataset.mode; prefs.set('mode', this.mode); this.showMode(); this.refreshTable(); });
    }
    this.addEventListener('inventory-sort', event => { this.order = { ...this.order, sort: event.detail.column, direction: event.detail.direction }; this.refreshTable(); });
    this.addEventListener('inventory-filter', event => { this.order = { ...this.order, filters: event.detail.filters }; this.refreshTable(); });
    // Column order and widths are kept per browser (chapter 30 moves them to the member).
    this.addEventListener('inventory-layout', event => {
      const saved = this.layout();
      const layout = { order: event.detail.order ?? saved.order ?? [], widths: { ...(saved.widths ?? {}), ...(event.detail.widths ?? {}) }, version: (saved.version ?? 0) + 1,
        lastColumn: Object.keys(event.detail.widths ?? {}).at(-1) ?? saved.lastColumn };
      prefs.set('stock-layout', JSON.stringify(layout));
      this.grid.columnLayout = layout;
      this.grid.layoutStatus = 'Saved';
    });
    // Edit opens the row in Preview, or focuses it there.
    this.addEventListener('stock-edit', event => {
      const { id } = event.detail;
      this.focusRow = id;
      this.setScope({ ...this.scope, pending: true, editing: [...new Set([...(this.scope.editing ?? []), id])] }, this.scopeLabel);
    });
    this.addEventListener('stock-describe-row', event => this.describeRow(event.detail));
    // The grid renders a window of rows; windows are cut here from the whole table.
    this.addEventListener('inventory-window', event => {
      const { requestId, queryId, start, anchorId } = event.detail;
      queueMicrotask(() => {
        this.grid.windowResponse = queryId === this.queryId
          ? { requestId, queryId, status: 'ok', model: this.windowModel(start, anchorId) }
          : { requestId, queryId, status: 'expired' };
      });
    });
    this.addEventListener('inventory-resnapshot', () => this.refreshTable());
    this.pendingButton.addEventListener('click', () => (this.scope.pending ? this.leavePreview() : this.setScope({ ...this.scope, pending: true }, this.scopeLabel)));
    this.submitButton.addEventListener('click', () => this.review());
    this.chip.addEventListener('click', () => { this.setScope({}, ''); this.search.focus(); });
    this.search.addEventListener('input', () => { clearTimeout(this.typing); this.typing = setTimeout(() => this.suggest(), 120); });
    this.search.addEventListener('keydown', event => this.searchKey(event));
    // Focus may move into the list (Retry), by Tab or by a press that outlasts the timer.
    const leave = () => setTimeout(() => { if (!this.search.matches(':focus') && !this.list.contains(document.activeElement)) this.closeSuggestions(); }, 150);
    this.search.addEventListener('blur', leave);
    this.list.addEventListener('focusout', leave);
    this.list.addEventListener('mousedown', event => event.preventDefault());
    this.addEventListener('stock-cell', event => this.edit(event.detail));
    // A new row's details wait in the filter until its first quantity saves them with the opening.
    this.addEventListener('stock-new-field', event => {
      this.grid.acknowledgeSave(event.detail);
      if (this.scope.newRowId !== event.detail.rowId) for (const key of ['variant', 'article', 'barcodes', 'unit', 'unit-cost', 'category-id', 'store-id']) delete this.scope[key];
      this.scope = { ...this.scope, newRowId: event.detail.rowId, [event.detail.path === 'item' ? 'variant' : event.detail.path]: event.detail.value.trim() };
      this.focusWarehouse = event.detail.path === 'item' && Boolean(this.scope.warehouseId && event.detail.value.trim());
      this.refreshTable();
    });
    // Unit cost and a new item's details apply to every pending opening of the row.
    this.addEventListener('stock-item-field', event => {
      const { entries, field, value } = event.detail;
      entries.forEach((entry, index) => this.edit({ ...entry, id: event.detail.id, path: `${field}:${index}`, field, value, target: index ? null : event.detail }));
    });
    this.addEventListener('item-history', event => this.toggleHistory(event.detail));
    // Move, Archive and Restore propose a change on the row's correction like a cell edit.
    this.addEventListener('stock-row-change', event => {
      if (event.detail.field === 'move-to') { this.moveItem(event.detail); return; }
      this.edit({ ...event.detail, path: event.detail.field, value: event.detail.value, target: null });
    });
    // Rows are included unless unticked, so rows added during the preview are included too.
    this.addEventListener('review-selection', event => {
      this.excluded = [...new Set(this.model.pending.map(entry => entry.rowId))].filter(id => !event.detail.selected.includes(id));
      this.grid.selectedEntries = event.detail.selected;
      this.selected = event.detail.selected; this.showSubmit();
    });
    // A selected cell's findings are read out below the table, so they need no mouse.
    this.addEventListener('grid-cell-selected', event => {
      const { cell } = event.detail;
      this.findingNote.textContent = /\bstock-finding\b/.test(cell.className ?? '') ? cell.title ?? '' : '';
    });
    this.addEventListener('stock-discard-row', event => {
      if (this.pending || !window.confirm(this.discardText(event.detail))) return;
      this.discards = event.detail.entries.map(({ entry, revision, line, shared, values }) => ({ id: entry, line, shared, values, revision: Math.max(revision, this.revisions.get(entry) ?? 0) }));
      this.discard(this.discards.shift());
    });
    for (const property of ['tableResponse', 'suggestResponse', 'reviewResponse', 'historyResponse', 'saveResponse', 'changed']) {
      if (Object.prototype.hasOwnProperty.call(this, property)) { const value = this[property]; delete this[property]; this[property] = value; }
    }
  }
  // Preview is always in units.
  showMode() {
    const mode = this.scope.pending ? 'units' : this.mode;
    for (const button of this.querySelectorAll('.stock-mode button')) {
      button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
      button.disabled = Boolean(this.scope.pending) && button.dataset.mode === 'money';
      if (button.dataset.mode === 'money') button.title = this.scope.pending ? t('Money is not shown in Preview') : t('Money');
    }
  }
  layout() {
    try { return JSON.parse(prefs.get('stock-layout', '{}')) || {}; } catch { return {}; }
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
    if (response.failed) { this.suggestFailed(); return; }
    const options = [...response.options];
    if (!response.exact && response.text.trim()) options.push({ kind: 'create', label: response.text.trim() });
    // "add" offers a new operation, built on the operations page.
    const first = response.text.trim().toLowerCase().split(/\s+/)[0];
    if (first.length >= 2 && ['add', t('Add operation').toLowerCase().split(' ')[0]].some(word => word.startsWith(first))) {
      options.unshift(...[['purchase', 'Purchase'], ['sale', 'Sale'], ['customer-return', 'Customer return'], ['supplier-return', 'Supplier return'], ['transfer', 'Transfer'],
        ['production', 'Production'], ['defect', 'Defect'], ['write-off', 'Write-off'], ['payment', 'Payment']].map(([op, name]) => ({ kind: 'operation', op, label: `${t('Add operation')}: ${t(name)}` })));
    }
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
  // A failed lookup is not "no match": offer Retry and no Create.
  suggestFailed() {
    if (!this.list) return;
    this.options = [];
    const item = element('li', { className: 'stock-option stock-option-failed' });
    this.retryNotice(item, () => { this.search.focus(); this.suggest(); });
    this.list.replaceChildren(item);
    this.list.hidden = false;
    this.search.setAttribute('aria-expanded', 'true');
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
    if (option.kind === 'operation') { location.assign(`/operations#add=${option.op}`); return; }
    // A product brings its default warehouse: the new row marks that cell and focus moves there once the variant is typed.
    if (option.kind === 'product') this.setScope({ productId: option.id, newRow: true, pending: true, newRowId: `new-row:${option.id}`, warehouseId: option.warehouseId }, option.label, true);
    else if (option.kind === 'item') this.setScope({ itemId: option.id, pending: this.scope.pending }, option.label);
    // A new product opens at the top of the preview; the table is not filtered.
    else this.setScope({ productName: option.label, newRow: true, pending: true, newRowId: 'new-row:new' }, '', true);
  }
  // Leaving the preview keeps a product filter; a product being created only lived in the preview.
  // Leaving it also ends editing: the rows opened with Edit go back to read-only.
  leavePreview() {
    const { productName, editing, newRowId, newRow, warehouseId, ...rest } = this.scope;
    this.setScope(productName ? {} : { ...rest, pending: false }, productName ? '' : this.scopeLabel);
  }
  setScope(scope, label, focus = false) {
    this.scope = Object.fromEntries(Object.entries(scope).filter(([, value]) => value !== false));
    this.scopeLabel = label;
    this.focusNew = focus;
    this.chip.hidden = !label;
    this.chip.textContent = label ? `${label} ×` : '';
    this.pendingButton.setAttribute('aria-pressed', String(Boolean(scope.pending)));
    this.showMode();
    this.refreshTable();
  }

  // Stock table
  refreshTable() {
    this.tableRequest = crypto.randomUUID();
    this.emit('stock-query', { requestId: this.tableRequest, mode: this.scope.pending ? 'units' : this.mode, hideEmpty: this.hideEmpty, showArchived: Boolean(this.showArchived),
      filter: this.scope, sort: this.order.sort, direction: this.order.direction, filters: this.order.filters });
  }
  showModel(model) {
    this.model = model;
    // History opens in read mode only.
    if (this.scope.pending && this.grid.rowDetail) this.grid.rowDetail = null;
    const layout = this.layout();
    this.queryId = crypto.randomUUID();
    this.queryKey = JSON.stringify([this.scope.pending ? 'units' : this.mode, this.hideEmpty, Boolean(this.showArchived), this.scope, this.order]);
    this.layoutFields = { savedWidths: layout.widths ?? {}, columnOrder: layout.order ?? [], layoutVersion: layout.version ?? 0, lastColumn: layout.lastColumn };
    const focus = this.focusRow ? model.rows.findIndex(row => row.id === this.focusRow) : -1;
    this.grid.model = this.windowModel(Math.max(0, focus - 66));
    this.grid.acceptedSort = this.order.sort ? { column: this.order.sort, direction: this.order.direction } : {};
    this.grid.acceptedFilters = this.order.filters;
    // A dot says there is something to preview; in preview the table itself shows it.
    this.pendingButton.classList.toggle('has-pending', Boolean(model.pending.length) && !this.scope.pending);
    // Preview starts with every row included; unticked rows wait for a later submit.
    const rows = [...new Set(model.pending.filter(entry => !entry.blocked).map(entry => entry.rowId))];
    if (!this.scope.pending) this.excluded = [];
    this.selected = this.scope.pending ? rows.filter(id => !(this.excluded ?? []).includes(id)) : null;
    this.grid.selectedEntries = this.selected ?? [];
    this.showSubmit();
    const newRow = candidate => candidate.className === 'stock-new-row' && (!this.scope.newRowId || candidate.id === this.scope.newRowId);
    const row = this.focusNew && model.rows.find(newRow);
    this.focusNew = false;
    if (row) this.grid.whenReady.then(() => this.grid.focusField(row.id, 'item'));
    // An edited row is focused; a product without stock items focuses its new stock item row.
    const edited = this.focusRow && (model.rows.find(candidate => candidate.id === this.focusRow) ?? model.rows.find(candidate => candidate.id === `new-row:${this.focusRow.replace(/^product:/, '')}`));
    this.focusRow = null;
    if (edited) this.grid.selectRow(edited.id);
    const typed = this.focusWarehouse && model.rows.find(newRow);
    this.focusWarehouse = false;
    if (typed) this.grid.whenReady.then(() => this.grid.focusField(typed.id, this.scope.warehouseId));
  }
  // Rows [start, start + 200), widened so a product's rows are never split.
  windowModel(start = 0, anchorId = null) {
    const { rows, merges = [] } = this.model, size = 200, total = rows.length;
    if (anchorId) { const index = rows.findIndex(row => row.id === anchorId); if (index >= 0) start = Math.max(0, index - 66); }
    const spans = new Map();
    for (const merge of merges) for (let y = merge.row; y < merge.row + (merge.rowspan || 1); y++) spans.set(y, [merge.row, merge.row + (merge.rowspan || 1)]);
    let from = Math.max(0, Math.min(start, total - 1));
    if (spans.has(from)) [from] = spans.get(from);
    let to = Math.min(total, from + size);
    if (to < total && spans.has(to - 1)) [, to] = spans.get(to - 1);
    return { ...this.model, ...this.layoutFields, rows: rows.slice(from, to),
      merges: merges.filter(merge => merge.row >= from && merge.row < to).map(merge => ({ ...merge, row: merge.row - from })),
      viewport: { queryId: this.queryId, queryKey: this.queryKey, start: from, end: to, totalRows: total, windowSize: size } };
  }
  // A cell commit saves a one-line opening for that item and warehouse.
  edit(detail) {
    const key = `${detail.id}|${detail.path}|${detail.values.variant ?? ''}|${detail.values.product ?? ''}`;
    const id = detail.entry || this.cellEntries.get(key) || crypto.randomUUID();
    this.cellEntries.set(key, id);
    // The line is the unit of an edit; other lines of its entry have their own.
    const slot = detail.values['line-id'] ?? `${id}-0`;
    // A correction's entry id exists before its first save; clearing a count there saves nothing.
    if (detail.entry && Math.max(detail.revision, this.revisions.get(id) ?? 0) > 0 && detail.field === 'quantity' && !detail.value.trim()) { this.discard({ id, line: slot, shared: detail.shared, values: detail.values, revision: Math.max(detail.revision, this.revisions.get(id) ?? 0), target: 'target' in detail ? detail.target : detail }); return; }
    // Build on the newest values this page holds: queued, then in flight, then its own last save, then the read.
    const inFlight = this.pending?.kind === 'save' && this.pending.slot === slot ? this.pending.values : null;
    const own = (this.revisions.get(id) ?? 0) >= detail.revision ? this.values.get(slot) : null;
    // A cleared line saved `remove`; a new value brings the line back.
    const { remove: _removed, ...base } = this.queued.get(slot)?.values ?? inFlight ?? own ?? detail.values;
    // A new row's details live in the filter; typing can outrun the table read that carries them.
    const typed = detail.id.startsWith('new-row:') ? Object.fromEntries(['variant', 'article', 'barcodes', 'unit', 'unit-cost', 'category-id', 'store-id']
      .filter(key => this.scope[key] !== undefined).map(key => [key, this.scope[key]])) : {};
    const values = { 'occurred-at': now(), ...base, ...typed, ...detail.extra, [detail.field]: detail.value };
    const request = { id, values, revision: detail.revision, target: 'target' in detail ? detail.target : detail };
    if (this.pending) { this.queued.set(slot, request); return; }
    this.save(slot, request);
  }
  // Move: an active product other than the current one, and a new variant label when the old one is taken there.
  moveItem(detail) {
    const select = element('select', { className: 'stock-move-product', required: true },
      element('option', { value: '', textContent: t('Choose a product…') }),
      ...(this.model.moveTargets ?? []).filter(product => product.id !== detail.current).map(product => element('option', { value: product.id, textContent: product.name })));
    const variant = element('input', { className: 'stock-move-variant', type: 'text', placeholder: t('Keep the variant label') });
    const dialog = element('dialog', { className: 'stock-move-dialog' },
      element('form', { method: 'dialog' },
        element('h2', { textContent: t('Move to another product') }),
        element('label', {}, element('span', { textContent: t('Product') }), select),
        element('label', {}, element('span', { textContent: t('New variant label (optional)') }), variant),
        element('div', { className: 'sheet-actions' },
          element('button', { type: 'submit', value: 'cancel', formNoValidate: true, textContent: t('Cancel') }),
          element('button', { type: 'submit', value: 'move', className: 'stock-move-confirm', textContent: t('Move') }))));
    dialog.addEventListener('close', () => {
      dialog.remove();
      if (dialog.returnValue !== 'move' || !select.value) return;
      this.edit({ ...detail, path: 'move-to', value: select.value, extra: variant.value.trim() ? { variant: variant.value.trim() } : {}, target: null });
    });
    this.append(dialog);
    dialog.showModal();
    select.focus();
  }
  // The confirmation names the row's item and everything its pending entries would lose.
  discardText({ id }) {
    const { rows, columns } = this.model;
    const at = key => columns.findIndex(column => column.key === key);
    const index = rows.findIndex(row => row.id === id);
    const product = rows.slice(0, index + 1).reverse().map(row => row.cells[at('product')]?.value).find(Boolean);
    const item = rows[index]?.cells[at('item')];
    const label = [product, item?.display ?? item?.value].filter(Boolean).join(' / ') || id;
    const changes = (rows[index]?.cells ?? []).map((cell, x) => (cell.className?.includes('stock-cell-pending') && columns[x]?.key !== 'total'
      ? `${columns[x].label}: ${cell.prefix ?? ''}${cell.display ?? cell.value}` : null)).filter(Boolean);
    return [t('Discard the pending changes of «{p0}»? This cannot be undone.', { p0: label }), ...changes].join('\n');
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
    if (tab === 'lineage' && response.rows.length) { body.replaceChildren(this.timeline(response.rows)); return; }
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

  // Every accepted operation of the item on a time axis: a lane per warehouse, a bar per balance, a mark per operation.
  timeline(rows) {
    const at = row => Date.parse(row['occurred-at']);
    const live = rows.filter(row => !row['superseded?']);
    const start = Math.min(...live.map(at));
    const end = Math.max(Date.now(), ...live.map(row => at(row) + 1));
    const percent = time => 100 * (time - start) / (end - start);
    const ticks = [0, 1 / 3, 2 / 3, 1];
    const lanes = new Map();
    for (const row of live) lanes.set(row.warehouse, [...(lanes.get(row.warehouse) ?? []), row]);
    const lines = () => ticks.map(tick => element('span', { className: 'stock-timeline-line', style: `left:${100 * tick}%` }));
    return element('div', { className: 'stock-timeline', role: 'group', ariaLabel: t('Operations over time by warehouse') },
      element('div', { className: 'stock-timeline-axis' }, ...ticks.map(tick => element('span', {
        className: tick === 1 ? 'is-last' : '', style: `left:${100 * tick}%`, textContent: new Date(start + tick * (end - start)).toLocaleString(document.documentElement.lang || undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) }))),
      ...[...lanes].sort(([a], [b]) => a.localeCompare(b)).map(([warehouse, events]) => element('div', { className: 'stock-timeline-lane' },
        element('div', { className: 'stock-timeline-label' }, element('strong', { textContent: warehouse }),
          element('span', { textContent: String(events.at(-1)['balance-after'] ?? '') })),
        element('div', { className: 'stock-timeline-plot' }, ...lines(),
          ...events.map((row, index) => {
            const until = index + 1 < events.length ? at(events[index + 1]) : end;
            const text = `${row['kind-label']} · ${formatDate(row['occurred-at'])} · ${row['quantity-change'] > 0 ? '+' : ''}${row['quantity-change']} → ${row['balance-after'] ?? '—'}`;
            return until > at(row) ? element('span', {
              className: `stock-timeline-bar${index === events.length - 1 ? ' is-current' : ''}${Number(row['balance-after']) === 0 ? ' is-empty' : ''}`,
              style: `left:${percent(at(row))}%;width:${percent(until) - percent(at(row))}%`, title: text, textContent: `${row['kind-label']} · ${row['balance-after'] ?? ''}` }) : null;
          }).filter(Boolean),
          ...events.map(row => element('span', {
            className: `stock-timeline-mark ${row['quantity-change'] < 0 ? 'is-out' : 'is-in'}`, style: `left:${percent(at(row))}%`,
            title: `${row['kind-label']} · ${formatDate(row['occurred-at'])} · ${row['quantity-change']}` }))))));
  }

  requestFailed(kind) {
    if (kind === 'save') { this.pending = null; this.setStatus('Failed', t('Could not save. Your edit is retained; retry when connected.')); }
    else if (kind === 'table') this.retryNotice(this.notice, () => this.refreshTable());
    else if (kind === 'suggest') { if (this.search.value.trim()) this.suggestFailed(); }
    else if (kind === 'review') this.setStatus('Failed', t('Could not check the pending changes. Try again.'));
    else if (kind === 'history' && this.historyNode) this.retryNotice(this.historyNode.querySelector('.stock-history-body'), () => this.loadHistory(this.historyItem));
  }
}

customElements.define('stock-workbench', StockWorkbench);

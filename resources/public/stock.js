import { t } from './i18n.js';
import { formatDate } from './dates.js';
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

class StockWorkbench extends HTMLElement {
  connectedCallback() { queueMicrotask(() => this.connect()); }
  connect() {
    if (!this.isConnected || this._connected) return;
    this._connected = true;
    this.workspaceId = this.dataset.workspaceId;
    this.mode = prefs.get('mode', 'units');
    this.days = prefs.get('period', '30');
    this.hideEmpty = true;
    this.grid = this.querySelector('.stock-grid');
    this.drafts = this.querySelector('.stock-drafts');
    this.reviewPanel = this.querySelector('.stock-review');
    this.notice = this.querySelector('.stock-notice');
    this.tableNotice = this.querySelector('.stock-table-notice');
    this.status = this.querySelector('.stock-save-status');
    this.reviewButton = this.querySelector('.stock-review-button');
    this.newRows = [];
    this.queued = new Map();
    this.revisions = new Map();
    this.values = new Map();
    this.selected = [];
    this.grid.model = JSON.parse(this.dataset.model);
    this.pendingModel = JSON.parse(this.dataset.pending);
    if (this.pendingModel) this.showDrafts(); else this.retryNotice(this.notice, () => this.refreshPending());
    if (this.mode !== 'units') this.refreshTable();
    this.showMode();

    this.querySelector('.stock-hide-empty input').addEventListener('change', event => { this.hideEmpty = event.target.checked; this.refreshTable(); });
    for (const button of this.querySelectorAll('.stock-mode button')) {
      button.addEventListener('click', () => { this.mode = button.dataset.mode; prefs.set('mode', this.mode); this.showMode(); this.refreshTable(); });
    }
    this.querySelector('.stock-add').addEventListener('click', () => this.addRow());
    this.reviewButton.addEventListener('click', () => this.review());
    this.addEventListener('stock-draft', event => this.edit(event.detail));
    this.addEventListener('stock-discard', event => this.discard(event.detail));
    this.addEventListener('review-selection', event => { this.selected = event.detail.selected; this.reviewButton.disabled = !this.selected.length; });
    this.addEventListener('item-history', event => this.toggleHistory(event.detail));
    for (const property of ['tableResponse', 'pendingResponse', 'reviewResponse', 'historyResponse', 'saveResponse', 'changed']) {
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
    this.drafts.saveStatus = status;
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

  // Stock table
  refreshTable() {
    this.tableRequest = crypto.randomUUID();
    this.emit('stock-query', { requestId: this.tableRequest, mode: this.mode, hideEmpty: this.hideEmpty });
  }
  set tableResponse(response) {
    if (!this.grid || !response || response.requestId !== this.tableRequest || !this.fresh(response)) return;
    if (!response.model) { this.retryNotice(this.tableNotice, () => this.refreshTable()); return; }
    this.tableNotice.replaceChildren();
    this.grid.model = response.model;
  }
  set changed(value) {
    if (!this.grid || !value || value === this.lastChange) return;
    this.lastChange = value;
    this.refreshTable();
    if (!this.pending) this.refreshPending();
  }

  // Opening drafts: every cell commit saves the whole entry.
  refreshPending() {
    this.pendingRequest = crypto.randomUUID();
    this.emit('stock-pending', { requestId: this.pendingRequest });
  }
  set pendingResponse(response) {
    if (!this.drafts || !response || response.requestId !== this.pendingRequest || !this.fresh(response)) return;
    if (!response.model) { this.retryNotice(this.notice, () => this.refreshPending()); return; }
    this.pendingModel = response.model;
    const saved = new Set(response.model.rows.map(row => row.id));
    this.newRows = this.newRows.filter(row => !saved.has(row.id));
    this.selected = this.selected.filter(id => saved.has(id));
    this.reviewButton.disabled = !this.selected.length;
    this.showDrafts();
    this.drafts.selectedEntries = this.selected;
    const [id, values] = this.queued.entries().next().value || [];
    if (id && !this.pending) { this.queued.delete(id); this.save(id, values); }
  }
  newRow({ id, values }) {
    const cells = this.pendingModel.draftCells.map((cell, index) => (index === 0 ? { value: '' }
      : { ...cell, value: values[cell.target.path] ?? '', target: { ...cell.target, id, rowId: id } }));
    return { id, groupId: id, className: 'stock-new-row', cells: [...cells, { value: t('New') }] };
  }
  showDrafts() {
    const count = this.newRows.length;
    this.drafts.model = { ...this.pendingModel,
      rows: [...this.newRows.map((row, index) => ({ ...this.newRow(row), rowIndex: index, groupIndex: index })),
        ...this.pendingModel.rows.map(row => ({ ...row, rowIndex: row.rowIndex + count, groupIndex: row.groupIndex + count }))] };
  }
  addRow() {
    const id = crypto.randomUUID();
    this.newRows.unshift({ id, values: {} });
    this.showDrafts();
    this.drafts.whenReady.then(() => this.drafts.focusField(id, 'product'));
  }
  rowValues(id) {
    const fresh = this.newRows.find(row => row.id === id);
    if (fresh) return { ...fresh.values };
    const row = this.pendingModel.rows.find(candidate => candidate.id === id);
    const read = Number(row?.cells.find(cell => cell.target)?.target.revision ?? 0);
    // Until the read catches up with this page's own save, the values it sent are the current ones.
    if (this.values.has(id) && (this.revisions.get(id) ?? 0) >= read) return { ...this.values.get(id) };
    return Object.fromEntries((row?.cells || []).filter(cell => cell.target).map(cell => [cell.target.path, String(cell.value ?? '')]));
  }
  revision(id) {
    // The page's own last save may be newer than the last read of pending work.
    const row = this.pendingModel.rows.find(candidate => candidate.id === id);
    return Math.max(Number(row?.cells.find(cell => cell.target)?.target.revision ?? 0), this.revisions.get(id) ?? 0);
  }
  edit(detail) {
    const values = { ...this.rowValues(detail.id), [detail.path]: detail.value };
    const fresh = this.newRows.find(row => row.id === detail.id);
    if (fresh) fresh.values = values;
    // One save at a time per page; a later edit waits for the saved revision.
    if (this.pending) { this.queued.set(detail.id, values); this.lastTarget = detail; return; }
    this.lastTarget = detail;
    this.save(detail.id, values);
  }
  save(id, values) {
    this.pending = { kind: 'save', id, revision: this.revision(id), values, commandId: crypto.randomUUID(), target: this.lastTarget };
    this.setStatus('Saving');
    this.emit('stock-save', { workspaceId: this.workspaceId, entryId: id, revision: this.pending.revision, values, commandId: this.pending.commandId });
  }
  discard(action) {
    if (this.pending || !window.confirm(t('Discard this entry? This cannot be undone.'))) return;
    this.pending = { kind: 'discard', id: action.id, commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-discard', { workspaceId: this.workspaceId, entryId: action.id, revision: Number(action.revision), commandId: this.pending.commandId });
  }
  set saveResponse(response) {
    if (!this.pending || !response || response.commandId !== this.pending.commandId) return;
    const request = this.pending; this.pending = null;
    if (response.status === 'ok') {
      if (request.kind === 'save') { this.revisions.set(request.id, request.revision + 1); this.values.set(request.id, request.values); }
      if (request.target) this.drafts.acknowledgeSave(request.target);
      if (request.kind === 'accept') { this.closeReview(); this.selected = []; this.refreshTable(); }
      this.setStatus('Saved');
    } else if (request.kind === 'accept') {
      this.setStatus('Saved');
      this.reviewPanel.querySelector('.stock-review-problems')?.replaceChildren(element('li', { role: 'alert', textContent: response.message }));
    } else {
      this.setStatus('Failed', response.message);
    }
    this.refreshPending();
  }

  // Review and accept
  review() {
    const ids = new Set(this.selected);
    const rows = this.pendingModel.rows.filter(row => ids.has(row.id));
    this.reviewRequest = crypto.randomUUID();
    this.emit('stock-review', { requestId: this.reviewRequest, selection: rows.map(row => ({ 'entry-id': row.id, revision: this.revision(row.id) })), order: rows.map(row => row.id) });
  }
  closeReview() { this.reviewPanel.hidden = true; this.reviewPanel.replaceChildren(); }
  set reviewResponse(response) {
    if (!this.reviewPanel || !response || response.requestId !== this.reviewRequest || !this.fresh(response)) return;
    const { review } = response;
    this.reviewPanel.hidden = false;
    if (!review) { this.retryNotice(this.reviewPanel, () => this.review()); return; }
    const effects = element('table', { className: 'stock-review-effects' },
      element('thead', {}, element('tr', {}, ...['Stock item', 'Warehouse', 'Quantity', 'Value'].map(label => element('th', { textContent: t(label) })))),
      element('tbody', {}, ...review.effects.map(effect => element('tr', {},
        element('td', { textContent: effect.item || effect['stock-item-id'] }),
        element('td', { textContent: effect.warehouse || effect['warehouse-id'] }),
        element('td', { className: 'stock-review-quantity', textContent: `${effect['quantity-before']} → ${effect['quantity-after']}` }),
        element('td', { textContent: `${effect.valueBefore ?? '—'} → ${effect.valueAfter ?? '—'}` })))));
    const registrations = element('ul', { className: 'stock-review-registrations' },
      ...review.registrations.map(item => element('li', { textContent: `${item.type}: ${item.label}` })));
    const problems = element('ul', { className: 'stock-review-problems' },
      ...review.problems.map(problem => element('li', { role: 'alert', textContent: problem.message })));
    const accept = element('button', { type: 'button', className: 'stock-accept', textContent: t('Accept'), disabled: !review['valid?'] });
    accept.addEventListener('click', () => {
      if (this.pending) return;
      this.pending = { kind: 'accept', commandId: crypto.randomUUID() };
      this.setStatus('Saving');
      this.emit('stock-accept', { workspaceId: this.workspaceId, selection: response.selection, order: response.order, fingerprint: review.fingerprint, commandId: this.pending.commandId });
    });
    const close = element('button', { type: 'button', textContent: t('Close') });
    close.addEventListener('click', () => this.closeReview());
    this.reviewPanel.replaceChildren(element('h2', { textContent: t('Review') }), effects,
      review.registrations.length ? element('h3', { textContent: t('Will be registered') }) : null, registrations, problems,
      element('div', { className: 'sheet-actions' }, accept, close));
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
    else if (kind === 'table') this.retryNotice(this.tableNotice, () => this.refreshTable());
    else if (kind === 'pending') this.retryNotice(this.notice, () => this.refreshPending());
    else if (kind === 'review') this.retryNotice(this.reviewPanel, () => this.review());
    else if (kind === 'history' && this.historyNode) this.retryNotice(this.historyNode.querySelector('.stock-history-body'), () => this.loadHistory(this.historyItem));
  }
}

customElements.define('stock-workbench', StockWorkbench);

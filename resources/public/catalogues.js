import { t } from './i18n.js';
import './inventory-grid.js';

document.addEventListener('datastar-fetch', event => {
  const workbench = event.detail?.el?.closest('catalogue-workbench');
  if (workbench && ['error', 'retries-failed'].includes(event.detail.type)) {
    workbench.requestFailed(workbench.pending ? 'save' : 'read');
  }
});
window.addEventListener('online', () => document.querySelector('catalogue-workbench')?.refresh());

class CatalogueWorkbench extends HTMLElement {
  connectedCallback() { queueMicrotask(() => this.connect()); }
  connect() {
    if (!this.isConnected || this._connected) return;
    this._connected = true;
    this.state = JSON.parse(this.dataset.query);
    this.grid = this.querySelector('.catalogue-grid');
    this.newGrid = this.querySelector('.catalogue-new-row');
    this.notice = this.querySelector('.catalogue-notice');
    this.status = this.querySelector('.catalogue-save-status');
    this.commands = new Map();
    this.newGrid.addEventListener('inventory-layout', event => event.stopPropagation());
    this.grid.model = JSON.parse(this.dataset.model);
    this.grid.acceptedSort = { column: this.state.sort, direction: this.state.direction };
    this.grid.acceptedFilters = this.state.filters;
    this.querySelector('.catalogue-add').addEventListener('click', () => this.addRow());
    this.querySelector('.catalogue-search').addEventListener('input', event => {
      clearTimeout(this.timer);
      this.state.query = event.target.value;
      this.timer = setTimeout(() => this.refresh(), 180);
    });
    this.querySelector('.catalogue-reset').addEventListener('click', () => {
      this.state.query = ''; this.state.filters = {};
      this.querySelector('.catalogue-search').value = ''; this.refresh();
    });
    this.addEventListener('catalogue-edit', event => this.save(event.detail));
    this.addEventListener('inventory-sort', event => {
      this.state.sort = event.detail.column; this.state.direction = event.detail.direction; this.refresh();
    });
    this.addEventListener('inventory-filter', event => { this.state.filters = event.detail.filters; this.refresh(); });
    this.addEventListener('inventory-window', event => {
      this.windowRequest = event.detail;
      this.emit('catalogue-window', event.detail);
    });
    this.addEventListener('inventory-catalogue-options', event => {
      this.choiceGrid = event.target; this.choiceRequest = event.detail;
      this.emit('catalogue-choices', { kind: event.detail.target.lookupKind, text: event.detail.text, requestId: event.detail.requestId });
    });
    this.addEventListener('inventory-resnapshot', () => this.refresh());
    this.addEventListener('inventory-conflict-resolved', () => {
      if (!this.pending) this.setStatus('Saved');
    });
    for (const property of ['catalogueResponse', 'catalogueWindow', 'catalogueChoices', 'catalogueSave', 'catalogueChanged', 'catalogueLayouts', 'catalogueLayoutStatus']) {
      if (Object.prototype.hasOwnProperty.call(this, property)) {
        const value = this[property]; delete this[property]; this[property] = value;
      }
    }
  }
  disconnectedCallback() { clearTimeout(this.timer); }
  emit(name, detail) { this.dispatchEvent(new CustomEvent(name, { bubbles: true, detail })); }
  setStatus(status, message = '') {
    this.status.textContent = t(status);
    this.grid.saveStatus = status; this.newGrid.saveStatus = status;
    this.grid.busy = status === 'Saving'; this.newGrid.busy = status === 'Saving';
    this.querySelector('.catalogue-add').disabled = status === 'Saving';
    this.notice.replaceChildren(); this.notice.textContent = message;
    this.notice.setAttribute('role', status === 'Failed' ? 'alert' : 'status');
  }
  refresh(extra = {}) {
    if (!this.isConnected || !this.grid) return;
    if (this.pending) return;
    this.requestId = crypto.randomUUID();
    this.emit('catalogue-query', { ...this.state, ...extra, requestId: this.requestId });
  }
  set catalogueResponse(response) {
    if (!this.grid || !response || response.kind !== this.state.kind || response.requestId !== this.requestId || response.requestId === this.lastResponseId) return;
    this.lastResponseId = response.requestId;
    this.grid.model = response.model;
    this.grid.acceptedSort = { column: this.state.sort, direction: this.state.direction };
    this.grid.acceptedFilters = this.state.filters;
    if (this.focusId) {
      const id = this.focusId; this.focusId = null;
      this.grid.whenReady.then(() => this.grid.focusField(id, this.state.kind === 'goods' ? 'article' : 'name'));
    }
  }
  set catalogueWindow(response) { if (this.grid && response) this.grid.windowResponse = response; }
  set catalogueChoices(response) { if (this.choiceGrid && response) this.choiceGrid.referenceOptionsResponse = response; }
  set catalogueLayouts(json) {
    if (!this.grid || !json) return;
    const layouts = JSON.parse(json);
    this.grid.columnLayout = layouts[this.grid.model.id] || { widths: {}, order: [], version: 0 };
  }
  set catalogueLayoutStatus(status) { if (this.grid && status) this.grid.layoutStatus = status; }
  set catalogueChanged(value) {
    if (!this.grid || !value || value === this.lastChange) return;
    this.lastChange = value; this.refresh();
  }
  addRow() {
    if (!this.newGrid.hidden) { this.newGrid.focusField(this.newId, 'name'); return; }
    this.newId = crypto.randomUUID();
    const columns = this.grid.model.columns.map(({ sortKey, filterKey, ...column }) => column);
    this.newGrid.model = { id: `${this.grid.model.id}-new`, columns, rowHeaders: true, editable: true, merges: [],
      rows: [{ id: this.newId, groupId: this.newId, cells: columns.map(column => column.key === 'name'
        ? { value: '', editor: { type: 'text' }, target: { type: 'catalogue-edit', id: this.newId, rowId: this.newId, kind: this.state.kind, revision: 0, path: 'name' } }
        : { value: column.key === 'unit' ? 'pcs' : '' }) }] };
    this.newGrid.hidden = false;
    this.newGrid.whenReady.then(() => this.newGrid.focusField(this.newId, 'name'));
  }
  save(detail) {
    const key = JSON.stringify([detail.id, detail.revision, detail.path, detail.value, detail.referenceName]);
    if (!this.commands.has(key)) this.commands.set(key, crypto.randomUUID());
    this.pending = { kind: this.state.kind, id: detail.id, revision: detail.revision, path: detail.path, value: detail.value, commandId: this.commands.get(key),
      ...(detail.referenceName ? { referenceName: detail.referenceName } : {}) };
    this.setStatus('Saving');
    this.emit('catalogue-save', this.pending);
  }
  set catalogueSave(response) {
    if (!this.pending || !response || response.commandId !== this.pending.commandId) return;
    const request = this.pending; this.pending = null;
    if (response.status === 'ok') {
      this.commands.delete(JSON.stringify([request.id, request.revision, request.path, request.value, request.referenceName]));
      if (request.id === this.newId) {
        this.newGrid.model = { ...this.newGrid.model, rows: [response.gridRow] };
        this.newGrid.hidden = true; this.newId = null; this.focusId = request.id;
      } else if (response.gridRow) {
        this.grid.acknowledgeReference(request, response.row[request.path]);
        this.grid.model = { ...this.grid.model, editStates: response.gridRow.cells,
          rows: this.grid.model.rows.map(row => row.id === request.id ? { ...row, ...response.gridRow } : row) };
      }
      this.setStatus('Saved');
      this.refresh({ anchorId: request.id });
    } else {
      this.setStatus('Failed', response.message);
      this.refresh();
    }
  }
  requestFailed(kind) {
    if (kind === 'save') { this.pending = null; this.setStatus('Failed', t('Could not save. Your edit is retained; retry when connected.')); }
    else if (kind === 'window') this.grid.windowResponse = { ...this.windowRequest, status: 'failed' };
    else if (kind === 'choices') this.choiceGrid.referenceOptionsResponse = { ...this.choiceRequest, status: 'failed', options: [] };
    else {
      this.notice.replaceChildren(document.createTextNode(t('Could not load records. ')));
      const retry = document.createElement('button'); retry.type = 'button'; retry.textContent = t('Retry');
      retry.addEventListener('click', () => this.refresh()); this.notice.append(retry);
    }
  }
}

customElements.define('catalogue-workbench', CatalogueWorkbench);

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
    this.drafts = [];
    this.labels = new Map();
    this.saveNew = this.querySelector('.catalogue-save-new');
    this.discardNew = this.querySelector('.catalogue-discard-new');
    this.notice = this.querySelector('.catalogue-notice');
    this.status = this.querySelector('.catalogue-save-status');
    this.commands = new Map();
    this.savedModel = JSON.parse(this.dataset.model);
    this.grid.model = this.savedModel;
    this.grid.acceptedSort = { column: this.state.sort, direction: this.state.direction };
    this.grid.acceptedFilters = this.state.filters;
    this.querySelector('.catalogue-add').addEventListener('click', () => this.addRow());
    this.saveNew.addEventListener('click', () => this.saveDrafts());
    this.discardNew.addEventListener('click', () => { this.drafts = []; this.showDrafts(); this.setStatus('Saved'); });
    this.addEventListener('catalogue-draft', event => this.editDraft(event.detail));
    this.addEventListener('catalogue-draft-discard', event => {
      this.drafts = this.drafts.filter(draft => draft.id !== event.detail.id); this.showDrafts();
    });
    this.onLeave = event => { if (this.drafts.length) event.preventDefault(); };
    window.addEventListener('beforeunload', this.onLeave);
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
  disconnectedCallback() { clearTimeout(this.timer); window.removeEventListener('beforeunload', this.onLeave); }
  emit(name, detail) { this.dispatchEvent(new CustomEvent(name, { bubbles: true, detail })); }
  setStatus(status, message = '') {
    this.status.textContent = status === 'Saved' ? '' : t(status);
    this.grid.saveStatus = status;
    this.grid.busy = status === 'Saving';
    for (const control of [this.querySelector('.catalogue-add'), this.saveNew, this.discardNew]) control.disabled = status === 'Saving';
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
    this.savedModel = response.model;
    this.grid.model = this.withDrafts(response.model);
    this.grid.acceptedSort = { column: this.state.sort, direction: this.state.direction };
    this.grid.acceptedFilters = this.state.filters;
    if (this.focusId) {
      const id = this.focusId; this.focusId = null;
      this.grid.whenReady.then(() => this.grid.focusField(id, this.state.kind === 'goods' ? 'article' : 'name'));
    }
  }
  set catalogueWindow(response) { if (this.grid && response) this.grid.windowResponse = response; }
  set catalogueChoices(response) {
    if (!this.choiceGrid || !response) return;
    for (const option of response.options || []) this.labels.set(option.value, option.label);
    this.choiceGrid.referenceOptionsResponse = response;
  }
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
  // New rows are drafts at the top of the grid: edits stay in the browser until Save registers each one with
  // all its fields, or Discard drops them. Existing rows still save cell by cell.
  addRow() {
    const id = crypto.randomUUID();
    this.drafts.unshift({ id, values: {} });
    this.showDrafts();
    this.grid.whenReady.then(() => this.grid.focusField(id, 'name'));
  }
  draftRow({ id, values }) {
    const cells = this.savedModel.draftCells.map(cell => {
      const path = cell.target.path, value = values[path] ?? '';
      const options = cell.editor?.type === 'select' ? cell.editor.options : null;
      const display = !value ? '' : options ? (options.find(option => option.value === value)?.label ?? '') : cell.editor?.type === 'catalogue-reference' ? (this.labels.get(value) ?? '') : value;
      return { ...cell, value, display,
        editor: cell.editor?.type === 'catalogue-reference' && value ? { ...cell.editor, options: [{ value, label: display }] } : cell.editor,
        target: { ...cell.target, type: 'catalogue-draft', id, rowId: id } };
    });
    return { id, groupId: id, label: values.name || '', className: 'catalogue-draft-row',
      cells: [...cells, { value: '', display: t('New'), actions: [{ type: 'catalogue-draft-discard', id, label: t('Discard row'), icon: '×' }] }] };
  }
  withDrafts(model) {
    const count = this.drafts.length, shift = index => (Number.isInteger(index) ? index + count : index);
    return { ...model, rows: [...this.drafts.map((draft, index) => ({ ...this.draftRow(draft), rowIndex: index, groupIndex: index })),
      ...model.rows.map(row => ({ ...row, rowIndex: shift(row.rowIndex), groupIndex: shift(row.groupIndex) }))] };
  }
  showDrafts() {
    this.saveNew.hidden = this.discardNew.hidden = !this.drafts.length;
    this.grid.model = this.withDrafts(this.savedModel);
  }
  editDraft(detail) {
    const draft = this.drafts.find(candidate => candidate.id === detail.id);
    this.grid.acknowledgeSave(detail);
    if (!draft) return;
    if (detail.referenceName) { this.setStatus('Failed', t('Choose an active record of this workspace.')); this.showDrafts(); return; }
    draft.values = { ...draft.values, [detail.path]: detail.value }; draft.commandId = null;
    // After the grid's own Tab/Enter move, so the rebuilt sheet keeps the new selection.
    setTimeout(() => this.showDrafts());
  }
  saveDrafts() {
    if (this.pending || !this.drafts.length) return;
    this.queue = this.drafts.map(draft => draft.id).reverse(); this.failures = [];
    this.saveNextDraft();
  }
  saveNextDraft() {
    const id = this.queue.shift(), draft = this.drafts.find(candidate => candidate.id === id);
    if (!draft) {
      this.saveNew.hidden = this.discardNew.hidden = !this.drafts.length;
      if (this.failures.length) this.setStatus('Failed', this.failures.join(' '));
      else this.setStatus('Saved');
      this.refresh(); return;
    }
    draft.commandId ||= crypto.randomUUID();
    this.pending = { kind: this.state.kind, id: draft.id, revision: 0, path: 'name', value: draft.values.name || '', values: { ...draft.values }, commandId: draft.commandId };
    this.setStatus('Saving');
    this.emit('catalogue-save', this.pending);
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
    const draft = this.drafts.find(candidate => candidate.id === request.id);
    if (draft) {
      // Each row registers on its own; a rejected row stays a draft with its values, the others save.
      if (response.status === 'ok') this.drafts = this.drafts.filter(candidate => candidate !== draft);
      else this.failures.push(`${draft.values.name || t('New row')}: ${response.message}`);
      this.saveNextDraft(); return;
    }
    if (response.status === 'ok') {
      this.commands.delete(JSON.stringify([request.id, request.revision, request.path, request.value, request.referenceName]));
      this.grid.acknowledgeSave(request);
      if (response.gridRow) {
        const current = this.grid.model.rows.find(row => row.id === request.id);
        const newer = current?.cells.some(cell => Number(cell.target?.revision) > Number(response.row.revision));
        if (!newer) {
          this.savedModel = { ...this.savedModel, rows: this.savedModel.rows.map(row => row.id === request.id ? { ...row, ...response.gridRow } : row) };
          this.grid.model = { ...this.withDrafts(this.savedModel), editStates: response.gridRow.cells };
        }
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

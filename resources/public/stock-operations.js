import { t } from './i18n.js';
import './inventory-grid.js';
import { formatDate } from './dates.js';

const text = value => value == null ? '' : String(value);
function element(tag, className, content) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (content != null) node.textContent = text(content);
  return node;
}

// Expansion changes presentation only; accepted values, operation groups and details are server supplied.
class StockOperations extends HTMLElement {
  constructor() {
    super();
    this._entries = [];
    this._model = { columns: [], rows: [], merges: [], editable: false };
    this._acceptedSort = {};
    this._acceptedFilters = {};
    this._saveStatus = 'Saved';
    this.expanded = new Set();
    this._detailCache = new Map(); this._detailRequests = new Map(); this._detailSequence = 0;
  }
  get entries() { return this._entries; }
  set entries(value) { this._entries = Array.isArray(value) ? value : []; if (this.grid) this.renderRows(); }
  get model() { return this._model; }
  set model(value) {
    if (!value) return;
    if (value.viewport?.queryId && value.viewport.queryId === this._model.viewport?.queryId && value.viewport.start !== this._model.viewport.start) return;
    if (value.viewport?.queryId !== this._model.viewport?.queryId) { this._detailCache.clear(); this._detailRequests.clear(); }
    if (value.layoutScope !== this._model.layoutScope) this.expanded.clear();
    this._model = value; this.renderRows();
  }
  get windowResponse() { return this._windowResponse; }
  set windowResponse(value) {
    this._windowResponse = value;
    if (!this.grid) return;
    if (value?.status === 'ok' && value.model) {
      if (value.requestId !== this.grid._windowPending?.requestId) return;
      this._model = value.model;
      this.grid.windowResponse = { ...value, model: this._expandedModel(value.model) };
      this._requestExpandedDetails();
    } else this.grid.windowResponse = value;
  }
  get operationDetailResponse() { return this._operationDetailResponse; }
  set operationDetailResponse(value) {
    if (!value || value.queryId !== this._model.viewport?.queryId || value.requestId !== this._detailRequests.get(value.operationId)) return;
    this._operationDetailResponse = value; this._detailRequests.delete(value.operationId);
    if (value.status === 'expired') {
      this.dispatchEvent(new CustomEvent('inventory-resnapshot', { bubbles: true, detail: { queryId: value.queryId } })); return;
    }
    this._detailCache.set(value.operationId, value.status === 'failed' ? { ...value, detail: { failed: true } } : value); this.renderRows();
  }
  get acceptedSort() { return this._acceptedSort; }
  set acceptedSort(value) { this._acceptedSort = value || {}; if (this.grid) this.grid.acceptedSort = this._acceptedSort; }
  get acceptedFilters() { return this._acceptedFilters; }
  set acceptedFilters(value) { this._acceptedFilters = value || {}; if (this.grid) this.grid.acceptedFilters = this._acceptedFilters; }
  get saveStatus() { return this._saveStatus; }
  set saveStatus(value) { this._saveStatus = value || 'Saved'; if (this.grid) this.grid.saveStatus = this._saveStatus; }
  openColumnFilter(column) { this.grid?.openColumnFilter(column); }
  connectedCallback() {
    for (const property of ['entries', 'model', 'acceptedSort', 'acceptedFilters', 'saveStatus', 'windowResponse', 'operationDetailResponse']) {
      if (!Object.prototype.hasOwnProperty.call(this, property)) continue;
      const value = this[property]; delete this[property]; this[property] = value;
    }
    if (this.grid) return;
    this.classList.add('stock-operations');
    this.grid = element('inventory-grid', 'operations-grid');
    this.grid.setAttribute('aria-label', t('Operations history spreadsheet'));
    this.grid.windowModelRenderer = model => {
      this._model = model.operationBaseModel || model;
      return this._expandedModel(this._model);
    };
    this.grid.cellRenderer = cell => {
      if (!cell.operationDetail) return;
      const entry = this._entryFor(cell.operationId);
      const panel = this.renderDetails({ ...entry, id: cell.operationId, detail: cell.operationDetail });
      panel.setAttribute('role', 'region'); panel.setAttribute('aria-label', t("{p0} details", {p0: entry.operation || t('Operation')}));
      return panel;
    };
    for (const [source, target] of [['inventory-sort', 'operations-sort'], ['inventory-filter', 'operations-filter']]) {
      this.grid.addEventListener(source, event => { event.stopPropagation(); this.dispatchEvent(new CustomEvent(target, { bubbles: true, detail: event.detail })); });
    }
    this.grid.addEventListener('operation-details', event => {
      event.stopPropagation();
      if (this.expanded.has(event.detail.id)) this.expanded.delete(event.detail.id); else this.expanded.add(event.detail.id);
      if (!this.expanded.has(event.detail.id)) this.grid._detailHeights.delete(`${event.detail.id}/details`);
      this.renderRows();
    });
    this.empty = element('div', 'operations-empty', t('No operations yet.')); this.empty.setAttribute('role', 'status');
    this.replaceChildren(this.grid, this.empty);
    this.grid.acceptedSort = this._acceptedSort; this.grid.acceptedFilters = this._acceptedFilters; this.grid.saveStatus = this._saveStatus;
    this.renderRows();
  }
  renderRows() {
    if (!this.grid) return;
    this.grid.model = this._expandedModel(this._model);
    this.empty.hidden = Boolean(this._model.rows?.length || this._model.viewport?.totalRows);
    this._requestExpandedDetails();
  }
  _entryFor(id) {
    return this._detailCache.get(id)?.entry || this._entries.find(item => item.id === id) || {
      id, operation: this._model.rows.flatMap(row => row.cells).find(cell => cell.action?.type === 'operation-details' && cell.action.id === id)?.value || t('Operation'),
    };
  }
  _requestExpandedDetails() {
    if (!this._model.viewport) return;
    for (const id of this.expanded) {
      if (this._detailCache.has(id) || this._detailRequests.has(id) || !this._model.rows.some(row => row.groupId === id)) continue;
      const requestId = `operation:${++this._detailSequence}`;
      this._detailRequests.set(id, requestId);
      this.dispatchEvent(new CustomEvent('operation-detail-request', { bubbles: true,
        detail: { operationId: id, queryId: this._model.viewport.queryId, requestId } }));
    }
  }
  _expandedModel(model) {
    const rows = [], merges = [], shifts = new Map(), columns = model.columns || [];
    for (const [index, source] of (model.rows || []).entries()) {
      shifts.set(index, rows.length);
      const row = { ...source, cells: source.cells.map(cell => {
        if (cell.action?.type !== 'operation-details') return cell;
        const expanded = this.expanded.has(cell.action.id);
        const entry = this._entryFor(cell.action.id);
        return { ...cell, action: { ...cell.action, icon: expanded ? '⌄' : '›', label: t("{p0} {p1} details", {p0: expanded ? t('Hide') : t('Show'), p1: (entry?.operation || t('operation')).toLocaleLowerCase()}) } };
      }) };
      rows.push(row);
      const groupId = source.groupId;
      if (this.expanded.has(groupId) && model.rows[index + 1]?.groupId !== groupId) {
        const detail = this._detailCache.get(groupId)?.detail || model.details?.[groupId];
        if (detail || model.viewport) {
          merges.push({ row: rows.length, col: 0, rowspan: 1, colspan: columns.length });
          rows.push({ id: `${groupId}/details`, groupId, detailPosition: source.rowIndex + 1,
            cells: columns.map((_, col) => col ? {} : { value: '', operationId: groupId, operationDetail: detail || { loading: true }, className: 'inventory-operation-details-cell' }) });
        }
      }
    }
    for (const merge of model.merges || []) merges.push({ ...merge, row: shifts.get(merge.row) ?? merge.row });
    const expanded = { ...model, rows, merges };
    Object.defineProperty(expanded, 'operationBaseModel', { value: model });
    return expanded;
  }

  renderMetadata(fields) {
    const metadata = element('dl', 'operation-details-meta');
    for (const field of fields || []) {
      const pair = element('div', 'operation-meta-field');
      pair.append(element('dt', '', field.label), element('dd', '', field['date?'] ? formatDate(field.value) : field.value));
      metadata.append(pair);
    }
    return metadata;
  }

  renderDetails(model) {
    const detail = model.detail || {};
    const panel = element('div', 'operation-details');
    if (detail.loading) { panel.append(element('span', '', t('Loading operation…'))); return panel; }
    if (detail.failed) {
      panel.append(element('span', '', t('Could not load operation.')));
      const retry = element('button', 'operation-record-action', t('Retry')); retry.type = 'button'; retry.setAttribute('aria-label', t('Retry loading operation'));
      retry.addEventListener('click', () => { this._detailCache.delete(model.id); this.renderRows(); }); panel.append(retry); return panel;
    }
    const header = element('div', 'operation-details-heading');
    if (detail.cancelled) header.append(element('span', 'operations-cancelled-label', t('Cancelled')));
    else {
      const actions = [['revision', t('Correct')], ['cancellation', t('Cancel')]];
      for (const [kind, label] of actions) {
        const button = element('button', 'operation-record-action', label);
        button.type = 'button';
        button.addEventListener('click', () => this.dispatchEvent(new CustomEvent('operation-revise', {
          bubbles: true, detail: { targetId: model.id, kind },
        })));
        header.append(button);
      }
    }
    panel.append(this.renderMetadata(detail['summary-metadata']), header);
    return panel;
  }

}
if (!customElements.get('stock-operations')) customElements.define('stock-operations', StockOperations);

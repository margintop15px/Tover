import './vendor/revogrid/revo-grid.esm.js';
import { h } from './vendor/revogrid/index-CtimkLsB.js';
import { renderOperationDetails } from './operation-details.js?v=lineage-12';
import { renderLineage } from './operation-lineage.js?v=lineage-12';

await customElements.whenDefined('revo-grid');

const text = value => value == null ? '' : String(value);
const timestamp = entry => {
  const value = Date.parse(entry.effectiveAt || entry.recordedAt || '');
  return Number.isNaN(value) ? null : value;
};
const operationName = entry => ({
  opening: 'Opening stock', 'opening-declaration': 'Opening stock',
  adjustment: 'Data edit', 'manual-edit': 'Data edit', 'data-edit': 'Data edit', data_edit: 'Data edit',
  transfer: 'Transfer', production: 'Production',
}[entry.kind || entry.type] || text(entry.type).replace(/[-_]/g, ' ').replace(/^./, letter => letter.toUpperCase()));

function element(tag, className, content) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (content != null) node.textContent = text(content);
  return node;
}

class StockOperations extends HTMLElement {
  constructor() {
    super();
    this._entries = [];
    this._stockRows = [];
    this._examplesEnabled = false;
    this._examplesDisabled = false;
    this.query = '';
    this.viewMode = 'table';
    this.filteredEntries = [];
    this.expanded = new Set();
    this.detailHeights = new Map();
    this.observedDetails = new Set();
    this.frame = null;
    this.activeGrid = null;
    this.activateGrid = event => { this.activeGrid = event.target.closest?.('revo-grid') || null; };
    this.detailObserver = new ResizeObserver(entries => {
      for (const { target } of entries) {
        if (!target.isConnected) {
          this.detailObserver.unobserve(target);
          this.observedDetails.delete(target);
          continue;
        }
        const height = Math.ceil(target.getBoundingClientRect().height);
        if (height && this.detailHeights.get(target.dataset.operationId) !== height) {
          this.detailHeights.set(target.dataset.operationId, height);
          this.scheduleLayout();
        }
      }
    });
    this.scheduleResize = () => this.scheduleLayout();
    this.sizeObserver = new ResizeObserver(this.scheduleResize);
  }

  get entries() { return this._entries; }
  set entries(value) {
    const entries = Array.isArray(value) ? value : [];
    if (JSON.stringify(this._entries) === JSON.stringify(entries)) return;
    this._entries = entries;
    if (this.isConnected && this.grid) this.renderRows();
  }

  get stockRows() { return this._stockRows; }
  set stockRows(value) {
    const rows = Array.isArray(value) ? value : [];
    if (JSON.stringify(this._stockRows) === JSON.stringify(rows)) return;
    this._stockRows = rows;
    if (this.isConnected && this.lineage && this.viewMode === 'lineage') this.renderLineageView();
  }

  get examplesEnabled() { return this._examplesEnabled; }
  set examplesEnabled(value) {
    this._examplesEnabled = !!value;
    if (this.examplesControl) this.examplesControl.checked = this._examplesEnabled;
  }

  get examplesDisabled() { return this._examplesDisabled; }
  set examplesDisabled(value) {
    this._examplesDisabled = !!value;
    if (this.examplesControl) {
      this.examplesControl.disabled = this._examplesDisabled;
      this.examplesControl.title = this._examplesDisabled ? 'Submit or revert changes before switching data' : '';
    }
  }

  connectedCallback() {
    for (const property of ['entries', 'stockRows', 'examplesEnabled', 'examplesDisabled']) {
      if (!Object.prototype.hasOwnProperty.call(this, property)) continue;
      const value = this[property];
      delete this[property];
      this[property] = value;
    }
    window.addEventListener('resize', this.scheduleResize);
    for (const type of ['pointerdown', 'focusin']) document.addEventListener(type, this.activateGrid);
    if (!this.grid) this.render();
    else {
      this.sizeObserver.observe(this);
      this.scheduleLayout();
    }
  }

  disconnectedCallback() {
    window.removeEventListener('resize', this.scheduleResize);
    for (const type of ['pointerdown', 'focusin']) document.removeEventListener(type, this.activateGrid);
    this.detailObserver.disconnect();
    this.sizeObserver.disconnect();
    this.observedDetails.clear();
    if (this.frame !== null) cancelAnimationFrame(this.frame);
    this.frame = null;
  }

  render() {
    this.detailObserver.disconnect();
    this.sizeObserver.disconnect();
    this.observedDetails.clear();
    this.classList.add('stock-operations');
    const nodes = [];
    const toolbar = element('div', 'operations-toolbar');
    const views = element('div', 'operations-view-switch');
    views.setAttribute('role', 'group');
    views.setAttribute('aria-label', 'Operations view');
    this.viewButtons = ['table', 'lineage'].map(mode => {
      const button = element('button', '', mode === 'table' ? 'Table' : 'Lineage');
      button.type = 'button';
      button.setAttribute('aria-pressed', String(this.viewMode === mode));
      button.addEventListener('click', () => {
        this.viewMode = mode;
        this.renderCurrentView();
      });
      views.append(button);
      return button;
    });
    const search = element('label', 'operations-search');
    const symbol = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    symbol.setAttribute('viewBox', '0 0 24 24');
    symbol.setAttribute('aria-hidden', 'true');
    const circle = document.createElementNS(symbol.namespaceURI, 'circle');
    circle.setAttribute('cx', '10.5'); circle.setAttribute('cy', '10.5'); circle.setAttribute('r', '6.5');
    const line = document.createElementNS(symbol.namespaceURI, 'path');
    line.setAttribute('d', 'm16 16 4 4');
    symbol.append(circle, line);
    const input = element('input');
    this.searchInput = input;
    input.type = 'search';
    input.placeholder = 'Search operations';
    input.setAttribute('aria-label', 'Search operations');
    input.value = this.query;
    input.addEventListener('input', () => {
      this.query = input.value;
      this.renderRows();
    });
    search.append(symbol, input);
    this.count = element('span', 'operations-count');
    this.count.setAttribute('role', 'status');
    toolbar.append(views, search);
    if (this.hasAttribute('data-examples')) {
      const examples = element('label', 'operations-example-control');
      this.examplesControl = element('input');
      this.examplesControl.type = 'checkbox';
      this.examplesControl.checked = this.examplesEnabled;
      this.examplesDisabled = this._examplesDisabled;
      this.examplesControl.addEventListener('change', () => {
        this.examplesEnabled = this.examplesControl.checked;
        this.dispatchEvent(new CustomEvent('examples-toggle', { bubbles: true, detail: { enabled: this.examplesEnabled } }));
      });
      examples.append(this.examplesControl, element('span', '', 'Demo data'));
      toolbar.append(examples);
    }
    toolbar.append(this.count);
    nodes.push(toolbar);
    this.grid = element('revo-grid', 'operations-grid');
    this.grid.setAttribute('aria-label', 'Operations history spreadsheet');
    const columns = [
      { prop: 'time', name: 'Date', size: 155, cellCompare: (_prop, a, b) => a._timestamp === b._timestamp ? 0 : (a._timestamp ?? -Infinity) - (b._timestamp ?? -Infinity) },
      { prop: 'operation', name: 'Operation', size: 185, cellTemplate: (h, { model }) => this.operationCell(h, model) },
      { prop: 'summary', name: 'Summary', size: 280 },
      { prop: 'context', name: 'Locations / party', size: 205 },
      { prop: 'items', name: 'Items', size: 75, cellCompare: (_prop, a, b) => a._itemCount - b._itemCount },
      { prop: 'reason', name: 'Reason / evidence', size: 230 },
    ].map(column => ({ ...column, sortable: true, readonly: true, cellProperties: ({ model }) => ({
      style: { height: '32px' }, title: text(model[column.prop]),
    }) }));
    Object.assign(this.grid, {
      columns, rowSize: 32, rowHeaders: { size: 44, cellProperties: () => ({ style: { height: '32px' } }) },
      readonly: true, resize: true, range: true, filter: true, theme: 'default',
    });
    // Initial sorting config is not applied when this pinned build creates its plugins.
    this.grid.addEventListener('aftergridinit', () => this.grid.updateColumnSorting(columns[0], 'desc', false), { once: true });
    this.grid.addEventListener('beforerowrender', event => this.renderDetail(event));
    this.grid.addEventListener('pointerdown', event => {
      const focused = document.activeElement;
      if (event.target.closest?.('.rgCell') && focused && focused !== document.body && !this.grid.contains(focused)) focused.blur();
    });
    this.grid.addEventListener('beforekeydown', event => {
      const original = event.detail.original;
      const targetGrid = original.target.closest?.('revo-grid');
      if (this.viewMode !== 'table' || (original.target === document.body ? this.activeGrid !== this.grid : targetGrid !== this.grid) ||
          original.target.closest?.('.operations-detail,.operations-toggle')) event.preventDefault();
    });
    for (const name of ['aftergridrender', 'contentsizechanged', 'afteranysource', 'aftertrimmed', 'aftersortingapply']) {
      this.grid.addEventListener(name, () => this.scheduleLayout());
    }
    this.grid.addEventListener('afterrender', () => {
      for (const node of this.observedDetails) {
        if (node.isConnected) continue;
        this.detailObserver.unobserve(node);
        this.observedDetails.delete(node);
      }
    });
    this.empty = element('div', 'operations-empty');
    this.empty.setAttribute('role', 'status');
    this.lineage = element('div', 'operations-lineage');
    this.lineage.setAttribute('role', 'region');
    this.lineage.setAttribute('aria-label', 'Stock lineage timeline');
    this.lineage.tabIndex = 0;
    this.lineageSignature = null;
    nodes.push(this.grid, this.lineage, this.empty);
    this.replaceChildren(...nodes);
    this.sizeObserver.observe(this);
    this.renderRows();
  }

  renderRows() {
    const query = this.query.trim().toLocaleLowerCase();
    const entries = this._entries.filter(entry => !query || [
      entry.name, entry.article, entry.location, operationName(entry), entry.reason, entry.evidence,
      entry.summary, entry.context, entry.route,
      ...(entry.changes || []).flatMap(change => [change.field, change.before, change.after]),
      ...(entry.effects || []).flatMap(effect => [effect.name, effect.article, effect.quantity, effect.unit, effect.location, effect.role]),
    ].some(value => text(value).toLocaleLowerCase().includes(query)));
    this.filteredEntries = entries;
    this.grid.source = entries.map((entry, index) => {
      const time = timestamp(entry);
      const effects = entry.effects || [];
      const count = entry.itemCount ?? (effects.length ? new Set(effects.map(effect => effect.article || effect.name || effect.rowId)).size : 1);
      const locations = [...new Set(effects.map(effect => effect.location).filter(Boolean))];
      const summary = entry.summary || entry.name || 'Stock change';
      return {
        id: text(entry.id) || `operation-${index}`, _entry: entry, _timestamp: time, _itemCount: count,
        time: time == null ? '—' : new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(time)),
        operation: operationName(entry), summary,
        context: entry.context || entry.route || locations.join(' → ') || entry.location || '—',
        items: `${count} ${count === 1 ? 'item' : 'items'}`,
        reason: entry.reason || entry.evidence || '—',
      };
    });
    this.empty.textContent = query ? 'No matching operations.' : 'No operations yet.';
    this.renderCurrentView();
  }

  renderCurrentView() {
    const lineage = this.viewMode === 'lineage';
    const searchLabel = lineage ? 'Find goods or article' : 'Search operations';
    this.searchInput.placeholder = searchLabel;
    this.searchInput.setAttribute('aria-label', searchLabel);
    const hasEntries = !!(lineage ? this._entries.length : this.filteredEntries.length);
    this.viewButtons.forEach((button, index) => button.setAttribute('aria-pressed', String(lineage === (index === 1))));
    this.empty.hidden = hasEntries;
    this.grid.hidden = lineage || !hasEntries;
    this.grid.style.display = this.grid.hidden ? 'none' : '';
    this.lineage.hidden = !lineage || !hasEntries;
    if (lineage && hasEntries) this.renderLineageView();
    this.updateCount(this.filteredEntries.length);
    this.scheduleLayout();
  }

  renderLineageView() {
    const signature = JSON.stringify([this._entries, this.stockRows, this.query]);
    if (signature !== this.lineageSignature) {
      this.lineageSignature = signature;
      this.lineage.replaceChildren(renderLineage(this._entries, { rows: this.stockRows, query: this.query }));
    }
    this.scheduleLayout();
  }

  operationCell(h, model) {
    const expanded = this.expanded.has(model.id);
    const entry = model._entry;
    return h('span', { class: 'operations-operation' },
      h('button', {
        type: 'button', class: 'operations-toggle',
        'aria-label': `${expanded ? 'Hide' : 'Show'} ${model.operation.toLocaleLowerCase()} details`,
        'aria-expanded': String(expanded), title: `${expanded ? 'Hide' : 'Show'} operation details`,
        onMouseDown: event => { event.preventDefault(); event.stopPropagation(); },
        onDblClick: event => event.stopPropagation(),
        onClick: event => {
          event.stopPropagation();
          if (expanded) this.expanded.delete(model.id);
          else this.expanded.add(model.id);
          this.syncDefinitions();
          this.grid.refresh('rgRow');
        },
      }, h('svg', { viewBox: '0 0 16 16', 'aria-hidden': 'true' },
        h('path', { d: expanded ? 'm4 6 4 4 4-4' : 'm6 4 4 4-4 4' }))),
      h('span', null, model.operation),
      entry.example ? h('span', { class: 'operations-example', title: 'Demo history. Does not affect saved stock.' }, 'Demo') : null,
      entry.local ? h('span', { class: 'operations-local', title: 'Current tab only. Reloading restores saved data.' }, 'Local') : null);
  }

  renderDetail(event) {
    const { model, node, colType, rowType } = event.detail;
    if (rowType !== 'rgRow' || colType !== 'rgCol' || !this.expanded.has(model.id)) return;
    node.$children$.push(h('div', {
      key: `operation-${model.id}`, class: 'operations-detail',
      'data-operation-id': model.id, role: 'region', 'aria-label': `${model.operation} details`,
      onMouseDown: event => event.stopPropagation(), onDblClick: event => event.stopPropagation(),
      ref: target => {
        if (!target) return;
        const signature = JSON.stringify([model._entry, this._entries]);
        if (target._detailSignature !== signature) {
          target._detailSignature = signature;
          target.replaceChildren(renderOperationDetails(model._entry, { entries: this._entries }));
        }
        if (!this.observedDetails.has(target)) {
          this.observedDetails.add(target);
          this.detailObserver.observe(target);
        }
      },
    }));
  }

  syncDefinitions() {
    const definitions = this.grid.source.flatMap((row, index) => this.expanded.has(row.id)
      ? [{ type: 'rgRow', index, size: 32 + (this.detailHeights.get(row.id) ?? 180) }]
      : []);
    if (JSON.stringify(this.grid.rowDefinitions) !== JSON.stringify(definitions)) this.grid.rowDefinitions = definitions;
  }

  updateCount(count) {
    if (!this.count) return;
    if (this.viewMode === 'lineage') {
      this.count.textContent = '';
      return;
    }
    this.count.textContent = `${count}${count !== this._entries.length ? ` of ${this._entries.length}` : ''} ${this._entries.length === 1 ? 'operation' : 'operations'}`;
  }

  scheduleLayout() {
    if (this.frame !== null || !this.isConnected) return;
    this.frame = requestAnimationFrame(async () => {
      this.frame = null;
      if (!this.grid?.isConnected) return;
      if (this.viewMode === 'lineage') {
        const maximum = `${Math.max(40, Math.floor(window.innerHeight - this.lineage.getBoundingClientRect().top - 8))}px`;
        if (this.lineage.style.maxHeight !== maximum) this.lineage.style.maxHeight = maximum;
        this.updateCount(this.filteredEntries.length);
        return;
      }
      this.syncDefinitions();
      const { y } = await this.grid.getContentSize();
      if (!this.grid?.isConnected || this.viewMode !== 'table') return;
      const style = getComputedStyle(this.grid);
      const header = parseFloat(style.getPropertyValue('--revo-grid-header-height')) || 34;
      const scrollbar = this.grid.querySelector('revogr-scroll-virtual.horizontal')?.offsetHeight || 0;
      const borders = (parseFloat(style.borderTopWidth) || 0) + (parseFloat(style.borderBottomWidth) || 0);
      const maximum = `${Math.max(header + borders, Math.floor(window.innerHeight - this.grid.getBoundingClientRect().top - 8))}px`;
      if (this.grid.style.maxHeight !== maximum) this.grid.style.maxHeight = maximum;
      const height = `${Math.ceil(y + header + scrollbar + borders)}px`;
      if (this.grid.style.height !== height) this.grid.style.height = height;
      this.updateCount((await this.grid.getVisibleSource()).length);
    });
  }
}

if (!customElements.get('stock-operations')) customElements.define('stock-operations', StockOperations);

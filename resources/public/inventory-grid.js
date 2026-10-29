import { t } from './i18n.js';
import { formatDate, toLocalDateTime, inputDateValue } from './dates.js';
import { installServerFilters } from './grid-filters.js';

// Coordinates belong to CE; persistent identities, merges and values come from the server.
const assetRoot = '/vendor/jspreadsheet-ce/';
const columnWidths = new Map();
let runtime;
function loadStyle(name) {
  if (document.querySelector(`link[data-inventory-asset="${name}"]`)) return;
  const link = document.createElement('link');
  link.rel = 'stylesheet'; link.href = `${assetRoot}${name}`; link.dataset.inventoryAsset = name;
  document.head.append(link);
}
function loadScript(name) {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `${assetRoot}${name}`; script.onload = resolve;
    script.onerror = () => reject(new Error(t("Could not load inventory spreadsheet: {p0}", {p0: name})));
    document.head.append(script);
  });
}
function loadRuntime() {
  if (!runtime) runtime = (async () => {
    loadStyle('jsuites.css'); loadStyle('jspreadsheet.css');
    await loadScript('jsuites.js'); await loadScript('formula.js'); await loadScript('jspreadsheet.js');
    return window.jspreadsheet;
  })();
  return runtime;
}
const pathKey = target => `${target?.id || ''}:${target?.path || ''}`;
const sameValue = (left, right) => String(left ?? '') === String(right ?? '');
const versionKey = target => JSON.stringify([target?.revision, target?.ledgerRevision, target?.draftId, target?.draftRevision]);
const utilityColumn = key => ['select', 'actions'].includes(key);
function projectColumnLayout(model, layout) {
  if (!layout) return model;
  const keys = model.columns.map(column => column.key);
  const content = [...new Set([...(layout.order || []), ...keys])].filter(key => keys.includes(key) && !utilityColumn(key));
  const order = [...keys.filter(key => key === 'select'), ...content, ...keys.filter(key => key === 'actions')];
  const indexes = order.map(key => keys.indexOf(key)), positions = new Map(indexes.map((index, position) => [index, position]));
  const rows = model.rows.map(row => ({ ...row, cells: indexes.map(index => row.cells[index]) }));
  const merges = (model.merges || []).map(merge => {
    if (merge.colspan === keys.length) {
      rows[merge.row].cells[0] = model.rows[merge.row].cells[merge.col];
      return { ...merge, col: 0 };
    }
    return { ...merge, col: positions.get(merge.col) };
  });
  return { ...model, columns: indexes.map(index => model.columns[index]), rows, merges,
    savedWidths: layout.widths || {}, columnOrder: content, layoutVersion: layout.version || 0, lastColumn: layout.lastColumn };
}
function gridIcon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('inventory-grid-icon');
  for (const [key, value] of Object.entries({ viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.7', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true', focusable: 'false' })) svg.setAttribute(key, value);
  const path = document.createElementNS(svg.namespaceURI, 'path');
  path.setAttribute('d', {
    ascending: 'm7 10 5-5 5 5 M12 5v14', descending: 'm7 14 5 5 5-5 M12 19V5',
    filter: 'M4 6h16 M7 12h10 M10 18h4', right: 'm9 6 6 6-6 6', down: 'm6 9 6 6 6-6',
  }[name]);
  svg.append(path); return svg;
}
function coordinateName(column, row) {
  let label = '';
  for (let index = column + 1; index > 0; index = Math.floor((index - 1) / 26)) label = String.fromCharCode(65 + (index - 1) % 26) + label;
  return `${label}${row + 1}`;
}
function button(label, className, text, action) {
  const node = document.createElement('button');
  node.type = 'button'; node.className = className; node.textContent = text;
  node.setAttribute('aria-label', label); node.title = label;
  node.addEventListener('mousedown', event => { event.preventDefault(); event.stopPropagation(); });
  node.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); action(); });
  return node;
}

export class InventoryGrid extends HTMLElement {
  constructor() {
    super();
    this._model = { columns: [], rows: [], merges: [] };
    this._rows = [];
    this._merges = [];
    this._rowDetail = null;
    this._selectedEntries = [];
    this._acceptedSort = {};
    this._acceptedFilters = {};
    this._saveStatus = 'Saved';
    this._layoutStatus = 'Saved';
    this._attempts = new Map();
    this._active = null;
    this._queued = false;
    this._rendering = false;
    this._windowSequence = 0;
    this._windowCache = new Map();
    this._detailHeights = new Map();
    this.whenReady = Promise.resolve();
  }
  connectedCallback() {
    if (this._connected) return;
    this._connected = true;
    for (const property of ['model', 'windowResponse', 'itemOptionsResponse', 'referenceOptionsResponse', 'columnLayout', 'selectedEntries', 'saveStatus', 'layoutStatus', 'busy', 'acceptedSort', 'acceptedFilters', 'rowDetail']) {
      if (!Object.prototype.hasOwnProperty.call(this, property)) continue;
      const value = this[property]; delete this[property]; this[property] = value;
    }
    this.classList.add('inventory-grid');
    if (!this.hasAttribute('tabindex')) this.tabIndex = 0;
    this._container = document.createElement('div');
    this._container.className = 'inventory-grid-sheet';
    this._notice = document.createElement('div');
    this._notice.className = 'inventory-grid-notice'; this._notice.setAttribute('role', 'status');
    this._layoutNotice = document.createElement('div');
    this._layoutNotice.className = 'inventory-grid-notice'; this._layoutNotice.setAttribute('role', 'status');
    this._windowNotice = document.createElement('div');
    this._windowNotice.className = 'inventory-grid-notice inventory-window-notice'; this._windowNotice.setAttribute('role', 'status');
    this._orphans = document.createElement('div'); this._orphans.className = 'inventory-grid-orphans';
    this._orphans.setAttribute('role', 'alert'); this._orphans.setAttribute('aria-label', t('Retained edits'));
    this._detailParking = document.createElement('div'); this._detailParking.hidden = true;
    this.replaceChildren(this._container, this._windowNotice, this._notice, this._layoutNotice, this._orphans, this._detailParking);
    this._syncLayoutStatus();
    this._filterUI = installServerFilters(this);
    this.addEventListener('focusin', event => {
      if (event.target.closest?.('inventory-grid') !== this) return;
      const control = event.target.closest?.('button,input,select,textarea,a');
      window.jspreadsheet.current = this._ownsEvent(event) && (!control || this._active) && (event.target === this || this._container.contains(event.target)) ? this.worksheet : null;
    });
    for (const type of ['mousedown', 'dblclick', 'touchstart']) this.addEventListener(type, event => {
      if (event.target.closest?.('inventory-grid') === this && !this._ownsEvent(event)) event.stopPropagation();
    });
    this.addEventListener('paste', event => this._paste(event), true);
    this.addEventListener('keydown', event => this._key(event), true);
    this.addEventListener('mousedown', event => this._beginResize(event), true);
    this.addEventListener('mousedown', event => this._beginColumnPress(event), true);
    this.addEventListener('click', () => { if (!this._dragging) this._finishColumnPress(); }, true);
    for (const type of ['wheel', 'touchmove']) this.addEventListener(type, event => {
      if (this._model.viewport && this._ownsEvent(event)) { this._pendingNavigation = null; this._pendingFieldFocus = null; }
    }, { capture: true, passive: true });
    // Native CE closes editors on scroll, including the delayed scroll that reveals a merged cell.
    this.addEventListener('scroll', event => {
      if (!this._ownsEvent(event) || !event.target.classList?.contains('jss_content')) return;
      if (this._active) event.stopPropagation();
      this._windowScrolled();
    }, true);
    this.whenReady = loadRuntime().then(() => { this._runtimeReady = true; this._render(); });
    this.whenReady.catch(() => { this._notice.textContent = t('Could not load inventory spreadsheet. Reload the page and try again.'); });
    this._onViewportResize = () => this._fitHeight();
    window.addEventListener('resize', this._onViewportResize);
    this._viewportObserver = new ResizeObserver(this._onViewportResize);
    if (this.parentElement) this._viewportObserver.observe(this.parentElement);
    const workbench = this.closest('#inventory-evidence-workbench');
    if (workbench && workbench !== this.parentElement) this._viewportObserver.observe(workbench);
  }
  disconnectedCallback() {
    // Datastar moves preserved hosts during a morph; dispose only after a real removal.
    queueMicrotask(() => {
      if (!this.isConnected) { this._filterUI?.close(); this._layoutObserver?.disconnect(); this._viewportObserver?.disconnect(); window.removeEventListener('resize', this._onViewportResize); this._destroy(); this._connected = false; }
    });
  }
  get model() { return this._model; }
  set model(value) {
    if (!value || !Array.isArray(value.rows) || !Array.isArray(value.columns)) return;
    const before = this._pendingQueryModel?.viewport || this._model.viewport, next = value.viewport;
    // Signal effects may repeat the initial window; requested windows enter through windowResponse.
    if (next && next.queryId === this._model.viewport?.queryId && next.start !== this._model.viewport.start) return;
    if (next && this._pendingQueryModel?.viewport.queryId === next.queryId) { this._pendingQueryModel = value; return; }
    let changedKey = next && before && next.queryKey !== before.queryKey;
    if (changedKey && this._rowDetail && this.worksheet) {
      let oldKey, newKey;
      try { oldKey = JSON.parse(before.queryKey); newKey = JSON.parse(next.queryKey); } catch (_) { /* Non-application fixtures may use opaque keys. */ }
      if (Array.isArray(oldKey) && Array.isArray(newKey) && JSON.stringify([oldKey[0], oldKey[1], oldKey[4]]) === JSON.stringify([newKey[0], newKey[1], newKey[4]])) {
        this._pendingQueryModel = value; this._windowCache.clear();
        this._windowAnchor = this._captureAnchor(this._rowDetail.rowId);
        this._detailHeights.clear();
        this._syncBusy();
        this._requestWindow(next, this._windowAnchor?.absoluteRow || 0, this._rowDetail.rowId); return;
      }
    }
    if (next && before && next.queryKey === before.queryKey && next.queryId !== before.queryId && this.worksheet) {
      this._pendingQueryModel = value;
      this._windowAnchor = this._captureAnchor();
      this._windowCache.clear(); this._detailHeights.clear();
      this._syncBusy();
      this._requestWindow(next, this._windowAnchor?.absoluteRow ?? this._model.viewport.start, this._windowAnchor?.rowId);
      return;
    }
    if (changedKey) {
      this._windowAnchor = null; this._resetViewport = true;
      this._windowCache.clear(); this._windowPending = null; this._pendingQueryModel = null;
      this._detailHeights.clear();
    }
    this._replaceModel(value);
    if (changedKey && this._rowDetail) this._requestWindow(next, next.start || 0);
  }
  _replaceModel(value) {
    const signature = JSON.stringify(value);
    if (signature === this._modelSignature) return;
    this._modelSignature = signature;
    if (value.viewport && this.worksheet && value.viewport.queryId !== this._renderedQueryId) this._pendingGeneration = value.viewport.queryId;
    this._lastSelection = this._selectionKey() || this._lastSelection;
    const layoutKey = JSON.stringify([value.layoutScope || '', value.id || this.id]);
    if (this._layoutKey() !== layoutKey) { this._columnLayout = null; this._layoutPending = null; this._lastResizedKey = null; this._layoutStatus = 'Saved'; this._syncLayoutStatus(); }
    this._sourceModel = value;
    if (value.viewport) {
      this._windowCache.set(value.viewport.start, value);
      while (this._windowCache.size > 5) this._windowCache.delete(this._windowCache.keys().next().value);
    }
    if (value.layoutScope && (!this._columnLayout || (value.layoutVersion || 0) >= (this._columnLayout.version || 0))) {
      this._columnLayout = { widths: value.savedWidths || {}, order: value.columnOrder || [], version: value.layoutVersion || 0, lastColumn: value.lastColumn };
    }
    this._model = projectColumnLayout(value, this._columnLayout);
    this._syncLayoutWidths();
    this._selectionBeforeSnapshot = this._lastSelection;
    this._reconcile();
    this._syncBusy();
    this._scheduleRender();
  }
  get windowResponse() { return this._windowResponse; }
  set windowResponse(response) {
    if (!response || response.requestId !== this._windowPending?.requestId) return;
    const pending = this._windowPending;
    if (response.queryId && response.queryId !== pending.queryId) return;
    this._windowResponse = response;
    if (this._pendingNavigation?.requestId && this._pendingNavigation.requestId !== response.requestId) this._pendingNavigation = null;
    if (response.status === 'expired') {
      this._windowPending = null;
      this._emit('inventory-resnapshot', { anchorId: pending.anchorId, start: pending.start }); return;
    }
    if (response.status !== 'ok' || !response.model?.viewport) {
      this._windowFailed = pending; this._windowPending = null; this._showWindowStatus('failed'); return;
    }
    if (response.model.viewport.queryId !== pending.queryId) return;
    this._windowPending = null; this._windowFailed = null; this._showWindowStatus();
    const model = response.model;
    if (model.viewport.queryId !== this._model.viewport?.queryId) this._detailHeights.clear();
    this._windowCache.set(model.viewport.start, model);
    while (this._windowCache.size > 5) this._windowCache.delete(this._windowCache.keys().next().value);
    this._pendingQueryModel = null;
    this._replaceModel(model);
  }
  get itemOptionsResponse() { return this._itemOptionsResponse; }
  set itemOptionsResponse(response) {
    const state = this._active?.itemOptions;
    if (!response || !state || response.requestId !== state.requestId) return;
    this._itemOptionsResponse = response;
    state.select.setAttribute('aria-busy', 'false');
    state.select.title = response.status === 'ok' ? t('Choose existing item') : t('Could not load items. Type to retry.');
    if (response.status === 'ok') {
      const options = (response.options || []).slice(0, 30);
      if (state.requestText === '' && state.selectedOption && !options.some(option => option.value === state.selectedOption.value)) options.unshift(state.selectedOption);
      state.select.replaceChildren(new Option(t('Choose existing item…'), ''),
        ...options.map(option => new Option(option.label, option.value)));
    }
  }
  _requestItemOptions(state, text = state.input.value) {
    if (this._active?.itemOptions !== state) return;
    state.requestText = text;
    state.requestId = `${this._model.id || this.id}:items:${crypto.randomUUID()}`;
    state.select.setAttribute('aria-busy', 'true'); state.select.title = t('Loading existing items…');
    this._emit('inventory-item-options', { requestId: state.requestId, text, target: { ...state.target } });
  }
  get referenceOptionsResponse() { return this._referenceOptionsResponse; }
  set referenceOptionsResponse(response) {
    const state = this._active?.referenceOptions;
    if (!response || !state || response.requestId !== state.requestId) return;
    this._referenceOptionsResponse = response;
    state.select.setAttribute('aria-busy', 'false');
    state.select.title = response.status === 'ok' ? t('Choose operation') : t('Could not load operations. Type to retry.');
    if (response.status === 'ok') {
      state.options = (response.options || []).filter(option => option.value !== '').slice(0, 30);
      if (state.requestText === '' && state.selectedOption && !state.options.some(option => option.value === state.selectedOption.value)) state.options.unshift(state.selectedOption);
      state.select.replaceChildren(new Option(t('Choose operation…'), state.placeholder), new Option(t('None'), ''),
        ...state.options.map(option => new Option(option.label, option.value)));
    }
  }
  _requestReferenceOptions(state, text = state.input.value) {
    if (this._active?.referenceOptions !== state) return;
    state.requestText = text;
    state.requestId = `${this._model.id || this.id}:references:${crypto.randomUUID()}`;
    state.select.setAttribute('aria-busy', 'true'); state.select.title = t('Loading operations…');
    this._emit('inventory-reference-options', { requestId: state.requestId, text, target: { ...state.target } });
  }
  _showWindowStatus(status) {
    if (!this._windowNotice) return;
    this.dataset.windowStatus = status || 'ready'; this._windowNotice.replaceChildren();
    if (status === 'failed') this._windowNotice.append(t('Could not load more rows. '), button(t('Retry loading rows'), '', t('Retry'), () => {
      const failed = this._windowFailed;
      if (failed) this._requestWindow({ queryId: failed.queryId }, failed.start, failed.anchorId);
    }));
    else if (status === 'loading') this._windowNotice.textContent = t('Loading rows…');
  }
  _requestWindow(viewport, start, anchorId) {
    if (!viewport?.queryId) return;
    start = Math.max(0, Math.floor(Number(start) || 0));
    if (this._windowPending?.queryId === viewport.queryId && this._windowPending.start === start && this._windowPending.anchorId === anchorId) return this._windowPending;
    const request = { queryId: viewport.queryId, start, count: viewport.windowSize || 200,
      requestId: `${this._model.id || this.id}:${++this._windowSequence}`, ...(anchorId ? { anchorId } : {}),
      ...(this._rowDetail?.rowId ? { detailRowId: this._rowDetail.rowId } : {}),
      ...(this._model.id === 'stock' ? { pendingRows: [...new Set([...this._attempts.values()].map(attempt => attempt.target.rowId || attempt.target.id))].slice(0, 100) } : {}) };
    this._windowPending = request; this._windowFailed = null; this._showWindowStatus('loading');
    this._emit('inventory-window', request);
    return request;
  }
  _windowScrolled() {
    if (!this._model.viewport || this._rendering) return;
    cancelAnimationFrame(this._scrollFrame);
    this._scrollFrame = requestAnimationFrame(() => {
      if (this._windowPending) this._windowAnchor = this._captureAnchor();
      this._ensureWindow();
    });
  }
  _ensureWindow() {
    const viewport = this._model.viewport, content = this._container?.querySelector('.jss_content');
    if (!viewport || !content || this._active || this._resizing || this._dragging || this._columnPress || this._pendingQueryModel) return;
    const first = Math.max(0, Math.floor(this._baseOffset(content.scrollTop) / 32));
    const visible = Math.ceil(content.clientHeight / 32), buffer = Math.max(visible, 10);
    if ((first >= viewport.start + buffer || viewport.start === 0) && (first + visible < viewport.end - buffer || viewport.end >= viewport.totalRows)) return;
    const start = Math.max(0, Math.min(viewport.totalRows - 1, first - Math.floor((viewport.windowSize || 200) / 3)));
    const cached = [...this._windowCache.values()].find(model => {
      const window = model.viewport;
      return window.queryId === viewport.queryId && first >= window.start + (window.start ? buffer : 0) && first + visible <= window.end - (window.end < window.totalRows ? buffer : 0);
    });
    this._windowAnchor = this._captureAnchor();
    if (cached) this._replaceModel(this.windowModelRenderer?.(cached) || cached);
    else this._requestWindow(viewport, start);
  }
  _baseOffset(offset) {
    let removed = 0;
    for (const detail of [...this._detailHeights.values()].sort((left, right) => left.position - right.position)) {
      if (detail.position * 32 + removed < offset) removed += detail.height;
    }
    return Math.max(0, offset - removed);
  }
  get rowDetail() { return this._rowDetail; }
  set rowDetail(value) {
    const previous = this._rowDetail;
    if (previous?.node === value?.node && previous?.rowId === value?.rowId) return;
    if (previous?.node !== value?.node) this._parkDetail();
    this._rowDetail = value?.rowId && value.node instanceof HTMLElement ? value : null;
    if (previous?.rowId !== this._rowDetail?.rowId) this._detailHeights.delete(`detail:${previous?.rowId}`);
    if (this.worksheet && previous?.rowId === this._rowDetail?.rowId && this._mountDetail()) return;
    this._scheduleRender();
  }
  focusRowAction(rowId) {
    if (this._queued || this._active) { this._pendingActionFocus = rowId; return; }
    const control = this._owned('td[data-row-id]').find(td => td.dataset.rowId === rowId)?.querySelector('button.inventory-cell-action');
    control?.focus({ preventScroll: true });
  }
  _owned(selector, root = this._container || this) {
    return [...root.querySelectorAll(selector)].filter(node => node.closest('inventory-grid') === this);
  }
  _ownsEvent(event) {
    if (event.target.closest?.('inventory-grid') !== this) return false;
    if (event.target.closest?.('.inventory-column-filter-panel') || this._rowDetail?.node.contains(event.target)) {
      if (window.jspreadsheet.current === this.worksheet) window.jspreadsheet.current = null;
      return false;
    }
    return true;
  }
  _parkDetail() {
    const replacement = this._container?.querySelector('#history-panel');
    const node = this._rowDetail?.node.isConnected ? this._rowDetail.node : replacement;
    const parking = document.getElementById('history-parking') || this._detailParking;
    if (node?.isConnected && parking && node.parentElement !== parking) parking.append(node);
  }
  _deriveRows() {
    this._renderedQueryId = this._model.viewport?.queryId;
    this._rows = [...this._model.rows]; this._merges = [...(this._model.merges || [])];
    if (this._rowDetail && this._model.viewport?.detailFound === false) {
      const rowId = this._rowDetail.rowId; this._parkDetail(); this._rowDetail = null;
      this._detailHeights.delete(`detail:${rowId}`); this._emit('inventory-row-detail-close', { rowId });
    }
    if (this._rowDetail) {
      const index = this._rows.findIndex(row => row.id === this._rowDetail.rowId);
      if (index < 0) {
        if (!this._model.viewport) {
          const rowId = this._rowDetail.rowId;
          this._parkDetail(); this._rowDetail = null;
          this._emit('inventory-row-detail-close', { rowId });
        }
      } else {
        const row = index + 1;
        this._rows.splice(row, 0, { id: `detail:${this._rowDetail.rowId}`, detail: true,
          detailPosition: this._rows[index].rowIndex + 1, cells: this._model.columns.map(() => ({})) });
        this._merges = this._merges.map(merge => ({ ...merge, row: merge.row >= row ? merge.row + 1 : merge.row }));
        this._merges.push({ row, col: 0, colspan: this._model.columns.length });
      }
    }
    const viewport = this._model.viewport;
    if (!viewport) return;
    const spacer = (kind, height) => ({ id: `spacer:${kind}`, spacer: kind, height,
      cells: this._model.columns.map(() => ({})) });
    const extra = (start, end) => [...this._detailHeights.values()].filter(detail => detail.position > start && detail.position <= end).reduce((sum, detail) => sum + detail.height, 0);
    if (viewport.start > 0) {
      this._rows.unshift(spacer('before', viewport.start * 32 + extra(0, viewport.start)));
      this._merges = this._merges.map(merge => ({ ...merge, row: merge.row + 1 }));
      this._merges.unshift({ row: 0, col: 0, colspan: this._model.columns.length });
    }
    if (viewport.end < viewport.totalRows) {
      this._merges.push({ row: this._rows.length, col: 0, colspan: this._model.columns.length });
      this._rows.push(spacer('after', (viewport.totalRows - viewport.end) * 32 + extra(viewport.end, viewport.totalRows)));
    }
  }
  _captureAnchor(preferredId) {
    const content = this._container?.querySelector('.jss_content');
    if (!content) return null;
    const virtual = Boolean(this._model.viewport), boundary = virtual ? content.getBoundingClientRect().top + 34 : 0;
    const rows = this._owned('tbody > tr[data-row-id]');
    const baseRows = new Map(this._rows.filter(row => !row.spacer && !row.detail && !row.cells.some(cell => cell.operationDetail)).map(row => [row.id, row]));
    const node = rows.find(tr => tr.dataset.rowId === preferredId && baseRows.has(tr.dataset.rowId)) || rows.find(tr => baseRows.has(tr.dataset.rowId) && tr.getBoundingClientRect().bottom > boundary) || rows.findLast(tr => baseRows.has(tr.dataset.rowId));
    const modelRow = node && this._rows.find(row => row.id === node.dataset.rowId);
    return { rowId: node?.dataset.rowId, absoluteRow: modelRow?.rowIndex, offset: node ? node.getBoundingClientRect().top - boundary : 0,
      left: content.scrollLeft, top: content.scrollTop, pageTop: window.scrollY, virtual };
  }
  _restoreAnchor(anchor) {
    const content = this._container?.querySelector('.jss_content');
    if (!content || !anchor) return;
    content.scrollLeft = anchor.left;
    const row = this._owned('tbody > tr[data-row-id]').find(tr => tr.dataset.rowId === anchor.rowId);
    if (anchor.virtual) {
      content.scrollTop = anchor.top;
      if (row) content.scrollTop += row.getBoundingClientRect().top - content.getBoundingClientRect().top - 34 - anchor.offset;
    } else {
      window.scrollTo({ top: anchor.pageTop, left: window.scrollX, behavior: 'instant' });
      if (row) window.scrollBy({ top: row.getBoundingClientRect().top - anchor.offset, left: 0, behavior: 'instant' });
      content.scrollTop = anchor.top;
    }
  }
  _mountDetail() {
    const node = this._rowDetail?.node;
    const row = this._rows.findIndex(candidate => candidate.detail);
    const cell = row < 0 ? null : this.worksheet?.getCellFromCoords(0, row);
    if (!cell || !node) return false;
    cell.classList.add('inventory-row-detail-cell');
    if (cell.firstChild !== node) cell.replaceChildren(node);
    const tr = cell.parentElement;
    tr.classList.add('inventory-row-detail'); tr.dataset.detailFor = this._rowDetail.rowId;
    if (tr.firstElementChild?.classList.contains('jss_row')) tr.firstElementChild.textContent = '';
    this._fitHeight(); return true;
  }
  get acceptedSort() { return this._acceptedSort; }
  set acceptedSort(value) { this._acceptedSort = value || {}; this._renderHeaders(); }
  get acceptedFilters() { return this._acceptedFilters; }
  set acceptedFilters(value) { this._acceptedFilters = value || {}; this._filterUI?.update(); this._renderHeaders(); }
  openColumnFilter(column) { this._filterUI?.open(this._model.columns.find(item => item.filterKey === column || item.key === column)); }
  get selectedEntries() { return this._selectedEntries; }
  set selectedEntries(value) {
    this._selectedEntries = Array.isArray(value) ? value : [];
    for (const input of this._owned('input[data-review-entry]')) input.checked = this._selectedEntries.includes(input.dataset.reviewEntry);
  }
  get saveStatus() { return this._saveStatus; }
  set saveStatus(value) {
    this._saveStatus = value || 'Saved';
    if (this._saveStatus === 'Saved') this._acknowledgedWrite = null;
    this.dataset.saveStatus = this._saveStatus;
    this._syncBusy();
    if (this._saveStatus.startsWith('Failed')) {
      for (const attempt of this._attempts.values()) if (attempt.state === 'saving') attempt.state = 'failed';
      this._scheduleRender();
      this._resolveAcknowledged();
    }
  }
  get filterBusy() { return this._externalBusy === true || this._saveStatus === 'Saving' || this._model.busy === true; }
  get busy() { return this.filterBusy || Boolean(this._pendingQueryModel || this._pendingGeneration); }
  set busy(value) { this._externalBusy = !!value; this._syncBusy(); }
  get layoutStatus() { return this._layoutStatus; }
  set layoutStatus(value) {
    this._layoutStatus = value || 'Saved';
    if (this._layoutStatus === 'Saved' && this._layoutPending) {
      this._layoutPending = null; this._syncLayoutWidths(); this._scheduleRender();
    }
    this._syncLayoutStatus();
  }
  get columnLayout() { return this._columnLayout; }
  set columnLayout(value) {
    if (!value || (value.version || 0) < (this._columnLayout?.version || 0) || JSON.stringify(value) === JSON.stringify(this._columnLayout)) return;
    this._lastSelection = this._selectionKey() || this._lastSelection;
    this._columnLayout = value;
    this._model = projectColumnLayout(this._sourceModel || this._model, value);
    this._selectionBeforeSnapshot = this._lastSelection;
    this._syncLayoutWidths(); this._scheduleRender();
  }
  _layoutKey() { return JSON.stringify([this._model.layoutScope || '', this._model.id || this.id]); }
  _syncLayoutWidths() {
    const key = this._layoutKey(), widths = columnWidths.get(key) || new Map();
    columnWidths.set(key, widths); this._columnWidths = widths;
    if (this._layoutPending || !this._model.savedWidths) return;
    this._lastResizedKey = this._model.lastColumn;
    for (const column of widths.keys()) if (!(column in this._model.savedWidths)) widths.delete(column);
    for (const [column, width] of Object.entries(this._model.savedWidths)) if (Number.isFinite(width) && width > 0) widths.set(column, width);
  }
  _syncLayoutStatus() {
    if (!this._layoutNotice) return;
    this.dataset.layoutStatus = this._layoutStatus;
    this._layoutNotice.replaceChildren();
    if (this._layoutStatus === 'Failed' && this._layoutPending) {
      this._layoutNotice.append(t('Could not save table layout. '), button(t('Retry saving table layout'), '', t('Retry'), () => this._requestLayout(this._layoutPending)));
    }
    for (const label of this._owned('.inventory-column-label[draggable]')) {
      if (label !== this._columnPress?.label) label.draggable = !this.busy && !this._active && !this._layoutBlocked();
    }
  }
  _layoutBlocked() { return this._layoutPending || this._layoutStatus !== 'Saved'; }
  _requestLayout(detail) {
    if (detail.widths) this._lastResizedKey = Object.keys(detail.widths).at(-1);
    if (this._model.layoutScope) { this._layoutPending = detail; this.layoutStatus = 'Saving'; }
    this._emit('inventory-layout', detail);
  }
  _syncBusy() {
    this.setAttribute('aria-busy', String(this.busy));
    this._filterUI?.update();
    this._syncLayoutStatus();
    for (const control of this._owned('[data-review-entry],.inventory-cell-action')) control.disabled = this.busy;
    for (const td of this._owned('td[data-x][data-y]')) {
      const cell = this._cell(Number(td.dataset.x), Number(td.dataset.y));
      const attempt = cell.target && this._attempts.get(pathKey(cell.target));
      td.classList.toggle('readonly', Boolean(!this._editable(cell) || ['saving', 'conflict'].includes(attempt?.state) || (attempt?.item && attempt.state === 'failed')));
    }
  }
  focusField(id, path) {
    const cells = [...this._owned('td[data-x][data-y]')];
    let td = cells.find(node => node.dataset.entryId === id && node.dataset.path === path);
    if (td) {
      td.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      this.worksheet?.updateSelectionFromCoords(Number(td.dataset.x), Number(td.dataset.y), Number(td.dataset.x), Number(td.dataset.y));
      if (!this.busy) this.worksheet?.openEditor(td, false);
      return;
    }
    for (const node of cells) {
      const cell = this._cell(Number(node.dataset.x), Number(node.dataset.y));
      const actions = cell.actions || (cell.action ? [cell.action] : []);
      const index = actions.findIndex(action => action.id === id && action.path === path);
      if (index < 0) continue;
      const control = node.querySelectorAll('.inventory-cell-action')[index];
      control?.scrollIntoView({ block: 'nearest', inline: 'nearest' }); control?.focus(); return;
    }
    if (!path) {
      td = cells.find(node => node.dataset.entryId === id && this._editable(this._cell(Number(node.dataset.x), Number(node.dataset.y))));
      if (td) this.focusField(id, td.dataset.path);
    }
  }
  _emit(name, detail) { this.dispatchEvent(new CustomEvent(name, { bubbles: true, detail })); }
  _cell(x, y) { return this._rows[y]?.cells[x] || {}; }
  _writable(cell) { return this._model.editable !== false && !!cell.target && cell.readOnly !== true; }
  _editable(cell) { return this._writable(cell) && !this.busy; }
  _targetCells() {
    const cells = [...(this._model.editStates || []), ...this._model.rows.flatMap(row => row.cells)].filter(cell => cell.target);
    if (this._model.viewport && this._active && !cells.some(cell => pathKey(cell.target) === pathKey(this._active.target))) {
      cells.push(this._cell(this._active.x, this._active.y));
    }
    return cells;
  }
  _resolveAcknowledged() {
    if (!this._saveStatus.startsWith('Failed') || !this._acknowledgedWrite || this._unresolvedCount !== 0) return;
    const target = this._acknowledgedWrite; this._acknowledgedWrite = null;
    this._emit('inventory-conflict-resolved', { id: target.id, path: target.path, remaining: 0 });
  }
  _attemptsChanged() {
    const count = this._attempts.size + (this._active && !this._attempts.has(pathKey(this._active.target)) ? 1 : 0);
    if (count !== this._unresolvedCount) { this._unresolvedCount = count; this._emit('inventory-unresolved', { count }); }
    this._resolveAcknowledged();
  }
  _reconcile() {
    const cells = new Map(this._targetCells().map(cell => [pathKey(cell.target), cell]));
    for (const [key, attempt] of this._attempts) {
      const cell = cells.get(key);
      if (!cell) {
        if (!this._model.viewport || this._model.missingRows?.includes(attempt.target.rowId || attempt.target.id)) attempt.state = 'orphan';
        continue;
      }
      if (versionKey(cell.target) !== versionKey(attempt.target)) {
        if (sameValue(attempt.item ? cell.itemValue : cell.value, attempt.value)) { this._attempts.delete(key); this._acknowledgedWrite = attempt.target; }
        else { attempt.state = 'conflict'; attempt.saved = cell.value; attempt.currentTarget = attempt.item ? cell.itemTarget : cell.target; }
      } else if (attempt.state === 'orphan') attempt.state = 'failed';
    }
    this._attemptsChanged();
  }
  _dismissAttempt(key, target) {
    this._attempts.delete(key); this._attemptsChanged();
    this._emit('inventory-conflict-resolved', { id: target.id, path: target.path, remaining: this._attempts.size });
    this._scheduleRender();
  }
  _presentOrphans() {
    if (!this._orphans) return;
    const visible = new Set(this._model.rows.flatMap(row => row.cells).filter(cell => cell.target).map(cell => pathKey(cell.target)));
    this._orphans.replaceChildren();
    for (const [key, attempt] of this._attempts) {
      if (visible.has(key)) continue;
      const row = document.createElement('div'); row.dataset.orphanEntryId = attempt.target.id; row.dataset.orphanPath = attempt.target.path;
      const value = attempt.display ?? (attempt.editorType === 'date' ? formatDate(attempt.value) : attempt.value);
      const field = attempt.fieldLabel || attempt.target.path.split('/').at(-1).replaceAll('-', ' ');
      const text = document.createElement('span'); text.textContent = t("{p0} Retained {p1}: {p2}", {p0: this._model.viewport && attempt.state !== 'orphan' ? t('This row is outside the loaded window.') : t('This row is no longer in the current view.'), p1: field, p2: value});
      if (attempt.state === 'conflict' && attempt.saved !== undefined) {
        const saved = document.createElement('span'); saved.textContent = t("Saved: {p0}", {p0: attempt.editorType === 'date' ? formatDate(attempt.saved) : attempt.saved}); row.append(saved);
      }
      row.prepend(text);
      if (this._model.viewport && this._model.id === 'stock' && attempt.state !== 'orphan') row.append(button(t('Show retained edit'), '', t('Show row'), () => {
        this._pendingFieldFocus = { id: attempt.target.rowId || attempt.target.id, path: attempt.target.path };
        this._windowAnchor = this._captureAnchor();
        this._requestWindow(this._model.viewport, this._model.viewport.start, attempt.target.rowId || attempt.target.id);
      }));
      row.append(button(t('Dismiss retained edit'), '', t('Dismiss'), () => this._dismissAttempt(key, attempt.target)));
      this._orphans.append(row);
    }
  }
  _scheduleRender() {
    if (this._resizing || this._dragging || this._columnPress) { this._resizePending = true; return; }
    if (!this._runtimeReady || this._active || this._queued) return;
    this._queued = true;
    queueMicrotask(() => { this._queued = false; if (!this._active) this._render(); });
  }
  _destroy() {
    if (!this.worksheet) return;
    this._parkDetail();
    if (window.jspreadsheet.current === this.worksheet) window.jspreadsheet.current = null;
    window.jspreadsheet.destroy(this._container);
    this.worksheet = null;
  }
  _selectionKey() {
    const selected = this.worksheet?.selectedCell;
    if (!selected) return this._lastSelection;
    const x = Number(selected[0]), y = Number(selected[1]), cell = this._cell(x, y);
    return cell.target ? { target: pathKey(cell.target) } : { rowId: this._rows[y]?.id, column: this._renderedColumns?.[x]?.key };
  }
  _beginColumnPress(event) {
    const label = event.target.closest?.('.inventory-column-label[draggable=true]');
    if (!label || !this._ownsEvent(event) || event.button !== 0 || this.busy || this._active || this._layoutBlocked()) return;
    const press = { label }; this._columnPress = press;
    document.addEventListener('mouseup', () => setTimeout(() => {
      if (this._columnPress === press && !this._dragging) this._finishColumnPress();
    }, 0), { once: true });
  }
  _finishColumnPress() {
    if (!this._columnPress) return;
    this._columnPress = null;
    queueMicrotask(() => {
      this._syncLayoutStatus();
      if (this._resizePending) { this._resizePending = false; this._scheduleRender(); }
      else { this._renderHeaders(); this._fitWidth(); }
    });
  }
  _beginResize(event) {
    const header = event.target.closest?.('thead td[data-x]');
    if (!header || !this._ownsEvent(event) || event.target !== header || event.button !== 0 || header.getBoundingClientRect().right - event.clientX >= 6) return;
    if (this.busy || this._active || this._layoutBlocked() || utilityColumn(this._renderedColumns[Number(header.dataset.x)].key)) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    this._resizing = true;
    this._resizingColumn = Number(header.dataset.x);
    this._resizeStartWidth = Number(this.worksheet.getWidth(this._resizingColumn));
    this._manualResize = null;
    document.addEventListener('mouseup', () => queueMicrotask(() => {
      const resized = this._manualResize;
      this._resizing = false;
      this._resizingColumn = null;
      if (resized && resized.width !== this._resizeStartWidth) {
        resized.width = Math.max(50, Math.min(2400, Math.round(resized.width)));
        const index = this._renderedColumns.findIndex(column => column.key === resized.key);
        this._columnWidths.set(resized.key, resized.width); this._setWidths([index], [resized.width]);
        this._requestLayout({ gridId: this._model.id, widths: { [resized.key]: resized.width } });
      }
      if (this._resizePending) { this._resizePending = false; this._scheduleRender(); }
      else this._fitWidth();
    }), { once: true });
  }
  _render() {
    if (this._resizing || this._dragging || this._columnPress) { this._resizePending = true; return; }
    if (!this._runtimeReady || !this.isConnected || this._active) return;
    const previousCurrent = window.jspreadsheet.current;
    const detailFocus = this._rowDetail?.node.contains(document.activeElement) ? document.activeElement : null;
    const wasCurrent = previousCurrent === this.worksheet;
    const restoreFocus = wasCurrent && (document.activeElement === document.body || (this._container?.contains(document.activeElement)));
    const selection = this._selectionBeforeSnapshot || this._selectionKey();
    this._selectionBeforeSnapshot = null;
    const anchor = this._resetViewport ? null : this._windowAnchor || this._captureAnchor();
    this._windowAnchor = null;
    const heldHeight = this._container.getBoundingClientRect().height;
    this._container.style.minHeight = `${heldHeight}px`;
    this._rendering = true;
    this._destroy();
    this._deriveRows();
    this._container.replaceChildren();
    const owner = this;
    const editor = {
      createCell(cell, value, x, y) { owner._present(cell, x, y); return cell; },
      updateCell(cell, value, x, y) { owner._present(cell, x, y, value); return value; },
      openEditor(cell, value, x, y, instance, options, event) { owner._open(cell, value, x, y, instance, event); },
      closeEditor(cell, save, x, y) { return owner._close(cell, save, x, y); },
    };
    const merges = Object.fromEntries(this._merges.map(merge => [coordinateName(merge.col, merge.row), [merge.colspan || 1, merge.rowspan || 1]]));
    const properties = {};
    this._rows.forEach((row, y) => row.cells.forEach((cell, x) => {
      if (!this._writable(cell)) properties[coordinateName(x, y)] = { readOnly: true };
    }));
    const data = this._rows.map(row => row.cells.map(cell => cell.value ?? ''));
    this._syncLayoutWidths();
    const widths = this._columnWidths;
    const renderedColumns = this._model.columns;
    this._renderedColumns = renderedColumns;
    this._columnWidths = widths;
    const options = {
      data, columns: renderedColumns.map(column => ({ title: column.label || '\u00a0', width: widths.get(column.key) || column.width || 140, type: editor, align: 'left' })),
      cells: properties, mergeCells: merges, minDimensions: [this._model.columns.length, data.length],
      editable: this._model.editable !== false, defaultRowHeight: 32,
      allowInsertColumn: false, allowManualInsertColumn: false, allowDeleteColumn: false,
      allowInsertRow: false, allowManualInsertRow: false, allowDeleteRow: false,
      allowRenameColumn: false, columnSorting: false, columnDrag: false, rowDrag: false,
      columnResize: true, rowResize: false, allowComments: false,
      parseFormulas: false, autoIncrement: false, filters: false, search: false,
      wordWrap: false, tableOverflow: true, tableWidth: '100%',
      tableHeight: this._model.viewport ? '400px' : ['stock', 'operations', 'shared-draft', 'item-history'].includes(this._model.id) ? 'auto' : this._model.height || `${Math.min(500, Math.max(66, data.length * 32 + 52))}px`,
      lazyLoading: false,
      rowHeaders: this._model.rowHeaders !== false,
    };
    const sheets = window.jspreadsheet(this._container, {
      worksheets: [options], toolbar: false, tabs: false, about: false,
      parseFormulas: false, contextMenu: () => false,
      onchange(instance, cell, x, y, value, old) {
        if (!owner._rendering && !sameValue(value, old)) owner._save(Number(x), Number(y), value);
      },
      oneditionend(instance, td, x, y) {
        if (owner._active?.td === td) { owner._active = null; owner._closedTarget = null; owner._closedContext = null; owner._present(td, x, y); }
        owner._attemptsChanged();
        queueMicrotask(() => owner._scheduleRender());
      },
      onselection(instance, x, y) {
        const cell = owner._cell(Number(x), Number(y));
        owner._lastSelection = cell.target ? { target: pathKey(cell.target) } : { rowId: owner._rows[y]?.id, column: owner._model.columns[x]?.key };
      },
      onresizecolumn(instance, column, width, previous) {
        if (owner._fittingWidth) return;
        const remember = (index, value) => {
          const key = renderedColumns[index].key;
          widths.delete(key); widths.set(key, Number(value));
          if (owner._resizing && index === owner._resizingColumn) owner._manualResize = { key, width: Number(value) };
        };
        // CE resizes every selected column; application resizing changes only the dragged one.
        if (Array.isArray(column) && owner._resizingColumn != null) {
          const restore = column.filter(index => index !== owner._resizingColumn);
          if (restore.length) owner._setWidths(restore, restore.map(index => previous[column.indexOf(index)]));
          remember(owner._resizingColumn, Array.isArray(width) ? width[column.indexOf(owner._resizingColumn)] : width);
        } else for (const [position, index] of (Array.isArray(column) ? column : [column]).entries()) remember(index, Array.isArray(width) ? width[position] : width);
      },
      onbeforepaste: () => false,
    });
    this.worksheet = sheets[0];
    // jSuites hardcodes this accessible region name outside its dictionary API.
    for (const region of this._container.querySelectorAll('[aria-label="Tab Panels"]')) region.setAttribute('aria-label', t('Spreadsheet panels'));
    if (wasCurrent) window.jspreadsheet.current = this.worksheet;
    else if (detailFocus && previousCurrent) window.jspreadsheet.current = previousCurrent;
    const table = this._container.querySelector('table.jss_worksheet');
    if (table) {
      table.setAttribute('aria-label', this.getAttribute('aria-label') || this._model.label || t('Inventory'));
      table.setAttribute('role', 'grid');
      let visibleRow = 0, groupId, groupHeader;
      for (const [index, tr] of [...table.tBodies[0].rows].entries()) {
        const row = this._rows[index]; if (!row) continue;
        tr.dataset.rowId = row.id;
        const header = tr.firstElementChild;
        if (header?.classList.contains('jss_row')) {
          if (row.spacer || row.detail || row.cells.some(cell => cell.operationDetail)) {
            header.textContent = ''; groupHeader = null;
          } else if (this._model.id === 'operations' && row.groupId && row.groupId === groupId && groupHeader) {
            groupHeader.rowSpan += 1;
            header.style.display = 'none';
          } else {
            header.textContent = String(Number.isInteger(row.groupIndex) ? row.groupIndex + 1 : Number.isInteger(row.rowIndex) ? row.rowIndex + 1 : ++visibleRow); groupId = row.groupId; groupHeader = header;
          }
        }
        for (const name of (row.className || '').split(/\s+/).filter(Boolean)) tr.classList.add(name);
        if (row.spacer) {
          tr.classList.add('inventory-virtual-spacer'); tr.dataset.spacer = row.spacer;
          tr.style.height = `${row.height}px`; tr.setAttribute('aria-hidden', 'true');
          for (const td of tr.cells) { td.style.height = `${row.height}px`; td.style.padding = '0'; td.style.border = '0'; }
        }
      }
      if (this._model.rowHeaders === false) table.classList.add('inventory-grid-no-row-headers');
    }
    if (selection) {
      outer: for (let y = 0; y < this._rows.length; y++) for (let x = 0; x < this._model.columns.length; x++) {
        const cell = this._cell(x, y);
        if (selection.target ? pathKey(cell.target) === selection.target : this._rows[y].id === selection.rowId && this._model.columns[x].key === selection.column) {
          this.worksheet.updateSelectionFromCoords(x, y, x, y); break outer;
        }
      }
    }
    this._mountDetail();
    this._rendering = false;
    this._renderHeaders();
    this._presentOrphans();
    this._fitWidth();
    this._fitHeight();
    this._container.style.minHeight = '';
    if (this._resetViewport) { this._container.querySelector('.jss_content').scrollTop = 0; this._resetViewport = false; }
    else this._restoreAnchor(anchor);
    this._layoutObserver?.disconnect();
    this._layoutObserver = new ResizeObserver(() => { this._fitWidth(); this._fitHeight(); });
    if (table) this._layoutObserver.observe(table);
    this._layoutObserver.observe(this);
    if (this._pendingGeneration === this._renderedQueryId) this._pendingGeneration = null;
    this._syncBusy();
    if (detailFocus?.isConnected) detailFocus.focus({ preventScroll: true });
    else if (restoreFocus) this.focus({ preventScroll: true });
    if (this._pendingActionFocus) { const rowId = this._pendingActionFocus; this._pendingActionFocus = null; this.focusRowAction(rowId); }
    if (this._pendingFieldFocus) {
      const field = this._pendingFieldFocus; this._pendingFieldFocus = null; this.focusField(field.id, field.path);
    }
    if (this._pendingNavigation) {
      const navigation = this._pendingNavigation;
      const y = this._rows.findIndex(row => row.rowIndex === navigation.rowIndex), x = this._model.columns.findIndex(column => column.key === navigation.column);
      if (y >= 0 && x >= 0) {
        this._pendingNavigation = null; this.worksheet.updateSelectionFromCoords(x, y, x, y);
        this.worksheet.getCellFromCoords(x, y)?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        this.focus({ preventScroll: true });
      }
    }
    this._emit('inventory-grid-ready', { id: this.id });
    if (this._model.viewport) this._windowScrolled();
  }
  _setWidths(indexes, widths) {
    const ignoreHistory = this.worksheet.ignoreHistory;
    this._fittingWidth = true; this.worksheet.ignoreHistory = true;
    try { this.worksheet.setWidth(indexes, widths); }
    finally { this.worksheet.ignoreHistory = ignoreHistory; this._fittingWidth = false; }
  }
  _fitWidth() {
    if (!this.worksheet || this._resizing || this._dragging || this._columnPress || this._fittingWidth) return;
    const table = this._container.querySelector('table.jss_worksheet'), content = this._container.querySelector('.jss_content');
    if (!table || !content?.clientWidth) return;
    const columns = this._renderedColumns, saved = this._columnWidths;
    const widths = columns.map(column => saved.get(column.key) || column.width || 140);
    const headers = this._owned('thead td[data-x]');
    const overhead = table.offsetWidth - headers.reduce((sum, header) => sum + header.offsetWidth, 0);
    const spare = Math.max(0, content.clientWidth - overhead - widths.reduce((sum, width) => sum + width, 0));
    const dataColumns = columns.map((column, index) => utilityColumn(column.key) ? -1 : index).filter(index => index >= 0);
    let flexible = dataColumns.filter(index => !saved.has(columns[index].key));
    // Once every column is manual, keep the latest drag exact and fill using older columns.
    if (!flexible.length) flexible = dataColumns.filter(index => columns[index].key !== (this._lastResizedKey || [...saved.keys()].at(-1)));
    if (!flexible.length) flexible = dataColumns;
    const total = flexible.reduce((sum, index) => sum + widths[index], 0);
    let weight = 0, allocated = 0;
    for (const index of flexible) {
      weight += widths[index];
      const next = Math.floor(spare * weight / total);
      widths[index] += next - allocated; allocated = next;
    }
    const changed = widths.flatMap((width, index) => Number(this.worksheet.getWidth(index)) === width ? [] : [index]);
    if (changed.length) this._setWidths(changed, changed.map(index => widths[index]));
  }
  _fitHeight() {
    const panels = [this._rowDetail?.node, ...this._owned('.inventory-operation-details-cell > .operation-details')];
    for (const detail of panels.filter(node => node?.isConnected)) {
      const padding = getComputedStyle(detail.parentElement);
      const width = `${Math.max(0, this.clientWidth - (this._model.rowHeaders === false ? 0 : 44) - parseFloat(padding.paddingLeft) - parseFloat(padding.paddingRight))}px`;
      if (detail.style.maxWidth !== width) detail.style.maxWidth = width;
    }
    const table = this._container?.querySelector('table.jss_worksheet'), content = this._container?.querySelector('.jss_content');
    if (!table || !content) return;
    this.classList.toggle('inventory-grid-virtual', Boolean(this._model.viewport));
    if (this._model.viewport) {
      const explicitHeight = getComputedStyle(this).getPropertyValue('--inventory-viewport-height').trim();
      const height = explicitHeight ? 'max(240px, var(--inventory-viewport-height))' : `${Math.max(240, window.innerHeight - content.getBoundingClientRect().top - (this._windowNotice?.offsetHeight || 0) - 2)}px`;
      if (content.style.height !== height) { content.style.height = height; content.style.maxHeight = height; }
      for (const row of this._rows.filter(row => this._renderedQueryId === this._model.viewport.queryId && (row.detail || row.cells.some(cell => cell.operationDetail)))) {
        const tr = this._owned('tbody > tr[data-row-id]').find(tr => tr.dataset.rowId === row.id);
        if (tr) {
          const bounds = tr.getBoundingClientRect(), previous = this._detailHeights.get(row.id);
          if (!this._rendering && previous && bounds.top + previous.height <= content.getBoundingClientRect().top + 34) content.scrollTop += bounds.height - previous.height;
          this._detailHeights.set(row.id, { position: row.detailPosition, height: bounds.height });
        }
      }
      return;
    }
    if (['stock', 'operations', 'shared-draft', 'item-history'].includes(this._model.id)) {
      content.style.height = 'auto'; content.style.maxHeight = 'none'; return;
    }
    if (this._model.height) return;
    const scrollbar = Math.max(0, content.offsetHeight - content.clientHeight);
    const maximum = 500;
    const height = `${Math.min(maximum, Math.max(52, table.offsetHeight + Math.max(0, scrollbar)))}px`;
    if (content.style.height !== height) content.style.height = height;
    content.style.maxHeight = `${maximum}px`;
  }
  _renderHeaders() {
    if (this._dragging || this._columnPress) return;
    for (const td of this._owned('thead td[data-x]')) {
      const column = this._model.columns[Number(td.dataset.x)]; if (!column) continue;
      td.replaceChildren(); td.dataset.columnKey = column.key; td.setAttribute('role', 'columnheader');
      const label = document.createElement(column.sortKey ? 'button' : 'span');
      label.className = 'inventory-column-label'; label.textContent = column.label || '\u00a0';
      if (column.sortKey) {
        label.type = 'button'; label.setAttribute('aria-label', t("Sort by {p0}", {p0: column.label}));
        label.addEventListener('mousedown', event => { this._suppressSortUntil = 0; event.stopPropagation(); });
        label.addEventListener('click', event => {
          event.preventDefault(); event.stopPropagation(); if (this.busy || (event.detail !== 0 && performance.now() < this._suppressSortUntil)) return;
          this._emit('inventory-sort', { column: column.sortKey, direction: this._acceptedSort.column === column.sortKey && this._acceptedSort.direction === 'asc' ? 'desc' : 'asc' });
        });
        const active = this._acceptedSort.column === column.sortKey;
        if (active) { const order = document.createElement('span'); order.className = 'inventory-column-order'; order.append(gridIcon(this._acceptedSort.direction === 'desc' ? 'descending' : 'ascending')); label.append(order); }
        td.setAttribute('aria-sort', active ? this._acceptedSort.direction === 'desc' ? 'descending' : 'ascending' : 'none');
      }
      if (!utilityColumn(column.key)) {
        label.draggable = !this.busy && !this._active && !this._layoutBlocked();
        label.title = t("Drag to reorder {p0}", {p0: column.label});
        label.addEventListener('dragstart', event => {
          if ((this.busy || this._active || this._resizing || this._layoutBlocked()) && this._columnPress?.label !== label) { event.preventDefault(); return; }
          this._dragging = { key: column.key, keys: this._renderedColumns.filter(item => !utilityColumn(item.key)).map(item => item.key) };
          this._lastSelection = this._selectionKey();
          event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', column.key);
          td.classList.add('inventory-column-dragging');
        });
        td.ondragover = event => {
          if (!this._dragging) return;
          event.preventDefault(); event.dataTransfer.dropEffect = 'move';
          for (const node of this._owned('thead td[data-drop-side]')) delete node.dataset.dropSide;
          td.dataset.dropSide = event.clientX < td.getBoundingClientRect().left + td.offsetWidth / 2 ? 'before' : 'after';
        };
        td.ondrop = event => {
          if (!this._dragging) return;
          event.preventDefault(); event.stopPropagation();
          const { key, keys } = this._dragging;
          const order = keys.filter(item => item !== key);
          const position = order.indexOf(column.key);
          if (position >= 0) order.splice(position + (td.dataset.dropSide === 'after' ? 1 : 0), 0, key);
          else order.splice(keys.indexOf(key), 0, key);
          this._finishColumnDrag();
          if (order.some((item, index) => item !== keys[index])) this._requestLayout({ gridId: this._model.id, order });
        };
        label.addEventListener('dragend', () => this._finishColumnDrag());
      }
      td.append(label);
      if (column.filterKey) {
        const filter = button(t("Filter {p0}", {p0: column.label}), `inventory-column-filter${this._acceptedFilters[column.filterKey]?.length ? ' is-active' : ''}`, '', () => this._filterUI.open(column));
        filter.append(gridIcon('filter')); td.append(filter);
      }
    }
  }
  _finishColumnDrag() {
    this._dragging = null; this._columnPress = null; this._suppressSortUntil = performance.now() + 250;
    for (const td of this._owned('thead td')) { delete td.dataset.dropSide; td.classList.remove('inventory-column-dragging'); }
    if (this._resizePending) { this._resizePending = false; this._scheduleRender(); }
    else { this._renderHeaders(); this._fitWidth(); }
  }
  _present(td, x, y, nextValue) {
    const cell = this._cell(Number(x), Number(y));
    const row = this._rows[y];
    if (row?.spacer) { td.replaceChildren(); td.className = 'inventory-virtual-spacer-cell'; td.setAttribute('aria-hidden', 'true'); return; }
    if (row?.detail) { td.className = Number(x) === 0 ? 'inventory-row-detail-cell' : ''; return; }
    const attempt = cell.target && this._attempts.get(pathKey(cell.target));
    const value = attempt ? (attempt.display ?? attempt.value) : (nextValue ?? cell.value ?? '');
    td.replaceChildren();
    td.dataset.rowId = row?.id || '';
    td.dataset.columnKey = this._model.columns[x]?.key || '';
    td.className = [cell.className, row?.className, cell.editor?.type === 'quantity' ? 'inventory-cell-number' : '', cell.before !== undefined && !sameValue(cell.before, cell.value) ? 'inventory-cell-changed' : ''].filter(Boolean).join(' ');
    td.setAttribute('role', 'gridcell');
    if (cell.target) { td.dataset.entryId = cell.target.id; td.dataset.path = cell.target.path; td.dataset.revision = cell.target.revision; }
    if (attempt) td.dataset.saveState = attempt.state;
    else delete td.dataset.saveState;
    const rendered = this.cellRenderer?.(cell, row);
    if (rendered) { td.append(rendered); return; }
    if (cell.selection) {
      const input = document.createElement('input');
      input.type = 'checkbox'; input.dataset.reviewEntry = cell.selection.id;
      input.checked = this._selectedEntries.includes(cell.selection.id);
      input.disabled = this.busy;
      input.setAttribute('aria-label', cell.selection.label || t('Select operation'));
      input.addEventListener('mousedown', event => event.stopPropagation());
      input.addEventListener('click', event => event.stopPropagation());
      input.addEventListener('change', () => {
        this._selectedEntries = input.checked ? [...new Set([...this._selectedEntries, cell.selection.id])] : this._selectedEntries.filter(id => id !== cell.selection.id);
        this._emit('review-selection', { selected: this._selectedEntries });
      });
      td.append(input); return;
    }
    if (cell.before !== undefined && !sameValue(cell.before, cell.value) && this._model.showPrevious !== false) {
      const before = document.createElement('span'); before.className = 'inventory-cell-before';
      before.textContent = String(cell.before === '' || cell.before == null ? '—' : cell.before); td.append(before, document.createTextNode(' → '));
    }
    const label = document.createElement('span'); label.className = 'inventory-cell-value';
    label.textContent = `${cell.prefix || ''}${cell.editor?.type === 'date' || cell.date ? formatDate(value) : String(attempt ? value : (cell.display ?? value))}`;
    if (cell.lines?.length) {
      label.replaceChildren();
      for (const line of cell.lines) { const node = document.createElement('span'); node.className = `inventory-cell-line${line.date ? ' inventory-cell-date' : ''}`; node.textContent = `${line.prefix || ''}${line.date ? formatDate(line.value) : (line.value ?? '')}`; label.append(node); }
    }
    td.append(label);
    if (this._model.showPrevious === false && cell.before !== undefined && !sameValue(cell.before, cell.value)) td.title = t("Previously: {p0}", {p0: cell.before === '' || cell.before == null ? '—' : cell.before});
    if (cell.title) td.title = cell.title;
    const actions = cell.actions || (cell.action ? [cell.action] : []);
    const expansion = actions.some(action => ['item-history', 'operation-details', 'operation-revise'].includes(action.type));
    const contents = expansion ? document.createElement('span') : td;
    if (expansion) { contents.className = 'inventory-cell-with-action'; contents.append(label); td.append(contents); }
    for (const action of actions) {
      const expanded = action.type === 'item-history' && this._rowDetail?.rowId === row?.id;
      const actionButton = button(action.label || action.command, `inventory-cell-action ${action.className || ''}`, (expanded ? '⌄' : action.icon) || (action.command?.startsWith('remove') || action.command === 'discard' ? '×' : '+'), () => { if (!this.busy) this._emit(action.type || 'draft-command', action.type === 'item-history' ? { ...action, expanded } : { ...action }); });
      if (['item-history', 'operation-details'].includes(action.type)) actionButton.replaceChildren(gridIcon(expanded || action.icon === '⌄' ? 'down' : 'right'));
      if (action.type === 'item-history') actionButton.setAttribute('aria-expanded', String(expanded));
      if (['item-history', 'operation-details', 'operation-revise'].includes(action.type)) contents.prepend(actionButton); else contents.append(actionButton);
    }
    if (attempt?.state === 'conflict' || attempt?.state === 'failed') {
      td.classList.add('inventory-cell-conflict');
      td.title = attempt.state === 'conflict' ? t("Your value: {p0}. Saved value: {p1}. Choose a value to continue.", {p0: cell.editor?.type === 'date' ? formatDate(attempt.value) : (attempt.display ?? attempt.value), p1: cell.editor?.type === 'date' ? formatDate(attempt.saved) : (attempt.saved ?? '')}) : t('This edit has not been saved. Retry or use the saved value.');
      const controls = document.createElement('span'); controls.className = 'inventory-cell-resolution';
      if (attempt.state === 'conflict') {
        const saved = document.createElement('span'); saved.className = 'inventory-cell-saved';
        saved.textContent = t("Saved: {p0}", {p0: cell.editor?.type === 'date' ? formatDate(attempt.saved) : (attempt.saved ?? '—')});
        controls.append(saved);
      }
      controls.append(button(t('Use saved value'), '', t('Use saved'), () => this._dismissAttempt(pathKey(cell.target), cell.target)));
      controls.append(button(attempt.state === 'conflict' ? t('Save my value') : t('Retry saving'), '', attempt.state === 'conflict' ? t('Use mine') : t('Retry'), () => {
        this._attempts.delete(pathKey(cell.target)); this._attemptsChanged();
        if (attempt.item) this._saveItem(cell, attempt.value, attempt.display);
        else this._save(Number(x), Number(y), attempt.value);
      }));
      td.append(controls);
    }
    td.onclick = event => {
      if (event.detail !== 1 || event.target.closest('button,input,select') || !this._editable(this._cell(Number(x), Number(y)))) return;
      this.worksheet?.openEditor(td, false, event);
    };
  }
  _open(td, value, x, y, worksheet, event) {
    const cell = this._cell(Number(x), Number(y));
    if (!this._editable(cell)) { worksheet.edition = null; return; }
    const attempt = this._attempts.get(pathKey(cell.target));
    if (attempt?.state === 'conflict' || attempt?.state === 'saving' || (attempt?.item && attempt.state === 'failed')) { worksheet.edition = null; return; }
    this._active = { td, x: Number(x), y: Number(y), target: { ...cell.target }, original: cell.value, worksheet,
      fieldLabel: this._model.columns[x]?.label, editorType: cell.editor?.type };
    this._attemptsChanged();
    td.replaceChildren(); td.classList.add('inventory-cell-editing');
    const wrapper = document.createElement('span'); wrapper.className = 'inventory-cell-editor';
    const type = cell.editor?.type || 'text';
    const input = document.createElement(type === 'select' ? 'select' : 'input');
    const raw = attempt?.value ?? cell.value ?? '';
    if (type === 'select') {
      for (const option of cell.editor.options || []) {
        const element = document.createElement('option'); element.value = option.value; element.textContent = option.label; input.append(element);
      }
      input.value = raw;
    } else {
      input.type = type === 'date' ? 'datetime-local' : ['quantity', 'decimal'].includes(type) ? 'number' : 'text';
      if (type === 'quantity') input.step = '1';
      if (type === 'decimal') input.step = 'any';
      if (type === 'date') { input.step = '60'; input.dataset.dateValue = raw; input.value = toLocalDateTime(raw); }
      else input.value = event?.type === 'keydown' && event.key?.length === 1 ? event.key : raw;
      if (type === 'operation-reference' && event?.key?.length !== 1) input.value = raw ? (attempt?.display ?? cell.display ?? cell.editor.options?.find(option => option.value === raw)?.label ?? raw) : '';
    }
    input.setAttribute('aria-label', t("{p0} for {p1}", {p0: this._model.columns[x]?.label || t('Value'), p1: this._rows[y]?.label || t('operation')}));
    input.addEventListener('input', () => input.setAttribute('data-unsaved-edit', ''));
    input.addEventListener('keydown', e => {
      e.stopPropagation();
      if (e.key === 'Escape') { e.preventDefault(); worksheet.closeEditor(td, false); }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault(); this._nextSelection = { x: Number(x), y: Number(y), backwards: e.shiftKey, vertical: e.key === 'Enter' };
        if (type === 'operation-reference') {
          const state = this._active?.referenceOptions;
          const selected = e.key === 'Enter' && (state?.options.find(option => input.value === option.label || (option.value && input.value === option.value)) || (input.value === t('None') ? { value: '', label: '' } : null));
          if (!selected) { worksheet.closeEditor(td, false); return; }
          state.chosenValue = selected.value; state.chosenLabel = selected.label;
        }
        worksheet.closeEditor(td, true);
      }
    });
    input.addEventListener('blur', () => { if (this._active?.td === td) worksheet.closeEditor(td, true); });
    wrapper.append(input);
    if (type === 'goods' || type === 'operation-reference') {
      const reference = type === 'operation-reference';
      const availableOptions = (cell.editor.options || []).filter(option => !reference || option.value !== '');
      const picker = document.createElement('span'); picker.className = 'inventory-goods-picker';
      const choices = document.createElement('select'); choices.className = 'inventory-goods-choice';
      choices.setAttribute('aria-label', reference ? t('Choose operation') : t('Choose existing item'));
      choices.title = reference ? t('Choose operation') : t('Choose existing item');
      const placeholder = reference ? `placeholder:${crypto.randomUUID()}` : '';
      choices.append(new Option(reference ? t('Choose operation…') : t('Choose existing item…'), placeholder));
      if (reference) choices.append(new Option(t('None'), ''));
      for (const option of availableOptions) choices.append(new Option(option.label, option.value));
      const state = { select: choices, input, options: availableOptions, placeholder,
        selectedOption: availableOptions.find(option => option.value === (reference ? cell.value : cell.itemValue)) || (!reference && availableOptions.length === 1 ? availableOptions[0] : null),
        target: reference ? cell.target : cell.itemTarget || cell.editor.itemTarget || cell.target };
      this._active[reference ? 'referenceOptions' : 'itemOptions'] = state;
      const requestOptions = text => reference ? this._requestReferenceOptions(state, text) : this._requestItemOptions(state, text);
      input.addEventListener('input', () => {
        clearTimeout(state.timer);
        state.requestId = null;
        state.timer = setTimeout(requestOptions, 150);
      });
      choices.addEventListener('mousedown', e => e.stopPropagation());
      input.addEventListener('blur', e => { if (wrapper.contains(e.relatedTarget)) e.stopImmediatePropagation(); }, true);
      choices.addEventListener('keydown', e => {
        e.stopPropagation();
        if (e.key === 'Escape') { e.preventDefault(); worksheet.closeEditor(td, false); }
      });
      choices.addEventListener('blur', e => {
        if (this._active?.td === td && !wrapper.contains(e.relatedTarget)) worksheet.closeEditor(td, true);
      });
      choices.addEventListener('change', () => {
        if (reference) {
          if (choices.selectedIndex === 0) return;
          state.chosenValue = choices.value; state.chosenLabel = choices.value ? choices.selectedOptions[0]?.textContent : '';
          worksheet.closeEditor(td, true); return;
        }
        if (!choices.value || !(cell.itemTarget || cell.editor.itemTarget)) return;
        const selected = choices.value, display = choices.selectedOptions[0]?.textContent || input.value;
        worksheet.closeEditor(td, false);
        this._saveItem(cell, selected, display);
      });
      picker.append(gridIcon('down'), choices); wrapper.append(picker);
      requestOptions('');
    }
    td.append(wrapper);
    queueMicrotask(() => { input.focus(); if (input.type === 'text' || input.type === 'number') input.select(); });
  }
  _close(td, save, x, y) {
    const active = this._active;
    if (!active || active.td !== td) return undefined;
    clearTimeout(active.itemOptions?.timer);
    clearTimeout(active.referenceOptions?.timer);
    const input = td.querySelector('.inventory-cell-editor > input,.inventory-cell-editor > select');
    const value = active.referenceOptions ? (active.referenceOptions.chosenValue ?? active.original) : input ? (input.type === 'datetime-local' ? inputDateValue(input) : input.value) : active.original;
    this._closedTarget = save && !sameValue(value, active.original) ? active.target : null;
    this._closedContext = this._closedTarget ? { fieldLabel: active.fieldLabel, editorType: active.editorType,
      ...(active.referenceOptions ? { display: active.referenceOptions.chosenLabel } : {}) } : null;
    this._active = null;
    td.classList.remove('inventory-cell-editing');
    this._present(td, x, y, save ? value : active.original);
    if (!save) this._scheduleRender();
    queueMicrotask(() => { this._attemptsChanged(); this._moveSelection(); });
    return save ? value : active.original;
  }
  _save(x, y, value) {
    const location = this._cell(x, y), target = this._closedTarget || location.target;
    const context = this._closedContext || { fieldLabel: this._model.columns[x]?.label, editorType: location.editor?.type };
    const cell = this._targetCells().findLast(candidate => pathKey(candidate.target) === pathKey(target));
    this._closedTarget = null; this._closedContext = null;
    if (!target || (cell && sameValue(value, cell.value))) { this._scheduleRender(); return; }
    const conflict = cell && versionKey(cell.target) !== versionKey(target);
    this._acknowledgedWrite = null;
    this._attempts.set(pathKey(target), { target: { ...target }, value: String(value ?? ''), ...context,
      state: !cell && !this._model.viewport ? 'orphan' : conflict ? 'conflict' : 'saving', ...(conflict ? { saved: cell.value, currentTarget: cell.target } : {}) });
    this._attemptsChanged();
    if (cell || this._model.viewport) this._emit(target.type || 'draft-edit', { ...target, value: String(value ?? '') });
    this._scheduleRender();
  }
  _saveItem(cell, value, display) {
    const target = cell.itemTarget || cell.editor?.itemTarget;
    if (!target) return;
    const current = this._targetCells().findLast(candidate => pathKey(candidate.target) === pathKey(cell.target));
    if (current && sameValue(current.itemValue, value)) return;
    const conflict = current && versionKey(current.itemTarget) !== versionKey(target);
    this._acknowledgedWrite = null;
    this._attempts.set(pathKey(cell.target), { target: { ...target }, value, display, item: true, state: !current && !this._model.viewport ? 'orphan' : conflict ? 'conflict' : 'saving',
      fieldLabel: t('Goods'), editorType: 'goods', ...(conflict ? { saved: current.value, currentTarget: current.itemTarget } : {}) });
    this._attemptsChanged();
    if (current || this._model.viewport) this._emit(target.type || 'draft-edit', { ...target, value }); this._scheduleRender();
  }
  _moveSelection() {
    const next = this._nextSelection; this._nextSelection = null;
    if (!next || !this.worksheet) return;
    const width = this._model.columns.length, height = this._rows.length;
    let index = next.y * width + next.x;
    const step = (next.backwards ? -1 : 1) * (next.vertical ? width : 1);
    for (let count = 0; count < width * height; count++) {
      index += step;
      if (index < 0 || index >= width * height) break;
      const x = index % width, y = Math.floor(index / width), cell = this._cell(x, y);
      if (this._rows[y]?.spacer) {
        const source = this._rows[next.y];
        if (Number.isInteger(source?.rowIndex)) this._navigateWindow(source.rowIndex + (next.backwards ? -1 : 1), this._model.columns[x].key);
        break;
      }
      const td = this.worksheet.getCellFromCoords(x, y);
      if (cell.target && td?.style.display !== 'none') { this.worksheet.updateSelectionFromCoords(x, y, x, y); break; }
    }
  }
  _singleSelection() {
    const selected = this.worksheet?.selectedCell?.map(Number);
    if (!selected) return null;
    const [x, y, endX, endY] = selected;
    if (x === endX && y === endY) return { x, y };
    const merge = this._merges.find(item => item.col === x && item.row === y && x + (item.colspan || 1) - 1 === endX && y + (item.rowspan || 1) - 1 === endY);
    return merge ? { x, y } : null;
  }
  _paste(event) {
    if (!this._ownsEvent(event)) return;
    if (this._active) return;
    if (event.target.closest?.('button,input,select,textarea,a')) return;
    event.preventDefault(); event.stopImmediatePropagation();
    const value = event.clipboardData?.getData('text/plain') ?? '';
    if (/[\t\r\n]/.test(value)) { this._notice.textContent = t('Paste one cell at a time.'); return; }
    const selected = this._singleSelection();
    if (!selected) return;
    const { x, y } = selected;
    if (!this._editable(this._cell(x, y))) return;
    this._notice.textContent = ''; this._save(x, y, value);
  }
  _key(event) {
    if (!this._ownsEvent(event)) return;
    if (this._active) return;
    if (event.target.closest?.('button,input,select,textarea,a')) return;
    if (this._model.viewport && ['ArrowUp', 'ArrowDown', 'Tab'].includes(event.key)) {
      const selected = this._singleSelection();
      if (selected) {
        const direction = event.key === 'ArrowUp' || (event.key === 'Tab' && event.shiftKey) ? -1 : 1;
        const width = this._model.columns.length;
        let x = selected.x, y = selected.y;
        if (event.key === 'Tab') { x += direction; if (x < 0 || x >= width) { x = x < 0 ? width - 1 : 0; y += direction; } }
        else {
          const merge = this._merges.find(merge => merge.row === y && merge.col === x);
          y += direction > 0 ? (merge?.rowspan || 1) : -1;
        }
        while (this._rows[y]?.detail || this._rows[y]?.cells.some(cell => cell.operationDetail)) y += direction;
        if (this._rows[y]?.spacer) {
          event.preventDefault(); event.stopImmediatePropagation();
          this._navigateWindow(direction > 0 ? this._model.viewport.end : this._model.viewport.start - 1, this._model.columns[x].key);
          return;
        }
      }
    }
    if ((event.ctrlKey || event.metaKey) && ['z', 'y'].includes(event.key.toLowerCase())) {
      event.preventDefault(); event.stopImmediatePropagation();
      this._notice.textContent = t('Use the toolbar to revert pending changes.');
    }
    if (this._rowDetail && ['ArrowUp', 'ArrowDown', 'Tab'].includes(event.key)) {
      const selected = this._singleSelection(), width = this._model.columns.length;
      if (selected) {
        let { x, y } = selected;
        const direction = event.key === 'ArrowUp' || (event.key === 'Tab' && event.shiftKey) ? -1 : 1;
        if (event.key === 'Tab') { x += direction; if (x < 0 || x >= width) { x = x < 0 ? width - 1 : 0; y += direction; } }
        else y += direction;
        if (this._rows[y]?.detail) {
          y += direction;
          if (this._rows[y]) {
            event.preventDefault(); event.stopImmediatePropagation();
            this.worksheet.updateSelectionFromCoords(x, y, x, y);
          }
        }
      }
    }
    if (event.key === 'Enter') {
      const selected = this._singleSelection();
      if (selected && this._editable(this._cell(selected.x, selected.y))) {
        event.preventDefault(); event.stopImmediatePropagation();
        this.worksheet.openEditor(this.worksheet.getCellFromCoords(selected.x, selected.y), false, event);
      }
    }
    if (['Backspace', 'Delete'].includes(event.key)) {
      event.preventDefault(); event.stopImmediatePropagation();
      const selected = this._singleSelection();
      if (!selected) return;
      const { x, y } = selected;
      if (this._editable(this._cell(x, y))) this._save(x, y, '');
    }
  }
  _navigateWindow(rowIndex, column) {
    if (rowIndex < 0 || rowIndex >= this._model.viewport.totalRows) return;
    this._windowAnchor = this._captureAnchor();
    const request = this._requestWindow(this._model.viewport, Math.max(0, rowIndex - 66));
    this._pendingNavigation = { rowIndex, column, requestId: request?.requestId };
  }
}
if (!customElements.get('inventory-grid')) customElements.define('inventory-grid', InventoryGrid);

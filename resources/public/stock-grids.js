import './vendor/revogrid/revo-grid.esm.js';
import { T as TextEditor } from './vendor/revogrid/text-editor-BVb0idxe.js';
import { h } from './vendor/revogrid/index-CtimkLsB.js';
import './stock-operations.js?v=lineage-12';
import { attribute, mergePatch } from './datastar.js';
import { renderOperationDetails } from './operation-details.js?v=lineage-12';
import { renderLineage } from './operation-lineage.js?v=lineage-12';

await customElements.whenDefined('revo-grid');

function mountStockGrid(grid) {
  const lifecycle = new AbortController();
  const on = (target, name, listener, options = {}) => target.addEventListener(name, listener, { ...options, signal: lifecycle.signal });
  const expandedHistory = new Set();
  const historyHeights = new Map();
  const expandedOperationDetails = new Map();
  const historyViews = new Map();
  const observedHistory = new Set();
  const editors = new Set();
  let operations = [];
  let historyFrame = null;
  let activeGrid = grid;

  const belongsToStock = event => event.target.closest?.('revo-grid') === grid;

  function activateStockGrid(event) {
    const targetGrid = event.target.closest?.('revo-grid');
    if (event.target.closest?.('.sheet-inline-history')) activeGrid = null;
    else if (targetGrid) activeGrid = targetGrid;
  }

  for (const name of ['pointerdown', 'focusin']) {
    on(document, name, activateStockGrid, { capture: true });
  }

  function rowOperations(rowId) {
    return operations.filter(operation => operation.rowId === rowId || operation.effects?.some(effect => effect.rowId === rowId));
  }

  function syncHistoryRows() {
    historyFrame = null;
    if (!grid.isConnected) return;
    const definitions = (grid.source || []).flatMap((row, index) => expandedHistory.has(row.id)
      ? [{ type: 'rgRow', index, size: 32 + (historyHeights.get(row.id) ?? 70 + rowOperations(row.id).length * 40) }]
      : []);
    if (JSON.stringify(grid.rowDefinitions) !== JSON.stringify(definitions)) grid.rowDefinitions = definitions;
  }

  function scheduleHistoryRows() {
    if (historyFrame === null) historyFrame = requestAnimationFrame(syncHistoryRows);
  }

  Object.defineProperty(grid, 'operations', {
    configurable: true,
    get: () => operations,
    set(value) {
      const entries = Array.isArray(value) ? value : [];
      if (JSON.stringify(entries) === JSON.stringify(operations)) return;
      operations = entries;
      scheduleHistoryRows();
      grid.refresh('rgRow');
    },
  });

  function toggleHistory(rowId) {
    if (expandedHistory.has(rowId)) expandedHistory.delete(rowId);
    else expandedHistory.add(rowId);
    syncHistoryRows();
    grid.refresh('rgRow');
  }

  function historyToggle(h, { model }) {
    const expanded = expandedHistory.has(model.id);
    return h('span', { class: 'sheet-goods-value' },
      h('button', {
        type: 'button',
        class: 'sheet-history-toggle',
        'aria-label': `${expanded ? 'Hide' : 'Show'} operations for ${model.name || 'new row'}`,
        'aria-expanded': String(expanded),
        'aria-controls': `sheet-history-${encodeURIComponent(model.id)}`,
        title: `${expanded ? 'Hide' : 'Show'} row history`,
        onMouseDown: event => { event.preventDefault(); event.stopPropagation(); },
        onDblClick: event => event.stopPropagation(),
        onClick: event => { event.stopPropagation(); toggleHistory(model.id); },
      }, h('svg', { viewBox: '0 0 16 16', 'aria-hidden': 'true' },
        h('path', { d: expanded ? 'm4 6 4 4 4-4' : 'm6 4 4 4-4 4' }))),
      h('span', { class: 'sheet-goods-name' }, model.name));
  }

  const historyObserver = new ResizeObserver(entries => {
    for (const { target } of entries) {
      const height = Math.ceil(target.getBoundingClientRect().height);
      if (height && historyHeights.get(target.dataset.rowId) !== height) {
        historyHeights.set(target.dataset.rowId, height);
        scheduleHistoryRows();
      }
    }
  });

  const historyDate = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  const operationTime = entry => Date.parse(entry.effectiveAt || entry.recordedAt || '') || 0;
  const operationType = entry => ({
    opening: 'Opening stock', 'opening-declaration': 'Opening stock', adjustment: 'Data edit',
    'manual-edit': 'Data edit', 'data-edit': 'Data edit', data_edit: 'Data edit',
    transfer: 'Transfer', production: 'Production', purchase: 'Purchase', sale: 'Sale',
  }[entry.kind || entry.type] || String(entry.type || 'Operation').replaceAll('_', ' ').replace(/^./, letter => letter.toUpperCase()));

  function historyElement(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content != null) node.textContent = String(content);
    return node;
  }

  function renderInlineHistory(target, model) {
    const entries = rowOperations(model.id).sort((left, right) => operationTime(right) - operationTime(left));
    const expanded = expandedOperationDetails.get(model.id) || new Set();
    const view = historyViews.get(model.id) || 'table';
    target._historyModel = model;
    if (!target.querySelector('.sheet-history-heading')) {
      const heading = historyElement('div', 'sheet-history-heading');
      heading.append(historyElement('span', '', 'Stock line history'), historyElement('span', 'sheet-history-count'));
      const views = historyElement('div', 'sheet-history-views');
      views.setAttribute('role', 'group');
      views.setAttribute('aria-label', 'Stock history view');
      for (const [value, label] of [['table', 'Table'], ['lineage', 'Lineage']]) {
        const button = historyElement('button', '', label);
        button.type = 'button';
        button.dataset.view = value;
        button.addEventListener('click', event => {
          event.stopPropagation();
          historyViews.set(target._historyModel.id, value);
          renderInlineHistory(target, target._historyModel);
        });
        views.append(button);
      }
      heading.append(views);
      target.replaceChildren(heading, historyElement('div', 'sheet-history-body'));
    }
    target.querySelector('.sheet-history-count').textContent = `${entries.length} ${entries.length === 1 ? 'operation' : 'operations'} · This stock line`;
    for (const button of target.querySelectorAll('.sheet-history-views button')) {
      button.setAttribute('aria-pressed', String(button.dataset.view === view));
    }
    const rows = (grid.source || []).map(({ id, name, article, quantity, unit, location, batchId }) => ({ id, name, article, quantity, unit, location, batchId }));
    const signature = JSON.stringify([model.name, entries, [...expanded], view, operations, view === 'lineage' ? rows : null]);
    if (target._historySignature === signature) return;
    target._historySignature = signature;
    const body = target.querySelector('.sheet-history-body');
    if (view === 'lineage') {
      body.replaceChildren(renderLineage(operations, { rowId: model.id, rows: grid.source || [] }));
      return;
    }
    const content = [];
    for (const entry of entries) {
      const operation = historyElement('div', 'sheet-history-operation');
      operation.dataset.operationId = entry.id;
      const open = expanded.has(entry.id);
      const type = operationType(entry);
      const button = historyElement('button', 'sheet-history-operation-toggle');
      button.type = 'button';
      button.setAttribute('aria-expanded', String(open));
      button.setAttribute('aria-label', `${open ? 'Hide' : 'Show'} ${type.toLocaleLowerCase()} details`);
      const detailId = `sheet-history-${encodeURIComponent(model.id)}-${encodeURIComponent(entry.id)}`;
      button.setAttribute('aria-controls', detailId);
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('viewBox', '0 0 16 16');
      icon.setAttribute('aria-hidden', 'true');
      const path = document.createElementNS(icon.namespaceURI, 'path');
      path.setAttribute('d', open ? 'm4 6 4 4 4-4' : 'm6 4 4 4-4 4');
      icon.append(path);
      const time = historyElement('time', 'sheet-history-date', operationTime(entry) ? historyDate.format(new Date(operationTime(entry))) : '—');
      if (operationTime(entry)) time.dateTime = new Date(operationTime(entry)).toISOString();
      const label = historyElement('span', 'sheet-history-type', type);
      if (entry.example) label.append(historyElement('small', 'sheet-history-badge', 'Demo'));
      else if (entry.local) label.append(historyElement('small', 'sheet-history-badge', 'Local'));
      const ownEffects = (entry.effects || []).filter(effect => effect.rowId === model.id);
      const effects = ownEffects.filter(effect => effect.role !== 'edited').map(effect => [
        { consumed: 'Consumed', produced: 'Produced', source: 'Sent', destination: 'Received', sold: 'Sold', returned: 'Returned', edited: 'Data changed' }[effect.role]
          || (effect.direction === 'out' ? 'Outgoing' : 'Incoming'), effect.quantity, effect.unit, effect.before != null && effect.after != null ? `· ${effect.before} → ${effect.after}` : '',
      ].filter(value => value != null && value !== '').join(' ')).join(' · ');
      const change = effects || (entry.changes || []).map(change => `${change.field}: ${change.before || '—'} → ${change.after || '—'}`).join(' · ') || entry.summary || entry.name || 'Stock change';
      const locations = [...new Set((entry.effects || []).map(effect => effect.location).filter(Boolean))].join(' → ');
      const context = (typeof entry.context === 'string' ? entry.context : '') || entry.route || locations || entry.location || '—';
      const summary = historyElement('span', 'sheet-history-effect is-current', change);
      button.append(icon, time, label, historyElement('span', 'sheet-history-context', context), summary);
      button.addEventListener('click', event => {
        event.stopPropagation();
        if (expanded.has(entry.id)) expanded.delete(entry.id);
        else expanded.add(entry.id);
        expandedOperationDetails.set(model.id, expanded);
        renderInlineHistory(target, model);
      });
      operation.append(button);
      if (open) {
        const detail = historyElement('div', 'sheet-history-operation-detail');
        detail.id = detailId;
        detail.append(renderOperationDetails(entry, { rowId: model.id, entries: operations }));
        operation.append(detail);
      }
      content.push(operation);
    }
    if (!entries.length) content.push(historyElement('div', 'sheet-history-empty', 'No operations yet.'));
    body.replaceChildren(...content);
  }

  on(grid, 'beforerowrender', event => {
    if (!belongsToStock(event)) return;
    const { model, node, colType, rowType } = event.detail;
    if (rowType !== 'rgRow' || colType !== 'rgCol' || !expandedHistory.has(model.id)) return;
    node.$children$.push(h('div', {
      key: `history-${model.id}`,
      id: `sheet-history-${encodeURIComponent(model.id)}`,
      class: 'sheet-inline-history',
      'data-row-id': model.id,
      role: 'region',
      'aria-label': `Operations for ${model.name || 'new row'}`,
      onMouseDown: event => event.stopPropagation(),
      onDblClick: event => event.stopPropagation(),
      ref: target => {
        if (!target) return;
        renderInlineHistory(target, model);
        if (!observedHistory.has(target)) {
          observedHistory.add(target);
          historyObserver.observe(target);
        }
      },
    }));
  });

  on(grid, 'afterrender', event => {
    if (!belongsToStock(event)) return;
    for (const target of observedHistory) {
      if (target.isConnected && expandedHistory.has(target.dataset.rowId)) continue;
      historyObserver.unobserve(target);
      observedHistory.delete(target);
    }
  });
  on(grid, 'afteranysource', event => {
    if (belongsToStock(event)) scheduleHistoryRows();
  });


  class StockValueEditor extends TextEditor {
    constructor(data, saveCallback, cancelCallback) {
      super(data, saveCallback);
      editors.add(this);
      this.cancelCallback = cancelCallback;
      this.options = [];
      this.activeOption = 0;
      this.positionOptions = () => {
        if (!this.menu || !this.editInput) return;
        const bounds = this.editInput.getBoundingClientRect();
        const width = Math.min(Math.max(bounds.width, 200), innerWidth - 16);
        this.menu.style.width = `${width}px`;
        this.menu.style.left = `${Math.max(8, Math.min(bounds.left, innerWidth - width - 8))}px`;
        this.menu.style.top = `${bounds.bottom + 3}px`;
        if (this.menu.getBoundingClientRect().bottom > innerHeight - 8) {
          this.menu.style.top = `${Math.max(8, bounds.top - this.menu.offsetHeight - 3)}px`;
        }
      };
    }

    async componentDidRender() {
      await super.componentDidRender();
      if (!this.editInput?.isConnected) return;
      this.editInput.setAttribute('role', 'combobox');
      this.editInput.setAttribute('aria-autocomplete', 'list');
      this.editInput.setAttribute('autocomplete', 'off');
      this.updateOptions();
      window.addEventListener('resize', this.positionOptions);
      window.addEventListener('scroll', this.positionOptions, true);
    }

    appendPendingInput(value) {
      const appended = super.appendPendingInput(value);
      if (appended) this.updateOptions();
      return appended;
    }

    updateOptions() {
      const value = this.getValue() ?? '';
      const query = value.toLocaleLowerCase();
      const known = [...new Set(grid.source.filter(row => !row._deleted)
        .map(row => String(row[this.data.prop] ?? '')).filter(Boolean))];
      this.options = known.filter(option => option.toLocaleLowerCase().includes(query))
        .slice(0, 8).map(value => ({ value }));
      if (value && !known.some(option => option.toLocaleLowerCase() === query)) {
        this.options.push({ value, create: true });
      }
      this.activeOption = 0;
      this.renderOptions();
    }

    renderOptions() {
      if (!this.options.length) {
        this.removeOptions();
        return;
      }
      if (!this.menu) {
        this.menu = document.createElement('div');
        this.menu.className = 'sheet-cell-options';
        this.menu.id = `sheet-options-${crypto.randomUUID()}`;
        this.menu.setAttribute('role', 'listbox');
        this.menu.setAttribute('aria-label', `${this.data.column.name} suggestions`);
        this.menu.addEventListener('pointerdown', event => event.preventDefault());
        this.menu.addEventListener('mousedown', event => event.stopPropagation());
        document.querySelector('#inventory-evidence-workbench').append(this.menu);
      }
      this.menu.replaceChildren(...this.options.map((option, index) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.tabIndex = -1;
        button.id = `${this.menu.id}-${index}`;
        button.setAttribute('role', 'option');
        button.setAttribute('aria-selected', String(index === this.activeOption));
        button.className = index === this.activeOption ? 'is-active' : '';
        button.textContent = option.value;
        if (option.create) {
          const hint = document.createElement('small');
          hint.className = 'sheet-option-hint';
          hint.textContent = 'New value';
          button.append(hint);
        }
        button.addEventListener('click', event => {
          event.stopPropagation();
          this.saveOption(option.value);
        });
        return button;
      }));
      this.editInput.setAttribute('aria-expanded', 'true');
      this.editInput.setAttribute('aria-controls', this.menu.id);
      this.editInput.setAttribute('aria-activedescendant', `${this.menu.id}-${this.activeOption}`);
      this.positionOptions();
      this.menu.children[this.activeOption]?.scrollIntoView({ block: 'nearest' });
    }

    saveOption(value) {
      this.editInput.value = value;
      this.beforeDisconnect();
      this.saveCallback(value, false);
    }

    onKeyDown(event) {
      if (event.isComposing) return;
      if (['ArrowDown', 'ArrowUp'].includes(event.key) && this.options.length) {
        event.preventDefault();
        event.stopPropagation();
        this.activeOption = (this.activeOption + (event.key === 'ArrowDown' ? 1 : -1) + this.options.length) % this.options.length;
        this.renderOptions();
      } else if (event.key === 'Enter') {
        event.preventDefault();
        event.stopPropagation();
        this.saveOption(this.options[this.activeOption]?.value ?? this.getValue());
      } else if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        this.beforeDisconnect();
        this.cancelCallback(false);
      } else {
        super.onKeyDown(event);
      }
    }

    removeOptions() {
      this.menu?.remove();
      this.menu = null;
      this.editInput?.setAttribute('aria-expanded', 'false');
      this.editInput?.removeAttribute('aria-activedescendant');
    }

    beforeDisconnect() {
      editors.delete(this);
      this.removeOptions();
      window.removeEventListener('resize', this.positionOptions);
      window.removeEventListener('scroll', this.positionOptions, true);
      super.beforeDisconnect();
    }

    disconnectedCallback() {
      this.beforeDisconnect();
    }

    render(h) {
      return h('div', { class: 'sheet-cell-editor', style: { height: '100%' } }, h('input', {
        type: 'text',
        value: this.editCell?.val ?? '',
        ref: element => { this.editInput = element; },
        onInput: () => this.updateOptions(),
        onKeyDown: event => this.onKeyDown(event),
      }));
    }
  }

  const quantityOrder = new Intl.Collator(undefined, { numeric: true });
  Object.assign(grid, {
    columns: [
      { prop: 'name', name: 'Goods', size: 310, sortable: true, editor: StockValueEditor, cellTemplate: historyToggle },
      {
        prop: 'quantity', name: 'Quantity', size: 110, sortable: true,
        cellProperties: () => ({ class: 'sheet-number' }),
        cellCompare: (prop, a, b) => quantityOrder.compare(a[prop], b[prop]),
      },
      { prop: 'unit', name: 'Unit', size: 90, sortable: true, editor: StockValueEditor },
      { prop: 'location', name: 'Location', size: 190, sortable: true, editor: StockValueEditor },
      { prop: 'article', name: 'Article', size: 150, sortable: true, editor: StockValueEditor },
      { prop: 'evidence', name: 'Evidence', size: 220, sortable: true },
    ].map(column => ({
      ...column,
      readonly: ({ model }) => column.prop === 'evidence' || model._deleted,
      cellProperties: model => ({ ...column.cellProperties?.(model), style: { height: '32px' } }),
    })),
    rowHeaders: {
      size: 44,
      cellProperties: ({ model, rowIndex }) => ({ style: { height: '32px' }, onClick: () => selectRow(model.id, rowIndex) }),
    },
    rowClass: '_changeClass',
    rowSize: 32,
    // Keep native header sorting when layout changes rebuild column definitions.
    sorting: { columns: [] },
    theme: 'default',
    range: true,
    resize: true,
    useClipboard: { rangeFill: true },
    applyOnClose: true,
  });

  async function fitGridHeight() {
    if (!grid.isConnected) return;
    const { y } = await grid.getContentSize();
    if (!grid.isConnected) return;
    const style = getComputedStyle(grid);
    const header = parseFloat(style.getPropertyValue('--revo-grid-header-height') || '34');
    const scrollbar = grid.querySelector('revogr-scroll-virtual.horizontal')?.offsetHeight ?? 0;
    const borders = parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);
    const height = `${Math.ceil(y + header + scrollbar + borders)}px`;
    if (grid.style.height !== height) grid.style.height = height;
    const maxHeight = `${Math.floor(Math.max(header + borders, innerHeight - grid.getBoundingClientRect().top - 1))}px`;
    if (grid.style.maxHeight !== maxHeight) grid.style.maxHeight = maxHeight;
  }

  const layoutObserver = new ResizeObserver(fitGridHeight);
  layoutObserver.observe(grid.closest('.stock-sheet'));
  on(window, 'resize', fitGridHeight);

  for (const event of ['aftergridrender', 'contentsizechanged', 'afteranysource', 'aftertrimmed']) {
    on(grid, event, native => {
      if (belongsToStock(native)) fitGridHeight();
    });
  }

  let focusFrame = null;
  let rowToFocus = null;
  let focusingRow = false;
  async function focusAddedRow() {
    focusFrame = null;
    if (!grid.isConnected || !rowToFocus || focusingRow) return;
    focusingRow = true;
    try {
      const rows = await grid.getVisibleSource();
      if (!grid.isConnected) return;
      const rowIndex = rows.findIndex(row => row.id === rowToFocus.rowId);
      if (rowIndex < 0) return;
      const { edit } = rowToFocus;
      rowToFocus = null;
      await grid.scrollToRow(rowIndex);
      if (!grid.isConnected) return;
      if (edit) {
        document.activeElement?.blur();
        await grid.scrollToColumnProp('name');
        await grid.setCellsFocus({ x: 0, y: rowIndex }, { x: 0, y: rowIndex });
        await grid.setCellEdit(rowIndex, 'name');
      }
    } finally {
      focusingRow = false;
    }
  }

  on(grid, 'stock-row-added', event => {
    activeGrid = grid;
    rowToFocus = event.detail;
    if (focusFrame !== null) cancelAnimationFrame(focusFrame);
    focusFrame = requestAnimationFrame(focusAddedRow);
  });
  on(grid, 'aftergridrender', event => {
    if (belongsToStock(event)) focusAddedRow();
  });

  // Native cell selection keeps DOM focus; release an outside input so grid shortcuts use body focus.
  on(grid, 'pointerdown', event => {
    if (event.target.closest?.('revo-grid') !== grid) return;
    const focused = document.activeElement;
    if (event.target.closest?.('.rgCell') && focused && focused !== document.body && !grid.contains(focused)) focused.blur();
  });

  // RevoGrid listens on document; outside controls keep their keys while body focus still drives grid selection.
  on(grid, 'beforekeydown', event => {
    if (!belongsToStock(event)) return;
    const original = event.detail.original;
    if (activeGrid !== grid || original.target.closest?.('.sheet-inline-history,.sheet-history-toggle') ||
      (original.target !== document.body && !grid.contains(original.target))) {
      event.preventDefault();
      return;
    }
    if (!['Delete', 'Backspace'].includes(original.key)) return;
    if (editing || pendingEdit || grid.querySelector('revogr-edit')) {
      event.preventDefault();
      return;
    }
    if (grid.readonly) return;
    if (selectedRowIds.length || original.metaKey || original.ctrlKey) {
      event.preventDefault();
      original.preventDefault();
      deleteRows([...selectedRowIds]);
    }
  });

  let editing = false;
  let editorMounted = false;
  let pendingEdit = null;
  let selectedRowIds = [];

  function dispatch(name, detail) {
    if (grid.isConnected) grid.dispatchEvent(new CustomEvent(name, { bubbles: true, detail }));
  }

  async function selectRow(rowId, rowIndex) {
    selectedRowIds = [rowId];
    await grid.setCellsFocus({ x: 0, y: rowIndex }, { x: grid.columns.length - 1, y: rowIndex });
    dispatch('rows-select', { rowIds: selectedRowIds });
  }

  on(grid, 'beforecellfocus', event => {
    if (!belongsToStock(event)) return;
    if (selectedRowIds.length) {
      selectedRowIds = [];
      dispatch('rows-select', { rowIds: [] });
    }
  });

  async function deleteRows(selection) {
    const rows = await grid.getVisibleSource();
    const focused = selection.length ? null : await grid.getFocused();
    const rowIds = selection.length
      ? rows.filter(row => selection.includes(row.id)).map(row => row.id)
      : focused?.model?.id ? [focused.model.id] : [];
    if (rowIds.length && !grid.readonly && !editing && !pendingEdit && !grid.querySelector('revogr-edit')) {
      dispatch('rows-delete', { rowIds });
    }
  }

  function setEditing(value) {
    if (editing === value) return;
    editing = value;
    dispatch('grid-editing', { editing });
  }

  function finishEditing() {
    if (!pendingEdit && !grid.querySelector('revogr-edit')) setEditing(false);
  }

  grid.cancelStockEdit = async () => {
    rowToFocus = null;
    await grid.querySelector('revogr-edit')?.cancelChanges();
    await grid.clearFocus();
    await new Promise(requestAnimationFrame);
    if (pendingEdit) await pendingEdit.done;
    finishEditing();
  };

  on(grid, 'beforeeditstart', event => {
    if (!belongsToStock(event)) return;
    setEditing(true);
    queueMicrotask(() => {
      if (event.defaultPrevented) finishEditing();
    });
  });

  function beginEdit(event, change) {
    change.done = new Promise(resolve => { change.complete = resolve; });
    pendingEdit = change;
    setEditing(true);
    queueMicrotask(() => {
      if (event.defaultPrevented && pendingEdit === change) {
        pendingEdit = null;
        change.complete();
        finishEditing();
      }
    });
  }

  on(grid, 'beforeedit', event => {
    if (!belongsToStock(event)) return;
    const { model, prop, val } = event.detail;
    if (model._deleted || prop === 'evidence') {
      event.preventDefault();
      return;
    }
    beginEdit(event, prop === 'quantity'
      ? { type: 'quantity-preview', detail: { rowId: model.id, quantity: val ?? '' } }
      : { type: 'cells-draft', detail: { changes: { [model.id]: { [prop]: val ?? '' } } } });
  });

  on(grid, 'beforerangeedit', event => {
    if (!belongsToStock(event)) return;
    const changes = Object.entries(event.detail.data);
    if (changes.some(([, values]) => Object.hasOwn(values, 'quantity'))) {
      const [rowIndex, values] = changes[0];
      if (changes.length !== 1 || Object.keys(values).length !== 1) {
        event.preventDefault();
        dispatch('stock-edit-error', { message: 'Preview one quantity at a time.' });
        return;
      }
      beginEdit(event, { type: 'quantity-preview', detail: {
        rowId: event.detail.models[rowIndex].id, quantity: values.quantity ?? '',
      } });
    } else if (changes.length) {
      beginEdit(event, { type: 'cells-draft', detail: { changes: Object.fromEntries(
        changes.map(([rowIndex, values]) => [event.detail.models[rowIndex].id,
          Object.fromEntries(Object.entries(values).map(([prop, value]) => [prop, value ?? ''])),
        ]),
      ) } });
    }
  });

  on(grid, 'afteredit', event => {
    if (!belongsToStock(event)) return;
    if (pendingEdit?.type === 'quantity-preview') {
      const row = grid.source.find(row => row.id === pendingEdit.detail.rowId);
      if (row) row._changeClass = 'sheet-row-modified';
    }
    if (pendingEdit) {
      dispatch(pendingEdit.type, pendingEdit.detail);
      pendingEdit.complete();
    }
    pendingEdit = null;
    finishEditing();
  });

  // Source changes wait until the native editor is gone and its asynchronous write has finished.
  const editorObserver = new MutationObserver(() => {
    if (grid.querySelector('revogr-edit')) {
      editorMounted = true;
      setEditing(true);
    } else if (editorMounted) {
      editorMounted = false;
      finishEditing();
    }
  });
  editorObserver.observe(grid, { childList: true, subtree: true });

  return () => {
    lifecycle.abort();
    historyObserver.disconnect();
    layoutObserver.disconnect();
    editorObserver.disconnect();
    observedHistory.clear();
    if (historyFrame !== null) cancelAnimationFrame(historyFrame);
    if (focusFrame !== null) cancelAnimationFrame(focusFrame);
    for (const editor of editors) editor.beforeDisconnect();
    grid.closest('#inventory-evidence-workbench')?.querySelectorAll('.sheet-cell-options').forEach(element => element.remove());
  };
}

attribute({ name: 'stock-grid', apply: ({ el }) => mountStockGrid(el) });
attribute({ name: 'inventory', apply: ({ el }) => {
  const signals = JSON.parse(el.getAttribute('data-signals__ifmissing') || '{}');
  return () => {
    if (!el.isConnected) mergePatch(Object.fromEntries(Object.keys(signals).map(key => [key, null])));
  };
} });

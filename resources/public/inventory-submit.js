import { t } from './i18n.js';
import { toISOString } from './dates.js';

// ponytail: per-browser preferences; per-member preferences move to chapter 30.
export const prefs = {
  get(key, fallback) { try { return localStorage.getItem(`tover.inventory.${key}`) ?? fallback; } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem(`tover.inventory.${key}`, value); } catch { /* storage unavailable */ } },
};

export const element = (tag, props = {}, ...children) => {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children.filter(child => child !== null && child !== undefined));
  return node;
};

// The stock and operations pages share the shared-draft save queue, Preview selection, Submit (time and reason,
// review, order choice) and acceptance. A subclass sets the grid and controls, and provides refreshTable,
// showModel and leavePreview.
export class InventoryWorkbench extends HTMLElement {
  get saveEvent() { return 'stock-save'; }
  // The Datastar effect reassigns every response whenever any signal changes; each answer is handled once.
  fresh({ requestId }) {
    this.handled ||= new Set();
    if (this.handled.has(requestId)) return false;
    this.handled.add(requestId); return true;
  }
  emit(name, detail) { this.dispatchEvent(new CustomEvent(name, { bubbles: true, detail })); }
  setStatus(status, message = '') {
    this.status.textContent = status === 'Saved' ? '' : t(status);
    this.grid.saveStatus = status;
    this.notice.textContent = message;
    this.notice.setAttribute('role', status === 'Failed' ? 'alert' : 'status');
  }
  retryNotice(node, retry) {
    const button = element('button', { type: 'button', textContent: t('Retry') });
    button.addEventListener('click', retry);
    node.replaceChildren(document.createTextNode(`${t('Could not load stock.')} `), button);
  }
  // The stock and operations tables share units / money, sorting, filters, column layout and windows of rows.
  get prefix() { return 'stock'; }
  get modeKey() { return 'mode'; }
  setupTable() {
    this.mode = prefs.get(this.modeKey, 'units');
    this.order = { sort: null, direction: 'asc', filters: {} };
    for (const button of this.querySelectorAll('.stock-mode button')) {
      button.addEventListener('click', () => { if (this.scope.pending) return; this.mode = button.dataset.mode; prefs.set(this.modeKey, this.mode); this.showMode(); this.refreshTable(); });
    }
    this.addEventListener('inventory-sort', event => { this.order = { ...this.order, sort: event.detail.column, direction: event.detail.direction }; this.refreshTable(); });
    this.addEventListener('inventory-filter', event => this.setFilters(event.detail.filters));
    // Column order, widths and visibility are kept per browser (chapters 30 and 47 move them to the member).
    this.addEventListener('inventory-layout', event => {
      const saved = this.layout();
      this.saveLayout({ ...(event.detail.order ? { order: event.detail.order } : {}), widths: { ...(saved.widths ?? {}), ...(event.detail.widths ?? {}) },
        lastColumn: Object.keys(event.detail.widths ?? {}).at(-1) ?? saved.lastColumn });
      this.grid.layoutStatus = 'Saved';
    });
    this.picker = this.querySelector('.column-picker');
    document.addEventListener('pointerdown', event => { if (this.picker.open && !this.picker.contains(event.target)) this.picker.open = false; });
    this.picker.addEventListener('keydown', event => { if (event.key === 'Escape') { this.picker.open = false; this.picker.querySelector('summary').focus(); } });
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
  setFilters(filters) { this.order = { ...this.order, filters }; this.refreshTable(); }
  filterColumns() { return this.model.columns.filter(column => column.filterKey).map(column => ({ key: column.filterKey, label: column.label })); }
  columnValues(key) {
    const x = this.model.columns.findIndex(column => column.filterKey === key);
    return [...new Set(this.model.rows.map(row => row.cells[x]).map(cell => String(cell?.display ?? cell?.value ?? '')).filter(Boolean))];
  }
  // The active filters below the input, each removable.
  showFilters() {
    const strip = this.querySelector('.smart-filters');
    const active = Object.entries(this.order.filters).filter(([, conditions]) => conditions?.length);
    const names = { empty: 'Empty', notEmpty: 'Not empty', eq: 'Equal', notEq: 'Not equal', begins: 'Begins with', contains: 'Contains', notContains: 'Does not contain' };
    const remove = key => { const { [key]: _gone, ...rest } = this.order.filters; this.setFilters(rest); };
    strip.hidden = !active.length;
    strip.replaceChildren(...active.map(([key, conditions]) => {
      const label = this.model.columns.find(column => column.filterKey === key)?.label ?? key;
      const text = conditions.map((condition, index) => [t(names[condition.type] ?? condition.type).toLowerCase(), condition.value, index < conditions.length - 1 ? t(condition.relation === 'or' ? 'OR' : 'AND') : ''].filter(Boolean).join(' ')).join(' ');
      const button = element('button', { type: 'button', className: 'stock-entry-chip-remove', textContent: '×', ariaLabel: t('Remove filter: {p0}', { p0: label }) });
      button.addEventListener('click', () => remove(key));
      return element('span', { className: 'stock-entry-chip' },
        element('span', { className: 'stock-entry-chip-edit' }, element('span', { className: 'stock-entry-chip-label', textContent: `${label}: ` }), element('span', { className: 'stock-entry-chip-value', textContent: text })), button);
    }));
    if (active.length) {
      const reset = element('button', { type: 'button', className: 'smart-filters-reset', textContent: t('Reset all') });
      reset.addEventListener('click', () => this.setFilters({}));
      strip.append(reset);
    }
  }
  layout() {
    try { return JSON.parse(prefs.get(`${this.prefix}-layout`, '{}')) || {}; } catch { return {}; }
  }
  saveLayout(change) {
    const saved = this.layout();
    const layout = { order: [], widths: {}, hidden: [], ...saved, ...change, version: (saved.version ?? 0) + 1 };
    prefs.set(`${this.prefix}-layout`, JSON.stringify(layout));
    this.layoutFields = this.fieldsOf(layout);
    this.grid.columnLayout = layout;
  }
  fieldsOf(layout) {
    return { savedWidths: layout.widths ?? {}, columnOrder: layout.order ?? [], hiddenColumns: layout.hidden ?? [], layoutVersion: layout.version ?? 0, lastColumn: layout.lastColumn };
  }
  showColumns() {
    const hidden = new Set(this.layout().hidden ?? []);
    this.picker.querySelector('.column-picker-list').replaceChildren(...this.model.columns.filter(column => column.label).map(column => {
      const box = element('input', { type: 'checkbox', checked: column.required || !hidden.has(column.key), disabled: Boolean(column.required) });
      box.addEventListener('change', () => {
        const now = new Set(this.layout().hidden ?? []);
        if (box.checked) now.delete(column.key); else now.add(column.key);
        this.saveLayout({ hidden: [...now] });
      });
      return element('label', {}, box, element('span', { textContent: column.label }));
    }));
  }
  // Gives the grid the current model: its first window, the column layout and the order in force.
  presentModel(start = 0) {
    this.queryId = crypto.randomUUID();
    this.queryKey = JSON.stringify([this.scope.pending ? 'units' : this.mode, this.hideEmpty, Boolean(this.showArchived), this.scope, this.order]);
    this.layoutFields = this.fieldsOf(this.layout());
    this.grid.model = this.windowModel(start);
    this.grid.acceptedSort = this.order.sort ? { column: this.order.sort, direction: this.order.direction } : {};
    this.grid.acceptedFilters = this.order.filters;
    this.showColumns();
    this.showFilters();
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
  showSubmit() {
    const count = this.scope.pending ? (this.selected ?? []).length : 0;
    this.submitButton.disabled = !count;
    this.submitButton.title = !this.model.pending.length ? t('No pending changes')
      : !this.scope.pending ? t('Preview pending changes to submit them')
        : count ? t('Submit the selected changes') : t('Select the changes to submit');
  }
  set tableResponse(response) {
    if (!this.grid || !response || response.requestId !== this.tableRequest || !this.fresh(response)) return;
    if (!response.model) { this.retryNotice(this.notice, () => this.refreshTable()); return; }
    // A retry offer goes once the table loads; a save error stays until the next save.
    if (this.notice.querySelector('button')) this.notice.replaceChildren();
    this.showModel(response.model);
    const [id, queued] = this.queued.entries().next().value || [];
    if (id && !this.pending) { this.queued.delete(id); this.save(id, queued); }
  }
  set changed(value) {
    if (!this.grid || !value || value === this.lastChange) return;
    this.lastChange = value;
    if (!this.pending) this.refreshTable();
  }

  save(slot, { id, values, revision, target }) {
    const current = Math.max(revision, this.revisions.get(id) ?? 0);
    this.pending = { kind: 'save', id, slot, revision: current, values, commandId: crypto.randomUUID(), target };
    this.setStatus('Saving');
    this.emit(this.saveEvent, { workspaceId: this.workspaceId, entryId: id, revision: current, values, commandId: this.pending.commandId });
  }
  discard({ id, revision, line, shared, values, target }) {
    if (this.pending) return;
    // Other lines of the entry stay. A cleared cell is acknowledged like a save.
    if (shared) { this.save(line, { id, revision, values: { ...values, 'line-id': line, remove: 'true' }, target }); this.pending.kind = 'discard-line'; return; }
    this.pending = { kind: 'discard', id, commandId: crypto.randomUUID(), target };
    this.setStatus('Saving');
    this.emit('stock-discard', { workspaceId: this.workspaceId, entryId: id, revision, commandId: this.pending.commandId });
  }
  set saveResponse(response) {
    if (!this.pending || !response || response.commandId !== this.pending.commandId) return;
    const request = this.pending; this.pending = null;
    if (response.status === 'ok') {
      if (['save', 'discard-line', 'describe'].includes(request.kind)) this.revisions.set(request.id, request.revision + 1);
      if (['save', 'discard-line'].includes(request.kind)) this.values.set(request.slot, request.values);
      if (request.kind === 'describe') { this.forget(request.id); if (this.describe()) return; }
      if (request.target) this.grid.acknowledgeSave(request.target);
      // The new item now has its own row; the blank row starts over.
      if (request.target?.id.startsWith('new-row:')) for (const key of ['variant', 'article', 'barcodes', 'unit', 'unit-cost', 'category-id', 'store-id']) delete this.scope[key];
      if (request.kind === 'accept') {
        this.leavePreview();
      }
      this.setStatus('Saved');
    } else {
      this.setStatus('Failed', response.message);
    }
    // Fanned-out edits save one after another before the table is read again.
    const [id, queued] = this.queued.entries().next().value || [];
    if (id && request.kind === 'save' && response.status === 'ok') { this.queued.delete(id); this.save(id, queued); return; }
    if (request.kind.startsWith('discard') && response.status === 'ok' && this.discards?.length) { this.discard(this.discards.shift()); return; }
    this.refreshTable();
  }

  // Review and accept
  review() {
    if (this.pending || this.submitButton.disabled) return;
    // An entry is submitted whole: every row it touches must be included and complete.
    const left = new Set(this.model.pending.filter(entry => entry.blocked || !this.selected.includes(entry.rowId)).map(entry => entry['entry-id']));
    const rows = [...new Map(this.model.pending.filter(entry => !left.has(entry['entry-id'])).map(entry => [entry['entry-id'], entry])).values()];
    if (!rows.length) { this.setStatus('Failed', t('Select the changes to submit')); return; }
    const shown = new Set(this.model.rows.flatMap(row => [row.id, row.entry]));
    const hidden = this.selected.filter(id => !shown.has(id)).length;
    if (hidden && !window.confirm(t('The filter hides {p0} selected rows. Submit them too?', { p0: hidden }))) return;
    this.reviewed = rows.map(row => ({ 'entry-id': row['entry-id'], revision: row.revision }));
    this.requestReview();
  }
  // A row's occurrence time or reason is saved on each of its entries in turn. True while one is being saved.
  describeRow({ entries, field, value, target }) {
    if (this.pending) return;
    const details = field === 'occurred-at' ? { occurredAt: toISOString(value) || value } : { reason: value };
    this.describing = { details, target, entries: entries.map(entry => ({ 'entry-id': entry.entry, revision: entry.revision })) };
    this.describe();
  }
  describe() {
    const entry = this.describing?.entries.shift();
    if (!entry) {
      if (this.describing?.target) this.grid.acknowledgeSave(this.describing.target);
      this.describing = null; return false;
    }
    const id = entry['entry-id'];
    const revision = Math.max(entry.revision, this.revisions.get(id) ?? 0);
    this.pending = { kind: 'describe', id, revision, commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-describe', { workspaceId: this.workspaceId, entryId: id, revision, ...this.describing.details, commandId: this.pending.commandId });
    return true;
  }
  // A later cell edit must build on the described opening, not on this page's older values.
  forget(id) {
    for (const [slot] of this.values) if (slot.startsWith(id)) this.values.delete(slot);
  }
  requestReview() {
    const rows = this.reviewed;
    this.reviewRequest = crypto.randomUUID();
    this.emit('stock-review', { requestId: this.reviewRequest, selection: rows.map(row => ({ 'entry-id': row['entry-id'], revision: Math.max(row.revision, this.revisions.get(row['entry-id']) ?? 0) })), order: rows.map(row => row['entry-id']) });
  }
  set reviewResponse(response) {
    if (!this.grid || !response || response.requestId !== this.reviewRequest || !this.fresh(response)) return;
    const { review } = response;
    if (!review) { this.setStatus('Failed', t('Could not check the pending changes. Try again.')); return; }
    const ties = review.problems.filter(problem => problem.code === 'order-required');
    if (ties.length && ties.length === review.problems.length) { this.chooseOrder(response, ties); return; }
    if (!review['valid?']) { this.setStatus('Failed', review.problems.map(problem => problem.message).join(' ')); return; }
    this.pending = { kind: 'accept', commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-accept', { workspaceId: this.workspaceId, selection: response.selection, order: response.order, ordered: response.ordered, fingerprint: review.fingerprint, commandId: this.pending.commandId });
  }
  // Entries on one stock at the same second need the member's order (chapter 17): they are listed in the order
  // they would be accepted, each can move up, and the review runs again with the chosen order.
  chooseOrder(response, ties) {
    this.setStatus('Failed', [...new Set(ties.map(tie => tie.message))].join(' '));
    const tied = new Set(ties.flatMap(tie => tie.tied));
    const chosen = response.order.filter(id => tied.has(id));
    const list = element('ol', { className: 'stock-order' });
    const render = () => list.replaceChildren(...chosen.map((id, index) => {
      const up = element('button', { type: 'button', className: 'stock-order-up', textContent: t('Move up'), disabled: index === 0 });
      up.setAttribute('aria-label', `${t('Move up')}: ${response.entryNames?.[id] ?? id}`);
      up.addEventListener('click', () => { [chosen[index - 1], chosen[index]] = [chosen[index], chosen[index - 1]]; render(); list.querySelectorAll('button')[Math.max(0, index - 1)]?.focus(); });
      return element('li', {}, element('span', { textContent: response.entryNames?.[id] ?? id }), ' ', up);
    }));
    render();
    const accept = element('button', { type: 'button', className: 'stock-order-accept', textContent: t('Accept in this order') });
    accept.addEventListener('click', () => {
      let next = 0;
      const order = response.order.map(id => (tied.has(id) ? chosen[next++] : id));
      this.notice.replaceChildren();
      this.reviewRequest = crypto.randomUUID();
      this.emit('stock-review', { requestId: this.reviewRequest, selection: response.selection, order, ordered: true });
    });
    this.notice.append(list, accept);
  }

}

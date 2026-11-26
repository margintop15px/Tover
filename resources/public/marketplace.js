import { t } from './i18n.js';
import './inventory-grid.js';

// The marketplace table workbench (tover.marketplace.grid): server rows are authoritative; a cell edit stays on
// its row in the browser until a selection action sends the selected rows as one command (or, for an action
// marked data-each, one command per row in turn). A rejected command keeps every attempted value and shows each
// row's reason on that row.
document.addEventListener('datastar-fetch', event => {
  const workbench = event.detail?.el?.closest('marketplace-workbench');
  if (workbench && ['error', 'retries-failed'].includes(event.detail.type)) workbench.requestFailed(workbench.pending ? 'command' : 'read');
});

class MarketplaceWorkbench extends HTMLElement {
  connectedCallback() { queueMicrotask(() => this.connect()); }
  connect() {
    if (!this.isConnected || this._connected) return;
    this._connected = true;
    this.state = JSON.parse(this.dataset.query);
    this.grid = this.querySelector('inventory-grid');
    this.notice = this.querySelector('.catalogue-notice');
    this.count = this.querySelector('.marketplace-selected');
    this.buttons = [...this.querySelectorAll('button[data-command]')];
    this.edits = new Map(); // row id → {path: value}, kept until the row's command is accepted
    this.findings = new Map(); // row id → [{path, message}] of the last rejection
    this.options = new Map(); // picker value → option, with its revision
    this.selected = [];
    this.saved = JSON.parse(this.dataset.model);
    this.show();
    for (const button of this.buttons) button.addEventListener('click', () => this.command(button.dataset.command));
    this.addEventListener('marketplace-cell', event => this.edit(event.detail));
    this.addEventListener('review-selection', event => this.select(event.detail.selected));
    this.addEventListener('grid-cell-selected', event => { this.current = event.detail.cell; });
    // Keys belong to the table only while it has focus: Enter on a toolbar button presses the button.
    this.addEventListener('focusin', event => { if (!this.grid.contains(event.target) && window.jspreadsheet) window.jspreadsheet.current = null; });
    // A table reached with Tab starts at its first cell, so arrows move from there.
    this.grid.addEventListener('focus', () => {
      const sheet = this.grid.worksheet;
      if (sheet && !sheet.selectedCell?.length && this.saved.rows.length) sheet.updateSelectionFromCoords(0, 0, 0, 0);
    });
    // Space ticks the selected row's box, so rows are chosen without a mouse.
    this.addEventListener('keydown', event => {
      if (event.key !== ' ' || !this.current?.selection || event.target.closest?.('input,select,button,textarea')) return;
      event.preventDefault(); event.stopPropagation();
      const id = this.current.selection.id;
      this.select(this.selected.includes(id) ? this.selected.filter(other => other !== id) : [...this.selected, id]);
    }, true);
    this.addEventListener('inventory-catalogue-options', event => {
      this.choiceRequest = event.detail;
      this.emit('marketplace-choices', { connectionId: this.state.connectionId, lookup: event.detail.target.lookupKind, text: event.detail.text, requestId: event.detail.requestId });
    });
    this.addEventListener('marketplace-open', event => location.assign(event.detail.href));
    this.addEventListener('inventory-resnapshot', () => this.refresh());
    for (const property of ['tableResponse', 'choicesResponse', 'commandResponse', 'changed']) {
      if (Object.prototype.hasOwnProperty.call(this, property)) { const value = this[property]; delete this[property]; this[property] = value; }
    }
  }
  emit(name, detail) { this.dispatchEvent(new CustomEvent(name, { bubbles: true, detail })); }
  rows() { return this.saved.rows; }
  select(ids) {
    const known = new Set(this.rows().filter(row => row.cells.some(cell => cell.selection)).map(row => row.id));
    this.selected = ids.filter(id => known.has(id));
    this.grid.selectedEntries = this.selected;
    this.count.textContent = this.selected.length ? t('{count} selected', { count: this.selected.length }) : '';
    for (const button of this.buttons) button.disabled = !this.selected.length || Boolean(this.pending);
  }
  setStatus(status, message = '') {
    this.grid.saveStatus = status;
    this.grid.busy = status === 'Saving';
    this.notice.textContent = message;
    this.notice.setAttribute('role', status === 'Failed' ? 'alert' : 'status');
    this.select(this.selected);
  }
  label(cell, value) {
    const options = cell.editor?.options || [];
    return options.find(option => option.value === value)?.label ?? this.options.get(value)?.label ?? value;
  }
  // Server cells with this browser's edits and the last rejection's reasons laid over them.
  withEdits(model) {
    return { ...model, rows: model.rows.map(row => {
      const edits = this.edits.get(row.id) || {}, findings = this.findings.get(row.id) || [];
      if (!Object.keys(edits).length && !findings.length) return row;
      return { ...row, cells: row.cells.map((cell, index) => {
        const path = cell.target?.path ?? model.columns[index]?.key, finding = findings.filter(item => item.path === path);
        let next = cell;
        if (cell.target && Object.hasOwn(edits, path)) {
          const value = edits[path], name = edits[`${path}-name`];
          const option = this.options.get(value);
          next = { ...cell, value, display: name ?? this.label(cell, value),
            editor: option && cell.editor ? { ...cell.editor, options: [option, ...(cell.editor.options || []).filter(other => other.value !== value)] } : cell.editor };
        }
        if (finding.length) {
          const message = finding.map(item => item.message).join(' ');
          next = { ...next, className: 'marketplace-finding', title: message, ...(next.lines ? { lines: [...next.lines, { value: message }] } : {}) };
        }
        return next;
      }) };
    }) };
  }
  show() {
    this.grid.model = this.withEdits(this.saved);
    this.select(this.selected);
  }
  edit(detail) {
    this.grid.acknowledgeSave(detail);
    const row = this.rows().find(candidate => candidate.id === detail.rowId);
    if (!row) return;
    const edits = { ...(this.edits.get(row.id) || {}) };
    if (detail.referenceName) {
      // A typed name that matches no choice is a new name where the column allows one.
      if (!detail.newName) { this.setStatus('Failed', t('Choose a value from the list.')); this.show(); return; }
      edits[detail.path] = ''; edits[`${detail.path}-name`] = detail.referenceName;
    } else {
      edits[detail.path] = detail.value; delete edits[`${detail.path}-name`];
    }
    this.edits.set(row.id, edits);
    this.findings.delete(row.id);
    // After the grid's own Tab/Enter move, so the rebuilt sheet keeps the new selection.
    setTimeout(() => this.show());
  }
  // What a row sends: every editable cell's current value, a picker's revision and a typed new name.
  values(row) {
    const edits = this.edits.get(row.id) || {}, values = {};
    for (const cell of row.cells) {
      const path = cell.target?.path;
      if (!path) continue;
      const value = Object.hasOwn(edits, path) ? edits[path] : cell.value;
      values[path] = value ?? '';
      if (cell.editor?.type === 'catalogue-reference') {
        const option = (cell.editor.options || []).find(candidate => candidate.value === value) || this.options.get(value);
        if (value && option?.revision != null) values[`${path}-revision`] = String(option.revision);
        if (!value && cell.target.newName) values[`${path}-name`] = Object.hasOwn(edits, `${path}-name`) ? edits[`${path}-name`] : (cell.display ?? '');
      }
    }
    return values;
  }
  command(name) {
    if (this.pending || !this.selected.length) return;
    const rows = this.selected.map(id => this.rows().find(row => row.id === id)).filter(Boolean)
      .map(row => ({ id: row.id, revision: row.revision, values: this.values(row), ...(row.revisions ? { revisions: row.revisions } : {}),
        ...(row.observation ? { observation: row.observation } : {}) }));
    const reason = this.querySelector(`[data-command-reason="${name}"]`)?.value;
    const each = this.buttons.find(button => button.dataset.command === name)?.dataset.each === 'true';
    this.queue = each ? rows.map(row => [row]) : [rows];
    this.outcome = { command: name, reason, accepted: [], rejected: [], messages: [] };
    this.setStatus('Saving');
    this.next();
  }
  next() {
    const rows = this.queue.shift();
    if (!rows) { this.finish(); return; }
    const { command, reason } = this.outcome;
    this.pending = { ...this.state, command, rows, commandId: crypto.randomUUID(), ...(reason ? { reason } : {}) };
    this.emit('marketplace-command', this.pending);
  }
  set commandResponse(response) {
    if (!this.pending || !response || response.commandId !== this.pending.commandId) return;
    const request = this.pending; this.pending = null;
    if (response.status === 'ok') this.outcome.accepted.push(...request.rows.map(row => row.id));
    else if ((response.rows || []).length) this.outcome.rejected.push(...response.rows);
    else this.outcome.messages.push(response.message);
    this.next();
  }
  // Every command of the action answered: accepted rows leave their edits, rejected ones show why.
  finish() {
    const { command, accepted, rejected, messages } = this.outcome; this.outcome = null;
    const sent = new Set(accepted);
    for (const id of sent) { this.edits.delete(id); this.findings.delete(id); }
    this.selected = this.selected.filter(id => !sent.has(id));
    if (!rejected.length && !messages.length) {
      const reason = this.querySelector(`[data-command-reason="${command}"]`);
      if (reason) reason.value = '';
      this.setStatus('Saved');
      this.refresh();
      return;
    }
    this.findings = new Map();
    for (const row of rejected) this.findings.set(row.id, [...(this.findings.get(row.id) || []), row]);
    const labels = new Map(this.rows().map(row => [row.id, row.label]));
    const reasons = rejected.map(row => `${labels.get(row.id) ?? row.id}: ${row.message}`);
    this.setStatus('Failed', [...reasons, ...messages].join(' '));
    this.show();
    if (sent.size) this.refresh();
  }
  refresh() {
    if (!this.isConnected || !this.grid || this.pending) return;
    this.requestId = crypto.randomUUID();
    this.emit('marketplace-query', { ...this.state, requestId: this.requestId });
  }
  set tableResponse(response) {
    if (!this.grid || !response || response.requestId !== this.requestId || response.requestId === this.lastResponseId) return;
    this.lastResponseId = response.requestId;
    if (response.status !== 'ok') { this.requestFailed('read'); return; }
    this.saved = response.model;
    const present = new Set(this.saved.rows.map(row => row.id));
    for (const map of [this.edits, this.findings]) for (const id of [...map.keys()]) if (!present.has(id)) map.delete(id);
    this.show();
  }
  set choicesResponse(response) {
    if (!this.grid || !response) return;
    for (const option of response.options || []) this.options.set(option.value, option);
    if (this.choiceRequest && response.requestId === this.choiceRequest.requestId) this.grid.referenceOptionsResponse = response;
  }
  set changed(value) {
    if (!this.grid || !value || value === this.lastChange) return;
    this.lastChange = value; this.refresh();
  }
  requestFailed(kind) {
    if (kind === 'command') {
      const accepted = this.outcome?.accepted.length;
      this.pending = null; this.queue = []; this.outcome = null;
      this.setStatus('Failed', t('Could not save. Your edit is retained; retry when connected.'));
      if (accepted) this.refresh();
    }
    else if (kind === 'choices') { if (this.choiceRequest) this.grid.referenceOptionsResponse = { ...this.choiceRequest, status: 'failed', options: [] }; }
    else {
      this.notice.replaceChildren(document.createTextNode(t('Could not load records. ')));
      const retry = document.createElement('button'); retry.type = 'button'; retry.textContent = t('Retry');
      retry.addEventListener('click', () => this.refresh()); this.notice.append(retry);
    }
  }
}

if (!customElements.get('marketplace-workbench')) customElements.define('marketplace-workbench', MarketplaceWorkbench);

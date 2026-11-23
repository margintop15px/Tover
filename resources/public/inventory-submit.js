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
  showSubmit() {
    const count = this.scope.pending ? (this.selected ?? []).length : 0;
    this.submitButton.disabled = !count;
    this.submitDetails.hidden = !this.scope.pending;
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
  discard({ id, revision, line, shared, values }) {
    if (this.pending) return;
    // Other lines of the entry stay.
    if (shared) { this.save(line, { id, revision, values: { ...values, 'line-id': line, remove: 'true' } }); this.pending.kind = 'discard-line'; return; }
    this.pending = { kind: 'discard', id, commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-discard', { workspaceId: this.workspaceId, entryId: id, revision, commandId: this.pending.commandId });
  }
  set saveResponse(response) {
    if (!this.pending || !response || response.commandId !== this.pending.commandId) return;
    const request = this.pending; this.pending = null;
    if (response.status === 'ok') {
      if (['save', 'discard-line', 'describe'].includes(request.kind)) this.revisions.set(request.id, request.revision + 1);
      if (['save', 'discard-line'].includes(request.kind)) this.values.set(request.slot, request.values);
      if (request.kind === 'describe') { this.forget(request.id); this.describe(); return; }
      if (request.target) this.grid.acknowledgeSave(request.target);
      // The new item now has its own row; the blank row starts over.
      if (request.target?.id.startsWith('new-row:')) for (const key of ['variant', 'article', 'barcodes', 'unit', 'unit-cost']) delete this.scope[key];
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
    const shown = new Set(this.model.rows.map(row => row.id));
    const hidden = this.selected.filter(id => !shown.has(id)).length;
    if (hidden && !window.confirm(t('The filter hides {p0} selected rows. Submit them too?', { p0: hidden }))) return;
    this.reviewed = rows.map(row => ({ 'entry-id': row['entry-id'], revision: row.revision }));
    const details = { occurredAt: toISOString(this.submitTime.value), reason: this.submitReason.value.trim() };
    if (!this.submitTime.checkValidity()) { this.submitTime.reportValidity(); return; }
    this.describing = details.occurredAt || details.reason ? { details, entries: [...this.reviewed] } : null;
    this.describe();
  }
  // The time and reason given at submit are saved on each opening, then the openings are reviewed.
  describe() {
    const entry = this.describing?.entries.shift();
    if (!entry) { this.describing = null; this.requestReview(); return; }
    const id = entry['entry-id'];
    const revision = Math.max(entry.revision, this.revisions.get(id) ?? 0);
    this.pending = { kind: 'describe', id, revision, commandId: crypto.randomUUID() };
    this.setStatus('Saving');
    this.emit('stock-describe', { workspaceId: this.workspaceId, entryId: id, revision, ...this.describing.details, commandId: this.pending.commandId });
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

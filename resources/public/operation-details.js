const text = value => value == null ? '' : String(value);

function element(tag, className, content) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (content != null) node.textContent = text(content);
  return node;
}

const fields = { name: 'Goods', article: 'Article', quantity: 'Quantity', unit: 'Unit', location: 'Location', evidence: 'Evidence' };
const quantity = effect => [text(effect.quantity), text(effect.unit)].filter(Boolean).join(' ');
const isOutgoing = effect => effect.direction === 'out' || ['consumed', 'source'].includes(effect.role);
const roleName = effect => ({ consumed: 'Consumed', produced: 'Produced', source: 'Sent', destination: 'Received', sold: 'Sold', returned: 'Returned' }[effect.role]
  || (isOutgoing(effect) ? 'Outgoing' : 'Incoming'));
const goodsKey = effect => effect.batchId || effect.article || effect.rowId;
const signedQuantity = effect => {
  const value = quantity(effect);
  return value && !/^[+−-]/.test(value) ? `${isOutgoing(effect) ? '−' : '+'}${value}` : value;
};

function renderChanges(entry) {
  const table = element('table', 'operation-change-table');
  table.setAttribute('aria-label', 'Changed fields');
  const head = element('thead');
  const headings = element('tr');
  for (const label of ['Field', 'Before', 'After']) {
    const cell = element('th', '', label);
    cell.scope = 'col';
    headings.append(cell);
  }
  head.append(headings);
  const body = element('tbody');
  const changes = entry.changes?.length ? entry.changes : [{ field: 'Quantity', before: entry.before, after: entry.after, quantity: true }];
  for (const change of changes) {
    const row = element('tr');
    const label = element('th', 'operation-change-field', fields[change.field] || change.field || 'Value');
    label.scope = 'row';
    const values = ['before', 'after'].map(side => {
      const value = text(change[side]);
      const cell = element('td', `operation-change-${side}`, value || '—');
      if (change.quantity && value && entry.unit) cell.append(element('span', 'operation-value-unit', ` ${entry.unit}`));
      return cell;
    });
    row.append(label, ...values);
    body.append(row);
  }
  table.append(head, body);
  return table;
}

function effectGroup(effects, label, direction, rowId, onItem) {
  const group = element('section', `operation-effect-group operation-effect-${direction}`);
  group.append(element('h4', 'operation-effect-heading', label));
  const table = element('table', 'operation-effect-table');
  table.setAttribute('aria-label', label);
  const head = element('thead');
  const headings = element('tr');
  for (const title of ['Goods', 'Quantity', 'Location', 'Balance']) {
    const cell = element('th', '', title);
    cell.scope = 'col';
    headings.append(cell);
  }
  head.append(headings);
  const body = element('tbody');
  for (const effect of effects) {
    const current = !!rowId && effect.rowId === rowId;
    const row = element('tr', current ? 'operation-effect-line is-current' : 'operation-effect-line');
    if (effect.rowId) row.dataset.rowId = text(effect.rowId);
    const name = element('td', 'operation-effect-goods');
    const goods = element(onItem && goodsKey(effect) ? 'button' : 'span', onItem && goodsKey(effect) ? 'operation-item-link' : '', effect.name || 'Unnamed goods');
    if (onItem && goodsKey(effect)) {
      goods.type = 'button';
      goods.title = `Open ${effect.name || 'item'} history`;
      goods.addEventListener('click', () => onItem(effect, goods));
    }
    name.append(goods);
    if (effect.article) name.append(element('small', 'operation-effect-article', effect.article));
    if (current) name.append(element('small', 'operation-effect-current', 'This stock line'));
    const balance = effect.before != null && effect.after != null ? `${effect.before} → ${effect.after}` : '—';
    const balanceCell = element('td', 'operation-effect-balance', balance);
    if (effect.unit && balance !== '—') balanceCell.append(element('small', '', effect.unit));
    row.append(name, element('td', 'operation-effect-quantity', signedQuantity(effect) || '—'), element('td', 'operation-effect-location', effect.location || '—'), balanceCell);
    body.append(row);
  }
  if (!effects.length) {
    const row = element('tr');
    const cell = element('td', 'operations-muted', 'No item lines recorded.');
    cell.colSpan = 4;
    row.append(cell);
    body.append(row);
  }
  table.append(head, body);
  group.append(table);
  return group;
}

function renderFlow(entry, rowId, onItem) {
  const effects = entry.effects || [];
  const production = entry.kind === 'production';
  const flow = element('div', 'operation-flow');
  const arrow = element('span', 'operation-flow-arrow', '→');
  arrow.setAttribute('aria-hidden', 'true');
  flow.append(
    effectGroup(effects.filter(isOutgoing), production ? 'Consumed inputs' : 'From', 'out', rowId, onItem),
    arrow,
    effectGroup(effects.filter(effect => !isOutgoing(effect)), production ? 'Produced output' : 'To', 'in', rowId, onItem),
  );
  return flow;
}

function operationDetails(entry, { rowId, onItem } = {}) {
  const kind = entry.kind || 'data_edit';
  const detail = element('section', `operation-details operation-details-${kind}`);
  detail.setAttribute('aria-label', `${entry.type || 'Operation'} details`);
  const heading = element('div', 'operation-details-heading');
  const label = { data_edit: 'Data changes', transfer: 'Stock movement', production: 'Production' }[kind] || 'Operation details';
  heading.append(element('h3', '', label));
  if (entry.example) heading.append(element('span', 'operations-example', 'Demo'));
  const context = typeof entry.context === 'string' ? entry.context : [entry.name, entry.article].filter(Boolean).join(' · ');
  if (context) heading.append(element('span', 'operation-details-context', context));
  detail.append(heading);
  const currentEffects = rowId && kind !== 'data_edit' ? (entry.effects || []).filter(effect => effect.rowId === rowId && effect.direction !== 'none') : [];
  if (currentEffects.length) {
    const roles = element('div', 'operation-inline-roles');
    for (const effect of currentEffects) {
      const role = element('span', `operation-inline-role is-${isOutgoing(effect) ? 'out' : 'in'}`);
      role.append(element('strong', '', roleName(effect)), document.createTextNode(` ${quantity(effect)} · ${effect.name || 'Unnamed goods'}`));
      roles.append(role);
    }
    detail.append(roles);
  }
  if (['transfer', 'production'].includes(kind)) detail.append(renderFlow(entry, rowId, onItem));
  else if (entry.effects?.length && kind !== 'data_edit') {
    const label = { opening: 'Opening stock', purchase: 'Received stock', sale: 'Sold stock', return: 'Returned stock' }[kind] || 'Stock effects';
    const effects = rowId ? entry.effects.filter(effect => effect.rowId === rowId) : entry.effects;
    detail.append(effectGroup(effects, label, effects.every(isOutgoing) ? 'out' : 'in', rowId, onItem));
  } else {
    detail.append(renderChanges(entry));
  }
  const metadata = element('dl', 'operation-details-meta');
  for (const [name, value] of [['Reason', entry.reason], ['Evidence', entry.evidence !== entry.reason ? entry.evidence : '']]) {
    if (!value) continue;
    metadata.append(element('dt', '', name), element('dd', '', value));
  }
  if (metadata.childElementCount) detail.append(metadata);
  return detail;
}

const operationTime = entry => Date.parse(entry.effectiveAt || entry.recordedAt || '');
const operationDate = entry => Number.isFinite(operationTime(entry))
  ? new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(operationTime(entry)) : '—';

function goodsChange(entry, effects) {
  if (entry.changes?.length) return entry.changes.map(change => `${fields[change.field] || change.field}: ${text(change.before) || '—'} → ${text(change.after) || '—'}`).join(' · ');
  const from = effects.find(isOutgoing);
  const to = effects.find(effect => effect.direction === 'in');
  if (from && to) return `${quantity(from)} · ${from.location || '—'} → ${to.location || '—'}`;
  return effects.map(effect => `${roleName(effect)} ${signedQuantity(effect)}`).join(' · ');
}

export function renderOperationDetails(entry, { rowId, entries = [] } = {}) {
  if (!entries.length) return operationDetails(entry, { rowId });
  const inspector = element('div', 'operation-inspector');
  const frames = [];
  let current;

  function back() {
    const frame = frames.pop();
    if (!frame) return;
    current = frame.node;
    inspector.replaceChildren(current);
    frame.trigger?.focus({ preventScroll: true });
  }

  function toolbar(label) {
    const bar = element('div', 'operation-history-toolbar');
    const button = element('button', 'operation-history-back', '← Back');
    button.type = 'button';
    button.setAttribute('aria-label', `Back to ${label}`);
    button.addEventListener('click', back);
    bar.append(button, element('span', '', label));
    return bar;
  }

  function push(node, trigger) {
    frames.push({ node: current, trigger });
    current = node;
    inspector.replaceChildren(current);
    current.querySelector('.operation-history-back')?.focus({ preventScroll: true });
  }

  function details(record, selectedRowId) {
    return operationDetails(record, { rowId: selectedRowId, onItem: (effect, trigger) => openHistory(effect, record, trigger) });
  }

  function openRecord(record, groupKey, groupName, trigger) {
    const frame = element('section', 'operation-history-operation');
    const effects = (record.effects || []).filter(effect => goodsKey(effect) === groupKey);
    const selectedRowId = (effects.find(effect => effect.direction === 'in') || effects[0])?.rowId;
    frame.append(toolbar(`${groupName} history`), details(record, selectedRowId));
    push(frame, trigger);
  }

  function openHistory(effect, origin, trigger) {
    const groupKey = goodsKey(effect);
    const history = entries.filter(record => record.effects?.some(line => goodsKey(line) === groupKey))
      .sort((left, right) => (operationTime(left) || 0) - (operationTime(right) || 0));
    if (!history.length) return;
    const goods = history.flatMap(record => record.effects.filter(line => goodsKey(line) === groupKey)).at(-1) || effect;
    const frame = element('section', 'operation-history');
    frame.append(toolbar(origin.type || 'operation'));
    const body = element('div', 'operation-history-body');
    const heading = element('div', 'operation-history-heading');
    heading.append(element('h3', '', goods.name || 'Goods'));
    if (goods.article) heading.append(element('span', 'operation-history-article', goods.article));
    const scope = element('p', 'operation-history-scope');
    const table = element('table', 'operation-history-table');
    table.setAttribute('aria-label', `${goods.name || 'Goods'} history`);
    const head = element('thead');
    const headings = element('tr');
    for (const title of ['Date', 'Operation', 'Change', 'Location / evidence']) {
      const cell = element('th', '', title);
      cell.scope = 'col';
      headings.append(cell);
    }
    head.append(headings);
    const records = element('tbody');
    table.append(head, records);
    scope.textContent = 'Across all locations';
    records.append(...history.map(record => {
        const effects = record.effects.filter(line => goodsKey(line) === groupKey);
        const row = element('tr');
        row.dataset.operationId = text(record.id);
        row.dataset.goodsGroup = groupKey;
        const name = element('td');
        const button = element('button', 'operation-history-event', record.type || 'Operation');
        button.type = 'button';
        button.addEventListener('click', () => openRecord(record, groupKey, goods.name || 'Goods', button));
        name.append(button);
        const location = element('td');
        location.append(element('span', '', [...new Set(effects.map(line => line.location).filter(Boolean))].join(' → ') || '—'));
        if (record.evidence) location.append(element('small', 'operation-history-evidence', record.evidence));
        row.append(element('td', 'operation-history-date', operationDate(record)), name, element('td', 'operation-history-change', goodsChange(record, effects)), location);
        return row;
      }));
    body.append(heading, scope, table);
    frame.append(body);
    push(frame, trigger);
  }

  current = details(entry, rowId);
  inspector.append(current);
  return inspector;
}

import { renderOperationDetails } from './operation-details.js?v=lineage-12';

const DAY = 86400000;
const text = value => value == null ? '' : String(value);
const time = entry => Date.parse(entry.effectiveAt || entry.recordedAt || '');
const date = value => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(value);
const operation = entry => entry.type || entry.kind || 'Operation';
const groupKey = effect => text(effect.batchId || effect.article || effect.rowId || effect.id);
const outgoing = effect => effect.direction === 'out' || ['source', 'consumed', 'sold'].includes(effect.role);
const quantity = (value, unit) => [text(value), text(unit)].filter(Boolean).join(' ');

function element(tag, className, content) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (content != null) node.textContent = text(content);
  return node;
}

function entryEffects(entry) {
  if (entry.effects?.length) return entry.effects;
  return entry.rowId ? [{ ...entry, quantity: entry.delta, role: 'edited' }] : [];
}

function goodsCatalog(entries, rows) {
  const catalog = new Map();
  const add = (effect, entry) => {
    const key = groupKey(effect);
    if (!key) return;
    if (!catalog.has(key)) catalog.set(key, { key, name: 'Goods', article: '', batchId: '', unit: '', events: [], rows: [], search: new Set() });
    const group = catalog.get(key);
    for (const field of ['name', 'article', 'batchId', 'unit']) if (effect[field]) group[field] = effect[field];
    for (const value of [effect.name, effect.article]) if (value) group.search.add(text(value));
    if (entry) group.events.push({ entry, effect, at: time(entry) });
    else group.rows.push(effect);
  };
  [...entries].sort((a, b) => time(a) - time(b)).forEach(entry => entryEffects(entry).forEach(effect => add(effect, entry)));
  rows.forEach(row => add(row));
  return catalog;
}

function groupLanes(group, end) {
  const lanes = new Map();
  for (const event of group.events.filter(event => Number.isFinite(event.at))) {
    const key = event.effect.rowId || event.effect.location;
    if (!key) continue;
    if (!lanes.has(key)) lanes.set(key, { rowId: event.effect.rowId || '', location: event.effect.location || 'Location not recorded', unit: event.effect.unit || group.unit, events: [], segments: [] });
    const lane = lanes.get(key);
    lane.events.push(event);
    if (event.effect.location) lane.location = event.effect.location;
  }
  for (const row of group.rows) {
    const key = row.rowId || row.id || row.location;
    if (!lanes.has(key)) lanes.set(key, { rowId: row.rowId || row.id || '', location: row.location || 'Location not recorded', unit: row.unit || group.unit, events: [], segments: [] });
    lanes.get(key).current = row.quantity;
  }
  for (const lane of lanes.values()) {
    lane.events.sort((a, b) => a.at - b.at);
    let previous;
    let balance;
    for (const event of lane.events) {
      if (previous && balance != null && event.at > previous.at) lane.segments.push({ from: previous.at, to: event.at, balance, entry: previous.entry });
      if (event.effect.after != null) balance = event.effect.after;
      previous = event;
    }
    if (previous && balance != null) lane.segments.push({ from: previous.at, to: end, balance, entry: previous.entry, final: true });
    if (lane.current == null) lane.current = balance;
  }
  return [...lanes.values()];
}

function productionGroups(entries, selectedKey) {
  const inputs = new Set();
  const outputs = new Set();
  for (const entry of entries.filter(entry => entry.kind === 'production')) {
    const consumed = entryEffects(entry).filter(outgoing).map(groupKey);
    const produced = entryEffects(entry).filter(effect => !outgoing(effect)).map(groupKey);
    if (produced.includes(selectedKey)) consumed.forEach(key => { if (key !== selectedKey) inputs.add(key); });
    if (consumed.includes(selectedKey)) produced.forEach(key => { if (key !== selectedKey) outputs.add(key); });
  }
  return { inputs: [...inputs], outputs: [...outputs] };
}

function effectDescription(effect) {
  const label = { consumed: 'Consumed', produced: 'Produced', source: 'Sent', destination: 'Received', sold: 'Sold', returned: 'Returned', edited: 'Edited' }[effect.role] || 'Changed';
  return `${label} ${quantity(effect.quantity, effect.unit)}${effect.before != null && effect.after != null ? ` · Balance ${effect.before} → ${effect.after}` : ''}`;
}

export function renderLineage(entries, { rowId = '', rows = [], query = '' } = {}) {
  const root = element('section', 'lineage-view');
  root.setAttribute('aria-label', rowId ? 'Stock line goods lineage' : 'Goods lineage');
  const catalog = goodsCatalog(entries, rows);
  const dates = entries.map(time).filter(Number.isFinite);
  if (!catalog.size || !dates.length) {
    root.append(element('p', 'lineage-empty', 'No dated goods history is available.'));
    return root;
  }
  const search = query.trim().toLocaleLowerCase();
  const available = [...catalog.values()].filter(group => !search || [...group.search].some(value => value.toLocaleLowerCase().includes(search)))
    .sort((a, b) => a.name.localeCompare(b.name));
  const scoped = rowId && [...catalog.values()].find(group => group.rows.some(row => (row.rowId || row.id) === rowId) || group.events.some(event => event.effect.rowId === rowId));
  if (rowId ? !scoped : !available.length) {
    root.append(element('p', 'lineage-empty', rowId ? 'No history is recorded for these goods.' : 'No goods match this search. Try a goods name or article.'));
    return root;
  }
  let selectedKey = scoped?.key || available.find(group => group.article === 'DESK-KIT')?.key || available[0].key;
  const backStack = [];
  let chart;
  let detail;
  const start = Math.floor(Math.min(...dates) / DAY) * DAY;
  const end = Math.max(...dates) + DAY / 2;
  const percent = at => Math.max(0, Math.min(100, (at - start) / (end - start) * 100));

  function showEvent(entry, lane) {
    for (const node of chart.querySelectorAll('[data-event-id]')) {
      const active = node.dataset.eventId === entry.id;
      node.classList.toggle('is-active', active);
      node.setAttribute('aria-pressed', String(active));
    }
    const group = catalog.get(selectedKey);
    const heading = element('div', 'lineage-event-heading');
    heading.append(element('strong', '', operation(entry)), element('span', '', `${group.name} · ${lane.location} · ${date(time(entry))}`));
    detail.replaceChildren(heading, renderOperationDetails(entry, { rowId: lane.rowId || rowId, entries }));
  }

  function render() {
    const group = catalog.get(selectedKey);
    const lanes = groupLanes(group, end);
    const links = productionGroups(entries, selectedKey);
    const toolbar = element('div', 'lineage-toolbar');
    if (rowId) {
      const heading = element('div', 'lineage-goods-heading');
      heading.append(element('strong', '', group.name), element('span', '', group.article));
      toolbar.append(heading);
    } else {
      const label = element('label', 'lineage-picker-label', 'Goods');
      const select = element('select', 'lineage-picker');
      select.setAttribute('aria-label', 'Goods');
      const options = available.some(item => item.key === selectedKey) ? available : [...available, group];
      for (const item of options) {
        const option = element('option', '', [item.name, item.article].filter(Boolean).join(' · '));
        option.value = item.key;
        option.selected = item.key === selectedKey;
        select.append(option);
      }
      select.addEventListener('change', () => { selectedKey = select.value; backStack.length = 0; render(); });
      label.append(select);
      toolbar.append(label);
    }
    if (backStack.length) {
      const back = element('button', 'lineage-back', `Back to ${catalog.get(backStack.at(-1)).name}`);
      back.type = 'button';
      back.addEventListener('click', () => { selectedKey = backStack.pop(); render(); });
      toolbar.append(back);
    }
    const current = element('span', 'lineage-selection-status', lanes.map(lane => `${lane.location}: ${quantity(lane.current ?? '—', lane.unit)}`).join(' · '));
    toolbar.append(current);
    const scope = element('p', 'lineage-scope', 'Recorded balances by location. Production inputs and outputs have separate histories.');
    const relations = element('div', 'lineage-relations');
    for (const [title, keys] of [['Produced from', links.inputs], ['Used to produce', links.outputs]]) {
      if (!keys.length) continue;
      const relation = element('div', 'lineage-relation');
      relation.append(element('span', '', title));
      for (const key of keys) {
        const related = catalog.get(key);
        if (!related) continue;
        const follow = element('button', 'lineage-follow', [related.name, related.article].filter(Boolean).join(' · '));
        follow.type = 'button';
        follow.title = `Show ${[related.name, related.article].filter(Boolean).join(' · ')} history`;
        follow.addEventListener('click', () => { backStack.push(selectedKey); selectedKey = key; render(); });
        relation.append(follow);
      }
      relations.append(relation);
    }
    chart = element('div', 'lineage-chart');
    const axis = element('div', 'lineage-axis');
    const caption = element('span', 'lineage-axis-caption', `${date(start)} – ${date(Math.max(...dates))}`);
    const header = element('div', 'lineage-chart-heading');
    header.append(caption, axis);
    chart.append(header);
    const days = Math.ceil((end - start) / DAY);
    const step = Math.max(1, Math.ceil(days / 8));
    const ticks = [];
    for (let at = start; at < end; at += DAY * step) {
      ticks.push(at);
      const tick = element('span', 'lineage-tick', date(at));
      tick.style.left = `${percent(at)}%`;
      axis.append(tick);
    }
    const snapshot = element('span', 'lineage-tick lineage-snapshot', 'Snapshot');
    snapshot.style.left = '100%';
    axis.append(snapshot);
    const body = element('div', 'lineage-lanes');
    for (const lane of lanes) {
      const line = element('div', 'lineage-lane');
      line.dataset.rowId = lane.rowId;
      const label = element('div', 'lineage-lane-label');
      label.append(element('strong', '', lane.location), element('span', '', group.name), element('small', '', `Current · ${quantity(lane.current ?? '—', lane.unit)}`));
      const plot = element('div', 'lineage-plot');
      for (const at of ticks) {
        const tick = element('span', 'lineage-grid-line');
        tick.style.left = `${percent(at)}%`;
        plot.append(tick);
      }
      for (const segment of lane.segments) {
        const bar = element('button', `lineage-bar${segment.final ? ' is-current' : ''}${Number(segment.balance) === 0 ? ' is-empty' : ''}`, `${quantity(segment.balance, lane.unit)} · ${lane.location}`);
        bar.type = 'button';
        bar.dataset.eventId = segment.entry.id;
        bar.style.left = `${percent(segment.from)}%`;
        bar.style.width = `${Math.max(.4, percent(segment.to) - percent(segment.from))}%`;
        bar.title = `${group.name} · ${quantity(segment.balance, lane.unit)} at ${lane.location} · ${date(segment.from)} → ${segment.final ? 'Current snapshot' : date(segment.to)}`;
        bar.setAttribute('aria-label', bar.title);
        bar.setAttribute('aria-pressed', 'false');
        bar.addEventListener('click', () => showEvent(segment.entry, lane));
        plot.append(bar);
      }
      for (const event of lane.events) {
        const marker = element('button', `lineage-marker is-${event.effect.role || event.entry.kind || 'operation'}`);
        marker.type = 'button';
        marker.dataset.eventId = event.entry.id;
        marker.style.left = `${percent(event.at)}%`;
        marker.title = `${date(event.at)} · ${operation(event.entry)} · ${effectDescription(event.effect)} · ${lane.location}`;
        marker.setAttribute('aria-label', marker.title);
        marker.setAttribute('aria-pressed', 'false');
        marker.addEventListener('click', () => showEvent(event.entry, lane));
        plot.append(marker);
      }
      line.append(label, plot);
      body.append(line);
    }
    chart.append(body);
    const legend = element('div', 'lineage-legend');
    for (const [kind, label] of [['stock', 'Recorded balance'], ['current', 'Current period'], ['change', 'Operation marker']]) legend.append(element('span', `is-${kind}`, label));
    detail = element('div', 'lineage-event');
    detail.setAttribute('aria-live', 'polite');
    detail.append(element('p', 'lineage-detail-hint', 'Select a balance period or operation marker for details. Follow goods links to view production inputs or outputs.'));
    root.replaceChildren(toolbar, scope, relations, chart, legend, detail);
  }
  render();
  return root;
}

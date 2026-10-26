import { t } from './i18n.js';
const operators = [
  ['empty', t('Empty')], ['notEmpty', t('Not empty')], ['eq', t('Equal')], ['notEq', t('Not equal')],
  ['begins', t('Begins with')], ['contains', t('Contains')], ['notContains', t('Does not contain')],
];
const clone = value => structuredClone(value || {});
function control(tag, className, text) {
  const node = document.createElement(tag); node.className = className;
  if (text) node.textContent = text;
  return node;
}

// The popup edits filter expressions. Only the server filters or orders data.
export function installServerFilters(grid, owner = grid, eventName = 'inventory-filter') {
  let panel, column, conditions, requested, timer;
  let intended = clone(grid.acceptedFilters);
  let acceptedSignature = JSON.stringify(grid.acceptedFilters || {});
  // Loading a replacement page must block stale cell edits without interrupting
  // the filter expression that requested it.
  const busy = () => grid.filterBusy ?? grid.busy;
  const publish = () => {
    clearTimeout(timer);
    if (!panel || busy()) return;
    const filters = clone(intended);
    if (conditions.length) filters[column.filterKey] = clone(conditions);
    else delete filters[column.filterKey];
    const signature = JSON.stringify(filters);
    if (signature === requested) return;
    requested = signature; intended = clone(filters);
    owner.dispatchEvent(new CustomEvent(eventName, { bubbles: true, detail: { filters } }));
  };
  const close = () => { publish(); panel?.remove(); panel = null; document.removeEventListener('pointerdown', outside, true); };
  const outside = event => { if (panel && !panel.contains(event.target) && !event.target.closest('.inventory-column-filter')) close(); };
  function render() {
    panel.replaceChildren();
    const title = control('div', 'inventory-filter-title', t("Filter {p0}", {p0: column.label})); panel.append(title);
    for (const [index, condition] of conditions.entries()) {
      const row = control('div', 'inventory-filter-rule');
      const type = control('select', ''); type.setAttribute('aria-label', t("Condition {p0}", {p0: index + 1}));
      for (const [value, label] of operators) type.append(new Option(label, value));
      type.value = condition.type;
      type.addEventListener('change', () => { condition.type = type.value; render(); publish(); });
      row.append(type);
      if (!['empty', 'notEmpty'].includes(condition.type)) {
        const input = control('input', ''); input.type = 'text'; input.value = condition.value || '';
        input.setAttribute('aria-label', t("Value {p0}", {p0: index + 1}));
        input.addEventListener('input', () => { condition.value = input.value; clearTimeout(timer); timer = setTimeout(publish, 250); });
        input.addEventListener('change', publish);
        input.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); publish(); } });
        row.append(input);
      }
      const remove = control('button', 'inventory-filter-remove', '×'); remove.type = 'button';
      remove.setAttribute('aria-label', t("Remove condition {p0}", {p0: index + 1}));
      remove.addEventListener('click', () => { conditions.splice(index, 1); render(); publish(); }); row.append(remove); panel.append(row);
      if (index < conditions.length - 1) {
        const relation = control('select', 'inventory-filter-relation'); relation.setAttribute('aria-label', t("Join condition {p0}", {p0: index + 1}));
        relation.append(new Option(t('AND'), 'and'), new Option(t('OR'), 'or')); relation.value = condition.relation || 'and';
        relation.addEventListener('change', () => { condition.relation = relation.value; publish(); }); panel.append(relation);
      }
    }
    const add = control('select', 'inventory-filter-add'); add.setAttribute('aria-label', t('Add condition'));
    add.append(new Option(t('Add condition'), ''));
    for (const [value, label] of operators) add.append(new Option(label, value));
    add.addEventListener('change', () => {
      if (!add.value || conditions.length >= 32) return;
      conditions.push({ id: crypto.randomUUID(), type: add.value, value: '', relation: 'and' }); render(); publish();
      panel.querySelector('.inventory-filter-rule:last-of-type input')?.focus();
    }); panel.append(add);
    const footer = control('div', 'inventory-filter-footer');
    const reset = control('button', '', t('Reset')); reset.type = 'button'; reset.addEventListener('click', () => { conditions = []; render(); publish(); });
    const done = control('button', '', t('Close')); done.type = 'button'; done.addEventListener('click', close);
    footer.append(done, reset); panel.append(footer);
    updateBusy();
  }
  function updateBusy() { for (const node of panel?.querySelectorAll('input,select,button') || []) node.disabled = busy(); }
  return {
    open(definition) {
      if (!definition?.filterKey || busy()) return;
      close(); column = definition; conditions = clone(intended?.[column.filterKey] || []);
      requested = JSON.stringify(intended);
      panel = control('div', 'inventory-column-filter-panel'); panel.setAttribute('role', 'dialog');
      panel.setAttribute('aria-label', t("Filter {p0}", {p0: column.label})); panel.addEventListener('keydown', event => { event.stopPropagation(); if (event.key === 'Escape') { event.preventDefault(); close(); } });
      panel.addEventListener('mousedown', event => event.stopPropagation()); grid.append(panel); render();
      const anchor = [...grid.querySelectorAll('thead td[data-x]')].find(td => td.closest('inventory-grid') === grid && grid.model.columns[Number(td.dataset.x)]?.key === definition.key);
      const bounds = anchor?.getBoundingClientRect() || grid.getBoundingClientRect();
      panel.style.left = `${Math.max(8, Math.min(bounds.left, innerWidth - panel.offsetWidth - 8))}px`;
      panel.style.top = `${Math.max(8, Math.min(bounds.bottom, innerHeight - panel.offsetHeight - 8))}px`;
      document.addEventListener('pointerdown', outside, true); panel.querySelector('input,select,button')?.focus();
    },
    update() {
      updateBusy();
      const signature = JSON.stringify(grid.acceptedFilters || {});
      if (signature === acceptedSignature) return;
      acceptedSignature = signature;
      if (!panel) intended = clone(grid.acceptedFilters);
      else if (!busy() && signature !== requested) {
        intended = clone(grid.acceptedFilters); conditions = clone(intended?.[column.filterKey] || []); requested = signature; render();
      }
    },
    close,
  };
}

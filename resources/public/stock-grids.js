import { t } from './i18n.js';
import './inventory-grid.js';
import { mergePatch } from './datastar.js';

document.addEventListener('datastar-fetch', event => {
  if (['error', 'retries-failed'].includes(event.detail?.type) && event.detail.el?.closest('#inventory-evidence-workbench')) {
    mergePatch({ saveStatus: t('Failed · edit remains in the field; retry when connected'), _reviewPending: false });
  }
});

window.addEventListener('online', () => {
  document.querySelector('#inventory-evidence-workbench')?.dispatchEvent(new CustomEvent('inventory-resnapshot', { bubbles: true }));
});

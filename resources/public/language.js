// Soft navigation can change the destination without replacing the header.
document.addEventListener('submit', event => {
  const form = event.target;
  if (!form.matches('.language-switch')) return;
  if (!location.pathname.startsWith('/action/')) form.elements['return-to'].value = location.pathname + location.search;
});

// Browser validation UI follows the app language, even when the browser itself uses another language.
const validationMessages = JSON.parse(document.querySelector('meta[name="tover-messages"]')?.content || '{}');
document.addEventListener('invalid', event => {
  const field = event.target;
  if (!field.validity) return;
  const rules = [
    ['valueMissing', 'Fill in this field.'], ['typeMismatch', 'Enter a valid value.'],
    ['tooShort', 'This value is too short.'], ['tooLong', 'This value is too long.'],
    ['rangeUnderflow', 'This value is below the minimum.'], ['rangeOverflow', 'This value exceeds the maximum.'],
    ['stepMismatch', 'Enter a value in the allowed increments.'], ['badInput', 'Enter a valid value.'],
    ['patternMismatch', 'Use the requested format.'],
  ];
  const rule = rules.find(([flag]) => field.validity[flag]);
  if (!rule) return;
  const key = rule[0] === 'typeMismatch' && field.type === 'email' ? 'Enter a valid email address.' : rule[1];
  field.setCustomValidity(validationMessages[key] || key);
}, true);
document.addEventListener('input', event => { event.target.setCustomValidity?.(''); });
document.addEventListener('change', event => { event.target.setCustomValidity?.(''); });

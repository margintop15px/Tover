import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
const root = fileURLToPath(new URL('../../', import.meta.url));
const catalog = JSON.parse(readFileSync(join(root, 'resources/i18n/messages.json'), 'utf8'));
const sourceFiles = directory => readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  const path = join(directory, entry.name);
  return entry.isDirectory() ? sourceFiles(path) : [path];
});

test('every literal translation key in server and browser source exists in both catalogs', () => {
  const files = [...sourceFiles(join(root, 'src/tover')).filter(path => path.endsWith('.clj')),
    ...sourceFiles(join(root, 'resources/public')).filter(path => path.endsWith('.js') && !path.includes('/vendor/') && !path.endsWith('/datastar.js'))];
  let checked = 0;
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const matches = file.endsWith('.clj') ? source.matchAll(/\(i18n\/t\s+("(?:[^"\\]|\\.)*")/g)
      : source.matchAll(/\bt\(\s*((['"])(?:\\.|(?!\2).)*?\2)/g);
    for (const match of matches) {
      const quoted = match[1];
      const key = quoted[0] === '"' ? JSON.parse(quoted) : quoted.slice(1, -1).replaceAll("\\'", "'");
      for (const locale of ['en', 'ru']) assert.ok(Object.hasOwn(catalog[locale], key), `${file}: missing ${locale} key ${key}`);
      checked++;
    }
  }
  assert.ok(checked > 250, `Expected broad interface coverage, checked ${checked} sites`);
});

test('declared server labels, titles, placeholders and validation messages all have translations', () => {
  for (const file of sourceFiles(join(root, 'src/tover')).filter(path => path.endsWith('.clj'))) {
    const source = readFileSync(file, 'utf8');
    for (const [, quoted] of source.matchAll(/:(?:aria-label|title|placeholder|label|heading|description|message)\s+("(?:[^"\\]|\\.)*")/g)) {
      const text = JSON.parse(quoted);
      if (!/[A-Za-z]/.test(text)) continue;
      assert.ok(Object.hasOwn(catalog.ru, text), `${file}: untranslated declared text ${text}`);
    }
  }
});

test('browser translator uses Russian plural forms and preserves parameter values verbatim', async () => {
  const previous = globalThis.document;
  try {
    globalThis.document = { documentElement: { lang: 'ru' }, querySelector: () => ({ content: JSON.stringify(catalog.ru) }) };
    const source = readFileSync(join(root, 'resources/public/i18n.js'), 'utf8');
    const { t } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
    assert.equal(t('{count} items', { count: 21 }), '21 товар');
    assert.equal(t('{count} items', { count: 22 }), '22 товара');
    assert.equal(t('{count} items', { count: 25 }), '25 товаров');
    assert.equal(t('History for {name}', { name: 'Purchase <script>' }), 'История товара «Purchase <script>»');
  } finally { if (previous === undefined) delete globalThis.document; else globalThis.document = previous; }
});

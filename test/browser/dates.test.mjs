import assert from 'node:assert/strict';
import test from 'node:test';

process.env.TZ = 'America/Los_Angeles';
const { formatDate, inputDateValue, toISOString, toLocalDateTime } = await import('../../resources/public/dates.js');

test('dates display in the viewer timezone instead of exposing wire timestamps', () => {
  const label = formatDate('2026-10-22T00:30:00.123456Z');
  assert.match(label, /Oct 21, 2026/);
  assert.match(label, /5:30/);
  assert.doesNotMatch(label, /T00:30|123456|Z$/);
  assert.match(formatDate('2026-10-22'), /Oct 22, 2026/);
});

test('local editors convert winter and summer values to UTC', () => {
  assert.equal(toLocalDateTime('2026-10-22T17:15:30.123456Z'), '2026-10-22T10:15');
  assert.equal(toISOString('2026-10-22T10:15'), '2026-10-22T17:15:00.000Z');
  assert.equal(toISOString('2026-01-22T10:15'), '2026-01-22T18:15:00.000Z');
});

test('unchanged local editors preserve original timestamp precision', () => {
  const input = { dataset: { dateValue: '2026-10-22T17:15:30.123456789Z' }, value: '2026-10-22T10:15' };
  assert.equal(inputDateValue(input), '2026-10-22T17:15:30.123456789Z');
  input.value = '2026-10-22T10:16';
  assert.equal(inputDateValue(input), '2026-10-22T17:16:00.000Z');
});

test('cleared and invalid input remains available for server validation', () => {
  assert.equal(toLocalDateTime(''), '');
  assert.equal(toISOString(''), '');
  assert.equal(formatDate('invalid date'), 'invalid date');
  assert.equal(formatDate('2026'), '2026');
  assert.equal(toLocalDateTime('12'), '12');
  assert.equal(toISOString('12'), '12');
  assert.equal(toISOString('invalid date'), 'invalid date');
  assert.equal(toISOString('2026-03-08T02:30'), '2026-03-08T02:30');
});

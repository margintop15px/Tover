import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fieldError, normalizeFieldValue } from '../../resources/public/field-input.js';

test('numeric input stays exact and comma decimals normalize without rounding', () => {
  for (const value of ['0', '12.50', '12,50', '12345678901234567890,1234']) {
    assert.equal(fieldError({ type: 'decimal' }, value), '', value);
  }
  assert.equal(normalizeFieldValue({ type: 'decimal' }, '12345678901234567890,1234'), '12345678901234567890.1234');
  for (const value of ['1e3', '-1', '+1', '12abc', '1,234.56', '1 234', '12.', '0.1234567890123', '123456789012345678901234567890123456789']) {
    assert.ok(fieldError({ type: 'decimal' }, value), value);
  }
});

test('quantities accept whole units through the server limit', () => {
  assert.equal(normalizeFieldValue({ type: 'quantity' }, '003'), '3');
  for (const value of ['', '0', '003', '9223372036854775807']) assert.equal(fieldError({ type: 'quantity' }, value), '', value);
  for (const value of ['1.5', '-1', '+1', '1e3', '12abc', ' 1', '9223372036854775808']) assert.ok(fieldError({ type: 'quantity' }, value), value);
});

test('dates reject invalid calendar dates instead of rolling into another month', () => {
  assert.equal(normalizeFieldValue({ type: 'date' }, '2026-11-02T12:00:00.123456789Z'), '2026-11-02T12:00:00.123456789Z');
  for (const value of ['', '2024-02-29', '2026-11-02T12:34:00Z', '2026-11-02T12:34:00+02:00']) assert.equal(fieldError({ type: 'date' }, value), '', value);
  for (const value of ['yesterday', '2026-02-29', '2026-02-30', '2026-11-02T32:00:00Z']) assert.ok(fieldError({ type: 'date' }, value), value);
});

test('currencies normalize ASCII letters and selection columns require declared choices', () => {
  assert.equal(normalizeFieldValue({ type: 'currency' }, 'eur'), 'EUR');
  for (const value of ['', 'eur', 'EUR']) assert.equal(fieldError({ type: 'currency' }, value), '', value);
  for (const value of ['EU', 'EURO', 'E1R', 'eß']) assert.ok(fieldError({ type: 'currency' }, value), value);
  const direction = { type: 'select', options: [{ value: 'incoming', label: 'Incoming' }, { value: 'outgoing', label: 'Outgoing' }] };
  assert.equal(fieldError(direction, 'Incoming'), '');
  assert.equal(normalizeFieldValue(direction, 'Incoming'), 'incoming');
  assert.ok(fieldError(direction, 'sideways'));
  assert.ok(fieldError(direction, ''));
  assert.equal(fieldError({ ...direction, allowEmpty: true }, ''), '');
  const reference = { type: 'operation-reference', options: [{ value: 'purchase-1', label: 'Purchase · Supplier' }] };
  assert.equal(fieldError(reference, ''), '');
  assert.equal(fieldError(reference, 'purchase-1'), '');
  assert.ok(fieldError(reference, 'missing'));
});

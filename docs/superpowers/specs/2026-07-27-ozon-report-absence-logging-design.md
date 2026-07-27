# Ozon Report Absence and Safe Step Logging Design

## Problem

Durable run `7b179e08-77ff-4351-883f-e04a5ad75484` proved two separate
production issues:

1. A deployment encryption-key mismatch caused steps to fail before reaching
   Ozon. The runner persisted only `kind: client`, so the exact internal reason
   had to be recovered from hosting logs.
2. After credentials were re-saved, the reports step successfully created the
   June mutual-settlement and compensation reports, then permanently failed
   when `/v1/finance/decompensation` returned the exact absence response
   `decompensation document not found`.

The current empty-report matcher accepts only `finance document not found`.
That is correct for mutual settlement but too narrow for the two adjacent
monthly document endpoints.

## Behavior

Monthly report generation will treat a `404` as valid empty data only when the
endpoint and terminal error identity match:

| Endpoint | Accepted empty identity |
| --- | --- |
| `/v1/finance/mutual-settlement` | `finance document not found` |
| `/v1/finance/compensation` | `compensation document not found` |
| `/v1/finance/decompensation` | `decompensation document not found` |

The identity may be the exact safe API code/message or the final
`desc = ... document not found` segment in Ozon's RPC wrapper. Prefixes,
suffixes, mismatched document types, non-404 statuses, and all other endpoints
remain permanent errors.

An accepted absence increments the reports step's `skipped` count and
processing continues through the other report types, months, cash flow, and
buyouts.

## Safe Structured Logging

When a claimed step throws, the durable runner will emit one structured server
log before persisting the classified result. The log will contain:

- event name;
- run, connection, step, and attempt identifiers;
- retry classification, safe HTTP status, retry delay, and endpoint when
  available;
- a safe reason selected only from typed integration errors.

Safe reasons include internal invariant/incomplete-response messages,
`PermanentOzonSyncError` messages such as stored-credential decryption failure,
and sanitized `OzonApiError` code/message fields. Transport and unknown runtime
errors expose only their type/code classification.

The logger must never include credentials, ciphertext, request/response
payloads, authorization headers, raw unknown errors, stack traces, or arbitrary
database error messages. Production uses `console.error`; tests inject a
recorder.

## Tests

Unit coverage will prove:

- each endpoint accepts only its corresponding exact absence identity;
- a mismatched endpoint/message, ordinary 404, prefixed/suffixed identity, and
  non-404 remain failures;
- a missing decompensation document is skipped while later report work
  continues;
- known typed failures produce the intended structured log;
- unknown failures cannot place their raw message or sensitive values in logs;
- retry persistence and existing error classification remain unchanged.

The full unit suite, Ozon validation E2E suite, lint, and production build will
run before publication.

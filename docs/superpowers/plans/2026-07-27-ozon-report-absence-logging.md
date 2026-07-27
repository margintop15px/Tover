# Ozon Report Absence and Safe Step Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Skip only endpoint-matched absent monthly finance documents and emit credential-safe structured logs for every durable Ozon step failure.

**Architecture:** Keep absence classification beside the existing monthly finance-report loop in `sync.ts`, using the typed `OzonApiError.endpoint` plus an exact terminal document identity. Add a pure safe-log builder to `durable-runner.ts`; the runner emits its output through an injected logger before lease-aware persistence, while production supplies `console.error`.

**Tech Stack:** TypeScript, Next.js, Supabase, Node test runner through `tsx`, Playwright Ozon mock server.

## Global Constraints

- Work on `codex/ozon-report-absence-logging` without a worktree.
- Treat only endpoint-matched `404` document absence as empty data.
- Preserve all other `404` responses as permanent failures.
- Never log credentials, ciphertext, authorization headers, raw request/response payloads, stack traces, arbitrary unknown-error messages, or database error text.
- Follow strict red-green-refactor: each production change requires a focused failing test observed before implementation.

---

### Task 1: Endpoint-specific monthly document absence

**Files:**
- Modify: `tests/unit/ozon-sync.test.ts`
- Modify: `src/lib/ozon/sync.ts`
- Modify: `tests/e2e/ozon.authenticated.spec.ts`
- Modify: `docs/ozon-integration.md`

**Interfaces:**
- Consumes: `OzonApiError.endpoint`, `status`, `code`, and `apiMessage`.
- Produces: `isMissingFinanceDocumentError(error: unknown): boolean`, now endpoint-aware while retaining its existing exported name.

- [ ] **Step 1: Replace the matcher unit case with an endpoint matrix**

Use literal `OzonApiError` fixtures:

```ts
const accepted = [
  new OzonApiError("/v1/finance/mutual-settlement", 404, {
    error: { code: "NOT_FOUND", message: "rpc error: desc = finance document not found" },
  }),
  new OzonApiError("/v1/finance/compensation", 404, {
    error: { code: "NOT_FOUND", message: "rpc error: desc = compensation document not found" },
  }),
  new OzonApiError("/v1/finance/decompensation", 404, {
    error: { code: "NOT_FOUND", message: "rpc error: desc = decompensation document not found" },
  }),
];

assert.deepEqual(accepted.map(isMissingFinanceDocumentError), [true, true, true]);
```

Add rejected literals for: compensation text on the decompensation endpoint,
an ordinary 404, a 500, prefixed/suffixed identities, and a non-report endpoint.

- [ ] **Step 2: Run the focused matcher test and observe RED**

Run:

```bash
npx tsx --test --test-name-pattern="endpoint-matched missing monthly finance documents" tests/unit/ozon-sync.test.ts
```

Expected: FAIL because compensation and decompensation identities are not
recognized.

- [ ] **Step 3: Implement the minimal endpoint-to-identity matcher**

In `src/lib/ozon/sync.ts`, add the fixed map:

```ts
const MISSING_FINANCE_DOCUMENT_BY_ENDPOINT: Partial<
  Record<OzonReadOnlyEndpoint, string>
> = {
  "/v1/finance/mutual-settlement": "finance document not found",
  "/v1/finance/compensation": "compensation document not found",
  "/v1/finance/decompensation": "decompensation document not found",
};
```

Require `OzonApiError`, status `404`, and a mapped endpoint. Compare only the
exact normalized code/message or a terminal `desc = <mapped identity>` segment.
Do not use a generic `document not found` substring.

- [ ] **Step 4: Run the focused matcher test and observe GREEN**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Extend the authenticated report regression**

In the existing missing-current-month report test, configure exact 404
sequences for mutual settlement and decompensation, leave compensation and
later cash-flow/buyout calls successful, and assert:

```ts
expect(syncResult.status).toBe("completed");
expect(syncResult.summary.errors).toEqual([]);
expect(syncResult.summary.reports).toMatchObject({ skipped: 2 });
expect(mock.requestCounts["/v1/finance/products/buyout"]).toBe(1);
```

This catches a regression where the loop stops after the accepted
decompensation absence.

- [ ] **Step 6: Document the three endpoint-specific identities**

Update `docs/ozon-integration.md` to list the exact endpoint/identity pairs and
state that every other 404 remains permanent.

- [ ] **Step 7: Run unit tests and commit Task 1**

Run:

```bash
npm run test:unit
```

Expected: all unit tests pass.

Commit:

```bash
git add src/lib/ozon/sync.ts tests/unit/ozon-sync.test.ts tests/e2e/ozon.authenticated.spec.ts docs/ozon-integration.md
git commit -m "fix: skip absent Ozon finance documents"
```

---

### Task 2: Credential-safe durable step failure logs

**Files:**
- Modify: `tests/unit/ozon-durable-runner.test.ts`
- Modify: `src/lib/ozon/durable-runner.ts`
- Modify: `docs/ozon-integration.md`

**Interfaces:**
- Produces:

```ts
export interface OzonSyncStepFailureLog {
  event: "ozon_sync_step_failed";
  runId: string;
  connectionId: string;
  stepKey: string;
  attemptCount: number;
  kind: PersistedOzonSyncErrorKind;
  retryable: boolean;
  status?: number;
  retryAfterMs?: number;
  endpoint?: string;
  code?: string | number;
  reason?: string;
}
```

- Extends `DurableOzonRunnerDependencies` with optional
  `logStepFailure?: (entry: OzonSyncStepFailureLog) => void`.

- [ ] **Step 1: Add a failing known-error logging test**

Use `runnerHarness` with an injected log recorder and throw:

```ts
new OzonApiError("/v1/finance/decompensation", 404, {
  error: {
    code: "NOT_FOUND",
    message: "rpc error: desc = decompensation document not found",
  },
});
```

Assert one literal structured object containing the step identifiers,
`kind: "client"`, `status: 404`, endpoint, safe code/message, and
`retryable: false`. Also assert the existing finish input is unchanged.

- [ ] **Step 2: Add a failing unknown-error redaction test**

Throw:

```ts
Object.assign(
  new Error("apiKey=secret raw response customer@example.com"),
  { responseBody: { authorization: "Bearer secret" } }
)
```

Assert the log contains only fixed step metadata plus
`kind: "unknown"`/`retryable: true`, and that serialized logs contain none of
`secret`, `raw response`, `customer@example.com`, `authorization`, or
`responseBody`.

- [ ] **Step 3: Run the focused logging tests and observe RED**

Run:

```bash
npx tsx --test --test-name-pattern="durable step failure log" tests/unit/ozon-durable-runner.test.ts
```

Expected: FAIL because the runner has no failure logger.

- [ ] **Step 4: Implement the safe log builder and injected logger**

In `src/lib/ozon/durable-runner.ts`:

1. Add `OzonSyncStepFailureLog` and the optional dependency.
2. Build the entry from the claimed step and already-sanitized classification.
3. Add endpoint/code/reason only for `OzonApiError`.
4. Add `reason` only for `PermanentOzonSyncError`,
   `OzonInvariantError`, and `OzonIncompleteResponseError`.
5. Add no caller-controlled fields for unknown, transport, or timeout errors.
6. Call `dependencies.logStepFailure?.(entry)` once before `finishStep`.
7. In `createProductionDependencies`, inject:

```ts
logStepFailure: (entry) =>
  console.error("Ozon sync step execution failed", entry),
```

Never pass the original `error` object to the logger.

- [ ] **Step 5: Run the focused logging tests and observe GREEN**

Run the command from Step 3.

Expected: both tests pass.

- [ ] **Step 6: Document log fields and hosting-log lookup**

Update `docs/ozon-integration.md` with the event name, safe fields, prohibited
fields, and guidance to correlate hosting logs using `runId`, `stepKey`, and
`attemptCount`.

- [ ] **Step 7: Run unit tests and commit Task 2**

Run:

```bash
npm run test:unit
```

Expected: all unit tests pass.

Commit:

```bash
git add src/lib/ozon/durable-runner.ts tests/unit/ozon-durable-runner.test.ts docs/ozon-integration.md
git commit -m "feat: log safe Ozon step failures"
```

---

### Task 3: Final verification and publication readiness

**Files:**
- Verify all modified files.

**Interfaces:**
- Consumes: Task 1 endpoint-aware matcher and Task 2 safe logger.
- Produces: a clean, reviewed branch ready for push and a draft PR.

- [ ] **Step 1: Run the verification suite**

Run:

```bash
npm run test:unit
npx playwright test tests/e2e/ozon.validation.spec.ts
npm run lint
npm run build
git diff --check origin/main...HEAD
```

Expected: tests and build pass; lint has no new errors or warnings.

- [ ] **Step 2: Review the branch diff**

Confirm:

- no broad 404 suppression;
- no raw error object reaches `console.error`;
- completed steps remain untouched;
- no credentials, ciphertext, headers, payloads, or stack traces appear in
  logs or persisted errors.

- [ ] **Step 3: Request code review**

Run a fix-round review focused on Critical/Important correctness, security,
retry semantics, and PII/credential leakage. Address every blocker and rerun
the affected verification.

- [ ] **Step 4: Report the authenticated-test constraint**

Do not run the destructive authenticated suite against the currently
configured hosted Supabase project. Record that the mock-based authenticated
report regression must run in CI or an isolated local/staging Supabase project.

- [ ] **Step 5: Push and open a draft PR only when requested**

Use the current branch `codex/ozon-report-absence-logging`. Include root cause,
behavioral change, safe-log contract, verification, and the authenticated-test
constraint in the PR body.

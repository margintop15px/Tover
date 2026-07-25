# Task 5 report: durable Ozon APIs and summary contract

## Delivered

- `POST /api/integrations/ozon/sync`
  - Authorizes the manager and connection with the user client before creating
    a service-role client.
  - Calls `begin_or_resume_ozon_sync_run` with the authorized connection and a
    resolved 30-day default date window.
  - Uses the database-returned active run ID, so concurrent manual requests
    naturally share the same run.
  - Passes an absolute deadline based on the exported
    `OZON_MANUAL_SYNC_BUDGET_MS` value of 25 seconds to the established durable
    runner and returns 202 for `running`/`retrying`, otherwise 200.
- `POST /api/integrations/ozon/sync/retry`
  - Requires `{runId}` and manager authorization.
  - Reads the run and its connection through the user client and verifies the
    requested run, workspace, connection, and provider share one scope.
  - Only then calls `retry_failed_ozon_sync_run_steps(p_run_id)` through the
    service-role client. The RPC retains the original run/date window and
    resets only its terminal `failed` steps.
- `POST /api/internal/integrations/ozon/recover`
  - Requires `OZON_SYNC_RECOVERY_SECRET`.
  - Returns fixed 503/401/500 errors and never calls `toRouteErrorResponse`.
  - Compares SHA-256 digests with `timingSafeEqual`, including different-length
    inputs, and calls `recoverOneOzonSyncStep` exactly once after authorization.
  - Exports `maxDuration = 110`, below the scheduler's 120-second timeout.
- Public projections
  - Composite RPC rows accept either an object or one-element array.
  - Durable aggregate summaries remain internal. Public summaries contain only
    the existing Ozon domain keys and `errors: string[]`, derived from step rows.
  - Recovery counts use registered persisted step keys/states. Error strings and
    `lastError` use only the step key plus normalized `kind`/`status`; raw error
    text and nested payloads are ignored.
  - The integration summary preserves existing fields and validation health,
    adds `recovery`, and safely projects recent durable run summaries.
  - Sync status types now include `retrying`.

## TDD evidence

Red runs were observed before each production slice:

- Missing `durable-sync` and `recovery` modules failed the first focused run.
- Missing public-summary and retry-scope helpers failed the second run.
- Missing internal recovery route failed the route-level run.
- Pending-state and retry-instant edge cases failed with the previous count and
  lexicographic timestamp behavior.
- An unregistered step key failed by inflating `failedStepCount`.

Green verification:

- `npm run test:unit`: 66 tests passed, 0 failed.
- `npx tsc --noEmit`: passed.
- Scoped ESLint over all Task 5 source and tests with `--max-warnings=0`: passed.
- `npm run build`: passed and emitted the sync, retry, and internal recovery
  routes.
- `git diff --check`: passed.

## Self-review

- Confirmed no Ozon route calls the old one-shot `syncOzonConnection`.
- Confirmed user-context authorization occurs before service-role construction
  in both manager routes.
- Confirmed worker run/step reads are scoped by run, workspace, connection, and
  provider.
- Confirmed RPC parameters and snake_case row fields match migration 020.
- Confirmed the 25-second claim deadline is passed to the established durable
  runner, while the manual routes allow a 60-second platform duration.
- Confirmed the internal route returns only `{processed}` on success and a fixed
  `{error}` on failures.
- Confirmed no UI, localization, operational docs, or migration files changed.

## Concerns

- Full-repository ESLint with `--max-warnings=0` still reports the pre-existing
  unused `carrotCake` variable in `scripts/seed.ts`; Task 5 scoped ESLint is
  clean.
- The established runner checks its absolute deadline before claiming the next
  step; it does not abort an already claimed domain request. A slow in-flight
  step can therefore outlive the 25-second claim budget and rely on its durable
  lease/recovery path if the hosting platform ends the request.
- No live Supabase/Ozon end-to-end test was run for this task. The production
  build, full unit suite, RPC contract tests, migration tests, and extracted
  route-helper tests are green.

## Independent review rulings

- No critical findings were reported.
- A suggested hard abort for an already claimed Ozon domain step was not
  adopted: Task 5 was given the established
  `runOzonSyncRunUntilDeadline(runId, absoluteDeadlineMs)` interface. Changing
  Task 4 client retry/domain execution interfaces is outside this task; the
  claim-deadline limitation is recorded above as a concern.
- Suggested `OzonSummaryShared` status-label and localization changes were not
  adopted because Task 5 explicitly excludes UI and localization files.
- Suggested large Next/Supabase route mocks were not adopted because Task 5
  explicitly requests focused pure/coordinator/route-helper seams and warns
  against giant framework/database mocks. The route helpers cover the required
  status, authorization scope, secret, one-step, and minimal-response behavior.

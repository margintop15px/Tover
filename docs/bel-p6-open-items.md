# Bel P6 — open items, decisions and findings

Phase P6 "Processors and effects" (`docs/bel-architecture.md` §9) landed on 2026-11-12 (plan: `docs/superpowers/plans/2026-11-12-bel-p6-processors.md`). This file keeps what the implementation left open: work not done, decisions made without a stakeholder, review findings that were deferred, and test observations. Remove an entry when it is resolved.

## Not done in P6 (by plan)

- Team screen UI and actions for chapter 05; Resend (05a), Cancel (05b), Accept (06); live cutover and retirement of the legacy invitation flow.
- SMTP environment wiring in `deployment/*/compose.yaml` and `tover.app`. The delivery worker registers no processor until cutover; `tover.invitations` is excluded from discovery.
- Migration 038 is applied only by `scripts/test-bel-workspace.sh`. Add it to the `identity.sh` / `production.sh` chains at cutover (R7). A dev REPL loading `tover.invitations` against the local DB lacks the tables until then.
- Partial unique index for one pending offer per (workspace, email) — add at cutover after the legacy resend retires (R6).
- "Fresh invitation reviewed after the latest removal": Tover has no removal events yet (chapter 35). Revisit at cutover.
- Per-attempt history rows; recovering an Unconfirmed outcome from the SMTP server (SMTP offers no lookup).
- Effect-level `:then`/`:catch` on action results (the Auth example in `docs/bel-dsl-proposal.md`) and the processor selector form remain proposed.

## Cutover checklist (decided-later items)

- Delivery continues after the inviting manager is demoted or removed (`requested_by` becomes the actor; the policy checks only the service principal). Board decision.
- `attempts-remaining` stays above 0 for a permanently failed delivery (`4 − attempts` per plan). Board decision.
- A delivery system without `:mailer` throws on send, so every invitation becomes Unconfirmed with nothing sent. Wire the mailer, or refuse to start the processor without one.
- One worker pass may open up to 100 concurrent SMTP connections; relay `421` responses become temporary retries. Tune batch size / concurrency.
- For user commands with effect descriptors: if the authority transaction fails after the owning store committed, recovery returns `fresh? false` and the effect is lost (never doubled). No current caller does this.

## Rulings made during implementation

Each with the cost if it is wrong.

| # | Ruling | Cost if wrong |
| --- | --- | --- |
| R1 | 038 grants `tover_journal` narrow invitation/directory reads and delivery-column updates; 036's "no directory access" statement narrowed. | A separate NOLOGIN role + login + env. |
| R2 | The workspace name is read through Tover coeffect `::workspace-name`, not the `::workspaces` model (its `::ordinary-user` policy rejects the service principal). | One coeffect replaced by a policy change. |
| R3 | `classify` walks the MessagingException chain for SMTP codes; `SendFailedException` without a code → `:uncertain`. | Some permanent failures shown Unconfirmed. |
| R4 | 038 adds `tover_app` INSERT policies on `bel_events`/`bel_receipts` for workspace scope, actor = JWT sub, active membership. | Any active member's runtime session can append journal rows for its workspace. |
| R5 | Nullable delivery fields are `{:optional true}` scalars, not `[:maybe …]`; omitting a `:set` field writes NULL. | Explicit nil writes may fail validation. |
| R6 | No unique pending-offer index; the SERIALIZABLE owning transaction covers concurrency. | Duplicate pending offers under concurrency. |
| R7 | 038 not added to `identity.sh` / `production.sh` chains in P6. | One-line chain addition at cutover. |
| R8 | Read-model clock binding named `:clock` (Bel forbids `:now`). | None. |
| R9 | Any registry including `tover.invitations.issue.model` must also load `tover.invitations.delivery.model` (issue maps delivery events; delivery requires issue). | Build fails loudly with "Undeclared mapped event". |
| R10 | `::due-deliveries` compares with the application clock (`:bel/now`), not Postgres `now()`; delivery timestamps are written from the app clock. | App-host clock skew shifts due times. |
| R11 | Runtime `retrying`: continuations retry `:retryable?` failures (SSI conflict) with the same command id up to 10 times, processor requests up to 3; jittered exponential backoff 20 ms doubling, capped at 1 s. | Under extreme contention an outcome can still be lost (delivery stays Unconfirmed); sends retry on the next tick. |
| R12 | `delivery-system` makes `:repo` optional (catalogue store only when given). | None. |
| Final #1 | Review finding "worker can call legacy public SECURITY DEFINER functions" judged not a defect: 033 revokes PUBLIC EXECUTE on every public definer function; integration test `journal-role-cannot-execute-public-definer-functions` guards it. | A definer function added later in `public` without a PUBLIC revoke becomes callable; the guard test catches it. |

## Deferred minor findings

### Processor runner (`src/bel/runtime.clj`)
- `processor-command-id` hashes `pr-str`; key order or print bindings could vary ids for equal maps.
- A handler returning a string or non-map collection is counted per element instead of one failure.
- A permanently failing item is retried and logged every second forever.
- `processor_test` setup has an unused `ids` parameter; the command-id test lacks `:scope`/`:id` variation.
- A scope `:user-id` is silently overridden by the trusted actor; could drop `:user-id` from allowed scope keys.
- No test pins the context merge order independently of the schema.

### Effect descriptors
- `effects_test` recovery/tuple tests still use `Thread/sleep` (flaky or vacuous on slow CI).
- The continuation trusted-context test asserts the principal only, not scope/actor equality.
- `continue!` catches `Exception`, not `Throwable`.
- A continuation command with a different `:scope` kind fails silently (only the type is logged).
- User command ids longer than ~90 characters make continuation ids exceed the 100-character cap silently.

### SMTP mailer (`src/tover/shared/email.clj`)
- An empty-string `:user` enables SMTP auth; use `(seq user)`.
- An unparseable `:to` is built inside the `try`, so `AddressException` → `:uncertain` (nothing sent; arguably `:permanent`).
- Unknown-host `MessagingException` → `:uncertain` (conservative).
- The ex-info cause may carry the SMTP response including the address; callers must not log the cause message.
- No implicit-TLS (port 465) option.

### Migration 038
- The invite UPDATE policy lets any owner/admin change any invite status (no `invited_by` check) — fine for cancel by any manager.
- Delivery insert accepts arbitrary lifecycle columns (status/attempts/submitted_at); could pin `status='queued'`, `attempts=0`.
- `tover_journal` has whole-table SELECT on organizations/invites; column grants would be tighter.
- Integration tests lack `tover_app` insert-path/RLS denial cases and a real-value `bel_recipient_language` test; the applies-twice test only counts objects.

### Invite Teammate slice
- Integration does not assert the 168 h lifetime; R4 negatives lack spoofed `actor_id` / non-active membership cases; no concurrent-invite integration test.
- R4 lets any active member (not only managers) append workspace journal rows via `tover_app`.
- Unknown recipient-membership status strings proceed to issue (not fail-closed).
- Expired-replace and different-role paths are unit-tested only.

### Final whole-branch review
- Review focus 1 (same due item processed twice) is tested sequentially only; add a two-worker concurrent integration test (pattern: `test/goods_journal_integration.clj`).
- The `Email` regex accepts addresses `InternetAddress` reads differently: `"x"<evil@x>` sends to `evil@x` while the stored email stays literal; `a@b,c` fails to parse → `:uncertain` (Unconfirmed, nothing sent). Reject `<>()[],;:"\`.
- `str/lower-case` uses the default JVM locale; a Turkish locale diverges from SQL `lower()`. Use `(.toLowerCase s java.util.Locale/ROOT)`.

## Test observations

- One full `clojure -M:dev:test` run (seed `1024827558`) failed 8 assertions with big-integer sign flips in `tover.shared.rdf-test/typed-values-preserve-rdf-identity-and-numeric-precision`, `tover.stock.store-test/derived-quantities-retain-integer-precision` and `tover.inventory.derivation-test/timeline-catches-supported-integer-overflow`. The same namespaces pass alone, and replaying the seed on a clean checkout (681/0) and with the P6 fix (682/0) passed. Not reproduced; investigate if it recurs.
- `clojure -M:fmt check` reports `src/bel/handler.clj` and `src/tover/shared/supabase.clj` as misformatted; they predate P6 and P6 did not touch them.
- `clj-kondo` reports `next.jdbc.result-set` as an unresolved namespace in `test/invitation_delivery_integration.clj` (warning; tests are not in the lint target).

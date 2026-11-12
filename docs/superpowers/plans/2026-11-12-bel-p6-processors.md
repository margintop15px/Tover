# Bel P6: Processors and Effects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bel runs automations (`defprocessor`) and effects with `:then`/`:catch` continuations, proven by an isolated chapter 05 slice: Invite Teammate issues an offer whose email is delivered by a processor with a durable four-attempt budget.

**Architecture:** A processor is an Event Modeling "todo list" automation: Bel polls a Tover coeffect for due work and executes the command requests the processor returns, under a service principal and with command ids Bel derives from the request (C2 makes reruns no-ops). Accepted state lives in the owning store, so restart, timers and retry waits need no queue or cursor. An effect descriptor `{:id :input :then :catch}` runs after a *fresh* acceptance only; its outcome is recorded by the continuation command. A crash between commit and outcome leaves the attempt Unconfirmed, which the board accepts. Invitation email goes through the same SMTP relay Supabase Auth uses (Jakarta Mail).

**Tech Stack:** Clojure, Malli, Bel runtime, bel-postgres, PostgreSQL 16, Jakarta Mail (Angus), Kaocha, Mailpit.

**Spec:** `docs/bel-architecture.md` (§2 Automation row, §5 C2/C6/C7, §6, §9 P6) and prooph board chapter 05 · Invite a teammate (`658a7f98-2197-41b3-a0b1-97cb4cc29b2d`, workspace `ec484260-1c12-45d7-a8a7-f0af8e2fc520`); `docs/bel-dsl-proposal.md` "Continuations and external effects"; `docs/bel-onboarding-mapping.md` row 05.

## Decisions taken with the user (2026-11-12)

- P6 = framework + chapter 05 as an **isolated** slice (excluded from discovery like `tover.workspaces.create`). The live Team screen and legacy `manage_workspace_invitation` stay untouched. Live cutover of 05/05a/05b/06 is a later phase.
- Invitation email is sent by Tover over SMTP, through the relay Supabase Auth already uses. P6 builds and tests the mailer; deployment env wiring waits for cutover (nothing live would use it).

## Global Constraints

- REPL-first per `AGENTS.md`: attach to or start an nREPL from this worktree before editing Clojure; dependency changes (Task 3) need a REPL restart. Never `:reload-all` / `refresh-all`. Unbalanced delimiters: `clj-paren-repair <file>`, never by hand.
- Every function carries `:malli/schema`. Prefer open map schemas (no `{:closed true}`).
- New migration file is exactly `supabase/migrations/038_invitation_delivery.sql`. Additive, idempotent (applies twice), wrapped in `BEGIN; … COMMIT;`. Never edit 033–037.
- No destructive database operations outside the disposable test cluster. Do not run `identity.sh up` or touch the local/production databases.
- One store per command (§2). Commands stay pure `[facts input] -> {:events :fx}`; no flow code in Tover (§1).
- C7: `:scope`, `:actor`, `:command-id`, `:now` and the service principal come from trusted context, never from input.
- Board numbers, verbatim: offer lifetime **168 hours**; per delivery request **4** submission attempts (1 initial + 3 retries); retry waits **1, 5, 30 minutes** after the preceding temporary failure; resend cooldown **60 seconds** after the last accepted request.
- Invitation roles: `"member"` (default) and `"admin"`; never `"owner"`. Recipient matching: `lower(btrim(email))`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Checks before each commit: `clojure -M:fmt check` (baseline failures only in `src/bel/handler.clj`, `src/tover/shared/supabase.clj`), `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` → 0 errors 0 warnings.

## Review Focus

1. **The same due item processed twice** (two worker passes before the first commits, restart mid-pass): one attempt recorded, one email. Pinned in Task 1 (derived command id) and Task 2 (no effect on recovery).
2. **A late outcome for an older attempt** must not overwrite a newer attempt's status. Pinned in Task 6 (`stale-outcome-is-ignored`).
3. **A retry that falls due at or after the offer's expiry** sends nothing and stops the request. Pinned in Task 6 (`expired-offer-stops-delivery`).
4. **The same recipient typed differently** (`" Ann@Example.COM "` vs `ann@example.com`) is the same offer. Pinned in Task 5 (`email-is-normalized`) and the Task 5 integration test.
5. **A mailer that throws something unexpected** (NPE, IO error) is Unconfirmed, never retried automatically. Pinned in Task 2 (non-`ExceptionInfo` → `:uncertain`) and Task 6 (`uncertain-is-unconfirmed`).

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/bel/core.clj` | `defprocessor` macro, registry entry `:processors`, `EffectDescriptor` schema |
| `src/bel/runtime.clj` | service principal, `:bel/now`, effect descriptors + continuations, `run-processors!` |
| `src/bel/delivery.clj` | worker loop also calls `runtime/run-processors!` |
| `src/tover/shared/email.clj` | `smtp-mailer`, `classify` (SMTP outcome → `:temporary`/`:permanent`/`:uncertain`) |
| `supabase/migrations/038_invitation_delivery.sql` | `invitation_deliveries`, grants/policies, two private lookup functions |
| `src/tover/invitations/persistence.clj` | table bindings, Tover coeffects |
| `src/tover/invitations/issue/model.clj` | Invite Teammate → Workspace Invitation Issued |
| `src/tover/invitations/delivery/model.clj` | processor, Send Invitation Email, outcome commands |
| `src/tover/invitations/delivery/email.clj` | message rendering (en/ru) and the `::send` effect |
| `src/tover/invitations/status/model.clj` | Invitation Status read model |
| `src/tover/shared/bel.clj` | register invitation tables; delivery system uses key `:directory` |
| `test/bel/processor_test.clj`, `test/bel/effects_test.clj`, `test/tover/shared/email_test.clj`, `test/tover/invitations/*_test.clj` | unit and scenario tests |
| `test/invitation_delivery_integration.clj` | Postgres + Mailpit integration, run by `scripts/test-bel-workspace.sh` |

---

### Task 1: `defprocessor` and the processor runner

**Files:**
- Modify: `src/bel/core.clj`, `src/bel/runtime.clj`, `src/bel/delivery.clj`
- Test: `test/bel/processor_test.clj`

**Interfaces:**
- Produces:
  - `(defprocessor name {:todo <coeffect-id>} [item] body)` registers `(:processors (core/operations))` as `{<qualified-id> {:todo kw :handler (fn [item] request-or-requests)}}`. The body returns one `ProcessorRequest` or a vector of them.
  - `core/ProcessorRequest` = `[:map [:type [:= :command]] [:id :qualified-keyword] [:scope [:map-of :keyword :uuid]] [:actor :uuid] [:input :map]]`.
  - `runtime/run-processors! [system] -> {:executed int :failed int}`.
  - The service principal: a context carrying `:bel/service <processor-id>` makes `with-authority` yield `{:service <processor-id>}` without calling the `Authority`. Only `run-processors!` and effect continuations (Task 2) set that key.
  - `runtime/processor-command-id [processor-id request] -> string`: `"p/"` + lowercase hex SHA-256 of `(pr-str [processor-id (:id request) (:scope request) (:input request)])`, 66 characters.
  - Coeffect `:bel/now` returns `(Instant/now)`. Read models need it; commands keep `:now` from facts.

- [ ] **Step 1: Write the failing tests** in `test/bel/processor_test.clj`, using the in-memory test `Store` from `runtime_test` (move it to `test/bel/fixture.clj` if it is private there):
  - `processor-runs-due-items`: a todo coeffect returns two items; the processor maps each to `{:type :command :id ::record :scope {:workspace-id w} :actor a :input {:n n}}`. After `run-processors!`, both commands are accepted and the result is `{:executed 2 :failed 0}`.
  - `rerun-is-idempotent`: run twice over the same items; the second run accepts nothing new (same receipts, events written once).
  - `service-principal-reaches-policy`: the command's policy receives `{:service ::the-processor}` and returns true only for it. The same command through `execute!` with an ordinary user context is `:forbidden`.
  - `one-failure-does-not-stop-others`: the first item's command throws `{:type :validation}`; the second is still accepted; the result is `{:executed 1 :failed 1}`.
  - `command-id-is-derived`: `(processor-command-id ::p req)` is 66 characters, starts with `"p/"`, and is stable for equal requests and different when `:input` differs.
  - `bad-request-shape-is-a-failure`: a handler returning `{:id ::x}` without `:scope` counts as failed and is logged, not thrown.

- [ ] **Step 2: Run and see them fail.** Run: `clojure -M:dev:test --focus bel.processor-test`. Expected: FAIL, `defprocessor` unresolved.

- [ ] **Step 3: Implement.**
  - `defprocessor` follows the pattern of `defcommand` registration.
  - In `run-processors!`, the todo coeffect is called as `(coeffect todo {:bel/system system} {})`. Each request is validated against `ProcessorRequest`, then `execute!` runs with context `(merge {:bel/system system :bel/service id :user-id actor} scope)` and invocation `{:command-id (processor-command-id id req) :input input}`.
  - Catch and log per item with `mu/log ::processor-failed :processor id :command (:id req) :error-type …`. Never log input.
  - `with-authority` checks `:bel/service` first.
  - `bel.delivery/start!`'s scheduled fn calls `(runtime/run-processors! system)` after `deliver-pending!`, inside its own try/catch so one failing does not skip the other.

- [ ] **Step 4: Run tests.** Run: `clojure -M:dev:test --focus bel.processor-test --focus bel.runtime-test`. Expected: PASS.

- [ ] **Step 5: Commit** `feat(bel): defprocessor todo-list automations with service principal`.

### Task 2: Effect descriptors with `:then` / `:catch`

**Files:**
- Modify: `src/bel/core.clj`, `src/bel/runtime.clj`
- Test: `test/bel/effects_test.clj`

**Interfaces:**
- Consumes: the Task 1 service principal (`:bel/service` stays in the context passed to continuations).
- Produces:
  - A plan's `:fx` accepts the existing `[effect-id input]` tuple (unchanged behaviour, C6) or `core/EffectDescriptor` = `[:map [:id :qualified-keyword] [:input :map] [:then CommandRef] [:catch CommandRef]]`, where `CommandRef` = `[:map [:type [:= :command]] [:id :qualified-keyword] [:input :map]]`.
  - After commit, for descriptor number `i` of the invocation:
    - success: execute `:then`'s command with input `(assoc input :outcome <effect return, or {} when not a map>)` and command id `(str command-id "/" i "/then")`;
    - failure: execute `:catch`'s command with input `(assoc input :problem {:type t :message m})` and command id `(str command-id "/" i "/catch")`.
  - `t` is `(:type (ex-data e))` when it is one of `#{:temporary :permanent :uncertain}`, otherwise `:uncertain` (including any non-`ExceptionInfo`). `m` is `(.getMessage e)` truncated to 200 characters.
  - Continuations run with the same context: scope, actor, `:bel/system` and `:bel/service`.
  - Descriptors run **only when the invocation was newly accepted**, never when `recover` returned a prior result. Tuple effects keep today's behaviour.

- [ ] **Step 1: Write the failing tests:**
  - `then-runs-after-success`: the effect returns `{:message-id "m1"}`; the `:then` command receives `:outcome {:message-id "m1"}` under command id `"<id>/0/then"`.
  - `catch-receives-problem-type`: the effect throws `(ex-info "x" {:type :permanent})`; the `:catch` command receives `:problem {:type :permanent :message "x"}`.
  - `unexpected-error-is-uncertain`: the effect throws `NullPointerException`; the catch command gets `:type :uncertain`.
  - `recovery-does-not-rerun-descriptors`: execute the same invocation twice; the effect ran once (a counting atom).
  - `undeclared-continuation-command-is-rejected`: a descriptor naming an unknown command fails the plan check with `:validation`, before commit.
  - `tuple-effects-unchanged`: an existing `[:bel/publish …]` style tuple still dispatches.

  Effects run on the existing virtual thread, so tests wait on a `promise` delivered by the continuation's fake command or effect (timeout 2 s).

- [ ] **Step 2: Run and see them fail.** Run: `clojure -M:dev:test --focus bel.effects-test`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - In `plan`, validate both shapes. Each descriptor's continuation command ids must exist in `(:commands definitions)`.
  - `accept` returns whether the result is fresh, e.g. `{:result … :fresh? bool}`. `execute!` passes descriptors to `dispatch-effects!` only when fresh.
  - Inside the virtual thread, a continuation failure is logged (`::continuation-failed`) and swallowed.

- [ ] **Step 4: Run tests.** Run: `clojure -M:dev:test --focus bel.effects-test --focus bel.runtime-test --focus bel.processor-test`. Expected: PASS.

- [ ] **Step 5: Commit** `feat(bel): effect descriptors with :then/:catch continuations`.

### Task 3: SMTP mailer

**Files:**
- Modify: `deps.edn`, adding `org.eclipse.angus/angus-mail {:mvn/version "2.0.3"}`. Restart the REPL afterwards.
- Create: `src/tover/shared/email.clj`
- Test: `test/tover/shared/email_test.clj`

**Interfaces:**
- Produces:
  - `(smtp-mailer {:host str :port int :user (maybe str) :password (maybe str) :from str :sender-name (maybe str) :starttls? bool}) -> (fn [{:to str :subject str :text str :html str}] -> {})`. It sends multipart/alternative UTF-8 with connection, read and write timeouts of 10000 ms. It throws `ex-info` with `{:type (classify e)}` and the original as cause.
  - `(classify Throwable) -> (enum :temporary :permanent :uncertain)`:
    - `SMTPAddressFailedException` or `SMTPSendFailedException` with return code 500–599 → `:permanent`;
    - the same with 400–499 → `:temporary`;
    - `MailConnectException` and `AuthenticationFailedException` (nothing was submitted) → `:temporary`;
    - anything else → `:uncertain`.

- [ ] **Step 1: Write the failing tests:** `classify` over constructed `SMTPAddressFailedException` (rc 550, 450), `SMTPSendFailedException` (rc 554, 451), `MailConnectException`, `AuthenticationFailedException`, `java.net.SocketTimeoutException` and `RuntimeException`, with the expected keywords above.
- [ ] **Step 2: Run and see them fail.** Run: `clojure -M:dev:test --focus tover.shared.email-test`. Expected: FAIL.
- [ ] **Step 3: Implement** `classify` and `smtp-mailer` using `jakarta.mail.Session` properties. When `:user` is present, use authentication. Real sending is covered in Task 7 against Mailpit.
- [ ] **Step 4: Run tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(email): SMTP mailer with outcome classification`.

### Task 4: Migration 038

**Files:**
- Create: `supabase/migrations/038_invitation_delivery.sql`
- Modify: `scripts/test-bel-workspace.sh` (apply 038 after 037, then apply 038 a second time), `deps.edn` `:bel-workspace-test` if a new main namespace is needed
- Create: `test/invitation_delivery_integration.clj`, called from the existing integration main the same way `goods_journal_integration` is

**Interfaces:**
- Produces (SQL):
  - Table `public.invitation_deliveries`:
    - `id uuid PRIMARY KEY`
    - `invitation_id uuid NOT NULL REFERENCES public.organization_invites(id) ON DELETE CASCADE`
    - `organization_id uuid NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE`
    - `requested_by uuid NOT NULL`
    - `requested_at timestamptz NOT NULL`
    - `status text NOT NULL CHECK (status IN ('queued','retrying','unconfirmed','submitted','failed','exhausted','stopped'))`
    - `attempts int NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 4)`
    - `next_attempt_at timestamptz`
    - `last_problem text CHECK (last_problem IN ('temporary','permanent','uncertain'))`
    - `attempted_at timestamptz`
    - `submitted_at timestamptz`
  - Index `invitation_deliveries_due_idx ON (next_attempt_at) WHERE status IN ('queued','retrying')`. RLS enabled.
  - `app_private.bel_recipient_membership(p_workspace_id uuid, p_email text) RETURNS text`: the recipient's membership status in that workspace, matched by `lower(btrim(u.email)) = lower(btrim(p_email))`, or NULL. It is `SECURITY DEFINER`, `SET search_path = ''`, EXECUTE to `tover_app` only.
  - `app_private.bel_recipient_language(p_email text) RETURNS text`: `profiles.language` of the matching account, or NULL. Also `SECURITY DEFINER`, EXECUTE to `tover_journal` only.
  - Grants for `tover_app`:
    - `SELECT` on `organization_invites` and `invitation_deliveries`;
    - `INSERT (id, organization_id, email, role_id, invited_by, status, expires_at, created_at)` on `organization_invites`, plus `UPDATE (status)`;
    - `INSERT` on `invitation_deliveries`.
  - Policies `TO tover_app` on both tables (read, insert, update) for rows whose `organization_id` passes `app_private.bel_workspace_membership(organization_id)` with role in owner/admin and status active. Inserts additionally require `invited_by` / `requested_by` = `request.jwt.claim.sub`.
  - Grants for `tover_journal`:
    - `USAGE ON SCHEMA public`;
    - `SELECT` on `organization_invites`, `organizations`, `invitation_deliveries`;
    - `UPDATE (status, attempts, next_attempt_at, last_problem, attempted_at, submitted_at)` on `invitation_deliveries`;
    - policies `TO tover_journal USING (true)` for those reads and the update.
  - No unique index on pending offers: legacy resend inserts rows that would violate it. SERIALIZABLE acceptance prevents competing offers instead.

- [ ] **Step 1: Write the failing integration tests:**
  - `migration-038-applies-twice`: the script's second apply succeeds.
  - `journal-role-cannot-read-auth-users`: as `tover_journal`, `SELECT 1 FROM auth.users` is denied.
  - `recipient-membership-matches-normalized-email`: an active member created with `ann@example.test` is found by `' ANN@example.test '`.
  - `recipient-language-is-null-for-unknown-email`.
  - `app-role-cannot-update-delivery-status`: `tover_app` gets an UPDATE denial on `invitation_deliveries`.
- [ ] **Step 2: Run and see them fail.** Run: `scripts/test-bel-workspace.sh`. Expected: the new tests fail (no migration yet); all earlier tests pass.
- [ ] **Step 3: Write `038_invitation_delivery.sql`.**
- [ ] **Step 4: Run.** Run: `scripts/test-bel-workspace.sh`. Expected: all green, including the restart run.
- [ ] **Step 5: Commit** `feat(db): 038 invitation delivery requests and private lookups`.

### Task 5: Issue a workspace invitation (isolated slice)

**Files:**
- Create: `src/tover/invitations/persistence.clj`, `src/tover/invitations/issue/model.clj`
- Modify: `src/tover/shared/bel.clj` (merge `invitations/tables` into the directory store), `dev/user.clj` and every other `:scan-exclude-ns` list (add `'tover.invitations`)
- Test: `test/tover/invitations/issue_test.clj`; one integration test in `test/invitation_delivery_integration.clj`

**Interfaces:**
- Consumes: migration 038; `::tover.workspaces.create.model/workspaces`; principal memberships from `tover.auth.bel/current`.
- Produces:
  - Models, both with `:store :directory` and `:policy ::invitation-reader`:
    - `::invitations` (id `:invitation-id`; fields `:workspace-id :email :role :invited-by :status :expires-at :created-at`);
    - `::deliveries` (id `:delivery-id`; fields `:invitation-id :workspace-id :requested-by :requested-at :delivery-status :attempts :next-attempt-at :last-problem :attempted-at :submitted-at`).
  - `invitations/tables` binds them to `public.organization_invites` and `public.invitation_deliveries`. Map `:delivery-status` to column `status` and `:role` to `role_id`.
  - Make `:next-attempt-at`, `:last-problem`, `:attempted-at` and `:submitted-at` optional `[:maybe …]` fields.
  - Both models live in `issue/model.clj`. Task 6 adds its four delivery events to `::deliveries` `:events` using fully qualified keywords (`:tover.invitations.delivery.model/…`), so neither namespace requires the other.
  - Policy `::invitation-manager`: the principal's membership for the scope workspace has role `#{:owner :admin}`.
  - Policy `::invitation-reader`: `::invitation-manager`, or `(some? (:service principal))`.
  - Coeffects in persistence:
    - `::open-invitation {:email e}`: the latest `pending` invitation for the scope workspace and normalized email, or nil;
    - `::recipient-membership {:email e}`: a status string or nil.
  - Event `::workspace-invitation-issued` data: `{:invitation-id :delivery-id :workspace-id :email :role :invited-by :status "pending" :expires-at :created-at :requested-by :requested-at :delivery-status "queued" :attempts 0 :next-attempt-at}`. It maps `:create` onto both models.
  - Event `::workspace-invitation-expired` `{:invitation-id :status "expired"}` maps `:set [:status]` onto `::invitations`.
  - Command `::invite-teammate`:
    - workspace scope, policy `::invitation-manager`;
    - input `{:email Email :role [:enum {:default "member"} "member" "admin"]}`, where `Email` decodes by trimming and lower-casing and requires `[:re #"^[^@\s]+@[^@\s]+$"]`, max 320;
    - cofx `:open`, `:recipient`, `:invitation-id :bel/uuid`, `:delivery-id :bel/uuid`.
  - Decision order:
    1. recipient `"active"` → throw `{:type :already-member}`;
    2. recipient `"suspended"` → throw `{:type :member-suspended}`;
    3. open offer not expired (`expires-at` after `now`) with the same role → `{}` (no events, no email);
    4. open offer not expired with a different role → throw `{:type :invitation-exists :invitation-id id}`;
    5. open offer expired → emit `::workspace-invitation-expired`, then issue;
    6. otherwise issue.

    Issuing sets `expires-at` = `now` + 168 h, `requested-at` = `next-attempt-at` = `created-at` = `now`, and `invited-by` = `requested-by` = `actor`.

- [ ] **Step 1: Write the failing scenario tests** with `runtime/decide`:
  - `issues-offer-with-queued-delivery`: checks every field, including `expires-at` exactly 168 h after `now`;
  - `email-is-normalized`: input `" Ann@Example.COM "` gives `:email "ann@example.com"`;
  - `role-defaults-to-member`;
  - `owner-role-is-rejected`: `:validation`;
  - `active-member-is-not-reinvited`;
  - `suspended-member-is-rejected`;
  - `same-role-repeat-emits-nothing`;
  - `different-role-reports-existing-offer`;
  - `expired-offer-is-replaced`: two events, expired first.

  Policy tests: `::invitation-manager` true for owner/admin, false for member and for a different workspace.
- [ ] **Step 2: Run and see them fail.** Run: `clojure -M:dev:test --focus tover.invitations.issue-test`. Expected: FAIL.
- [ ] **Step 3: Implement** models, events, command and coeffects.
- [ ] **Step 4: Integration test** `invite-teammate-commits-offer-and-delivery`: run `execute!` as an owner session (reuse the workspace-creation fixtures). Rows exist in both tables, and a retry with the same command id returns the same receipt. A second invite with `"ANN@example.test"` and the same role adds no row. Run: `scripts/test-bel-workspace.sh`. Expected: green.
- [ ] **Step 5: Run unit tests.** Run: `clojure -M:dev:test --focus tover.invitations.issue-test`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(invitations): isolated Invite Teammate slice`.

### Task 6: Invitation delivery processor and outcome commands

**Files:**
- Create: `src/tover/invitations/delivery/model.clj`, `src/tover/invitations/delivery/email.clj`
- Modify: `src/tover/invitations/persistence.clj` (add coeffects `::due-deliveries`, `::recipient-language`)
- Test: `test/tover/invitations/delivery_test.clj`

**Interfaces:**
- Consumes: Tasks 1, 2, 3 and 5 interfaces.
- Produces:
  - Coeffect `::due-deliveries` `{}` → up to 100 rows `{:delivery-id :workspace-id :requested-by :attempts}` where status is in `queued`/`retrying` and `next_attempt_at <= now()`, ordered by `next_attempt_at`.
  - Coeffect `::recipient-language {:email e}` → `"en"`, `"ru"` or nil.
  - Processor:

    ```clojure
    (defprocessor invitation-delivery {:todo ::persistence/due-deliveries}
      [{:keys [delivery-id workspace-id requested-by attempts]}]
      {:type :command :id ::send-invitation-email :scope {:workspace-id workspace-id}
       :actor requested-by :input {:delivery-id delivery-id :attempt (inc attempts)}})
    ```

  - Policy `::delivery-service`: `(some? (:service principal))`.
  - Command `::send-invitation-email`:
    - policy `::delivery-service`;
    - input `{:delivery-id :uuid :attempt [:int {:min 1 :max 4}]}`;
    - cofx: `:delivery` (`::deliveries`), `:invitation` (`::invitations` by the delivery's `:invitation-id`), `:workspace` (`::workspaces`), `:language` (`::recipient-language` by the invitation email).
  - Its decision:
    1. Delivery status not in `queued`/`retrying`, or `attempts` ≠ `attempt − 1` → `{}`.
    2. Invitation status not `"pending"`, or `now` not before `expires-at` → `::invitation-delivery-stopped` `{:delivery-id :delivery-status "stopped" :next-attempt-at nil}`.
    3. Otherwise → `::invitation-delivery-attempted` `{:delivery-id :attempts attempt :delivery-status "unconfirmed" :next-attempt-at nil :attempted-at now}`, with fx:

       ```clojure
       {:id ::email/send
        :input {:invitation-id .. :to email :language (or language "en") :workspace-name .. :role .. :expires-at ..}
        :then {:type :command :id ::record-email-submitted :input {:delivery-id .. :attempt ..}}
        :catch {:type :command :id ::record-email-failed :input {:delivery-id .. :attempt ..}}}
       ```

  - Command `::record-email-submitted`:
    - policy `::delivery-service`; input `{:delivery-id :attempt :outcome {:optional true} :map}`;
    - when the delivery has `attempts` = `attempt` and status `"unconfirmed"` → `::invitation-email-submitted` `{:delivery-id :delivery-status "submitted" :submitted-at now}`, else `{}`.
  - Command `::record-email-failed`:
    - input `{:delivery-id :attempt :problem [:map [:type [:enum :temporary :permanent :uncertain]] [:message :string]]}`;
    - same staleness rule; emits `::invitation-email-failed` `{:delivery-id :delivery-status s :last-problem (name type) :next-attempt-at n}`;
    - `:temporary` with attempt < 4 → `"retrying"`, `n` = `now` + `[1 5 30]` minutes at index `attempt − 1`;
    - `:temporary` with attempt 4 → `"exhausted"`, `n` nil;
    - `:permanent` → `"failed"`, `n` nil;
    - `:uncertain` → `"unconfirmed"`, `n` nil.
  - Model mappings: each delivery event `:set`s exactly the fields it carries besides `:delivery-id`.
  - `email.clj`:
    - `(message origin input) -> {:to :subject :text :html}`. The subject and body come from `tover.shared.i18n` with `*locale*` bound to `(:language input)`. They name the workspace, the offered role and the expiry date (ISO date, UTC), and link to `(str origin "/invitations/" invitation-id)`. HTML-escape the workspace name.
    - `(defmethod runtime/effect! ::send [_ {:keys [resources]} input] ((:mailer resources) (message (:origin resources) input)) {})`.

- [ ] **Step 1: Write the failing scenario tests** with `runtime/decide`:
  - `processor-maps-due-row`: call the processor handler directly;
  - `first-attempt-is-recorded-and-sends`: checks the event and the fx descriptor, including `:then`/`:catch` ids;
  - `expired-offer-stops-delivery`: `now` equals `expires-at`;
  - `cancelled-offer-stops-delivery`: status `"revoked"`;
  - `already-attempted-is-noop`;
  - `submitted-records-only-current-attempt`;
  - `stale-outcome-is-ignored`: attempt 1 outcome after attempts = 2;
  - `temporary-failures-follow-schedule`: attempts 1, 2, 3 give `next-attempt-at` = `now` + 1, 5 and 30 minutes;
  - `fourth-temporary-failure-exhausts`;
  - `permanent-failure-stops`;
  - `uncertain-is-unconfirmed`.

  Message tests: `ru` subject differs from `en`; nil language falls back to `en`; the link is exact; a workspace named `<b>` is escaped in the HTML.
- [ ] **Step 2: Run and see them fail.** Run: `clojure -M:dev:test --focus tover.invitations.delivery-test`. Expected: FAIL.
- [ ] **Step 3: Implement.** Add the Russian strings to the i18n messages.
- [ ] **Step 4: Run tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(invitations): delivery processor with bounded email retries`.

### Task 7: Invitation Status, end-to-end wiring and docs

**Files:**
- Create: `src/tover/invitations/status/model.clj`, `test/tover/invitations/status_test.clj`
- Modify:
  - `src/tover/shared/bel.clj`: `delivery-system` uses store key `:directory` (a PgStore on the journal datasource with workspace, account and invitation tables) and `:journal :directory`, replacing `:journal-writer`; it takes optional `:mailer` and `:origin` into `:resources`;
  - `test/invitation_delivery_integration.clj`, `scripts/test-bel-workspace.sh` (start a disposable `axllent/mailpit:v1.27.8` with `-p 127.0.0.1::1025 -p 127.0.0.1::8025`, export its ports, stop it in `cleanup`);
  - `docs/bel-architecture.md` (§2 Automation row, §5 C6 note, §9 P6 "done 2026-11-xx"), `docs/bel-onboarding-mapping.md` status paragraph, `docs/bel-dsl-proposal.md` (mark processors/descriptors implemented and describe the todo-list form).

**Interfaces:**
- Consumes: everything above.
- Produces: read model `::invitation-status`:
  - workspace scope, policy `::invitation-manager`;
  - input `{:email Email}`;
  - cofx: `:invitation` (Tover coeffect `::latest-invitation`: the newest invitation for scope and email, or nil), `:delivery` (`::latest-delivery`: the newest delivery for that invitation, or nil), `:now :bel/now`.
  - Output: nil when no invitation, else:

    ```clojure
    {:invitation-id :email :role
     :status (enum "pending" "expired" "accepted" "revoked")
     :expires-at
     :delivery {:status (enum "queued" "submitted" "failed" "unconfirmed" "stopped")
                :attempts-remaining int :next-attempt-at (maybe inst) :next-resend-at inst}}
    ```

  - A stored `"pending"` whose `expires-at` is not after `now` reads as `"expired"`.
  - Delivery status mapping: `retrying`/`exhausted`/`failed` → `"failed"`; others map as-is. `attempts-remaining` = `4 − attempts`. `next-resend-at` = `requested-at` + 60 s.

- [ ] **Step 1: Write the failing status scenario tests:** `pending-shows-queued`; `retrying-shows-failed-with-next-attempt`; `exhausted-has-zero-remaining`; `past-deadline-reads-expired`; `resend-time-is-60s-after-request`; `no-invitation-is-nil`.
- [ ] **Step 2: Run and see them fail, then implement, then pass.** Run: `clojure -M:dev:test --focus tover.invitations.status-test`.
- [ ] **Step 3: Write the end-to-end integration tests** (delivery system on the `tover_journal` login, processors loaded explicitly):
  - `issued-invitation-is-submitted`: the owner invites; `run-processors!` with a stub mailer that records messages; wait for status `"submitted"` (poll at most 5 s); exactly one message, to the normalized address.
  - `temporary-failure-schedules-retry`: the stub throws `{:type :temporary}`; status becomes `retrying`, `attempts` 1, `next_attempt_at` about 1 min ahead. An immediate second `run-processors!` sends nothing.
  - `second-pass-does-not-resend`: two passes back to back give one message.
  - `smtp-mailer-reaches-mailpit`: `smtp-mailer` against the disposable Mailpit; `GET /api/v1/search?query=to:<addr>` returns 1 message whose subject matches.
- [ ] **Step 4: Run everything.**
  - `scripts/test-bel-workspace.sh`: expected all green, including the restart run;
  - `clojure -M:dev:test`: expected 0 failures;
  - fmt and kondo per Global Constraints;
  - the Playwright `identity` and `management` configs, unchanged behaviour, green.
- [ ] **Step 5: Update docs**, then commit `feat(invitations): invitation status, delivery wiring and P6 docs`.

---

## Explicitly not in P6

- Team screen UI and actions for chapter 05; resend (05a), cancel (05b), accept (06); live cutover and legacy retirement.
- SMTP env wiring in `deployment/*/compose.yaml` and `tover.app`. The worker carries no registered processor until cutover.
- "Fresh invitation reviewed after the latest removal": Tover has no removal events or state yet (chapter 35). Revisit at cutover.
- Per-attempt history rows; recovering an Unconfirmed outcome from the SMTP server (SMTP offers no lookup).

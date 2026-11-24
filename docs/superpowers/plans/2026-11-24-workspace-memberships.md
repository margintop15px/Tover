# Workspace Memberships (board chapters 34–39) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Owners and Admins change roles, suspend, reinstate and remove members from the Team page; members leave from workspace settings; Owners grant and transfer ownership.

**Architecture:** One new domain folder `src/tover/memberships` (board context *Workspace Memberships*). Each command is a pure Bel `defcommand` deciding from coeffects; events map onto the existing `memberships` model. Migration `002` adds the membership revision, the columns the Team row needs, row policies for manager and self writes, and a definer function that locks a workspace's active Owners for the "at least one active Owner" rule. The Team page (`tover.invitations.team`) gains per-row membership actions.

**Tech Stack:** Clojure, Bel (`defcommand`/`defevent`/`defmodel`/`defreadmodel`/`defaction`), PostgreSQL with RLS via `bel-postgres`, Kaocha, Playwright.

**Spec:** the prooph board, chapters `34 · Change workspace roles` … `39 · Transfer workspace ownership` (workspace `ec484260-1c12-45d7-a8a7-f0af8e2fc520`). Element details hold Input/Fields/Rules/Rejections; slice details hold Decisions and Examples. Read each slice with `get_chapter` + `slice_ids` before its task. This plan never restates board rules; when they disagree, the board wins.

## Global Constraints

- Every slice example becomes one `deftest`, named after the example title in kebab-case.
- Command input field names follow the board: `membership-id`, `expected-revision`, `role`, `reason`, `my-expected-revision`; the board's `attempt id` is the existing form field `command-id`; `workspace-id` is checked against the selected workspace exactly as `tover.invitations.team.web/command` does.
- `reason`: optional text, max 500 characters; blank is stored as absent.
- Rejection `:type` keywords: `:not-permitted`, `:own-membership`, `:changed-since-review`, `:not-active`, `:not-suspended`, `:already-removed`, `:last-owner`, `:already-owner`, `:not-a-member`. All map to HTTP 409 in `team/problems` except `:not-permitted` (403).
- Roles in data are lowercase strings `"owner" "admin" "member"` as today.
- Actions return command descriptors; no inline `runtime/execute!` (AGENTS.md).
- New migration is `supabase/migrations/002_workspace_memberships.sql`, no `BEGIN`/`COMMIT`.
- Malli maps stay open. UI components follow the `[props]` rule. All user-visible text goes through `i18n/t` with entries in `resources/i18n/messages.json` (en and ru).
- REPL-first: start `clojure -M:dev:nrepl` in this worktree and use `(user/start!)` after edits.

## Review Focus

1. Two Owners leaving or demoting themselves at the same moment must not leave zero active Owners — Task 1 integration test `concurrent-owner-departure-keeps-an-owner`.
2. A Team page open in another tab after the actor lost Admin must not still act — covered by commit-time policy; Task 8 web test `demoted-manager-form-is-refused`.
3. A suspended or removed person's open live page must stop receiving updates — Task 4 test `suspended-member-loses-the-workspace` asserts `access/current-identity` drops the workspace.
4. Removing then re-inviting must not let the pre-removal invitation be accepted — Task 5 test `old-invitation-cannot-bring-them-back`.
5. Retried command ids after a later opposite change must return the original outcome without reapplying — one `retry-…` test per command, run through `runtime/execute!` with the fixture store so receipts are exercised.

---

### Task 1: Migration, membership model and persistence

**Files:**
- Create: `supabase/migrations/002_workspace_memberships.sql`
- Create: `src/tover/memberships/model.clj`, `src/tover/memberships/persistence.clj`
- Modify: `src/tover/invitations/accept/model.clj` (memberships `defmodel` schema and event map; join sets `revision`)
- Modify: `src/tover/invitations/persistence.clj` (`::own-membership`, `::team` return the new columns)
- Modify: `test/tover/invitations/fixtures.clj` (in-memory versions of the new coeffects)
- Test: `test/membership_integration.clj` (run by `scripts/test-bel-workspace.sh`), `test/tover/memberships/model_test.clj`

**Interfaces:**
- Produces SQL: columns `memberships.revision bigint NOT NULL DEFAULT 1`, `removal_kind text CHECK (removal_kind IN ('removed','left'))`, `suspended_at timestamptz`, `suspension_reason text`; `app_private.bel_active_owners(p_workspace_id uuid) RETURNS SETOF uuid` (locks active Owner rows `FOR UPDATE`, callable by any active member of the workspace); `bel_workspace_team` additionally returns `membership_id, revision, removal_kind, suspended_at, suspension_reason`; policies `membership_manage` (UPDATE by `bel_manager(workspace_id)`; rows with role `owner`, before or after, only when the caller is an active Owner) and `membership_leave` (UPDATE own active row to `removed`); column grants for the new columns.
- Produces Clojure (`tover.memberships.model`):
  - `Membership` schema: `:membership-id :workspace-id :user-id :role :status :revision` plus optional `:removed-at :removal-kind :suspended-at :suspension-reason`.
  - `(next-revision membership) -> int` — `(inc (:revision membership 0))`.
  - `(check-target! target expected-revision statuses) -> nil` — throws `:changed-since-review` when revisions differ, `:not-active`/`:not-suspended`/`:already-removed` when status not in `statuses`.
  - `(may-manage? actor-role target-role) -> boolean` — owner: any; admin: target not owner.
  - `(reason input) -> string|nil` — trimmed, blank→nil, >500 throws `:reason-too-long`.
  - Policies `::manager` (active owner/admin in scope) and `::owner` (active owner) and `::member` (any active membership).
- Produces coeffects (`tover.memberships.persistence`): `::target` `{:membership-id}` → `Membership` or nil, scoped to the workspace; `::own` → actor's `Membership`; `::active-owners` → vector of user ids, locked.

- [ ] **Step 1:** Write `model_test.clj` tests `check-target-rejects-stale-revision`, `check-target-rejects-wrong-status`, `admin-may-not-manage-owner`, `reason-blank-is-absent`, `reason-over-500-rejects`. Run `clojure -M:dev:test --focus tover.memberships.model-test`; expect FAIL (namespace missing).
- [ ] **Step 2:** Implement `tover.memberships.model`; run the focus again; expect PASS.
- [ ] **Step 3:** Write migration 002 and the persistence coeffects; extend `bel_workspace_team` with `CREATE OR REPLACE`. Extend the `memberships` defmodel event map with the seven new events by fully qualified keyword (Tasks 3–7 define them) and make `workspace-member-joined` carry `:revision` (`next-revision` of the own membership) and clear `:removal-kind`.
- [ ] **Step 4:** Write `membership_integration.clj` tests `manager-cannot-update-owner-row-as-admin`, `member-cannot-update-other-rows`, `self-leave-only-sets-removed`, `concurrent-owner-departure-keeps-an-owner` (two connections lock via `bel_active_owners`; the second gets a serialization failure). Run `scripts/test-bel-workspace.sh`; expect PASS.
- [ ] **Step 5:** Run the full suite `clojure -M:dev:test`; expect the invitation tests still PASS with the extra columns. Commit `feat(memberships): revision, row policies and owner lock`.

### Task 2: Board slices in progress

- [ ] **Step 1:** Set all 20 slices of chapters 34–39 to `in-progress` with `update_slice`. No commit.

### Task 3: 34 · Change workspace roles

**Files:**
- Create: `src/tover/memberships/role/model.clj`
- Test: `test/tover/memberships/role_test.clj`

**Interfaces:**
- Consumes: Task 1 model and coeffects.
- Produces: `::workspace-member-role-changed` event (board Fields); command `::change-workspace-member-role` (`:scope :workspace`, `:policy ::model/manager`, cofx `[:target ::persistence/target] [:own ::persistence/own] [:owners ::persistence/active-owners]`); `(settable-roles actor target) -> [:vector :string]` used by Task 8.

- [ ] **Step 1:** Write one test per example of slices *Review a member's role…* (via `settable-roles`) and *Change the reviewed membership role* (via `runtime/decide`), e.g. `owner-promotes-a-member` asserts the event data `{:role "admin" :previous-role "member" :revision 4}`; `last-active-owner-cannot-demote-themselves` asserts `:last-owner`. Run focus; expect FAIL.
- [ ] **Step 2:** Implement. Order of checks: permission, target revision/status, same role → `{}`, last owner. Run focus; expect PASS.
- [ ] **Step 3:** Add `retry-returns-the-original-outcome` through `runtime/execute!` with the fixture store. PASS. Commit `feat(memberships): change workspace roles`.

### Task 4: 35 · Suspend and reinstate workspace access

**Files:**
- Create: `src/tover/memberships/suspension/model.clj`
- Test: `test/tover/memberships/suspension_test.clj`

**Interfaces:**
- Produces: events `::workspace-member-suspended` (sets `status "suspended"`, `revision`, `suspended-at`, `suspension-reason`) and `::workspace-member-reinstated` (sets `status "active"`, `revision`, clears suspension fields); commands `::suspend-workspace-member`, `::reinstate-workspace-member`; `(can-suspend? actor target)`, `(can-reinstate? actor target)`.

- [ ] **Step 1:** Tests for every example in the five slices. `suspended-member-loses-the-workspace` uses the fixture store and asserts `tover.identity.access/current-identity` no longer selects workspace W while V still opens. Run; FAIL.
- [ ] **Step 2:** Implement; PASS. Commit `feat(memberships): suspend and reinstate`.

### Task 5: 36 · Remove workspace access

**Files:**
- Create: `src/tover/memberships/removal/model.clj`
- Test: `test/tover/memberships/removal_test.clj`

**Interfaces:**
- Produces: `::workspace-member-removed` (sets `status "removed"`, `removed-at now`, `removal-kind "removed"`, `revision`); command `::remove-workspace-member`; `(can-remove? actor target)`.

- [ ] **Step 1:** Tests for the three slices' examples. `old-invitation-cannot-bring-them-back` decides `accept/accept-workspace-invitation` with the membership produced by the removal and asserts `:invitation-not-pending` with status `"invalidated"`. FAIL.
- [ ] **Step 2:** Implement; PASS. Commit `feat(memberships): remove members`.

### Task 6: 37 · Leave a workspace

**Files:**
- Create: `src/tover/memberships/departure/model.clj`, `src/tover/memberships/departure/web.clj`
- Modify: `src/tover/workspaces/currency/web.clj` (settings page renders the Leave section)
- Test: `test/tover/memberships/departure_test.clj`

**Interfaces:**
- Produces: `::workspace-member-left` (as removal with `removal-kind "left"`); command `::leave-workspace` (`:policy ::model/member`, input without `membership-id`); read model `::departure-eligibility` → `{:workspace-name :role :revision :can-leave? :blocked-by-last-owner?}`; action `::leave` whose `:then` is the workspace chooser page `:tover.workspaces.select.web/…` with input `{:left <workspace-id>}`.

- [ ] **Step 1:** Tests for the three slices' examples, including `concurrent-co-owner-departure` (decide twice with owners `[ann dee]` then `[dee]`; second is `:last-owner`) and `no-workspaces-left` (chooser shows Create Workspace). FAIL.
- [ ] **Step 2:** Implement model, settings section and chooser note; PASS. Commit `feat(memberships): leave a workspace`.

### Task 7: 38 · Grant and 39 · Transfer ownership

**Files:**
- Create: `src/tover/memberships/ownership/model.clj`
- Test: `test/tover/memberships/ownership_test.clj`

**Interfaces:**
- Produces: `::workspace-ownership-granted` (sets target `role "owner"`, `revision`); `::workspace-ownership-transferred` emitted as two model updates — one event per membership is simplest in Bel, so the transfer command emits `::workspace-ownership-transferred` carrying both sides and the memberships defmodel maps it only for the target, plus `::workspace-member-role-changed` for the actor (`role "admin"`), committed together. Commands `::grant-workspace-ownership`, `::transfer-workspace-ownership` (`:policy ::model/owner`); `(can-grant? actor target)`, `(can-transfer? actor target)`.

- [ ] **Step 1:** Tests for the six slices' examples. `actor-changed-since-review` asserts `:not-permitted` when the actor's own row is now admin; `changed-since-review` covers `my-expected-revision`. FAIL.
- [ ] **Step 2:** Implement; PASS. Commit `feat(memberships): grant and transfer ownership`.

### Task 8: Team page row actions

**Files:**
- Create: `src/tover/memberships/web.clj` (one `defaction` per Team command, built on `team/command`)
- Modify: `src/tover/invitations/team/model.clj` (`Member` gains `:membership-id :revision :removal-kind :suspended-at :suspension-reason` and an `:actions` map computed from the actor: `{:roles [...] :suspend? :reinstate? :remove? :grant? :transfer?}`; `team` takes the actor's own membership)
- Modify: `src/tover/invitations/team/web.clj` (row: role select + Save; `<details>` confirms for Suspend, Reinstate, Remove, Make owner, Transfer ownership, each with optional reason; status label Left vs Removed; outcome message from the command result; `problems` gains the new rejection types)
- Modify: `resources/i18n/messages.json`, `resources/public/app.css` only if the existing `management-*` classes are not enough
- Test: `test/tover/invitations/web_test.clj`, `test/browser/management.spec.mjs`

- [ ] **Step 1:** Web tests: `owner-row-actions` (rendered HTML for owner Ann shows the expected controls per row from Task 3–7 examples), `admin-sees-no-owner-controls`, `demoted-manager-form-is-refused` (POST after demotion → 403 page), `self-demotion-lands-on-home`. FAIL.
- [ ] **Step 2:** Implement; PASS. Run `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj` and `clojure -M:fmt check`; both clean.
- [ ] **Step 3:** Playwright in `management.spec.mjs`: owner promotes, suspends, reinstates, removes and re-invites a member; member leaves from settings; owner transfers ownership. Run `npx playwright test -c test/browser/management.config.mjs`; PASS.
- [ ] **Step 4:** Commit `feat(memberships): team page actions`.

### Task 9: Finish

- [ ] **Step 1:** `clojure -M:dev:test`, `scripts/test-bel-workspace.sh`, lint, fmt: all pass.
- [ ] **Step 2:** README implementation status: memberships implemented.
- [ ] **Step 3:** Board: set every slice whose tests pass to `ready`; leave a comment on the slice's command element for anything not covered.
- [ ] **Step 4:** Commit `docs: memberships ready`.

# Onboarding cutover brief (temporary)

Shared context for every agent working on the onboarding cutover. Read this whole file first; it replaces exploring the repo from scratch. Deleted when the cutover is merged.

## Goal

Board chapters 01, 01a, 02, 02a, 02b, 02c, 03, 03a, 04, 05, 05a, 05b, 06, 07 (all slices `planned`) move onto the Bel architecture. All legacy code for users, credentials, sessions, profiles, workspaces, memberships, invitations and workspace selection is deleted. Inventory, catalogues, stock, operations and integrations may stay functionally broken, but **must keep compiling** so the app starts; their failing test suites are skipped in `tests.edn` with a reason. Everything deploys together later. Existing UI components, CSS classes and i18n keys are reused wherever possible.

Out of scope: chapter 29 (help a teammate recover access) and 34–39 (membership management). Their legacy code is deleted, not rebuilt.

## Decisions (do not reopen)

1. **Existing Postgres rows are kept.** New migrations only, numbered per chunk (below). Never edit 001–038. Never run destructive SQL against a non-disposable database.
2. **Supabase GoTrue stays the identity provider** (board lane "Identity Provider" is an external system). Reach it only through Bel effects (`:fx` with `:then`/`:catch`). Reuse the HTTP code from `src/tover/auth/supabase.clj` and `src/tover/shared/supabase.clj`, moved under `tover.identity.provider`. GoTrue keeps sending confirmation and recovery emails with `deployment/*/templates/*.html`.
3. **Sessions stay in `app_private.sessions`** with the `bel.session` cookie (`tover-session`) and the encrypted token store (AES-GCM, row lock, 30-day absolute expiry) from `src/tover/auth/sessions.clj`, trimmed.
4. **Request identity is read from Postgres on every request**: the session row read that already happens, joined with active memberships and the profile. JWT custom claims are not used for identity. The `custom_access_token_hook`, `refresh-claims!` and the `:tover.auth/refresh-claims` effect go away. If no workspace is selected, the user goes to the chooser; **never fall back to the first membership** (legacy bug in `security.clj:57-61`).
5. **Route access** lives in `tover.identity.access`. Keep the route-data key `:tover/access` and its values (`:optional :authenticated :workspace :password :finish :logout :profile`, adjust as needed), and keep the request shape `:bel/context {:user-id :workspace-id :role ...}` and `:tover/identity`, so inventory/catalogue routes compile unchanged.
6. **One store per command**: everything here is on `:directory` (Postgres). Cross-store writes are processors.
7. **Namespaces by board context**, one folder per slice with `model.clj` + `web.clj`:
   - `tover.identity.<slice>`: 01, 01a, 02, 02a, 02b, 02c
   - `tover.accounts.profile`: 03, 03a (exists)
   - `tover.workspaces.<slice>`: 04 (`create`, exists), 07 (`select`)
   - `tover.invitations.<slice>`: 05 (`issue`, `delivery`, `status` exist), 05a, 05b, 06
8. **UI**: copy legacy page markup from `git show main:<path>` into the new views; reuse `tover.shared.ui.core`, `tover.shared.ui.forms`, `.identity*`, `.management-*`, `.profile-*` classes in `resources/public/app.css`. New CSS only when unavoidable, in a block headed `/* <context> */`.

## Board

Workspace `ec484260-1c12-45d7-a8a7-f0af8e2fc520`. Follow `docs/board-process.md`.

- Read one slice at a time: `get_chapter` with `slice_ids: ["<id>"]`. Full chapter reads time out; MCP calls fail intermittently, so retry.
- Element `details` hold fields/rules (template layout); slice `details` hold step rules and `## Examples`. Where details are empty, the element/slice description and slice prose are the spec.
- At start set your slices `in-progress`; when implemented with passing tests set them `ready`. Never set `planned`, `reviewed`, `deployed`.
- A gap or contradiction you cannot resolve from the board: add a task comment on the slice (`add_slice_comment` with `is_task: true`), pick the safest reading, note it in your report. Do not invent business rules silently.
- You may fill empty element details on your own chapters using the templates (Command: Input/Rules/Rejections; Event and Information: Fields/Rules) when you derive them from the slice text.

| Ch | Chapter id | Slices (id: label) |
| --- | --- | --- |
| 01 | `ff146ef1-065e-45a9-a59e-130830f43616` | `a3720010-9ac4-41c0-935c-333002c6e574` Start signup; `940c36d0-fcb9-44f3-8600-ec7ebad9450b` Register an unconfirmed account; `6d60ddad-b5c0-4f66-90ff-0603e3155358` Check your email; `8d2ba11d-6614-4f5c-bb79-085fe5344211` Open the confirmation email link; `46acb8a7-b656-4601-8ccf-55f100e9b795` Confirm ownership of the email; `e3f5458a-18b1-43c6-a2c9-51a39e295f36` Continue to profile setup |
| 01a | `63f7b191-b825-4e51-954e-99a12f1fab40` | `c8f0fc8f-558a-4476-b36b-f9541218a1d3` Choose how to recover signup; `d77b102b-88b2-43ff-8d21-d755f5236ad2` Request another confirmation message; `122dff3a-0bb2-4829-88a2-60fc38efecdb` Check email or use another recovery option |
| 02 | `fb0b3a3b-0354-440c-a2b1-1cb73c662047` | `3378b8ad-55fb-42ec-906a-993b1c78dcbf` Return to Tover; `62dc0ef4-12a4-435d-9a1d-f94027ef0373` Establish a usable sign-in; `933985cf-4842-4729-81b7-8aa748e5e549` Continue to the next authorized screen |
| 02a | `515390b8-479e-4925-abb0-3870a007ec58` | `f994b116-2a87-49a6-8df4-e4c531ce892e` Request help signing in; `4966aacd-04f8-4b35-a195-c64845bc92c2` Request a password recovery message; `f477ee80-81db-4e8c-9bab-026174fce000` Check for the recovery email; `2a0cdc8c-931a-4005-a86e-e9e43f2f862f` Choose a new password from the recovery link; `885220ce-4708-46d9-8967-4050b510583f` Reset the password and end other sign-ins; `439abf54-de3e-4f2d-83c4-f453fabadf36` Resume the shared sign-in and access journey |
| 02b | `ada73c0d-20df-4014-9472-81803aa1e1bc` | `016d98f2-8490-4107-96ba-928bbfae74b0` Choose to end the current sign-in; `f01dcc7a-40c7-4ff5-88cf-d15498887f1b` End this sign-in; `32531a60-6f00-401a-b359-8202f5c9634c` Continue after sign-out |
| 02c | `d42e7aa3-7b7c-4150-9626-a7340bd92bce` | `1292d3df-9b50-406e-9733-6c43d5e3f60d` Read the interrupted reset and safe recovery choices; `bc6c2cd2-1cf6-4489-93b8-40e940d4e7df` Resume the retained completion obligation; `d77bc176-5899-4b13-adfc-4cd0303e9181` Complete the original reset without ending newer sign-ins; `1517ccdf-bef4-49f8-a555-f663afed1927` Read completion and continue through current access checks |
| 03 | `78b68815-3d9d-4a48-acaf-c07629523f31` | `d1023671-9b90-42be-9688-e9f70fcef703` Review basic user details; `6f92d5d3-908a-455f-a158-ac7964b4effa` Save the display name; `ca7b1206-831f-4784-922f-2b32f12ab109` Continue through shared access routing |
| 03a | `aec262e9-fa50-4bdd-bcd6-5ab1f3caf645` | `a0e2c4fb-0007-40d8-877d-cf459b6c21d1` Choose language on the current screen; `21044d2f-efbd-4fc4-b289-45eb5bb4fd0d` Save the account's language choice; `21fbcffb-784f-4628-b4d2-6b4ed838b90c` Continue in the chosen language |
| 04 | `10b18f7e-34b2-4cbc-82eb-20b3022c461d` | `a0f3f67a-dbfe-473c-aaf0-2e240626974f` Choose to create a workspace; `8a36f5fa-f472-470a-bdba-b601e12220e5` Create the workspace and its owner; `42220f84-4cba-4214-8c21-c51e80ed33ec` See the created workspace and current access |
| 05 | `658a7f98-2197-41b3-a0b1-97cb4cc29b2d` | `d036b471-d751-4b08-b804-5513cf8e2a8b` Review team and pending invitations; `78c4965f-85ea-49c5-993d-b613da1f3f0a` Issue a workspace invitation; `dcad0405-705b-42db-a652-23e651d9c6b0` Show the accepted invitation immediately; `dd5c62d4-0863-4c15-92cf-53224520f217` Arrange delivery independently; `efa905b5-39f4-4030-9201-43ac3ad201b9` Submit the invitation email; `9b764905-f4bc-4b83-be20-c0c8fd5ec636` Review the received invitation |
| 05a | `a21a1d42-2a08-46fb-a65c-33e831bf26be` | `59fe474d-a695-42db-a0a1-85a654d5a28b` Review an invitation before resending; `a51e6dba-cc3b-4ae0-9b27-e28058346dba` Request another invitation email; `cfd4b2b6-6430-4fea-8d76-927207805da7` See the reminder request and delivery status |
| 05b | `8acef5e2-15c6-4d32-98a9-a5a498da30ee` | `d48b11e3-8665-481b-954e-56966e1ec0de` Review the invitation to cancel; `5e71a360-3251-437b-8bbd-724c3c8c8551` Withdraw the pending offer; `b0aae4cc-05f3-41ff-b48e-4e4ec157d13f` See the withdrawn invitation |
| 06 | `f6592432-f795-4c86-a363-45142985f7e1` | `3c8d8a3b-e68a-4630-9528-a65ed29723fd` Review the exact workspace and role; `9bee6475-ac84-4dae-ae96-4f5a645f2e3e` Accept the reviewed invitation; `9688b286-4c6e-46a2-ad36-1860cbb789c6` See acceptance and current workspace access |
| 07 | `13b338e2-18d6-4124-8f98-a4334d5d9c99` | `130bc5ca-c049-4212-a486-86266cb2169e` Choose an authorized workspace; `91d65c70-8824-4efd-a247-a89e3d2afcc0` Select a workspace for this sign-in; `e5ab738c-21bb-4797-bb52-b2ebe51d4e87` Continue in the selected workspace |

`docs/bel-onboarding-mapping.md` has a per-chapter summary of agreed rules and fixed defaults (invitation lifetime 7 days, resend cooldown 60 s, delivery retries after 1, 5, 30 minutes, session expiry 30 days absolute). Where it disagrees with the board, the board wins (example: workspace name is 1–100 grapheme clusters, not 200 characters).

## Chunks and file ownership

Each agent works in its own git worktree on its own branch. Touch only the files you own. If you need a change in a file you do not own, describe it in your report.

| Chunk | Chapters | Owns | Migration |
| --- | --- | --- | --- |
| F foundation | shared core | `src/tover/identity/access.clj`, `src/tover/identity/session*`, `src/tover/identity/provider*`, `src/tover/identity/shell*`, `src/tover/app.clj`, `src/tover/shared/**`, `src/bel/**` (only if a framework gap blocks), `dev/**`, `test/tover/support.clj`, `tests.edn`, deployment scripts, deletions of legacy namespaces | `039_onboarding_foundation.sql` |
| A identity | 01, 01a, 02, 02a, 02b, 02c | `src/tover/identity/**` except F's files, `test/tover/identity/**` | `040_identity_flows.sql` if needed |
| B profile + workspaces | 03, 03a, 04, 07 | `src/tover/accounts/**`, `src/tover/workspaces/**`, `src/tover/shared/ui/core.clj` (header/nav), `test/tover/accounts/**`, `test/tover/workspaces/**` | `041_workspace_selection.sql` if needed |
| C invitations | 05, 05a, 05b, 06 | `src/tover/invitations/**`, `test/tover/invitations/**`, `test/invitation_delivery_integration.clj` | `042_invitation_lifecycle.sql` |
| D integration | all | scripts, `test/*_integration.clj`, `supabase/tests/**`, `test/browser/**`, docs | `043_*` only if needed |

Shared files everyone may append to: `resources/i18n/messages.json` (add keys in both `en` and `ru`, never rename or delete existing keys) and `resources/public/app.css` (only a new block for your context). Expect merge conflicts there; keep additions grouped.

## Current code map (main, before cutover)

Legacy, deleted by F:
- `tover.auth.core`: `Auth` protocol (sign-up, sign-in, token-identity, refresh, sign-out, verify-email, recover, set-password, invite, magic-link), schemas, `fail!`, `check-input!`, `claims-identity`.
- `tover.auth.supabase`: GoTrue HTTP adapter (`/signup`, `/token`, `/verify`, `/recover`, `PUT /user`, `/logout?scope=local`, `/invite`, `/otp`). `token-identity` decodes the JWT without verifying the signature. Reuse the HTTP calls, not the protocol.
- `tover.auth.sessions`: Ring `SessionStore` on `app_private.sessions` (row lock, AES-GCM token encryption, 30-day expiry), `establish!`, `select-workspace!`, `update-session!`. Reuse the store mechanics.
- `tover.auth.security`: `wrap-identity`/`identity-middleware` (route policy enum), `authenticate` (token refresh under row lock, identity from claims, first-membership fallback), `refresh-claims!`, `access-token`, `require-manager!`, `local-path`, `wrap-origin`, `datastar-request?`. Keep `wrap-origin`, `local-path`, `datastar-request?` (move to `tover.identity.access` or `tover.shared.web`).
- `tover.auth.web`: auth pages (`/auth/login`, `/auth/signup`, `/auth/recover`, `/auth/verify`, `/auth/confirm`, `/auth/password`), actions (sign-in, sign-up, send-recovery, verify-email, finish-sign-in, set-password, sign-out, set-language). Own `document`/`auth-shell` (`body.identity-document > main.identity`). Sign-up passes `organization_name` (creates a workspace through a trigger); `complete-sign-in` auto-accepts invitations. Both behaviors are removed.
- `tover.auth.bel`: `CurrentSession` Bel authority (locks session row FOR SHARE, checks user/`:bel/session-id`/expiry, sets `request.jwt.claim.sub` and `tover.session_key`, builds the principal with membership for a workspace scope). Basis for the new authority.
- `tover.workspaces.core` (`Directory` protocol), `tover.workspaces.supabase` (PostgREST RPC adapter), `tover.workspaces.web` (`/workspace` chooser + create + sign-out footer form), `tover.teams.core` (`/team` page; invite/resend/cancel/recovery calling GoTrue inline).

Bel code that stays:
- `tover.accounts.profile.{model,web}` live slice (03/03a) and `tover.accounts.persistence`.
- `tover.workspaces.create.{model,web}` (04) and `tover.workspaces.persistence` (organizations, organization_memberships, `::membership` lookup).
- `tover.invitations.issue.model`, `.delivery.{model,email}`, `.status.model`, `tover.invitations.persistence` (05; isolated today).
- `tover.shared.bel` system composition (`system`, `delivery-system`); `tover.shared.email` SMTP mailer. Delivery system is built without `:mailer`/`:origin` in `app.clj:268`.
- `bel.session` (cookie + `:bel/session-id`).

Isolation today: `app.clj:155` and `dev/user.clj:22,49` pass `:scan-exclude-ns ['tover.catalogues.rename 'tover.workspaces.create 'tover.invitations]` to `bel/init!`. F removes the two onboarding entries.

Coupling outside onboarding (must keep compiling):
- `app.clj`: `auth/Auth` and `workspaces/Directory` schemas, `security/identity-middleware`, `security/wrap-origin`, `security/authenticate` in realtime `:authorize`, `sessions/jdbc-store`, Integrant keys `::auth`, `::workspaces`, `::session-store`, readiness probe on `app_private.sessions`.
- `tover.shared.bel` requires `tover.auth.bel`; `tover.shared.supabase` uses `auth/fail!`, `auth/check-input!`; `tover.shared.web.errors` uses `security/datastar-request?`; `tover.shared.ui.core:29` posts to `/action/tover.auth.web/set-language`; `tover.shared.ui.forms:33` reads `[:tover/identity :auth-version]`; `tover.shared.web.core` reads `:tover/identity` (`auth-version` meta, realtime only with `:workspace-id` and `:profile-complete?`); `tover.shared.i18n` reads `[:tover/identity :user :locale]` and `:password-required?`.
- `tover.inventory.actors` uses `workspaces/actor-labels` + `security/access-token` (SQL `workspace_actor_labels` from 034); `stock/web.clj` uses it.
- `tover.catalogues.rename.model:34` reads `:principal :memberships`.
- catalogues/stock/operations/inventory web namespaces declare `:route-data {:tover/access :workspace}` and read `(:user-id context)`, `(:workspace-id context)`.
- `resources/public/identity.js`: adds CSRF and `x-tover-context` headers (must equal session `version`, else 409), reloads other tabs via BroadcastChannel, follows `x-tover-redirect` and 401.

Database (directory):
- Tables: `public.profiles` (+ `language`, `name_revision`, `language_revision` from 037), `public.roles`, `public.organizations`, `public.organization_memberships` (status active/invited/suspended, `recovery_requested_at`), `public.organization_invites` (pending/accepted/expired/revoked, 7-day expiry, no unique pending index), `public.invitation_deliveries` (038), `app_private.sessions` (id, data, tokens, user_id, workspace_id, version, expires_at), journal `app_private.bel_events`/`bel_receipts` (035/036).
- Roles: `tover_app` (runtime login, column grants + Bel row policies), `tover_journal` (NOLOGIN in 036; tests grant LOGIN).
- Bel helpers to keep: `app_private.bel_confirmed_user`, `bel_new_workspace`, `bel_workspace_membership`, `bel_recipient_membership`, `bel_recipient_language`.
- Legacy functions to drop (F, 039): `create_organization`, `accept_my_organization_invites`, `private.accept_workspace_invitations`, `manage_workspace_invitation` (both), `revoke_workspace_invitation_reservation` (both), `workspace_team` (both), `my_workspaces` (both), `request_member_recovery`, legacy `authenticated` RLS helpers no longer used, the organization branch of `bootstrap_new_user` (the trigger still creates profiles), `custom_access_token_hook`. Keep `workspace_actor_labels`.
- Migration chains: `deployment/local-identity/identity.sh:51` and `deployment/production/production.sh:42` list 034–037 explicitly (038 missing); `scripts/test-bel-workspace.sh:31-49` lists 033–038. F changes all three to apply every file from 034 on in order, so later chunks only add files.

Tests and fixtures:
- Unit suite: `clojure -M:dev:test` (Kaocha, instrumented). Shared fixtures: `test/tover/support.clj` (`with-application`, `authenticated-services`, `request`), `bel.core-test/isolated-registry`, `dev/tover/identity.clj` (fake `Auth` + `Directory`, `FixtureSession`, `bel-system`; users owner/member/other/unnamed `@example.test`, password `tover-development`), `dev/tover/session_store.clj`, `test/bel/runtime_test.clj` `MemoryStore`/`TestPrincipal`.
- Integration (Docker, wave 3 only): `scripts/test-bel-workspace.sh` (runs `test/workspace_creation_integration.clj`, which also runs goods and invitation delivery integration), `scripts/test-identity-integration.sh`, `scripts/test-auth-security.sh` (`supabase/tests/workspace-invitation-security.sql`).
- Browser: `test/browser` (Playwright); every spec signs in through `auth.fixture.mjs` posting to `/action/tover.auth.web/sign-in`.

## Foundation API (chunk F)

Identity lives in `src/tover/identity/`; the legacy `tover.auth`, `tover.teams`, `tover.workspaces.{core,web,supabase}` and `tover.shared.supabase` namespaces are gone.

- `tover.identity.access`: route policy. Declare `:route-data {:tover/access <policy>}` on every `defpage`/`defaction` (required; no default). Policies, checked in this order: signed in (all but `:optional`, `:logout`), context token `x-tover-context` equals the session `version` on writes and Datastar requests (not `:optional`, `:finish`), password set (not `:password`, `:finish`), profile complete (not `:password`, `:finish`, `:profile`), selected workspace (`:workspace`, `:manager`), owner/admin (`:manager`). GET page failures redirect to `/auth/login`, `/auth/password`, `/account/profile` or `/workspace`; others answer the status with `x-tover-error`. Handlers receive `:tover/identity` (`identity-of`), `:bel/context` (identity plus `:locale`, **nil when signed out**: signed-out commands need A's own design) and `:bel/realtime-context`. Also `current-identity`, `local-path`, `datastar-request?`, `failure`.
- `tover.identity.session`: Ring `SessionStore` (`jdbc-store`, cookie `tover-session`), `CurrentSession` Bel authority (principal `{:user-id :session-id :confirmed? :password-required? :memberships}`; scopes `:user`, `:session`, `:workspace`), helpers `session`, `update-session!`, `session-key`, `new-row`, `live?`, `valid?`, `ended!`. Read models `sessions` (policy `::current-session`) and `session-boundaries` (`::own-boundary`) declare no events. A slice that writes sessions declares its own `defmodel` with `:store :directory` over `(session/session-table [:fields ...])` and adds the binding to `tover.identity.persistence/tables` (A) or its own `tables` merged in `tover.shared.bel/directory-tables`.
- Session columns (039): secret cookie key `id`; Bel identity `session_id`; `created_at`; `ended_at` (ended marker, never revived: writes to an ended or expired key rotate to a new key); `version` is the selection revision. Reset boundary: `app_private.session_boundaries(user_id, valid_after, kept_session_id)`; a session is valid when live and created after `valid_after` or equal to `kept_session_id`.
- `tover.identity.provider`: GoTrue client (`client`, `health!`, `sign-up!`, `sign-in!`, `refresh!`, `verify!`, `resend!`, `recover!`, `set-password!`, `sign-out!`). Bel effects `::sign-up`, `::sign-in`, `::verify`, `::set-password` carry credentials and run only through `provider/call! context id input` (an fx descriptor fails permanently), so passwords and tokens never reach the journal. `::resend`, `::recover`, `::sign-out` (`{:session-id :scope}`) may be fx; failures map to `:temporary` (429, 5xx), `:uncertain` or `:permanent` with `:code`.
- `tover.identity.shell`: `auth-shell` page, `document`/`page-content`, `set-language` action (signed-in branch is B's).
- `tover.identity.placeholders`: `/auth/login`, `/auth/password`, `/workspace`; delete each when A/B ship the real page.
- Bel resources: `{:realtime :provider :session-store :origin}`; delivery system gets `:origin` and `:mailer` when `TOVER_SMTP_HOST` is set (`TOVER_SMTP_PORT` 587, `_USER`, `_PASSWORD`, `_FROM` required, `_SENDER_NAME`, `_STARTTLS`). `TOVER_REST_URL` is unused.
- Fixtures (`dev/tover/fixtures.clj`, ns `tover.fixtures`): seeded `owner@`, `member@`, `other@`, `unnamed@example.test` (password `fixtures/password`, ids `owner-id` … `unnamed-id`), workspaces `workspace-id`, `other-workspace-id`; `system` (fake GoTrue `:provider`, `:session-store`, `:bel/system`, `:state` with `:db` keyed by table and `:emails`), `sign-in! services email {:workspace-id :password-required? :data}`, `request`, `context`. Tests: `tover.support/with-application`, `support/sign-in! services browser email opts`, `session-key`, `with-server`.
- Migration chains apply every `supabase/migrations/0NN_*.sql` from 034 on in name order; add new files only.

## Patterns to copy

- Live slice end to end: `src/tover/accounts/profile/model.clj` + `web.clj`, tests `test/tover/accounts/profile_test.clj`, `profile_web_test.clj`.
- Declarative action to command with `:then`/`:catch` views: `src/tover/workspaces/create/web.clj`.
- Scenario tests without a store: `test/tover/invitations/issue_test.clj`, `test/tover/workspaces/create_test.clj` (register only the slice namespaces with `bel/build-registry!`, `use-fixtures :each fixture/isolated-registry register`, call `runtime/decide`, compare `(map #(select-keys % [:type :data]) events)`).
- Processor with durable attempts: `src/tover/invitations/delivery/model.clj`.
- One board example (`## Examples` in slice details) = one `deftest` named after it.

## Working rules

- REPL first (AGENTS.md). In your worktree start `clojure -M:dev:nrepl` in the background, find its port with `clj-nrepl-eval --discover-ports` (use the one listed for your worktree directory), eval with `clj-nrepl-eval -p <port> "..."`. Never `:reload-all`. Repair delimiters with `clj-paren-repair`.
- Use `jbcontext search` only for code you cannot locate from this brief.
- Before finishing: `clojure -M:dev:test` (or the focused namespaces plus a full run at the end), `clj-kondo --lint src dev .clj-kondo/hooks/datastar.clj`, `clojure -M:fmt check` on files you touched (fix with `clojure -M:fmt fix <files>`).
- No Docker integration scripts in waves 1–2 (they collide across worktrees).
- Commit on your worktree branch in small logical commits (conventional prefixes `feat(identity):`, `refactor(auth):` …), ending each message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push, never touch `main`.
- Ponytail rules apply: smallest code that satisfies the board; no speculative abstractions.

## Report format (your final message, ≤300 words)

1. Branch name and commits.
2. Files added/changed/deleted (grouped).
3. Tests run and results (exact counts).
4. Board slices and statuses set.
5. Gaps, task comments filed, deliberate deviations.
6. Edits needed in files you do not own.

# Onboarding: Event Modeling to Bel

The business chapters live in [prooph board](https://flow.prooph-board.com/), workspace `ec484260-1c12-45d7-a8a7-f0af8e2fc520`. This document maps them to the [DSL](bel-dsl-proposal.md). The board holds the rules, fields and examples; this file only says where each chapter lives in code.

Status 2026-11-16: chapters 01–07 run live on Bel. The legacy `tover.auth`, `tover.teams` and `tover.workspaces.{core,web,supabase}` namespaces, the access-token hook and the identity PostgREST RPCs are gone (migration 039). Request identity is read from Postgres on every request (`tover.identity.access`). Chapters 29 and 34–39 (teammate recovery, membership management) are not implemented.

## Common contracts

- Screens use `defview`; interactions use `defaction`; decisions use `defcommand`; facts use `defevent`; current entities use `defmodel`; screen information uses `defreadmodel`; automated reactions use `defprocessor`.
- Scopes are `:user`, `:session`, `:workspace`. Declarations and adapter IDs stay qualified. Continuations use `:then` and `:catch` for both commands and effects.
- Supabase Auth owns credentials and verification. Postgres owns profiles, directory, invitations and sessions. RDF owns catalogue and inventory. PostgreSQL is the shared business event/receipt journal. PostgreSQL domain commands commit everything together; RDF commands require durable journal delivery after source acceptance. The isolated Goods slice implements this delivery path; existing stores and live routes remain unchanged.
- Read current domain data. Keep receipt recovery distinct from current access. Missing data, forbidden access and failed reads are different outcomes.
- Normalize semantic input before receipt comparison. Preserve command identity through uncertainty. A retry never restores an old role, selection or field value.
- A valid session is required for user operations. Changing its selected workspace does not retarget or invalidate profile, workspace creation or explicit invitation acceptance.
- Decode UUIDs with Malli at transport boundaries; retain UUIDs and instants internally. Identity never comes from token claims; current authority guards acceptance.
- Late responses cannot replace newer session/context state. Relevant session and permission changes serialize with acceptance. RDF commands hold the current Postgres authorization guard through their own commit.
- Read-model composition does not introduce business decisions into adapters or renderers. Model mappings copy accepted event fields; no inference or replay is required for ordinary reads.
- Bel coordinates authority transactions, acceptance, receipt recovery and journal delivery. Tover owns current-access checks, native table bindings and graph allocation. Adapters implement storage mechanics without importing Tover code.

## Chapter mapping

One folder per slice with `model.clj` (declarations) and `web.clj` (screens and actions); scenario tests mirror the paths under `test/`.

- 01 · Sign up and confirm email (`ff146ef1-065e-45a9-a59e-130830f43616`): `tover.identity.signup.web`, `tover.identity.confirm.web`.
- 01a · Recover signup confirmation (`63f7b191-b825-4e51-954e-99a12f1fab40`): `tover.identity.resend.web`.
- 02 · Sign in and find workspace access (`fb0b3a3b-0354-440c-a2b1-1cb73c662047`): `tover.identity.sign-in.{model,web}`, routing in `tover.identity.access`.
- 02a · Recover password (`515390b8-479e-4925-abb0-3870a007ec58`): `tover.identity.recovery.{model,web}`.
- 02b · Sign out (`ada73c0d-20df-4014-9472-81803aa1e1bc`): `tover.identity.sign-out.{model,web}`.
- 02c · Complete an interrupted reset (`d42e7aa3-7b7c-4150-9626-a7340bd92bce`): `tover.identity.reset-completion.{model,web}`.
- 03 · Profile (`78b68815-3d9d-4a48-acaf-c07629523f31`) and 03a · Language (`aec262e9-fa50-4bdd-bcd6-5ab1f3caf645`): `tover.accounts.profile.{model,web}`; signed-out language in `tover.identity.shell/set-language`.
- 04 · Create workspace (`10b18f7e-34b2-4cbc-82eb-20b3022c461d`): `tover.workspaces.create.{model,web}`.
- 05 · Invite a teammate (`658a7f98-2197-41b3-a0b1-97cb4cc29b2d`): `tover.invitations.{issue,delivery,status,team}`.
- 05a · Resend an invitation (`a21a1d42-2a08-46fb-a65c-33e831bf26be`): `tover.invitations.resend.{model,web}`.
- 05b · Cancel an invitation (`8acef5e2-15c6-4d32-98a9-a5a498da30ee`): `tover.invitations.cancel.{model,web}`.
- 06 · Accept an invitation (`f6592432-f795-4c86-a363-45142985f7e1`): `tover.invitations.accept.{model,web}`.
- 07 · Choose a workspace (`13b338e2-18d6-4124-8f98-a4334d5d9c99`): `tover.workspaces.select.{model,web}`.

Shared pieces: `tover.identity.session` (session store, CurrentSession authority), `tover.identity.provider` (GoTrue effects), `tover.identity.shell` (signed-out document), persistence bindings in `tover.{identity,accounts,workspaces,invitations}.persistence`, offline fixtures in `dev/tover/fixtures.clj`.

## Delivery and identity boundaries

Invitation issuance retains delivery work atomically. A failed send preserves the offer. Queued, Submitted, Failed and Unconfirmed describe attempts, independently of invitation acceptance. SMTP acknowledgement is not inbox delivery. Workers recheck current offer eligibility, use durable claims and stable request IDs, and record outcomes/callback requests together. Duplicate emails are possible after uncertainty; duplicate offers and memberships are not.

Retain a membership removal marker or generation before implementing rejoin. Pending offers issued before removal cannot grant access afterwards. Suspension always requires a separate management flow.

Auth effects must not place passwords or tokens in event history, receipts, jobs, logs or views. Only verified, sanitized outcomes reach completion commands; browser input cannot manufacture them. Private credential writes remain within the session integration. End other Tover sessions using a stable reset boundary; do not repeatedly issue a broad revocation that could end newer sessions.

The saved language lives on the Postgres profile (037) and is seeded from the signup locale. Invitation email goes through Tover's SMTP mailer from the journal worker; GoTrue keeps sending confirmation and recovery email.

## Fixed defaults

Lifetimes, cooldowns, retry schedules, name limits and session expiry are recorded in the shared element details on the board. Code constants follow the board; where an older summary disagreed (for example a 200-character workspace name), the board won.

## Adoption order

Completed 2026-11-16: isolated Create Workspace, Goods journal delivery, profile and language, identity effects with sign-in/recovery/sign-out, invitations with durable delivery, and workspace selection, followed by the onboarding cutover. Signup workspace creation, automatic invitation acceptance, synchronous invitation delivery and first-membership fallback are retired.

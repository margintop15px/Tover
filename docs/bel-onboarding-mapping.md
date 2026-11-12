# Onboarding: Event Modeling to Bel

The business chapters live in [prooph board](https://flow.prooph-board.com/), workspace `ec484260-1c12-45d7-a8a7-f0af8e2fc520`. This document maps them to the [DSL](bel-dsl-proposal.md). Only chapter 04 has an isolated executable slice. Nothing here switches live onboarding routes.

## Common contracts

- Screens use `defview`; interactions use `defaction`; decisions use `defcommand`; facts use `defevent`; current entities use `defmodel`; screen information uses `defreadmodel`; automated reactions use `defprocessor`.
- Scopes are `:user`, `:session`, `:workspace`. Declarations and adapter IDs stay qualified. Continuations use `:then` and `:catch` for both commands and effects.
- Supabase Auth owns credentials and verification. Postgres owns profiles, directory, invitations and sessions. RDF owns catalogue and inventory. PostgreSQL is the shared business event/receipt journal. PostgreSQL domain commands commit everything together; RDF commands require durable journal delivery after source acceptance. The isolated Goods slice implements this delivery path; existing stores and live routes remain unchanged.
- Read current domain data. Keep receipt recovery distinct from current access. Missing data, forbidden access and failed reads are different outcomes.
- Normalize semantic input before receipt comparison. Preserve command identity through uncertainty. A retry never restores an old role, selection or field value.
- A valid session is required for user operations. Changing its selected workspace does not retarget or invalidate profile, workspace creation or explicit invitation acceptance.
- Decode UUIDs with Malli at transport boundaries; retain UUIDs and instants internally. Future JWT workspace hints may reduce routing reads, but current authority must still guard acceptance.
- Late responses cannot replace newer session/context state. Relevant session and permission changes serialize with acceptance. The isolated RDF slice holds the current Postgres authorization guard through its own commit; legacy live writers still need explicit cutover.
- Read-model composition does not introduce business decisions into adapters or renderers. Model mappings copy accepted event fields; no inference or replay is required for ordinary reads.
- Bel coordinates authority transactions, acceptance, receipt recovery and journal delivery. Tover owns current-access checks, native table bindings and graph allocation. Adapters implement storage mechanics without importing Tover code.

## Chapter mapping

| Chapter and board ID | Decisions and facts | Current state and screen data | Acceptance rules |
| --- | --- | --- | --- |
| 01 · `ff146ef1-065e-45a9-a59e-130830f43616` | Register Account and Confirm Email are transient Auth effects. Account Registered / Email Confirmed are provider-owned facts. | Public registration/link guidance; verified account and session status. | Neutral success is not proof of a new account. Confirmation GET is inert; require explicit POST. No workspace or membership is created. Fresh browser works; changing account requires explicit sign-out. |
| 01a · `63f7b191-b825-4e51-954e-99a12f1fab40` | Resend Confirmation is a transient effect. | Signup recovery choices and permitted retry time. | Neutral public response; follow provider eligibility and frequency limits. No password reset or preference replacement. |
| 02 · `fb0b3a3b-0354-440c-a2b1-1cb73c662047` | Sign In effect; verified completion records User Signed In. | Session and Next Authorized Step read models. | Existing sessions need no fresh event. Route through recovery requirement, profile, explicit invitation, requested authorized screen, then chooser. Failed reads offer Retry. Preserve invitation intent without accepting it. |
| 02a · `515390b8-479e-4925-abb0-3870a007ec58` | Request Recovery / Set Password are transient effects. Completion records Password Reset Completed. | Restricted recovery authority; confirmed outcome and current session. | Complete only after password acceptance and ending other Tover sessions. A stable completion identity prevents retry from ending newer sessions. Unknown provider outcomes remain unknown; do not automatically repeat password updates. |
| 02b · `ada73c0d-20df-4014-9472-81803aa1e1bc` | Session-scoped Sign Out → User Signed Out. | Targeted session outcome and public return choice. | End the exact session and private streams across its tabs. Other devices are unaffected. Provider cleanup cannot block local sign-out. Late requests cannot end replacement sessions. |
| 03 · `78b68815-3d9d-4a48-acaf-c07629523f31` | User-scoped Set Display Name → Display Name Changed. | Profile with independent name revision; next authorized step. | Trim/nonblank/plain text; duplicates allowed. Reject conflicting stale edits. Unchanged values emit nothing. Readiness derives from the saved name, not a flag. |
| 03a · `aec262e9-fa50-4bdd-bcd6-5ab1f3caf645` | User-scoped Change Language → Account Language Changed. | Profile language with its own revision; current form. | EN/RU. Name and language saves cannot overwrite one another. Preserve unsaved ordinary fields. Guest/recovery choices are browser-local. Future emails use the accepted account preference. |
| 04 · `10b18f7e-34b2-4cbc-82eb-20b3022c461d` | User-scoped Create Workspace → Workspace Created + Workspace Owner Assigned. | Workspace, membership, profile; Created Workspace and Current Access. | Confirmed ordinary session and saved profile. No membership-list prerequisite. Both facts and rows commit with one receipt. Duplicate names allowed; duplicate identities cannot overwrite. No selection or inventory/catalogue seeding. |
| 05 · `658a7f98-2197-41b3-a0b1-97cb4cc29b2d` | Workspace-scoped Invite Teammate → Workspace Invitation Issued. Processor requests Send Invitation Email; effects record delivery outcomes. | Invitations and delivery attempts; team and invitation status. | Current Owner/Admin may offer Member/Admin. One live offer per normalized workspace/recipient. Same-role repeat returns existing offer without new email; role changes require cancel/new offer. Active members are not reinvited. Return before email delivery. |
| 05a · `a21a1d42-2a08-46fb-a65c-33e831bf26be` | Workspace-scoped Resend Invitation → Invitation Resend Requested. | Exact offer, delivery eligibility, cooldown. | Preserve offer, link and original expiry. Deduplicate attempt; no overlapping queued/running reminder. Expired offers require a fresh invitation. |
| 05b · `8acef5e2-15c6-4d32-98a9-a5a498da30ee` | Workspace-scoped Cancel Invitation → Workspace Invitation Cancelled. | Exact invitation state and current permission. | Cancellation and acceptance have one winner. Never cancel a replacement offer or remove membership. Late mail cannot revive an offer. |
| 06 · `f6592432-f795-4c86-a363-45142985f7e1` | User-scoped Accept Invitation → Workspace Invitation Accepted + Workspace Member Joined. | Exact eligible invitation, original acceptance and current access. | Verified recipient, ordinary session and completed profile. Explicit acceptance; no automatic selection. Already-member result emits no fake join. Fresh post-removal invitations may rejoin; old offers cannot. Suspension needs separate management. Not Now only clears this visit's destination. |
| 07 · `13b338e2-18d6-4124-8f98-a4334d5d9c99` | Session-scoped Select Workspace → Workspace Selected. | Memberships, selection revision and current workspace access. | Explicit target and current membership. Same selection is a no-op; stale competing choices reject. Resolve unsaved input before switching. Old data/streams cannot reappear. No automatic fallback after lost access. |

## Delivery and identity boundaries

Invitation issuance retains delivery work atomically. A failed send preserves the offer. Queued, Submitted, Failed and Unconfirmed describe attempts, independently of invitation acceptance. SMTP acknowledgement is not inbox delivery. Workers recheck current offer eligibility, use durable claims and stable request IDs, and record outcomes/callback requests together. Duplicate emails are possible after uncertainty; duplicate offers and memberships are not.

Retain a membership removal marker or generation before implementing rejoin. Pending offers issued before removal cannot grant access afterwards. Suspension always requires a separate management flow.

Auth effects must not place passwords or tokens in event history, receipts, jobs, logs or views. Only verified, sanitized outcomes reach completion commands; browser input cannot manufacture them. Private credential writes remain within the session integration. End other Tover sessions using a stable reset boundary; do not repeatedly issue a broad revocation that could end newer sessions.

Move saved language into the Postgres profile during the profile milestone. Initialize existing locales from Auth without overwriting accepted preferences. Auth email hooks read the profile; initial signup uses the new account's selected language. Auth keeps credential and verification ownership.

Status 2026-11-12: chapters 03 and 03a run on Bel (`tover.accounts.profile`); migration 037 stores `language`, `name_revision` and `language_revision` on the profile and seeds language from the signup locale. Request identity comes from access-token claims (`language`, `profile_complete`, `email_confirmed`, `memberships`) added by the `custom_access_token_hook`; only writes and the profile page read the profile. A claim change reaches other sessions at their next token refresh (at most `JWT_EXP`); the acting user's sessions are refreshed after their own profile change and after invitation acceptance or workspace creation.

Status 2026-11-12 (P6): chapter 05 exists as an isolated Bel slice (`tover.invitations`, excluded from discovery). Invite Teammate issues an offer with a queued delivery request in one acceptance; the `invitation-delivery` processor sends the email through Tover's SMTP mailer (same relay as Auth), recording each attempt before sending and its outcome afterwards; temporary failures retry after 1, 5 and 30 minutes within four attempts; uncertain outcomes stay Unconfirmed. The live Team screen and legacy invitation functions are unchanged until the 05/05a/05b/06 cutover, which also adds migration 038 to the `identity.sh`/`production.sh` chains (until then only the integration script applies it), SMTP settings to deployment, and a unique pending-offer index after legacy resend retires.

## Fixed defaults

| Rule | Decision |
| --- | --- |
| Display name | Nonblank trimmed Unicode text, maximum 100 characters; duplicates allowed. |
| Workspace name | Nonblank trimmed Unicode text, maximum 200 characters; duplicates allowed. |
| Language | EN or RU; preselected and editable. |
| Invitation lifetime | Seven days. |
| Resend | Same offer/link/expiry; 60-second cooldown. |
| Automatic delivery retries | Three retries after 1, 5 and 30 minutes. |
| Definite email failure | Keep invitation valid and allow delivery retry. |
| Session expiry | 30 days absolute; no additional idle timeout. |
| Password reset | End other Tover sessions; later sessions survive completion retries. |
| In-flight changes | Reject unfinished acceptance if its relevant context/access changed first. Accepted work remains accepted. |

## Adoption order

1. Isolated Create Workspace: native models, two events, one acceptance, read model and view. No effects or processor.
2. Isolated Goods delivery to the shared PostgreSQL journal, including cross-store crash recovery (implemented).
3. Profile/name/language and shared authorized routing.
4. Auth effects and sign-in/recovery/sign-out.
5. Invitation issue/resend/cancel/accept with durable delivery.
6. Workspace selection and complete onboarding cutover.

Before live cutover, retire signup workspace creation, automatic invitation acceptance, synchronous invitation delivery and unguarded selection. Close legacy write paths that bypass receipts and acceptance guards. Preserve current IDs and rows; establish a baseline without inventing historical events. Open/Invite controls on creation and acceptance outcomes must target that exact workspace through chapter 07, never through a prior selection.

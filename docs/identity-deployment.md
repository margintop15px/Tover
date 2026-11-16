# Identity deployments

The application selects the GoTrue provider (`tover.identity.provider`) and the session store (`tover.identity.session`) through Integrant. Hosted Supabase and self-hosted Supabase use the same HTTP adapter. GoTrue only holds credentials and sends confirmation and recovery email; every request reads its identity from Postgres (the locked session row, the profile, active memberships and the reset boundary), and Bel commands write the directory under row policies for the `tover_app` role. Token claims, the access-token hook and PostgREST RPCs are not used. Browser cookies contain an opaque session identifier; encrypted tokens remain in the private session database.

Both self-hosted Compose profiles give Auth a fixed database pool: at most 8 open connections and up to 4 idle connections retained for reuse. This is separate from the Clojure JDBC pool. Auth must be recreated after changing these environment settings; existing sessions remain in Postgres.

## Local integration profile

Requirements: Docker Compose v2, Python 3 and the repository’s Clojure CLI/JDK setup. The profile publishes only loopback ports and creates its own `tover-identity` project and `identity-data` volume.

Initialize the profile once; skip `init` when its private `.env` already exists:

```sh
deployment/local-identity/identity.sh init
deployment/local-identity/identity.sh up
scripts/test-identity-integration.sh
```

`init` generates private random credentials in an ignored `.env` with mode 0600 and refuses to overwrite it. `up` starts Postgres, configures local database role passwords, starts Auth/PostgREST/Mailpit/gateway, applies migration 033 only when `app_private.sessions` does not exist yet (033 checks the original profile shape), then every migration from 034 on in name order (currently 034–042), configures the restricted `tover_app` session role and gives the private `tover_journal` worker role a local login. A profile created before the journal worker gets `TO_DEV_JOURNAL_PASSWORD` appended to its `.env` on the next `up`; other keys stay unchanged. Running `up` again preserves accounts and data. `stop` stops this profile without removing its volume.

| Service | Local address |
| --- | --- |
| Auth HTTP | `http://127.0.0.1:58000/auth/v1` |
| PostgREST (not used by the application) | `http://127.0.0.1:58000/rest/v1` |
| Session database | `jdbc:postgresql://127.0.0.1:55432/postgres` |
| Mail inbox | `http://127.0.0.1:58025` |
| Mail SMTP (Tover invitation email) | `127.0.0.1:51025`, override with `IDENTITY_SMTP_PORT` |

With the profile running, start the application normally:

```sh
clojure -M:dev
```

Default development (`TOVER_IDENTITY_MODE=local`) reads `deployment/local-identity/.env` directly. No shell exports are required, including after an IDE REPL restart followed by `(user/start!)`. Application startup does not start Docker or apply migrations. Missing local configuration fails explicitly; it does not select fixtures. Runtime demo/seed mode is removed; isolated tests retain their own fixture services.

Optional: export the local configuration for explicit environment mode:

```sh
deployment/local-identity/identity.sh app-env
. deployment/local-identity/.env.app.local
TOVER_IDENTITY_MODE=supabase clojure -M:dev
```

`TOVER_IDENTITY_MODE=supabase` uses explicit `TOVER_*` environment settings for a hosted or custom development target. Both `local` and `supabase` use the same Supabase HTTP adapters. Development permits this loopback HTTP origin. Production `clojure -M:run` requires HTTPS and never loads development fixtures. The optional private application environment sets `TOVER_AUTH_URL`, `TOVER_API_KEY`, `TOVER_AUTH_ADMIN_KEY`, `TOVER_ORIGIN`, `TOVER_JDBC_URL`, `TOVER_DB_USER`, `TOVER_DB_PASSWORD`, `TOVER_SESSION_KEY`, `TOVER_JOURNAL_DB_USER` and `TOVER_JOURNAL_DB_PASSWORD`. The journal pair is optional: with both set, the application runs the journal delivery worker as that private role; with only one set, startup fails. `TOVER_REST_URL` is no longer read (the production compose file may still set it). `app-env` and `clojure -M:dev` point `TOVER_SMTP_*` at the profile's Mailpit. Invitation email is sent by the journal worker over SMTP when `TOVER_SMTP_HOST` is set: `TOVER_SMTP_FROM` is then required, `TOVER_SMTP_PORT` defaults to 587, `TOVER_SMTP_USER`, `TOVER_SMTP_PASSWORD` and `TOVER_SMTP_SENDER_NAME` are optional and `TOVER_SMTP_STARTTLS=false` disables STARTTLS. Without `TOVER_SMTP_HOST` the worker runs without a mailer and invitation sends are recorded as failed attempts. Keep these values out of browser assets and Git.

The session database user is `tover_app`; its password is `TO_DEV_APP_PASSWORD` in the private profile file. `ANON_KEY`, `SERVICE_ROLE_KEY` and `SESSION_ENCRYPTION_KEY` also live there. Keep the service key and database/encryption credentials on the server. Restart the application after changing credentials or deployment target.

The application owns one JDBC pool for private sessions and readiness: 2 idle connections, at most 8, a 2-second acquisition timeout and 15-second PostgreSQL connect/socket timeouts. Shutdown drains HTTP requests before closing it. Session transactions retain row locks for concurrent refresh safety and only persist changed rows. Buddy AES-GCM preserves the existing 32-byte key and `Base64(nonce).Base64(ciphertext-and-tag)` format; this change needs no SQL migration or session reset.

Auth uses captured local email, not an external mail provider. Templates direct recipients to `/auth/confirm` with a token hash. GET displays confirmation; only the explicit POST consumes the token. Set `TO_DEV_SITE_URL` in `deployment/local-identity/.env` when the application origin differs from `http://localhost:3030`. Port overrides in that file are `IDENTITY_API_PORT`, `IDENTITY_DB_PORT` and `IDENTITY_MAIL_PORT`. When testing nondefault ports, supply the matching `IDENTITY_TEST_AUTH_URL`, `IDENTITY_TEST_MAIL_URL` and `IDENTITY_TEST_JDBC_URL`.

The pinned Auth/PostgREST/Postgres versions follow the [official Supabase Docker composition](https://github.com/supabase/supabase/blob/master/docker/docker-compose.yml), checked on 2026-10-21. Nginx supplies `/auth/v1` and `/rest/v1`; it does not implement hosted Supabase API-key management. This local HTTP profile is an integration target. The separate [production profile](production-deployment.md) supplies automatic HTTPS, external SMTP configuration, private identity ingress, persistent stores and explicit installation/upgrade/backup/restore procedures. It preserves this local profile and its data.

## SQL contract

`033_identity_and_sessions.sql` accepts either an initialized Supabase Auth database with no Tover identity tables or the complete identity contract through migration 032. It rejects partial/incompatible identity schemas. Fresh installations begin with 033; existing installations apply it after their retained migration history. Do not replay legacy inventory migrations into a fresh identity deployment.

Migration 033 preserves account UUIDs, credentials, profiles, workspaces, memberships, invitations and legacy inventory. It removes the signup trigger's warehouse insert. As forward permission hardening, it removes `PUBLIC` execution of public `SECURITY DEFINER` functions while preserving explicit authenticated/service grants, so the session role cannot inherit legacy privileged RPC access. It restricted membership/invitation writes to authorized RPCs and added `my_workspaces()` plus reservation-specific invitation revocation; 039 drops those RPCs again. The private `app_private.sessions` table and its non-inheriting, non-superuser, non-BYPASSRLS login role have no Data API exposure. Assign the session role's password separately through the deployment's secret-management process; the migration contains no passwords.

Migration 037 adds the profile `language`, `name_revision` and `language_revision` columns and a display-name CHECK (nonblank, at most 100 characters; existing longer names stay until edited). It also created `public.custom_access_token_hook`, which 039 drops: Auth must not enable `GOTRUE_HOOK_CUSTOM_ACCESS_TOKEN_*` (neither compose file sets it). Because identity is read from Postgres on every request, a removed membership or suspended account loses access on its next request.

The onboarding cutover adds:

- 038: invitation deliveries and manager row policies on invitations.
- 039: drops the legacy identity RPCs and the access-token hook; signup metadata only names the profile and never creates or joins a workspace; `app_private.sessions` gains `session_id`, `created_at` and `ended_at`; `app_private.session_boundaries` holds password-reset boundaries; session-scoped journal entries; `app_private.bel_actor_labels`.
- 040: `app_private.password_resets` for interrupted reset completion.
- 041: invitation lifecycle helpers (`bel_received_invitation`, `bel_recipient_access`, `bel_workspace_team`, `bel_invitation_grant`) and recipient acceptance policies.
- 042: drops the unused `bel_recipient_membership` and makes the membership lookup behind every manager policy ignore ended sign-ins.

All are additive and idempotent and preserve accounts, profiles, workspaces, memberships, invitations and sessions. Every chain (`identity.sh up`, `production.sh`, the Docker test scripts) applies every file from 034 on in name order, so a new migration only adds a file.

Migration 034 adds `workspace_actor_labels(workspace_id, actor_ids)` for history attribution. Any active member can resolve up to 200 requested workspace actor IDs per call to trimmed display names or email addresses, including retained inactive membership records. Unknown or deleted users return no label. The caller's access token authorizes each lookup; the team-administration RPC and profile policies stay unchanged. Apply 034 after 033 before running this application version. No inventory records or identity data are rewritten.

Existing user/password migration between deployments is separate work. Do not copy or reinterpret Auth internals without a rehearsed version-compatible migration. Every deployment selects one identity target at startup; switching endpoints does not transfer users or sessions.

## Verification

```sh
scripts/test-auth-security.sh
scripts/test-identity-integration.sh
```

The first script creates a disposable PostgreSQL cluster with networking disabled. It tests fresh installation and an identity upgrade with retained synthetic account/inventory data through 042 (each applied twice), catalog/permission parity, rejected partial schemas, the removal of the legacy RPCs, signup metadata safeguards, the Bel row policies for outsiders, members, managers and recipients (verified email, offered role, revocation, live sign-in required), session-scoped journal entries, reset boundaries and password resets, actor-name fallbacks, and a cancellation overlapping an acceptance. It never reads project `.env` files or connects to an existing database.

The integration suite uses the local profile by default. It creates uniquely named synthetic users and exercises real Auth login/user verification/refresh/logout. With the local mail catcher, it also proves confirmation/recovery delivery, fresh-client token-hash verification, replay rejection and password setup. Hosted email checks require an explicit `IDENTITY_TEST_MAIL_URL`; otherwise that part is reported as skipped. It retains synthetic accounts/workspaces and removes only temporary session rows created by its own checks. The final Clojure runner exercises `tover.identity.provider`, the encrypted JDBC session store and the Bel directory behind the real handler: an invitation link kept through sign-in, Create and Select Workspace, navigation without session rewrites, concurrent token refresh under the row lock and Sign Out. Fixture tests do not prove real provider compatibility.

A designated disposable hosted project can run the same integration suite:

```sh
IDENTITY_TEST_TARGET=disposable \
IDENTITY_TEST_AUTH_URL=https://PROJECT.supabase.co/auth/v1 \
IDENTITY_TEST_PUBLIC_KEY=... \
IDENTITY_TEST_SERVICE_KEY=... \
scripts/test-identity-integration.sh
```

Supply secrets through the environment, not shell history. Apply 033 through 042 to that test target first; do not enable an access-token hook. The suite refuses a remote target without `IDENTITY_TEST_TARGET=disposable`. To include hosted JDBC checks, explicitly set `IDENTITY_TEST_JDBC_URL`, `IDENTITY_TEST_DB_USER`, `IDENTITY_TEST_DB_PASSWORD` and `IDENTITY_TEST_SESSION_KEY`; otherwise the suite reports that part as skipped. No production or hosted target is configured by this repository.

Verified on 2026-11-16 (onboarding cutover): `scripts/test-auth-security.sh` passed fresh, upgrade, parity and partial-schema checks; `scripts/test-identity-integration.sh` passed the captured-email and Auth checks and the Clojure runner (3 tests, 45 assertions) against the local profile. No hosted project or production database was contacted.

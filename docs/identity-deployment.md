# Identity deployments

The application selects Auth, workspace and session services through Integrant. Hosted Supabase and self-hosted Supabase use the same HTTP adapters. Workspace authorization stays in Postgres RPCs and the application checks current membership on each protected request. Browser cookies contain an opaque session identifier; encrypted tokens remain in the private session database.

## Local integration profile

Requirements: Docker Compose v2, Python 3 and the repository’s Clojure CLI/JDK setup. The profile publishes only loopback ports and creates its own `tover-identity` project and `identity-data` volume.

Initialize the profile once; skip `init` when its private `.env` already exists:

```sh
deployment/local-identity/identity.sh init
deployment/local-identity/identity.sh up
scripts/test-identity-integration.sh
```

`init` generates private random credentials in an ignored `.env` with mode 0600 and refuses to overwrite it. `up` starts Postgres, configures local database role passwords, starts Auth/PostgREST/Mailpit/gateway, applies migration 033 and configures the restricted `tover_app` session role. Running `up` again preserves accounts and data. `stop` stops this profile without removing its volume.

| Service | Local address |
| --- | --- |
| Auth HTTP | `http://127.0.0.1:58000/auth/v1` |
| PostgREST | `http://127.0.0.1:58000/rest/v1` |
| Session database | `jdbc:postgresql://127.0.0.1:55432/postgres` |
| Mail inbox | `http://127.0.0.1:58025` |

With the profile running, start the application normally:

```sh
clojure -M:dev
```

Default development (`TOVER_IDENTITY_MODE=local`) reads `deployment/local-identity/.env` directly. No shell exports are required, including after an IDE REPL restart followed by `(user/start!)`. Application startup does not start Docker or apply migrations. Missing local configuration fails explicitly; it does not select fixtures. For offline seeded development, use `TOVER_IDENTITY_MODE=fixtures clojure -M:dev`.

Optional: export the local configuration for explicit environment mode:

```sh
deployment/local-identity/identity.sh app-env
. deployment/local-identity/.env.app.local
TOVER_IDENTITY_MODE=supabase clojure -M:dev
```

`TOVER_IDENTITY_MODE=supabase` uses explicit `TOVER_*` environment settings for a hosted or custom development target. Both `local` and `supabase` use the same Supabase HTTP adapters and skip fixture seeds. Development permits this loopback HTTP origin. Production `clojure -M:run` requires HTTPS and never loads development fixtures. The optional private application environment sets `TOVER_AUTH_URL`, `TOVER_REST_URL`, `TOVER_API_KEY`, `TOVER_AUTH_ADMIN_KEY`, `TOVER_ORIGIN`, `TOVER_JDBC_URL`, `TOVER_DB_USER`, `TOVER_DB_PASSWORD` and `TOVER_SESSION_KEY`. Keep these values out of browser assets and Git.

The session database user is `tover_app`; its password is `TO_DEV_APP_PASSWORD` in the private profile file. `ANON_KEY`, `SERVICE_ROLE_KEY` and `SESSION_ENCRYPTION_KEY` also live there. Keep the service key and database/encryption credentials on the server. Restart the application after changing credentials or deployment target.

Auth uses captured local email, not an external mail provider. Templates direct recipients to `/auth/confirm` with a token hash. GET displays confirmation; only the explicit POST consumes the token. Set `TO_DEV_SITE_URL` in `deployment/local-identity/.env` when the application origin differs from `http://localhost:3030`. Port overrides in that file are `IDENTITY_API_PORT`, `IDENTITY_DB_PORT` and `IDENTITY_MAIL_PORT`. When testing nondefault ports, supply the matching `IDENTITY_TEST_AUTH_URL`, `IDENTITY_TEST_REST_URL`, `IDENTITY_TEST_MAIL_URL` and `IDENTITY_TEST_JDBC_URL`.

The pinned Auth/PostgREST/Postgres versions follow the [official Supabase Docker composition](https://github.com/supabase/supabase/blob/master/docker/docker-compose.yml), checked on 2026-10-19. Nginx supplies `/auth/v1` and `/rest/v1`; it does not implement hosted Supabase API-key management. This local HTTP profile is an integration target. Production needs TLS, a real SMTP provider, restricted ingress, backups and an explicit upgrade process. Those deployment/cutover tasks remain milestone 8.

## SQL contract

`033_identity_and_sessions.sql` accepts either an initialized Supabase Auth database with no Tover identity tables or the complete identity contract through migration 032. It rejects partial/incompatible identity schemas. Fresh installations begin with 033; existing installations apply it after their retained migration history. Do not replay legacy inventory migrations into a fresh identity deployment.

Migration 033 preserves account UUIDs, credentials, profiles, workspaces, memberships, invitations and legacy inventory. It removes the signup trigger's warehouse insert. As forward permission hardening, it removes `PUBLIC` execution of public `SECURITY DEFINER` functions while preserving explicit authenticated/service grants, so the session role cannot inherit legacy privileged RPC access. It restricts membership/invitation writes to authorized RPCs and adds `my_workspaces()` plus reservation-specific invitation revocation. The private `app_private.sessions` table and its non-inheriting, non-superuser, non-BYPASSRLS login role have no Data API exposure. Assign the session role's password separately through the deployment's secret-management process; the migration contains no passwords.

Existing user/password migration between deployments is separate work. Do not copy or reinterpret Auth internals without a rehearsed version-compatible migration. Every deployment selects one identity target at startup; switching endpoints does not transfer users or sessions.

## Verification

```sh
scripts/test-auth-security.sh
scripts/test-identity-integration.sh
```

The first script creates a disposable PostgreSQL cluster with networking disabled. It tests fresh installation, an identity upgrade with retained synthetic account/inventory data, repeated application, catalog/permission parity, rejected partial schemas, manager/member restrictions, signup metadata safeguards, current invitation semantics, overlapping create/resend reservations and cancellation/acceptance. It never reads project `.env` files or connects to an existing database.

The integration suite uses the local profile by default. It creates uniquely named synthetic users/workspaces and exercises real Auth login/user verification/refresh/logout, user-authenticated PostgREST RPCs, invitation acceptance, role limits and cross-workspace isolation. With the local mail catcher, it also proves confirmation/recovery delivery, fresh-client token-hash verification, replay rejection and password setup. Hosted email checks require an explicit `IDENTITY_TEST_MAIL_URL`; otherwise that part is reported as skipped. It retains synthetic accounts/workspaces and removes only temporary session rows created by its own checks. The final Clojure runner exercises the actual Auth/workspace adapters and encrypted JDBC SessionStore, including concurrent refresh, preservation of newer credentials during ordinary writes, and real Ring login/workspace creation/logout. Fixture tests do not prove real provider compatibility.

A designated disposable hosted project can run the same integration suite:

```sh
IDENTITY_TEST_TARGET=disposable \
IDENTITY_TEST_AUTH_URL=https://PROJECT.supabase.co/auth/v1 \
IDENTITY_TEST_REST_URL=https://PROJECT.supabase.co/rest/v1 \
IDENTITY_TEST_PUBLIC_KEY=... \
IDENTITY_TEST_SERVICE_KEY=... \
scripts/test-identity-integration.sh
```

Supply secrets through the environment, not shell history. Apply 033 to that test target first. The suite refuses a remote target without `IDENTITY_TEST_TARGET=disposable`. To include hosted JDBC checks, explicitly set `IDENTITY_TEST_JDBC_URL`, `IDENTITY_TEST_DB_USER`, `IDENTITY_TEST_DB_PASSWORD` and `IDENTITY_TEST_SESSION_KEY`; otherwise the suite reports that part as skipped. No production or hosted target is configured by this repository.

Verified on 2026-10-19: the disposable PostgreSQL 17.6 fixture suite passed fresh/upgrade/rerun, permission parity, preservation and concurrency checks. The pinned local Auth/PostgREST profile passed the real HTTP and captured-email suite and the actual Clojure adapter/JDBC checks (2 tests, 41 assertions). No hosted project was configured or tested; no production database was contacted.

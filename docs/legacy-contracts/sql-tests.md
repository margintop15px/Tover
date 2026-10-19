# Standalone SQL test retention

Recommendation for the root cleanup; no scripts, SQL tests or migrations were changed here.

| Keep in current tree | Purpose and dependency |
| --- | --- |
| `scripts/test-auth-security.sh` | Runs a disposable `postgres:16-alpine` container with networking disabled, no published port and no `.env` loading. Invokes the invitation SQL fixture. No Node dependency. |
| `supabase/tests/workspace-invitation-security.sql` | Tests forged signup metadata, verified-email invitation acceptance, role source, idempotence, revoked/expired/wrong-email denial, manager access, cooldown/concurrent reservation and suspended membership behavior. Includes migrations 001, 002, 003, 029 and 032. |
| `scripts/test-workspace-reset.sh` | Same isolated container approach for the historical reset regression. No Node dependency. |
| `supabase/tests/workspace-reset-performance.sql` | Historical evidence for explicit confirmation, owner authorization, workspace isolation, retained identity/settings/credentials and FK-index performance. Loads migrations 001–030 and then 031; uses synthetic rows. |
| `supabase/migrations/001_…sql` through `032_…sql` | Retain the full immutable migration chain. Both standalone harnesses resolve migration files relative to their SQL test; keep the existing tree shape. |

The auth harness currently asserts that legitimate signup creates a defect warehouse (`workspace-invitation-security.sql:41-46`). That is a **legacy dependency**, not a required Auth behavior. A future forward migration removing bootstrap's warehouse insert must update the harness to apply that migration and assert only the new identity/workspace contract. The old fixture passing does not prove the future Auth implementation.

The reset fixture is a reproducible old-schema check, not a new inventory requirement or a tool to reset production. No measurement or test run was performed during this extraction.

Remove from the active runtime with the old JS app; retrieve from baseline if needed:

- `scripts/test-auth-recovery.sh`: despite being a shell script, it generates JWTs using Node, starts old Auth/PostgREST/Mailpit services and invokes removed Playwright/Next configuration. Its useful fresh-browser email and race scenarios are summarized in [identity-and-email.md](identity-and-email.md).
- `supabase/tests/inventory-adjustment-type.sql`: isolated old `operations.type` repair test for migration 030; no retained identity contract. It remains available at the baseline commit.
- Old TS mock servers and TS unit/browser suites: preserve only the contract and synthetic JSON cases in this tree. They are not runnable tests for the replacement app.

Source inspection: `baseline:scripts/test-auth-security.sh:4-22`; `baseline:scripts/test-workspace-reset.sh:4-18`; `baseline:supabase/tests/workspace-invitation-security.sql`; `baseline:supabase/tests/workspace-reset-performance.sql:22-53,113-169`; `baseline:scripts/test-auth-recovery.sh:7-13,61-77`. See [source notation](README.md#source-references).

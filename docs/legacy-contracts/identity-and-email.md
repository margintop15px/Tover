# Identity, workspace and email contracts

Source notation and hashes: [source manifest](sources.json). These behaviors were extracted from the legacy app; later implementation belongs to Clojure.

## Identity and authorization

- Retain Supabase account UUIDs, passwords, profiles, organizations, active/invited/suspended memberships, roles and invitations. `workspace_id` means `organizations.id`.
- Authenticate through Supabase, then load active membership for the requested workspace. Roles are `owner`, `admin`, `member`; manager actions require owner/admin. Do not derive access from client metadata, names or a cached browser role.
- Accept invitations using Auth's verified email. Ignore signup metadata claiming an existing organization ID or role. Signup may create a new organization owned by its creator.
- Preserve the SQL access checks even when an application handler also checks permission. Team/member addresses come from stored Auth records; managers cannot supply a replacement recipient address.

Evidence: `baseline:supabase/migrations/002_auth_orgs_rbac.sql:3-62,98-131,313-443`; `baseline:supabase/migrations/029_secure_workspace_invitations.sql:1-108`; `baseline:supabase/migrations/032_team_invitation_recovery.sql:9-210`; `baseline:src/lib/request-context.ts:28-73`.

## Workspace selection and stale requests

- Order memberships by creation time, then organization UUID. Use a saved selection only while it remains accessible; otherwise choose the first active membership. No active membership means no operational access.
- Validate every explicit workspace override. A rendered page carries its workspace snapshot on requests. If another tab changes the browser selection, reject the old page with `409 WORKSPACE_CHANGED` before running its action. Never retarget that action to the newly selected workspace.
- Switching workspaces refreshes all tabs and clears old deep links, filters and drafts. The old `/operations` landing route is an implementation detail to replace.
- A failed read is not an empty successful result. Preserve prior data and show a retry state. A failed write with an unknown commit result preserves input and requires an explicit user retry; do not automatically repeat it.

Evidence: `baseline:src/lib/workspace.ts:23-55`; `baseline:src/app/api/auth/me/route.ts:19-93`; `baseline:src/app/api/auth/workspace/route.ts:5-23`; `working:src/contexts/WorkspaceContext.tsx`; `baseline:tests/e2e/workspace-switch.spec.ts:84-210`.

## Team invitations and recovery

- New invitation roles are admin/member. Normalize email with trim and lowercase. Create/resend/cancel and member recovery require manager access to the selected workspace.
- Reserve a send in a short database transaction. Serialize requests for the workspace/email; concurrent resends reserve only one email. Apply a 60-second cooldown. Do HTTP delivery after the transaction.
- If Auth reports `email_exists`, send a fresh magic link with account creation disabled. Keep the workspace invitation pending until the verified recipient signs in. Do not delete an account or reset its password to invite it.
- Other definitive email failures revoke only the newly reserved pending invitation. A network timeout or lost response means `DELIVERY_UNCONFIRMED`; it does not prove email was not sent and must not revoke pending access.
- Recheck invitation status after delivery. A canceled/expired invitation must not become active again. Cancellation and acceptance share locks. Acceptance must not reactivate a suspended membership or change an already-active member's role.
- For member recovery, resolve the stored member email in SQL, enforce active status and cooldown, then send recovery mail. The manager's current session and credentials remain intact.
- Self-service recovery uses a separate non-persisting mail client. Unknown email gets the same success outcome. Show cooldown and delivery uncertainty; do not clear the current browser session to send mail.

Evidence: `baseline:src/lib/team-actions.ts:9-94`; `baseline:src/app/api/auth/members/[userId]/recovery/route.ts:6-28`; `baseline:src/lib/supabase-email.ts:3-10`; `baseline:src/app/forgot-password/page.tsx:21-50`; `baseline:supabase/migrations/032_team_invitation_recovery.sql:43-175`.

## Recipient links and password setup

- New-user invites and recovery links must work in a fresh recipient browser. Invite/recovery sessions lead to password setup; verify the account before enabling password change.
- The old callback accepts a complete access/refresh token fragment or a PKCE code, removes credentials from browser history before exchange, exchanges once, and saves the session before navigation. Missing fragments, expired links and provider errors are explicit failures.
- Old signup PKCE links require the initiating browser's verifier. A different-browser failure explains that the person can log in with their chosen password. This browser restriction is historical behavior, not a requirement for a new server-side token-hash flow.
- Allow only local safe next paths. Reject protocol-relative paths, backslashes, control characters and whitespace. Old server email links use the configured site URL, falling back to the request origin; configure the production origin explicitly when porting.
- A password-update network failure has an unknown result. Preserve the input and explain that the update could not be confirmed.

Evidence: `baseline:src/app/auth/callback/page.tsx:11-74`; `baseline:src/lib/auth-redirect.ts:1-7`; `baseline:src/lib/supabase-server.ts:50-60`; `baseline:src/app/reset-password/page.tsx:19-74`; [copied configuration](runbooks/auth-configuration-legacy.md).

## Uncommitted fixes captured before deletion

These behaviors came from working files, not the baseline commit:

- Password login, initial browser Auth verification and `/api/auth/me` reads stop waiting after 15 seconds. A request timeout must leave usable Retry controls instead of an endless spinner.
- A transient connection failure during initial Auth verification preserves the existing session. Only missing-session/401/403 outcomes redirect to login. Subscribe to Auth notifications only after successful initial verification; subscribing earlier can wait behind the SDK session lock.
- Login transport failures show a localized accessible alert and preserve typed credentials.
- Workspace GET reads have one 15-second deadline covering all attempts and backoff, combined with caller cancellation. Retry only transport `TypeError` failures, at most three total attempts, after 500 ms then 1000 ms. Timeout/cancel aborts and writes are not retried.

Evidence: `working:src/app/login/page.tsx:42-63,104`; `working:src/components/AppShell.tsx:43-96`; `working:src/contexts/WorkspaceContext.tsx:20-23`; `working:src/lib/supabase-browser.ts:20-22`; `working:src/lib/workspace-fetch.ts:25-48`; `working:tests/e2e/workspace-network.spec.ts:51-128`; `working:tests/unit/workspace.test.ts:133-157`.

Limit: the legacy server route helper still maps every Supabase user lookup error to 401 (`baseline:src/lib/request-context.ts:34-40`). Do not assume the browser fix corrected that server classification.

## SQL dependency boundary

The latest signup bootstrap still inserts a default defect warehouse (`029:34-35`). A future forward migration must remove that insert before removing legacy warehouse tables, while retaining the verified-email safeguards. `032` team functions do not depend on inventory. Keep migration history unchanged; `workspace_settings` and the legacy reset RPC have separate operational-table dependencies. No cleanup migration was created or applied here.
